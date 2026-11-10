package app.reshebnik.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.reshebnik.solver.Draft
import app.reshebnik.solver.Quality
import app.reshebnik.solver.SolveError
import app.reshebnik.solver.SolverUiState
import app.reshebnik.solver.TaskMode

/** Всё, что экран «По фото» умеет попросить сделать. */
class SolverActions(
    val onCamera: () -> Unit = {},
    val onGallery: () -> Unit = {},
    val onTextTask: () -> Unit = {},
    val onDraftChange: ((Draft) -> Draft) -> Unit = {},
    val onRotate: () -> Unit = {},
    val onQualityChange: (Quality) -> Unit = {},
    val onSolve: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onReset: () -> Unit = {},
    val onEditAgain: () -> Unit = {},
    val onAsk: (String) -> Unit = {},
    val onDismissQuestion: () -> Unit = {},
    val onShareText: () -> Unit = {},
    val onShareImage: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
)

/**
 * @param hasKey сохранён ли ключ API; без него вместо отправки открываются настройки
 * @param cameraDenied пользователь отказал приложению в доступе к камере
 */
@Composable
fun SolverScreen(
    state: SolverUiState,
    hasKey: Boolean,
    quality: Quality,
    cameraDenied: Boolean,
    actions: SolverActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        NotebookPage(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Header(state, actions)
                if (state is SolverUiState.Editing) {
                    EditingPage(state.draft, actions)
                } else {
                    // У каждого задания своя прокрутка: новое решение открывается с начала.
                    key(state::class, (state as? SolverUiState.Solved)?.entry?.id) { ScrollingPage(state, actions) }
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                SolverUiState.Idle -> IdlePanel(hasKey, cameraDenied, actions)
                is SolverUiState.Editing -> EditingPanel(state.draft, hasKey, quality, actions)
                is SolverUiState.Solving -> SecondaryButton("Отменить", actions.onCancel)
                is SolverUiState.Solved -> SolvedPanel(state, actions)
                is SolverUiState.Failed -> FailedPanel(state, actions)
            }
        }
    }
}

@Composable
private fun Header(state: SolverUiState, actions: SolverActions) {
    Row(
        Modifier.fillMaxWidth().padding(start = PageStartPadding, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Wordmark()
        Spacer(Modifier.weight(1f))
        when (state) {
            is SolverUiState.Editing -> {
                if (state.draft.photo != null) IconAction(AppIcons.Rotate, "Повернуть фото", actions.onRotate)
                IconAction(AppIcons.Close, "Убрать задание", actions.onReset)
            }
            is SolverUiState.Solved -> if (state.entry.solution.solved) ShareMenu(actions)
            else -> Unit
        }
        IconAction(AppIcons.Settings, "Настройки", actions.onOpenSettings)
    }
}

@Composable
private fun ShareMenu(actions: SolverActions) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconAction(AppIcons.Share, "Поделиться решением", { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = palette.key) {
            DropdownMenuItem(
                text = { Text("Картинкой", color = palette.text, fontFamily = Sans, fontSize = 16.sp) },
                onClick = {
                    open = false
                    actions.onShareImage()
                },
            )
            DropdownMenuItem(
                text = { Text("Текстом", color = palette.text, fontFamily = Sans, fontSize = 16.sp) },
                onClick = {
                    open = false
                    actions.onShareText()
                },
            )
        }
    }
}

// ---- Лист ---------------------------------------------------------------------------------

@Composable
private fun ScrollingPage(state: SolverUiState, actions: SolverActions) {
    val scroll = rememberScrollState()
    // Задали вопрос — прокручиваем к нему и следим за ответом, пока он пишется.
    val asking = (state as? SolverUiState.Solved)?.asking
    LaunchedEffect(asking?.question, asking?.partial?.size) {
        if (asking != null) scroll.animateScrollTo(scroll.maxValue)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(start = PageStartPadding, end = PageEndPadding, top = 8.dp, bottom = 24.dp),
    ) {
        when (state) {
            SolverUiState.Idle -> IdlePage()
            is SolverUiState.Solving -> {
                state.photo?.let { Photo(it, Modifier.heightIn(max = 150.dp).alpha(0.6f).padding(bottom = 16.dp)) }
                val partial = state.partial
                if (partial == null) {
                    Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.Bottom) {
                        Text("Решаю", color = LocalPalette.current.ink, fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = 30.sp)
                        WritingDots()
                    }
                    PageText("Нейросеть думает — решение появится здесь по мере написания.", Modifier.padding(top = 6.dp))
                } else {
                    SolutionContent(partial, state.draft.mode, writing = true)
                }
            }
            is SolverUiState.Solved -> {
                val solution = state.entry.solution
                if (!solution.solved) {
                    Spacer(Modifier.height(12.dp))
                    PageTitle("Не вижу задания")
                    if (solution.problem.isEmpty()) {
                        PageText("Попробуй снять ближе и при хорошем свете, чтобы текст был в фокусе.", Modifier.padding(top = 10.dp))
                    } else {
                        Blocks(solution.problem, Modifier.padding(top = 10.dp), color = LocalPalette.current.textMuted)
                    }
                } else {
                    state.photo?.let { Photo(it, Modifier.heightIn(max = 150.dp).padding(bottom = 16.dp)) }
                    SolutionContent(
                        solution = solution,
                        mode = state.entry.mode,
                        writing = false,
                        onExplain = { number ->
                            val title = solution.steps.getOrNull(number - 1)?.title.orEmpty()
                            actions.onAsk("Объясни подробнее шаг $number" + if (title.isBlank()) "" else ": «$title»")
                        },
                    )
                    Questions(
                        exchanges = state.entry.exchanges,
                        asking = state.asking,
                        onRetry = { state.asking?.let { actions.onAsk(it.question) } },
                        onDismiss = actions.onDismissQuestion,
                    )
                }
            }
            is SolverUiState.Failed -> FailedPage(state.error, state.detail)
            is SolverUiState.Editing -> Unit
        }
    }
}

@Composable
private fun IdlePage() {
    val palette = LocalPalette.current
    Spacer(Modifier.height(12.dp))
    PageTitle("Сфотографируй задание")
    PageText(
        "Пример, уравнение или задача — нейросеть пришлёт ответ и решение по шагам. А ещё проверит твоё решение и найдёт ошибку.",
        Modifier.padding(top = 10.dp),
    )
    // Образец того, что получится: строчки «ручкой» и оценка учителя на полях.
    Box(Modifier.fillMaxWidth().padding(top = 26.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MathFormula("2x + 6 = 14", color = palette.ink, fontSize = 24.sp)
            MathFormula("2x = 8", color = palette.ink, fontSize = 24.sp)
            MathFormula("x = 4", Modifier.doubleUnderline(palette), color = palette.ink, fontSize = 24.sp)
        }
        Text(
            text = "5",
            color = palette.margin,
            fontFamily = Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 34.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp),
        )
    }
}

@Composable
private fun EditingPage(draft: Draft, actions: SolverActions) {
    val palette = LocalPalette.current
    if (draft.photo != null) {
        CropBox(
            photo = draft.photo,
            crop = draft.crop,
            onCropChange = { area -> actions.onDraftChange { it.copy(crop = area) } },
            modifier = Modifier.padding(start = 16.dp, end = PageMargin + 10.dp, top = 6.dp, bottom = 18.dp),
        )
    } else {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        TextField(
            value = draft.text,
            onValueChange = { text -> actions.onDraftChange { it.copy(text = text) } },
            modifier = Modifier.fillMaxSize().padding(start = 6.dp, end = PageMargin).focusRequester(focus),
            placeholder = {
                Text(
                    text = if (draft.mode == TaskMode.CHECK) "Напиши условие и своё решение" else "Напиши условие задачи",
                    color = palette.textFaint,
                    fontFamily = Serif,
                    fontSize = 20.sp,
                )
            },
            textStyle = TextStyle(color = palette.text, fontFamily = Serif, fontSize = 20.sp, lineHeight = 30.sp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = palette.ink,
            ),
        )
    }
}

@Composable
private fun FailedPage(error: SolveError, detail: String?) {
    val (title, text) = error.message()
    Spacer(Modifier.height(12.dp))
    PageTitle(title)
    PageText(text, Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite })
    if (!detail.isNullOrBlank() && (error == SolveError.BAD_REQUEST || error == SolveError.FORBIDDEN || error == SolveError.UNKNOWN)) {
        Text(
            text = detail,
            color = LocalPalette.current.textFaint,
            fontFamily = Sans,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

// ---- Панель под листом ----------------------------------------------------------------------

@Composable
private fun IdlePanel(hasKey: Boolean, cameraDenied: Boolean, actions: SolverActions) {
    if (!hasKey) Notice("Для решения нужен ключ Claude API.", "Добавить", actions.onOpenSettings)
    if (cameraDenied) Notice("Нет доступа к камере. Разреши его в настройках телефона.", "Открыть", actions.onOpenAppSettings)
    PrimaryButton("Сделать фото", actions.onCamera, icon = AppIcons.Camera)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton("Из галереи", actions.onGallery, Modifier.weight(1f), icon = AppIcons.Gallery)
        SecondaryButton("Написать", actions.onTextTask, Modifier.weight(1f), icon = AppIcons.Pencil)
    }
}

@Composable
private fun Notice(text: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            color = LocalPalette.current.textMuted,
            fontFamily = Sans,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f),
        )
        TextAction(action, onClick)
    }
}

@Composable
private fun EditingPanel(draft: Draft, hasKey: Boolean, quality: Quality, actions: SolverActions) {
    Segmented(
        options = listOf(TaskMode.SOLVE to "Решить задание", TaskMode.CHECK to "Проверить моё решение"),
        selected = draft.mode,
        onSelect = { mode -> actions.onDraftChange { it.copy(mode = mode) } },
        modifier = Modifier.fillMaxWidth(),
    )
    if (draft.photo != null) {
        PanelField(
            value = draft.text,
            onValueChange = { text -> actions.onDraftChange { it.copy(text = text) } },
            placeholder = if (draft.mode == TaskMode.CHECK) "Уточнение, если нужно" else "Уточнение: например, только № 3",
            imeAction = ImeAction.Done,
            onImeAction = {},
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // «Быстро» — дешёвая модель для простых примеров, «Точно» — самая сильная.
        Segmented(
            options = listOf(Quality.FAST to "Быстро", Quality.PRECISE to "Точно"),
            selected = quality,
            onSelect = actions.onQualityChange,
            modifier = Modifier.width(168.dp).height(54.dp),
        )
        PrimaryButton(
            text = if (draft.mode == TaskMode.CHECK) "Проверить" else "Решить",
            onClick = if (hasKey) actions.onSolve else actions.onOpenSettings,
            modifier = Modifier.weight(1f),
            enabled = draft.canSend,
        )
    }
}

@Composable
private fun SolvedPanel(state: SolverUiState.Solved, actions: SolverActions) {
    if (!state.entry.solution.solved) {
        PrimaryButton("Другое задание", actions.onReset, icon = AppIcons.Camera)
        return
    }
    val busy = state.asking != null && state.asking.error == null
    var question by rememberSaveable(state.entry.id) { mutableStateOf("") }
    val send = {
        if (question.isNotBlank() && !busy) {
            actions.onAsk(question)
            question = ""
        }
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PanelField(
            value = question,
            onValueChange = { question = it },
            placeholder = "Спросить по решению",
            imeAction = ImeAction.Send,
            onImeAction = send,
            modifier = Modifier.weight(1f),
        )
        val palette = LocalPalette.current
        val enabled = question.isNotBlank() && !busy
        Box(
            modifier = Modifier
                .size(56.dp)
                .alpha(if (enabled) 1f else 0.4f)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.ink)
                .clickable(enabled = enabled, role = Role.Button, onClick = send)
                .semantics { contentDescription = "Отправить вопрос" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Send, contentDescription = null, tint = palette.onInk, modifier = Modifier.size(22.dp))
        }
    }
    SecondaryButton("Новое задание", actions.onReset, icon = AppIcons.Camera)
}

@Composable
private fun FailedPanel(state: SolverUiState.Failed, actions: SolverActions) {
    val needsKey = state.error == SolveError.NO_KEY || state.error == SolveError.INVALID_KEY
    val canRetry = state.draft != null && state.error != SolveError.REFUSED && state.error != SolveError.BAD_IMAGE
    when {
        needsKey -> PrimaryButton("Открыть настройки", actions.onOpenSettings)
        canRetry -> PrimaryButton("Повторить", actions.onSolve)
    }
    if (state.draft != null) {
        SecondaryButton("Изменить задание", actions.onEditAgain)
    } else {
        SecondaryButton("Другое фото", actions.onReset)
    }
}

/** Поле ввода в панели под листом: уточнение к фото или вопрос по решению. */
@Composable
private fun PanelField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = { Text(placeholder, fontFamily = Sans, fontSize = 16.sp, maxLines = 1) },
        textStyle = TextStyle(fontFamily = Sans, fontSize = 16.sp),
        shape = RoundedCornerShape(16.dp),
        maxLines = 3,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onImeAction() }, onSend = { onImeAction() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = palette.key,
            unfocusedContainerColor = palette.key,
            unfocusedBorderColor = palette.keyBorder,
            unfocusedPlaceholderColor = palette.textFaint,
            focusedPlaceholderColor = palette.textFaint,
        ),
    )
}
