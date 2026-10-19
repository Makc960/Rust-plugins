package com.makc.basedefense

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.makc.basedefense.engine.Music
import com.makc.basedefense.engine.Payment
import com.makc.basedefense.engine.Platform
import com.makc.basedefense.engine.Product
import com.makc.basedefense.engine.Purchases
import com.makc.basedefense.engine.Reminder
import com.makc.basedefense.engine.Sfx
import com.makc.basedefense.engine.Shop
import com.makc.basedefense.engine.Synth
import com.makc.basedefense.engine.Track
import java.io.File
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * Sound through SoundPool and MediaPlayer (effects and music are synthesized once
 * into WAV files), vibration, saves, the date, the [Store], the [Notices] and the [Adverts].
 * [askNotifications] asks the player to allow notifications and hears the answer.
 */
class AndroidPlatform(
    context: Context,
    private val store: Store,
    private val notices: Notices,
    private val adverts: Adverts,
    private val askNotifications: ((Boolean) -> Unit) -> Unit,
) : Platform {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("base_defense", Context.MODE_PRIVATE)
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        app.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        app.getSystemService(Vibrator::class.java)
    }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val pool = SoundPool.Builder().setMaxStreams(12).setAudioAttributes(attributes).build()
    private val ids = AtomicIntegerArray(Sfx.entries.size)
    private val main = Handler(Looper.getMainLooper())

    // Music: the track asked for, the one playing, and the files ready to play.
    private var wanted: Track? = null
    private var volume = 0f
    private var playing: Track? = null
    private var player: MediaPlayer? = null
    private val ready = HashSet<Track>()
    private var released = false

    init {
        Thread({ prepareAudio() }, "audio").apply { isDaemon = true }.start()
    }

    private fun prepareAudio() {
        // The first version kept its sounds here.
        File(app.cacheDir, "sfx").deleteRecursively()
        val dir = File(app.cacheDir, "audio").apply { mkdirs() }
        for (s in Sfx.entries) {
            try {
                val file = File(dir, "${s.name.lowercase()}_$AUDIO_VERSION.wav")
                if (!file.exists()) write(file, Synth.wav(Synth.render(s)))
                ids.set(s.ordinal, pool.load(file.path, 1))
            } catch (e: Exception) {
                android.util.Log.w("BaseDefense", "Sound $s not loaded", e)
            }
        }
        // The menu theme first: it is the one heard right away.
        for (t in listOf(Track.MENU, Track.BATTLE, Track.BOSS)) {
            try {
                val file = musicFile(t)
                if (!file.exists()) write(file, Synth.wav(Music.render(t)))
                main.post {
                    ready += t
                    applyMusic()
                }
            } catch (e: Exception) {
                android.util.Log.w("BaseDefense", "Music $t not made", e)
            }
        }
    }

    /** Writes through a temporary file, so a half-written file is never used. */
    private fun write(file: File, bytes: ByteArray) {
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(file)
    }

    private fun musicFile(t: Track) = File(File(app.cacheDir, "audio"), "music_${t.name.lowercase()}_$AUDIO_VERSION.wav")

    override fun play(sfx: Sfx, volume: Float) {
        val id = ids.get(sfx.ordinal)
        if (id != 0) pool.play(id, volume, volume, 1, 0, 1f)
    }

    override fun music(track: Track?, volume: Float) {
        wanted = track
        this.volume = volume
        applyMusic()
    }

    private fun applyMusic() {
        if (released) return
        val t = wanted
        if (t == null || t !in ready) {
            if (t == null) stopMusic()
            return
        }
        if (t != playing) {
            stopMusic()
            try {
                val p = MediaPlayer()
                p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                p.setDataSource(musicFile(t).path)
                p.isLooping = true
                p.prepare()
                p.start()
                player = p
                playing = t
            } catch (e: Exception) {
                android.util.Log.w("BaseDefense", "Music $t did not start", e)
                return
            }
        }
        player?.setVolume(volume, volume)
    }

    private fun stopMusic() {
        player?.let {
            try {
                it.stop()
            } catch (e: IllegalStateException) {
                // Already stopped.
            }
            it.release()
        }
        player = null
        playing = null
    }

    override fun vibrate(ms: Int) {
        vibrator?.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    override fun today(): Int = LocalDate.now().toEpochDay().toInt()

    override fun systemLanguage(): String = Locale.getDefault().language

    override val supportUrl: String? get() = BuildConfig.SUPPORT_URL.ifBlank { null }

    override val shop: Shop get() = store.shop

    override fun buy(product: Product, done: (Payment, Int) -> Unit) = store.buy(product, done)

    override fun checkPurchases(done: (Purchases?) -> Unit) = store.check(done)

    override fun askReview() = store.askReview()

    override fun checkUpdate(done: (Boolean) -> Unit) = store.checkUpdate(done)

    override fun startUpdate() = store.startUpdate()

    override val remindersAllowed: Boolean get() = notices.allowed

    override fun allowReminders(done: (Boolean) -> Unit) = askNotifications(done)

    override fun planReminders(reminders: List<Reminder>) = notices.plan(reminders)

    override val ads: Boolean get() = adverts.enabled

    override val adReady: Boolean get() = adverts.ready

    override fun showAd(done: (Boolean) -> Unit) = adverts.show(done)

    override fun openUrl(url: String) {
        try {
            app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // No browser on the phone: nothing to open the page with.
        }
    }

    fun release() {
        released = true
        stopMusic()
        pool.release()
    }

    private companion object {
        /** Bump when Synth or Music change, so old cached files are not reused. */
        const val AUDIO_VERSION = 2
    }
}
