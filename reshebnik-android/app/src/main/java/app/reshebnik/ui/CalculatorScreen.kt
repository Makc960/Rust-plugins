package app.reshebnik.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import app.reshebnik.data.CalcRecord
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.reshebnik.calc.CalcError
import app.reshebnik.calc.CalcKey
import app.reshebnik.calc.CalcState
import app.reshebnik.calc.formatExpression

/**
 * @param records лента прошлых вычислений, от старых к новым
 * @param landscape альбомная раскладка: лист слева, справа — клавиши с инженерными функциями
 * @param onInsert подставить число из ленты в выражение
 */
@Composable
fun CalculatorScreen(
    state: CalcState,
    records: List<CalcRecord>,
    landscape: Boolean,
    onKey: (CalcKey) -> Unit,
    onInsert: (String) -> Unit,
    onClearHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (landscape) {
            val keyHeight = ((maxHeight - 16.dp - 6.dp * 4) / 5).coerceIn(30.dp, 64.dp)
            Row(Modifier.fillMaxSize()) {
                NotebookPage(Modifier.weight(0.36f).fillMaxHeight().padding(bottom = 8.dp)) {
                    Display(state, records, compact = true, onInsert, onClearHistory, onOpenSettings)
                }
                Row(
                    modifier = Modifier.weight(0.64f).fillMaxHeight().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScientificKeys(state.degrees, onKey, keyHeight, Modifier.weight(1f))
                    StandardKeys(onKey, keyHeight, compact = true, Modifier.weight(1f))
                }
            }
        } else {
            // Клавиши занимают около 60 % высоты, но не раздуваются на длинных экранах.
            val keyHeight = ((maxHeight - 96.dp) * 0.6f / 5).coerceIn(46.dp, 74.dp)
            Column(Modifier.fillMaxSize()) {
                NotebookPage(Modifier.weight(1f).fillMaxWidth()) {
                    Display(state, records, compact = false, onInsert, onClearHistory, onOpenSettings)
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.height(40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Key("√", "Квадратный корень", KeyKind.Function) { onKey(CalcKey.Sqrt) }
                        Key("x²", "В квадрат", KeyKind.Function) { onKey(CalcKey.Square) }
                        Key("xʸ", "Степень", KeyKind.Function) { onKey(CalcKey.Operator('^')) }
                        Key("π", "Число пи", KeyKind.Function) { onKey(CalcKey.Pi) }
                    }
                    StandardKeys(onKey, keyHeight, compact = false)
                }
            }
        }
    }
}

/**
 * Лист калькулятора: лента прошлых вычислений, под ней текущее выражение и результат.
 *
 * @param compact альбомная раскладка: без названия и с мелким шрифтом
 */
@Composable
private fun Display(
    state: CalcState,
    records: List<CalcRecord>,
    compact: Boolean,
    onInsert: (String) -> Unit,
    onClearHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = PageStartPadding, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!compact) Wordmark()
            Spacer(Modifier.weight(1f))
            IconAction(AppIcons.Settings, "Настройки", onOpenSettings)
        }

        // Только что посчитанный пример показан ниже крупно — в ленте его не повторяем.
        val current = records.lastOrNull()?.takeIf { state.evaluated && it.expression == state.previous && it.result == state.expression }
        val tape = if (current != null) records.dropLast(1) else records
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(start = PageStartPadding, end = PageEndPadding - 10.dp, bottom = 6.dp),
            horizontalAlignment = Alignment.End,
        ) {
            items(tape.size) { index ->
                val record = tape[tape.size - 1 - index]
                val line = formatExpression(record.expression) + " = " + formatExpression(record.result)
                Text(
                    text = line,
                    color = palette.textMuted,
                    fontFamily = Serif,
                    fontSize = if (compact) 16.sp else 18.sp,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClickLabel = "Подставить результат") { onInsert(record.result) }
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                        .semantics { contentDescription = "$line. Нажми, чтобы подставить результат" },
                )
            }
            if (tape.isNotEmpty()) {
                item { TextAction("Очистить ленту", onClearHistory, color = palette.textFaint, compact = true, modifier = Modifier.padding(end = 10.dp)) }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(start = PageStartPadding, end = PageEndPadding, bottom = if (compact) 6.dp else 14.dp),
            horizontalAlignment = Alignment.End,
        ) {
            AnimatedVisibility(
                visible = state.previous != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Text(
                    text = state.previous?.let { formatExpression(it) + " =" }.orEmpty(),
                    color = palette.textMuted,
                    fontFamily = Serif,
                    fontSize = if (compact) 16.sp else 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            val shown = formatExpression(state.expression)
            val color by animateColorAsState(
                targetValue = when {
                    state.expression.isEmpty() -> palette.textFaint
                    state.evaluated -> palette.ink
                    else -> palette.text
                },
                label = "expression",
            )
            val size = expressionSize(shown.length) * if (compact) 0.62f else 1f
            Text(
                text = shown.ifEmpty { "0" },
                color = color,
                fontFamily = Serif,
                fontSize = size,
                lineHeight = size * 1.15f,
                textAlign = TextAlign.End,
                maxLines = if (compact) 2 else 3,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.semantics { contentDescription = "Выражение: ${shown.ifEmpty { "0" }}" },
            )

            // Строка под выражением: результат «на лету» или ошибка. Высота постоянная, чтобы лист не прыгал.
            Box(Modifier.height(if (compact) 26.dp else 34.dp).padding(top = 2.dp), contentAlignment = Alignment.CenterEnd) {
                val error = state.error
                val preview = state.preview
                when {
                    error != null -> Text(error.message(), color = palette.danger, fontFamily = Sans, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    preview != null -> Text(
                        text = "= " + formatExpression(preview),
                        color = palette.ink,
                        fontFamily = Serif,
                        fontSize = if (compact) 18.sp else 22.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun expressionSize(length: Int): TextUnit = when {
    length <= 8 -> 58.sp
    length <= 11 -> 46.sp
    length <= 16 -> 36.sp
    else -> 28.sp
}

private fun CalcError.message(): String = when (this) {
    CalcError.DIVISION_BY_ZERO -> "На ноль делить нельзя"
    CalcError.DOMAIN -> "Корень из отрицательного числа"
    CalcError.TOO_LARGE -> "Слишком большое число"
    CalcError.INVALID -> "Выражение не дописано"
}

private enum class KeyKind { Digit, Operation, Danger, Equals, Function }

/** Обычные клавиши: цифры и четыре действия. */
@Composable
private fun StandardKeys(onKey: (CalcKey) -> Unit, keyHeight: Dp, compact: Boolean, modifier: Modifier = Modifier) {
    val gap = if (compact) 6.dp else 8.dp
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
        KeyRow(keyHeight, gap) {
            Key("AC", "Очистить", KeyKind.Danger, compact) { onKey(CalcKey.Clear) }
            Key("( )", "Скобка", KeyKind.Operation, compact) { onKey(CalcKey.Parenthesis) }
            Key("%", "Процент", KeyKind.Operation, compact) { onKey(CalcKey.Percent) }
            Key("÷", "Разделить", KeyKind.Operation, compact) { onKey(CalcKey.Operator('/')) }
        }
        KeyRow(keyHeight, gap) {
            DigitKeys("789", compact, onKey)
            Key("×", "Умножить", KeyKind.Operation, compact) { onKey(CalcKey.Operator('*')) }
        }
        KeyRow(keyHeight, gap) {
            DigitKeys("456", compact, onKey)
            Key("−", "Минус", KeyKind.Operation, compact) { onKey(CalcKey.Operator('-')) }
        }
        KeyRow(keyHeight, gap) {
            DigitKeys("123", compact, onKey)
            Key("+", "Плюс", KeyKind.Operation, compact) { onKey(CalcKey.Operator('+')) }
        }
        KeyRow(keyHeight, gap) {
            DigitKeys("0", compact, onKey)
            Key(",", "Запятая", KeyKind.Digit, compact) { onKey(CalcKey.Comma) }
            Key(
                label = "",
                description = "Стереть. Долгое нажатие очищает всё",
                kind = KeyKind.Danger,
                compact = compact,
                icon = AppIcons.Backspace,
                onLongClick = { onKey(CalcKey.Clear) },
            ) { onKey(CalcKey.Backspace) }
            Key("=", "Равно", KeyKind.Equals, compact) { onKey(CalcKey.Equals) }
        }
    }
}

/** Инженерные клавиши альбомной раскладки. */
@Composable
private fun ScientificKeys(degrees: Boolean, onKey: (CalcKey) -> Unit, keyHeight: Dp, modifier: Modifier = Modifier) = BoxWithConstraints(modifier) {
    val gap = 6.dp
    // На узком экране клавиши у́же подписи «arcsin» — уменьшаем шрифт под ширину клавиши.
    val labelScale = ((maxWidth - gap * 3) / 4 / 54.dp).coerceIn(0.6f, 1f)

    @Composable
    fun RowScope.Function(label: String, description: String, name: String) =
        Key(label, description, KeyKind.Function, compact = true, labelScale = labelScale) { onKey(CalcKey.Function(name)) }

    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        KeyRow(keyHeight, gap) {
            Key(
                label = if (degrees) "град" else "рад",
                description = if (degrees) "Углы в градусах. Нажми, чтобы считать в радианах" else "Углы в радианах. Нажми, чтобы считать в градусах",
                kind = KeyKind.Operation,
                compact = true,
                labelScale = labelScale,
            ) { onKey(CalcKey.AngleUnit) }
            Function("sin", "Синус", "sin")
            Function("cos", "Косинус", "cos")
            Function("tg", "Тангенс", "tan")
        }
        KeyRow(keyHeight, gap) {
            Key("π", "Число пи", KeyKind.Function, compact = true) { onKey(CalcKey.Pi) }
            Function("arcsin", "Арксинус", "asin")
            Function("arccos", "Арккосинус", "acos")
            Function("arctg", "Арктангенс", "atan")
        }
        KeyRow(keyHeight, gap) {
            Key("e", "Число e", KeyKind.Function, compact = true) { onKey(CalcKey.Euler) }
            Function("ln", "Натуральный логарифм", "ln")
            Function("lg", "Десятичный логарифм", "lg")
            Key("n!", "Факториал", KeyKind.Function, compact = true) { onKey(CalcKey.Factorial) }
        }
        KeyRow(keyHeight, gap) {
            Key("√", "Квадратный корень", KeyKind.Function, compact = true) { onKey(CalcKey.Sqrt) }
            Key("x²", "В квадрат", KeyKind.Function, compact = true) { onKey(CalcKey.Square) }
            Key("xʸ", "Степень", KeyKind.Function, compact = true) { onKey(CalcKey.Operator('^')) }
            Key("1/x", "Обратное число", KeyKind.Function, compact = true) { onKey(CalcKey.Reciprocal) }
        }
        KeyRow(keyHeight, gap) {
            Function("|x|", "Модуль", "abs")
            Function("∛", "Кубический корень", "cbrt")
            Key("(", "Открыть скобку", KeyKind.Function, compact = true) { onKey(CalcKey.OpenParenthesis) }
            Key(")", "Закрыть скобку", KeyKind.Function, compact = true) { onKey(CalcKey.CloseParenthesis) }
        }
    }
}

@Composable
private fun KeyRow(height: Dp, gap: Dp, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.height(height), horizontalArrangement = Arrangement.spacedBy(gap), content = content)
}

@Composable
private fun RowScope.DigitKeys(digits: String, compact: Boolean, onKey: (CalcKey) -> Unit) {
    digits.forEach { digit ->
        Key(digit.toString(), digit.toString(), KeyKind.Digit, compact) { onKey(CalcKey.Digit(digit)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.Key(
    label: String,
    description: String,
    kind: KeyKind,
    compact: Boolean = false,
    labelScale: Float = 1f,
    icon: ImageVector? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "key")

    val background = when (kind) {
        KeyKind.Digit, KeyKind.Danger -> palette.key
        KeyKind.Operation -> palette.keyTint
        KeyKind.Equals -> palette.ink
        KeyKind.Function -> Color.Transparent
    }
    val content = when (kind) {
        KeyKind.Digit -> palette.text
        KeyKind.Operation, KeyKind.Function -> palette.ink
        KeyKind.Danger -> palette.danger
        KeyKind.Equals -> palette.onInk
    }
    val bordered = kind == KeyKind.Digit || kind == KeyKind.Danger || kind == KeyKind.Function
    val shape = RoundedCornerShape(if (kind == KeyKind.Function || compact) 12.dp else 18.dp)
    // Имена функций (sin, arctg) набраны прямо, одиночные буквы (π, e) и «x²» — курсивом.
    val named = label.length > 2 && label.all { it.isLetter() }

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(background)
            .then(if (bordered) Modifier.border(BorderStroke(1.dp, palette.keyBorder), shape) else Modifier)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onLongClick = onLongClick,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                },
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(if (compact) 22.dp else 26.dp))
        } else {
            Text(
                text = label,
                color = content,
                // У шрифта интерфейса нет «√» и «π», поэтому функции набраны шрифтом листа.
                fontFamily = if (kind == KeyKind.Function) Serif else Sans,
                fontStyle = if (kind == KeyKind.Function && !named) FontStyle.Italic else FontStyle.Normal,
                fontWeight = if (kind == KeyKind.Digit || kind == KeyKind.Danger) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                softWrap = false,
                fontSize = when (kind) {
                    KeyKind.Function -> if (named) 15.sp else 19.sp
                    KeyKind.Operation, KeyKind.Equals -> if (label.length > 1) 22.sp else 30.sp
                    KeyKind.Danger -> 20.sp
                    KeyKind.Digit -> 26.sp
                } * (if (compact && kind != KeyKind.Function) 0.78f else 1f) * labelScale,
            )
        }
    }
}
