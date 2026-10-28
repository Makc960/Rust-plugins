package com.makc.espcamera

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where and how big the ESP layer is drawn: on screen, in a photo or in a video frame. */
class Viewport {
    var width = 0f
    var height = 0f

    /** Pixels per dp, so lines and text keep their look at any resolution. */
    var unit = 1f

    /** Set when this picture is mirrored relative to the analysed frames. */
    var flipX = false
    var safeLeft = 0f
    var safeTop = 0f
    var safeRight = 0f
    var safeBottom = 0f
    var bottomReserved = 0f

    /** Photos and videos leave out the zoom label, focus ring and REC badge. */
    var capture = false

    var scale = 1f
        private set
    var offsetX = 0f
        private set
    var offsetY = 0f
        private set

    /** Image -> viewport mapping for a FILL_CENTER picture. */
    fun fit(imageW: Int, imageH: Int) {
        scale = max(width / imageW, height / imageH)
        offsetX = (width - imageW * scale) / 2f
        offsetY = (height - imageH * scale) / 2f
    }

    fun x(u: Float): Float {
        val p = u * scale + offsetX
        return if (flipX) width - p else p
    }

    fun y(v: Float): Float = v * scale + offsetY

    fun rect(src: RectF, out: RectF) {
        val a = x(src.left)
        val b = x(src.right)
        out.set(min(a, b), y(src.top), max(a, b), y(src.bottom))
    }

    /** Image-space horizontal direction or angle as it looks in this viewport. */
    fun dirX(dx: Float): Float = if (flipX) -dx else dx

    fun visibleImage(imageW: Int, imageH: Int, out: RectF) {
        out.set(
            max(0f, -offsetX / scale),
            max(0f, -offsetY / scale),
            min(imageW.toFloat(), (width - offsetX) / scale),
            min(imageH.toFloat(), (height - offsetY) / scale),
        )
    }

    /** Bottom edge of the area the ESP layer may use. */
    val usableBottom: Float get() = height - max(bottomReserved, safeBottom)
}

/**
 * Draws the ESP layer: picture filters, boxes, names, confidence bars,
 * distance, snaplines, trails, skeletons, arrows to objects behind you,
 * the radar, the crosshair and the HUD.
 */
class EspRenderer {
    private val accent = 0xFF39FF6A.toInt()
    private val hudText = 0xFFE6FFEE.toInt()
    private val bone = 0xFFF4F4F4.toInt()
    private val ru = Locale.forLanguageTag("ru")
    private val mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.BLACK
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono }
    private val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = mono }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val path = Path()
    private val box = RectF()
    private val tmp = RectF()
    private val hsv = FloatArray(3)
    private val point = FloatArray(3)

    private var u = 1f
    private var tagAscent = 0f
    private var tagDescent = 0f

    fun render(canvas: Canvas, scene: Scene, vp: Viewport, now: Long) {
        applySizes(vp.unit)
        val s = scene.settings
        val hasFrame = scene.imageW > 0 && scene.imageH > 0
        if (hasFrame) {
            vp.fit(scene.imageW, scene.imageH)
            if (s.vision != VisionMode.NORMAL) drawFilteredFrame(canvas, scene, vp)
            if (s.vision == VisionMode.MATRIX) drawRain(canvas, scene, vp)
            if (s.trails) drawTrails(canvas, scene, vp, now)
            if (s.snaplines) drawSnaplines(canvas, scene, vp, now)
            drawBoxes(canvas, scene, vp, now)
            if (s.skeleton && s.filter.accepts(Kind.PERSON)) drawSkeletons(canvas, scene, vp, now)
            if (s.markers && scene.orientation.ready) drawMarks(canvas, scene, vp, now)
        }
        drawCrosshair(canvas, vp, scene.target)
        drawHud(canvas, scene, vp)
        scene.target?.let { drawTargetCard(canvas, scene, vp, it) }
        if (s.radar && hasFrame) drawRadar(canvas, scene, vp, now)
        if (!vp.capture) drawTransient(canvas, scene, vp, now)
    }

    private fun applySizes(unit: Float) {
        u = unit
        tagTextPaint.textSize = 11f * u
        tagAscent = -tagTextPaint.ascent()
        tagDescent = tagTextPaint.descent()
        hudPaint.textSize = 12f * u
        hudPaint.setShadowLayer(2f * u, 0f, 0f, Color.BLACK)
        crossPaint.strokeWidth = 1.6f * u
        crossPaint.setShadowLayer(2f * u, 0f, 0f, Color.BLACK)
    }

    // --- picture -------------------------------------------------------------------------

    private fun drawFilteredFrame(canvas: Canvas, scene: Scene, vp: Viewport) {
        val bitmap = scene.filtered ?: return
        canvas.save()
        if (vp.flipX) canvas.scale(-1f, 1f, vp.width / 2f, 0f)
        tmp.set(vp.offsetX, vp.offsetY, vp.offsetX + scene.imageW * vp.scale, vp.offsetY + scene.imageH * vp.scale)
        canvas.drawBitmap(bitmap, null, tmp, bitmapPaint)
        canvas.restore()
    }

    private fun drawRain(canvas: Canvas, scene: Scene, vp: Viewport) {
        val columns = MatrixRain.COLUMNS
        val cellW = vp.width / columns
        val cellH = cellW * 1.1f
        rainPaint.textSize = cellW * 0.95f
        val glyphs = MatrixRain.GLYPHS
        val flicker = (scene.rain.clock / 120).toInt()
        for (c in 0 until columns) {
            val headRow = (scene.rain.heads[c] * vp.height / cellH).toInt()
            val x = c * cellW + cellW * 0.08f
            for (k in 0 until MatrixRain.TAIL) {
                val row = headRow - k
                val y = row * cellH
                if (y < 0f || y > vp.height + cellH) continue
                val seed = c * 31 + row * 17 + if ((c + row) % 4 == 0) flicker else 0
                val g = ((seed % glyphs.size) + glyphs.size) % glyphs.size
                if (k == 0) {
                    rainPaint.color = 0xFFD8FFE0.toInt()
                } else {
                    rainPaint.color = accent
                    rainPaint.alpha = (200 * (1f - k / MatrixRain.TAIL.toFloat())).roundToInt()
                }
                canvas.drawText(glyphs, g, 1, x, y, rainPaint)
            }
        }
    }

    // --- objects -------------------------------------------------------------------------

    private fun drawTrails(canvas: Canvas, scene: Scene, vp: Viewport, now: Long) {
        linePaint.strokeWidth = 2f * u
        for (t in scene.tracker.tracks) {
            val info = Labels.of(t.label)
            if (!scene.settings.filter.accepts(info.kind)) continue
            val n = t.trailSize
            if (n < 2) continue
            val a = scene.tracker.alpha(t, now)
            linePaint.color = info.kind.color
            var px = vp.x(t.trailX(0))
            var py = vp.y(t.trailY(0))
            for (i in 1 until n) {
                val x = vp.x(t.trailX(i))
                val y = vp.y(t.trailY(i))
                linePaint.alpha = (a * 210f * i / n).roundToInt()
                canvas.drawLine(px, py, x, y, linePaint)
                px = x
                py = y
            }
        }
    }

    private fun drawSnaplines(canvas: Canvas, scene: Scene, vp: Viewport, now: Long) {
        val originX = vp.width / 2f
        val originY = vp.usableBottom
        linePaint.strokeWidth = 1.2f * u
        for (t in scene.tracker.tracks) {
            val info = Labels.of(t.label)
            if (!scene.settings.filter.accepts(info.kind)) continue
            vp.rect(t.shown, box)
            linePaint.color = info.kind.color
            linePaint.alpha = (scene.tracker.alpha(t, now) * if (t === scene.target) 230 else 150).toInt()
            canvas.drawLine(originX, originY, box.centerX(), box.bottom, linePaint)
        }
    }

    private fun drawBoxes(canvas: Canvas, scene: Scene, vp: Viewport, now: Long) {
        for (t in scene.tracker.tracks) {
            val info = Labels.of(t.label)
            if (!scene.settings.filter.accepts(info.kind)) continue
            vp.rect(t.shown, box)
            drawTrack(canvas, scene, vp, t, info, scene.tracker.alpha(t, now), t === scene.target, now)
        }
    }

    private fun drawTrack(
        canvas: Canvas,
        scene: Scene,
        vp: Viewport,
        t: Track,
        info: LabelInfo,
        alpha: Float,
        isTarget: Boolean,
        now: Long,
    ) {
        val r = box
        val color = info.kind.color
        val a = (alpha * 255).roundToInt()

        fillPaint.color = color
        fillPaint.alpha = (a * if (isTarget) 0.16f else 0.07f).roundToInt()
        canvas.drawRect(r, fillPaint)

        val stroke = (if (isTarget) 3f else 2f) * u
        buildBox(r, scene.settings.cornerBoxes)
        shadowPaint.strokeWidth = stroke + 2.5f * u
        shadowPaint.alpha = (a * 0.55f).roundToInt()
        canvas.drawPath(path, shadowPaint)
        boxPaint.strokeWidth = stroke
        boxPaint.color = color
        boxPaint.alpha = a
        canvas.drawPath(path, boxPaint)

        if (isTarget) {
            val pulse = 5f * u + 3f * u * sin(now / 140.0).toFloat()
            tmp.set(r.left - pulse, r.top - pulse, r.right + pulse, r.bottom + pulse)
            buildCorners(tmp)
            boxPaint.strokeWidth = 2f * u
            boxPaint.color = Color.WHITE
            boxPaint.alpha = (a * 0.85f).roundToInt()
            canvas.drawPath(path, boxPaint)
        }

        drawConfidenceBar(canvas, r, t.score, a)

        val tagH = tagAscent + tagDescent + 4f * u
        val name = "${info.title} #${t.id}"
        val nameY = if (r.top - tagH - 3f * u > vp.safeTop) r.top - 3f * u - tagH else r.top + 3f * u
        drawTag(canvas, vp, name, r.left, nameY, color, a)

        val meta = metaText(scene, t)
        val metaY = if (r.bottom + 3f * u + tagH < vp.usableBottom) r.bottom + 3f * u else r.bottom - 3f * u - tagH
        drawTag(canvas, vp, meta, r.left, metaY, color, a)
    }

    private fun metaText(scene: Scene, t: Track): String {
        val percent = (t.score * 100).roundToInt()
        val distance = scene.distanceMeters(t) ?: return "$percent%"
        return "$percent% · ~${formatDistance(distance)}"
    }

    private fun buildBox(r: RectF, corners: Boolean) {
        if (corners) {
            buildCorners(r)
        } else {
            path.reset()
            path.addRect(r, Path.Direction.CW)
        }
    }

    private fun buildCorners(r: RectF) {
        val len = (min(r.width(), r.height()) * 0.25f).coerceAtLeast(8f * u)
        path.reset()
        path.moveTo(r.left, r.top + len); path.lineTo(r.left, r.top); path.lineTo(r.left + len, r.top)
        path.moveTo(r.right - len, r.top); path.lineTo(r.right, r.top); path.lineTo(r.right, r.top + len)
        path.moveTo(r.right, r.bottom - len); path.lineTo(r.right, r.bottom); path.lineTo(r.right - len, r.bottom)
        path.moveTo(r.left + len, r.bottom); path.lineTo(r.left, r.bottom); path.lineTo(r.left, r.bottom - len)
    }

    /** Vertical bar left of the box, like a health bar: full and green means sure. */
    private fun drawConfidenceBar(canvas: Canvas, r: RectF, score: Float, a: Int) {
        val w = 3f * u
        val x = r.left - 7f * u
        fillPaint.color = Color.BLACK
        fillPaint.alpha = (a * 0.6f).roundToInt()
        canvas.drawRect(x - u, r.top - u, x + w + u, r.bottom + u, fillPaint)

        val level = ((score - 0.4f) / 0.6f).coerceIn(0.05f, 1f)
        hsv[0] = 120f * level
        hsv[1] = 0.9f
        hsv[2] = 1f
        fillPaint.color = Color.HSVToColor(hsv)
        fillPaint.alpha = a
        canvas.drawRect(x, r.bottom - r.height() * level, x + w, r.bottom, fillPaint)
    }

    private fun drawTag(canvas: Canvas, vp: Viewport, text: String, left: Float, top: Float, color: Int, a: Int) {
        val padX = 4f * u
        val w = tagTextPaint.measureText(text) + padX * 2
        val h = tagAscent + tagDescent + 4f * u
        val x = left.coerceIn(0f, max(0f, vp.width - w))
        tmp.set(x, top, x + w, top + h)
        tagBgPaint.color = Color.BLACK
        tagBgPaint.alpha = (a * 0.62f).roundToInt()
        canvas.drawRoundRect(tmp, 2f * u, 2f * u, tagBgPaint)
        tagTextPaint.color = color
        tagTextPaint.alpha = a
        canvas.drawText(text, x + padX, top + 2f * u + tagAscent, tagTextPaint)
    }

    // --- skeletons -----------------------------------------------------------------------

    private fun drawSkeletons(canvas: Canvas, scene: Scene, vp: Viewport, now: Long) {
        val personColor = Kind.PERSON.color
        val bones = SkeletonTracker.BONES
        for (s in scene.skeletons.skeletons) {
            val a = (scene.skeletons.alpha(s, now) * 255).roundToInt()
            val p = s.shown

            shadowPaint.strokeWidth = 4.5f * u
            shadowPaint.alpha = (a * 0.5f).roundToInt()
            linePaint.strokeWidth = 2.2f * u
            linePaint.color = bone
            linePaint.alpha = a
            var b = 0
            while (b < bones.size) {
                val i = bones[b]
                val j = bones[b + 1]
                b += 2
                if (!visible(p, i) || !visible(p, j)) continue
                val x1 = vp.x(p[i * 3])
                val y1 = vp.y(p[i * 3 + 1])
                val x2 = vp.x(p[j * 3])
                val y2 = vp.y(p[j * 3 + 1])
                canvas.drawLine(x1, y1, x2, y2, shadowPaint)
                canvas.drawLine(x1, y1, x2, y2, linePaint)
            }

            val ls = SkeletonTracker.LEFT_SHOULDER
            val rs = SkeletonTracker.RIGHT_SHOULDER
            val nose = SkeletonTracker.NOSE
            val shouldersSeen = visible(p, ls) && visible(p, rs)
            if (shouldersSeen && visible(p, nose)) {
                val mx = vp.x((p[ls * 3] + p[rs * 3]) / 2f)
                val my = vp.y((p[ls * 3 + 1] + p[rs * 3 + 1]) / 2f)
                val nx = vp.x(p[nose * 3])
                val ny = vp.y(p[nose * 3 + 1])
                canvas.drawLine(mx, my, nx, ny, shadowPaint)
                canvas.drawLine(mx, my, nx, ny, linePaint)
            }

            fillPaint.color = personColor
            fillPaint.alpha = a
            for (j in SkeletonTracker.JOINTS) {
                if (!visible(p, j)) continue
                canvas.drawCircle(vp.x(p[j * 3]), vp.y(p[j * 3 + 1]), 2.6f * u, fillPaint)
            }

            if (visible(p, nose)) {
                val le = SkeletonTracker.LEFT_EAR
                val re = SkeletonTracker.RIGHT_EAR
                val headImage = when {
                    visible(p, le) && visible(p, re) ->
                        hypot(p[le * 3] - p[re * 3], p[le * 3 + 1] - p[re * 3 + 1]) * 0.65f
                    shouldersSeen ->
                        hypot(p[ls * 3] - p[rs * 3], p[ls * 3 + 1] - p[rs * 3 + 1]) * 0.3f
                    else -> 0f
                }
                val headR = headImage * vp.scale
                val hx = vp.x(p[nose * 3])
                val hy = vp.y(p[nose * 3 + 1])
                if (headR > 3f * u) {
                    boxPaint.strokeWidth = 2f * u
                    boxPaint.color = personColor
                    boxPaint.alpha = a
                    shadowPaint.strokeWidth = 4f * u
                    canvas.drawCircle(hx, hy, headR, shadowPaint)
                    canvas.drawCircle(hx, hy, headR, boxPaint)
                }
                // Head "hitbox" dot.
                fillPaint.color = Color.WHITE
                fillPaint.alpha = a
                canvas.drawCircle(hx, hy, 3.2f * u, fillPaint)
                fillPaint.color = personColor
                fillPaint.alpha = a
                canvas.drawCircle(hx, hy, 2f * u, fillPaint)
            }
        }
    }

    private fun visible(p: FloatArray, i: Int): Boolean = p[i * 3 + 2] >= 0.5f

    // --- remembered objects --------------------------------------------------------------

    private fun drawMarks(canvas: Canvas, scene: Scene, vp: Viewport, now: Long) {
        val cx = vp.width / 2f
        val cy = vp.height / 2f
        val left = vp.safeLeft + 26f * u
        val right = vp.width - vp.safeRight - 26f * u
        val top = vp.safeTop + 92f * u
        val bottom = vp.usableBottom - 26f * u
        if (right <= left || bottom <= top || cy <= top || cy >= bottom) return

        for (m in scene.memory.marks.values) {
            if (scene.tracker.isActive(m.id)) continue
            val info = Labels.of(m.label)
            if (!scene.settings.filter.accepts(info.kind)) continue
            val age = now - m.lastSeen
            val a = (255 * (1f - age.toFloat() / Memory.MAX_AGE_MS).coerceIn(0.4f, 1f)).roundToInt()
            val seconds = (age / 1000).toInt()
            val distance = m.distance?.let { " ~${formatDistance(it)}" } ?: ""
            val label = "${info.title} ${seconds}с$distance"

            var dx: Float
            var dy: Float
            if (scene.projectMark(m, point)) {
                val sx = vp.x(point[0])
                val sy = vp.y(point[1])
                if (sx in 0f..vp.width && sy in 0f..vp.height) {
                    drawLostSpot(canvas, vp, sx, sy, info.kind.color, a, label)
                    continue
                }
                dx = sx - cx
                dy = sy - cy
            } else {
                dx = vp.dirX(point[0])
                dy = point[1]
            }
            if (abs(dx) < 1e-4f && abs(dy) < 1e-4f) dx = 1f

            var t = Float.MAX_VALUE
            if (dx > 0f) t = min(t, (right - cx) / dx) else if (dx < 0f) t = min(t, (left - cx) / dx)
            if (dy > 0f) t = min(t, (bottom - cy) / dy) else if (dy < 0f) t = min(t, (top - cy) / dy)
            val ex = cx + dx * t
            val ey = cy + dy * t
            val angle = atan2(dy, dx)
            drawArrow(canvas, ex, ey, angle, info.kind.color, a)

            val lx = ex - cos(angle) * 30f * u
            val ly = ey - sin(angle) * 30f * u
            val w = tagTextPaint.measureText(label) + 8f * u
            val tagX = max(left - 20f * u, min(lx - w / 2f, right + 20f * u - w))
            drawTag(canvas, vp, label, tagX, ly - 8f * u, info.kind.color, a)
        }
    }

    private fun drawArrow(canvas: Canvas, x: Float, y: Float, angle: Float, color: Int, a: Int) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat())
        path.reset()
        path.moveTo(13f * u, 0f)
        path.lineTo(-8f * u, -9f * u)
        path.lineTo(-3f * u, 0f)
        path.lineTo(-8f * u, 9f * u)
        path.close()
        shadowPaint.strokeWidth = 3f * u
        shadowPaint.alpha = (a * 0.6f).roundToInt()
        canvas.drawPath(path, shadowPaint)
        fillPaint.color = color
        fillPaint.alpha = a
        canvas.drawPath(path, fillPaint)
        canvas.restore()
    }

    /** The object was here a moment ago but the detector no longer sees it. */
    private fun drawLostSpot(canvas: Canvas, vp: Viewport, x: Float, y: Float, color: Int, a: Int, label: String) {
        val r = 9f * u
        path.reset()
        path.moveTo(x, y - r)
        path.lineTo(x + r, y)
        path.lineTo(x, y + r)
        path.lineTo(x - r, y)
        path.close()
        boxPaint.strokeWidth = 2f * u
        boxPaint.color = color
        boxPaint.alpha = a
        canvas.drawPath(path, boxPaint)
        drawTag(canvas, vp, label, x - r, y + r + 3f * u, color, a)
    }

    // --- HUD -----------------------------------------------------------------------------

    private fun drawCrosshair(canvas: Canvas, vp: Viewport, target: Track?) {
        val cx = vp.width / 2f
        val cy = vp.height / 2f
        val color = target?.let { Labels.of(it.label).kind.color } ?: hudText
        crossPaint.color = color
        crossPaint.alpha = if (target != null) 255 else 200
        val ring = 11f * u
        val gap = 5f * u
        val arm = 9f * u
        canvas.drawCircle(cx, cy, ring, crossPaint)
        canvas.drawLine(cx - ring - arm, cy, cx - gap, cy, crossPaint)
        canvas.drawLine(cx + gap, cy, cx + ring + arm, cy, crossPaint)
        canvas.drawLine(cx, cy - ring - arm, cx, cy - gap, crossPaint)
        canvas.drawLine(cx, cy + gap, cx, cy + ring + arm, crossPaint)
        fillPaint.color = color
        fillPaint.alpha = 255
        canvas.drawCircle(cx, cy, 1.8f * u, fillPaint)
    }

    private fun hudX(vp: Viewport) = vp.safeLeft + 14f * u

    private fun hudTop(vp: Viewport) = vp.safeTop + 22f * u

    private fun drawHud(canvas: Canvas, scene: Scene, vp: Viewport) {
        val x = hudX(vp)
        var y = hudTop(vp)
        val line = 17f * u
        hudPaint.textAlign = Paint.Align.LEFT

        hudPaint.color = accent
        canvas.drawText("ESP·CAM", x, y, hudPaint)
        y += line
        hudPaint.color = hudText
        if (scene.imageW == 0) {
            canvas.drawText("ПОИСК ЦЕЛЕЙ…", x, y, hudPaint)
            return
        }
        val device = if (scene.gpuActive) "GPU" else "CPU"
        canvas.drawText("FPS ${scene.fps.roundToInt()} · ${scene.inferenceMs} мс · $device", x, y, hudPaint)
        y += line
        var count = 0
        for (t in scene.tracker.tracks) if (scene.settings.filter.accepts(Labels.of(t.label).kind)) count++
        canvas.drawText("ОБЪЕКТОВ $count · ${scene.settings.filter.title}", x, y, hudPaint)
    }

    private fun drawTargetCard(canvas: Canvas, scene: Scene, vp: Viewport, t: Track) {
        val info = Labels.of(t.label)
        val x = vp.width - vp.safeRight - 14f * u
        var y = hudTop(vp)
        val line = 17f * u
        hudPaint.textAlign = Paint.Align.RIGHT

        hudPaint.color = info.kind.color
        canvas.drawText("ЦЕЛЬ ЗАХВАЧЕНА", x, y, hudPaint)
        y += line
        hudPaint.color = hudText
        canvas.drawText("${info.title} #${t.id}", x, y, hudPaint)
        y += line
        canvas.drawText(metaText(scene, t).replace(" · ", "  ·  "), x, y, hudPaint)
        hudPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawRadar(canvas: Canvas, scene: Scene, vp: Viewport, now: Long) {
        val r = 54f * u
        val cx = hudX(vp) + r
        val cy = hudTop(vp) + 2f * 17f * u + 16f * u + r
        if (cy + r > vp.usableBottom) return

        fillPaint.color = Color.BLACK
        fillPaint.alpha = 115
        canvas.drawCircle(cx, cy, r, fillPaint)

        // Field of view: the part of the world that is on screen right now.
        val f = scene.focalPx()
        val halfFov = atan((vp.width / vp.scale / 2f) / f)
        val halfDeg = Math.toDegrees(halfFov.toDouble()).toFloat()
        tmp.set(cx - r, cy - r, cx + r, cy + r)
        path.reset()
        path.moveTo(cx, cy)
        path.arcTo(tmp, -90f - halfDeg, 2f * halfDeg)
        path.close()
        fillPaint.color = accent
        fillPaint.alpha = 34
        canvas.drawPath(path, fillPaint)

        linePaint.strokeWidth = 1f * u
        linePaint.color = accent
        linePaint.alpha = 70
        for (k in 1..3) canvas.drawCircle(cx, cy, r * k / 3f, linePaint)
        canvas.drawLine(cx, cy - r, cx, cy + r, linePaint)
        canvas.drawLine(cx - r, cy, cx + r, cy, linePaint)

        tagTextPaint.color = accent
        tagTextPaint.alpha = 170
        val small = tagTextPaint.textSize
        tagTextPaint.textSize = 8.5f * u
        for (k in 1..3) {
            val meters = (RADAR_RANGE_M * k / 3).roundToInt()
            canvas.drawText(if (k == 3) "$meters м" else "$meters", cx + 2f * u, cy - r * k / 3f + 9f * u, tagTextPaint)
        }
        tagTextPaint.textSize = small

        // You, looking up the radar.
        path.reset()
        path.moveTo(cx, cy - 5f * u)
        path.lineTo(cx + 4f * u, cy + 4f * u)
        path.lineTo(cx - 4f * u, cy + 4f * u)
        path.close()
        fillPaint.color = hudText
        fillPaint.alpha = 255
        canvas.drawPath(path, fillPaint)

        for (t in scene.tracker.tracks) {
            val info = Labels.of(t.label)
            if (!scene.settings.filter.accepts(info.kind)) continue
            val angle = vp.dirX(scene.angleAt(t.shown.centerX()))
            val a = (scene.tracker.alpha(t, now) * 255).roundToInt()
            drawBlip(canvas, cx, cy, r, angle, scene.distanceMeters(t), info.kind.color, a, filled = true)
            if (t === scene.target) {
                val d = scene.distanceMeters(t)
                val rr = blipRadius(r, d)
                linePaint.color = Color.WHITE
                linePaint.alpha = 220
                linePaint.strokeWidth = 1.4f * u
                canvas.drawCircle(cx + sin(angle) * rr, cy - cos(angle) * rr, 6f * u, linePaint)
            }
        }

        if (scene.settings.markers && scene.orientation.ready) {
            for (m in scene.memory.marks.values) {
                if (scene.tracker.isActive(m.id)) continue
                val info = Labels.of(m.label)
                if (!scene.settings.filter.accepts(info.kind)) continue
                scene.projectMark(m, point)
                drawBlip(canvas, cx, cy, r, vp.dirX(point[2]), m.distance, info.kind.color, 190, filled = false)
            }
        }
    }

    private fun blipRadius(r: Float, distance: Float?): Float =
        if (distance == null) r * 0.92f else min(distance / RADAR_RANGE_M, 1f) * r

    private fun drawBlip(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        angle: Float,
        distance: Float?,
        color: Int,
        a: Int,
        filled: Boolean,
    ) {
        val rr = blipRadius(r, distance)
        val x = cx + sin(angle) * rr
        val y = cy - cos(angle) * rr
        val unknown = distance == null || distance > RADAR_RANGE_M
        if (filled && !unknown) {
            fillPaint.color = color
            fillPaint.alpha = a
            canvas.drawCircle(x, y, 3.4f * u, fillPaint)
        } else {
            boxPaint.strokeWidth = 1.6f * u
            boxPaint.color = color
            boxPaint.alpha = a
            canvas.drawCircle(x, y, 3f * u, boxPaint)
        }
    }

    /** REC badge, zoom label, focus ring and the photo flash: screen only. */
    private fun drawTransient(canvas: Canvas, scene: Scene, vp: Viewport, now: Long) {
        if (scene.recordingSince > 0L) {
            val seconds = (now - scene.recordingSince) / 1000
            val text = String.format(ru, "REC %02d:%02d", seconds / 60, seconds % 60)
            hudPaint.textAlign = Paint.Align.CENTER
            hudPaint.color = 0xFFFF3B3B.toInt()
            val y = hudTop(vp)
            canvas.drawText(text, vp.width / 2f + 6f * u, y, hudPaint)
            if ((now / 500) % 2 == 0L) {
                fillPaint.color = 0xFFFF3B3B.toInt()
                fillPaint.alpha = 255
                canvas.drawCircle(vp.width / 2f - hudPaint.measureText(text) / 2f - 4f * u, y - 4f * u, 4f * u, fillPaint)
            }
            hudPaint.textAlign = Paint.Align.LEFT
        }

        val zoomAge = now - scene.zoomChangedAt
        if (zoomAge < 1200L) {
            hudPaint.textAlign = Paint.Align.CENTER
            hudPaint.color = hudText
            hudPaint.alpha = (255 * (1f - zoomAge / 1200f)).roundToInt().coerceIn(0, 255)
            val size = hudPaint.textSize
            hudPaint.textSize = 22f * u
            canvas.drawText(String.format(ru, "%.1f×", scene.zoom), vp.width / 2f, vp.usableBottom - 40f * u, hudPaint)
            hudPaint.textSize = size
            hudPaint.textAlign = Paint.Align.LEFT
        }

        val focusAge = now - scene.focusAt
        if (focusAge < 900L) {
            val k = focusAge / 900f
            linePaint.color = accent
            linePaint.alpha = (255 * (1f - k)).roundToInt()
            linePaint.strokeWidth = 1.6f * u
            canvas.drawCircle(scene.focusX, scene.focusY, (42f - 14f * min(1f, k * 3f)) * u, linePaint)
        }

        val flashAge = now - scene.flashAt
        if (flashAge < 220L) {
            fillPaint.color = Color.WHITE
            fillPaint.alpha = (200 * (1f - flashAge / 220f)).roundToInt()
            canvas.drawRect(0f, 0f, vp.width, vp.height, fillPaint)
        }
    }

    private fun formatDistance(m: Float): String =
        if (m < 10f) String.format(ru, "%.1f м", m) else "${m.roundToInt()} м"

    private companion object {
        const val RADAR_RANGE_M = 15f
    }
}
