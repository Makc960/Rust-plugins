package com.makc.espcamera

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * The ESP layer over the camera preview. It animates the [Scene] at display
 * rate and draws it; photos and video frames reuse the same renderer.
 */
class EspOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    lateinit var scene: Scene

    /** Space at the bottom taken by the buttons; snaplines start above it. */
    var bottomReserved = 0

    private val renderer = EspRenderer()
    private val screen = Viewport()
    private val still = Viewport()
    private val video = Viewport()
    private val density = resources.displayMetrics.density

    private var safeLeft = 0
    private var safeTop = 0
    private var safeRight = 0
    private var safeBottom = 0
    private var lastDrawAt = 0L

    fun setSafeArea(left: Int, top: Int, right: Int, bottom: Int) {
        safeLeft = left
        safeTop = top
        safeRight = right
        safeBottom = bottom
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!::scene.isInitialized) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastDrawAt == 0L) 16L else (now - lastDrawAt).coerceIn(0L, 100L)
        lastDrawAt = now
        scene.step(now, dt)

        setupScreen(screen, capture = false)
        renderer.render(canvas, scene, screen, now)
        if (scene.imageW > 0) screen.visibleImage(scene.imageW, scene.imageH, scene.visibleImage)

        postInvalidateOnAnimation()
    }

    /** Draws what is on screen, minus the buttons and the transient UI, for a photo of the same size. */
    fun renderStill(canvas: Canvas) {
        setupScreen(still, capture = true)
        renderer.render(canvas, scene, still, SystemClock.uptimeMillis())
    }

    /**
     * Draws the ESP layer into a video frame whose upright size is [w] x [h].
     * [mirrored] tells whether that frame is shown mirrored, like the selfie preview.
     */
    fun renderVideo(canvas: Canvas, w: Int, h: Int, mirrored: Boolean) {
        video.width = w.toFloat()
        video.height = h.toFloat()
        // Same proportions as on screen, whatever the video resolution.
        video.unit = density * max(w, h) / max(1, max(width, height)).toFloat()
        video.flipX = mirrored != scene.front
        video.safeLeft = 0f
        video.safeTop = 0f
        video.safeRight = 0f
        video.safeBottom = 0f
        video.bottomReserved = 0f
        video.capture = true
        renderer.render(canvas, scene, video, SystemClock.uptimeMillis())
    }

    private fun setupScreen(vp: Viewport, capture: Boolean) {
        vp.width = width.toFloat()
        vp.height = height.toFloat()
        vp.unit = density
        vp.flipX = false
        vp.safeLeft = safeLeft.toFloat()
        vp.safeTop = safeTop.toFloat()
        vp.safeRight = safeRight.toFloat()
        vp.safeBottom = if (capture) 0f else safeBottom.toFloat()
        vp.bottomReserved = if (capture) 0f else bottomReserved.toFloat()
        vp.capture = capture
    }
}
