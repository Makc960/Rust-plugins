package com.makc.shturman

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import kotlin.math.max

/**
 * The navigator is a web page in a WebView (see web/): this activity hosts it, lets it reach the
 * phone through [Bridge], and passes in the back button, the screen's edges and links from other apps.
 */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var bridge: Bridge
    private var loaded = false
    private var pendingLink: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#F4F6FA"))
        setContentView(web)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        // The page comes from the app's own assets at a https address, as a web app would.
        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            // The layout is made for the default text size; a large system font would spill out of it.
            textZoom = 100
            setGeolocationEnabled(false)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val response = assets.shouldInterceptRequest(request.url) ?: return null
                // Module scripts load only with a script type, and Android does not know ".mjs".
                typeOf(request.url.path.orEmpty())?.let {
                    response.mimeType = it
                    response.encoding = "utf-8"
                }
                return response
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == HOST) return false
                openExternal(request.url)
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loaded = true
                ViewCompat.requestApplyInsets(web)
                pendingLink?.let { deliverLink(it) }
                pendingLink = null
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // The page's process died (out of memory, mostly): start over rather than crash.
                recreate()
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.w(TAG, "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                }
                return true
            }
        }

        bridge = Bridge(this, web)
        web.addJavascriptInterface(bridge, "ShturmanNative")

        // The page draws under the status and navigation bars and keeps its buttons clear of them.
        ViewCompat.setOnApplyWindowInsetsListener(web) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val d = resources.displayMetrics.density
            js("insets(${bars.top / d}, ${bars.right / d}, ${max(bars.bottom, ime.bottom) / d}, ${bars.left / d})")
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!loaded) {
                    finish()
                    return
                }
                web.evaluateJavascript("window.__shturman ? window.__shturman.back() : false") { result ->
                    if (result != "true") finish()
                }
            }
        })

        if (savedInstanceState == null) pendingLink = linkOf(intent)
        web.loadUrl("https://$HOST/assets/web/index.html")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // "Завершить" in the driving notification.
        if (intent.action == NavService.ACTION_END) {
            js("command('end')")
            return
        }
        val link = linkOf(intent) ?: return
        if (loaded) deliverLink(link) else pendingLink = link
    }

    override fun onStart() {
        super.onStart()
        bridge.onVisible(true)
    }

    override fun onStop() {
        bridge.onVisible(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        js("resume()")
    }

    override fun onPause() {
        // Driving, the page keeps running behind another app so the voice goes on.
        if (!bridge.navigating) web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        bridge.release()
        web.destroy()
        super.onDestroy()
    }

    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Dark status bar icons over the day map, light ones at night. */
    fun setDark(dark: Boolean) {
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.isAppearanceLightStatusBars = !dark
        bars.isAppearanceLightNavigationBars = !dark
        web.setBackgroundColor(Color.parseColor(if (dark) "#0D131D" else "#F4F6FA"))
    }

    fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Log.w(TAG, "nothing opens $uri", e)
        }
    }

    /** Calls the page's window.__shturman.[call] on the main thread. */
    fun js(call: String) {
        if (!loaded) return
        runOnUiThread { web.evaluateJavascript("window.__shturman && window.__shturman.$call", null) }
    }

    private fun typeOf(path: String): String? = when (path.substringAfterLast('.', "")) {
        "mjs", "js" -> "text/javascript"
        "css" -> "text/css"
        "html" -> "text/html"
        "json" -> "application/json"
        else -> null
    }

    private fun deliverLink(link: String) {
        js("intent(${JSONObject.quote(link)})")
    }

    private fun linkOf(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return intent.dataString
    }

    companion object {
        const val TAG = "Shturman"
        const val HOST = "appassets.androidplatform.net"
    }
}
