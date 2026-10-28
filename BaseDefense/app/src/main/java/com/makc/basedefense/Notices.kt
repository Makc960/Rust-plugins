package com.makc.basedefense

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.makc.basedefense.engine.Reminder
import java.time.LocalDate
import java.time.ZoneId

/** The game's reminders as Android notifications, posted by alarms while the game is closed. */
class Notices(context: Context) {
    private val app = context.applicationContext
    private val alarms = app.getSystemService(AlarmManager::class.java)

    /** False until the player allows notifications (Android 13 and later), or after turning them off. */
    val allowed: Boolean get() = app.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

    /** Puts [reminders] in place of the ones planned before; those already past are dropped. */
    fun plan(reminders: List<Reminder>) {
        val now = System.currentTimeMillis()
        for (slot in 0 until SLOTS) {
            val r = reminders.getOrNull(slot)
            val intent = Intent(app, NoticeReceiver::class.java)
            if (r != null) intent.putExtra(TITLE, r.title).putExtra(TEXT, r.text)
            val pending = PendingIntent.getBroadcast(app, slot, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val at = r?.let { LocalDate.ofEpochDay(it.day.toLong()).atStartOfDay(ZoneId.systemDefault()).plusMinutes(it.minute.toLong()).toInstant().toEpochMilli() }
            if (at == null || at <= now) alarms?.cancel(pending) else alarms?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    companion object {
        const val TITLE = "title"
        const val TEXT = "text"
        const val CHANNEL = "reminders"

        /** The most reminders planned at once. */
        const val SLOTS = 4
    }
}

/** Posts one reminder; a tap on it opens the game. */
class NoticeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(Notices.TITLE) ?: return
        val text = intent.getStringExtra(Notices.TEXT) ?: ""
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(Notices.CHANNEL, context.getString(R.string.reminders_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notice = Notification.Builder(context, Notices.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(PLATE)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTICE_ID, notice)
    }

    private companion object {
        /** One at a time: a new reminder replaces the last. */
        const val NOTICE_ID = 1
        const val PLATE = 0xFFF2B634.toInt()
    }
}
