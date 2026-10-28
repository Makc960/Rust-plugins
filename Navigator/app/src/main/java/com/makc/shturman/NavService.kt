package com.makc.shturman

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app alive while driving with the screen off or another app in front: Android then goes
 * on sending positions, and the page goes on speaking. Its notification shows the next manoeuvre
 * and has a button to end the drive.
 */
class NavService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = build(this, intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.nav_running), intent?.getStringExtra(EXTRA_TEXT) ?: "")
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, ID, notification, type)
            running = true
        } catch (e: Exception) {
            // Without location permission Android refuses; the drive goes on in front only.
            Log.w(MainActivity.TAG, "no background guidance", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val ID = 7
        private const val CHANNEL = "navigation"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        const val ACTION_END = "com.makc.shturman.END_DRIVE"

        @Volatile
        private var running = false

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, NavService::class.java))
            } catch (e: Exception) {
                Log.w(MainActivity.TAG, "could not start background guidance", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NavService::class.java))
            running = false
        }

        /** New words for the notification, as the drive goes on. */
        fun update(context: Context, title: String, text: String) {
            if (!running) return
            try {
                context.getSystemService(NotificationManager::class.java)?.notify(ID, build(context, title, text))
            } catch (e: Exception) {
                Log.w(MainActivity.TAG, "could not update the notification", e)
            }
        }

        private fun build(context: Context, title: String, text: String): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL) == null) {
                val channel = NotificationChannel(CHANNEL, context.getString(R.string.nav_channel), NotificationManager.IMPORTANCE_LOW)
                channel.setShowBadge(false)
                manager?.createNotificationChannel(channel)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags,
            )
            val end = PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java).setAction(ACTION_END).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .addAction(0, context.getString(R.string.nav_end), end)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
