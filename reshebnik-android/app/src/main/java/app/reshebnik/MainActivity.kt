package app.reshebnik

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import app.reshebnik.calc.CalcState
import app.reshebnik.solver.SolverUiState
import app.reshebnik.solver.SolverViewModel
import app.reshebnik.ui.AppScaffold
import app.reshebnik.ui.CalculatorScreen
import app.reshebnik.ui.ReshebnikTheme
import app.reshebnik.ui.SettingsSheet
import app.reshebnik.ui.SolverScreen
import app.reshebnik.ui.Tab
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ReshebnikTheme { App() }
        }
    }
}

/** Выражение переживает поворот экрана и выгрузку приложения из памяти. */
private val CalcStateSaver = Saver<CalcState, List<Any?>>(
    save = { listOf(it.expression, it.previous, it.evaluated) },
    restore = { CalcState(it[0] as String, it[1] as String?, it[2] as Boolean) },
)

@Composable
private fun App(viewModel: SolverViewModel = viewModel()) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.Calculator) }
    var calc by rememberSaveable(stateSaver = CalcStateSaver) { mutableStateOf(CalcState()) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    val solverState by viewModel.state.collectAsState()
    val hint by viewModel.hint.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()

    // Адрес файла для снимка сохраняем: пока открыта камера, систему может выгрузить наше приложение.
    var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingPhoto
        pendingPhoto = null
        if (saved && uri != null) viewModel.onPhotoPicked(uri.toUri())
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onPhotoPicked(uri)
    }

    BackHandler(enabled = tab == Tab.Solver) {
        when (solverState) {
            SolverUiState.Idle -> tab = Tab.Calculator
            is SolverUiState.Solving -> viewModel.cancel()
            else -> viewModel.reset()
        }
    }

    AppScaffold(tab, onTabChange = { tab = it }) { current ->
        when (current) {
            Tab.Calculator -> CalculatorScreen(
                state = calc,
                onKey = { calc = calc.press(it) },
                onOpenSettings = { settingsOpen = true },
            )
            Tab.Solver -> SolverScreen(
                state = solverState,
                hasKey = apiKey.isNotEmpty(),
                hint = hint,
                onHintChange = viewModel::setHint,
                onCamera = {
                    val file = File(File(context.cacheDir, "photos").apply { mkdirs() }, "task.jpg")
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
                    pendingPhoto = uri.toString()
                    try {
                        camera.launch(uri)
                    } catch (e: ActivityNotFoundException) {
                        pendingPhoto = null
                        Toast.makeText(context, "На телефоне нет приложения камеры", Toast.LENGTH_LONG).show()
                    }
                },
                onGallery = {
                    try {
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, "Не нашлось приложения для выбора фото", Toast.LENGTH_LONG).show()
                    }
                },
                onSolve = viewModel::solve,
                onCancel = viewModel::cancel,
                onReset = viewModel::reset,
                onOpenSettings = { settingsOpen = true },
            )
        }
    }

    if (settingsOpen) {
        SettingsSheet(apiKey = apiKey, onSave = viewModel::setApiKey, onDismiss = { settingsOpen = false })
    }
}
