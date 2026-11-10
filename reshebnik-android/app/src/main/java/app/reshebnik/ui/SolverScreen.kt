package app.reshebnik.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.reshebnik.solver.Solution
import app.reshebnik.solver.SolveError
import app.reshebnik.solver.SolverUiState
import kotlinx.coroutines.delay

/**
 * @param hasKey сохранён ли ключ API; без него вместо отправки открываются настройки
 */
@Composable
fun SolverScreen(
    state: SolverUiState,
    hasKey: Boolean,
    hint: String,
    onHintChange: (String) -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onSolve: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        NotebookPage(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = PageStartPadding, top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Wordmark()
                    IconAction(AppIcons.Settings, "Настройки", onOpenSettings)
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = PageStartPadding, end = PageEndPadding, top = 8.dp, bottom = 24.dp),
                ) {
                    when (state) {
                        SolverUiState.Idle -> IdlePage()
                        is SolverUiState.Preview -> PreviewPage(state.photo, hint, onHintChange, onSolve)
                        is SolverUiState.Solving -> SolvingPage(state.photo)
                        is SolverUiState.Solved -> SolvedPage(state.solution)
                        is SolverUiState.Failed -> FailedPage(state.error, state.detail)
                    }
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                SolverUiState.Idle -> {
                    if (!hasKey) KeyNotice(onOpenSettings)
                    PrimaryButton("Сделать фото", onCamera, icon = AppIcons.Camera)
                    SecondaryButton("Выбрать из галереи", onGallery, icon = AppIcons.Gallery)
                }
                is SolverUiState.Preview -> {
                    PrimaryButton("Решить", if (hasKey) onSolve else onOpenSettings)
                    SecondaryButton("Другое фото", onReset)
                }
                is SolverUiState.Solving -> SecondaryButton("Отменить", onCancel)
                is SolverUiState.Solved -> PrimaryButton("Новое задание", onReset, icon = AppIcons.Camera)
                is SolverUiState.Failed -> {
                    when {
                        state.error.needsKey() -> PrimaryButton("Открыть настройки", onOpenSettings)
                        state.photo != null && state.error.canRetry() -> PrimaryButton("Повторить", onSolve)
                    }
                    SecondaryButton("Другое фото", onReset)
                }
            }
        }
    }
}

private fun SolveError.needsKey() = this == SolveError.NO_KEY || this == SolveError.INVALID_KEY

private fun SolveError.canRetry() = this != SolveError.REFUSED && this != SolveError.BAD_IMAGE

@Composable
private fun PageTitle(text: String) {
    Text(
        text = text,
        color = LocalPalette.current.text,
        fontFamily = Serif,
        fontSize = 30.sp,
        lineHeight = 36.sp,
    )
}

@Composable
private fun PageText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = LocalPalette.current.textMuted,
        fontFamily = Sans,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = LocalPalette.current.textMuted,
        fontFamily = Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
    )
}

@Composable
private fun IdlePage() {
    val palette = LocalPalette.current
    Spacer(Modifier.height(12.dp))
    PageTitle("Сфотографируй задание")
    PageText(
        "Пример, уравнение или задача — нейросеть пришлёт ответ и решение по шагам.",
        Modifier.padding(top = 10.dp),
    )
    // Образец того, что получится: строчки «ручкой» и оценка учителя на полях.
    Box(Modifier.fillMaxWidth().padding(top = 30.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("2x + 6 = 14", "2x = 8").forEach { line -> InkLine(line) }
            InkLine("x = 4", Modifier.doubleUnderline(palette))
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
private fun InkLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = LocalPalette.current.ink,
        fontFamily = Serif,
        fontStyle = FontStyle.Italic,
        fontSize = 24.sp,
    )
}

/** Двойная черта под ответом — как подчёркивают в тетради. */
private fun Modifier.doubleUnderline(palette: Palette): Modifier = padding(bottom = 7.dp).drawBehind {
    val stroke = 1.5.dp.toPx()
    val first = size.height + 2.dp.toPx()
    val second = first + 4.dp.toPx()
    drawLine(palette.ink, Offset(0f, first), Offset(size.width, first), stroke)
    drawLine(palette.ink, Offset(0f, second), Offset(size.width, second), stroke)
}

@Composable
private fun Photo(photo: Bitmap, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Image(
        bitmap = photo.asImageBitmap(),
        contentDescription = "Фото задания",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .heightIn(max = 300.dp)
            .clip(shape)
            .border(BorderStroke(1.dp, LocalPalette.current.keyBorder), shape),
    )
}

@Composable
private fun PreviewPage(photo: Bitmap, hint: String, onHintChange: (String) -> Unit, onSolve: () -> Unit) {
    val palette = LocalPalette.current
    Photo(photo)
    OutlinedTextField(
        value = hint,
        onValueChange = onHintChange,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        label = { Text("Уточнение, если нужно") },
        placeholder = { Text("Например: только № 3") },
        textStyle = TextStyle(fontFamily = Sans, fontSize = 16.sp),
        shape = RoundedCornerShape(14.dp),
        maxLines = 3,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSolve() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = palette.paper,
            unfocusedContainerColor = palette.paper,
            unfocusedBorderColor = palette.textFaint,
            unfocusedPlaceholderColor = palette.textFaint,
            focusedPlaceholderColor = palette.textFaint,
        ),
    )
}

@Composable
private fun SolvingPage(photo: Bitmap) {
    val palette = LocalPalette.current
    Photo(photo, Modifier.alpha(0.55f))
    Row(Modifier.padding(top = 22.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.Bottom) {
        Text("Решаю", color = palette.ink, fontFamily = Serif, fontStyle = FontStyle.Italic, fontSize = 30.sp)
        WritingDots()
    }
    PageText("Это может занять до минуты.", Modifier.padding(top = 6.dp))
}

/** Три точки, которые «дописываются» по очереди. */
@Composable
private fun WritingDots() {
    val phase by rememberInfiniteTransition(label = "dots").animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Row {
        repeat(3) { index ->
            Text(
                text = ".",
                color = LocalPalette.current.ink,
                fontFamily = Serif,
                fontSize = 30.sp,
                modifier = Modifier.alpha(if (phase >= index + 1) 1f else 0.15f),
            )
        }
    }
}

@Composable
private fun ColumnScope.SolvedPage(solution: Solution) {
    val palette = LocalPalette.current
    if (!solution.solved) {
        Spacer(Modifier.height(12.dp))
        PageTitle("Не вижу задания")
        PageText(
            solution.problem.ifBlank { "Попробуй снять ближе и при хорошем свете, чтобы текст был в фокусе." },
            Modifier.padding(top = 10.dp),
        )
        return
    }

    SectionLabel("Условие")
    Text(
        text = solution.problem,
        color = palette.text,
        fontFamily = Serif,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        modifier = Modifier.padding(top = 6.dp),
    )

    Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("Ответ", Modifier.weight(1f))
        CopyAction(solution.answer)
    }
    Text(
        text = solution.answer,
        color = palette.ink,
        fontFamily = Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        modifier = Modifier.widthIn(min = 48.dp).doubleUnderline(palette),
    )

    if (solution.steps.isNotEmpty()) {
        SectionLabel("Решение", Modifier.padding(top = 26.dp))
        solution.steps.forEachIndexed { index, step ->
            Row(Modifier.padding(top = 14.dp)) {
                Text(
                    text = "${index + 1}.",
                    color = palette.ink,
                    fontFamily = Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    modifier = Modifier.width(28.dp),
                )
                Column {
                    if (step.title.isNotBlank()) {
                        Text(
                            text = step.title,
                            color = palette.text,
                            fontFamily = Sans,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                        )
                    }
                    Text(
                        text = step.text,
                        color = palette.text,
                        fontFamily = Serif,
                        fontSize = 17.sp,
                        lineHeight = 26.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }

    Text(
        text = "Нейросеть может ошибаться — сверяй ход решения.",
        color = palette.textFaint,
        fontFamily = Sans,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        modifier = Modifier.padding(top = 26.dp),
    )
}

@Composable
private fun CopyAction(text: String) {
    val context = LocalContext.current
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }
    IconAction(
        icon = if (copied) AppIcons.Check else AppIcons.Copy,
        label = if (copied) "Скопировано" else "Скопировать ответ",
        tint = if (copied) LocalPalette.current.ink else LocalPalette.current.textMuted,
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Ответ", text))
            copied = true
        },
    )
}

@Composable
private fun FailedPage(error: SolveError, detail: String?) {
    val (title, text) = when (error) {
        SolveError.NO_KEY -> "Нужен ключ" to "Добавь ключ Claude API в настройках — без него нейросеть не ответит."
        SolveError.INVALID_KEY -> "Ключ не подошёл" to "Anthropic не принял ключ. Проверь, что он скопирован целиком и не отозван."
        SolveError.FORBIDDEN -> "Доступ закрыт" to "Anthropic отклонил запрос. Так бывает, если API недоступен в твоей стране или у ключа нет прав."
        SolveError.BILLING -> "Закончились средства" to "На счёте Anthropic не хватает средств. Пополни баланс в console.anthropic.com."
        SolveError.RATE_LIMIT -> "Слишком часто" to "Превышен лимит запросов. Подожди минуту и повтори."
        SolveError.OVERLOADED -> "Сервер занят" to "Сервис Anthropic сейчас перегружен. Повтори чуть позже."
        SolveError.NETWORK -> "Нет связи" to "Не получилось связаться с сервером. Проверь интернет и повтори."
        SolveError.REFUSED -> "Нейросеть отказалась" to "Это задание модель решать не стала. Попробуй другое фото."
        SolveError.TOO_LONG -> "Слишком длинное решение" to "Ответ не поместился. Оставь на фото одно задание или уточни, какое решать."
        SolveError.BAD_REQUEST -> "Запрос не принят" to "Сервер отклонил запрос."
        SolveError.BAD_IMAGE -> "Фото не открылось" to "Не удалось прочитать файл. Выбери другое фото."
        SolveError.UNKNOWN -> "Что-то пошло не так" to "Не получилось получить решение. Повтори попытку."
    }
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

@Composable
private fun KeyNotice(onOpenSettings: () -> Unit) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Для решения по фото нужен ключ Claude API.",
            color = palette.textMuted,
            fontFamily = Sans,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f),
        )
        TextAction("Добавить", onOpenSettings)
    }
}
