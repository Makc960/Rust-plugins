package app.reshebnik

import app.reshebnik.solver.Block
import app.reshebnik.solver.JsonWriter
import app.reshebnik.solver.LenientJson
import app.reshebnik.solver.Solution
import app.reshebnik.solver.SolutionJson
import app.reshebnik.solver.SolutionStep
import app.reshebnik.solver.Verdict
import app.reshebnik.solver.toPlainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {
    private val full = """{"status":"solved","verdict":"correct","problem":[{"type":"text","value":"Реши: \"x\"\n"},""" +
        """{"type":"math","value":"x^2 = 4"}],"answer":[{"type":"math","value":"x = \\pm 2"}],""" +
        """"steps":[{"title":"Корень","body":[{"type":"math","value":"x = \\pm\\sqrt{4}"}]}]}"""

    @Test
    fun `complete solution is parsed`() {
        val solution = SolutionJson.parseSolution(full)!!
        assertTrue(solution.solved)
        assertEquals(Verdict.CORRECT, solution.verdict)
        assertEquals(listOf(Block.Text("Реши: \"x\""), Block.Formula("x^2 = 4")), solution.problem)
        assertEquals(listOf(Block.Formula("x = \\pm 2")), solution.answer)
        assertEquals(SolutionStep("Корень", listOf(Block.Formula("x = \\pm\\sqrt{4}"))), solution.steps.single())
    }

    @Test
    fun `every prefix of a solution parses without errors and never loses finished blocks`() {
        var previousBlocks = 0
        for (length in 0..full.length) {
            val solution = SolutionJson.parseSolution(full.substring(0, length))
            val blocks = solution?.let { it.problem.size + it.answer.size + it.steps.sumOf { step -> step.body.size } } ?: 0
            assertTrue("blocks went backwards at $length", blocks >= previousBlocks)
            previousBlocks = blocks
        }
        assertEquals(4, previousBlocks)
    }

    @Test
    fun `text cut mid string is shown as far as it got`() {
        val solution = SolutionJson.parseSolution("""{"status":"solved","verdict":"none","problem":[{"type":"text","value":"Найдите ко""")!!
        assertEquals(listOf(Block.Text("Найдите ко")), solution.problem)
        assertTrue(solution.steps.isEmpty())
    }

    @Test
    fun `nothing useful yet gives null`() {
        assertNull(SolutionJson.parseSolution(""))
        assertNull(SolutionJson.parseSolution("{\"sta"))
        assertNull(SolutionJson.parseSolution("не JSON"))
    }

    @Test
    fun `escapes and unicode survive`() {
        assertEquals(mapOf("a" to "x\ny\t\"z\" \\ é"), LenientJson.parse("""{"a":"x\ny\t\"z\" \\ é"}"""))
        assertEquals(listOf(1.0, -2.5, true, null, "s"), LenientJson.parse("""[1, -2.5, true, null, "s"]"""))
        // Оборванная последовательность \u отбрасывается, уже прочитанное остаётся.
        assertEquals(mapOf("a" to "обрыв \\"), LenientJson.parse("""{"a":"обрыв \\\u00"""))
    }

    @Test
    fun `solution survives writing and reading back`() {
        val solution = Solution(
            solved = true,
            verdict = Verdict.WRONG,
            problem = listOf(Block.Text("Строка с \"кавычками\", \\ и\nпереносом"), Block.Formula("\\frac{1}{2}")),
            answer = listOf(Block.Formula("x_1 = 2")),
            steps = listOf(SolutionStep("Шаг", listOf(Block.Text("Пояснение"), Block.Formula("a^2"))), SolutionStep("", emptyList())),
        )
        assertEquals(solution, SolutionJson.parseSolution(JsonWriter.write(SolutionJson.toMap(solution))))
    }

    @Test
    fun `answer blocks are parsed and may be partial`() {
        assertEquals(listOf(Block.Text("Да"), Block.Formula("x = 1")), SolutionJson.parseBlocks("""{"blocks":[{"type":"text","value":"Да"},{"type":"math","value":"x = 1"}]}"""))
        assertEquals(listOf(Block.Text("Да")), SolutionJson.parseBlocks("""{"blocks":[{"type":"text","value":"Да"},{"type":"ma"""))
        assertEquals(emptyList<Block>(), SolutionJson.parseBlocks(""))
    }

    @Test
    fun `blocks turn into plain text`() {
        val blocks = listOf(Block.Text(" Ответ: "), Block.Formula("x = \\frac{a + b}{2}"), Block.Formula("y^2"))
        assertEquals("Ответ:\nx = (a + b)/2\ny²", blocks.toPlainText())
    }
}
