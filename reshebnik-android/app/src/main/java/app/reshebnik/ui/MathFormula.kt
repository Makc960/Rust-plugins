package app.reshebnik.ui

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import app.reshebnik.R
import app.reshebnik.math.LatexParser
import app.reshebnik.math.MathFonts
import app.reshebnik.math.MathPlain
import app.reshebnik.math.MathRenderer
import kotlin.math.ceil
import kotlin.math.min

/** Шрифты формул загружаются один раз на всё приложение. */
object MathTypefaces {
    @Volatile
    private var cached: MathFonts? = null

    fun get(context: Context): MathFonts = cached ?: synchronized(this) {
        cached ?: MathFonts(
            math = ResourcesCompat.getFont(context, R.font.stix_two_math)!!,
            text = ResourcesCompat.getFont(context, R.font.ptserif_regular)!!,
        ).also { cached = it }
    }
}

/**
 * Формула, набранная как в учебнике. Слишком широкая формула уменьшается, чтобы поместиться по ширине.
 *
 * @param latex формула в LaTeX без окружающих знаков доллара
 */
@Composable
fun MathFormula(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 20.sp,
    color: Color = LocalPalette.current.text,
) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { fontSize.toPx() }
    val argb = color.toArgb()
    val box = remember(latex, sizePx, argb) {
        MathRenderer(MathTypefaces.get(context), sizePx, argb).layout(LatexParser.parse(latex))
    }
    val spoken = remember(latex) { MathPlain.of(latex) }
    Layout(
        content = {},
        modifier = modifier
            .padding(vertical = 3.dp)
            .semantics { contentDescription = spoken }
            .drawBehind {
                if (box.width <= 0f) return@drawBehind
                val scale = min(1f, size.width / box.width)
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    native.save()
                    native.scale(scale, scale)
                    box.draw(native, 0f, box.ascent)
                    native.restore()
                }
            },
    ) { _, constraints ->
        val scale = if (constraints.hasBoundedWidth && box.width > constraints.maxWidth) constraints.maxWidth / box.width else 1f
        val width = ceil(box.width * scale).toInt().coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = ceil(box.height * scale).toInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {}
    }
}
