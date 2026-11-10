package app.reshebnik.solver

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.reshebnik.BuildConfig
import app.reshebnik.data.HistoryEntry
import app.reshebnik.data.HistoryStore
import app.reshebnik.data.SettingsStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Задание, которое готовят к отправке.
 *
 * @param photo снимок или null, если условие набирают текстом
 * @param crop какая часть снимка уйдёт нейросети
 * @param text уточнение к фото, а без фото — само условие
 */
data class Draft(
    val photo: Bitmap?,
    val crop: CropArea = CropArea.FULL,
    val text: String = "",
    val mode: TaskMode = TaskMode.SOLVE,
) {
    val canSend: Boolean get() = photo != null || text.isNotBlank()
}

/**
 * Вопрос по решению, на который ещё нет ответа.
 *
 * @param partial ответ, пришедший к этому моменту
 * @param error не null, если ответить не получилось
 */
data class Asking(val question: String, val partial: List<Block> = emptyList(), val error: SolveError? = null)

sealed interface SolverUiState {
    /** Задание ещё не выбрано. */
    data object Idle : SolverUiState

    data class Editing(val draft: Draft) : SolverUiState

    /** @param partial решение, пришедшее к этому моменту; null, пока нейросеть думает */
    data class Solving(val draft: Draft, val photo: Bitmap?, val partial: Solution?) : SolverUiState

    /** @param photo снимок, отправленный нейросети (для записи из истории подгружается чуть позже) */
    data class Solved(val entry: HistoryEntry, val photo: Bitmap?, val asking: Asking? = null) : SolverUiState

    /** @param draft null, если не открылось само фото */
    data class Failed(val draft: Draft?, val error: SolveError, val detail: String?) : SolverUiState
}

class SolverViewModel(
    application: Application,
    private val solver: ProblemSolver,
    private val settings: SettingsStore,
    private val historyStore: HistoryStore,
) : AndroidViewModel(application) {

    @Suppress("unused") // Этим конструктором модель создаёт система.
    constructor(application: Application) : this(
        application,
        ClaudeSolver(BuildConfig.API_BASE_URL.ifEmpty { null }),
        SettingsStore(application),
        HistoryStore(application),
    )

    private val _state = MutableStateFlow<SolverUiState>(SolverUiState.Idle)
    val state: StateFlow<SolverUiState> = _state.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    val apiKey: StateFlow<String> = settings.apiKey
    val quality: StateFlow<Quality> = settings.quality

    private var job: Job? = null

    init {
        viewModelScope.launch { refreshHistory() }
    }

    fun setApiKey(value: String) {
        settings.setApiKey(value)
        // Ключ исправили после ошибки — возвращаем задание, чтобы не собирать его заново.
        val current = _state.value
        if (current is SolverUiState.Failed && current.draft != null &&
            (current.error == SolveError.NO_KEY || current.error == SolveError.INVALID_KEY)
        ) {
            _state.value = SolverUiState.Editing(current.draft)
        }
    }

    fun setQuality(value: Quality) = settings.setQuality(value)

    // ---- Подготовка задания -------------------------------------------------------------

    /** @param crop область, которую стоит предложить для обрезки: рамка камеры */
    fun onPhotoPicked(uri: Uri, crop: CropArea = CropArea.FULL) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = try {
                val photo = withContext(Dispatchers.IO) {
                    ImagePrep.load(getApplication<Application>().contentResolver, uri)
                }
                SolverUiState.Editing(Draft(photo, crop))
            } catch (e: IOException) {
                Log.w(TAG, "Cannot load photo $uri", e)
                SolverUiState.Failed(null, SolveError.BAD_IMAGE, null)
            } catch (e: SecurityException) {
                Log.w(TAG, "No access to photo $uri", e)
                SolverUiState.Failed(null, SolveError.BAD_IMAGE, null)
            }
        }
    }

    /** Задание без фото: условие набирают текстом. */
    fun startTextTask() {
        job?.cancel()
        _state.value = SolverUiState.Editing(Draft(photo = null))
    }

    fun updateDraft(change: (Draft) -> Draft) {
        _state.update { current ->
            if (current is SolverUiState.Editing) current.copy(draft = change(current.draft).limited()) else current
        }
    }

    fun rotatePhoto() {
        val draft = (_state.value as? SolverUiState.Editing)?.draft ?: return
        val photo = draft.photo ?: return
        job?.cancel()
        job = viewModelScope.launch {
            val rotated = withContext(Dispatchers.Default) { ImagePrep.rotate(photo) }
            updateDraft { it.copy(photo = rotated, crop = CropArea.FULL) }
        }
    }

    private fun Draft.limited() = if (text.length > MAX_TEXT_LENGTH) copy(text = text.take(MAX_TEXT_LENGTH)) else this

    // ---- Решение ------------------------------------------------------------------------

    fun solve() {
        val draft = when (val current = _state.value) {
            is SolverUiState.Editing -> current.draft
            is SolverUiState.Failed -> current.draft ?: return
            else -> return
        }
        if (!draft.canSend) return
        job?.cancel()
        job = viewModelScope.launch {
            val running = currentCoroutineContext().job
            try {
                val photo = draft.photo?.let { withContext(Dispatchers.Default) { ImagePrep.crop(it, draft.crop) } }
                _state.value = SolverUiState.Solving(draft, photo, null)
                val jpeg = photo?.let { withContext(Dispatchers.Default) { ImagePrep.toJpeg(it) } }
                val request = SolveRequest(apiKey.value, quality.value, draft.mode, jpeg?.let(ImagePrep::toBase64), draft.text)
                val solution = solver.solve(request) { partial ->
                    // Запоздавший кусок отменённого запроса не должен попасть на экран.
                    if (running.isActive) _state.update { if (it is SolverUiState.Solving) it.copy(partial = partial) else it }
                }
                val now = System.currentTimeMillis()
                val entry = HistoryEntry(HistoryStore.newId(now), now, draft.mode, draft.text.trim(), photo != null, solution)
                if (solution.solved) {
                    withContext(Dispatchers.IO) {
                        try {
                            historyStore.save(entry, jpeg)
                        } catch (e: IOException) {
                            Log.w(TAG, "Cannot save the solution to history", e)
                        }
                    }
                    refreshHistory()
                }
                _state.value = SolverUiState.Solved(entry, photo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SolveException) {
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "Solve failed: ${e.error}", e)
                _state.value = SolverUiState.Failed(draft, e.error, e.detail)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "Solve failed", e)
                _state.value = SolverUiState.Failed(draft, SolveError.UNKNOWN, e.message)
            }
        }
    }

    /** «Отменить» во время решения: возвращаемся к подготовленному заданию. */
    fun cancel() {
        val current = _state.value as? SolverUiState.Solving ?: return
        job?.cancel()
        _state.value = SolverUiState.Editing(current.draft)
    }

    /** После ошибки: назад к заданию, чтобы поправить его. */
    fun editAgain() {
        val draft = (_state.value as? SolverUiState.Failed)?.draft ?: return reset()
        _state.value = SolverUiState.Editing(draft)
    }

    fun reset() {
        job?.cancel()
        _state.value = SolverUiState.Idle
    }

    // ---- Вопросы по решению -------------------------------------------------------------

    fun ask(question: String) {
        val solved = _state.value as? SolverUiState.Solved ?: return
        val text = question.trim().take(MAX_TEXT_LENGTH)
        if (text.isEmpty() || (solved.asking != null && solved.asking.error == null)) return
        val entry = solved.entry
        job?.cancel()
        job = viewModelScope.launch {
            val running = currentCoroutineContext().job
            fun show(asking: Asking?) = _state.update {
                if (it is SolverUiState.Solved && it.entry.id == entry.id) it.copy(asking = asking) else it
            }
            show(Asking(text))
            try {
                val jpeg = if (entry.hasPhoto) withContext(Dispatchers.IO) { readPhoto(entry.id) } else null
                val task = SolveRequest(apiKey.value, quality.value, entry.mode, jpeg?.let(ImagePrep::toBase64), entry.text)
                val answer = solver.ask(AskRequest(task, entry.solution, entry.exchanges, text)) { partial ->
                    if (running.isActive) show(Asking(text, partial))
                }
                val updated = entry.copy(exchanges = entry.exchanges + Exchange(text, answer))
                withContext(Dispatchers.IO) {
                    try {
                        historyStore.update(updated)
                    } catch (e: IOException) {
                        Log.w(TAG, "Cannot save the answer to history", e)
                    }
                }
                refreshHistory()
                _state.update {
                    if (it is SolverUiState.Solved && it.entry.id == entry.id) it.copy(entry = updated, asking = null) else it
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SolveException) {
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "Ask failed: ${e.error}", e)
                show(Asking(text, error = e.error))
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "Ask failed", e)
                show(Asking(text, error = SolveError.UNKNOWN))
            }
        }
    }

    /** Убирает неотвеченный вопрос с экрана: после ошибки или по «Отменить». */
    fun dismissQuestion() {
        val solved = _state.value as? SolverUiState.Solved ?: return
        if (solved.asking == null) return
        job?.cancel()
        _state.value = solved.copy(asking = null)
    }

    private fun readPhoto(id: String): ByteArray? = try {
        historyStore.photoFile(id).takeIf { it.isFile }?.readBytes()
    } catch (e: IOException) {
        Log.w(TAG, "Cannot read the photo of $id", e)
        null
    }

    // ---- История ------------------------------------------------------------------------

    fun openFromHistory(entry: HistoryEntry) {
        job?.cancel()
        _state.value = SolverUiState.Solved(entry, photo = null)
        if (!entry.hasPhoto) return
        job = viewModelScope.launch {
            val photo = withContext(Dispatchers.IO) { ImagePrep.loadFile(historyStore.photoFile(entry.id), PREVIEW_SIDE) }
            _state.update { if (it is SolverUiState.Solved && it.entry.id == entry.id) it.copy(photo = photo) else it }
        }
    }

    fun deleteFromHistory(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { historyStore.delete(id) }
            refreshHistory()
            val current = _state.value
            if (current is SolverUiState.Solved && current.entry.id == id) reset()
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { historyStore.clear() }
            refreshHistory()
            if (_state.value is SolverUiState.Solved) reset()
        }
    }

    /** Миниатюра снимка для строки истории. */
    suspend fun loadThumbnail(id: String): Bitmap? =
        withContext(Dispatchers.IO) { ImagePrep.loadFile(historyStore.photoFile(id), THUMBNAIL_SIDE) }

    private suspend fun refreshHistory() {
        _history.value = withContext(Dispatchers.IO) { historyStore.list() }
    }

    private companion object {
        const val TAG = "Reshebnik"
        const val MAX_TEXT_LENGTH = 2000
        const val PREVIEW_SIDE = 1280
        const val THUMBNAIL_SIDE = 160
    }
}
