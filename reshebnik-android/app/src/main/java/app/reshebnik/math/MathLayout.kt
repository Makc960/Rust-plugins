package app.reshebnik.math

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import app.reshebnik.math.MathNode.Accent
import app.reshebnik.math.MathNode.Fenced
import app.reshebnik.math.MathNode.Fraction
import app.reshebnik.math.MathNode.Root
import app.reshebnik.math.MathNode.Row
import app.reshebnik.math.MathNode.Scripts
import app.reshebnik.math.MathNode.Space
import app.reshebnik.math.MathNode.Symbol
import app.reshebnik.math.MathNode.Table
import kotlin.math.max
import kotlin.math.min

/**
 * @param math математический шрифт (STIX Two Math): буквы, цифры, знаки
 * @param text шрифт для слов кириллицей внутри формулы
 */
class MathFonts(val math: Typeface, val text: Typeface)

/** Готовый к рисованию кусок формулы. Размеры — в пикселях, [ascent] и [descent] отсчитаны от базовой линии. */
abstract class MathBox(val width: Float, val ascent: Float, val descent: Float) {
    val height: Float get() = ascent + descent

    abstract fun draw(canvas: Canvas, x: Float, baseline: Float)
}

/**
 * Раскладывает дерево формулы в коробки, как это делает TeX, только проще:
 * размеры и сдвиги взяты из таблицы MATH шрифта STIX Two Math и подобраны на глаз.
 *
 * @param textSize кегль основного уровня формулы, px
 */
class MathRenderer(private val fonts: MathFonts, private val textSize: Float, private val color: Int) {

    fun layout(node: MathNode): MathBox = layout(node, Style(0, 0))

    /**
     * @param level 0 — основной текст, 1 — степень или индекс, 2 — степень в степени
     * @param fractionDepth сколько дробей вокруг: дробь внутри дроби набирается мельче
     */
    private data class Style(val level: Int, val fractionDepth: Int)

    private val Style.size: Float get() = textSize * LEVEL_SCALE[level]
    private val Style.display: Boolean get() = level == 0 && fractionDepth == 0
    private val Style.axis: Float get() = size * AXIS

    private fun Style.script() = copy(level = min(level + 1, 2))

    private fun layout(node: MathNode, style: Style): MathBox = when (node) {
        is Row -> layoutRow(node.items, style)
        is Symbol -> layoutSymbol(node, style)
        is Fraction -> layoutFraction(node, style)
        is Root -> layoutRoot(node, style)
        is Scripts -> layoutScripts(node, style)
        is Fenced -> layoutFenced(node, style)
        is Table -> layoutTable(node, style)
        is Accent -> layoutAccent(node, style)
        is Space -> SpaceBox(node.em * style.size)
    }

    // ---- Строка -------------------------------------------------------------------------

    /** @param continuation строка продолжает соседнюю ячейку: знак в её начале — бинарный, с пробелом перед ним */
    private fun layoutRow(items: List<MathNode>, style: Style, continuation: Boolean = false): MathBox {
        if (items.isEmpty()) return SpaceBox(0f)
        val kinds = rowKinds(items, continuation)
        val parts = ArrayList<Part>(items.size)
        var x = 0f
        var ascent = 0f
        var descent = 0f
        items.forEachIndexed { index, item ->
            if (index > 0) x += gap(items[index - 1], kinds[index - 1].second, item, kinds[index].first, style)
            else if (continuation && kinds[0].first in OPERATORS_WITH_SPACE) x += style.size * 0.28f
            val box = layout(item, style)
            parts += Part(box, x, 0f)
            x += box.width
            ascent = max(ascent, box.ascent)
            descent = max(descent, box.descent)
        }
        return GroupBox(x, ascent, descent, parts)
    }

    /** Для каждого элемента — как он выглядит слева и справа; null у пробела. */
    private fun rowKinds(items: List<MathNode>, continuation: Boolean): List<Pair<Kind?, Kind?>> {
        val kinds = items.map { edgeKinds(it) }.toMutableList()
        var previous: Kind? = if (continuation) Kind.ORDINARY else null
        kinds.forEachIndexed { index, (left, right) ->
            if (left == null) return@forEachIndexed
            // Минус в начале, после знака или открывающей скобки — унарный: пробелы вокруг не нужны.
            val last = kinds.drop(index + 1).all { it.first == null }
            if (left == Kind.BINARY && (previous == null || previous in NO_OPERAND_BEFORE || last)) {
                kinds[index] = Kind.ORDINARY to Kind.ORDINARY
            }
            previous = kinds[index].second ?: right
        }
        return kinds
    }

    private fun edgeKinds(node: MathNode): Pair<Kind?, Kind?> = when (node) {
        is Symbol -> node.kind to node.kind
        is Scripts -> edgeKinds(node.base).let { (left, _) -> (left ?: Kind.ORDINARY) to if (left in OPERATOR_NAMES) left else Kind.ORDINARY }
        is Fenced -> Kind.OPEN to Kind.CLOSE
        is Space -> null to null
        is Row -> if (node.items.size == 1) edgeKinds(node.items[0]) else Kind.ORDINARY to Kind.ORDINARY
        else -> Kind.ORDINARY to Kind.ORDINARY
    }

    private fun gap(left: MathNode, a: Kind?, right: MathNode, b: Kind?, style: Style): Float {
        if (a == null || b == null) return 0f
        val em = style.size
        val script = style.level > 0
        return when {
            a == Kind.RELATION || b == Kind.RELATION -> if (script) 0f else em * 0.28f
            a == Kind.BINARY || b == Kind.BINARY -> if (script) 0f else em * 0.22f
            a == Kind.PUNCTUATION -> if (script) 0f else em * 0.2f
            b in OPERATOR_NAMES && (a in OPERANDS || a in OPERATOR_NAMES) -> em * 0.17f
            a in OPERATOR_NAMES && (b in OPERANDS && b != Kind.CLOSE) -> em * 0.17f
            // Слово, приклеенное к числу или букве: «5 см», «x при».
            b == Kind.TEXT && a in OPERANDS && !textOf(right).startsWithSpaceOrPunctuation() -> em * 0.22f
            a == Kind.TEXT && (b == Kind.ORDINARY || b == Kind.NUMBER || b == Kind.OPEN) && !textOf(left).endsWith(' ') -> em * 0.22f
            else -> 0f
        }
    }

    private fun textOf(node: MathNode): String = (node as? Symbol)?.text.orEmpty()

    private fun String.startsWithSpaceOrPunctuation() = isEmpty() || first() == ' ' || first() in ",.;:)"

    // ---- Символ -------------------------------------------------------------------------

    private fun layoutSymbol(symbol: Symbol, style: Style): MathBox {
        val size = style.size
        return when (symbol.kind) {
            Kind.LARGE_OPERATOR -> centered(GlyphBox(symbol.text, paint(size * if (style.display) 1.5f else 1.1f, fonts.math)), style)
            Kind.INTEGRAL -> centered(GlyphBox(symbol.text, paint(size * if (style.display) 1.9f else 1.25f, fonts.math)), style)
            Kind.TEXT -> {
                val cyrillic = symbol.text.any { it in 'Ѐ'..'ӿ' }
                GlyphBox(symbol.text, paint(size, if (cyrillic) fonts.text else fonts.math), minAscent = size * 0.45f)
            }
            Kind.ORDINARY -> GlyphBox(italic(symbol.text), paint(size, fonts.math), minAscent = size * 0.45f)
            Kind.NUMBER, Kind.FUNCTION -> GlyphBox(symbol.text, paint(size, fonts.math), minAscent = size * 0.45f)
            else -> GlyphBox(symbol.text, paint(size, fonts.math))
        }
    }

    /** Ставит знак так, чтобы его середина легла на математическую ось (линию дробной черты). */
    private fun centered(box: MathBox, style: Style): MathBox {
        val shift = (box.ascent - box.descent) / 2f - style.axis
        return GroupBox(box.width, box.ascent - shift, box.descent + shift, listOf(Part(box, 0f, shift)))
    }

    /** Переменные набираются курсивом: в математическом шрифте он лежит в отдельном блоке Юникода. */
    private fun italic(text: String): String {
        val out = StringBuilder()
        for (c in text) {
            val code = when (c) {
                'h' -> 0x210E
                in 'a'..'z' -> 0x1D44E + (c - 'a')
                in 'A'..'Z' -> 0x1D434 + (c - 'A')
                in 'α'..'ω' -> 0x1D6FC + (c - 'α')
                'ϑ' -> 0x1D717
                'ϕ' -> 0x1D719
                'ϵ' -> 0x1D716
                else -> c.code
            }
            out.appendCodePoint(code)
        }
        return out.toString()
    }

    // ---- Дробь --------------------------------------------------------------------------

    private fun layoutFraction(node: Fraction, style: Style): MathBox {
        val inner = if (style.display) style.copy(fractionDepth = 1) else style.script().copy(fractionDepth = style.fractionDepth + 1)
        val numerator = layout(node.numerator, inner)
        val denominator = layout(node.denominator, inner)
        val size = style.size
        val rule = max(1f, size * RULE)
        val gap = size * if (style.display) 0.16f else 0.1f
        val overhang = size * 0.1f
        val margin = size * 0.07f
        val content = max(numerator.width, denominator.width)
        val width = content + 2 * (overhang + margin)
        val axis = style.axis
        val parts = listOf(
            Part(numerator, (width - numerator.width) / 2f, -(axis + rule / 2f + gap + numerator.descent)),
            Part(denominator, (width - denominator.width) / 2f, -axis + rule / 2f + gap + denominator.ascent),
        )
        val line = strokePaint(rule)
        return GroupBox(
            width = width,
            ascent = axis + rule / 2f + gap + numerator.height,
            descent = max(0f, denominator.height + gap + rule / 2f - axis),
            parts = parts,
        ) { canvas, x, baseline ->
            canvas.drawLine(x + margin, baseline - axis, x + width - margin, baseline - axis, line)
        }
    }

    // ---- Корень -------------------------------------------------------------------------

    private fun layoutRoot(node: Root, style: Style): MathBox {
        val body = layout(node.body, style)
        val size = style.size
        val rule = max(1f, size * RULE)
        val top = max(body.ascent, size * 0.48f) + size * 0.14f + rule
        val bottom = max(body.descent, size * 0.04f) + size * 0.03f
        val height = top + bottom
        val signWidth = size * 0.46f + height * 0.07f
        val index = node.index?.let { layout(it, Style(2, style.fractionDepth)) }
        // Показатель стоит над «галочкой» знака корня; широкий показатель отодвигает знак вправо.
        val signX = if (index == null) 0f else max(0f, index.width - signWidth * 0.5f)
        val tickPeak = top - height * 0.5f
        val bodyX = signX + signWidth + size * 0.05f
        val width = bodyX + body.width + size * 0.1f

        val parts = buildList {
            add(Part(body, bodyX, 0f))
            if (index != null) {
                add(Part(index, signX + signWidth * 0.5f - index.width, -(tickPeak + size * 0.1f + index.descent)))
            }
        }
        val thin = strokePaint(rule).apply { strokeJoin = Paint.Join.MITER }
        val thick = strokePaint(rule * 1.9f).apply { strokeCap = Paint.Cap.ROUND }
        val indexTop = if (index == null) 0f else tickPeak + size * 0.1f + index.height
        return GroupBox(width, max(top, indexTop), bottom, parts) { canvas, x, baseline ->
            val x0 = x + signX
            val peakX = x0 + signWidth * 0.26f
            val peakY = baseline - tickPeak
            val bottomX = x0 + signWidth * 0.56f
            val bottomY = baseline + bottom - rule
            val topY = baseline - top + rule / 2f
            val path = Path().apply {
                moveTo(x0 + signWidth * 0.04f, peakY + height * 0.06f)
                lineTo(peakX, peakY)
                lineTo(bottomX, bottomY)
                lineTo(x0 + signWidth, topY)
                lineTo(x + width - size * 0.04f, topY)
            }
            canvas.drawPath(path, thin)
            // Нисходящий штрих у знака корня толще остальных.
            canvas.drawLine(peakX + rule * 0.4f, peakY + rule * 0.6f, bottomX - rule * 0.2f, bottomY - rule * 0.6f, thick)
        }
    }

    // ---- Степени и индексы --------------------------------------------------------------

    private fun layoutScripts(node: Scripts, style: Style): MathBox {
        val base = layout(node.base, style)
        val scriptStyle = style.script()
        val sup = node.sup?.let { layout(it, scriptStyle) }
        val sub = node.sub?.let { layout(it, scriptStyle) }
        val em = style.size
        val baseSymbol = node.base as? Symbol

        val stacked = style.display && baseSymbol != null && (baseSymbol.kind == Kind.LARGE_OPERATOR || baseSymbol.limits)
        if (stacked) {
            // Пределы над и под знаком: ∑, lim.
            val width = max(base.width, max(sup?.width ?: 0f, sub?.width ?: 0f))
            val parts = buildList {
                add(Part(base, (width - base.width) / 2f, 0f))
                if (sup != null) add(Part(sup, (width - sup.width) / 2f, -(base.ascent + em * 0.14f + sup.descent)))
                if (sub != null) add(Part(sub, (width - sub.width) / 2f, base.descent + em * 0.16f + sub.ascent))
            }
            return GroupBox(
                width,
                base.ascent + (sup?.let { em * 0.14f + it.height } ?: 0f),
                base.descent + (sub?.let { em * 0.16f + it.height } ?: 0f),
                parts,
            )
        }

        val integral = baseSymbol?.kind == Kind.INTEGRAL
        val slanted = baseSymbol?.kind == Kind.ORDINARY && baseSymbol.text.any { it.isLetter() }
        var supShift = 0f
        var subShift = 0f
        if (sup != null) {
            supShift = max(em * 0.36f, base.ascent - em * 0.26f)
            supShift = max(supShift, sup.descent + em * 0.22f)
        }
        if (sub != null) {
            subShift = max(em * 0.21f, base.descent + if (integral) -em * 0.1f else em * 0.08f)
            subShift = max(subShift, sub.ascent - em * 0.38f)
        }
        if (sup != null && sub != null) {
            val clearance = (supShift - sup.descent) - (sub.ascent - subShift)
            if (clearance < em * 0.16f) subShift += em * 0.16f - clearance
        }
        val supX = base.width + em * when {
            integral -> 0.12f
            slanted -> 0.05f
            else -> 0.02f
        }
        val subX = base.width + em * if (integral) -0.22f else 0.02f
        val parts = buildList {
            add(Part(base, 0f, 0f))
            if (sup != null) add(Part(sup, supX, -supShift))
            if (sub != null) add(Part(sub, subX, subShift))
        }
        val width = max(sup?.let { supX + it.width } ?: 0f, sub?.let { subX + it.width } ?: 0f) + em * 0.04f
        return GroupBox(
            max(width, base.width),
            max(base.ascent, sup?.let { supShift + it.ascent } ?: 0f),
            max(base.descent, sub?.let { subShift + it.descent } ?: 0f),
            parts,
        )
    }

    // ---- Скобки -------------------------------------------------------------------------

    private fun layoutFenced(node: Fenced, style: Style): MathBox {
        val body = layout(node.body, style)
        val half = max(body.ascent - style.axis, body.descent + style.axis)
        return fence(node.open, node.close, body, half * 2f, style)
    }

    private fun fence(open: String, close: String, body: MathBox, innerHeight: Float, style: Style): MathBox {
        val size = style.size
        val left = delimiter(open, innerHeight, style)
        val right = delimiter(close, innerHeight, style)
        val pad = size * 0.04f
        val parts = buildList {
            var x = 0f
            if (left != null) {
                add(Part(left, x, 0f))
                x += left.width + pad
            }
            add(Part(body, x, 0f))
            x += body.width
            if (right != null) add(Part(right, x + pad, 0f))
        }
        val width = (left?.let { it.width + pad } ?: 0f) + body.width + (right?.let { it.width + pad } ?: 0f)
        return GroupBox(
            width,
            max(body.ascent, max(left?.ascent ?: 0f, right?.ascent ?: 0f)),
            max(body.descent, max(left?.descent ?: 0f, right?.descent ?: 0f)),
            parts,
        )
    }

    /** Невысокую скобку берём из шрифта, высокую рисуем сами: растянутых вариантов Paint не даёт. */
    private fun delimiter(symbol: String, innerHeight: Float, style: Style): MathBox? {
        if (symbol.isEmpty()) return null
        val size = style.size
        if (innerHeight <= size * 1.25f) return GlyphBox(symbol, paint(size, fonts.math))
        return DelimiterBox(symbol, innerHeight + size * 0.12f, size, style.axis, strokePaint(max(1f, size * 0.055f)), fillPaint())
    }

    // ---- Системы и матрицы --------------------------------------------------------------

    private fun layoutTable(node: Table, style: Style): MathBox {
        val size = style.size
        val cells = node.rows.map { row ->
            row.mapIndexed { column, cell ->
                val items = if (cell is Row) cell.items else listOf(cell)
                layoutRow(items, style, continuation = node.layout == TableLayout.ALIGNED && column % 2 == 1)
            }
        }
        val columns = cells.maxOfOrNull { it.size } ?: 0
        if (columns == 0) return SpaceBox(0f)
        val columnWidths = FloatArray(columns) { column -> cells.maxOf { it.getOrNull(column)?.width ?: 0f } }
        val rowAscents = cells.map { row -> max(size * 0.72f, row.maxOfOrNull { it.ascent } ?: 0f) }
        val rowDescents = cells.map { row -> max(size * 0.24f, row.maxOfOrNull { it.descent } ?: 0f) }
        val rowGap = size * if (node.layout == TableLayout.MATRIX) 0.34f else 0.42f

        fun columnGap(before: Int): Float = size * when (node.layout) {
            TableLayout.ALIGNED -> if (before % 2 == 1) 0f else 1.4f
            else -> 0.9f
        }

        val columnX = FloatArray(columns)
        var x = 0f
        for (column in 0 until columns) {
            if (column > 0) x += columnGap(column)
            columnX[column] = x
            x += columnWidths[column]
        }
        val contentWidth = x
        val totalHeight = rowAscents.sum() + rowDescents.sum() + rowGap * (cells.size - 1)
        val ascent = totalHeight / 2f + style.axis
        val descent = totalHeight / 2f - style.axis

        val parts = ArrayList<Part>()
        var top = -ascent
        cells.forEachIndexed { rowIndex, row ->
            val baseline = top + rowAscents[rowIndex]
            row.forEachIndexed { column, box ->
                val free = columnWidths[column] - box.width
                val dx = when (node.layout) {
                    TableLayout.MATRIX -> free / 2f
                    TableLayout.ALIGNED -> if (column % 2 == 0) free else 0f
                    else -> 0f
                }
                parts += Part(box, columnX[column] + dx, baseline)
            }
            top += rowAscents[rowIndex] + rowDescents[rowIndex] + rowGap
        }
        val body = GroupBox(contentWidth, ascent, descent, parts)
        return if (node.open.isEmpty() && node.close.isEmpty()) body else fence(node.open, node.close, body, totalHeight, style)
    }

    // ---- Надстрочные знаки --------------------------------------------------------------

    private fun layoutAccent(node: Accent, style: Style): MathBox {
        val body = layout(node.body, style)
        val size = style.size
        val line = strokePaint(max(1f, size * 0.05f)).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val dot = fillPaint()
        val markY = body.ascent + size * 0.14f
        return GroupBox(body.width, body.ascent + size * 0.3f, body.descent, listOf(Part(body, 0f, 0f))) { canvas, x, baseline ->
            val y = baseline - markY
            val left = x + size * 0.04f
            val right = x + body.width - size * 0.02f
            val center = (left + right) / 2f
            when (node.mark) {
                AccentMark.BAR -> canvas.drawLine(left, y, right, y, line)
                AccentMark.VECTOR -> {
                    val end = max(right, left + size * 0.3f)
                    canvas.drawLine(left, y, end, y, line)
                    canvas.drawLine(end, y, end - size * 0.13f, y - size * 0.08f, line)
                    canvas.drawLine(end, y, end - size * 0.13f, y + size * 0.08f, line)
                }
                AccentMark.HAT -> {
                    val half = min(body.width / 2f, size * 0.22f)
                    canvas.drawLine(center - half, y + size * 0.04f, center, y - size * 0.08f, line)
                    canvas.drawLine(center, y - size * 0.08f, center + half, y + size * 0.04f, line)
                }
                AccentMark.DOT -> canvas.drawCircle(center, y - size * 0.02f, size * 0.055f, dot)
                AccentMark.TILDE -> {
                    val half = min(body.width / 2f, size * 0.25f)
                    val path = Path().apply {
                        moveTo(center - half, y + size * 0.03f)
                        cubicTo(center - half / 2f, y - size * 0.12f, center + half / 2f, y + size * 0.12f, center + half, y - size * 0.03f)
                    }
                    canvas.drawPath(path, line)
                }
            }
        }
    }

    // ---- Кисти --------------------------------------------------------------------------

    private fun paint(size: Float, typeface: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        color = this@MathRenderer.color
        textSize = size
        this.typeface = typeface
    }

    private fun strokePaint(width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@MathRenderer.color
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    private fun fillPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@MathRenderer.color
        style = Paint.Style.FILL
    }

    private companion object {
        /** Кегль степеней и «степеней в степени» относительно основного (ScriptPercentScaleDown шрифта). */
        val LEVEL_SCALE = floatArrayOf(1f, 0.7f, 0.55f)

        /** Высота математической оси над базовой линией, в долях кегля (AxisHeight шрифта). */
        const val AXIS = 0.258f

        /** Толщина дробной черты и черты корня. */
        const val RULE = 0.06f

        val NO_OPERAND_BEFORE = setOf(Kind.BINARY, Kind.RELATION, Kind.OPEN, Kind.PUNCTUATION, Kind.FUNCTION, Kind.LARGE_OPERATOR, Kind.INTEGRAL)
        val OPERATORS_WITH_SPACE = setOf(Kind.BINARY, Kind.RELATION)
        val OPERATOR_NAMES = setOf(Kind.FUNCTION, Kind.LARGE_OPERATOR, Kind.INTEGRAL)
        val OPERANDS = setOf(Kind.ORDINARY, Kind.NUMBER, Kind.CLOSE, Kind.TEXT)
    }
}

private class Part(val box: MathBox, val dx: Float, val dy: Float)

/** Несколько коробок с общим началом координат и, при необходимости, дорисованными линиями. */
private class GroupBox(
    width: Float,
    ascent: Float,
    descent: Float,
    private val parts: List<Part>,
    private val decoration: ((canvas: Canvas, x: Float, baseline: Float) -> Unit)? = null,
) : MathBox(width, ascent, descent) {
    override fun draw(canvas: Canvas, x: Float, baseline: Float) {
        decoration?.invoke(canvas, x, baseline)
        for (part in parts) part.box.draw(canvas, x + part.dx, baseline + part.dy)
    }
}

private class SpaceBox(width: Float) : MathBox(width, 0f, 0f) {
    override fun draw(canvas: Canvas, x: Float, baseline: Float) = Unit
}

private class GlyphBox private constructor(
    private val text: String,
    private val paint: Paint,
    width: Float,
    ascent: Float,
    descent: Float,
) : MathBox(width, ascent, descent) {
    override fun draw(canvas: Canvas, x: Float, baseline: Float) {
        canvas.drawText(text, x, baseline, paint)
    }

    companion object {
        /** @param minAscent нижняя граница высоты, чтобы «x» и «b» давали дробям и корням одинаковый зазор */
        operator fun invoke(text: String, paint: Paint, minAscent: Float = 0f): GlyphBox {
            val bounds = Rect()
            paint.getTextBounds(text, 0, text.length, bounds)
            return GlyphBox(text, paint, paint.measureText(text), max(minAscent, -bounds.top.toFloat()), max(0f, bounds.bottom.toFloat()))
        }
    }
}

/**
 * Скобка, нарисованная под нужную высоту.
 *
 * @param height полная высота скобки
 * @param axis высота оси, относительно которой скобка симметрична
 */
private class DelimiterBox(
    private val symbol: String,
    fullHeight: Float,
    private val size: Float,
    axis: Float,
    private val stroke: Paint,
    private val fill: Paint,
) : MathBox(widthOf(symbol, fullHeight, size), fullHeight / 2f + axis, fullHeight / 2f - axis) {

    init {
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
    }

    override fun draw(canvas: Canvas, x: Float, baseline: Float) {
        val top = baseline - ascent
        val bottom = baseline + descent
        val middle = (top + bottom) / 2f
        val inset = stroke.strokeWidth
        val left = x + inset
        val right = x + width - inset
        when (symbol) {
            "(", ")" -> {
                // Серп: толще в середине, острый на концах.
                val opening = symbol == "("
                val tip = if (opening) right else left
                val outer = if (opening) left - (right - left) * 0.75f else right + (right - left) * 0.75f
                val thickness = stroke.strokeWidth * 2.6f * if (opening) 1f else -1f
                val path = Path().apply {
                    moveTo(tip, top)
                    quadTo(outer, middle, tip, bottom)
                    quadTo(outer + thickness, middle, tip, top)
                    close()
                }
                canvas.drawPath(path, fill)
            }
            "[", "]", "⌊", "⌋", "⌈", "⌉" -> {
                val opening = symbol in "[⌊⌈"
                val spine = if (opening) left else right
                val arm = if (opening) right else left
                canvas.drawLine(spine, top, spine, bottom, stroke)
                if (symbol !in "⌊⌋") canvas.drawLine(spine, top, arm, top, stroke)
                if (symbol !in "⌈⌉") canvas.drawLine(spine, bottom, arm, bottom, stroke)
            }
            "{", "}" -> {
                val opening = symbol == "{"
                val arm = if (opening) right else left
                val point = if (opening) left else right
                val spine = (left + right) / 2f
                val curl = min(size * 0.45f, height / 4f)
                val path = Path().apply {
                    moveTo(arm, top)
                    quadTo(spine, top, spine, top + curl)
                    lineTo(spine, middle - curl)
                    quadTo(spine, middle, point, middle)
                    quadTo(spine, middle, spine, middle + curl)
                    lineTo(spine, bottom - curl)
                    quadTo(spine, bottom, arm, bottom)
                }
                canvas.drawPath(path, stroke)
            }
            "⟨", "⟩" -> {
                val opening = symbol == "⟨"
                val tip = if (opening) left else right
                val open = if (opening) right else left
                canvas.drawLine(open, top, tip, middle, stroke)
                canvas.drawLine(tip, middle, open, bottom, stroke)
            }
            "‖" -> {
                canvas.drawLine(left, top, left, bottom, stroke)
                canvas.drawLine(right, top, right, bottom, stroke)
            }
            else -> canvas.drawLine(x + width / 2f, top, x + width / 2f, bottom, stroke)
        }
    }

    private companion object {
        fun widthOf(symbol: String, height: Float, size: Float): Float = when (symbol) {
            "(", ")" -> (height * 0.14f).coerceIn(size * 0.3f, size * 0.55f)
            "{", "}" -> size * 0.5f
            "⟨", "⟩" -> (height * 0.18f).coerceIn(size * 0.3f, size * 0.5f)
            "‖" -> size * 0.34f
            "[", "]", "⌊", "⌋", "⌈", "⌉" -> size * 0.34f
            else -> size * 0.22f
        }
    }
}
