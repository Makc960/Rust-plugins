package app.reshebnik

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.reshebnik.data.CalcHistoryStore
import app.reshebnik.data.CalcRecord
import app.reshebnik.data.HistoryEntry
import app.reshebnik.data.HistoryStore
import app.reshebnik.data.SettingsStore
import app.reshebnik.solver.Block
import app.reshebnik.solver.Exchange
import app.reshebnik.solver.Quality
import app.reshebnik.solver.Solution
import app.reshebnik.solver.SolutionStep
import app.reshebnik.solver.TaskMode
import app.reshebnik.solver.Verdict
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StorageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun entry(id: String, createdAt: Long, exchanges: List<Exchange> = emptyList()) = HistoryEntry(
        id = id,
        createdAt = createdAt,
        mode = TaskMode.CHECK,
        text = "только № 3",
        hasPhoto = true,
        solution = Solution(
            solved = true,
            verdict = Verdict.WRONG,
            problem = listOf(Block.Text("Реши \"уравнение\""), Block.Formula("\\frac{x}{2} = 1")),
            answer = listOf(Block.Formula("x = 2")),
            steps = listOf(SolutionStep("Умножаем на 2", listOf(Block.Formula("x = 2")))),
        ),
        exchanges = exchanges,
    )

    @Test
    fun `history keeps entries with photos, newest first`() {
        val store = HistoryStore(folder.newFolder("history"))
        val photo = byteArrayOf(1, 2, 3)
        store.save(entry("a", 1000), photo)
        store.save(entry("b", 3000), null)
        store.save(entry("c", 2000), photo)

        // Новый объект читает с диска — как после перезапуска приложения.
        val reopened = HistoryStore(folder.root.resolve("history"))
        assertEquals(listOf("b", "c", "a"), reopened.list().map { it.id })
        assertEquals(entry("a", 1000), reopened.list().last())
        assertArrayEquals(photo, reopened.photoFile("a").readBytes())
        assertFalse(reopened.photoFile("b").exists())
    }

    @Test
    fun `history entry is updated and deleted`() {
        val store = HistoryStore(folder.newFolder("history"))
        store.save(entry("a", 1000), byteArrayOf(9))
        val asked = entry("a", 1000, listOf(Exchange("Почему?", listOf(Block.Text("Потому что."), Block.Formula("a^2")))))

        store.update(asked)

        assertEquals(asked, store.list().single())
        assertTrue(store.photoFile("a").exists())

        store.update(entry("missing", 5))
        assertEquals(1, store.list().size)

        store.delete("a")
        assertTrue(store.list().isEmpty())
        assertFalse(store.photoFile("a").exists())
    }

    @Test
    fun `broken entries are skipped and clear removes everything`() {
        val root = folder.newFolder("history")
        val store = HistoryStore(root)
        store.save(entry("good", 1000), null)
        File(root, "broken").mkdirs()
        File(root, "broken/entry.json").writeText("{\"id\": \"broken\", \"createdAt\"")
        File(root, "empty").mkdirs()

        assertEquals(listOf("good"), store.list().map { it.id })

        store.clear()
        assertTrue(store.list().isEmpty())
        store.save(entry("again", 1), null)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `history is capped`() {
        val store = HistoryStore(folder.newFolder("history"))
        repeat(HistoryStore.MAX_ENTRIES + 3) { index -> store.save(entry("e$index", index.toLong()), null) }

        val ids = store.list().map { it.id }
        assertEquals(HistoryStore.MAX_ENTRIES, ids.size)
        assertEquals("e${HistoryStore.MAX_ENTRIES + 2}", ids.first())
        assertFalse("e0" in ids)
    }

    @Test
    fun `settings keep the key and the quality`() {
        val settings = SettingsStore(context)
        assertEquals("", settings.apiKey.value)
        assertEquals(Quality.PRECISE, settings.quality.value)

        settings.setApiKey("  sk-ant-secret  ")
        settings.setQuality(Quality.FAST)

        val reopened = SettingsStore(context)
        assertEquals("sk-ant-secret", reopened.apiKey.value)
        assertEquals(Quality.FAST, reopened.quality.value)

        reopened.setApiKey("")
        assertEquals("", SettingsStore(context).apiKey.value)
    }

    @Test
    fun `key saved by version 1 is picked up`() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("claude_api_key", "sk-ant-old").commit()

        assertEquals("sk-ant-old", SettingsStore(context).apiKey.value)
        assertEquals("sk-ant-old", SettingsStore(context).apiKey.value)
    }

    @Test
    fun `calculator tape keeps the last records`() {
        val tape = CalcHistoryStore(context)
        tape.add(CalcRecord("2+2", "4"))
        tape.add(CalcRecord("2+2", "4"))
        tape.add(CalcRecord("10/4", "2.5"))

        assertEquals(listOf(CalcRecord("2+2", "4"), CalcRecord("10/4", "2.5")), CalcHistoryStore(context).records.value)

        repeat(60) { tape.add(CalcRecord("$it+1", "${it + 1}")) }
        assertEquals(50, CalcHistoryStore(context).records.value.size)
        assertEquals(CalcRecord("59+1", "60"), CalcHistoryStore(context).records.value.last())

        tape.clear()
        assertTrue(CalcHistoryStore(context).records.value.isEmpty())
    }
}
