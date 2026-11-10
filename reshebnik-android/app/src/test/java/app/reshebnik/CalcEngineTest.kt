package app.reshebnik

import app.reshebnik.calc.CalcEngine
import app.reshebnik.calc.CalcError
import app.reshebnik.calc.CalcResult
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class CalcEngineTest {
    private fun value(expression: String): String =
        when (val result = CalcEngine.evaluate(expression)) {
            is CalcResult.Value -> CalcEngine.toInternal(result.value)
            is CalcResult.Failure -> "error:${result.error}"
        }

    private fun error(expression: String): CalcError? = (CalcEngine.evaluate(expression) as? CalcResult.Failure)?.error

    @Test
    fun `respects operator precedence`() {
        assertEquals("6", value("2+2*2"))
        assertEquals("8", value("(2+2)*2"))
        assertEquals("-1", value("1-2"))
        assertEquals("2.5", value("10/4"))
    }

    @Test
    fun `decimal arithmetic has no binary artefacts`() {
        assertEquals("0.3", value("0.1+0.2"))
        assertEquals("2", value("2/3*3"))
        assertEquals("0.333333333333", value("1/3"))
    }

    @Test
    fun `percent is relative after plus and minus`() {
        assertEquals("220", value("200+10%"))
        assertEquals("180", value("200-10%"))
        assertEquals("20", value("200*10%"))
        assertEquals("0.5", value("50%"))
    }

    @Test
    fun `powers and unary minus`() {
        assertEquals("1024", value("2^10"))
        assertEquals("-4", value("-2^2"))
        assertEquals("4", value("(-2)^2"))
        assertEquals("0.5", value("2^-1"))
        assertEquals("512", value("2^3^2"))
        assertEquals("-15", value("5*-3"))
        assertEquals("3", value("9^0.5"))
    }

    @Test
    fun `roots and pi`() {
        assertEquals("3", value("√9"))
        assertEquals("1.41421356237", value("√2"))
        assertEquals("6", value("2√9"))
        assertEquals("5", value("√(9+16)"))
        assertEquals("6.28318530718", value("2π"))
        assertEquals("10000000000", value("√100000000000000000000"))
    }

    @Test
    fun `implicit multiplication`() {
        assertEquals("21", value("(1+2)(3+4)"))
        assertEquals("14", value("2(3+4)"))
        assertEquals("10", value("(2+3)2"))
    }

    @Test
    fun `unfinished input is forgiven`() {
        assertEquals("5", value("(2+3"))
        assertEquals("2", value("2+"))
        assertEquals("6", value("2*(3"))
        assertEquals("5", value("5."))
    }

    @Test
    fun `errors are reported`() {
        assertEquals(CalcError.DIVISION_BY_ZERO, error("1/0"))
        assertEquals(CalcError.DIVISION_BY_ZERO, error("0^-1"))
        assertEquals(CalcError.DOMAIN, error("√-4"))
        assertEquals(CalcError.DOMAIN, error("(-8)^0.5"))
        assertEquals(CalcError.TOO_LARGE, error("9^9^9"))
        assertEquals(CalcError.TOO_LARGE, error("10^2000"))
        assertEquals(CalcError.INVALID, error(""))
        assertEquals(CalcError.INVALID, error("+"))
        assertEquals(CalcError.INVALID, error("2)"))
        assertEquals(CalcError.INVALID, error("1.2.3"))
    }

    @Test
    fun `large and small results use scientific notation`() {
        assertEquals("100000000000000", value("10000000*10000000"))
        assertEquals("1E20", value("10000000000*10000000000"))
        assertEquals("1E21", value("1E20*10"))
        assertEquals("1.5E-10", value("0.00015/1000000"))
        assertEquals("-2.5E15", CalcEngine.toInternal(BigDecimal("-2500000000000000")))
        assertEquals("0", value("5-5"))
    }
}
