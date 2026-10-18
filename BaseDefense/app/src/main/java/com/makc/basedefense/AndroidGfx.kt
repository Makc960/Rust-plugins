package com.makc.basedefense

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import com.makc.basedefense.engine.Align
import com.makc.basedefense.engine.Gfx
import com.makc.basedefense.engine.Sprite
import com.makc.basedefense.engine.Sprites
import kotlin.math.ceil

/** [Gfx] on an Android [Canvas]; set [canvas] before each frame. */
class AndroidGfx : Gfx {
    lateinit var canvas: Canvas

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val src = Rect()
    private val shaderMatrix = Matrix()
    private val gradients = HashMap<Int, RadialGradient>()

    /** Terrain pictures, newest last; dropped oldest first past [MAX_CACHE_PIXELS]. */
    private val pictures = LinkedHashMap<String, Bitmap>(16, 0.75f, true)

    override fun save() {
        canvas.save()
    }

    override fun restore() {
        canvas.restore()
    }

    override fun translate(dx: Float, dy: Float) = canvas.translate(dx, dy)

    override fun scale(s: Float) = canvas.scale(s, s)

    override fun rotate(degrees: Float) = canvas.rotate(degrees)

    override fun clip(x: Float, y: Float, w: Float, h: Float) {
        canvas.clipRect(x, y, x + w, y + h)
    }

    override fun fillRect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        fill.color = color
        canvas.drawRect(x, y, x + w, y + h, fill)
    }

    override fun fillRoundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
        fill.color = color
        canvas.drawRoundRect(x, y, x + w, y + h, r, r, fill)
    }

    override fun strokeRoundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int, width: Float) {
        stroke.color = color
        stroke.strokeWidth = width
        canvas.drawRoundRect(x, y, x + w, y + h, r, r, stroke)
    }

    override fun fillCircle(cx: Float, cy: Float, r: Float, color: Int) {
        fill.color = color
        canvas.drawCircle(cx, cy, r, fill)
    }

    override fun strokeCircle(cx: Float, cy: Float, r: Float, color: Int, width: Float) {
        stroke.color = color
        stroke.strokeWidth = width
        canvas.drawCircle(cx, cy, r, stroke)
    }

    override fun fillArc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, color: Int) {
        fill.color = color
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, startDeg, sweepDeg, true, fill)
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
        stroke.color = color
        stroke.strokeWidth = width
        canvas.drawLine(x1, y1, x2, y2, stroke)
    }

    override fun polyline(xs: FloatArray, ys: FloatArray, n: Int, color: Int, width: Float) {
        if (n < 2) return
        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until n) path.lineTo(xs[i], ys[i])
        stroke.color = color
        stroke.strokeWidth = width
        canvas.drawPath(path, stroke)
    }

    override fun fillPolygon(xs: FloatArray, ys: FloatArray, n: Int, color: Int) {
        if (n < 3) return
        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until n) path.lineTo(xs[i], ys[i])
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
    }

    override fun glow(cx: Float, cy: Float, r: Float, color: Int) {
        if (r <= 0f) return
        val shader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Since Android 9 a recorded draw keeps its own copy of the shader,
            // so one gradient per color can be moved around with its matrix.
            if (gradients.size > 64) gradients.clear()
            val g = gradients.getOrPut(color) {
                RadialGradient(0f, 0f, 1f, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
            }
            shaderMatrix.setScale(r, r)
            shaderMatrix.postTranslate(cx, cy)
            g.setLocalMatrix(shaderMatrix)
            g
        } else {
            RadialGradient(cx, cy, r, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
        glowPaint.shader = shader
        canvas.drawCircle(cx, cy, r, glowPaint)
    }

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Align) {
        textPaint.textSize = size
        textPaint.color = color
        textPaint.textAlign = when (align) {
            Align.LEFT -> Paint.Align.LEFT
            Align.CENTER -> Paint.Align.CENTER
            Align.RIGHT -> Paint.Align.RIGHT
        }
        canvas.drawText(s, x, y, textPaint)
    }

    override fun textWidth(s: String, size: Float): Float {
        textPaint.textSize = size
        return textPaint.measureText(s)
    }

    override fun cached(id: String, w: Float, h: Float, pxPerUnit: Float, content: (Gfx) -> Unit) {
        val bw = ceil(w * pxPerUnit).toInt()
        val bh = ceil(h * pxPerUnit).toInt()
        if (bw <= 0 || bh <= 0 || bw.toLong() * bh > MAX_CACHE_PIXELS) {
            content(this)
            return
        }
        val key = "$id@$bw"
        var picture = pictures[key]
        if (picture == null) {
            picture = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(picture)
            c.scale(bw / w, bh / h)
            val painter = AndroidGfx()
            painter.canvas = c
            content(painter)
            pictures[key] = picture
            trim()
        }
        rect.set(0f, 0f, w, h)
        canvas.drawBitmap(picture, null, rect, bitmapPaint)
    }

    override val sprites: Boolean get() = atlas != null

    override fun sprite(s: Sprite, cx: Float, cy: Float, w: Float, h: Float, degrees: Float, alpha: Float) {
        val bitmap = atlas ?: return
        if (alpha <= 0f) return
        src.set(s.x, s.y, s.x + s.w, s.y + s.h)
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        spritePaint.alpha = (alpha.coerceAtMost(1f) * 255f).toInt()
        if (degrees == 0f) {
            canvas.drawBitmap(bitmap, src, rect, spritePaint)
            return
        }
        canvas.save()
        canvas.rotate(degrees, cx, cy)
        canvas.drawBitmap(bitmap, src, rect, spritePaint)
        canvas.restore()
    }

    private fun trim() {
        var total = pictures.values.sumOf { it.width.toLong() * it.height }
        val iter = pictures.entries.iterator()
        // Never recycle: a frame being drawn may still use the bitmap; the collector frees it.
        while (total > MAX_CACHE_PIXELS && pictures.size > 1 && iter.hasNext()) {
            val e = iter.next()
            total -= e.value.width.toLong() * e.value.height
            iter.remove()
        }
    }

    companion object {
        private const val MAX_CACHE_PIXELS = 12_000_000L

        /** The sprite atlas, decoded once for every painter; without it the game paints shapes. */
        private val atlas: Bitmap? by lazy {
            try {
                AndroidGfx::class.java.getResourceAsStream(Sprites.IMAGE)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                }
            } catch (e: OutOfMemoryError) {
                null
            }
        }

        /** Decodes the atlas now, before the first frame needs it. */
        fun preload() {
            atlas
        }
    }
}
