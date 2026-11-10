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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.reshebnik.solver.Asking
import app.reshebnik.solver.Block
import app.reshebnik.solver.Exchange
import app.reshebnik.solver.Solution
import app.reshebnik.solver.SolveError
import app.reshebnik.solver.TaskMode
import app.reshebnik.solver.Verdict
import app.reshebnik.solver.toPlainText
import kotlinx.coroutines.delay

@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = LocalPalette.current.text, fontFamily = Serif, fontSize = 30.sp, lineHeight = 36.sp)
}

@Composable
fun PageText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = LocalPalette.current.textMuted, fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = LocalPalette.current.textMuted, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp)
}

/** Двойная черта под ответом — как подчёркивают в тетради. */
fun Modifier.doubleUnderline(palette: Palette): Modifier = padding(bottom = 8.dp).drawBehind {
    val stroke = 1.5.dp.toPx()
    val first = size.height + 3.dp.toPx()
    val second = first + 4.dp.toPx()
    drawLine(palette.ink, Offset(0f, first), Offset(size.width, first), stroke)
    drawLine(palette.ink, Offset(0f, second), Offset(size.width, second), stroke)
}

@Composable
fun Photo(photo: Bitmap, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Image(
        bitmap = photo.asImageBitmap(),
        contentDescription = "Фото задания",
        contentScale = ContentScale.Fit,
        modifier = modifier.clip(shape).border(BorderStroke(1.dp, LocalPalette.current.keyBorder), shape),
    )
}

/**
 * Пояснения и формулы подряд, как строки в тетради.
 *
 * @param formulaSize кегль формул; он чуть крупнее текста, потому что у математического шрифта буквы ниже
 */
@Composable
fun Blocks(
    blocks: List<Block>,
    modifier: Modifier = Modifier,
    color: Color = LocalPalette.current.text,
    textSize: TextUnit = 17.sp,
    formulaSize: TextUnit = 20.sp,
    weight: FontWeight = FontWeight.Normal,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Text -> Text(
                    text = block.value,
                    color = color,
                    fontFamily = Serif,
                    fontWeight = weight,
                    fontSize = textSize,
                    lineHeight = textSize * 1.5f,
                )
                is Block.Formula -> MathFormula(block.value, fontSize = formulaSize, color = color)
            }
        }
    }
}

/** Три точки, которые «дописываются» по очереди: нейросеть ещё пишет. */
@Composable
fun WritingDots(modifier: Modifier = Modifier, size: TextUnit = 30.sp) {
    val phase by rememberInfiniteTransition(label = "dots").animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Row(modifier) {
        repeat(3) { index ->
            Text(
                text = ".",
                color = LocalPalette.current.ink,
                fontFamily = Serif,
                fontSize = size,
                modifier = Modifier.alpha(if (phase >= index + 1) 1f else 0.15f),
            )
        }
    }
}

/**
 * Решение на листе: условие, ответ, шаги. Годится и для готового решения, и для того, что ещё дописывается.
 *
 * @param writing решение ещё приходит: в конце мигают точки, а действия спрятаны
 * @param onExplain просьба объяснить шаг подробнее (номер шага с единицы); null — без такой кнопки
 */
@Composable
fun ColumnScope.SolutionContent(
    solution: Solution,
    mode: TaskMode,
    writing: Boolean,
    onExplain: ((Int) -> Unit)? = null,
) {
    val palette = LocalPalette.current
    if (solution.problem.isNotEmpty()) {
        SectionLabel("Условие")
        Blocks(solution.problem, Modifier.padding(top = 6.dp), textSize = 18.sp)
    }

    if (solution.verdict != Verdict.NONE) {
        // Пометка «красной ручкой», как у учителя.
        Text(
            text = if (solution.verdict == Verdict.CORRECT) "Решено верно ✓" else "В решении есть ошибка",
            color = palette.margin,
            fontFamily = Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 24.sp,
            modifier = Modifier.padding(top = 20.dp),
        )
    }

    if (solution.answer.isNotEmpty()) {
        // Высота строки задана заранее, чтобы лист не прыгал, когда появится кнопка «Скопировать».
        Row(Modifier.fillMaxWidth().padding(top = 18.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(if (mode == TaskMode.CHECK) "Итог" else "Ответ", Modifier.weight(1f))
            if (!writing) CopyAction(solution.answer.toPlainText())
        }
        Blocks(
            blocks = solution.answer,
            modifier = Modifier.width(IntrinsicSize.Max).widthIn(min = 48.dp).doubleUnderline(palette),
            color = palette.ink,
            textSize = 20.sp,
            formulaSize = 24.sp,
            weight = FontWeight.Bold,
        )
    }

    if (solution.steps.isNotEmpty()) {
        SectionLabel(if (mode == TaskMode.CHECK) "Разбор" else "Решение", Modifier.padding(top = 26.dp))
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
                Column(Modifier.weight(1f)) {
                    if (step.title.isNotBlank()) {
                        Text(step.title, color = palette.text, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp)
                    }
                    Blocks(step.body, Modifier.padding(top = 3.dp))
                    if (!writing && onExplain != null) {
                        TextAction(
                            text = "Объяснить подробнее",
                            onClick = { onExplain(index + 1) },
                            modifier = Modifier.padding(top = 2.dp),
                            compact = true,
                        )
                    }
                }
            }
        }
    }

    if (writing) {
        WritingDots(Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
    } else {
        Text(
            text = "Нейросеть может ошибаться — сверяй ход решения.",
            color = palette.textFaint,
            fontFamily = Sans,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}

/** Вопросы по решению: уже отвеченные и тот, на который ответ ещё пишется. */
@Composable
fun ColumnScope.Questions(exchanges: List<Exchange>, asking: Asking?, onRetry: () -> Unit, onDismiss: () -> Unit) {
    if (exchanges.isEmpty() && asking == null) return
    val palette = LocalPalette.current
    SectionLabel("Вопросы", Modifier.padding(top = 26.dp))
    exchanges.forEach { exchange ->
        QuestionText(exchange.question)
        Blocks(exchange.answer, Modifier.padding(top = 6.dp))
    }
    if (asking != null) {
        QuestionText(asking.question)
        Blocks(asking.partial, Modifier.padding(top = 6.dp))
        if (asking.error == null) {
            WritingDots(Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        } else {
            Text(
                text = asking.error.shortMessage(),
                color = palette.danger,
                fontFamily = Sans,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row {
                TextAction("Повторить", onRetry, compact = true)
                TextAction("Убрать вопрос", onDismiss, compact = true, color = palette.textMuted)
            }
        }
    }
}

@Composable
private fun QuestionText(question: String) {
    Text(
        text = question,
        color = LocalPalette.current.text,
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        modifier = Modifier.padding(top = 16.dp),
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

/** Заголовок и пояснение для экрана ошибки. */
fun SolveError.message(): Pair<String, String> = when (this) {
    SolveError.NO_KEY -> "Нужен ключ" to "Добавь ключ Claude API в настройках — без него нейросеть не ответит."
    SolveError.INVALID_KEY -> "Ключ не подошёл" to "Anthropic не принял ключ. Проверь, что он скопирован целиком и не отозван."
    SolveError.FORBIDDEN -> "Доступ закрыт" to "Anthropic отклонил запрос. Так бывает, если API недоступен в твоей стране или у ключа нет прав."
    SolveError.BILLING -> "Закончились средства" to "На счёте Anthropic не хватает средств. Пополни баланс в console.anthropic.com."
    SolveError.RATE_LIMIT -> "Слишком часто" to "Превышен лимит запросов. Подожди минуту и повтори."
    SolveError.OVERLOADED -> "Сервер занят" to "Сервис Anthropic сейчас перегружен. Повтори чуть позже."
    SolveError.NETWORK -> "Нет связи" to "Не получилось связаться с сервером. Проверь интернет и повтори."
    SolveError.REFUSED -> "Нейросеть отказалась" to "Это задание модель решать не стала. Попробуй другое задание или режим «Точно»."
    SolveError.TOO_LONG -> "Слишком длинное решение" to "Ответ не поместился. Оставь одно задание или уточни, какое решать."
    SolveError.BAD_REQUEST -> "Запрос не принят" to "Сервер отклонил запрос."
    SolveError.BAD_IMAGE -> "Фото не открылось" to "Не удалось прочитать файл. Выбери другое фото."
    SolveError.UNKNOWN -> "Что-то пошло не так" to "Не получилось получить решение. Повтори попытку."
}

private fun SolveError.shortMessage(): String = message().let { (title, text) -> "$title. $text" }
