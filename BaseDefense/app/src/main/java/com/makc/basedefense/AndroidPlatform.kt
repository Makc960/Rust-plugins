package com.makc.basedefense

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.makc.basedefense.engine.Payment
import com.makc.basedefense.engine.Platform
import com.makc.basedefense.engine.Product
import com.makc.basedefense.engine.Purchases
import com.makc.basedefense.engine.Reminder
import com.makc.basedefense.engine.Sfx
import com.makc.basedefense.engine.Shop
import com.makc.basedefense.engine.Track
import java.io.File
import java.time.LocalDate
import java.util.Locale
import java.util.Random
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Sound through SoundPool and MediaPlayer from the Ogg files in assets/audio, vibration,
 * saves, the date, the [Store], the [Notices] and the [Adverts].
 * [askNotifications] asks the player to allow notifications and hears the answer;
 * [textWindow] shows a window to type a line in.
 */
class AndroidPlatform(
    context: Context,
    private val store: Store,
    private val notices: Notices,
    private val adverts: Adverts,
    private val askNotifications: ((Boolean) -> Unit) -> Unit,
    private val textWindow: (title: String, hint: String, ok: String, done: (String?) -> Unit) -> Unit,
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
    private val pool = SoundPool.Builder().setMaxStreams(16).setAudioAttributes(attributes).build()

    /** The SoundPool ids of every sound's takes, filled in as they load. */
    private val takes = AtomicReferenceArray<IntArray>(Sfx.entries.size)
    private val files = ArrayList<AssetFileDescriptor>()
    private val random = Random()

    // Music: the track asked for and the one playing.
    private var wanted: Track? = null
    private var volume = 0f
    private var playing: Track? = null
    private var player: MediaPlayer? = null
    private var released = false

    init {
        Thread({ loadSounds() }, "audio").apply { isDaemon = true }.start()
    }

    private fun loadSounds() {
        // Earlier versions synthesized their sounds into these folders.
        File(app.cacheDir, "sfx").deleteRecursively()
        File(app.cacheDir, "audio").deleteRecursively()
        val names = app.assets.list(AUDIO)?.toSet() ?: emptySet()
        for (s in Sfx.entries) {
            val ids = ArrayList<Int>()
            var take = 1
            while (s.file(take) in names) {
                try {
                    val fd = app.assets.openFd("$AUDIO/${s.file(take)}")
                    synchronized(files) { files += fd }
                    ids += pool.load(fd, 1)
                } catch (e: Exception) {
                    android.util.Log.w("BaseDefense", "Sound ${s.file(take)} not loaded", e)
                }
                take++
            }
            takes.set(s.ordinal, ids.toIntArray())
        }
    }

    override fun play(sfx: Sfx, volume: Float) {
        val ids = takes.get(sfx.ordinal) ?: return
        if (ids.isEmpty()) return
        val id = ids[random.nextInt(ids.size)]
        // A shot or a blast a little higher or lower each time; fanfares a step ahead of the din.
        val rate = if (sfx.varied) 0.93f + random.nextFloat() * 0.14f else 1f
        pool.play(id, volume, volume, if (sfx.varied) 1 else 2, 0, rate)
    }

    override fun music(track: Track?, volume: Float) {
        wanted = track
        this.volume = volume
        applyMusic()
    }

    private fun applyMusic() {
        if (released) return
        val t = wanted
        if (t == null) {
            stopMusic()
            return
        }
        if (t != playing) {
            stopMusic()
            try {
                val p = MediaPlayer()
                p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                app.assets.openFd("$AUDIO/${t.file}").use { p.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
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

    override fun askText(title: String, hint: String, ok: String, done: (String?) -> Unit) = textWindow(title, hint, ok, done)

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
        synchronized(files) {
            for (fd in files) fd.close()
            files.clear()
        }
    }

    private companion object {
        /** Where the sounds and the music lie in the app's assets. */
        const val AUDIO = "audio"
    }
}
