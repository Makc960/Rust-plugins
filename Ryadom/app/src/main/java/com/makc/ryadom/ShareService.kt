package com.makc.ryadom

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Sends this phone's position to the server while sharing is on, with the app closed and the
 * screen off: friends see where it is. It asks Android for positions sparingly (about once a minute,
 * sooner when walking or driving, from the phone's own blend of satellites, Wi-Fi and cell towers
 * where it has one), and tells about new friend requests with a notification.
 */
class ShareService : Service() {
    private lateinit var locations: LocationManager
    private val sender = Executors.newSingleThreadExecutor()
    private var listening = false
    private var sentAt = 0L
    private var sentWhere: Location? = null

    private val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) = onLocation(location)

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String, status: Int, extras: Bundle?) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locations = getSystemService(LocationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            hide()
            return START_NOT_STICKY
        }
        if (Sharing.target(this) == null || !Sharing.hasLocation(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, ID, notification(this), type)
        } catch (e: Exception) {
            // Android refuses a location service started from the background without «always».
            Log.w(TAG, "cannot share in the background", e)
            stopSelf()
            return START_NOT_STICKY
        }
        listen()
        return START_STICKY
    }

    override fun onDestroy() {
        if (listening) LocationManagerCompat.removeUpdates(locations, listener)
        listening = false
        sender.shutdown()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun listen() {
        if (listening) return
        try {
            val main = ContextCompat.getMainExecutor(this)
            if (Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in locations.allProviders) {
                val request = LocationRequestCompat.Builder(INTERVAL)
                    .setQuality(LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY)
                    .setMinUpdateIntervalMillis(MIN_INTERVAL)
                    .build()
                LocationManagerCompat.requestLocationUpdates(locations, LocationManager.FUSED_PROVIDER, request, main, listener)
            } else {
                if (LocationManager.NETWORK_PROVIDER in locations.allProviders) {
                    val request = LocationRequestCompat.Builder(INTERVAL).setQuality(LocationRequestCompat.QUALITY_LOW_POWER).build()
                    LocationManagerCompat.requestLocationUpdates(locations, LocationManager.NETWORK_PROVIDER, request, main, listener)
                }
                if (LocationManager.GPS_PROVIDER in locations.allProviders) {
                    val request = LocationRequestCompat.Builder(INTERVAL / 2).setMinUpdateDistanceMeters(MOVED.toFloat()).build()
                    LocationManagerCompat.requestLocationUpdates(locations, LocationManager.GPS_PROVIDER, request, main, listener)
                }
            }
            listening = true
            // Something to send at once, if the phone knows where it was a moment ago.
            locations.getProviders(true).mapNotNull { locations.getLastKnownLocation(it) }
                .filter { System.currentTimeMillis() - it.time < 5 * 60_000 }
                .minByOrNull { it.accuracy }
                ?.let { onLocation(it) }
        } catch (e: SecurityException) {
            stopSelf()
        }
    }

    private fun onLocation(l: Location) {
        val now = System.currentTimeMillis()
        val moved = sentWhere?.distanceTo(l) ?: Float.MAX_VALUE
        if (now - sentAt < INTERVAL - 5000 && moved < MOVED) return
        // A rough network position right after a good one is no news.
        val last = sentWhere
        if (last != null && now - sentAt < INTERVAL && l.accuracy > last.accuracy * 3 && moved < l.accuracy) return
        sentAt = now
        sentWhere = l
        val fix = Sharing.fix(this, l).toString()
        sender.execute { send(fix) }
    }

    /** Sends one position; the answer has the friend requests, new ones become a notification. */
    private fun send(fix: String) {
        val (server, token) = Sharing.target(this) ?: return
        val (status, text) = Sharing.request("POST", "$server/api/location", mapOf("Authorization" to "Bearer $token"), fix)
        if (status == 401) {
            // The account is gone from the server.
            Sharing.turnOff(this)
            stopSelf()
            return
        }
        if (status !in 200..299) return
        try {
            val incoming = JSONObject(text).optJSONArray("incoming") ?: return
            val seen = Sharing.seenRequests(this)
            val now = mutableSetOf<String>()
            for (i in 0 until incoming.length()) {
                val person = incoming.getJSONObject(i)
                val id = person.optString("id")
                now += id
                if (id !in seen) notifyRequest(this, id, person.optString("name"))
            }
            Sharing.saveSeenRequests(this, now)
        } catch (e: Exception) {
            Log.w(TAG, "odd answer from the server", e)
        }
    }

    /** «Скрыть меня» in the notification: the server stops showing this phone, and the service ends. */
    private fun hide() {
        val target = Sharing.target(this)
        Sharing.turnOff(this)
        if (target != null) {
            val (server, token) = target
            sender.execute {
                Sharing.request("PATCH", "$server/api/me", mapOf("Authorization" to "Bearer $token"), """{"sharing":false}""")
            }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val TAG = "Ryadom"
        private const val ID = 11
        private const val SHARING_CHANNEL = "sharing"
        private const val REQUESTS_CHANNEL = "requests"
        private const val ACTION_HIDE = "com.makc.ryadom.HIDE"
        // About once a minute; sooner after moving this far.
        private const val INTERVAL = 60_000L
        private const val MIN_INTERVAL = 15_000L
        private const val MOVED = 30

        fun start(context: Context) {
            if (Sharing.target(context) == null || !Sharing.hasLocation(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, ShareService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "could not start sharing", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ShareService::class.java))
        }

        private fun channels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(SHARING_CHANNEL) == null) {
                val channel = NotificationChannel(SHARING_CHANNEL, context.getString(R.string.share_channel), NotificationManager.IMPORTANCE_LOW)
                channel.setShowBadge(false)
                manager.createNotificationChannel(channel)
            }
            if (manager.getNotificationChannel(REQUESTS_CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(REQUESTS_CHANNEL, context.getString(R.string.requests_channel), NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
        }

        private fun openApp(context: Context, code: Int): PendingIntent = PendingIntent.getActivity(
            context, code,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun notification(context: Context): Notification {
            channels(context)
            val hide = PendingIntent.getService(
                context, 1,
                Intent(context, ShareService::class.java).setAction(ACTION_HIDE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, SHARING_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.share_title))
                .setContentText(context.getString(R.string.share_text))
                .setContentIntent(openApp(context, 0))
                .addAction(0, context.getString(R.string.share_hide), hide)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        fun notifyRequest(context: Context, id: String, name: String) {
            channels(context)
            val notification = NotificationCompat.Builder(context, REQUESTS_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.request_title))
                .setContentText(context.getString(R.string.request_text, name))
                .setContentIntent(openApp(context, 2))
                .setAutoCancel(true)
                .build()
            try {
                context.getSystemService(NotificationManager::class.java)?.notify(id.hashCode(), notification)
            } catch (e: SecurityException) {
                // Notifications are off for the app.
            }
        }
    }
}
