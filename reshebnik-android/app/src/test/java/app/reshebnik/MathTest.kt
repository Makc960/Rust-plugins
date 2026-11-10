package app.reshebnik

import app.reshebnik.math.Kind
import app.reshebnik.math.LatexParser
import app.reshebnik.math.MathNode
import app.reshebnik.math.MathPlain
import app.reshebnik.math.TableLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathTest {
    private fun plain(latex: String) = MathPlain.of(latex)

    @Test
    fun `fractions roots and powers read as plain text`() {
        assertEquals("x² − 5x + 6 = 0", plain("x^2 - 5x + 6 = 0"))
        assertEquals("x_(1,2) = (−b ± √D)/(2a)", plain("""x_{1,2} = \frac{-b \pm \sqrt{D}}{2a}"""))
        assertEquals("3/4 + 5/6 = 19/12", plain("""\frac{3}{4} + \frac{5}{6} = \frac{19}{12}"""))
        assertEquals("∛27 + √(a² + b²)", plain("""\sqrt[3]{27} + \sqrt{a^2 + b^2}"""))
        assertEquals("e^(x²)", plain("e^{x^2}"))
        assertEquals("a⁻ⁿ = 1/aⁿ", plain("""a^{-n} = \frac{1}{a^n}"""))
        assertEquals("30° = π/6", plain("""30^\circ = \frac{\pi}{6}"""))
        assertEquals("0,5 + 1,25", plain("0{,}5 + 1.25"))
    }

    @Test
    fun `functions limits and systems read as plain text`() {
        assertEquals("sin² α + cos² α = 1", plain("""\sin^2 \alpha + \cos^2 \alpha = 1"""))
        assertEquals("log₂ 8 = 3", plain("""\log_2 8 = 3"""))
        assertEquals("sin(x + 1)", plain("""\sin(x + 1)"""))
        assertEquals("{2x + 3y = 7; x − y = 1", plain("""\begin{cases} 2x + 3y = 7 \\ x - y = 1 \end{cases}"""))
        assertEquals("(a/b)ⁿ", plain("""\left( \frac{a}{b} \right)^{n}"""))
        assertEquals("S = 12 см²", plain("""S = 12 \text{ см}^2"""))
        assertEquals("|x − 3| ≤ 5 ⇔ −2 ≤ x ≤ 8", plain("""|x - 3| \le 5 \iff -2 \le x \le 8"""))
    }

    @Test
    fun `dollar wrappers are stripped`() {
        assertEquals("x = 1", plain("\$\$ x = 1 \$\$"))
        assertEquals("x = 1", plain("\\( x = 1 \\)"))
        assertEquals("x = 1", plain("\$x = 1\$"))
    }

    @Test
    fun `structure is recognised`() {
        val fraction = LatexParser.parse("""\frac12""") as MathNode.Fraction
        assertEquals(MathNode.Symbol("1", Kind.NUMBER), fraction.numerator)
        assertEquals(MathNode.Symbol("2", Kind.NUMBER), fraction.denominator)

        val cases = LatexParser.parse("""\begin{cases} x^2, & x \ge 0 \\ -x, & x < 0 \end{cases}""") as MathNode.Table
        assertEquals(TableLayout.CASES, cases.layout)
        assertEquals("{", cases.open)
        assertEquals(listOf(2, 2), cases.rows.map { it.size })

        val aligned = LatexParser.parse("""\begin{aligned} a &= b \\ c &= d \end{aligned}""") as MathNode.Table
        assertEquals(TableLayout.ALIGNED, aligned.layout)

        val lines = LatexParser.parse("""a = 1 \\ b = 2""") as MathNode.Table
        assertEquals(TableLayout.LINES, lines.layout)
        assertEquals(2, lines.rows.size)

        val sum = LatexParser.parse("""\sum_{k=1}^{n} k""") as MathNode.Row
        val scripts = sum.items[0] as MathNode.Scripts
        assertEquals(MathNode.Symbol("∑", Kind.LARGE_OPERATOR), scripts.base)

        val limit = (LatexParser.parse("""\lim_{x \to 0} f""") as MathNode.Row).items[0] as MathNode.Scripts
        assertTrue((limit.base as MathNode.Symbol).limits)
    }

    @Test
    fun `broken input never throws`() {
        val broken = listOf(
            "", "{", "}", "\\", "\\frac", "\\frac{1", "\\frac{1}{", "\\sqrt[3", "x^", "x_", "^2", "\\left(", "\\right)",
            "\\begin{cases} x", "\\begin{", "\\end{cases}", "a & b \\\\ c", "\\unknowncommand{x}", "\\text{незакрытый", "$", "$$",
            "\\left\\{ \\frac{a}{b} \\right", "x'''", "\\not", "\\mathbb{R", "{{{{", "}}}}", "\\\\\\\\", "a \\\\[", "\\begin{array}{cc} 1 & 2",
        )
        for (latex in broken) {
            LatexParser.parse(latex)
            MathPlain.of(latex)
        }
        // И любой обрывок настоящей формулы — так она приходит, пока ответ ещё пишется.
        val formula = """x_{1,2} = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a} \begin{cases} x \\ y \end{cases} \left( \int_0^1 \right)"""
        for (length in 0..formula.length) LatexParser.parse(formula.substring(0, length))
    }

    @Test
    fun `unknown command is shown by name`() {
        assertEquals("foox", plain("""\foo x"""))
    }
}
