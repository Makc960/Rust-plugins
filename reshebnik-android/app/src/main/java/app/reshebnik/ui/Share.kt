package app.reshebnik.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import app.reshebnik.R
import app.reshebnik.data.HistoryEntry
import app.reshebnik.math.LatexParser
import app.reshebnik.math.MathRenderer
import app.reshebnik.solver.Block
import app.reshebnik.solver.TaskMode
import app.reshebnik.solver.Verdict
import app.reshebnik.solver.toPlainText
import java.io.File
import java.io.IOException
import kotlin.math.min

/** Решение обычным текстом — для мессенджеров, где формулы картинкой не нужны. */
fun solutionText(entry: HistoryEntry): String = buildString {
    val solution = entry.solution
    val check = entry.mode == TaskMode.CHECK
    if (solution.problem.isNotEmpty()) append("Условие\n").append(solution.problem.toPlainText()).append("\n\n")
    when (solution.verdict) {
        Verdict.CORRECT -> append("Решено верно\n\n")
        Verdict.WRONG -> append("В решении есть ошибка\n\n")
        Verdict.NONE -> Unit
    }
    if (solution.answer.isNotEmpty()) append(if (check) "Итог\n" else "Ответ\n").append(solution.answer.toPlainText()).append("\n\n")
    if (solution.steps.isNotEmpty()) {
        append(if (check) "Разбор\n" else "Решение\n")
        solution.steps.forEachIndexed { index, step ->
            append(index + 1).append(". ").append(step.title).append('\n')
            append(step.body.toPlainText()).append("\n\n")
        }
    }
}.trim()

fun shareText(context: Context, entry: HistoryEntry) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, solutionText(entry))
    launchShare(context, intent)
}

/** Рисует решение картинкой и отдаёт её другим приложениям. Не для главного потока: рисование и запись файла. */
fun writeShareImage(context: Context, entry: HistoryEntry): File? {
    val bitmap = SolutionImage.render(context, entry) ?: return null
    return try {
        val file = File(File(context.cacheDir, "share").apply { mkdirs() }, "reshenie.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file
    } catch (e: IOException) {
        Log.w("Reshebnik", "Cannot write the share image", e)
        null
    } finally {
        bitmap.recycle()
    }
}

fun shareImage(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // По ClipData окно выбора приложения показывает картинку и получает право её прочитать.
    intent.clipData = ClipData.newRawUri("Решение", uri)
    launchShare(context, intent)
}

private fun launchShare(context: Context, intent: Intent) {
    try {
        context.startActivity(Intent.createChooser(intent, "Поделиться решением").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Нет приложения, которому можно отправить решение", Toast.LENGTH_LONG).show()
    }
}

/**
 * Решение одной длинной картинкой на тетрадном листе. Рисуется обычным Canvas, без экрана,
 * поэтому длина картинки не ограничена размером текстуры видеокарты.
 */
object SolutionImage {
    /** Выше этого картинка занимает слишком много памяти. */
    private const val MAX_HEIGHT = 9000

    /**
     * @return null, если решение не уместилось в [MAX_HEIGHT] даже в мелком масштабе
     */
    fun render(context: Context, entry: HistoryEntry): Bitmap? {
        for (scale in floatArrayOf(3f, 2f)) {
            val page = Page(context, scale)
            page.draw(entry)
            if (page.height <= MAX_HEIGHT) return page.toBitmap()
        }
        return null
    }

    /**
     * Лист, на который по очереди кладутся строки. Сначала команды рисования запоминаются
     * (так узнаём высоту), потом воспроизводятся на картинке нужного размера.
     *
     * @param dp сколько пикселей в одной условной точке экрана
     */
    private class Page(private val context: Context, private val dp: Float) {
        private val palette = NotebookPalette
        private val width = (360 * dp).toInt()
        private val left = 22 * dp
        private val contentWidth = width - left - 50 * dp
        private val commands = ArrayList<(Canvas) -> Unit>()
        private var y = 26 * dp

        private val serif = font(R.font.ptserif_regular)
        private val serifBold = font(R.font.ptserif_bold)
        private val serifItalic = font(R.font.ptserif_italic)
        private val sansMedium = font(R.font.onest_medium)
        private val sansSemiBold = font(R.font.onest_semibold)
        private val mathFonts = MathTypefaces.get(context)

        val height: Int get() = (y + 24 * dp).toInt()

        private fun font(id: Int): Typeface = ResourcesCompat.getFont(context, id)!!

        fun draw(entry: HistoryEntry) {
            val solution = entry.solution
            val check = entry.mode == TaskMode.CHECK
            text("Решебник", serifItalic, 20f, palette.ink.toArgb())
            space(14f)
            if (solution.problem.isNotEmpty()) {
                label("Условие")
                blocks(solution.problem, palette.text.toArgb(), serif, 18f, 20f)
            }
            if (solution.verdict != Verdict.NONE) {
                space(16f)
                text(if (solution.verdict == Verdict.CORRECT) "Решено верно ✓" else "В решении есть ошибка", serifItalic, 24f, palette.margin.toArgb())
            }
            if (solution.answer.isNotEmpty()) {
                space(18f)
                label(if (check) "Итог" else "Ответ")
                val top = y
                val widest = blocks(solution.answer, palette.ink.toArgb(), serifBold, 20f, 24f)
                underline(top, widest)
            }
            if (solution.steps.isNotEmpty()) {
                space(24f)
                label(if (check) "Разбор" else "Решение")
                solution.steps.forEachIndexed { index, step ->
                    space(8f)
                    val numberTop = y
                    val indent = 28 * dp
                    text(step.title, sansSemiBold, 16f, palette.text.toArgb(), indent)
                    val number = paint(serifBold, 18f, palette.ink.toArgb())
                    commands += { canvas -> canvas.drawText("${index + 1}.", left, numberTop + 18 * dp, number) }
                    space(2f)
                    blocks(step.body, palette.text.toArgb(), serif, 17f, 20f, indent)
                }
            }
        }

        private fun label(text: String) {
            text(text, sansMedium, 13f, palette.textMuted.toArgb())
            space(6f)
        }

        private fun space(points: Float) {
            y += points * dp
        }

        private fun paint(typeface: Typeface, size: Float, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = size * dp
            this.color = color
        }

        /** @return ширина самой длинной строки */
        private fun text(text: String, typeface: Typeface, size: Float, color: Int, indent: Float = 0f): Float {
            if (text.isBlank()) return 0f
            val available = (contentWidth - indent).toInt()
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint(typeface, size, color), available)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.25f)
                .setIncludePad(false)
                .build()
            val top = y
            commands += { canvas ->
                canvas.save()
                canvas.translate(left + indent, top)
                layout.draw(canvas)
                canvas.restore()
            }
            y += layout.height
            return (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }
        }

        /** @return ширина самого широкого блока */
        private fun blocks(blocks: List<Block>, color: Int, typeface: Typeface, textSize: Float, formulaSize: Float, indent: Float = 0f): Float {
            var widest = 0f
            blocks.forEach { block ->
                space(5f)
                widest = maxOf(
                    widest,
                    when (block) {
                        is Block.Text -> text(block.value, typeface, textSize, color, indent)
                        is Block.Formula -> formula(block.value, formulaSize, color, indent)
                    },
                )
            }
            return widest
        }

        private fun formula(latex: String, size: Float, color: Int, indent: Float): Float {
            val box = MathRenderer(mathFonts, size * dp, color).layout(LatexParser.parse(latex))
            if (box.width <= 0f) return 0f
            val scale = min(1f, (contentWidth - indent) / box.width)
            val top = y + 3 * dp
            commands += { canvas ->
                canvas.save()
                canvas.translate(left + indent, top)
                canvas.scale(scale, scale)
                box.draw(canvas, 0f, box.ascent)
                canvas.restore()
            }
            y = top + box.height * scale + 3 * dp
            return box.width * scale
        }

        /** Двойная черта под ответом. */
        private fun underline(top: Float, lineWidth: Float) {
            if (y <= top) return
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.ink.toArgb()
                strokeWidth = 1.5f * dp
            }
            val first = y + 4 * dp
            val right = left + maxOf(lineWidth, 48 * dp)
            commands += { canvas ->
                canvas.drawLine(left, first, right, first, line)
                canvas.drawLine(left, first + 4 * dp, right, first + 4 * dp, line)
            }
            y = first + 8 * dp
        }

        fun toBitmap(): Bitmap {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(palette.paper.toArgb())
            val grid = Paint().apply {
                color = palette.grid.toArgb()
                strokeWidth = 0.75f * dp
            }
            val cell = 22 * dp
            var x = cell
            while (x < width) {
                canvas.drawLine(x, 0f, x, height.toFloat(), grid)
                x += cell
            }
            var lineY = cell
            while (lineY < height) {
                canvas.drawLine(0f, lineY, width.toFloat(), lineY, grid)
                lineY += cell
            }
            val margin = Paint().apply {
                color = palette.margin.toArgb()
                strokeWidth = 1.25f * dp
            }
            canvas.drawLine(width - 34 * dp, 0f, width - 34 * dp, height.toFloat(), margin)
            commands.forEach { it(canvas) }
            return bitmap
        }
    }
}
