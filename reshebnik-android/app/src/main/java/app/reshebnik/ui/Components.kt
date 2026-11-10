package app.reshebnik.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Сторона клетки. В настоящей тетради — 5 мм. */
val GridCell = 22.dp

/** Ширина полей справа от красной линии. */
val PageMargin = 34.dp

/** Отступ содержимого листа от правого края: поля плюс воздух до линии. */
val PageEndPadding = PageMargin + 14.dp

val PageStartPadding = 22.dp

/** Лист в клетку с красной линией полей. */
fun Modifier.notebookPaper(palette: Palette): Modifier = drawBehind {
    drawRect(palette.paper)
    val step = GridCell.toPx()
    val hairline = 1.dp.toPx() * 0.75f
    var x = step
    while (x < size.width) {
        drawLine(palette.grid, Offset(x, 0f), Offset(x, size.height), hairline)
        x += step
    }
    var y = step
    while (y < size.height) {
        drawLine(palette.grid, Offset(0f, y), Offset(size.width, y), hairline)
        y += step
    }
    val marginX = size.width - PageMargin.toPx()
    drawLine(palette.margin, Offset(marginX, 0f), Offset(marginX, size.height), 1.25.dp.toPx())
}

@Composable
fun NotebookPage(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
    Box(
        modifier = modifier
            .shadow(if (palette.isDark) 0.dp else 6.dp, shape, ambientColor = palette.ink, spotColor = palette.ink)
            .clip(shape)
            .notebookPaper(palette),
        content = content,
    )
}

/** Название приложения, «написанное» в углу листа. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Text(
        text = "Решебник",
        modifier = modifier,
        color = LocalPalette.current.ink,
        fontFamily = Serif,
        fontStyle = FontStyle.Italic,
        fontSize = 20.sp,
    )
}

@Composable
fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = LocalPalette.current.textMuted) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val palette = LocalPalette.current
    ButtonFrame(text, onClick, modifier, icon, background = palette.ink, content = palette.onInk, border = null)
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val palette = LocalPalette.current
    ButtonFrame(text, onClick, modifier, icon, background = palette.key, content = palette.text, border = palette.keyBorder)
}

@Composable
private fun ButtonFrame(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: ImageVector?,
    background: Color,
    content: Color,
    border: Color?,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(shape)
            .background(background)
            .then(if (border != null) Modifier.border(BorderStroke(1.dp, border), shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = content, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
    }
}

/** Иконки в одном стиле: контур 1,8 со скруглёнными концами на сетке 24×24. */
object AppIcons {
    val Camera = strokeIcon(
        "M4 8h3l1.5-2.5h7L17 8h3a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z",
        "M9 13.5a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
    )
    val Gallery = strokeIcon(
        "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z",
        "M7.5 9a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0",
        "M20 15.5l-4.5-4.5L6 20",
    )
    val Backspace = strokeIcon(
        "M9 5h10a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H9l-6-7z",
        "M11.5 9.5l5 5",
        "M16.5 9.5l-5 5",
    )
    val Calculator = strokeIcon(
        "M6 3h12a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
        "M8.5 7.5h7",
        "M8.5 12h.01", "M12 12h.01", "M15.5 12h.01",
        "M8.5 16.5h.01", "M12 16.5h.01", "M15.5 16.5h.01",
    )
    val Settings = strokeIcon(
        "M4 7.5h9", "M19 7.5h1", "M13 7.5a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
        "M4 16.5h1", "M11 16.5h9", "M5 16.5a3 3 0 1 0 6 0a3 3 0 1 0-6 0",
    )
    val Copy = strokeIcon(
        "M10 9h9a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1v-9a1 1 0 0 1 1-1z",
        "M15 5.5V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v9a1 1 0 0 0 1 1h.5",
    )
    val Check = strokeIcon("M5 12.5l4.5 4.5L19 7.5")

    private fun strokeIcon(vararg paths: String, size: Dp = 24.dp): ImageVector =
        ImageVector.Builder(defaultWidth = size, defaultHeight = size, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                paths.forEach { path ->
                    addPath(
                        pathData = addPathNodes(path),
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = 1.8f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            .build()
}

/** Текстовая кнопка для второстепенного действия внутри строки. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = LocalPalette.current.ink) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}
