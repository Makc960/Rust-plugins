package app.reshebnik.solver

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.reshebnik.data.SettingsStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SolverUiState {
    /** Фото ещё не выбрано. */
    data object Idle : SolverUiState

    /** Фото выбрано, ждём «Решить». */
    data class Preview(val photo: Bitmap) : SolverUiState

    data class Solving(val photo: Bitmap) : SolverUiState

    data class Solved(val photo: Bitmap, val solution: Solution) : SolverUiState

    /** @param photo null, если не открылось само фото */
    data class Failed(val photo: Bitmap?, val error: SolveError, val detail: String?) : SolverUiState
}

class SolverViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = SettingsStore(application)
    private val solver: ProblemSolver = ClaudeSolver()

    private val _state = MutableStateFlow<SolverUiState>(SolverUiState.Idle)
    val state: StateFlow<SolverUiState> = _state.asStateFlow()

    private val _hint = MutableStateFlow("")
    val hint: StateFlow<String> = _hint.asStateFlow()

    val apiKey: StateFlow<String> = settings.apiKey

    private var job: Job? = null

    fun setApiKey(value: String) {
        settings.setApiKey(value)
        // Ключ исправили после ошибки — возвращаем выбранное фото, чтобы его не искать заново.
        val current = _state.value
        if (current is SolverUiState.Failed && current.photo != null &&
            (current.error == SolveError.NO_KEY || current.error == SolveError.INVALID_KEY)
        ) {
            _state.value = SolverUiState.Preview(current.photo)
        }
    }

    fun setHint(value: String) {
        _hint.value = value.take(MAX_HINT_LENGTH)
    }

    fun onPhotoPicked(uri: Uri) {
        job?.cancel()
        job = viewModelScope.launch {
            _hint.value = ""
            _state.value = try {
                val photo = withContext(Dispatchers.IO) {
                    ImagePrep.load(getApplication<Application>().contentResolver, uri)
                }
                SolverUiState.Preview(photo)
            } catch (e: IOException) {
                Log.w(TAG, "Cannot load photo $uri", e)
                SolverUiState.Failed(null, SolveError.BAD_IMAGE, null)
            } catch (e: SecurityException) {
                Log.w(TAG, "No access to photo $uri", e)
                SolverUiState.Failed(null, SolveError.BAD_IMAGE, null)
            }
        }
    }

    fun solve() {
        val photo = when (val current = _state.value) {
            is SolverUiState.Preview -> current.photo
            is SolverUiState.Failed -> current.photo ?: return
            else -> return
        }
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = SolverUiState.Solving(photo)
            _state.value = try {
                val jpeg = withContext(Dispatchers.Default) { ImagePrep.toJpegBase64(photo) }
                SolverUiState.Solved(photo, solver.solve(apiKey.value, jpeg, _hint.value))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SolveException) {
                Log.w(TAG, "Solve failed: ${e.error}", e)
                SolverUiState.Failed(photo, e.error, e.detail)
            } catch (e: Exception) {
                Log.w(TAG, "Solve failed", e)
                SolverUiState.Failed(photo, SolveError.UNKNOWN, e.message)
            }
        }
    }

    /** «Отменить» во время решения: возвращаемся к выбранному фото. */
    fun cancel() {
        val current = _state.value as? SolverUiState.Solving ?: return
        job?.cancel()
        _state.value = SolverUiState.Preview(current.photo)
    }

    fun reset() {
        job?.cancel()
        _hint.value = ""
        _state.value = SolverUiState.Idle
    }

    private companion object {
        const val TAG = "Reshebnik"
        const val MAX_HINT_LENGTH = 300
    }
}
