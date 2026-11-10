package app.reshebnik

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import app.reshebnik.calc.CalcKey
import app.reshebnik.calc.CalcState
import app.reshebnik.data.CalcRecord
import app.reshebnik.data.HistoryEntry
import app.reshebnik.solver.Asking
import app.reshebnik.solver.Block
import app.reshebnik.solver.CropArea
import app.reshebnik.solver.Draft
import app.reshebnik.solver.Exchange
import app.reshebnik.solver.Quality
import app.reshebnik.solver.Solution
import app.reshebnik.solver.SolutionStep
import app.reshebnik.solver.SolveError
import app.reshebnik.solver.SolverUiState
import app.reshebnik.solver.TaskMode
import app.reshebnik.solver.Verdict
import app.reshebnik.ui.AppScaffold
import app.reshebnik.ui.CalculatorScreen
import app.reshebnik.ui.HistoryScreen
import app.reshebnik.ui.LocalPalette
import app.reshebnik.ui.ReshebnikTheme
import app.reshebnik.ui.SettingsContent
import app.reshebnik.ui.SolutionImage
import app.reshebnik.ui.SolverActions
import app.reshebnik.ui.SolverScreen
import app.reshebnik.ui.Tab
import app.reshebnik.ui.solutionText
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val PHONE = "w412dp-h892dp-xxhdpi"
private const val SMALL_PHONE = "w360dp-h640dp-xhdpi"
private const val LANDSCAPE = "w892dp-h412dp-land-xxhdpi"
private const val SMALL_LANDSCAPE = "w640dp-h360dp-land-xhdpi"

/** Снимки всех экранов в app/build/screenshots — чтобы посмотреть на вёрстку без телефона. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { ReshebnikTheme(dark) { content() } }
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private fun typed(text: String, from: CalcState = CalcState()) = text.fold(from) { state, c ->
        state.press(
            when (c) {
                ',' -> CalcKey.Comma
                '(' -> CalcKey.Parenthesis
                '%' -> CalcKey.Percent
                '=' -> CalcKey.Equals
                '√' -> CalcKey.Sqrt
                in "+-*/^" -> CalcKey.Operator(c)
                else -> CalcKey.Digit(c)
            },
        )
    }

    private val tape = listOf(
        CalcRecord("1250*(3+4.5)-10%", "8437.5"),
        CalcRecord("2^10", "1024"),
        CalcRecord("sin(30)+cos(60)", "1"),
        CalcRecord("12500/8+375", "1937.5"),
    )

    @Composable
    private fun Calculator(state: CalcState, records: List<CalcRecord> = tape) {
        AppScaffold(Tab.Calculator, onTabChange = {}) { _, landscape ->
            CalculatorScreen(state, records, landscape, onKey = {}, onInsert = {}, onClearHistory = {}, onOpenSettings = {})
        }
    }

    @Composable
    private fun Solver(state: SolverUiState, hasKey: Boolean = true, quality: Quality = Quality.PRECISE, cameraDenied: Boolean = false) {
        AppScaffold(Tab.Solver, onTabChange = {}) { _, _ ->
            SolverScreen(state, hasKey, quality, cameraDenied, SolverActions())
        }
    }

    private val solution = Solution(
        solved = true,
        verdict = Verdict.NONE,
        problem = listOf(Block.Text("Решите уравнение:"), Block.Formula("x^2 - 5x + 6 = 0")),
        answer = listOf(Block.Formula("x_1 = 2, \\quad x_2 = 3")),
        steps = listOf(
            SolutionStep(
                "Находим дискриминант",
                listOf(Block.Text("Уравнение квадратное: a = 1, b = −5, c = 6."), Block.Formula("D = b^2 - 4ac = (-5)^2 - 4 \\cdot 1 \\cdot 6 = 1")),
            ),
            SolutionStep(
                "Считаем корни",
                listOf(Block.Formula("x_{1,2} = \\frac{-b \\pm \\sqrt{D}}{2a} = \\frac{5 \\pm 1}{2}"), Block.Formula("x_1 = 2, \\quad x_2 = 3")),
            ),
            SolutionStep(
                "Проверяем подстановкой",
                listOf(Block.Formula("2^2 - 5 \\cdot 2 + 6 = 0"), Block.Formula("3^2 - 5 \\cdot 3 + 6 = 0"), Block.Text("Оба корня подходят.")),
            ),
        ),
    )

    private val checked = Solution(
        solved = true,
        verdict = Verdict.WRONG,
        problem = listOf(Block.Text("Найдите корни уравнения:"), Block.Formula("\\frac{x + 1}{2} = \\frac{x}{3} + 1")),
        answer = listOf(Block.Text("Ошибка во второй строке: правую часть не умножили на 6 целиком."), Block.Formula("x = 3")),
        steps = listOf(
            SolutionStep("Что сделано верно", listOf(Block.Text("Умножение на общий знаменатель 6 выбрано правильно."))),
            SolutionStep(
                "Где ошибка",
                listOf(Block.Text("У тебя получилось:"), Block.Formula("3(x + 1) = 2x + 1"), Block.Text("А должно быть:"), Block.Formula("3(x + 1) = 2x + 6")),
            ),
        ),
    )

    private fun entry(solution: Solution = this.solution, mode: TaskMode = TaskMode.SOLVE, exchanges: List<Exchange> = emptyList(), id: String = "1") =
        HistoryEntry(id, 1_794_300_000_000 - id.hashCode() * 3_600_000L, mode, "", hasPhoto = true, solution = solution, exchanges = exchanges)

    private val exchange = Exchange(
        "Объясни подробнее шаг 2: «Считаем корни»",
        listOf(Block.Text("Подставляем коэффициенты в формулу корней. Корень из D равен 1, поэтому:"), Block.Formula("x = \\frac{5 + 1}{2} = 3 \\quad \\text{или} \\quad x = \\frac{5 - 1}{2} = 2")),
    )

    /** Имитация снимка: лист с написанным уравнением. */
    private fun photo(width: Int = 1200, height: Int = 900): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(236, 232, 222))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 40, 90); textSize = 96f }
        canvas.drawText("№ 3.  x² − 5x + 6 = 0", 90f, height * 0.45f, paint)
        canvas.drawText("№ 4.  2x + 6 = 14", 90f, height * 0.75f, paint)
        return bitmap
    }

    // ---- Калькулятор ------------------------------------------------------------------------

    @Test fun calculator_empty() = capture("01_calculator_empty") { Calculator(CalcState(), emptyList()) }

    @Test fun calculator_tape() = capture("02_calculator_tape") { Calculator(typed("1250*(3+4,5(-10%"), tape.drop(1)) }

    @Test fun calculator_result() = capture("03_calculator_result") { Calculator(typed("12500/8+375=")) }

    @Test fun calculator_error_dark() = capture("04_calculator_error_dark", dark = true) { Calculator(typed("15/0=")) }

    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun calculator_small() = capture("05_calculator_small") { Calculator(typed("123456789*987654321+55555/3333")) }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun calculator_landscape() = capture("06_calculator_landscape") {
        Calculator(typed("30(+2", CalcState().press(CalcKey.Function("sin"))))
    }

    @Test
    @Config(qualifiers = SMALL_LANDSCAPE)
    fun calculator_landscape_small_dark() = capture("07_calculator_landscape_small_dark", dark = true) {
        Calculator(typed("12500/8+375=").press(CalcKey.AngleUnit))
    }

    // ---- По фото ----------------------------------------------------------------------------

    @Test fun solver_idle() = capture("10_solver_idle") { Solver(SolverUiState.Idle) }

    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun solver_idle_notices() = capture("11_solver_idle_notices") { Solver(SolverUiState.Idle, hasKey = false, cameraDenied = true) }

    @Test
    fun solver_editing() = capture("12_solver_editing") {
        Solver(SolverUiState.Editing(Draft(photo(), CropArea(0.04f, 0.22f, 0.96f, 0.58f), "", TaskMode.SOLVE)))
    }

    @Test
    fun solver_editing_check_dark() = capture("13_solver_editing_check_dark", dark = true) {
        Solver(SolverUiState.Editing(Draft(photo(900, 1500), CropArea.FULL, "только номер 3", TaskMode.CHECK)), quality = Quality.FAST)
    }

    @Test
    fun solver_editing_text() = capture("14_solver_editing_text") {
        Solver(SolverUiState.Editing(Draft(null, text = "Найдите площадь треугольника со сторонами 3, 4 и 5 см.")))
    }

    @Test fun solver_thinking() = capture("15_solver_thinking") { Solver(SolverUiState.Solving(Draft(photo()), photo(), null)) }

    @Test
    fun solver_writing() = capture("16_solver_writing") {
        Solver(SolverUiState.Solving(Draft(photo()), photo(1200, 330), solution.copy(steps = solution.steps.take(1))))
    }

    @Test fun solver_solved() = capture("17_solver_solved") { Solver(SolverUiState.Solved(entry(), photo(1200, 330))) }

    @Test fun solver_solved_dark() = capture("18_solver_solved_dark", dark = true) { Solver(SolverUiState.Solved(entry(), null)) }

    @Test
    fun solver_checked() = capture("19_solver_checked") { Solver(SolverUiState.Solved(entry(checked, TaskMode.CHECK), null)) }

    @Test
    fun solver_questions() = capture("20_solver_questions") {
        val short = solution.copy(steps = solution.steps.take(1))
        Solver(SolverUiState.Solved(entry(short, exchanges = listOf(exchange)), null, Asking("А если D меньше нуля?", listOf(Block.Text("Тогда действительных корней нет:")))))
    }

    @Test
    fun solver_question_failed_dark() = capture("21_solver_question_failed_dark", dark = true) {
        val short = solution.copy(steps = emptyList())
        Solver(SolverUiState.Solved(entry(short), null, Asking("Почему так?", error = SolveError.NETWORK)))
    }

    @Test
    fun solver_no_task() = capture("22_solver_no_task") {
        val empty = Solution(false, Verdict.NONE, listOf(Block.Text("На фото размытый текст. Сними ближе и без бликов.")), emptyList(), emptyList())
        Solver(SolverUiState.Solved(entry(empty), null))
    }

    @Test fun solver_failed() = capture("23_solver_failed") { Solver(SolverUiState.Failed(Draft(photo()), SolveError.NETWORK, null)) }

    @Test
    fun solver_failed_key_dark() = capture("24_solver_failed_key_dark", dark = true) {
        Solver(SolverUiState.Failed(Draft(photo()), SolveError.INVALID_KEY, null))
    }

    // ---- История и настройки ----------------------------------------------------------------

    @Composable
    private fun History(entries: List<HistoryEntry>) {
        AppScaffold(Tab.History, onTabChange = {}) { _, _ ->
            HistoryScreen(entries, loadThumbnail = { id -> if (id == "3") null else photo(300, 300) }, onOpen = {}, onDelete = {}, onClear = {})
        }
    }

    private val entries = listOf(
        entry(id = "1"),
        entry(checked, TaskMode.CHECK, id = "2"),
        entry(solution.copy(problem = listOf(Block.Text("Найдите площадь треугольника со сторонами 3, 4 и 5 см.")), answer = listOf(Block.Formula("S = 6 \\text{ см}^2"))), id = "3")
            .copy(hasPhoto = false),
    )

    @Test fun history() = capture("30_history") { History(entries) }

    @Test fun history_dark() = capture("31_history_dark", dark = true) { History(entries) }

    @Test fun history_empty() = capture("32_history_empty") { History(emptyList()) }

    @Test
    fun settings() = capture("33_settings") {
        Box(Modifier.fillMaxSize().background(LocalPalette.current.paper), contentAlignment = Alignment.BottomCenter) {
            SettingsContent(apiKey = "sk-ant-api03-example", onSave = {}, onDismiss = {})
        }
    }

    // ---- «Поделиться» -----------------------------------------------------------------------

    @Test
    fun share_image_and_text() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for ((name, shared) in listOf("40_share_solved" to entry(), "41_share_checked" to entry(checked, TaskMode.CHECK))) {
            val bitmap = SolutionImage.render(context, shared)!!
            assertTrue(bitmap.width == 1080 && bitmap.height > 600)
            File("build/screenshots").mkdirs()
            File("build/screenshots/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val text = solutionText(entry())
        assertTrue(text, text.startsWith("Условие\nРешите уравнение:\nx² − 5x + 6 = 0\n\nОтвет\nx₁ = 2, x₂ = 3\n\nРешение\n1. Находим дискриминант\n"))
        assertTrue(text, text.contains("x_(1,2) = (−b ± √D)/(2a) = (5 ± 1)/2"))
    }
}
