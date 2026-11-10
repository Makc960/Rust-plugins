package app.reshebnik

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
import app.reshebnik.calc.CalcKey
import app.reshebnik.calc.CalcState
import app.reshebnik.solver.Solution
import app.reshebnik.solver.SolutionStep
import app.reshebnik.solver.SolveError
import app.reshebnik.solver.SolverUiState
import app.reshebnik.ui.AppScaffold
import app.reshebnik.ui.CalculatorScreen
import app.reshebnik.ui.LocalPalette
import app.reshebnik.ui.ReshebnikTheme
import app.reshebnik.ui.SettingsContent
import app.reshebnik.ui.SolverScreen
import app.reshebnik.ui.Tab
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val PHONE = "w412dp-h892dp-xxhdpi"
private const val SMALL_PHONE = "w360dp-h640dp-xhdpi"

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

    private fun typed(vararg keys: CalcKey) = keys.fold(CalcState()) { state, key -> state.press(key) }

    private fun digits(text: String) = text.map { c ->
        when (c) {
            ',' -> CalcKey.Comma
            '(' -> CalcKey.Parenthesis
            '%' -> CalcKey.Percent
            '=' -> CalcKey.Equals
            '√' -> CalcKey.Sqrt
            in "+-*/^" -> CalcKey.Operator(c)
            else -> CalcKey.Digit(c)
        }
    }.toTypedArray()

    @Composable
    private fun Calculator(state: CalcState) {
        AppScaffold(Tab.Calculator, onTabChange = {}) {
            CalculatorScreen(state, onKey = {}, onOpenSettings = {})
        }
    }

    @Composable
    private fun Solver(state: SolverUiState, hasKey: Boolean = true, hint: String = "") {
        AppScaffold(Tab.Solver, onTabChange = {}) {
            SolverScreen(state, hasKey, hint, {}, {}, {}, {}, {}, {}, {})
        }
    }

    private val expression = typed(*digits("1250*(3+4,5(-10%"))
    private val result = typed(*digits("12500/8+375="))

    private val solution = Solution(
        solved = true,
        problem = "Решите уравнение: x² − 5x + 6 = 0",
        answer = "x₁ = 2, x₂ = 3",
        steps = listOf(
            SolutionStep("Находим дискриминант", "D = b² − 4ac\nD = (−5)² − 4·1·6 = 25 − 24 = 1"),
            SolutionStep("Считаем корни", "x = (−b ± √D) / 2a\nx₁ = (5 − 1) / 2 = 2\nx₂ = (5 + 1) / 2 = 3"),
            SolutionStep("Проверяем подстановкой", "2² − 5·2 + 6 = 0 ✓\n3² − 5·3 + 6 = 0 ✓"),
        ),
    )

    /** Имитация снимка: лист с написанным уравнением. */
    private fun photo(): Bitmap {
        val bitmap = Bitmap.createBitmap(1200, 700, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(236, 232, 222))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 40, 90); textSize = 110f }
        canvas.drawText("x² − 5x + 6 = 0", 120f, 380f, paint)
        return bitmap
    }

    @Test fun calculator_empty() = capture("01_calculator_empty") { Calculator(CalcState()) }

    @Test fun calculator_expression() = capture("02_calculator_expression") { Calculator(expression) }

    @Test fun calculator_result() = capture("03_calculator_result") { Calculator(result) }

    @Test fun calculator_error() = capture("04_calculator_error") { Calculator(typed(*digits("15/0="))) }

    @Test fun calculator_dark() = capture("05_calculator_dark", dark = true) { Calculator(result) }

    @Test fun calculator_long() = capture("06_calculator_long") { Calculator(typed(*digits("123456789*987654321+55555/3333-(77777+88888(*99999"))) }

    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun calculator_small() = capture("07_calculator_small") { Calculator(expression) }

    @Test fun solver_idle() = capture("10_solver_idle") { Solver(SolverUiState.Idle) }

    @Test fun solver_idle_no_key() = capture("11_solver_idle_no_key") { Solver(SolverUiState.Idle, hasKey = false) }

    @Test fun solver_idle_dark() = capture("12_solver_idle_dark", dark = true) { Solver(SolverUiState.Idle) }

    @Test fun solver_preview() = capture("13_solver_preview") { Solver(SolverUiState.Preview(photo()), hint = "только корни") }

    @Test fun solver_solving() = capture("14_solver_solving") { Solver(SolverUiState.Solving(photo())) }

    @Test fun solver_solved() = capture("15_solver_solved") { Solver(SolverUiState.Solved(photo(), solution)) }

    @Test fun solver_solved_dark() = capture("16_solver_solved_dark", dark = true) { Solver(SolverUiState.Solved(photo(), solution)) }

    @Test
    fun solver_no_task() = capture("17_solver_no_task") {
        Solver(SolverUiState.Solved(photo(), Solution(false, "На фото размытый текст. Сними ближе и без бликов.", "", emptyList())))
    }

    @Test fun solver_failed() = capture("18_solver_failed") { Solver(SolverUiState.Failed(photo(), SolveError.NETWORK, null)) }

    @Test fun solver_failed_key() = capture("19_solver_failed_key", dark = true) { Solver(SolverUiState.Failed(photo(), SolveError.INVALID_KEY, null)) }

    @Test
    @Config(qualifiers = SMALL_PHONE)
    fun solver_idle_small() = capture("20_solver_idle_small") { Solver(SolverUiState.Idle, hasKey = false) }

    @Test
    fun settings() = capture("30_settings") {
        Box(Modifier.fillMaxSize().background(LocalPalette.current.paper), contentAlignment = Alignment.BottomCenter) {
            SettingsContent(apiKey = "sk-ant-api03-example", onSave = {}, onDismiss = {})
        }
    }

    @Test
    fun settings_dark() = capture("31_settings_dark", dark = true) {
        Box(Modifier.fillMaxSize().background(LocalPalette.current.paper), contentAlignment = Alignment.BottomCenter) {
            SettingsContent(apiKey = "", onSave = {}, onDismiss = {})
        }
    }
}
