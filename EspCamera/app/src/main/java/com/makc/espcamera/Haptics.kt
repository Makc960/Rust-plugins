package com.makc.espcamera

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** A short buzz, and a beep if asked, when the crosshair locks onto a target. */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }
    private var tone: ToneGenerator? = null

    fun lock(alert: Alert) {
        if (alert == Alert.OFF) return
        vibrator?.vibrate(VibrationEffect.createOneShot(28, VibrationEffect.DEFAULT_AMPLITUDE))
        if (alert == Alert.SOUND) beep()
    }

    private fun beep() {
        val generator = tone ?: try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 70).also { tone = it }
        } catch (e: RuntimeException) {
            null
        }
        generator?.startTone(ToneGenerator.TONE_PROP_BEEP, 70)
    }

    fun release() {
        tone?.release()
        tone = null
    }
}
