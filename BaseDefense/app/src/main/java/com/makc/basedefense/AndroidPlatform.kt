package com.makc.basedefense

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.makc.basedefense.engine.Platform
import com.makc.basedefense.engine.Sfx
import com.makc.basedefense.engine.Synth
import java.io.File
import java.util.concurrent.atomic.AtomicIntegerArray

/** Sound through SoundPool (effects are synthesized once into WAV files), vibration and saves. */
class AndroidPlatform(context: Context) : Platform {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("base_defense", Context.MODE_PRIVATE)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        app.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        app.getSystemService(Vibrator::class.java)
    }
    private val pool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val ids = AtomicIntegerArray(Sfx.entries.size)

    init {
        Thread({ loadSounds() }, "sfx").apply { isDaemon = true }.start()
    }

    private fun loadSounds() {
        val dir = File(app.cacheDir, "sfx").apply { mkdirs() }
        for (s in Sfx.entries) {
            try {
                val file = File(dir, "${s.name.lowercase()}_$SOUND_VERSION.wav")
                if (!file.exists()) file.writeBytes(Synth.wav(Synth.render(s)))
                ids.set(s.ordinal, pool.load(file.path, 1))
            } catch (e: Exception) {
                android.util.Log.w("BaseDefense", "Sound $s not loaded", e)
            }
        }
    }

    override fun play(sfx: Sfx, volume: Float) {
        val id = ids.get(sfx.ordinal)
        if (id != 0) pool.play(id, volume, volume, 1, 0, 1f)
    }

    override fun vibrate(ms: Int) {
        vibrator?.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun release() {
        pool.release()
    }

    private companion object {
        /** Bump when Synth changes, so old cached files are not reused. */
        const val SOUND_VERSION = 1
    }
}
