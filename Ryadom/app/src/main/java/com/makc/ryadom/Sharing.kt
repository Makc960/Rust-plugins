package com.makc.ryadom

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the page and the background service share: the account, whether this phone's position goes
 * to friends, and the requests to the app's server.
 */
object Sharing {
    private const val PREFS = "ryadom"
    private const val ACCOUNT = "account"
    private const val SERVER = "share.server"
    private const val TOKEN = "share.token"
    private const val ON = "share.on"
    private const val SEEN = "requests.seen"

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The page's account as it saved it ({token, id} in JSON), or "". */
    fun account(context: Context): String = prefs(context).getString(ACCOUNT, "").orEmpty()

    fun saveAccount(context: Context, raw: String) {
        prefs(context).edit().apply {
            if (raw.isEmpty()) {
                remove(ACCOUNT)
                remove(TOKEN)
                putBoolean(ON, false)
                remove(SEEN)
            } else {
                putString(ACCOUNT, raw)
            }
        }.apply()
    }

    fun turnOn(context: Context, server: String, token: String) {
        prefs(context).edit().putString(SERVER, server).putString(TOKEN, token).putBoolean(ON, true).apply()
    }

    fun turnOff(context: Context) {
        prefs(context).edit().putBoolean(ON, false).apply()
    }

    /** Where to send positions and with what, while sharing is on; null when it is off. */
    fun target(context: Context): Pair<String, String>? {
        val p = prefs(context)
        val server = p.getString(SERVER, null)
        val token = p.getString(TOKEN, null)
        return if (p.getBoolean(ON, false) && !server.isNullOrEmpty() && !token.isNullOrEmpty()) server to token else null
    }

    /** The requests the phone has told about already, so each is told once. */
    fun seenRequests(context: Context): Set<String> = prefs(context).getStringSet(SEEN, emptySet()).orEmpty()

    fun saveSeenRequests(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(SEEN, ids).apply()
    }

    fun hasLocation(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Location «always»: before Android 10 every location permission is that. */
    fun hasBackgroundLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** {level, charging} of the battery, or null when the phone does not say. */
    fun battery(context: Context): JSONObject? {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return null
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level !in 0..100) return null
        return JSONObject().put("level", level).put("charging", manager.isCharging)
    }

    /** A position as the server takes it. */
    fun fix(context: Context, l: Location): JSONObject {
        val power = battery(context)
        return JSONObject()
            .put("lat", l.latitude)
            .put("lon", l.longitude)
            .put("accuracy", if (l.hasAccuracy()) l.accuracy.toDouble() else 50.0)
            .put("speed", if (l.hasSpeed()) l.speed.toDouble() else JSONObject.NULL)
            .put("heading", if (l.hasBearing() && l.hasSpeed() && l.speed > 0.5f) l.bearing.toDouble() else JSONObject.NULL)
            .put("battery", power?.optInt("level") ?: JSONObject.NULL)
            .put("charging", power?.optBoolean("charging") ?: false)
            .put("time", l.time)
    }

    /** One request to the server, off the main thread: {status, text}, status -1 when there was no answer. */
    fun request(method: String, url: String, headers: Map<String, String>, body: String, timeout: Int = 15000): Pair<Int, String> {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = timeout
                c.readTimeout = timeout
                c.setRequestProperty("User-Agent", USER_AGENT)
                c.setRequestProperty("Accept-Language", "ru")
                for ((k, v) in headers) c.setRequestProperty(k, v)
                if (body.isNotEmpty()) {
                    c.doOutput = true
                    if (headers.keys.none { it.equals("Content-Type", true) }) c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = c.responseCode
                val stream = if (status >= 400) c.errorStream else c.inputStream
                status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            -1 to e.javaClass.simpleName
        }
    }

    val USER_AGENT = "Ryadom/${BuildConfig.VERSION_NAME} (Android; +https://github.com/Makc960/Rust-plugins)"
}
