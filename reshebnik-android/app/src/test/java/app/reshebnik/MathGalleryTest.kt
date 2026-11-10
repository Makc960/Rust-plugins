package app.reshebnik

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.reshebnik.ui.LocalPalette
import app.reshebnik.ui.MathFormula
import app.reshebnik.ui.ReshebnikTheme
import app.reshebnik.ui.notebookPaper
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Формулы на одном листе — чтобы посмотреть на набор глазами (app/build/screenshots/math_*.png). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h1400dp-xxhdpi")
class MathGalleryTest {
    @get:Rule
    val compose = createComposeRule()

    private fun gallery(name: String, dark: Boolean, formulas: List<String>) {
        compose.setContent {
            ReshebnikTheme(dark) {
                Column(
                    Modifier.fillMaxWidth().notebookPaper(LocalPalette.current).padding(start = 22.dp, end = 48.dp, top = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    formulas.forEach { MathFormula(it) }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test
    fun school() = gallery(
        "math_1_school", dark = false,
        listOf(
            """x^2 - 5x + 6 = 0""",
            """D = b^2 - 4ac = (-5)^2 - 4 \cdot 1 \cdot 6 = 1""",
            """x_{1,2} = \frac{-b \pm \sqrt{D}}{2a} = \frac{5 \pm 1}{2}""",
            """\frac{3}{4} + \frac{5}{6} = \frac{9 + 10}{12} = \frac{19}{12} = 1\frac{7}{12}""",
            """\sqrt[3]{27} + \sqrt{\frac{a^2 + b^2}{2}} \ge \sqrt{ab}""",
            """\begin{cases} 2x + 3y = 7 \\ x - y = 1 \end{cases} \Rightarrow \begin{cases} x = 2 \\ y = 1 \end{cases}""",
            """\sin^2 \alpha + \cos^2 \alpha = 1, \quad \tg \alpha = \frac{\sin \alpha}{\cos \alpha}""",
            """S = \frac{1}{2} a h = \frac{1}{2} \cdot 6 \cdot 4 = 12 \text{ см}^2""",
            """\log_2 8 = 3, \quad \lg 0{,}01 = -2, \quad 30^\circ = \frac{\pi}{6}""",
            """|x - 3| \le 5 \iff -2 \le x \le 8, \quad x \in [-2; 8]""",
            """\left( \frac{a}{b} \right)^{n} = \frac{a^n}{b^n}, \quad a^{-n} = \frac{1}{a^n}, \quad e^{x^2}""",
            """v = \frac{s}{t} = \frac{120 \text{ км}}{1{,}5 \text{ ч}} = 80 \text{ км/ч}""",
        ),
    )

    @Test
    fun advanced() = gallery(
        "math_2_advanced", dark = true,
        listOf(
            """\int_0^1 x^2 \, dx = \left. \frac{x^3}{3} \right|_0^1 = \frac{1}{3}""",
            """\sum_{k=1}^{n} k = \frac{n(n+1)}{2}, \quad \prod_{i=1}^{n} i = n!""",
            """\lim_{x \to 0} \frac{\sin x}{x} = 1, \quad \lim_{n \to \infty} \left(1 + \frac{1}{n}\right)^n = e""",
            """f'(x) = \frac{d}{dx}\left( x^3 \ln x \right) = 3x^2 \ln x + x^2""",
            """\begin{pmatrix} 1 & 2 \\ 3 & 4 \end{pmatrix} \cdot \begin{pmatrix} x \\ y \end{pmatrix} = \begin{pmatrix} 5 \\ 11 \end{pmatrix}, \quad \begin{vmatrix} a & b \\ c & d \end{vmatrix} = ad - bc""",
            """\begin{aligned} (a + b)^2 &= a^2 + 2ab + b^2 \\ a^2 - b^2 &= (a - b)(a + b) \end{aligned}""",
            """\vec{a} \cdot \vec{b} = |\vec{a}| |\vec{b}| \cos \varphi, \quad \overline{AB} = 5, \quad \hat{p} = 0{,}4""",
            """E = mc^2, \quad F = G \frac{m_1 m_2}{r^2}, \quad \Delta t = \frac{t_0}{\sqrt{1 - \frac{v^2}{c^2}}}""",
            """f(x) = \begin{cases} x^2, & x \ge 0 \\ -x, & x < 0 \end{cases}""",
            """\frac{\frac{1}{2} + \frac{1}{3}}{1 - \frac{1}{6}} = 1, \quad x^{y^z}, \quad a_{n_k}, \quad \sqrt{2 + \sqrt{2 + \sqrt{2}}}""",
            """a_1 + a_2 + \ldots + a_n = \frac{a_1 + a_n}{2} \cdot n, \quad C_n^k = \frac{n!}{k!(n-k)!}, \quad \binom{n}{k}""",
            """\left\{ \frac{x^2}{a^2} + \frac{y^2}{b^2} = 1 \right\}, \quad \left[ \frac{1}{2}; 3 \right), \quad \left\langle \frac{a}{b} \right\rangle, \quad \left\lfloor \frac{7}{2} \right\rfloor = 3""",
            """\text{Очень длинная формула: } 1 + 2 + 3 + 4 + 5 + 6 + 7 + 8 + 9 + 10 + 11 + 12 + 13 + 14 + 15 + 16 + 17 = 153""",
            """\frac{x + 1}{x - 1 \quad \text{оборванная формула \sqrt{2""",
        ),
    )
}
