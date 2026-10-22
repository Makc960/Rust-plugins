package com.makc.basedefense

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.makc.basedefense.engine.Game
import com.makc.basedefense.engine.Touch

/** Runs the game loop at display rate and forwards touches: one finger plays, two zoom and move the map. */
@SuppressLint("ViewConstructor")
class GameView(context: Context, private val game: Game) : View(context) {
    private val gfx = AndroidGfx()
    private var last = 0L
    private var insetTop = 0
    private var insetBottom = 0

    private var twoFingers = false
    private var focusX = 0f
    private var focusY = 0f
    private var focusValid = false

    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                game.pinch(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        },
    )

    init {
        // Double-tap-and-drag zoom would fight with taps on the map; two fingers are enough.
        scaler.isQuickScaleEnabled = false
    }

    fun setInsets(top: Int, bottom: Int) {
        insetTop = top
        insetBottom = bottom
        if (width > 0) resizeGame()
    }

    /** After a pause the first frame should not jump. */
    fun resetClock() {
        last = 0L
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        resizeGame()
    }

    private fun resizeGame() {
        game.resize(width, height, resources.displayMetrics.density, insetTop, insetBottom)
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
        last = now
        game.update(dt)
        gfx.canvas = canvas
        game.render(gfx)
        postInvalidateOnAnimation()
    }

    /** The middle of the fingers still down, leaving out one that is lifting. */
    private fun focus(event: MotionEvent, skip: Int) {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (i in 0 until event.pointerCount) {
            if (i == skip) continue
            sx += event.getX(i)
            sy += event.getY(i)
            n++
        }
        if (n == 0) return
        focusX = sx / n
        focusY = sy / n
        focusValid = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                twoFingers = false
                focusValid = false
                game.touch(Touch.DOWN, event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!twoFingers) {
                    // A second finger turns a tap or a drag into a pinch.
                    twoFingers = true
                    game.touch(Touch.CANCEL, event.getX(0), event.getY(0))
                }
                focus(event, -1)
            }
            MotionEvent.ACTION_MOVE -> if (twoFingers) {
                val fx = focusX
                val fy = focusY
                val had = focusValid
                focus(event, -1)
                if (had) game.pan(focusX - fx, focusY - fy)
            } else {
                game.touch(Touch.MOVE, event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_UP -> focus(event, event.actionIndex)
            MotionEvent.ACTION_UP -> {
                if (!twoFingers) game.touch(Touch.UP, event.x, event.y)
                twoFingers = false
                focusValid = false
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                game.touch(Touch.CANCEL, event.x, event.y)
                twoFingers = false
                focusValid = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
