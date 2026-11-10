package app.reshebnik

import app.reshebnik.calc.CalcError
import app.reshebnik.calc.CalcKey
import app.reshebnik.calc.CalcState
import app.reshebnik.calc.formatExpression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalcStateTest {
    /** Нажимает клавиши по строке: цифры, операторы, ',', '=', '(' — умная скобка, 'C', '<', '%', '√', 'π', 's' — квадрат. */
    private fun type(keys: String, from: CalcState = CalcState()): CalcState =
        keys.fold(from) { state, c ->
            state.press(
                when (c) {
                    in '0'..'9' -> CalcKey.Digit(c)
                    '+', '-', '*', '/', '^' -> CalcKey.Operator(c)
                    ',' -> CalcKey.Comma
                    '(' -> CalcKey.Parenthesis
                    '%' -> CalcKey.Percent
                    '√' -> CalcKey.Sqrt
                    'π' -> CalcKey.Pi
                    's' -> CalcKey.Square
                    '<' -> CalcKey.Backspace
                    'C' -> CalcKey.Clear
                    '=' -> CalcKey.Equals
                    else -> error("unknown key $c")
                },
            )
        }

    @Test
    fun `equals moves the expression up and shows the result`() {
        val state = type("12+3=")
        assertEquals("15", state.expression)
        assertEquals("12+3", state.previous)
        assertTrue(state.evaluated)
    }

    @Test
    fun `after equals a digit starts over and an operator continues`() {
        assertEquals("7", type("12+3=7").expression)
        assertEquals("15*2", type("12+3=*2").expression)
        assertNull(type("12+3=*2").previous)
    }

    @Test
    fun `numbers are entered sanely`() {
        assertEquals("5", type("005").expression)
        assertEquals("0.5", type(",5").expression)
        assertEquals("1.25", type("1,2,5").expression)
        assertEquals("3+0.5", type("3+,5").expression)
    }

    @Test
    fun `operators replace each other`() {
        assertEquals("5*", type("5+*").expression)
        assertEquals("5*-", type("5*-").expression)
        assertEquals("5+", type("5*-+").expression)
        assertEquals("", type("*").expression)
        assertEquals("-", type("-").expression)
        assertEquals("(", type("(*").expression)
    }

    @Test
    fun `smart parenthesis opens and closes`() {
        assertEquals("(2+3)", type("(2+3(").expression)
        assertEquals("2*(", type("2*(").expression)
        assertEquals("((1+2)*3)", type("((1+2(*3(").expression)
        assertEquals("(2+3)*4", type("(2+3(4").expression)
    }

    @Test
    fun `live preview appears only for expressions`() {
        assertNull(type("12").preview)
        assertEquals("15", type("12+3").preview)
        assertEquals("12", type("12+").preview)
        assertNull(type("12+3=").preview)
        assertNull(type("1/0").preview)
    }

    @Test
    fun `error is shown on equals and cleared by typing`() {
        val failed = type("1/0=")
        assertEquals(CalcError.DIVISION_BY_ZERO, failed.error)
        assertEquals("1/0", failed.expression)
        assertNull(type("<", failed).error)
    }

    @Test
    fun `backspace keeps the result editable`() {
        val state = type("12+3=<")
        assertEquals("15", state.expression)
        assertNull(state.previous)
        assertEquals("1", type("<", state).expression)
    }

    @Test
    fun `negative result is squared as a whole`() {
        assertEquals("25", type("2-7=s=").expression)
        assertEquals("9", type("3s=").expression)
    }

    @Test
    fun `unfinished expression is tidied in history`() {
        assertEquals("(2+3)", type("(2+3=").previous)
        assertEquals("2", type("2+=").previous)
    }

    @Test
    fun `clear resets everything`() {
        assertEquals(CalcState(), type("12+3=C"))
    }

    @Test
    fun `expression is formatted for people`() {
        assertEquals("1 234 567,5 × −2", formatExpression("1234567.5*-2"))
        assertEquals("1234 + 5", formatExpression("1234+5"))
        assertEquals("(−3 − 4) ÷ 2", formatExpression("(-3-4)/2"))
        assertEquals("1,5·10^20", formatExpression("1.5E20"))
        assertEquals("2·10^−7", formatExpression("2E-7"))
        assertEquals("√9 + 2^3", formatExpression("√9+2^3"))
        assertEquals("0,", formatExpression("0."))
    }
}
