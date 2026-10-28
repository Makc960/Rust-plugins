package com.makc.ryadom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After the phone restarts (or the app updates), sharing goes on by itself, where Android allows a
 * location service to start with the app closed: that is, with location «always».
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Sharing.hasBackgroundLocation(context)) ShareService.start(context)
    }
}
