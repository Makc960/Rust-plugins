package com.makc.basedefense.engine

import kotlin.math.abs
import kotlin.math.min

internal enum class Screen { MENU, LEVELS, PLAY, SETTINGS, PERKS, ACHIEVEMENTS, CODEX, DAILY, VIP, COMMANDERS, INTRO, WARDROBE, PASS }

enum class Mode { CAMPAIGN, ENDLESS, DAILY, TUTORIAL, WEEKLY }

/** One battle and everything chosen for it. */
internal class Session(
    val mode: Mode,
    val level: LevelDef,
    val difficulty: Difficulty,
    val challenge: Boolean,
    val day: Int,
    val progress: Int,
    val perks: Perks,
    val vip: Boolean,
    val commander: CommanderType?,
    /** The commander's rank when the battle began. */
    val rank: Int,
    listener: WorldListener,
) {
    val mods: List<Mod> = when (mode) {
        Mode.DAILY -> Mod.forDay(day)
        Mode.WEEKLY -> Mod.forWeek(Weekly.week(day))
        else -> emptyList()
    }
    val rules = Rules(difficulty, if (challenge) level.challenge else null, perks, mods, progress, vip, commander, rank)
    val world = World(level, rules, listener)

    fun save(): String =
        "game=2\nmode=${mode.name}\nlevel=${level.id}\ndifficulty=${difficulty.name}\nchallenge=${if (challenge) 1 else 0}\n" +
            "day=$day\nprogress=$progress\nperks=${perks.encode()}\nvip=${if (vip) 1 else 0}\ncommander=${commander?.name ?: "-"}\nrank=$rank\n" +
            world.save()

    companion object {
        /** Rebuilds a battle from [save], or null when the text is not a usable save. */
        fun load(text: String, listener: WorldListener): Session? = try {
            val head = HashMap<String, String>()
            for (line in text.lineSequence()) {
                val eq = line.indexOf('=')
                if (eq > 0) head.putIfAbsent(line.substring(0, eq), line.substring(eq + 1))
            }
            if (head["game"] != "2") {
                null
            } else {
                val mode = Mode.valueOf(head.getValue("mode"))
                val progress = head.getValue("progress").toInt()
                val day = head.getValue("day").toInt()
                val level = when (mode) {
                    Mode.CAMPAIGN -> Levels.all.first { it.id == head.getValue("level").toInt() }
                    Mode.ENDLESS -> Levels.endless(progress)
                    Mode.DAILY -> Levels.daily(day, progress)
                    Mode.TUTORIAL -> Levels.tutorial()
                    Mode.WEEKLY -> Levels.weekly(Weekly.week(day), progress)
                }
                // Battles saved before commanders came along go on without one.
                val commander = CommanderType.entries.firstOrNull { it.name == head["commander"] }
                val s = Session(
                    mode, level, Difficulty.valueOf(head.getValue("difficulty")), head["challenge"] == "1", day, progress,
                    Perks.decode(head["perks"] ?: ""), head["vip"] == "1", commander, head["rank"]?.toIntOrNull() ?: 1, listener,
                )
                s.world.restore(text)
                if (s.world.over) null else s
            }
        } catch (e: RuntimeException) {
            null
        }
    }
}

/**
 * The whole game behind a few calls from the phone: [update], [render], [touch],
 * [pinch] and [pan]. Screens and saves live here; the battle itself is [World].
 * With [showIntro] it opens on the [Intro] instead of the menu.
 */
class Game(private val platform: Platform, showIntro: Boolean = false) {
    internal val profile = Profile(platform)
    internal val ui = Ui { s -> sound(s, if (s == Sfx.CLICK) 0.5f else 0.4f) }
    internal val renderer = Renderer()
    internal var clock = 0f
        private set
    internal var screen = if (showIntro) Screen.INTRO else Screen.MENU
        private set
    private var nextScreen: Screen? = null
    private var fade = 0f

    internal var session: Session? = null
    internal val play = PlayScreen(this)
    internal val menus = Menus(this)
    internal val intro = Intro(this)

    private val lastPlayed = FloatArray(Sfx.entries.size) { -10f }
    private var track: Track? = null
    private var trackVolume = -1f

    private class Toast(val text: String, var life: Float, val icon: Icon)

    private val toasts = ArrayList<Toast>()

    /** RuStore has a newer version of the game. */
    internal var updateOut = false
        private set

    init {
        I18n.lang = profile.language
        checkPurchases()
        // Asked first, so a store that answers at once has its new version offered before anything else.
        platform.checkUpdate { out ->
            updateOut = out
            if (out && screen == Screen.MENU) menus.enterMenu()
        }
        if (screen == Screen.MENU) menus.enterMenu()
    }

    internal val today: Int get() = platform.today()

    internal val shop: Shop get() = platform.shop

    internal val vip: Boolean get() = profile.vip(today)

    /** Asks the store what was paid for, maybe on another phone or after a renewal; [onDone] hears whether it found anything. */
    internal fun checkPurchases(onDone: ((Boolean) -> Unit)? = null) {
        platform.checkPurchases { found ->
            val pass = found != null && found.passDays.any { Season.of(it) == Season.of(today) }
            if (found != null) {
                found.vipUntil?.let { profile.grantVip(it) }
                for (p in found.owned) profile.grant(p)
                if (pass) profile.grant(Product.PASS)
            }
            onDone?.invoke(found != null && ((found.vipUntil ?: -1) >= today || found.owned.isNotEmpty() || pass))
        }
    }

    /** Buys [product] through the store; [onDone] hears whether it went through. */
    internal fun buy(product: Product, onDone: (Payment) -> Unit) {
        platform.buy(product) { payment, until ->
            if (payment == Payment.PAID) {
                if (product.subscription) profile.grantVip(if (until >= today) until else today + Vip.DAYS) else profile.grant(product)
                val (text, icon) = when (product) {
                    Product.VIP_MONTH, Product.VIP_FOREVER -> Tx.VIP_THANKS.t to Icon.CROWN
                    else -> Tx.BOUGHT.t to Icon.COMMANDER
                }
                toast(text, icon)
                sound(Sfx.ACHIEVEMENT, 0.8f)
            } else if (payment == Payment.FAILED) {
                toast(Tx.PAY_FAILED.t, Icon.WARNING)
            }
            onDone(payment)
        }
    }

    /** Rewarded adverts are offered: this build plays them, the player has no VIP, and today's are not used up. */
    internal val adsOffered: Boolean get() = platform.ads && !vip && profile.adsWatched(today) < Ads.PER_DAY

    /** An advert can play right now. */
    internal val adReady: Boolean get() = platform.adReady

    /** Plays an advert when one is offered; [reward] runs only when the player watched it through. */
    internal fun watchAd(reward: () -> Unit) {
        if (!adsOffered || !platform.adReady) return
        platform.showAd { watched ->
            if (watched) {
                profile.countAd(today)
                reward()
                sound(Sfx.ACHIEVEMENT, 0.7f)
            } else {
                toast(Tx.AD_NOT_WATCHED.t, Icon.VIDEO)
            }
        }
    }

    /** The promo codes this game knows; tests add their own. */
    internal var promoCodes = Promo.CODES

    /** Asks for a promo code and hands over what it gives. */
    internal fun enterPromo() {
        platform.askText(Tx.PROMO_TITLE.t, Tx.PROMO_HINT.t, Tx.PROMO_GO.t) { text -> if (text != null) redeem(text) }
    }

    internal fun redeem(text: String): PromoResult {
        val code = Promo.find(text, promoCodes)
        val result = if (code == null) PromoResult.UNKNOWN else profile.redeem(code, today)
        when (result) {
            PromoResult.DONE -> {
                toast(Tx.PROMO_DONE.f(code!!.rewards.joinToString(", ") { describe(it) }), Icon.STAR)
                sound(Sfx.ACHIEVEMENT, 0.8f)
            }
            PromoResult.ADMIN -> {
                toast(Tx.ADMIN_ON.t, Icon.CROWN)
                sound(Sfx.ACHIEVEMENT, 0.8f)
            }
            PromoResult.USED -> toast(Tx.PROMO_USED.t, Icon.WARNING)
            PromoResult.EXPIRED -> toast(Tx.PROMO_EXPIRED.t, Icon.WARNING)
            PromoResult.UNKNOWN -> toast(Tx.PROMO_UNKNOWN.t, Icon.WARNING)
        }
        return result
    }

    /** Switches admin mode, once its code has been entered here. */
    internal fun setAdmin(on: Boolean) {
        if (!profile.adminKnown) return
        profile.admin = on
        toast(if (on) Tx.ADMIN_ON.t else Tx.ADMIN_OFF.t, Icon.CROWN)
    }

    private fun describe(r: Reward): String = when (r.kind) {
        RewardKind.POINTS -> Tx.GIFT_POINTS.f(r.amount)
        RewardKind.SERVICE -> Tx.GIFT_SERVICE.f(r.amount)
        RewardKind.VIP_DAYS -> Tx.GIFT_VIP.f(r.amount)
        RewardKind.CAMO -> Camo.URBAN.title.t
    }

    internal val supportUrl: String? get() = platform.supportUrl

    internal fun startUpdate() = platform.startUpdate()

    internal val remindersAllowed: Boolean get() = platform.remindersAllowed

    /** Turns reminders on, asking the phone first where it must; [done] hears whether they are on. */
    internal fun allowReminders(done: (Boolean) -> Unit) {
        val ask = { ok: Boolean ->
            profile.reminders = ok
            if (!ok) toast(Tx.REMINDERS_BLOCKED.t, Icon.WARNING)
            done(ok)
        }
        if (platform.remindersAllowed) ask(true) else platform.allowReminders(ask)
    }

    /** After the third campaign win, and once more after the fifteenth, the store asks for a rating. */
    internal fun askReviewIfDue() {
        val wins = profile.stat(Stat.WINS)
        val due = REVIEW_AT.lastOrNull { wins >= it } ?: return
        if (profile.reviewAskedAt >= due) return
        profile.reviewAskedAt = wins
        platform.askReview()
    }

    internal fun openUrl(url: String) = platform.openUrl(url)

    fun resize(width: Int, height: Int, density: Float, insetTop: Int, insetBottom: Int) {
        ui.w = width.toFloat()
        ui.h = height.toFloat()
        ui.dp = density
        ui.top = insetTop.toFloat()
        ui.bottom = insetBottom.toFloat()
    }

    fun update(dt: Float) {
        val step = dt.coerceIn(0f, 0.1f)
        clock += step
        ui.update(step)
        val target = nextScreen
        if (target != null) {
            val before = fade
            fade += step / FADE_TIME
            if (before < 0.5f && fade >= 0.5f) {
                screen = target
                if (target == Screen.MENU) menus.enterMenu()
            }
            if (fade >= 1f) {
                nextScreen = null
                fade = 0f
            }
        }
        when (screen) {
            Screen.PLAY -> play.update(step)
            Screen.INTRO -> intro.update(step)
            else -> menus.update(step)
        }
        for (t in toasts) t.life -= step
        toasts.removeAll { it.life <= 0f }
        updateMusic()
    }

    private fun updateMusic() {
        val want = when {
            screen == Screen.PLAY && session?.world?.enemies?.any { it.type.boss && it.alive } == true -> Track.BOSS
            screen == Screen.PLAY -> Track.BATTLE
            else -> Track.MENU
        }
        val volume = profile.music / 100f
        if (want != track || abs(volume - trackVolume) > 0.001f) {
            track = want
            trackVolume = volume
            platform.music(if (volume > 0f) want else null, volume)
        }
    }

    fun render(g: Gfx) {
        renderer.camo = profile.battleCamo
        for (c in CommanderType.entries) renderer.outfits[c.ordinal] = profile.battleOutfit(c)
        ui.begin()
        g.fillRect(0f, 0f, ui.w, ui.h, Ui.BG)
        if (ui.w <= 0f || ui.h <= 0f) return
        if (screen != Screen.MENU && screen != Screen.PLAY && screen != Screen.INTRO) ui.backdrop(g)
        when (screen) {
            Screen.MENU -> menus.menu(g)
            Screen.LEVELS -> menus.levels(g)
            Screen.SETTINGS -> menus.settings(g)
            Screen.PERKS -> menus.perks(g)
            Screen.ACHIEVEMENTS -> menus.achievements(g)
            Screen.CODEX -> menus.codex(g)
            Screen.DAILY -> menus.daily(g)
            Screen.VIP -> menus.vip(g)
            Screen.COMMANDERS -> menus.commanders(g)
            Screen.WARDROBE -> menus.wardrobe(g)
            Screen.PASS -> menus.pass(g)
            Screen.PLAY -> play.render(g)
            Screen.INTRO -> intro.render(g)
        }
        drawToasts(g)
        if (nextScreen != null) {
            val a = 1f - abs(fade - 0.5f) * 2f
            g.fillRect(0f, 0f, ui.w, ui.h, Colors.alpha(0xFF000000.toInt(), a))
        }
    }

    private fun drawToasts(g: Gfx) {
        var y = ui.top + ui.d(58f)
        for (t in toasts) {
            val a = min(1f, t.life / 0.4f) * min(1f, (TOAST_TIME - t.life) / 0.25f + 0.2f)
            val tw = min(ui.w - ui.d(32f), g.textWidth(t.text, ui.d(13f)) + ui.d(62f))
            val th = ui.d(42f)
            val x = (ui.w - tw) / 2f
            ui.chamfer(g, x, y, tw, th, ui.d(9f), Colors.alpha(Ui.GOLD, a))
            ui.chamfer(g, x + ui.d(2f), y + ui.d(2f), tw - ui.d(4f), th - ui.d(4f), ui.d(8f), Colors.alpha(Ui.BAND, a))
            ui.icon(g, t.icon, x + ui.d(24f), y + th / 2f, ui.d(22f), Colors.alpha(Ui.GOLD, a))
            ui.textMid(g, t.text, x + ui.d(44f), y + th / 2f, ui.d(13f), Colors.alpha(Ui.TEXT, a), Align.LEFT, maxW = tw - ui.d(56f))
            y += ui.d(48f)
        }
    }

    fun touch(action: Touch, x: Float, y: Float) {
        if (nextScreen != null) {
            ui.touch(Touch.CANCEL, x, y)
            if (screen == Screen.PLAY) play.touch(Touch.CANCEL, x, y, true)
            return
        }
        val used = ui.touch(action, x, y)
        if (screen == Screen.PLAY) play.touch(action, x, y, used)
    }

    /** Two fingers spreading ([scale] above 1) or pinching around (fx, fy). */
    fun pinch(scale: Float, fx: Float, fy: Float) {
        if (screen == Screen.PLAY) play.pinch(scale, fx, fy)
    }

    /** Two fingers dragging the map by (dx, dy) pixels. */
    fun pan(dx: Float, dy: Float) {
        if (screen == Screen.PLAY) play.pan(dx, dy)
    }

    /** Back button: closes the innermost thing; false means leave the app. */
    fun back(): Boolean {
        if (nextScreen != null) return true
        when (screen) {
            Screen.MENU -> return false
            Screen.PLAY -> return play.back()
            Screen.INTRO -> intro.skip()
            Screen.LEVELS -> if (!menus.back()) go(Screen.MENU)
            else -> go(menus.returnTo())
        }
        return true
    }

    /** The app went to the background: pause the fight, keep it on disk and plan the reminders. */
    fun pause() {
        if (screen == Screen.PLAY) play.pauseFromSystem()
        track = null
        platform.music(null, 0f)
        platform.planReminders(Reminders.plan(profile, today, vip))
    }

    internal fun go(s: Screen) {
        if (s == screen && nextScreen == null) return
        nextScreen = s
        fade = 0f
    }

    internal fun sound(s: Sfx, volume: Float) {
        val level = profile.sound
        if (level <= 0) return
        val gap = when (s) {
            Sfx.SHOOT -> 0.06f
            Sfx.COIN -> 0.08f
            Sfx.FLAME -> 0.25f
            Sfx.ZAP, Sfx.BOOM, Sfx.MISSILE, Sfx.SNIPER, Sfx.MINE -> 0.07f
            Sfx.JAM -> 0.3f
            else -> 0.03f
        }
        if (clock - lastPlayed[s.ordinal] < gap) return
        lastPlayed[s.ordinal] = clock
        platform.play(s, volume * level / 100f)
    }

    internal fun vibrate(ms: Int) {
        if (profile.vibration) platform.vibrate(ms)
    }

    internal fun toast(text: String, icon: Icon = Icon.MEDAL) {
        toasts += Toast(text, TOAST_TIME, icon)
        if (toasts.size > 3) toasts.removeAt(0)
    }

    internal fun announce(achievements: List<Achievement>) {
        for (a in achievements) toast(Tx.ACHIEVEMENT_TOAST.f(a.title.t))
        if (achievements.isNotEmpty()) sound(Sfx.ACHIEVEMENT, 0.8f)
    }

    internal fun setLanguage(lang: Language) {
        profile.language = lang
        I18n.lang = lang
    }

    // --- battles --------------------------------------------------------------------------

    private val commander: CommanderType get() = profile.battleCommander

    private val rank: Int get() = profile.rank(commander)

    internal fun startCampaign(level: LevelDef, difficulty: Difficulty, challenge: Boolean) {
        begin(Session(Mode.CAMPAIGN, level, difficulty, challenge, today, profile.progress, profile.perks, vip, commander, rank, play))
    }

    internal fun startEndless() {
        begin(Session(Mode.ENDLESS, Levels.endless(profile.progress), Difficulty.NORMAL, false, today, profile.progress, profile.perks, vip, commander, rank, play))
    }

    internal fun startDaily() {
        val day = today
        begin(Session(Mode.DAILY, Levels.daily(day, profile.progress), Difficulty.NORMAL, false, day, profile.progress, profile.perks, vip, commander, rank, play))
    }

    internal fun startWeekly() {
        val day = today
        begin(Session(Mode.WEEKLY, Levels.weekly(Weekly.week(day), profile.progress), Difficulty.NORMAL, false, day, profile.progress, profile.perks, vip, commander, rank, play))
    }

    /** The training battle, the same for everyone: the sergeant, no VIP, the first squads. */
    internal fun startTutorial() {
        begin(Session(Mode.TUTORIAL, Levels.tutorial(), Difficulty.EASY, false, today, TUTORIAL_PROGRESS, Perks.NONE, false, CommanderType.SERGEANT, 1, play))
    }

    internal fun restart() {
        val s = session ?: return
        play.flushStats()
        if (s.mode == Mode.TUTORIAL) return startTutorial()
        begin(Session(s.mode, s.level, s.difficulty, s.challenge, s.day, s.progress, profile.perks, vip, commander, rank, play))
    }

    /** The saved battle, if there is one that still loads. */
    internal fun savedSession(): Session? = profile.save?.let { Session.load(it, play) }

    internal fun resumeSaved(): Boolean {
        val s = savedSession()
        if (s == null) {
            profile.save = null
            return false
        }
        begin(s, resumed = true)
        return true
    }

    private fun begin(s: Session, resumed: Boolean = false) {
        if (!resumed) profile.save = null
        session = s
        s.world.lite = profile.lite
        s.world.damageNumbers = profile.damageNumbers
        play.begin(s, resumed)
        go(Screen.PLAY)
    }

    internal fun saveSession() {
        val s = session ?: return
        // A tutorial is not worth continuing from the menu.
        if (s.world.over || s.mode == Mode.TUTORIAL) {
            profile.save = null
            return
        }
        profile.save = s.save()
    }

    internal fun leaveBattle(to: Screen) {
        val s = session
        if (s != null && !s.world.over) saveSession()
        go(to)
    }

    companion object {
        const val STEP = 1f / 60f
        const val VERSION = "3.0"
        const val FADE_TIME = 0.28f
        const val TOAST_TIME = 3.2f

        /** Campaign wins after which the store asks for a rating. */
        val REVIEW_AT = intArrayOf(3, 15)

        /** The tutorial has the squads and the airstrike of a player past the first level. */
        const val TUTORIAL_PROGRESS = 2
    }
}
