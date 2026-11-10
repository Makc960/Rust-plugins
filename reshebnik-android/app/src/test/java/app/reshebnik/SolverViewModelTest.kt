package app.reshebnik

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.reshebnik.data.HistoryStore
import app.reshebnik.data.SettingsStore
import app.reshebnik.solver.AskRequest
import app.reshebnik.solver.Block
import app.reshebnik.solver.CropArea
import app.reshebnik.solver.Draft
import app.reshebnik.solver.ProblemSolver
import app.reshebnik.solver.Quality
import app.reshebnik.solver.Solution
import app.reshebnik.solver.SolutionStep
import app.reshebnik.solver.SolveError
import app.reshebnik.solver.SolveException
import app.reshebnik.solver.SolveRequest
import app.reshebnik.solver.SolverUiState
import app.reshebnik.solver.SolverViewModel
import app.reshebnik.solver.TaskMode
import app.reshebnik.solver.Verdict
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SolverViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val solved = Solution(
        solved = true,
        verdict = Verdict.NONE,
        problem = listOf(Block.Formula("2x = 8")),
        answer = listOf(Block.Formula("x = 4")),
        steps = listOf(SolutionStep("Делим на 2", listOf(Block.Formula("x = 4")))),
    )

    /** Решатель-заглушка: отдаёт заготовленный ответ, а по [gate] может придержать его. */
    private class FakeSolver : ProblemSolver {
        var solution: Solution? = null
        var answer: List<Block> = listOf(Block.Text("Потому что."))
        var error: SolveException? = null
        var gate: CompletableDeferred<Unit>? = null
        val requests = mutableListOf<SolveRequest>()
        val questions = mutableListOf<AskRequest>()

        override suspend fun solve(request: SolveRequest, onPartial: (Solution) -> Unit): Solution {
            requests += request
            val result = solution!!
            onPartial(result.copy(steps = emptyList()))
            gate?.await()
            error?.let { throw it }
            return result
        }

        override suspend fun ask(request: AskRequest, onPartial: (List<Block>) -> Unit): List<Block> {
            questions += request
            onPartial(answer.take(1))
            gate?.await()
            error?.let { throw it }
            return answer
        }
    }

    private val solver = FakeSolver()
    private lateinit var settings: SettingsStore
    private lateinit var history: HistoryStore
    private lateinit var viewModel: SolverViewModel

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        settings = SettingsStore(application).apply { setApiKey("sk-ant-test") }
        history = HistoryStore(folder.newFolder("history"))
        viewModel = SolverViewModel(application, solver, settings, history)
        solver.solution = solved
    }

    /** Крутит главный поток, пока фоновые шаги модели не приведут к нужному состоянию. */
    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what; state = ${viewModel.state.value}")
            Thread.sleep(5)
        }
    }

    private inline fun <reified T : SolverUiState> awaitState(): T {
        await(T::class.simpleName!!) { viewModel.state.value is T }
        return viewModel.state.value as T
    }

    private fun solveText(text: String = "Реши: 2x = 8"): SolverUiState.Solved {
        viewModel.startTextTask()
        viewModel.updateDraft { it.copy(text = text) }
        viewModel.solve()
        return awaitState()
    }

    @Test
    fun `text task is solved and saved to history`() {
        viewModel.setQuality(Quality.FAST)
        viewModel.startTextTask()
        viewModel.updateDraft { it.copy(text = "  Проверь: 2x = 8, x = 5  ", mode = TaskMode.CHECK) }
        viewModel.solve()

        val state = awaitState<SolverUiState.Solved>()

        assertEquals(solved, state.entry.solution)
        assertEquals(TaskMode.CHECK, state.entry.mode)
        assertEquals("Проверь: 2x = 8, x = 5", state.entry.text)
        assertNull(state.photo)
        val request = solver.requests.single()
        assertEquals("sk-ant-test", request.apiKey)
        assertEquals(Quality.FAST, request.quality)
        assertEquals(TaskMode.CHECK, request.mode)
        assertNull(request.jpegBase64)
        await("history") { viewModel.history.value.size == 1 }
        assertEquals(state.entry, history.list().single())
    }

    @Test
    fun `empty draft is not sent`() {
        viewModel.startTextTask()
        viewModel.solve()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(viewModel.state.value is SolverUiState.Editing)
        assertTrue(solver.requests.isEmpty())
    }

    @Test
    fun `photo is cropped before sending and kept for questions`() {
        val photo = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        viewModel.startTextTask()
        viewModel.updateDraft { Draft(photo, CropArea(0.25f, 0f, 0.75f, 1f), "только № 2") }
        viewModel.solve()

        val state = awaitState<SolverUiState.Solved>()

        assertEquals(200, state.photo!!.width)
        assertEquals(200, state.photo!!.height)
        assertNotNull(solver.requests.single().jpegBase64)
        assertTrue(state.entry.hasPhoto)
        assertTrue(history.photoFile(state.entry.id).isFile)

        viewModel.ask("Почему?")
        await("answer") { (viewModel.state.value as? SolverUiState.Solved)?.entry?.exchanges?.size == 1 }
        assertNotNull(solver.questions.single().task.jpegBase64)
    }

    @Test
    fun `partial solution is shown while solving and cancel returns to the draft`() {
        solver.gate = CompletableDeferred()
        viewModel.startTextTask()
        viewModel.updateDraft { it.copy(text = "2x = 8") }
        viewModel.solve()

        await("partial") { (viewModel.state.value as? SolverUiState.Solving)?.partial != null }
        viewModel.cancel()

        val editing = viewModel.state.value as SolverUiState.Editing
        assertEquals("2x = 8", editing.draft.text)
        // Ответ, пришедший после отмены, уже никому не нужен.
        solver.gate!!.complete(Unit)
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(viewModel.state.value is SolverUiState.Editing)
        assertTrue(history.list().isEmpty())
    }

    @Test
    fun `failure keeps the draft and a fixed key brings it back`() {
        solver.error = SolveException(SolveError.INVALID_KEY)
        viewModel.startTextTask()
        viewModel.updateDraft { it.copy(text = "2x = 8") }
        viewModel.solve()

        val failed = awaitState<SolverUiState.Failed>()
        assertEquals(SolveError.INVALID_KEY, failed.error)
        assertEquals("2x = 8", failed.draft!!.text)

        viewModel.setApiKey("sk-ant-new")
        assertEquals("2x = 8", (viewModel.state.value as SolverUiState.Editing).draft.text)

        solver.error = SolveException(SolveError.NETWORK)
        viewModel.solve()
        awaitState<SolverUiState.Failed>()
        solver.error = null
        viewModel.solve()
        awaitState<SolverUiState.Solved>()
        assertEquals("sk-ant-new", solver.requests.last().apiKey)
    }

    @Test
    fun `photo without a task is not saved`() {
        solver.solution = Solution(false, Verdict.NONE, listOf(Block.Text("Тут нет задания.")), emptyList(), emptyList())

        val state = solveText()

        assertEquals(false, state.entry.solution.solved)
        assertTrue(history.list().isEmpty())
    }

    @Test
    fun `question is answered and saved with the entry`() {
        val state = solveText()
        solver.gate = CompletableDeferred()
        solver.answer = listOf(Block.Text("Делим обе части."), Block.Formula("x = 4"))

        viewModel.ask("  Почему делим?  ")
        await("partial answer") { (viewModel.state.value as? SolverUiState.Solved)?.asking?.partial?.isNotEmpty() == true }
        assertEquals("Почему делим?", (viewModel.state.value as SolverUiState.Solved).asking!!.question)
        solver.gate!!.complete(Unit)
        await("answer") { (viewModel.state.value as? SolverUiState.Solved)?.let { it.asking == null && it.entry.exchanges.size == 1 } == true }

        val exchange = (viewModel.state.value as SolverUiState.Solved).entry.exchanges.single()
        assertEquals("Почему делим?", exchange.question)
        assertEquals(solver.answer, exchange.answer)
        assertEquals(listOf(exchange), history.list().single().exchanges)
        val asked = solver.questions.single()
        assertEquals(state.entry.solution, asked.solution)
        assertEquals("Реши: 2x = 8", asked.task.text)

        // Второй вопрос уходит уже вместе с первым.
        solver.gate = null
        viewModel.ask("А ещё?")
        await("second answer") { (viewModel.state.value as? SolverUiState.Solved)?.entry?.exchanges?.size == 2 }
        assertEquals(listOf(exchange), solver.questions.last().exchanges)
    }

    @Test
    fun `failed question stays on screen until dismissed`() {
        solveText()
        solver.error = SolveException(SolveError.NETWORK)

        viewModel.ask("Почему?")
        await("error") { (viewModel.state.value as? SolverUiState.Solved)?.asking?.error != null }
        assertEquals(SolveError.NETWORK, (viewModel.state.value as SolverUiState.Solved).asking!!.error)

        viewModel.dismissQuestion()
        val state = viewModel.state.value as SolverUiState.Solved
        assertNull(state.asking)
        assertTrue(state.entry.exchanges.isEmpty())
    }

    @Test
    fun `entry is reopened from history and deleted`() {
        val entry = solveText().entry
        viewModel.reset()
        assertEquals(SolverUiState.Idle, viewModel.state.value)

        viewModel.openFromHistory(entry)
        assertEquals(entry, (viewModel.state.value as SolverUiState.Solved).entry)

        viewModel.deleteFromHistory(entry.id)
        await("deletion") { viewModel.history.value.isEmpty() && viewModel.state.value == SolverUiState.Idle }
        assertTrue(history.list().isEmpty())
    }
}
