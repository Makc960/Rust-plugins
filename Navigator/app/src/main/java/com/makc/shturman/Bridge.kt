package com.makc.shturman

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.RecognizerIntent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * What the page can ask of the phone, as window.ShturmanNative: position, voice both ways, network,
 * the map kept for offline, keeping the screen on and bright, sharing. Calls come on WebView's own
 * thread; answers go back through window.__shturman (see web/src/js/platform.js).
 */
class Bridge(private val activity: MainActivity, private val web: WebView, private val tiles: TileCache) {
    private val locations = activity.getSystemService(LocationManager::class.java)
    private val voice = Voice(activity) { status -> activity.js("voiceStatus(${JSONObject.quote(status)})") }
    private val network = Executors.newFixedThreadPool(3)

    /** "nav": a position every second; "browse": now and then, to spare the battery. */
    private var locationMode = "browse"

    /** The page wants positions; they stop while the app is out of sight, unless driving. */
    private var wanted = false
    private var listening = false
    private var visible = true
    private var lastGps = 0L

    @Volatile
    var navigating = false
        private set

    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) listen() else activity.js("locationStatus('denied')")
    }

    private val notifications = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private var askedNotifications = false

    // The phone's own listening window, for a destination said aloud.
    private val speech = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        activity.js("heard(${if (text.isNullOrBlank()) "null" else JSONObject.quote(text)})")
    }

    // Every method spelled out: before Android 11 the system calls them all and has no defaults.

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)

        override fun onProviderEnabled(provider: String) {
            if (LocationManagerCompat.isLocationEnabled(locations)) activity.js("locationStatus('ok')")
        }

        override fun onProviderDisabled(provider: String) {
            if (!LocationManagerCompat.isLocationEnabled(locations)) activity.js("locationStatus('off')")
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    // ---- Position ----

    @JavascriptInterface
    fun startLocation() = activity.runOnUiThread {
        wanted = true
        if (hasPermission()) listen()
        else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @JavascriptInterface
    fun stopLocation() = activity.runOnUiThread {
        wanted = false
        stopListening()
    }

    /** Asks again; once refused for good, only the app's settings page can allow it. */
    @JavascriptInterface
    fun requestLocation() = activity.runOnUiThread {
        wanted = true
        if (hasPermission()) {
            listen()
        } else if (activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) || !askedBefore) {
            askedBefore = true
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
        }
    }

    private var askedBefore = false

    @JavascriptInterface
    fun openLocationSettings() = activity.runOnUiThread {
        try {
            activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (e: Exception) {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun listen() {
        if (!hasPermission()) return
        if (!LocationManagerCompat.isLocationEnabled(locations)) {
            activity.js("locationStatus('off')")
        }
        if (listening) return
        try {
            val providers = locations.getProviders(true)
            // The last known position, if fresh, puts the map in place before the first fix.
            providers.mapNotNull { locations.getLastKnownLocation(it) }
                .filter { System.currentTimeMillis() - it.time < 120_000 }
                .minByOrNull { it.accuracy }
                ?.let { onLocation(it) }
            // Driving: every second. Looking at the map: every few seconds, and only after a move.
            val driving = locationMode == "nav"
            if (LocationManager.GPS_PROVIDER in locations.allProviders) {
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, if (driving) 1000L else 3000L, if (driving) 0f else 4f, listener, Looper.getMainLooper())
            }
            if (LocationManager.NETWORK_PROVIDER in locations.allProviders) {
                locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, if (driving) 3000L else 10_000L, 0f, listener, Looper.getMainLooper())
            }
            listening = true
        } catch (e: SecurityException) {
            activity.js("locationStatus('denied')")
        }
    }

    private fun stopListening() {
        if (!listening) return
        locations.removeUpdates(listener)
        listening = false
    }

    private fun onLocation(l: Location) {
        val now = System.currentTimeMillis()
        if (l.provider == LocationManager.GPS_PROVIDER) lastGps = now
        // While satellites answer, the rougher network positions would only make the car jump.
        else if (now - lastGps < 5000) return
        val fix = JSONObject()
            .put("lon", l.longitude)
            .put("lat", l.latitude)
            .put("accuracy", if (l.hasAccuracy()) l.accuracy.toDouble() else 50.0)
            .put("speed", if (l.hasSpeed()) l.speed.toDouble() else JSONObject.NULL)
            .put("heading", if (l.hasBearing() && l.hasSpeed() && l.speed > 0.5f) l.bearing.toDouble() else JSONObject.NULL)
            .put("time", l.time)
        activity.js("location($fix)")
    }

    /** How often to ask for positions: "nav" while driving, "browse" otherwise. */
    @JavascriptInterface
    fun setLocationMode(mode: String) = activity.runOnUiThread {
        if (mode == locationMode) return@runOnUiThread
        locationMode = mode
        if (listening) {
            stopListening()
            listen()
        }
    }

    /** The activity coming into sight or leaving it. */
    fun onVisible(shown: Boolean) {
        visible = shown
        if (shown) {
            if (wanted && hasPermission()) listen()
            else if (wanted && !hasPermission()) activity.js("locationStatus('denied')")
        } else if (!navigating) {
            stopListening()
        }
    }

    // ---- Voice ----

    @JavascriptInterface
    fun speak(text: String) = voice.speak(text)

    @JavascriptInterface
    fun stopSpeaking() = voice.stop()

    /** Whether the phone can listen: some phones have no speech recognition at all. */
    @JavascriptInterface
    fun canListen(): Boolean = try {
        activity.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    } catch (e: Exception) {
        false
    }

    /** Opens the phone's listening window; what was said comes back through heard(). */
    @JavascriptInterface
    fun listen(prompt: String) = activity.runOnUiThread {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            speech.launch(intent)
        } catch (e: ActivityNotFoundException) {
            activity.js("heard(null)")
        }
    }

    // ---- Network ----

    /**
     * Fetches [url] off the main thread and hands the page {status, text}, or status -1 on no answer.
     * [headers] is a JSON object of request headers (Content-Type for the body among them).
     */
    @JavascriptInterface
    fun http(id: Int, method: String, url: String, body: String, timeout: Int, headers: String) {
        network.execute {
            var status = -1
            var text = ""
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = method
                c.connectTimeout = timeout
                c.readTimeout = timeout
                c.setRequestProperty("User-Agent", USER_AGENT)
                c.setRequestProperty("Accept-Language", "ru")
                var type = "application/json"
                try {
                    val json = JSONObject(headers.ifEmpty { "{}" })
                    for (key in json.keys()) {
                        if (key.equals("Content-Type", ignoreCase = true)) type = json.getString(key)
                        else c.setRequestProperty(key, json.getString(key))
                    }
                } catch (e: Exception) {
                    // An old page passing just the body's type.
                    if (headers.contains('/')) type = headers
                }
                if (body.isNotEmpty()) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", type)
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                status = c.responseCode
                val stream = if (status >= 400) c.errorStream else c.inputStream
                text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()
            } catch (e: Exception) {
                status = -1
                text = e.javaClass.simpleName
            }
            activity.js("http($id, $status, ${JSONObject.quote(text)})")
        }
    }

    // ---- Everything else ----

    /** Driving starts or ends: the screen stays on, and guidance goes on in the background. */
    @JavascriptInterface
    fun keepScreenOn(on: Boolean) = activity.runOnUiThread {
        navigating = on
        activity.keepScreenOn(on)
        if (on) {
            if (hasPermission()) NavService.start(activity)
            // Android 13 and later show the notification only once allowed; asked at the first drive.
            if (Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
                ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                askedNotifications = true
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            NavService.stop(activity)
            if (!visible) stopListening()
        }
    }

    /** The next manoeuvre, for the notification while the app is out of sight. */
    @JavascriptInterface
    fun navStatus(title: String, text: String) = NavService.update(activity, title, text)

    @JavascriptInterface
    fun setTheme(theme: String) = activity.runOnUiThread { activity.setDark(theme == "night") }

    /** The windscreen view: the screen at full brightness, or back to what the phone chooses. */
    @JavascriptInterface
    fun hud(on: Boolean) = activity.runOnUiThread { activity.setBright(on) }

    /** {online, metered}: a network, and whether it is paid by the megabyte. */
    @JavascriptInterface
    fun network(): String {
        val cm = activity.getSystemService(ConnectivityManager::class.java)
            ?: return JSONObject().put("online", true).put("metered", true).toString()
        val active = cm.activeNetwork
        val caps = if (active != null) cm.getNetworkCapabilities(active) else null
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        return JSONObject().put("online", online).put("metered", cm.isActiveNetworkMetered).toString()
    }

    // ---- The map kept on the phone ----

    @JavascriptInterface
    fun prefetch(tag: String, urls: String, keep: Boolean) {
        val list = try {
            val json = JSONArray(urls)
            List(json.length()) { json.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
        if (list.isNotEmpty()) tiles.prefetch(tag, list, keep)
    }

    @JavascriptInterface
    fun cancelPrefetch(tag: String) = tiles.cancel(tag)

    @JavascriptInterface
    fun storage(): String {
        val (cache, offline) = tiles.sizes()
        return JSONObject().put("cache", cache).put("offline", offline).toString()
    }

    @JavascriptInterface
    fun clearMaps(which: String) = tiles.clear(which)

    /** «Рядом», if it is on the phone; the page to get it otherwise. */
    @JavascriptInterface
    fun openRyadom() = activity.runOnUiThread {
        val launch = activity.packageManager.getLaunchIntentForPackage(RYADOM)
        if (launch != null) activity.startActivity(launch)
        else activity.openExternal(Uri.parse("https://github.com/Makc960/Rust-plugins/releases/tag/ryadom"))
    }

    @JavascriptInterface
    fun share(text: String) = activity.runOnUiThread {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        activity.startActivity(Intent.createChooser(send, null))
    }

    @JavascriptInterface
    fun copy(text: String) = activity.runOnUiThread {
        activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Штурман", text))
    }

    @JavascriptInterface
    fun openUrl(url: String) = activity.runOnUiThread { activity.openExternal(Uri.parse(url)) }

    @JavascriptInterface
    fun vibrate(ms: Int) {
        val v = activity.getSystemService(Vibrator::class.java) ?: return
        if (v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
    }

    @JavascriptInterface
    fun exit() = activity.runOnUiThread { activity.finish() }

    @JavascriptInterface
    fun info(): String = JSONObject()
        .put("version", BuildConfig.VERSION_NAME)
        .put("store", BuildConfig.STORE)
        .put("supportUrl", BuildConfig.SUPPORT_URL)
        .toString()

    fun release() {
        NavService.stop(activity)
        stopListening()
        voice.release()
        network.shutdownNow()
    }

    companion object {
        val USER_AGENT = "Navigator-ru/${BuildConfig.VERSION_NAME} (Android navigator; +https://github.com/Makc960/Rust-plugins)"
        private const val RYADOM = "com.makc.ryadom"
    }
}
