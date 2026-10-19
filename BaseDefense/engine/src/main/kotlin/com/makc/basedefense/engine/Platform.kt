package com.makc.basedefense.engine

enum class Sfx {
    SHOOT, SHOTGUN, FLAME, ROCKET, MISSILE, ZAP, BOOM, BASE_HIT, COIN,
    BUILD, UPGRADE, SELL, WAVE, WIN, LOSE, CLICK, ERROR,
    SNIPER, MINE, PLANE, FREEZE, REPAIR, SIREN, JAM, CLEAR, ACHIEVEMENT,
}

enum class Track { MENU, BATTLE, BOSS }

enum class Touch { DOWN, MOVE, UP, CANCEL }

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

    val shop: Shop get() = Shop.NONE

    /**
     * Starts buying [product]; [done] gets how it ended and, for the subscription,
     * the last paid day (days since 1970, 0 if unknown).
     */
    fun buy(product: Product, done: (Payment, Int) -> Unit) = Unit

    /** Asks the store what is paid for; [done] gets null when the store cannot tell. */
    fun checkPurchases(done: (Purchases?) -> Unit) = Unit

    /** Opens the store's own window for rating the game, where the store has one. */
    fun askReview() = Unit

    /** Asks the store whether a newer version is out; [done] hears true when it is. */
    fun checkUpdate(done: (Boolean) -> Unit) = Unit

    /** Installs the newer version through the store. */
    fun startUpdate() = Unit

    /** Whether the phone lets the game post notifications. */
    val remindersAllowed: Boolean get() = false

    /** Asks the phone to let the game post notifications; [done] hears whether it may. */
    fun allowReminders(done: (Boolean) -> Unit) = done(false)

    /** Puts [reminders] in place of every reminder planned before. */
    fun planReminders(reminders: List<Reminder>) = Unit
}

/** A notification for later: on [day] (days since 1970) at [minute] past midnight, phone time. */
class Reminder(val day: Int, val minute: Int, val title: String, val text: String)

/** No sound, memory-only saves: the tests run on this. */
class SilentPlatform(var day: Int = 20_000, var language: String = "ru") : Platform {
    private val ints = HashMap<String, Int>()
    private val strings = HashMap<String, String>()
    val played = ArrayList<Sfx>()
    val vibrations = ArrayList<Int>()
    val achievements = ArrayList<String>()
    val opened = ArrayList<String>()
    override var supportUrl: String? = null
    override var shop = Shop.NONE

    /** How the next purchase ends, and what the store says is paid for; null [vipPaidUntil] is no subscription. */
    var payment = Payment.PAID
    var vipPaidUntil: Int? = null
    val owned = HashSet<Product>()

    /** False makes the store unreachable. */
    var storeAnswers = true
    var reviewsAsked = 0
    var updateOut = false
    var updatesStarted = 0
    override var remindersAllowed = true

    /** What the phone answers when asked for notifications. */
    var allowsReminders = true
    var reminders: List<Reminder> = emptyList()
        private set
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

    override fun buy(product: Product, done: (Payment, Int) -> Unit) {
        if (payment != Payment.PAID) return done(payment, 0)
        if (product.subscription) vipPaidUntil = day + 30 else owned += product
        done(payment, if (product.subscription) day + 30 else 0)
    }

    override fun checkPurchases(done: (Purchases?) -> Unit) = done(if (storeAnswers) Purchases(vipPaidUntil, owned.toSet()) else null)

    override fun askReview() {
        reviewsAsked++
    }

    override fun checkUpdate(done: (Boolean) -> Unit) = done(updateOut)

    override fun startUpdate() {
        updatesStarted++
    }

    override fun allowReminders(done: (Boolean) -> Unit) {
        remindersAllowed = allowsReminders
        done(allowsReminders)
    }

    override fun planReminders(reminders: List<Reminder>) {
        this.reminders = reminders
    }
}
