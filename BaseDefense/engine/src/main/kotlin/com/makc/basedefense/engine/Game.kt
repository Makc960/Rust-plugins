package com.makc.basedefense.engine

import kotlin.math.abs
import kotlin.math.min

internal enum class Screen { MENU, LEVELS, PLAY, SETTINGS, PERKS, ACHIEVEMENTS, CODEX, DAILY }

enum class Mode { CAMPAIGN, ENDLESS, DAILY }

/** One battle and everything chosen for it. */
internal class Session(
    val mode: Mode,
    val level: LevelDef,
    val difficulty: Difficulty,
    val challenge: Boolean,
    val day: Int,
    val progress: Int,
    val perks: Perks,
    listener: WorldListener,
) {
    val mods: List<Mod> = if (mode == Mode.DAILY) Mod.forDay(day) else emptyList()
    val rules = Rules(difficulty, if (challenge) level.challenge else null, perks, mods, progress)
    val world = World(level, rules, listener)

    fun save(): String =
        "game=2\nmode=${mode.name}\nlevel=${level.id}\ndifficulty=${difficulty.name}\nchallenge=${if (challenge) 1 else 0}\n" +
            "day=$day\nprogress=$progress\nperks=${perks.encode()}\n" + world.save()

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
                }
                val s = Session(
                    mode, level, Difficulty.valueOf(head.getValue("difficulty")), head["challenge"] == "1", day, progress,
                    Perks.decode(head["perks"] ?: ""), listener,
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
 */
class Game(private val platform: Platform) {
    internal val profile = Profile(platform)
    internal val ui = Ui { s -> sound(s, if (s == Sfx.CLICK) 0.5f else 0.4f) }
    internal val renderer = Renderer()
    internal var clock = 0f
        private set
    internal var screen = Screen.MENU
        private set
    private var nextScreen: Screen? = null
    private var fade = 0f

    internal var session: Session? = null
    internal val play = PlayScreen(this)
    internal val menus = Menus(this)

    private val lastPlayed = FloatArray(Sfx.entries.size) { -10f }
    private var track: Track? = null
    private var trackVolume = -1f

    private class Toast(val text: String, var life: Float)

    private val toasts = ArrayList<Toast>()

    init {
        I18n.lang = profile.language
    }

    internal val today: Int get() = platform.today()

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
        val target = nextScreen
        if (target != null) {
            val before = fade
            fade += step / FADE_TIME
            if (before < 0.5f && fade >= 0.5f) screen = target
            if (fade >= 1f) {
                nextScreen = null
                fade = 0f
            }
        }
        if (screen == Screen.PLAY) play.update(step) else menus.update(step)
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
        ui.begin()
        g.fillRect(0f, 0f, ui.w, ui.h, Ui.BG)
        if (ui.w <= 0f || ui.h <= 0f) return
        when (screen) {
            Screen.MENU -> menus.menu(g)
            Screen.LEVELS -> menus.levels(g)
            Screen.SETTINGS -> menus.settings(g)
            Screen.PERKS -> menus.perks(g)
            Screen.ACHIEVEMENTS -> menus.achievements(g)
            Screen.CODEX -> menus.codex(g)
            Screen.DAILY -> menus.daily(g)
            Screen.PLAY -> play.render(g)
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
            val tw = min(ui.w - ui.d(32f), g.textWidth(t.text, ui.d(13f)) + ui.d(56f))
            val x = (ui.w - tw) / 2f
            g.fillRoundRect(x, y, tw, ui.d(38f), ui.d(19f), Colors.alpha(0xF0151C19.toInt(), a))
            g.strokeRoundRect(x, y, tw, ui.d(38f), ui.d(19f), Colors.alpha(Ui.GOLD, a), ui.d(1.5f))
            ui.medal(g, x + ui.d(22f), y + ui.d(19f), ui.d(20f), true)
            ui.textMid(g, t.text, x + ui.d(40f), y + ui.d(19f), ui.d(13f), Colors.alpha(Ui.TEXT, a), Align.LEFT, maxW = tw - ui.d(52f))
            y += ui.d(44f)
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
            Screen.LEVELS -> if (!menus.back()) go(Screen.MENU)
            else -> go(menus.returnTo())
        }
        return true
    }

    /** The app went to the background: pause the fight and keep it on disk. */
    fun pause() {
        if (screen == Screen.PLAY) play.pauseFromSystem()
        track = null
        platform.music(null, 0f)
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

    internal fun toast(text: String) {
        toasts += Toast(text, TOAST_TIME)
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

    internal fun startCampaign(level: LevelDef, difficulty: Difficulty, challenge: Boolean) {
        begin(Session(Mode.CAMPAIGN, level, difficulty, challenge, today, profile.progress, profile.perks, play))
    }

    internal fun startEndless() {
        begin(Session(Mode.ENDLESS, Levels.endless(profile.progress), Difficulty.NORMAL, false, today, profile.progress, profile.perks, play))
    }

    internal fun startDaily() {
        val day = today
        begin(Session(Mode.DAILY, Levels.daily(day, profile.progress), Difficulty.NORMAL, false, day, profile.progress, profile.perks, play))
    }

    internal fun restart() {
        val s = session ?: return
        play.flushStats()
        begin(Session(s.mode, s.level, s.difficulty, s.challenge, s.day, s.progress, profile.perks, play))
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
        if (s.world.over) {
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
        const val VERSION = "2.1"
        const val FADE_TIME = 0.28f
        const val TOAST_TIME = 3.2f
    }
}
