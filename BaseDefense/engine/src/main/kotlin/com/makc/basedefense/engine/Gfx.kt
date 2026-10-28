package com.makc.basedefense.engine

enum class Align { LEFT, CENTER, RIGHT }

/** The game's typefaces: [DISPLAY] for titles, buttons and numbers, the other two for reading. */
enum class Font(val file: String) {
    DISPLAY("RussoOne-Regular.ttf"),
    BOLD("Play-Bold.ttf"),
    BODY("Play-Regular.ttf"),
    ;

    val resource: String get() = "/fonts/$file"
}

/**
 * Everything the game draws goes through this, so the same code paints the
 * phone screen and the PNG screenshots in tests. Colors are ARGB ints,
 * text comes in the game's [Font]s and `y` is its baseline.
 */
interface Gfx {
    fun save()
    fun restore()
    fun translate(dx: Float, dy: Float)
    fun scale(s: Float)

    /** Clockwise, in degrees. */
    fun rotate(degrees: Float)
    fun clip(x: Float, y: Float, w: Float, h: Float)

    fun fillRect(x: Float, y: Float, w: Float, h: Float, color: Int)
    fun fillRoundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int)
    fun strokeRoundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int, width: Float)
    fun fillCircle(cx: Float, cy: Float, r: Float, color: Int)
    fun strokeCircle(cx: Float, cy: Float, r: Float, color: Int, width: Float)

    /** Pie slice; 0 degrees points right, the sweep runs clockwise. */
    fun fillArc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, color: Int)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float)
    fun polyline(xs: FloatArray, ys: FloatArray, n: Int, color: Int, width: Float)
    fun fillPolygon(xs: FloatArray, ys: FloatArray, n: Int, color: Int)

    /** A disc that fades from [color] in the middle to transparent at the edge. */
    fun glow(cx: Float, cy: Float, r: Float, color: Int)
    fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Align = Align.LEFT, font: Font = Font.BOLD)
    fun textWidth(s: String, size: Float, font: Font = Font.BOLD): Float

    /**
     * Draws [content], which covers (0, 0)-([w], [h]) in current units and never
     * changes for this [id]. A phone keeps it as a picture of [pxPerUnit] pixels
     * per unit and reuses it every frame; the default just draws it.
     */
    fun cached(id: String, w: Float, h: Float, pxPerUnit: Float, content: (Gfx) -> Unit) = content(this)

    /** True when [sprite] draws pictures from the atlas; otherwise the renderer paints shapes. */
    val sprites: Boolean get() = false

    /**
     * Draws [s] centred on ([cx], [cy]), [w] by [h] units, turned [degrees] clockwise.
     * A [tint] other than 0 paints the picture in that one colour, keeping its shape.
     */
    fun sprite(s: Sprite, cx: Float, cy: Float, w: Float, h: Float, degrees: Float = 0f, alpha: Float = 1f, tint: Int = 0) = Unit
}

object Colors {
    fun rgb(rgb: Int): Int = rgb or (0xFF shl 24)

    /** Multiplies the color's alpha by [a]. */
    fun alpha(color: Int, a: Float): Int {
        val base = color ushr 24
        val na = (base * a.coerceIn(0f, 1f)).toInt()
        return (na shl 24) or (color and 0xFFFFFF)
    }

    fun mix(c1: Int, c2: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val a = (c1 ushr shift) and 0xFF
            val b = (c2 ushr shift) and 0xFF
            return (a + (b - a) * k).toInt() and 0xFF
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun darker(color: Int, k: Float): Int = mix(color, color and 0xFF000000.toInt(), k)

    fun lighter(color: Int, k: Float): Int = mix(color, color or 0xFFFFFF, k)
}
