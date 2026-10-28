package com.makc.shturman

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews

/**
 * A row on the home screen: search, home, work and the parked car, each a tap away. The buttons
 * open the app with its own links (shturman://home and the like), as the icon's shortcuts do.
 */
class NavWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget)
        val buttons = listOf(R.id.widget_search to "search", R.id.widget_home to "home", R.id.widget_work to "work", R.id.widget_car to "car")
        buttons.forEachIndexed { i, (id, link) ->
            val open = Intent(Intent.ACTION_VIEW, Uri.parse("shturman://$link"), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            views.setOnClickPendingIntent(id, PendingIntent.getActivity(context, 100 + i, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        manager.updateAppWidget(ids, views)
    }
}
