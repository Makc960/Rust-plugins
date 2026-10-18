package com.makc.basedefense

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import com.makc.basedefense.engine.Game
import com.makc.basedefense.engine.Touch

/** Runs the game loop at display rate and forwards touches. */
@SuppressLint("ViewConstructor")
class GameView(context: Context, private val game: Game) : View(context) {
    private val gfx = AndroidGfx()
    private var last = 0L
    private var insetTop = 0
    private var insetBottom = 0

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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> Touch.DOWN
            MotionEvent.ACTION_MOVE -> Touch.MOVE
            MotionEvent.ACTION_UP -> Touch.UP
            MotionEvent.ACTION_CANCEL -> Touch.CANCEL
            else -> return true
        }
        game.touch(action, event.x, event.y)
        if (action == Touch.UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
