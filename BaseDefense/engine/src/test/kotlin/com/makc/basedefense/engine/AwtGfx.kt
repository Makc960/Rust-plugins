package com.makc.basedefense.engine

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.MultipleGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

/** [Gfx] on Java2D, for screenshots in tests. */
class AwtGfx(val image: BufferedImage) : Gfx {
    private val g: Graphics2D = image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }
    private val stack = ArrayDeque<Pair<AffineTransform, Shape?>>()
    private val baseFont = Font("DejaVu Sans", Font.BOLD, 12)

    private fun color(c: Int) = Color(c, true)

    override fun save() {
        stack.addLast(g.transform to g.clip)
    }

    override fun restore() {
        val (t, c) = stack.removeLast()
        g.transform = t
        g.clip = c
    }

    override fun translate(dx: Float, dy: Float) = g.translate(dx.toDouble(), dy.toDouble())

    override fun scale(s: Float) = g.scale(s.toDouble(), s.toDouble())

    override fun rotate(degrees: Float) = g.rotate(Math.toRadians(degrees.toDouble()))

    override fun clip(x: Float, y: Float, w: Float, h: Float) = g.clip(Rectangle2D.Float(x, y, w, h))

    private fun fill(s: Shape, c: Int) {
        g.color = color(c)
        g.fill(s)
    }

    private fun draw(s: Shape, c: Int, width: Float) {
        g.color = color(c)
        g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(s)
    }

    override fun fillRect(x: Float, y: Float, w: Float, h: Float, color: Int) = fill(Rectangle2D.Float(x, y, w, h), color)

    override fun fillRoundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) =
        fill(RoundRectangle2D.Float(x, y, w, h, r * 2, r * 2), color)

    override fun strokeRoundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int, width: Float) =
        draw(RoundRectangle2D.Float(x, y, w, h, r * 2, r * 2), color, width)

    override fun fillCircle(cx: Float, cy: Float, r: Float, color: Int) = fill(Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2), color)

    override fun strokeCircle(cx: Float, cy: Float, r: Float, color: Int, width: Float) =
        draw(Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2), color, width)

    override fun fillArc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, color: Int) =
        fill(Arc2D.Float(cx - r, cy - r, r * 2, r * 2, -startDeg, -sweepDeg, Arc2D.PIE), color)

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) =
        draw(Line2D.Float(x1, y1, x2, y2), color, width)

    override fun polyline(xs: FloatArray, ys: FloatArray, n: Int, color: Int, width: Float) {
        if (n < 2) return
        val p = Path2D.Float()
        p.moveTo(xs[0], ys[0])
        for (i in 1 until n) p.lineTo(xs[i], ys[i])
        draw(p, color, width)
    }

    override fun fillPolygon(xs: FloatArray, ys: FloatArray, n: Int, color: Int) {
        if (n < 3) return
        val p = Path2D.Float()
        p.moveTo(xs[0], ys[0])
        for (i in 1 until n) p.lineTo(xs[i], ys[i])
        p.closePath()
        fill(p, color)
    }

    override fun glow(cx: Float, cy: Float, r: Float, color: Int) {
        if (r <= 0f) return
        g.paint = RadialGradientPaint(
            Point2D.Float(cx, cy), r, floatArrayOf(0f, 1f),
            arrayOf(color(color), color(color and 0x00FFFFFF)), MultipleGradientPaint.CycleMethod.NO_CYCLE,
        )
        g.fill(Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2))
    }

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Align) {
        val f = baseFont.deriveFont(size)
        g.font = f
        g.color = color(color)
        val w = f.getStringBounds(s, g.fontRenderContext).width.toFloat()
        val dx = when (align) {
            Align.LEFT -> 0f
            Align.CENTER -> -w / 2f
            Align.RIGHT -> -w
        }
        g.drawString(s, x + dx, y)
    }

    override fun textWidth(s: String, size: Float): Float =
        baseFont.deriveFont(size).getStringBounds(s, g.fontRenderContext).width.toFloat()
}
