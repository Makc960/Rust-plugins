package com.makc.basedefense.engine

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font as AwtFont
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
import javax.imageio.ImageIO

/** [Gfx] on Java2D, for screenshots in tests. */
class AwtGfx(val image: BufferedImage) : Gfx {
    private val g: Graphics2D = image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }
    private val stack = ArrayDeque<Pair<AffineTransform, Shape?>>()

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

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Align, font: Font) {
        val f = fonts.getValue(font).deriveFont(size)
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

    override fun textWidth(s: String, size: Float, font: Font): Float =
        fonts.getValue(font).deriveFont(size).getStringBounds(s, g.fontRenderContext).width.toFloat()

    override val sprites: Boolean get() = atlas != null

    override fun sprite(s: Sprite, cx: Float, cy: Float, w: Float, h: Float, degrees: Float, alpha: Float, tint: Int) {
        val img = (if (tint != 0) tinted(s, tint) else atlas) ?: return
        if (alpha <= 0f) return
        val t = g.transform
        val comp = g.composite
        g.translate(cx.toDouble(), cy.toDouble())
        if (degrees != 0f) g.rotate(Math.toRadians(degrees.toDouble()))
        g.scale((w / s.w).toDouble(), (h / s.h).toDouble())
        g.translate(-s.w / 2.0, -s.h / 2.0)
        if (alpha < 1f) g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        if (tint != 0) {
            g.drawImage(img, 0, 0, s.w, s.h, 0, 0, s.w, s.h, null)
        } else {
            g.drawImage(img, 0, 0, s.w, s.h, s.x, s.y, s.x + s.w, s.y + s.h, null)
        }
        g.composite = comp
        g.transform = t
    }

    private companion object {
        val fonts: Map<Font, AwtFont> by lazy {
            Font.entries.associateWith { f ->
                AwtGfx::class.java.getResourceAsStream(f.resource)?.use { AwtFont.createFont(AwtFont.TRUETYPE_FONT, it) }
                    ?: AwtFont("DejaVu Sans", AwtFont.BOLD, 12)
            }
        }

        private val tints = HashMap<Pair<String, Int>, BufferedImage>()

        /** [s] alone, every pixel in [tint] with its own alpha, premultiplied like the atlas. */
        fun tinted(s: Sprite, tint: Int): BufferedImage? {
            val src = atlas ?: return null
            return tints.getOrPut(s.name to tint) {
                val out = BufferedImage(s.w, s.h, BufferedImage.TYPE_INT_ARGB)
                val rgb = tint and 0xFFFFFF
                val ta = (tint ushr 24) and 0xFF
                for (y in 0 until s.h) for (x in 0 until s.w) {
                    val p = src.getRGB(s.x + x, s.y + y)
                    val a = ((p ushr 24) and 0xFF) * ta / 255
                    out.setRGB(x, y, (a shl 24) or rgb)
                }
                val pre = BufferedImage(s.w, s.h, BufferedImage.TYPE_INT_ARGB_PRE)
                pre.createGraphics().apply { drawImage(out, 0, 0, null); dispose() }
                pre
            }
        }

        /** Premultiplied, so filtering never darkens the soft edges. */
        val atlas: BufferedImage? by lazy {
            val src = AwtGfx::class.java.getResourceAsStream(Sprites.IMAGE)?.use { ImageIO.read(it) } ?: return@lazy null
            val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB_PRE)
            out.createGraphics().apply { drawImage(src, 0, 0, null); dispose() }
            out
        }
    }
}
