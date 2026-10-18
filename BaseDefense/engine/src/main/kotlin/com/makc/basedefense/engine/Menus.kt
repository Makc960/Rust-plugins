package com.makc.basedefense.engine

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Every screen outside the battle. */
internal class Menus(private val game: Game) {
    private val ui get() = game.ui
    private val profile get() = game.profile
    private val renderer get() = game.renderer
    private val clock get() = game.clock

    private var demo: World? = null
    private var demoBot: Bot? = null
    private var demoAcc = 0f
    private val thumbs = HashMap<String, GameMap>()

    private var world = 0
    private var sheet: LevelDef? = null
    private var sheetDifficulty = Difficulty.NORMAL
    private var sheetChallenge = false
    private var codexTab = 0
    private var codexPick = -1
    private var resetArmedAt = -10f
    private var cameFrom = Screen.MENU

    private fun d(v: Float) = ui.d(v)

    fun update(dt: Float) {
        var w = demo
        if (w == null || w.over || w.time > 240f) {
            val reached = (profile.progress - 1) / Levels.PER_WORLD
            val pick = Levels.all[reached * Levels.PER_WORLD + ((clock / 240f).toInt() % 3)]
            w = World(pick, Rules(progress = pick.id))
            w.lite = true
            demo = w
            demoBot = Bot(w)
        }
        demoAcc += dt
        while (demoAcc >= Game.STEP) {
            demoBot?.update(Game.STEP)
            w.step(Game.STEP)
            demoAcc -= Game.STEP
        }
    }

    /** Back from the levels screen closes the level sheet first. */
    fun back(): Boolean {
        if (sheet != null) {
            sheet = null
            return true
        }
        return false
    }

    fun returnTo(): Screen = cameFrom

    private fun open(s: Screen) {
        cameFrom = Screen.MENU
        ui.resetScroll(s.name)
        game.go(s)
    }

    private var savedText: String? = null
    private var saved: Session? = null

    /** The battle waiting in the save, read again only when the save changes. */
    private fun savedBattle(): Session? {
        val text = profile.save
        if (text != savedText) {
            savedText = text
            saved = if (text == null) null else game.savedSession()
        }
        return saved
    }

    private fun thumb(level: LevelDef): GameMap = thumbs.getOrPut("${level.id}-${level.plan.seed}") { GameMap(level) }

    // --- main menu ------------------------------------------------------------------------

    private fun drawDemoBackground(g: Gfx) {
        val d0 = demo ?: return
        val c = max(ui.w / COLS, ui.h / ROWS)
        g.save()
        g.translate((ui.w - c * COLS) / 2f, (ui.h - c * ROWS) / 2f)
        g.scale(c)
        renderer.drawWorld(g, d0, clock, c)
        g.restore()
        g.fillRect(0f, 0f, ui.w, ui.h, 0xA6080B0A.toInt())
    }

    fun menu(g: Gfx) {
        drawDemoBackground(g)
        val cx = ui.w / 2f
        val big = min(d(46f), ui.w / 6.5f)
        val ty = ui.top + max(d(70f), ui.h * 0.14f)
        for ((i, line) in listOf(Tx.TITLE_1.t, Tx.TITLE_2.t).withIndex()) {
            val y = ty + i * big
            ui.text(g, line, cx + d(3f), y + d(3f), big, 0xFF000000.toInt(), Align.CENTER, maxW = ui.w - d(40f))
            ui.text(g, line, cx, y, big, if (i == 0) Ui.ACCENT else Ui.TEXT, Align.CENTER, maxW = ui.w - d(40f))
        }
        ui.text(g, Tx.TAGLINE.t, cx, ty + big + d(30f), d(14f), Ui.DIM, Align.CENTER)

        val saved = savedBattle()
        val bottomRow = ui.h - ui.bottom - d(118f)
        val top = ty + big + d(56f)
        val count = if (saved != null) 5 else 4
        val gap = d(10f)
        val bh = min(d(54f), (bottomRow - top - d(10f) - gap * (count - 1)) / count)
        val bw = min(ui.w - d(64f), d(320f))
        val bx = (ui.w - bw) / 2f
        var y = top
        if (saved != null) {
            ui.button(g, "continue", bx, y, bw, bh, Tx.CONTINUE.t, Style.PRIMARY, size = 17f, icon = { gg, x, yy, s, c -> ui.play(gg, x, yy, s, c) }) {
                game.resumeSaved()
            }
            val sub = Tx.CONTINUE_SUB.f(saved.level.name.t, max(1, saved.world.wavesStarted))
            ui.textMid(g, sub, cx, y + bh + d(7f), d(10.5f), Ui.DIM, maxW = bw)
            y += bh + gap + d(8f)
        }
        ui.button(g, "play", bx, y, bw, bh, Tx.CAMPAIGN.t, if (saved == null) Style.PRIMARY else Style.NORMAL, size = 18f) {
            world = ((profile.progress - 1) / Levels.PER_WORLD).coerceIn(0, 2)
            sheet = null
            open(Screen.LEVELS)
        }
        y += bh + gap
        val dailyLabel = if (profile.dailyUnlocked) Tx.DAILY.t else Tx.DAILY_LOCKED.t
        ui.button(g, "daily", bx, y, bw, bh, dailyLabel, enabled = profile.dailyUnlocked, size = 15f, icon = { gg, x, yy, s, c -> ui.calendar(gg, x, yy, s, c) }) {
            open(Screen.DAILY)
        }
        if (profile.dailyUnlocked && !profile.dailyWon(game.today)) g.fillCircle(bx + bw - d(14f), y + d(14f), d(5f), Ui.RED)
        y += bh + gap
        val endlessLabel = if (profile.endlessUnlocked) Tx.ENDLESS.t else Tx.ENDLESS_LOCKED.t
        ui.button(g, "endless", bx, y, bw, bh, endlessLabel, enabled = profile.endlessUnlocked, size = 15f, icon = { gg, x, yy, s, c -> ui.infinity(gg, x, yy, s, c) }) {
            game.startEndless()
        }
        y += bh + gap
        ui.button(g, "perks", bx, y, bw, bh, Tx.UPGRADES.t, size = 15f, icon = { gg, x, yy, s, c -> ui.upgradeArrow(gg, x, yy, s, c) }) {
            open(Screen.PERKS)
        }
        val free = profile.freePoints
        if (free > 0) {
            val label = Tx.FREE_POINTS.f(free)
            val tw = g.textWidth(label, d(12f)) + d(14f)
            g.fillRoundRect(bx + bw - tw - d(8f), y + bh / 2f - d(11f), tw, d(22f), d(11f), Ui.ACCENT)
            ui.textMid(g, label, bx + bw - tw / 2f - d(8f), y + bh / 2f, d(12f), 0xFF1E1404.toInt())
        }

        // Codex, achievements and settings along the bottom.
        val iconW = min(d(96f), (ui.w - d(48f)) / 3f)
        val rowX = (ui.w - iconW * 3 - d(16f)) / 2f
        val icons = listOf<Triple<String, String, (Gfx, Float, Float, Float, Int) -> Unit>>(
            Triple("codex", Tx.CODEX.t) { gg, x, yy, s, c -> ui.book(gg, x, yy, s, c) },
            Triple("ach", Tx.ACHIEVEMENTS.t) { gg, x, yy, s, c -> ui.trophy(gg, x, yy, s, c) },
            Triple("settings", Tx.SETTINGS.t) { gg, x, yy, s, c -> ui.gear(gg, x, yy, s, c) },
        )
        for ((i, item) in icons.withIndex()) {
            val x = rowX + i * (iconW + d(8f))
            ui.button(g, item.first, x, bottomRow, iconW, d(52f), "", icon = item.third) {
                when (item.first) {
                    "codex" -> {
                        codexPick = -1
                        open(Screen.CODEX)
                    }
                    "ach" -> open(Screen.ACHIEVEMENTS)
                    else -> open(Screen.SETTINGS)
                }
            }
            ui.textMid(g, item.second, x + iconW / 2f, bottomRow + d(64f), d(11f), Ui.DIM, maxW = iconW + d(6f))
        }
        val fy = ui.h - ui.bottom - d(20f)
        ui.star(g, cx - d(56f), fy - d(5f), d(9f), true)
        ui.text(g, "${profile.totalStars} / ${Levels.all.size * 3}", cx - d(42f), fy, d(13f), Ui.TEXT)
        if (profile.bestEndless > 0) ui.text(g, Tx.BEST_WAVES.f(profile.bestEndless), cx + d(28f), fy, d(12f), Ui.DIM, maxW = ui.w / 2f - d(36f))
    }

    // --- levels ---------------------------------------------------------------------------

    private fun worldOpen(i: Int): Boolean = profile.unlocked(i * Levels.PER_WORLD + 1)

    fun levels(g: Gfx) {
        val headerBottom = ui.header(g, Tx.CAMPAIGN.t) { game.go(Screen.MENU) }
        ui.star(g, ui.w - d(70f), ui.top + d(30f), d(9f), true)
        ui.textMid(g, "${profile.totalStars}/${Levels.all.size * 3}", ui.w - d(56f), ui.top + d(31f), d(13f), Ui.TEXT, Align.LEFT)

        val tabs = Biome.entries.map { it.title.t }
        ui.segmented(g, "world", d(12f), headerBottom, ui.w - d(24f), d(38f), tabs, world, { worldOpen(it) }, size = 13f) {
            world = it
        }
        val margin = d(12f)
        val gap = d(10f)
        val gridTop = headerBottom + d(50f)
        val list = Levels.all.filter { it.world == world }
        val cols = if (ui.w > ui.h) 4 else 2
        val rows = (list.size + cols - 1) / cols
        val cw = (ui.w - margin * 2 - gap * (cols - 1)) / cols
        val avail = ui.h - ui.bottom - gridTop - margin
        val ch = min(d(230f), (avail - gap * (rows - 1)) / rows)
        for ((i, lvl) in list.withIndex()) {
            val x = margin + (i % cols) * (cw + gap)
            val y = gridTop + (i / cols) * (ch + gap)
            val open = profile.unlocked(lvl.id)
            val id = "level${lvl.id}"
            ui.area(id, x, y, cw, ch, open) {
                sheet = lvl
                sheetDifficulty = Difficulty.NORMAL
                sheetChallenge = false
            }
            val down = ui.down(id)
            g.fillRoundRect(x, y + d(3f), cw, ch, d(12f), 0xFF0A0E0C.toInt())
            g.fillRoundRect(x, y + (if (down) d(2f) else 0f), cw, ch, d(12f), Ui.PANEL_LIGHT)
            g.strokeRoundRect(x, y, cw, ch, d(12f), if (open) Ui.EDGE else Ui.PANEL, d(1f))
            ui.text(g, "${lvl.id} · ${lvl.name.t}", x + d(10f), y + d(21f), d(13.5f), if (open) Ui.TEXT else Ui.DIM, maxW = cw - d(20f))
            val thumbTop = y + d(30f)
            val thumbH = ch - d(30f) - d(28f)
            renderer.drawMapThumb(g, thumb(lvl), x + d(8f), thumbTop, cw - d(16f), thumbH)
            if (!open) {
                g.fillRoundRect(x, y, cw, ch, d(12f), 0xB30E1412.toInt())
                ui.lock(g, x + cw / 2f, y + ch / 2f, d(34f))
            } else {
                val s = profile.stars(lvl.id)
                for (k in 0 until 3) ui.star(g, x + d(18f) + k * d(19f), y + ch - d(14f), d(8f), k < s)
                if (profile.hardWon(lvl.id)) ui.skull(g, x + cw - d(42f), y + ch - d(14f), d(15f), Ui.RED)
                ui.medal(g, x + cw - d(18f), y + ch - d(16f), d(17f), profile.medal(lvl.id))
            }
        }
        if (!worldOpen(world)) {
            val prev = Biome.entries[max(0, world - 1)].title.t
            ui.textMid(g, Tx.WORLD_LOCKED.f(prev), ui.w / 2f, gridTop - d(4f), d(12f), Ui.DIM)
        }
        sheet?.let { drawSheet(g, it) }
    }

    private fun drawSheet(g: Gfx, lvl: LevelDef) {
        ui.begin()
        g.fillRect(0f, 0f, ui.w, ui.h, Ui.SCRIM)
        ui.area("scrim", 0f, 0f, ui.w, ui.h, silent = true) { sheet = null }
        val pw = min(ui.w - d(28f), d(380f))
        val ph = min(ui.h - ui.top - ui.bottom - d(20f), d(560f))
        val px = (ui.w - pw) / 2f
        val py = ui.top + (ui.h - ui.top - ui.bottom - ph) / 2f
        ui.area("sheet", px, py, pw, ph, silent = true) {}
        ui.panel(g, px, py, pw, ph)
        ui.text(g, "${lvl.id} · ${lvl.name.t}", px + d(18f), py + d(34f), d(19f), Ui.TEXT, maxW = pw - d(80f))
        ui.button(g, "sheetclose", px + pw - d(52f), py + d(10f), d(40f), d(36f), "", Style.GHOST, icon = { gg, cx, cy, s, c -> ui.cross(gg, cx, cy, s * 0.8f, c) }) {
            sheet = null
        }
        // The map on the left, what is new on the right.
        val thumbW = pw * 0.36f
        val thumbH = thumbW * ROWS / COLS
        val ty = py + d(50f)
        renderer.drawMapThumb(g, thumb(lvl), px + d(16f), ty, thumbW, thumbH)
        val ix = px + d(28f) + thumbW
        val iw = px + pw - d(16f) - ix
        var iy = ty + d(6f)
        ui.text(g, Tx.WAVES_COUNT.f(lvl.plan.count), ix, iy + d(10f), d(14f), Ui.TEXT, maxW = iw)
        iy += d(24f)
        val stars = profile.stars(lvl.id)
        for (k in 0 until 3) ui.star(g, ix + d(9f) + k * d(20f), iy + d(4f), d(8.5f), k < stars)
        if (profile.hardWon(lvl.id)) ui.skull(g, ix + d(78f), iy + d(4f), d(15f), Ui.RED)
        ui.medal(g, ix + d(100f), iy + d(2f), d(17f), profile.medal(lvl.id))
        iy += d(26f)
        val fresh = EnemyType.entries.filter { it.campaign == lvl.id }
        if (fresh.isNotEmpty()) {
            ui.text(g, Tx.NEW_ENEMIES.t, ix, iy + d(10f), d(11f), Ui.DIM, maxW = iw)
            iy += d(16f)
            for ((k, e) in fresh.withIndex()) renderer.drawEnemyIcon(g, e, ix + d(14f) + k * d(32f), iy + d(14f), d(26f), clock)
            iy += d(34f)
        }
        val tower = TowerType.entries.firstOrNull { it.unlock == lvl.id && lvl.id > 1 }
        if (tower != null) {
            ui.text(g, Tx.NEW_TOWER.t, ix, iy + d(10f), d(11f), Ui.DIM, maxW = iw)
            renderer.drawTowerIcon(g, tower, 0, ix + d(16f), iy + d(32f), d(28f), clock)
            ui.text(g, tower.title.t, ix + d(36f), iy + d(36f), d(12f), Ui.TEXT, maxW = iw - d(36f))
            iy += d(52f)
        }
        val ability = Ability.entries.firstOrNull { it.unlock == lvl.id }
        if (ability != null) {
            ui.text(g, Tx.NEW_ABILITY.t, ix, iy + d(10f), d(11f), Ui.DIM, maxW = iw)
            renderer.drawAbilityIcon(g, ability, ix + d(14f), iy + d(30f), d(22f), Ui.TEXT)
            ui.text(g, ability.title.t, ix + d(36f), iy + d(34f), d(12f), Ui.TEXT, maxW = iw - d(36f))
        }

        var y = ty + thumbH + d(16f)
        ui.text(g, Tx.DIFFICULTY.t, px + d(18f), y + d(12f), d(12f), Ui.DIM)
        y += d(20f)
        val hardOpen = stars > 0
        ui.segmented(
            g, "diff", px + d(16f), y, pw - d(32f), d(40f), Difficulty.entries.map { it.title.t }, sheetDifficulty.ordinal,
            { it != Difficulty.HARD.ordinal || hardOpen }, size = 13f,
        ) { sheetDifficulty = Difficulty.entries[it] }
        y += d(46f)
        val diffHint = if (!hardOpen && sheetDifficulty != Difficulty.HARD) Tx.HARD_LOCKED.t else sheetDifficulty.hint.t
        ui.textMid(g, diffHint, px + pw / 2f, y + d(6f), d(11f), Ui.DIM, maxW = pw - d(32f))
        y += d(20f)
        val challenge = lvl.challenge
        if (challenge != null) {
            val rowH = d(58f)
            ui.toggle(g, "chal", px + d(12f), y, pw - d(24f), rowH, "", sheetChallenge) { sheetChallenge = it }
            ui.medal(g, px + d(32f), y + rowH / 2f, d(26f), profile.medal(lvl.id))
            ui.text(g, Tx.CHALLENGE.t + ": " + challenge.title, px + d(52f), y + d(24f), d(13f), Ui.TEXT, maxW = pw - d(130f))
            ui.text(g, Tx.CHALLENGE_HINT.t, px + d(52f), y + d(42f), d(10.5f), Ui.DIM, maxW = pw - d(130f))
            y += rowH + d(8f)
        }
        val by = max(y + d(4f), py + ph - d(70f))
        ui.button(g, "fight", px + d(16f), by, pw - d(32f), d(52f), Tx.FIGHT.t, Style.PRIMARY, size = 18f) {
            val diff = if (sheetDifficulty == Difficulty.HARD && !hardOpen) Difficulty.NORMAL else sheetDifficulty
            sheet = null
            game.startCampaign(lvl, diff, sheetChallenge)
        }
    }

    // --- settings -------------------------------------------------------------------------

    fun settings(g: Gfx) {
        val top = ui.header(g, Tx.SETTINGS.t) { game.go(Screen.MENU) }
        val x = d(16f)
        val w = ui.w - d(32f)
        val viewH = ui.h - ui.bottom - top
        val content = settingsHeight(false) + d(300f)
        val off = ui.beginScroll(g, Screen.SETTINGS.name, 0f, top, ui.w, viewH, content)
        var y = top - off + d(4f)
        y += settingsRows(g, x, y, w, inBattle = false)
        y += d(12f)
        val armed = clock - resetArmedAt < 3f
        ui.button(g, "reset", x, y, w, d(46f), if (armed) Tx.RESET_SURE.t else Tx.RESET.t, if (armed) Style.DANGER else Style.GHOST, size = 14f) {
            if (armed) {
                profile.resetProgress()
                resetArmedAt = -10f
                world = 0
            } else {
                resetArmedAt = clock
            }
        }
        y += d(64f)
        ui.text(g, Tx.PRIVACY.t, x + d(4f), y + d(14f), d(15f), Ui.TEXT)
        y += d(24f)
        y += ui.paragraph(g, Tx.PRIVACY_TEXT.t, x + d(4f), y, w - d(8f), d(12.5f), Ui.DIM)
        y += d(14f)
        ui.text(g, Tx.CREDITS.t, x + d(4f), y + d(14f), d(15f), Ui.TEXT)
        y += d(24f)
        y += ui.paragraph(g, Tx.CREDITS_TEXT.t, x + d(4f), y, w - d(8f), d(12.5f), Ui.DIM)
        y += d(14f)
        ui.text(g, Tx.VERSION.f(Game.VERSION), x + d(4f), y + d(12f), d(12f), Ui.DIM)
        ui.endScroll(g, Screen.SETTINGS.name)
    }

    private fun settingsHeight(inBattle: Boolean): Float = d(70f) + d(62f) * 2 + d(52f) * 4 + (if (inBattle) 0f else 0f)

    /** The settings rows from the top at [y]; returns their height. */
    private fun settingsRows(g: Gfx, x: Float, y0: Float, w: Float, inBattle: Boolean): Float {
        var y = y0
        ui.text(g, Tx.LANGUAGE.t, x + d(4f), y + d(14f), d(15f), Ui.TEXT)
        ui.segmented(g, "lang", x, y + d(24f), w, d(38f), listOf("Русский", "English"), I18n.lang.ordinal) {
            game.setLanguage(Language.entries[it])
        }
        y += d(70f)
        ui.slider(g, "sfx", x, y, w, d(56f), Tx.SOUND.t, profile.sound) { profile.sound = it }
        y += d(62f)
        ui.slider(g, "music", x, y, w, d(56f), Tx.MUSIC.t, profile.music) { profile.music = it }
        y += d(62f)
        ui.toggle(g, "vibe", x, y, w, d(46f), Tx.VIBRATION.t, profile.vibration) { profile.vibration = it }
        y += d(52f)
        ui.toggle(g, "lite", x, y, w, d(46f), Tx.LITE.t, profile.lite) {
            profile.lite = it
            game.session?.world?.lite = it
        }
        y += d(52f)
        ui.toggle(g, "numbers", x, y, w, d(46f), Tx.NUMBERS.t, profile.damageNumbers) {
            profile.damageNumbers = it
            game.session?.world?.damageNumbers = it
        }
        y += d(52f)
        ui.toggle(g, "tips", x, y, w, d(46f), Tx.TIPS.t, profile.tips) { profile.tips = it }
        y += d(52f)
        return y - y0
    }

    /** Settings as a dialog over the paused battle. */
    fun settingsPanel(g: Gfx, inBattle: Boolean, onBack: () -> Unit) {
        val pw = min(ui.w - d(32f), d(360f))
        val inner = settingsHeight(inBattle)
        val ph = min(ui.h - ui.top - ui.bottom - d(24f), inner + d(130f))
        val px = (ui.w - pw) / 2f
        val py = ui.top + (ui.h - ui.top - ui.bottom - ph) / 2f
        ui.panel(g, px, py, pw, ph)
        ui.textMid(g, Tx.SETTINGS_BTN.t, ui.w / 2f, py + d(28f), d(18f), Ui.TEXT)
        val listTop = py + d(50f)
        val listH = ph - d(50f) - d(70f)
        val off = ui.beginScroll(g, "battle-settings", px, listTop, pw, listH, inner)
        settingsRows(g, px + d(16f), listTop - off, pw - d(32f), inBattle)
        ui.endScroll(g, "battle-settings")
        ui.button(g, "sback", px + d(16f), py + ph - d(60f), pw - d(32f), d(46f), Tx.RESUME.t, Style.PRIMARY) { onBack() }
    }

    // --- perks ----------------------------------------------------------------------------

    fun perks(g: Gfx) {
        val top = ui.header(g, Tx.UPGRADES.t) { game.go(Screen.MENU) }
        val x = d(14f)
        val w = ui.w - d(28f)
        ui.panel(g, x, top, w, d(74f), Ui.ACCENT)
        ui.star(g, x + d(28f), top + d(26f), d(11f), true)
        ui.text(g, Tx.POINTS.f(profile.freePoints), x + d(46f), top + d(32f), d(17f), Ui.GOLD, maxW = w - d(60f))
        ui.paragraph(g, Tx.POINTS_HINT.t, x + d(16f), top + d(40f), w - d(32f), d(10.5f), Ui.DIM, spacing = 1.2f)
        val listTop = top + d(86f)
        val rowH = d(74f)
        val bottomH = d(64f)
        val viewH = ui.h - ui.bottom - listTop - bottomH
        val content = Perk.entries.size * (rowH + d(8f))
        val off = ui.beginScroll(g, Screen.PERKS.name, 0f, listTop, ui.w, viewH, content)
        for ((i, p) in Perk.entries.withIndex()) {
            val y = listTop - off + i * (rowH + d(8f))
            if (y > listTop + viewH || y + rowH < listTop) continue
            val rank = profile.perk(p)
            g.fillRoundRect(x, y, w, rowH, d(12f), Ui.PANEL_LIGHT)
            g.strokeRoundRect(x, y, w, rowH, d(12f), Ui.EDGE, d(1f))
            val bw = d(86f)
            ui.text(g, p.title.t, x + d(14f), y + d(24f), d(15f), Ui.TEXT, maxW = w - bw - d(40f))
            ui.text(g, p.hint.t, x + d(14f), y + d(43f), d(11.5f), Ui.DIM, maxW = w - bw - d(40f))
            for (k in 0 until p.maxRank) {
                val px = x + d(20f) + k * d(18f)
                g.fillRoundRect(px - d(6f), y + d(54f), d(14f), d(8f), d(4f), if (k < rank) Ui.ACCENT else Ui.PANEL)
            }
            val bx = x + w - bw - d(10f)
            if (rank >= p.maxRank) {
                ui.textMid(g, Tx.MAX.t, bx + bw / 2f, y + rowH / 2f, d(14f), Ui.GOLD)
            } else {
                ui.button(g, "buy${p.name}", bx, y + d(18f), bw, d(38f), "${p.cost}", Style.PRIMARY, profile.freePoints >= p.cost, size = 15f,
                    icon = { gg, cx, cy, s, _ -> ui.star(gg, cx, cy, s * 0.55f, true, 0xFF6A4300.toInt()) }) {
                    if (profile.buyPerk(p)) game.sound(Sfx.UPGRADE, 0.6f)
                }
            }
        }
        ui.endScroll(g, Screen.PERKS.name)
        val by = ui.h - ui.bottom - bottomH + d(10f)
        ui.button(g, "respec", x, by, w, d(44f), Tx.RESPEC.t, Style.GHOST, profile.spent > 0) { profile.resetPerks() }
    }

    // --- achievements ---------------------------------------------------------------------

    fun achievements(g: Gfx) {
        val top = ui.header(g, Tx.ACHIEVEMENTS.t) { game.go(Screen.MENU) }
        val done = Achievement.entries.count { profile.achieved(it) }
        ui.textMid(g, Tx.ACH_COUNT.f(done, Achievement.entries.size), ui.w / 2f, top + d(4f), d(13f), Ui.ACCENT)
        val listTop = top + d(20f)
        val rowH = d(68f)
        val x = d(14f)
        val w = ui.w - d(28f)
        val viewH = ui.h - ui.bottom - listTop
        val list = Achievement.entries.sortedBy { if (profile.achieved(it)) 0 else 1 }
        val content = list.size * (rowH + d(8f)) + d(8f)
        val off = ui.beginScroll(g, Screen.ACHIEVEMENTS.name, 0f, listTop, ui.w, viewH, content)
        for ((i, a) in list.withIndex()) {
            val y = listTop - off + d(4f) + i * (rowH + d(8f))
            if (y > listTop + viewH || y + rowH < listTop) continue
            val got = profile.achieved(a)
            g.fillRoundRect(x, y, w, rowH, d(12f), if (got) 0xFF262214.toInt() else Ui.PANEL_LIGHT)
            g.strokeRoundRect(x, y, w, rowH, d(12f), if (got) Ui.GOLD else Ui.EDGE, d(1f))
            ui.medal(g, x + d(28f), y + rowH / 2f, d(30f), got)
            val tx = x + d(52f)
            val tw = w - d(64f)
            ui.text(g, a.title.t, tx, y + d(22f), d(14.5f), if (got) Ui.GOLD else Ui.TEXT, maxW = tw)
            ui.text(g, a.hint.t, tx, y + d(39f), d(11f), Ui.DIM, maxW = tw)
            val now = min(a.progress(profile), a.goal)
            val count = "$now/${a.goal}"
            val cw = g.textWidth(count, d(10.5f))
            ui.bar(g, tx, y + d(48f), tw - cw - d(14f), d(7f), now / a.goal.toFloat(), if (got) Ui.GOLD else Ui.ACCENT)
            ui.text(g, count, tx + tw - d(2f), y + d(55f), d(10.5f), Ui.DIM, Align.RIGHT)
        }
        ui.endScroll(g, Screen.ACHIEVEMENTS.name)
    }

    // --- codex ----------------------------------------------------------------------------

    private class Entry(val key: String, val known: Boolean, val name: String, val draw: (Gfx, Float, Float, Float) -> Unit, val details: () -> List<String>)

    private fun codexEntries(): List<Entry> {
        val progress = profile.progress
        return when (codexTab) {
            0 -> EnemyType.entries.map { e ->
                Entry(e.key, profile.seen(e.key), e.title.t, { g, cx, cy, s -> renderer.drawEnemyIcon(g, e, cx, cy, s, clock) }) {
                    buildList {
                        add(e.hint.t)
                        add(listOf(Tx.HEALTH.f(e.hp.roundToInt()), Tx.SPEED.f(Num.one(e.speed)), Tx.ARMOR.f(e.armor.roundToInt())).joinToString(" · "))
                        add(listOf(Tx.REWARD.f(e.reward), Tx.BASE_DAMAGE.f(e.damage)).joinToString(" · "))
                        val traits = buildList {
                            if (e.boss) add(Tx.BOSS.t)
                            if (e.air) add(Tx.FLYING.t)
                            if (e.shield > 0f) add(Tx.SHIELDED.f(Num.percent(e.shield)))
                            if (e.stealth) add(Tx.STEALTHY.t)
                        }
                        if (traits.isNotEmpty()) add(traits.joinToString(" · "))
                    }
                }
            }
            1 -> TowerType.entries.map { t ->
                Entry(t.key, t.unlock <= progress, t.title.t, { g, cx, cy, s -> renderer.drawTowerIcon(g, t, 2, cx, cy, s, clock) }) {
                    val b = t.branches
                    listOf(t.hint.t, Tx.COST.f(t.levels[0].cost), Tx.BRANCHES.f(b[0].title.t, b[1].title.t), b[0].title.t + ": " + b[0].hint.t, b[1].title.t + ": " + b[1].hint.t)
                }
            }
            else -> Ability.entries.map { a ->
                Entry(a.key, a.unlock <= progress, a.title.t, { g, cx, cy, s -> renderer.drawAbilityIcon(g, a, cx, cy, s * 0.7f, Ui.TEXT) }) {
                    listOf(a.hint.t, Tx.COOLDOWN.f(a.cooldown.roundToInt()))
                }
            }
        }
    }

    private fun unlockLevel(key: String): Int = when {
        key.startsWith("e:") -> EnemyType.valueOf(key.substring(2)).campaign
        key.startsWith("t:") -> TowerType.valueOf(key.substring(2)).unlock
        else -> Ability.valueOf(key.substring(2)).unlock
    }

    fun codex(g: Gfx) {
        val top = ui.header(g, Tx.CODEX.t) { game.go(Screen.MENU) }
        ui.segmented(g, "ctab", d(12f), top, ui.w - d(24f), d(38f), listOf(Tx.TAB_ENEMIES.t, Tx.TAB_TOWERS.t, Tx.TAB_ABILITIES.t), codexTab, size = 13f) {
            codexTab = it
            codexPick = -1
            ui.resetScroll(Screen.CODEX.name)
        }
        val entries = codexEntries()
        val detailH = d(176f)
        val gridTop = top + d(50f)
        val gridH = ui.h - ui.bottom - gridTop - detailH - d(8f)
        val cols = 4
        val gap = d(8f)
        val cw = (ui.w - d(24f) - gap * (cols - 1)) / cols
        val ch = cw + d(18f)
        val rows = (entries.size + cols - 1) / cols
        val off = ui.beginScroll(g, Screen.CODEX.name, 0f, gridTop, ui.w, gridH, rows * (ch + gap))
        for ((i, e) in entries.withIndex()) {
            val x = d(12f) + (i % cols) * (cw + gap)
            val y = gridTop - off + (i / cols) * (ch + gap)
            if (y > gridTop + gridH || y + ch < gridTop) continue
            val id = "entry$i"
            ui.area(id, x, y, cw, ch) { codexPick = i }
            val picked = codexPick == i
            g.fillRoundRect(x, y, cw, ch, d(10f), if (picked) 0xFF2A2416.toInt() else Ui.PANEL_LIGHT)
            g.strokeRoundRect(x, y, cw, ch, d(10f), if (picked) Ui.ACCENT else Ui.EDGE, if (picked) d(2f) else d(1f))
            if (e.known) {
                e.draw(g, x + cw / 2f, y + cw / 2f, cw * 0.6f)
                ui.textMid(g, e.name, x + cw / 2f, y + ch - d(12f), d(10f), Ui.TEXT, maxW = cw - d(6f))
            } else {
                ui.textMid(g, "?", x + cw / 2f, y + cw / 2f, d(26f), Ui.DIM)
                ui.textMid(g, "???", x + cw / 2f, y + ch - d(12f), d(10f), Ui.DIM)
            }
        }
        ui.endScroll(g, Screen.CODEX.name)

        val dy = ui.h - ui.bottom - detailH
        ui.panel(g, d(12f), dy, ui.w - d(24f), detailH - d(8f))
        val pick = entries.getOrNull(codexPick)
        if (pick == null) {
            ui.textMid(g, Tx.TAP_FOR_DETAILS.t, ui.w / 2f, dy + detailH / 2f - d(4f), d(13f), Ui.DIM, maxW = ui.w - d(48f))
            return
        }
        if (!pick.known) {
            val text = if (codexTab == 0) Tx.NOT_MET.t else Tx.UNLOCKS_ON.f(unlockLevel(pick.key))
            ui.textMid(g, text, ui.w / 2f, dy + detailH / 2f - d(4f), d(14f), Ui.DIM, maxW = ui.w - d(48f))
            return
        }
        g.fillCircle(d(56f), dy + d(52f), d(34f), Ui.PANEL_LIGHT)
        pick.draw(g, d(56f), dy + d(52f), d(48f))
        val tx = d(102f)
        val tw = ui.w - tx - d(24f)
        ui.text(g, pick.name, tx, dy + d(30f), d(17f), Ui.TEXT, maxW = tw)
        var y = dy + d(38f)
        for ((k, line) in pick.details().withIndex()) {
            val h = ui.paragraph(g, line, tx, y, tw, d(11f), if (k == 0) Ui.TEXT else Ui.DIM, spacing = 1.2f)
            y += h + d(2f)
            if (y > dy + detailH - d(20f)) break
        }
    }

    // --- daily ----------------------------------------------------------------------------

    fun daily(g: Gfx) {
        val top = ui.header(g, Tx.DAILY.t) { game.go(Screen.MENU) }
        val day = game.today
        val level = Levels.daily(day, profile.progress)
        val mods = Mod.forDay(day)
        val date = LocalDate.ofEpochDay(day.toLong())
        ui.textMid(g, "%02d.%02d.%d".format(date.dayOfMonth, date.monthValue, date.year), ui.w / 2f, top + d(4f), d(13f), Ui.ACCENT)
        val x = d(14f)
        val w = ui.w - d(28f)
        val thumbH = min(d(240f), (ui.h - ui.bottom - top) * 0.36f)
        val thumbW = thumbH * COLS / ROWS
        val ty = top + d(28f)
        renderer.drawMapThumb(g, thumb(level), (ui.w - thumbW) / 2f, ty, thumbW, thumbH)
        var y = ty + thumbH + d(10f)
        val source = Levels.all.firstOrNull { it.ascii == level.ascii }
        if (source != null) ui.textMid(g, Tx.DAILY_MAP.f(source.name.t), ui.w / 2f, y + d(6f), d(12f), Ui.DIM)
        y += d(22f)
        ui.text(g, Tx.DAILY_TWISTS.t, x + d(4f), y + d(12f), d(13f), Ui.TEXT)
        y += d(20f)
        for (m in mods) {
            g.fillRoundRect(x, y, w, d(48f), d(10f), Ui.PANEL_LIGHT)
            g.strokeRoundRect(x, y, w, d(48f), d(10f), Ui.EDGE, d(1f))
            ui.text(g, m.title.t, x + d(14f), y + d(20f), d(14f), Ui.ACCENT, maxW = w - d(28f))
            ui.text(g, m.hint.t, x + d(14f), y + d(37f), d(11.5f), Ui.DIM, maxW = w - d(28f))
            y += d(54f)
        }
        y += d(4f)
        val best = profile.dailyBest(day)
        val status = if (profile.dailyWon(day)) Tx.DAILY_WON.t else Tx.DAILY_BEST.f(best)
        ui.textMid(g, status, ui.w / 2f, y + d(8f), d(13f), if (profile.dailyWon(day)) Ui.GREEN else Ui.TEXT, maxW = w)
        y += d(20f)
        ui.textMid(g, Tx.STREAK.f(profile.dailyStreak(day)) + " · " + Tx.DAILY_REWARD.t, ui.w / 2f, y + d(8f), d(11f), Ui.DIM, maxW = w)
        val by = max(y + d(24f), ui.h - ui.bottom - d(70f))
        ui.button(g, "dstart", x, by, w, d(54f), Tx.START.t, Style.PRIMARY, size = 18f) { game.startDaily() }
    }
}
