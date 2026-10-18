package com.makc.basedefense

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.makc.basedefense.engine.Game
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private lateinit var platform: AndroidPlatform
    private lateinit var game: Game
    private lateinit var view: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        AndroidGfx.preload(cacheDir)
        platform = AndroidPlatform(this)
        game = Game(platform)
        view = GameView(this, game)
        setContentView(view)
        hideSystemBars()

        // The bars are hidden, but the HUD still keeps clear of the camera cutout
        // and of where the status bar slides in.
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val status = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())
            view.setInsets(max(cutout.top, status.top), cutout.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!game.back()) finish()
                }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        view.resetClock()
        hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        game.pause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        super.onDestroy()
        platform.release()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
