package app.reshebnik.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import app.reshebnik.R

/**
 * Цвета приложения. Светлая тема — тетрадный лист в клетку и синяя ручка,
 * тёмная — школьная доска и жёлтый мел.
 *
 * @param desk фон под клавишами и кнопками
 * @param paper лист, на котором показаны выражение и решение
 * @param grid линии клетки
 * @param margin красная линия полей
 * @param ink акцент: «чернила» в светлой теме, «мел» в тёмной
 * @param keyTint фон клавиш с операциями
 */
@Immutable
data class Palette(
    val desk: Color,
    val paper: Color,
    val grid: Color,
    val margin: Color,
    val ink: Color,
    val onInk: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val key: Color,
    val keyBorder: Color,
    val keyTint: Color,
    val danger: Color,
    val isDark: Boolean,
)

val NotebookPalette = Palette(
    desk = Color(0xFFEEF1F6),
    paper = Color(0xFFFCFCFA),
    grid = Color(0xFFD3DFF1),
    margin = Color(0xFFE26A6A),
    ink = Color(0xFF2443B8),
    onInk = Color(0xFFFFFFFF),
    text = Color(0xFF161B2E),
    textMuted = Color(0xFF545C75),
    textFaint = Color(0xFF6F778D),
    key = Color(0xFFFFFFFF),
    keyBorder = Color(0xFFDCE2ED),
    keyTint = Color(0xFFE1E8FA),
    danger = Color(0xFFC23838),
    isDark = false,
)

val ChalkboardPalette = Palette(
    desk = Color(0xFF0F1714),
    paper = Color(0xFF18241F),
    grid = Color(0xFF22312B),
    margin = Color(0xFFB8635C),
    ink = Color(0xFFF2D16B),
    onInk = Color(0xFF1C1A0B),
    text = Color(0xFFEDF1EC),
    textMuted = Color(0xFFA9B5AD),
    textFaint = Color(0xFF8A968E),
    key = Color(0xFF1F2D28),
    keyBorder = Color(0xFF2C3D36),
    keyTint = Color(0xFFF2D16B).copy(alpha = 0.14f).compositeOver(Color(0xFF1F2D28)),
    danger = Color(0xFFF2938A),
    isDark = true,
)

val LocalPalette = staticCompositionLocalOf { NotebookPalette }

/** Шрифт «листа»: выражения, условие и выкладки — как в учебнике. */
val Serif = FontFamily(
    Font(R.font.ptserif_regular, FontWeight.Normal),
    Font(R.font.ptserif_bold, FontWeight.Bold),
    Font(R.font.ptserif_italic, FontWeight.Normal, FontStyle.Italic),
)

/** Шрифт интерфейса: клавиши, кнопки, подписи. */
val Sans = FontFamily(
    Font(R.font.onest_regular, FontWeight.Normal),
    Font(R.font.onest_medium, FontWeight.Medium),
    Font(R.font.onest_semibold, FontWeight.SemiBold),
)

private fun Typography.withFamily(family: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = family)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

@Composable
fun ReshebnikTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (dark) ChalkboardPalette else NotebookPalette
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = palette.ink,
        onPrimary = palette.onInk,
        background = palette.desk,
        onBackground = palette.text,
        surface = palette.paper,
        onSurface = palette.text,
        surfaceContainerLow = palette.paper,
        onSurfaceVariant = palette.textMuted,
        outline = palette.textFaint,
        outlineVariant = palette.keyBorder,
        error = palette.danger,
    )
    MaterialTheme(colorScheme = scheme, typography = Typography().withFamily(Sans)) {
        CompositionLocalProvider(LocalPalette provides palette, content = content)
    }
}
