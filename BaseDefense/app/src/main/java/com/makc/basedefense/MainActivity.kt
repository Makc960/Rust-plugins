package com.makc.basedefense

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var store: Store

    /** Who waits for the player's answer about notifications. */
    private var notificationsAnswer: ((Boolean) -> Unit)? = null
    private val notificationsQuestion = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAnswer?.invoke(granted)
        notificationsAnswer = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        AndroidGfx.preload(cacheDir)
        store = Stores.create(this)
        // A payment app may have opened the game to say how a payment went.
        if (savedInstanceState == null) store.handleIntent(intent)
        platform = AndroidPlatform(this, store, Notices(this), ::askNotifications)
        // The intro plays when the game opens, not when Android brings it back.
        game = Game(platform, showIntro = savedInstanceState == null)
        view = GameView(this, game)
        setContentView(view)
        // The launch picture was only for the start; the game paints the whole screen from here on.
        window.setBackgroundDrawableResource(R.color.background)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android's launch screen melts into the intro, its emblem coming at the player.
            splashScreen.setOnExitAnimationListener { splash ->
                splash.iconView?.animate()?.scaleX(SPLASH_ZOOM)?.scaleY(SPLASH_ZOOM)?.setDuration(SPLASH_EXIT_MS)?.start()
                splash.animate().alpha(0f).setDuration(SPLASH_EXIT_MS).withEndAction { splash.remove() }.start()
            }
        }
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

    /** Android 13 and later ask the player; older ones keep the switch in the phone's settings, opened here. */
    private fun askNotifications(done: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationsAnswer = done
            notificationsQuestion.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } catch (e: RuntimeException) {
                // No such settings page on this phone.
            }
            done(false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        store.handleIntent(intent)
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

    private companion object {
        const val SPLASH_EXIT_MS = 380L
        const val SPLASH_ZOOM = 1.6f
    }
}
