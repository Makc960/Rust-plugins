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

@Composable
fun CalculatorScreen(state: CalcState, onKey: (CalcKey) -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Клавиши занимают около 60 % высоты, но не раздуваются на длинных экранах.
        val keyHeight = ((maxHeight - 96.dp) * 0.6f / 5).coerceIn(46.dp, 74.dp)
        Column(Modifier.fillMaxSize()) {
            NotebookPage(Modifier.weight(1f).fillMaxWidth()) {
                Display(state, onOpenSettings)
            }
            Keypad(onKey, keyHeight)
        }
    }
}

@Composable
private fun Display(state: CalcState, onOpenSettings: () -> Unit) {
    val palette = LocalPalette.current
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Wordmark(Modifier.align(Alignment.TopStart).padding(start = PageStartPadding, top = 14.dp))
        IconAction(
            icon = AppIcons.Settings,
            label = "Настройки",
            onClick = onOpenSettings,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .fillMaxWidth()
                .padding(start = PageStartPadding, end = PageEndPadding, bottom = 14.dp),
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
                    fontSize = 20.sp,
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
            val size = expressionSize(shown.length)
            Text(
                text = shown.ifEmpty { "0" },
                color = color,
                fontFamily = Serif,
                fontSize = size,
                lineHeight = size * 1.15f,
                textAlign = TextAlign.End,
                maxLines = 3,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.semantics { contentDescription = "Выражение: ${shown.ifEmpty { "0" }}" },
            )

            // Строка под выражением: результат «на лету» или ошибка. Высота постоянная, чтобы лист не прыгал.
            Box(Modifier.height(34.dp).padding(top = 2.dp), contentAlignment = Alignment.CenterEnd) {
                val error = state.error
                val preview = state.preview
                when {
                    error != null -> Text(error.message(), color = palette.danger, fontFamily = Sans, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    preview != null -> Text(
                        text = "= " + formatExpression(preview),
                        color = palette.ink,
                        fontFamily = Serif,
                        fontSize = 22.sp,
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

@Composable
private fun Keypad(onKey: (CalcKey) -> Unit, keyHeight: Dp) {
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
        KeyRow(keyHeight) {
            Key("AC", "Очистить", KeyKind.Danger) { onKey(CalcKey.Clear) }
            Key("( )", "Скобка", KeyKind.Operation) { onKey(CalcKey.Parenthesis) }
            Key("%", "Процент", KeyKind.Operation) { onKey(CalcKey.Percent) }
            Key("÷", "Разделить", KeyKind.Operation) { onKey(CalcKey.Operator('/')) }
        }
        KeyRow(keyHeight) {
            DigitKeys("789", onKey)
            Key("×", "Умножить", KeyKind.Operation) { onKey(CalcKey.Operator('*')) }
        }
        KeyRow(keyHeight) {
            DigitKeys("456", onKey)
            Key("−", "Минус", KeyKind.Operation) { onKey(CalcKey.Operator('-')) }
        }
        KeyRow(keyHeight) {
            DigitKeys("123", onKey)
            Key("+", "Плюс", KeyKind.Operation) { onKey(CalcKey.Operator('+')) }
        }
        KeyRow(keyHeight) {
            DigitKeys("0", onKey)
            Key(",", "Запятая", KeyKind.Digit) { onKey(CalcKey.Comma) }
            Key(
                label = "",
                description = "Стереть. Долгое нажатие очищает всё",
                kind = KeyKind.Danger,
                icon = AppIcons.Backspace,
                onLongClick = { onKey(CalcKey.Clear) },
            ) { onKey(CalcKey.Backspace) }
            Key("=", "Равно", KeyKind.Equals) { onKey(CalcKey.Equals) }
        }
    }
}

@Composable
private fun KeyRow(height: Dp, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.height(height), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun RowScope.DigitKeys(digits: String, onKey: (CalcKey) -> Unit) {
    digits.forEach { digit ->
        Key(digit.toString(), digit.toString(), KeyKind.Digit) { onKey(CalcKey.Digit(digit)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.Key(
    label: String,
    description: String,
    kind: KeyKind,
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
    val shape = RoundedCornerShape(if (kind == KeyKind.Function) 12.dp else 18.dp)

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
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(26.dp))
        } else {
            Text(
                text = label,
                color = content,
                // У шрифта интерфейса нет «√» и «π», поэтому функции набраны шрифтом листа.
                fontFamily = if (kind == KeyKind.Function) Serif else Sans,
                fontStyle = if (kind == KeyKind.Function) FontStyle.Italic else FontStyle.Normal,
                fontWeight = if (kind == KeyKind.Digit || kind == KeyKind.Danger) FontWeight.Medium else FontWeight.Normal,
                fontSize = when (kind) {
                    KeyKind.Function -> 19.sp
                    KeyKind.Operation, KeyKind.Equals -> if (label.length > 1) 22.sp else 30.sp
                    KeyKind.Danger -> 20.sp
                    KeyKind.Digit -> 26.sp
                },
            )
        }
    }
}
