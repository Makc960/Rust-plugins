package com.makc.basedefense.engine

/**
 * The sound effects. Each is one or more takes in the app's audio files (sfx_shoot_1.ogg,
 * sfx_shoot_2.ogg…), one picked at random; a [varied] one also plays a little higher or
 * lower each time, so a long fight does not drone. Fanfares and the interface stay as made.
 */
enum class Sfx(val varied: Boolean = true) {
    SHOOT, SHOTGUN, FLAME, ROCKET, MISSILE, ZAP, BOOM, BASE_HIT, COIN,
    BUILD, UPGRADE(false), SELL, WAVE(false), WIN(false), LOSE(false), CLICK(false), ERROR(false),
    SNIPER, MINE, PLANE(false), FREEZE(false), REPAIR, SIREN(false), JAM, CLEAR, ACHIEVEMENT(false), RAIL,
    ;

    /** The name of its [take]'s file, from 1. */
    fun file(take: Int): String = "sfx_${name.lowercase()}_$take.ogg"
}

/** The music, a looping file each (music_menu.ogg…). */
enum class Track {
    MENU, BATTLE, BOSS;

    val file: String get() = "music_${name.lowercase()}.ogg"
}

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

    /**
     * The code this phone's purchases are kept under on the game's own shop server, for the player
     * to type on a new phone; null when the store keeps purchases with the player's account. With
     * such a code, VIP by the month is paid for 30 days at a time and does not renew by itself.
     */
    val purchaseCode: String? get() = null

    /**
     * Makes [code], typed from another phone, this phone's purchase code, the purchases under both
     * kept together; [done] hears what they are, or null when the code is wrong or the shop is out of reach.
     */
    fun usePurchaseCode(code: String, done: (Purchases?) -> Unit) = done(null)

    /** Puts [text] on the phone's clipboard. */
    fun copyText(text: String) = Unit

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

    /** Whether this build plays rewarded adverts at all. */
    val ads: Boolean get() = false

    /** A rewarded advert is loaded and can play right away. */
    val adReady: Boolean get() = false

    /** Plays a rewarded advert; [done] hears true when the player watched it to the reward. */
    fun showAd(done: (Boolean) -> Unit) = done(false)

    /** Asks the player to type a line, like a promo code; [done] hears it, or null when they cancel. */
    fun askText(title: String, hint: String, ok: String, done: (String?) -> Unit) = done(null)
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
    val passDays = ArrayList<Int>()

    /** False makes the store unreachable. */
    var storeAnswers = true
    override var purchaseCode: String? = null

    /** What the shop keeps under each purchase code; a code not here is a wrong one. */
    val codes = HashMap<String, Purchases>()
    var copied: String? = null
    var reviewsAsked = 0
    var updateOut = false
    var updatesStarted = 0
    override var remindersAllowed = true

    /** What the phone answers when asked for notifications. */
    var allowsReminders = true

    /** What the player types when asked; null cancels. */
    var typed: String? = null

    /** Adverts: whether the build has them, one is loaded, and the player watches it through. */
    override var ads = false
    override var adReady = true
    var adWatched = true
    var adsShown = 0
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
        when {
            product.subscription -> vipPaidUntil = day + 30
            product.consumable -> passDays += day
            else -> owned += product
        }
        done(payment, if (product.subscription) day + 30 else 0)
    }

    override fun checkPurchases(done: (Purchases?) -> Unit) = done(if (storeAnswers) Purchases(vipPaidUntil, owned.toSet(), passDays.toList()) else null)

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

    override fun showAd(done: (Boolean) -> Unit) {
        adsShown++
        done(adWatched)
    }

    override fun askText(title: String, hint: String, ok: String, done: (String?) -> Unit) = done(typed)

    override fun usePurchaseCode(code: String, done: (Purchases?) -> Unit) {
        val found = codes[code] ?: return done(null)
        purchaseCode = code
        done(found)
    }

    override fun copyText(text: String) {
        copied = text
    }
}
