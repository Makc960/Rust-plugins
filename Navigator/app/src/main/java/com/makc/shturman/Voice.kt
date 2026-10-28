package com.makc.shturman

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * The voice that reads the directions: the phone's speech engine in Russian. While it speaks,
 * music and other sounds are turned down, as for any navigator.
 * [onStatus] hears "missing" when the phone has no Russian voice.
 */
class Voice(context: Context, private val onStatus: (String) -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private var ready = false
    private var waiting: String? = null
    private var count = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> onReady(status) }

    private fun onReady(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            onStatus("missing")
            return
        }
        val result = tts.setLanguage(Locale.forLanguageTag("ru-RU"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) onStatus("missing")
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}

            override fun onDone(id: String) = release(id)

            @Deprecated("Deprecated in Java")
            override fun onError(id: String) = release(id)

            override fun onError(id: String, errorCode: Int) = release(id)
        })
        ready = true
        waiting?.let { speak(it) }
        waiting = null
    }

    fun speak(text: String) {
        if (!ready) {
            waiting = text
            return
        }
        try {
            audio.requestAudioFocus(focus)
            val id = "say${++count}"
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        } catch (e: Exception) {
            Log.w(MainActivity.TAG, "could not speak", e)
        }
    }

    fun stop() {
        if (ready) tts.stop()
        audio.abandonAudioFocusRequest(focus)
    }

    /** The last phrase done: the music comes back up. */
    private fun release(id: String) {
        if (id == "say$count") audio.abandonAudioFocusRequest(focus)
    }

    fun release() {
        stop()
        tts.shutdown()
    }
}
