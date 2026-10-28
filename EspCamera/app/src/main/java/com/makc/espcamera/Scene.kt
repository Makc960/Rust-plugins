package com.makc.espcamera

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/** Falling glyph columns for the "matrix" view, in screen heights so any viewport can draw them. */
class MatrixRain {
    /** Head of each column: 0 is the top of the screen, 1 the bottom. */
    val heads = FloatArray(COLUMNS) { Random.nextFloat() * 1.6f - 0.6f }
    private val speeds = FloatArray(COLUMNS) { randomSpeed() }

    /** Milliseconds since start, for glyph flicker. */
    var clock = 0L
        private set

    fun step(dtMs: Long) {
        clock += dtMs
        for (i in 0 until COLUMNS) {
            heads[i] += speeds[i] * dtMs / 1000f
            if (heads[i] > 1.5f) {
                heads[i] = -Random.nextFloat() * 0.6f
                speeds[i] = randomSpeed()
            }
        }
    }

    private fun randomSpeed(): Float = 0.22f + Random.nextFloat() * 0.5f

    companion object {
        const val COLUMNS = 26
        const val TAIL = 16
        val GLYPHS = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎ0123456789ЖЭЮЯΣΞ".toCharArray()
    }
}

/**
 * Everything the ESP layer shows, owned by the main thread. The screen,
 * photos and video frames are all drawn from this one state.
 */
class Scene(val settings: Settings, val orientation: Orientation) {
    val tracker = Tracker()
    val skeletons = SkeletonTracker()
    val memory = Memory()
    val rain = MatrixRain()

    var imageW = 0
        private set
    var imageH = 0
        private set

    var focalMm = 0f
    var sensorLongMm = 0f
    var zoom = 1f
    var front = false
    var gpuActive = false

    var fps = 0f
        private set
    var inferenceMs = 0L
        private set
    private var lastFrameAt = 0L

    /** Latest thermal / night / matrix picture, when one of those views is on. */
    var filtered: Bitmap? = null

    var recordingSince = 0L
    var zoomChangedAt = 0L
    var focusX = 0f
    var focusY = 0f
    var focusAt = 0L
    var flashAt = 0L

    /** The part of the frame that is on screen, in image pixels; kept up to date by the view. */
    val visibleImage = RectF()

    var target: Track? = null
        private set
    var onTargetLocked: ((Track) -> Unit)? = null
    private var lockedId = -1
    private var lockedAt = 0L

    private val ray = FloatArray(3)
    private val world = FloatArray(3)
    private val projected = FloatArray(3)

    /** The camera looks out of the back (-z) or, for selfies, out of the screen (+z). */
    private val forward: Float get() = if (front) 1f else -1f

    fun submitObjects(frame: Vision.ObjectFrame, now: Long) {
        if (frame.width != imageW || frame.height != imageH) {
            tracker.clear()
            skeletons.clear()
            filtered = null
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

        if (settings.markers && orientation.ready) {
            for (t in tracker.tracks) {
                if (t.lastSeen != now) continue
                rayAt(t.target.centerX(), t.target.centerY(), ray)
                orientation.toWorld(ray[0], ray[1], ray[2], world)
                memory.observe(t.id, t.label, world, distanceMeters(t, t.target), now)
            }
        }
    }

    fun submitPoses(frame: Vision.PoseFrame, now: Long) {
        if (frame.width == imageW && frame.height == imageH) skeletons.update(frame.poses, now, imageW)
    }

    /** After switching lenses everything on screen belongs to the other camera. */
    fun resetView() {
        tracker.clear()
        skeletons.clear()
        filtered = null
        imageW = 0
        imageH = 0
        fps = 0f
        lastFrameAt = 0L
        target = null
        lockedId = -1
    }

    fun step(now: Long, dtMs: Long) {
        tracker.step(dtMs)
        skeletons.step(dtMs)
        if (settings.vision == VisionMode.MATRIX) rain.step(dtMs)
        if (imageW == 0) return
        updateTarget(now)
        if (settings.markers && orientation.ready) {
            memory.prune(now, tracker::isActive) { m ->
                projectMark(m, projected) && visibleImage.contains(projected[0], projected[1])
            }
        }
    }

    /** The locked target is the smallest box under the crosshair. */
    private fun updateTarget(now: Long) {
        val cx = imageW / 2f
        val cy = imageH / 2f
        var best: Track? = null
        var bestArea = Float.MAX_VALUE
        for (t in tracker.tracks) {
            if (now - t.lastSeen > LOCK_STALE_MS) continue
            if (!settings.filter.accepts(Labels.of(t.label).kind)) continue
            if (!t.shown.contains(cx, cy)) continue
            val area = t.shown.width() * t.shown.height()
            if (area < bestArea) {
                bestArea = area
                best = t
            }
        }
        target = best
        val id = best?.id ?: -1
        if (id != lockedId) {
            lockedId = id
            if (best != null && now - lockedAt > LOCK_COOLDOWN_MS) {
                lockedAt = now
                onTargetLocked?.invoke(best)
            }
        }
    }

    /** Focal length in image pixels, including zoom. */
    fun focalPx(): Float {
        val longPx = max(imageW, imageH).toFloat()
        val base = if (focalMm > 0f && sensorLongMm > 0f) {
            focalMm / sensorLongMm * longPx
        } else {
            // Typical main phone camera: about 66 degrees along the long side.
            (longPx / 2f) / tan(Math.toRadians(33.0)).toFloat()
        }
        return base * zoom
    }

    /**
     * Pinhole estimate: distance = real height * focal length (px) / box height (px).
     * Skipped when the box is cut by the frame, since its height is then wrong.
     */
    fun distanceMeters(t: Track, box: RectF = t.shown): Float? {
        val realHeight = Labels.of(t.label).heightM ?: return null
        val edge = imageH * 0.02f
        if (box.top <= edge || box.bottom >= imageH - edge || box.height() < 4f) return null
        return realHeight * focalPx() / box.height()
    }

    /** Horizontal angle to an image column, radians, positive to the right. */
    fun angleAt(u: Float): Float = atan2(u - imageW / 2f, focalPx())

    /** Unit ray through an image point, in screen coordinates (x right, y up, z out of the screen). */
    fun rayAt(u: Float, v: Float, out: FloatArray) {
        val f = focalPx()
        val x = (u - imageW / 2f) / f
        val y = -(v - imageH / 2f) / f
        val z = forward
        val len = sqrt(x * x + y * y + z * z)
        out[0] = x / len
        out[1] = y / len
        out[2] = z / len
    }

    /**
     * Where a remembered object is relative to the camera now. When it is in
     * front, [out] gets its image point and this returns true; otherwise [out]
     * gets the image-space direction towards it. out[2] is always the horizontal
     * angle to it in radians, positive to the right.
     */
    fun projectMark(m: Mark, out: FloatArray): Boolean {
        orientation.toScreen(m.world, ray)
        val depth = ray[2] * forward
        out[2] = atan2(ray[0], depth)
        if (depth > 0.05f) {
            val f = focalPx()
            out[0] = imageW / 2f + f * ray[0] / depth
            out[1] = imageH / 2f - f * ray[1] / depth
            return true
        }
        out[0] = ray[0]
        out[1] = -ray[1]
        return false
    }

    private companion object {
        const val LOCK_STALE_MS = 300L
        const val LOCK_COOLDOWN_MS = 400L
    }
}
