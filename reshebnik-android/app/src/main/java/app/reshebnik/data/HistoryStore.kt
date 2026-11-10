package app.reshebnik.data

import android.content.Context
import android.util.Log
import app.reshebnik.solver.Exchange
import app.reshebnik.solver.JsonWriter
import app.reshebnik.solver.LenientJson
import app.reshebnik.solver.Solution
import app.reshebnik.solver.SolutionJson
import app.reshebnik.solver.TaskMode
import java.io.File
import java.io.IOException

/**
 * Решённое задание.
 *
 * @param text уточнение к фото, а без фото — само условие
 * @param hasPhoto есть ли у записи снимок (лежит отдельным файлом)
 */
data class HistoryEntry(
    val id: String,
    val createdAt: Long,
    val mode: TaskMode,
    val text: String,
    val hasPhoto: Boolean,
    val solution: Solution,
    val exchanges: List<Exchange> = emptyList(),
)

/**
 * История решений на диске: по папке на запись, в ней `entry.json` и, если был снимок, `photo.jpg`.
 * Методы читают и пишут файлы — вызывать не из главного потока.
 */
class HistoryStore(private val root: File) {
    constructor(context: Context) : this(File(context.filesDir, "history"))

    /** Записи от новых к старым. Повреждённые записи пропускаются. */
    fun list(): List<HistoryEntry> =
        root.listFiles().orEmpty()
            .mapNotNull { dir -> read(File(dir, ENTRY_FILE)) }
            .sortedByDescending { it.createdAt }

    /** @param photoJpeg снимок в том виде, в каком он ушёл нейросети; нужен для вопросов по решению */
    @Throws(IOException::class)
    fun save(entry: HistoryEntry, photoJpeg: ByteArray?) {
        val dir = File(root, entry.id)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        if (photoJpeg != null) File(dir, PHOTO_FILE).writeBytes(photoJpeg)
        write(dir, entry)
        trim()
    }

    /** Переписывает запись, не трогая снимок: после нового вопроса по решению. */
    @Throws(IOException::class)
    fun update(entry: HistoryEntry) {
        val dir = File(root, entry.id)
        if (dir.isDirectory) write(dir, entry)
    }

    fun photoFile(id: String): File = File(File(root, id), PHOTO_FILE)

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    fun clear() {
        root.deleteRecursively()
    }

    private fun write(dir: File, entry: HistoryEntry) {
        val json = JsonWriter.write(
            linkedMapOf(
                "id" to entry.id,
                "createdAt" to entry.createdAt,
                "mode" to entry.mode.name,
                "text" to entry.text,
                "hasPhoto" to entry.hasPhoto,
                "solution" to SolutionJson.toMap(entry.solution),
                "exchanges" to entry.exchanges.map {
                    linkedMapOf("question" to it.question, "answer" to SolutionJson.toList(it.answer))
                },
            ),
        )
        // Сначала во временный файл: если приложение убьют посреди записи, старая версия уцелеет.
        val temp = File(dir, "$ENTRY_FILE.tmp")
        temp.writeText(json)
        if (!temp.renameTo(File(dir, ENTRY_FILE))) throw IOException("Cannot replace entry in $dir")
    }

    private fun read(file: File): HistoryEntry? {
        if (!file.isFile) return null
        return try {
            val map = LenientJson.parse(file.readText()) as? Map<*, *> ?: return null
            HistoryEntry(
                id = map["id"] as? String ?: return null,
                createdAt = (map["createdAt"] as? Double)?.toLong() ?: return null,
                mode = if (map["mode"] == TaskMode.CHECK.name) TaskMode.CHECK else TaskMode.SOLVE,
                text = (map["text"] as? String).orEmpty(),
                hasPhoto = map["hasPhoto"] == true,
                solution = SolutionJson.solutionFrom(map["solution"]) ?: return null,
                exchanges = (map["exchanges"] as? List<*>).orEmpty().mapNotNull { item ->
                    val exchange = item as? Map<*, *> ?: return@mapNotNull null
                    Exchange((exchange["question"] as? String).orEmpty(), SolutionJson.blocksFrom(exchange["answer"]))
                },
            )
        } catch (e: IOException) {
            Log.w("Reshebnik", "Cannot read $file", e)
            null
        }
    }

    /** Держим не больше [MAX_ENTRIES] записей: самые старые удаляются. */
    private fun trim() {
        val entries = list()
        if (entries.size > MAX_ENTRIES) entries.drop(MAX_ENTRIES).forEach { delete(it.id) }
    }

    companion object {
        const val MAX_ENTRIES = 200
        private const val ENTRY_FILE = "entry.json"
        private const val PHOTO_FILE = "photo.jpg"

        fun newId(createdAt: Long): String = createdAt.toString(36) + "-" + (0..0xFFFF).random().toString(36)
    }
}
