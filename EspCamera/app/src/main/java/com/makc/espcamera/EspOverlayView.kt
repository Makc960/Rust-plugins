package com.makc.espcamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Draws the ESP layer over the camera preview: boxes, names, confidence bars,
 * distance, snaplines, the crosshair and the locked target.
 */
class EspOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Filter(val title: String) {
        ALL("ВСЁ"),
        PEOPLE("ЛЮДИ"),
        VEHICLES("ТЕХНИКА"),
        ANIMALS("ЗВЕРИ"),
        ITEMS("ВЕЩИ");

        fun accepts(kind: Kind): Boolean = when (this) {
            ALL -> true
            PEOPLE -> kind == Kind.PERSON
            VEHICLES -> kind == Kind.VEHICLE
            ANIMALS -> kind == Kind.ANIMAL
            ITEMS -> kind == Kind.ITEM
        }

        fun next(): Filter = entries[(ordinal + 1) % entries.size]
    }

    var filter = Filter.ALL
    var cornerBoxes = true
    var snaplines = true

    /** Space at the bottom taken by the buttons; snaplines start above it. */
    var bottomReserved = 0

    private val tracker = Tracker()
    private var imageW = 0
    private var imageH = 0
    private var focalMm = 0f
    private var sensorLongMm = 0f
    private var safeTop = 0
    private var safeBottom = 0

    private var fps = 0f
    private var inferenceMs = 0L
    private var lastFrameAt = 0L
    private var lastDrawAt = 0L

    // Image -> view mapping for a FILL_CENTER preview.
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val density = resources.displayMetrics.density
    private val ru = Locale.forLanguageTag("ru")
    private val accent = 0xFF39FF6A.toInt()
    private val hudText = 0xFFE6FFEE.toInt()

    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        color = Color.BLACK
    }
    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = mono
        textSize = dp(11f)
    }
    private val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = mono
        textSize = dp(12f)
        setShadowLayer(dp(2f), 0f, 0f, Color.BLACK)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        strokeCap = Paint.Cap.ROUND
        setShadowLayer(dp(2f), 0f, 0f, Color.BLACK)
    }

    private val path = Path()
    private val viewRect = RectF()
    private val tmpRect = RectF()
    private val hsv = FloatArray(3)
    private val tagAscent = -tagTextPaint.fontMetrics.ascent
    private val tagDescent = tagTextPaint.fontMetrics.descent

    fun submit(frame: ObjectDetectorHelper.Frame) {
        val now = SystemClock.uptimeMillis()
        if (frame.width != imageW || frame.height != imageH) {
            tracker.clear()
            imageW = frame.width
            imageH = frame.height
        }
        tracker.update(frame.detections, now)
        if (lastFrameAt > 0L) {
            val dt = now - lastFrameAt
            if (dt > 0) {
                val instant = 1000f / dt
                fps = if (fps == 0f) instant else fps * 0.9f + instant * 0.1f
            }
        }
        lastFrameAt = now
        inferenceMs = frame.inferenceMs
        invalidate()
    }

    /** Forget everything, e.g. after switching cameras. */
    fun reset() {
        tracker.clear()
        imageW = 0
        imageH = 0
        fps = 0f
        lastFrameAt = 0L
        invalidate()
    }

    fun setOptics(focalMm: Float, sensorLongMm: Float) {
        this.focalMm = focalMm
        this.sensorLongMm = sensorLongMm
    }

    fun setSafeArea(top: Int, bottom: Int) {
        safeTop = top
        safeBottom = bottom
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastDrawAt == 0L) 16L else (now - lastDrawAt).coerceIn(0L, 100L)
        lastDrawAt = now
        tracker.step(dt)

        val cx = width / 2f
        val cy = height / 2f
        var target: Track? = null
        var visibleCount = 0

        if (imageW > 0 && imageH > 0) {
            scale = max(width / imageW.toFloat(), height / imageH.toFloat())
            offsetX = (width - imageW * scale) / 2f
            offsetY = (height - imageH * scale) / 2f

            // The locked target is the smallest box under the crosshair.
            var targetArea = Float.MAX_VALUE
            for (t in tracker.tracks) {
                if (!filter.accepts(Labels.of(t.label).kind)) continue
                visibleCount++
                toView(t.shown, viewRect)
                if (viewRect.contains(cx, cy)) {
                    val area = viewRect.width() * viewRect.height()
                    if (area < targetArea) {
                        targetArea = area
                        target = t
                    }
                }
            }

            if (snaplines) {
                val originY = (height - max(bottomReserved, safeBottom)).toFloat()
                for (t in tracker.tracks) {
                    val info = Labels.of(t.label)
                    if (!filter.accepts(info.kind)) continue
                    toView(t.shown, viewRect)
                    linePaint.color = info.kind.color
                    linePaint.alpha = (tracker.alpha(t, now) * if (t === target) 230 else 150).toInt()
                    canvas.drawLine(cx, originY, viewRect.centerX(), viewRect.bottom, linePaint)
                }
            }

            for (t in tracker.tracks) {
                val info = Labels.of(t.label)
                if (!filter.accepts(info.kind)) continue
                toView(t.shown, viewRect)
                drawTrack(canvas, t, info, viewRect, tracker.alpha(t, now), t === target, now)
            }
        }

        drawCrosshair(canvas, cx, cy, target)
        drawHud(canvas, visibleCount)
        if (target != null) drawTargetCard(canvas, target)

        postInvalidateOnAnimation()
    }

    private fun drawTrack(
        canvas: Canvas,
        t: Track,
        info: LabelInfo,
        r: RectF,
        alpha: Float,
        isTarget: Boolean,
        now: Long,
    ) {
        val color = info.kind.color
        val a = (alpha * 255).roundToInt()

        fillPaint.color = color
        fillPaint.alpha = (a * if (isTarget) 0.16f else 0.07f).roundToInt()
        canvas.drawRect(r, fillPaint)

        val stroke = dp(if (isTarget) 3f else 2f)
        buildBox(r)
        shadowPaint.strokeWidth = stroke + dp(2.5f)
        shadowPaint.alpha = (a * 0.55f).roundToInt()
        canvas.drawPath(path, shadowPaint)
        boxPaint.strokeWidth = stroke
        boxPaint.color = color
        boxPaint.alpha = a
        canvas.drawPath(path, boxPaint)

        if (isTarget) {
            val pulse = dp(5f) + dp(3f) * sin(now / 140.0).toFloat()
            tmpRect.set(r.left - pulse, r.top - pulse, r.right + pulse, r.bottom + pulse)
            buildCorners(tmpRect)
            boxPaint.strokeWidth = dp(2f)
            boxPaint.color = Color.WHITE
            boxPaint.alpha = (a * 0.85f).roundToInt()
            canvas.drawPath(path, boxPaint)
        }

        drawConfidenceBar(canvas, r, t.score, a)

        val name = "${info.title} #${t.id}"
        val tagH = tagAscent + tagDescent + dp(4f)
        val nameY = if (r.top - tagH - dp(3f) > safeTop) r.top - dp(3f) - tagH else r.top + dp(3f)
        drawTag(canvas, name, r.left, nameY, color, a)

        val percent = (t.score * 100).roundToInt()
        val distance = distanceMeters(t, info)
        val meta = if (distance != null) "$percent% · ~${formatDistance(distance)}" else "$percent%"
        val limit = height - max(bottomReserved, safeBottom)
        val metaY = if (r.bottom + dp(3f) + tagH < limit) r.bottom + dp(3f) else r.bottom - dp(3f) - tagH
        drawTag(canvas, meta, r.left, metaY, color, a)
    }

    private fun buildBox(r: RectF) {
        if (cornerBoxes) {
            buildCorners(r)
        } else {
            path.reset()
            path.addRect(r, Path.Direction.CW)
        }
    }

    private fun buildCorners(r: RectF) {
        val len = (min(r.width(), r.height()) * 0.25f).coerceAtLeast(dp(8f))
        path.reset()
        path.moveTo(r.left, r.top + len); path.lineTo(r.left, r.top); path.lineTo(r.left + len, r.top)
        path.moveTo(r.right - len, r.top); path.lineTo(r.right, r.top); path.lineTo(r.right, r.top + len)
        path.moveTo(r.right, r.bottom - len); path.lineTo(r.right, r.bottom); path.lineTo(r.right - len, r.bottom)
        path.moveTo(r.left + len, r.bottom); path.lineTo(r.left, r.bottom); path.lineTo(r.left, r.bottom - len)
    }

    /** Vertical bar left of the box, like a health bar: full and green means sure. */
    private fun drawConfidenceBar(canvas: Canvas, r: RectF, score: Float, a: Int) {
        val w = dp(3f)
        val x = r.left - dp(7f)
        fillPaint.color = Color.BLACK
        fillPaint.alpha = (a * 0.6f).roundToInt()
        canvas.drawRect(x - dp(1f), r.top - dp(1f), x + w + dp(1f), r.bottom + dp(1f), fillPaint)

        val level = ((score - 0.4f) / 0.6f).coerceIn(0.05f, 1f)
        hsv[0] = 120f * level
        hsv[1] = 0.9f
        hsv[2] = 1f
        fillPaint.color = Color.HSVToColor(hsv)
        fillPaint.alpha = a
        canvas.drawRect(x, r.bottom - r.height() * level, x + w, r.bottom, fillPaint)
    }

    private fun drawTag(canvas: Canvas, text: String, left: Float, top: Float, color: Int, a: Int) {
        val padX = dp(4f)
        val w = tagTextPaint.measureText(text) + padX * 2
        val h = tagAscent + tagDescent + dp(4f)
        val x = left.coerceIn(0f, max(0f, width - w))
        tmpRect.set(x, top, x + w, top + h)
        tagBgPaint.color = Color.BLACK
        tagBgPaint.alpha = (a * 0.62f).roundToInt()
        canvas.drawRoundRect(tmpRect, dp(2f), dp(2f), tagBgPaint)
        tagTextPaint.color = color
        tagTextPaint.alpha = a
        canvas.drawText(text, x + padX, top + dp(2f) + tagAscent, tagTextPaint)
    }

    private fun drawCrosshair(canvas: Canvas, cx: Float, cy: Float, target: Track?) {
        val color = target?.let { Labels.of(it.label).kind.color } ?: hudText
        crossPaint.color = color
        crossPaint.alpha = if (target != null) 255 else 200
        val ring = dp(11f)
        val gap = dp(5f)
        val arm = dp(9f)
        canvas.drawCircle(cx, cy, ring, crossPaint)
        canvas.drawLine(cx - ring - arm, cy, cx - gap, cy, crossPaint)
        canvas.drawLine(cx + gap, cy, cx + ring + arm, cy, crossPaint)
        canvas.drawLine(cx, cy - ring - arm, cx, cy - gap, crossPaint)
        canvas.drawLine(cx, cy + gap, cx, cy + ring + arm, crossPaint)
        fillPaint.color = color
        fillPaint.alpha = 255
        canvas.drawCircle(cx, cy, dp(1.8f), fillPaint)
    }

    private fun drawHud(canvas: Canvas, count: Int) {
        val x = dp(14f)
        var y = safeTop + dp(22f)
        val line = dp(17f)
        hudPaint.textAlign = Paint.Align.LEFT

        hudPaint.color = accent
        canvas.drawText("ESP·CAM", x, y, hudPaint)
        y += line
        hudPaint.color = hudText
        if (imageW == 0) {
            canvas.drawText("ПОИСК ЦЕЛЕЙ…", x, y, hudPaint)
            return
        }
        canvas.drawText("FPS ${fps.roundToInt()}  ·  ${inferenceMs} мс", x, y, hudPaint)
        y += line
        canvas.drawText("ОБЪЕКТОВ $count  ·  ${filter.title}", x, y, hudPaint)
    }

    private fun drawTargetCard(canvas: Canvas, t: Track) {
        val info = Labels.of(t.label)
        val x = width - dp(14f)
        var y = safeTop + dp(22f)
        val line = dp(17f)
        hudPaint.textAlign = Paint.Align.RIGHT

        hudPaint.color = info.kind.color
        canvas.drawText("ЦЕЛЬ ЗАХВАЧЕНА", x, y, hudPaint)
        y += line
        hudPaint.color = hudText
        canvas.drawText("${info.title} #${t.id}", x, y, hudPaint)
        y += line
        val percent = (t.score * 100).roundToInt()
        val distance = distanceMeters(t, info)
        val text = if (distance != null) "$percent%  ·  ~${formatDistance(distance)}" else "$percent%"
        canvas.drawText(text, x, y, hudPaint)
    }

    /**
     * Pinhole estimate: distance = real height * focal length (px) / box height (px).
     * Skipped when the box is cut by the frame, since its height is then wrong.
     */
    private fun distanceMeters(t: Track, info: LabelInfo): Float? {
        val realHeight = info.heightM ?: return null
        val box = t.shown
        val edge = imageH * 0.02f
        if (box.top <= edge || box.bottom >= imageH - edge || box.height() < 4f) return null
        val longPx = max(imageW, imageH).toFloat()
        val focalPx = if (focalMm > 0f && sensorLongMm > 0f) {
            focalMm / sensorLongMm * longPx
        } else {
            // Typical main phone camera: about 66 degrees along the long side.
            (longPx / 2f) / tan(Math.toRadians(33.0)).toFloat()
        }
        return realHeight * focalPx / box.height()
    }

    private fun formatDistance(m: Float): String =
        if (m < 10f) String.format(ru, "%.1f м", m) else "${m.roundToInt()} м"

    private fun toView(src: RectF, out: RectF) {
        out.set(
            src.left * scale + offsetX,
            src.top * scale + offsetY,
            src.right * scale + offsetX,
            src.bottom * scale + offsetY,
        )
    }

    private fun dp(v: Float): Float = v * density
}
