package com.makc.basedefense.engine

enum class Sfx {
    SHOOT, SHOTGUN, FLAME, ROCKET, MISSILE, ZAP, BOOM, BASE_HIT, COIN,
    BUILD, UPGRADE, SELL, WAVE, WIN, LOSE, CLICK, ERROR,
    SNIPER, MINE, PLANE, FREEZE, REPAIR, SIREN, JAM, CLEAR, ACHIEVEMENT,
}

enum class Track { MENU, BATTLE, BOSS }

enum class Touch { DOWN, MOVE, UP, CANCEL }

/** Where this build lets players buy VIP. */
enum class VipShop {
    /** Nowhere, and the VIP screen stays hidden: Google Play allows no outside payments. */
    NONE,

    /** In the game's RuStore version; this build only shows what VIP gives. */
    ELSEWHERE,

    /** Right here, through RuStore. */
    HERE,
}

/** How buying VIP ended. */
enum class Payment { PAID, CANCELLED, FAILED }

/** What the game needs from the phone: sound, music, vibration, saves and the date. */
interface Platform {
    fun play(sfx: Sfx, volume: Float)

    /** Loops [track] at [volume] from 0 to 1; null stops the music. */
    fun music(track: Track?, volume: Float)
    fun vibrate(ms: Int)
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getString(key: String): String?

    /** Null removes the value. */
    fun putString(key: String, value: String?)

    /** Days since 1970-01-01 in the phone's time zone. */
    fun today(): Int

    /** Two-letter code of the phone's language, like "ru" or "en". */
    fun systemLanguage(): String

    /** A store with online achievements can mirror ours here. */
    fun achievement(id: String) = Unit

    /** Where players can support the author; null hides the button (Google Play allows no such links). */
    val supportUrl: String? get() = null

    /** Opens [url] in the browser. */
    fun openUrl(url: String) = Unit

    val vipShop: VipShop get() = VipShop.NONE

    /** Starts buying VIP; [done] gets how it ended and, when paid, the last paid day (days since 1970, 0 if unknown). */
    fun buyVip(done: (Payment, Int) -> Unit) = Unit

    /** Asks the store about VIP; [done] gets the last paid day, or null when nothing is paid or the store cannot tell. */
    fun checkVip(done: (Int?) -> Unit) = Unit
}

/** No sound, memory-only saves: the tests run on this. */
class SilentPlatform(var day: Int = 20_000, var language: String = "ru") : Platform {
    private val ints = HashMap<String, Int>()
    private val strings = HashMap<String, String>()
    val played = ArrayList<Sfx>()
    val vibrations = ArrayList<Int>()
    val achievements = ArrayList<String>()
    val opened = ArrayList<String>()
    override var supportUrl: String? = null
    override var vipShop = VipShop.NONE

    /** How the next VIP purchase ends, and the last day the store says VIP is paid for. */
    var payment = Payment.PAID
    var vipPaidUntil: Int? = null
    var track: Track? = null
        private set
    var musicVolume = 0f
        private set

    override fun play(sfx: Sfx, volume: Float) {
        played += sfx
    }

    override fun music(track: Track?, volume: Float) {
        this.track = track
        musicVolume = volume
    }

    override fun vibrate(ms: Int) {
        vibrations += ms
    }

    override fun getInt(key: String, default: Int): Int = ints[key] ?: default

    override fun putInt(key: String, value: Int) {
        ints[key] = value
    }

    override fun getString(key: String): String? = strings[key]

    override fun putString(key: String, value: String?) {
        if (value == null) strings.remove(key) else strings[key] = value
    }

    override fun today(): Int = day

    override fun systemLanguage(): String = language

    override fun achievement(id: String) {
        achievements += id
    }

    override fun openUrl(url: String) {
        opened += url
    }

    override fun buyVip(done: (Payment, Int) -> Unit) {
        if (payment == Payment.PAID) vipPaidUntil = day + 30
        done(payment, if (payment == Payment.PAID) day + 30 else 0)
    }

    override fun checkVip(done: (Int?) -> Unit) = done(vipPaidUntil)
}
