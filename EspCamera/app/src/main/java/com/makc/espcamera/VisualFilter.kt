package com.makc.espcamera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns camera frames into thermal, night-vision or "matrix" pictures on the
 * analysis thread. Output bitmaps are reused in a small ring, so the one
 * on screen is never the one being written.
 */
class VisualFilter {
    private var pixels = IntArray(0)
    private var luma = IntArray(0)
    private val outputs = arrayOfNulls<Bitmap>(3)
    private var nextOutput = 0

    private var small: Bitmap? = null
    private val smallCanvas = Canvas()
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val smallRect = Rect()

    private var vignette = IntArray(0)
    private var vignetteW = 0
    private var vignetteH = 0
    private var seed = 0x2545F491

    private val ironbow = IntArray(256).also { lut ->
        // Black -> indigo -> magenta -> red -> orange -> yellow -> white.
        val stops = floatArrayOf(0f, 0.18f, 0.38f, 0.58f, 0.76f, 0.9f, 1f)
        val colors = intArrayOf(0x000000, 0x1E0050, 0x7A0090, 0xD8203C, 0xFF8A00, 0xFFDC30, 0xFFFFE8)
        for (i in 0 until 256) {
            val t = i / 255f
            var s = 0
            while (s < stops.size - 2 && t > stops[s + 1]) s++
            val f = ((t - stops[s]) / (stops[s + 1] - stops[s])).coerceIn(0f, 1f)
            lut[i] = 0xFF000000.toInt() or mix(colors[s], colors[s + 1], f)
        }
    }

    /**
     * [heat] are person boxes in [src] pixels; in the thermal view they glow
     * warmer than their surroundings, the way bodies do on a real imager.
     */
    fun process(src: Bitmap, mode: VisionMode, heat: List<RectF>): Bitmap = when (mode) {
        VisionMode.THERMAL -> thermal(src, heat)
        VisionMode.NIGHT -> night(src)
        VisionMode.MATRIX -> matrix(src)
        VisionMode.NORMAL -> src
    }

    private fun thermal(src: Bitmap, heat: List<RectF>): Bitmap {
        // Real thermal sensors are coarse; a third of the resolution looks right and is cheap.
        val w = max(1, src.width / 3)
        val h = max(1, src.height / 3)
        val input = downscale(src, w, h)
        val n = load(input, w, h)
        for (i in 0 until n) luma[i] = luma[i] * 140 shr 8

        val sx = w / src.width.toFloat()
        val sy = h / src.height.toFloat()
        for (box in heat) {
            val x0 = max(0, (box.left * sx).toInt())
            val x1 = min(w - 1, (box.right * sx).toInt())
            val y0 = max(0, (box.top * sy).toInt())
            val y1 = min(h - 1, (box.bottom * sy).toInt())
            if (x1 <= x0 || y1 <= y0) continue
            val cx = (x0 + x1) / 2f
            val cy = (y0 + y1) / 2f
            val rx = (x1 - x0) / 2f
            val ry = (y1 - y0) / 2f
            for (y in y0..y1) {
                val dy = (y - cy) / ry
                for (x in x0..x1) {
                    val dx = (x - cx) / rx
                    val d = dx * dx + dy * dy
                    if (d < 1f) {
                        val i = y * w + x
                        luma[i] += (125f * (1f - d * 0.55f)).toInt()
                    }
                }
            }
        }
        for (i in 0 until n) pixels[i] = ironbow[min(255, luma[i])]
        return publish(w, h)
    }

    private fun night(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val n = load(src, w, h)
        ensureVignette(w, h)
        var s = seed
        for (i in 0 until n) {
            s = s xor (s shl 13)
            s = s xor (s ushr 17)
            s = s xor (s shl 5)
            val noise = (s and 31) - 16
            var v = (luma[i] * 435 shr 8) + 18 + noise
            v = v * vignette[i] shr 8
            v = v.coerceIn(0, 255)
            pixels[i] = 0xFF000000.toInt() or ((v * 70 shr 8) shl 16) or (v shl 8) or (v * 95 shr 8)
        }
        seed = s
        return publish(w, h)
    }

    private fun matrix(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        load(src, w, h)
        for (y in 0 until h) {
            val row = y * w
            val scan = if ((y and 1) == 1) 205 else 256
            for (x in 0 until w) {
                val i = row + x
                var v = min(255, luma[i] * 300 shr 8)
                v = (v / 43) * 43 // six flat steps, like a terminal
                v = v * scan shr 8
                pixels[i] = 0xFF000000.toInt() or ((v * 22 shr 8) shl 16) or (v shl 8) or (v * 70 shr 8)
            }
        }
        return publish(w, h)
    }

    /** Reads [bitmap] into [pixels] and its brightness into [luma]; returns the pixel count. */
    private fun load(bitmap: Bitmap, w: Int, h: Int): Int {
        val n = w * h
        if (pixels.size < n) {
            pixels = IntArray(n)
            luma = IntArray(n)
        }
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in 0 until n) {
            val c = pixels[i]
            luma[i] = (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
        }
        return n
    }

    private fun publish(w: Int, h: Int): Bitmap {
        var out = outputs[nextOutput]
        if (out == null || out.width != w || out.height != h) {
            out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            outputs[nextOutput] = out
        }
        out!!.setPixels(pixels, 0, w, 0, 0, w, h)
        nextOutput = (nextOutput + 1) % outputs.size
        return out
    }

    private fun downscale(src: Bitmap, w: Int, h: Int): Bitmap {
        var target = small
        if (target == null || target.width != w || target.height != h) {
            target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            small = target
        }
        smallCanvas.setBitmap(target)
        smallRect.set(0, 0, w, h)
        smallCanvas.drawBitmap(src, null, smallRect, scalePaint)
        return target!!
    }

    private fun ensureVignette(w: Int, h: Int) {
        if (w == vignetteW && h == vignetteH) return
        vignette = IntArray(w * h)
        val cx = w / 2f
        val cy = h / 2f
        val maxR = sqrt(cx * cx + cy * cy)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) / maxR
                vignette[y * w + x] = (256f * (1f - 0.6f * r * r)).toInt()
            }
        }
        vignetteW = w
        vignetteH = h
    }

    private fun mix(a: Int, b: Int, f: Float): Int {
        val r = ((a shr 16 and 255) + ((b shr 16 and 255) - (a shr 16 and 255)) * f).toInt()
        val g = ((a shr 8 and 255) + ((b shr 8 and 255) - (a shr 8 and 255)) * f).toInt()
        val bl = ((a and 255) + ((b and 255) - (a and 255)) * f).toInt()
        return (r shl 16) or (g shl 8) or bl
    }
}
