package app.reshebnik

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.reshebnik.calc.CalcKey
import app.reshebnik.calc.CalcState
import app.reshebnik.data.CalcHistoryStore
import app.reshebnik.data.CalcRecord
import app.reshebnik.solver.SolverUiState
import app.reshebnik.solver.SolverViewModel
import app.reshebnik.ui.AppScaffold
import app.reshebnik.ui.CalculatorScreen
import app.reshebnik.ui.CameraScreen
import app.reshebnik.ui.HistoryScreen
import app.reshebnik.ui.ReshebnikTheme
import app.reshebnik.ui.SettingsSheet
import app.reshebnik.ui.SolverActions
import app.reshebnik.ui.SolverScreen
import app.reshebnik.ui.Tab
import app.reshebnik.ui.shareImage
import app.reshebnik.ui.shareText
import app.reshebnik.ui.writeShareImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    save = { listOf(it.expression, it.previous, it.evaluated, it.degrees) },
    restore = { CalcState(it[0] as String, it[1] as String?, it[2] as Boolean, degrees = it[3] as Boolean) },
)

@Composable
private fun App(viewModel: SolverViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(Tab.Calculator) }
    var calc by rememberSaveable(stateSaver = CalcStateSaver) { mutableStateOf(CalcState()) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var cameraOpen by rememberSaveable { mutableStateOf(false) }
    var cameraDenied by rememberSaveable { mutableStateOf(false) }

    val solverState by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val quality by viewModel.quality.collectAsStateWithLifecycle()
    val calcHistory = remember { CalcHistoryStore(context) }
    val calcRecords by calcHistory.records.collectAsStateWithLifecycle()

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraDenied = !granted
        cameraOpen = granted
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onPhotoPicked(uri)
    }

    // Альбомная раскладка есть только у калькулятора (инженерный режим); остальные экраны книжные.
    val activity = context as? Activity
    LaunchedEffect(tab, cameraOpen) {
        activity?.requestedOrientation =
            if (tab == Tab.Calculator && !cameraOpen) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    BackHandler(enabled = cameraOpen || tab != Tab.Calculator) {
        when {
            cameraOpen -> cameraOpen = false
            tab == Tab.History -> tab = Tab.Calculator
            else -> when (val state = solverState) {
                SolverUiState.Idle -> tab = Tab.Calculator
                is SolverUiState.Solving -> viewModel.cancel()
                is SolverUiState.Failed -> viewModel.editAgain()
                is SolverUiState.Solved -> if (state.asking != null) viewModel.dismissQuestion() else viewModel.reset()
                is SolverUiState.Editing -> viewModel.reset()
            }
        }
    }

    if (cameraOpen) {
        CameraScreen(
            onCaptured = { uri, frame ->
                cameraOpen = false
                viewModel.onPhotoPicked(uri, frame)
            },
            onClose = { cameraOpen = false },
        )
    } else {
        val actions = remember(viewModel) {
            SolverActions(
                onCamera = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        cameraDenied = false
                        cameraOpen = true
                    } else {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
                onGallery = {
                    try {
                        gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, "Не нашлось приложения для выбора фото", Toast.LENGTH_LONG).show()
                    }
                },
                onTextTask = viewModel::startTextTask,
                onDraftChange = viewModel::updateDraft,
                onRotate = viewModel::rotatePhoto,
                onQualityChange = viewModel::setQuality,
                onSolve = viewModel::solve,
                onCancel = viewModel::cancel,
                onReset = viewModel::reset,
                onEditAgain = viewModel::editAgain,
                onAsk = viewModel::ask,
                onDismissQuestion = viewModel::dismissQuestion,
                onShareText = {
                    (viewModel.state.value as? SolverUiState.Solved)?.let { shareText(context, it.entry) }
                },
                onShareImage = {
                    val entry = (viewModel.state.value as? SolverUiState.Solved)?.entry
                    if (entry != null) {
                        scope.launch {
                            val file = withContext(Dispatchers.Default) { writeShareImage(context, entry) }
                            // Очень длинное решение картинкой не уместить — отправляем текстом.
                            if (file != null) shareImage(context, file) else shareText(context, entry)
                        }
                    }
                },
                onOpenSettings = { settingsOpen = true },
                onOpenAppSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
        }

        AppScaffold(tab, onTabChange = { tab = it }) { current, landscape ->
            when (current) {
                Tab.Calculator -> CalculatorScreen(
                    state = calc,
                    records = calcRecords,
                    landscape = landscape,
                    onKey = { key ->
                        val next = calc.press(key)
                        // Новый результат уходит в ленту.
                        val previous = next.previous
                        if (key == CalcKey.Equals && next.evaluated && !calc.evaluated && previous != null) {
                            calcHistory.add(CalcRecord(previous, next.expression))
                        }
                        calc = next
                    },
                    onInsert = { value -> calc = calc.insert(value) },
                    onClearHistory = calcHistory::clear,
                    onOpenSettings = { settingsOpen = true },
                )
                Tab.Solver -> SolverScreen(
                    state = solverState,
                    hasKey = apiKey.isNotEmpty(),
                    quality = quality,
                    cameraDenied = cameraDenied,
                    actions = actions,
                )
                Tab.History -> HistoryScreen(
                    entries = history,
                    loadThumbnail = viewModel::loadThumbnail,
                    onOpen = { entry ->
                        viewModel.openFromHistory(entry)
                        tab = Tab.Solver
                    },
                    onDelete = viewModel::deleteFromHistory,
                    onClear = viewModel::clearHistory,
                )
            }
        }
    }

    if (settingsOpen) {
        SettingsSheet(apiKey = apiKey, onSave = viewModel::setApiKey, onDismiss = { settingsOpen = false })
    }
}
