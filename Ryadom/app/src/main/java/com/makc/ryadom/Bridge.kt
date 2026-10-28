package com.makc.ryadom

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.location.LocationManagerCompat
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * What the page can ask of the phone, as window.RyadomNative: position, network, the account,
 * sharing in the background, permissions. Calls come on WebView's own thread; answers go back
 * through window.__ryadom (see web/src/js/platform.js).
 */
class Bridge(private val activity: MainActivity) {
    private val locations = activity.getSystemService(LocationManager::class.java)
    private val network = Executors.newFixedThreadPool(3)

    /** The page wants positions while the app is in sight. */
    private var wanted = false
    private var listening = false
    private var lastGps = 0L
    private var askedBefore = false
    private var askedNotifications = false

    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) {
            listen()
            ShareService.start(activity)
        } else {
            activity.js("locationStatus('denied')")
        }
        activity.js("resume()")
    }

    private val background = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        activity.js("resume()")
    }

    private val notifications = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        activity.js("resume()")
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

    // ---- Position, for the map while the app is open ----

    @JavascriptInterface
    fun startLocation() = activity.runOnUiThread {
        wanted = true
        if (Sharing.hasLocation(activity)) listen()
        else activity.js("locationStatus('denied')")
    }

    @JavascriptInterface
    fun stopLocation() = activity.runOnUiThread {
        wanted = false
        stopListening()
    }

    /** Asks for the permission; once refused for good, only the app's settings page can allow it. */
    @JavascriptInterface
    fun requestLocation() = activity.runOnUiThread {
        wanted = true
        if (Sharing.hasLocation(activity)) {
            listen()
        } else if (activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) || !askedBefore) {
            askedBefore = true
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            openAppSettings()
        }
    }

    @JavascriptInterface
    fun openLocationSettings() = activity.runOnUiThread {
        try {
            activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (e: Exception) {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    @SuppressLint("MissingPermission")
    private fun listen() {
        if (!wanted || !Sharing.hasLocation(activity)) return
        if (!LocationManagerCompat.isLocationEnabled(locations)) activity.js("locationStatus('off')")
        if (listening) return
        try {
            locations.getProviders(true).mapNotNull { locations.getLastKnownLocation(it) }
                .filter { System.currentTimeMillis() - it.time < 120_000 }
                .minByOrNull { it.accuracy }
                ?.let { onLocation(it) }
            if (LocationManager.GPS_PROVIDER in locations.allProviders) {
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 5f, listener, Looper.getMainLooper())
            }
            if (LocationManager.NETWORK_PROVIDER in locations.allProviders) {
                locations.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10_000L, 0f, listener, Looper.getMainLooper())
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
        // While satellites answer, the rougher network positions would only make the dot jump.
        else if (now - lastGps < 15_000) return
        val fix = JSONObject()
            .put("lon", l.longitude)
            .put("lat", l.latitude)
            .put("accuracy", if (l.hasAccuracy()) l.accuracy.toDouble() else 50.0)
            .put("speed", if (l.hasSpeed()) l.speed.toDouble() else JSONObject.NULL)
            .put("heading", if (l.hasBearing() && l.hasSpeed() && l.speed > 0.5f) l.bearing.toDouble() else JSONObject.NULL)
            .put("time", l.time)
        activity.js("location($fix)")
    }

    /** The activity coming into sight or leaving it: the map's own positions stop out of sight. */
    fun onVisible(shown: Boolean) {
        if (shown) {
            if (wanted && Sharing.hasLocation(activity)) listen()
            else if (wanted) activity.js("locationStatus('denied')")
            // Android may have stopped the service; it starts again with the app.
            ShareService.start(activity)
        } else {
            stopListening()
        }
    }

    // ---- Network ----

    /** Sends a request off the main thread and hands the page {status, text}, or status -1 on no answer. */
    @JavascriptInterface
    fun http(id: Int, method: String, url: String, body: String, timeout: Int, headers: String) {
        network.execute {
            val map = mutableMapOf<String, String>()
            try {
                val json = JSONObject(headers.ifEmpty { "{}" })
                for (key in json.keys()) map[key] = json.getString(key)
            } catch (e: Exception) {
                // No headers then.
            }
            val (status, text) = Sharing.request(method, url, map, body, timeout)
            activity.js("http($id, $status, ${JSONObject.quote(text)})")
        }
    }

    // ---- The account and sharing ----

    @JavascriptInterface
    fun account(): String = Sharing.account(activity)

    @JavascriptInterface
    fun saveAccount(raw: String) {
        Sharing.saveAccount(activity, raw)
        if (raw.isEmpty()) activity.runOnUiThread { ShareService.stop(activity) }
    }

    /** Sharing on: the service sends positions with the app closed too. */
    @JavascriptInterface
    fun startSharing(server: String, token: String) = activity.runOnUiThread {
        Sharing.turnOn(activity, server, token)
        ShareService.start(activity)
        // Android 13 and later show the service's notification only once allowed: asked the first time.
        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications && Sharing.hasLocation(activity) &&
            !Sharing.granted(activity, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            askedNotifications = true
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @JavascriptInterface
    fun stopSharing() = activity.runOnUiThread {
        Sharing.turnOff(activity)
        ShareService.stop(activity)
    }

    /** What lets sharing go on in the background: {always, notifications, unrestricted}. */
    @JavascriptInterface
    fun background(): String {
        val power = activity.getSystemService(PowerManager::class.java)
        return JSONObject()
            .put("always", Sharing.hasBackgroundLocation(activity))
            .put("notifications", NotificationManagerCompat.from(activity).areNotificationsEnabled())
            .put("unrestricted", power?.isIgnoringBatteryOptimizations(activity.packageName) ?: true)
            .toString()
    }

    /** Location «always». Android 11 and later answer with the app's settings page, where it is chosen. */
    @JavascriptInterface
    fun requestBackground() = activity.runOnUiThread {
        when {
            !Sharing.hasLocation(activity) -> requestLocation()
            Build.VERSION.SDK_INT >= 29 && !Sharing.hasBackgroundLocation(activity) ->
                try {
                    background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } catch (e: Exception) {
                    openAppSettings()
                }
            else -> activity.js("resume()")
        }
    }

    @JavascriptInterface
    fun requestNotifications() = activity.runOnUiThread {
        if (Build.VERSION.SDK_INT >= 33 && !Sharing.granted(activity, Manifest.permission.POST_NOTIFICATIONS) &&
            activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS).let { it || !askedNotifications }
        ) {
            askedNotifications = true
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            try {
                activity.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName))
            } catch (e: Exception) {
                openAppSettings()
            }
        }
    }

    /** Asks to let the app run with no battery limits, so Android does not stop sharing. */
    @SuppressLint("BatteryLife")
    @JavascriptInterface
    fun openBatterySettings() = activity.runOnUiThread {
        try {
            activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")))
        } catch (e: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                openAppSettings()
            }
        }
    }

    @JavascriptInterface
    fun battery(): String = Sharing.battery(activity)?.toString() ?: ""

    private fun openAppSettings() {
        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
    }

    // ---- Everything else ----

    @JavascriptInterface
    fun share(text: String) = activity.runOnUiThread {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        activity.startActivity(Intent.createChooser(send, null))
    }

    @JavascriptInterface
    fun copy(text: String) = activity.runOnUiThread {
        activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Рядом", text))
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
        .put("server", BuildConfig.SERVER)
        .toString()

    fun release() {
        stopListening()
        network.shutdownNow()
    }
}
