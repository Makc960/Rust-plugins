package com.makc.basedefense.engine

import java.time.LocalDate
import kotlin.math.abs
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
    private var settingsBottom = 0f
    private var vipBottom = 0f
    private var vipBusyAt = -100f
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

    /** The map in a dark frame, fitted into the box; returns the map's own x, y, width and height. */
    private fun framedThumb(g: Gfx, map: GameMap, x: Float, y: Float, w: Float, h: Float): FloatArray {
        val cell = min(w / COLS, h / ROWS)
        val mw = cell * COLS
        val mh = cell * ROWS
        val mx = x + (w - mw) / 2f
        val my = y + (h - mh) / 2f
        g.fillRect(mx - d(2f), my - d(2f), mw + d(4f), mh + d(4f), Ui.EDGE)
        g.save()
        g.clip(mx, my, mw, mh)
        renderer.drawMapThumb(g, map, mx, my, mw, mh)
        g.restore()
        return floatArrayOf(mx, my, mw, mh)
    }

    private fun number(id: Int): String = id.toString().padStart(2, '0')

    private fun date(day: Int): String {
        val d = LocalDate.ofEpochDay(day.toLong())
        return "${d.dayOfMonth.toString().padStart(2, '0')}.${d.monthValue.toString().padStart(2, '0')}.${d.year}"
    }

    /** Stars won out of all there are, in a slot ending at [right]. */
    private fun starTotal(g: Gfx, right: Float, cy: Float) {
        val text = "${profile.totalStars}/${Levels.all.size * 3}"
        val tw = d(30f) + g.textWidth(text, d(13f), Font.DISPLAY) + d(10f)
        ui.well(g, right - tw, cy - d(14f), tw, d(28f))
        ui.star(g, right - tw + d(16f), cy + d(1f), d(8f), true)
        ui.textMid(g, text, right - d(10f), cy + d(1f), d(13f), Ui.TEXT, Align.RIGHT, font = Font.DISPLAY)
    }

    // --- main menu ------------------------------------------------------------------------

    private fun drawDemoBackground(g: Gfx) {
        val d0 = demo ?: return
        val c = max(ui.w / COLS, ui.h / ROWS)
        g.save()
        g.translate((ui.w - c * COLS) / 2f, (ui.h - c * ROWS) / 2f)
        g.scale(c)
        renderer.drawWorld(g, d0, clock, c)
        g.restore()
        g.fillRect(0f, 0f, ui.w, ui.h, 0xC20E1009.toInt())
    }

    fun menu(g: Gfx) {
        drawDemoBackground(g)
        val cx = ui.w / 2f
        val big = min(d(52f), ui.w / 6.6f)
        val ty = ui.top + max(d(70f), ui.h * 0.125f)
        val maxW = ui.w - d(48f)
        ui.text(g, Tx.TITLE_1.t, cx + d(3f), ty + d(4f), big, Ui.EDGE, Align.CENTER, maxW, Font.DISPLAY)
        ui.text(g, Tx.TITLE_1.t, cx, ty, big, Ui.TEXT, Align.CENTER, maxW, Font.DISPLAY)
        ui.tape(g, Tx.TITLE_2.t, cx, ty + big * 0.8f, ui.fit(g, Tx.TITLE_2.t, big * 0.9f, maxW - big, Font.DISPLAY), -3f)
        ui.textMid(g, Tx.TAGLINE.t.uppercase(), cx, ty + big * 1.82f, d(12f), Ui.DIM, maxW = maxW)
        if (game.vipShop != VipShop.NONE || game.vip) vipBadge(g)

        val saved = savedBattle()
        val bottomRow = ui.h - ui.bottom - d(128f)
        val top = ty + big * 2.3f
        val count = if (saved != null) 5 else 4
        val gap = d(11f)
        val bh = min(d(58f), (bottomRow - top - d(12f) - gap * (count - 1)) / count)
        val bw = min(ui.w - d(56f), d(330f))
        val bx = (ui.w - bw) / 2f
        var y = top
        if (saved != null) {
            val sub = Tx.CONTINUE_SUB.f(saved.level.name.t, max(1, saved.world.wavesStarted))
            ui.button(g, "continue", bx, y, bw, bh, Tx.CONTINUE.t, Style.PRIMARY, size = 17f, icon = Icon.PLAY, sub = sub) {
                game.resumeSaved()
            }
            y += bh + gap
        }
        ui.button(g, "play", bx, y, bw, bh, Tx.CAMPAIGN.t, if (saved == null) Style.PRIMARY else Style.NORMAL, size = 18f, icon = Icon.FLAG) {
            world = ((profile.progress - 1) / Levels.PER_WORLD).coerceIn(0, 2)
            sheet = null
            open(Screen.LEVELS)
        }
        y += bh + gap
        val dailyOpen = profile.dailyUnlocked
        ui.button(
            g, "daily", bx, y, bw, bh, Tx.DAILY.t, enabled = dailyOpen, size = 16f,
            icon = if (dailyOpen) Icon.CALENDAR else Icon.LOCK, sub = if (dailyOpen) null else Tx.DAILY_LOCKED.t,
        ) { open(Screen.DAILY) }
        if (dailyOpen && !profile.dailyWon(game.today)) {
            g.fillCircle(bx + bw - d(4f), y + d(4f), d(7f), Ui.EDGE)
            g.fillCircle(bx + bw - d(4f), y + d(4f), d(5f), Ui.RED)
        }
        y += bh + gap
        val endlessOpen = profile.endlessUnlocked
        ui.button(
            g, "endless", bx, y, bw, bh, Tx.ENDLESS.t, enabled = endlessOpen, size = 16f,
            icon = if (endlessOpen) Icon.INFINITY else Icon.LOCK, sub = if (endlessOpen) null else Tx.ENDLESS_LOCKED.t,
        ) { game.startEndless() }
        y += bh + gap
        ui.button(g, "perks", bx, y, bw, bh, Tx.UPGRADES.t, size = 16f, icon = Icon.RANKS) { open(Screen.PERKS) }
        val free = profile.freePoints
        if (free > 0) ui.tag(g, Tx.FREE_POINTS.f(free), bx + bw - d(12f), y + bh / 2f + (if (ui.down("perks")) d(2f) else 0f), d(13f), right = true)

        // Codex, achievements and settings along the bottom.
        val iconW = min(d(96f), (ui.w - d(64f)) / 3f)
        val gapX = d(12f)
        val rowX = (ui.w - iconW * 3 - gapX * 2) / 2f
        val icons = listOf(
            Triple("codex", Tx.CODEX.t, Icon.BOOK),
            Triple("ach", Tx.ACHIEVEMENTS.t, Icon.TROPHY),
            Triple("settings", Tx.SETTINGS.t, Icon.GEAR),
        )
        for ((i, item) in icons.withIndex()) {
            val x = rowX + i * (iconW + gapX)
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
            ui.textMid(g, item.second, x + iconW / 2f, bottomRow + d(70f), d(11.5f), Ui.DIM, maxW = iconW + d(10f))
        }
        val fy = ui.h - ui.bottom - d(24f)
        val record = if (profile.bestEndless > 0) Tx.BEST_WAVES.f(profile.bestEndless) else null
        if (record == null) {
            starTotal(g, cx + d(48f), fy)
        } else {
            starTotal(g, cx - d(4f), fy)
            val rw = d(30f) + g.textWidth(record, d(12f)) + d(10f)
            ui.well(g, cx + d(4f), fy - d(14f), rw, d(28f))
            ui.icon(g, Icon.INFINITY, cx + d(20f), fy + d(1f), d(16f), Ui.DIM)
            ui.textMid(g, record, cx + d(34f), fy + d(1f), d(12f), Ui.DIM, Align.LEFT)
        }
    }

    // --- levels ---------------------------------------------------------------------------

    private fun worldOpen(i: Int): Boolean = profile.unlocked(i * Levels.PER_WORLD + 1)

    fun levels(g: Gfx) {
        val headerBottom = ui.header(g, Tx.CAMPAIGN.t) { game.go(Screen.MENU) }
        starTotal(g, ui.w - d(12f), ui.top + d(30f))

        val tabs = Biome.entries.map { it.title.t }
        ui.segmented(g, "world", d(12f), headerBottom, ui.w - d(24f), d(40f), tabs, world, { worldOpen(it) }, size = 13f) {
            world = it
        }
        val margin = d(12f)
        val gap = d(10f)
        val gridTop = headerBottom + d(54f)
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
            levelCard(g, lvl, x, y + (if (ui.down(id)) d(2f) else 0f), cw, ch, open)
        }
        if (!worldOpen(world)) {
            val text = Tx.WORLD_LOCKED.f(Biome.entries[max(0, world - 1)].title.t)
            val tw = min(ui.w - d(40f), g.textWidth(text, d(13f)) + d(44f))
            val ty = gridTop - d(6f) + (rows * (ch + gap)) / 2f
            ui.plate(g, (ui.w - tw) / 2f, ty - d(22f), tw, d(44f), Ui.PANEL_DARK)
            ui.lock(g, (ui.w - tw) / 2f + d(20f), ty, d(16f), Ui.ACCENT)
            ui.textMid(g, text, (ui.w - tw) / 2f + d(34f), ty, d(13f), Ui.TEXT, Align.LEFT, maxW = tw - d(44f))
        }
        sheet?.let { drawSheet(g, it) }
    }

    private fun levelCard(g: Gfx, lvl: LevelDef, x: Float, y: Float, cw: Float, ch: Float, open: Boolean) {
        ui.plate(g, x, y, cw, ch, if (open) Ui.PANEL else Ui.PANEL_DARK, cut = d(8f))
        val tagW = ui.tag(g, number(lvl.id), x + d(8f), y + d(18f), d(12f), if (open) Ui.ACCENT else Ui.PANEL_HI, if (open) Ui.DARK_TEXT else Ui.FADED)
        ui.textMid(g, lvl.name.t, x + d(14f) + tagW, y + d(18f), d(13.5f), if (open) Ui.TEXT else Ui.FADED, Align.LEFT, maxW = cw - tagW - d(24f))
        val thumbTop = y + d(36f)
        val thumbH = ch - d(36f) - d(32f)
        val r = framedThumb(g, thumb(lvl), x + d(10f), thumbTop, cw - d(20f), thumbH)
        if (!open) {
            g.fillRect(r[0], r[1], r[2], r[3], 0xC0141710.toInt())
            ui.lock(g, x + cw / 2f, thumbTop + thumbH / 2f, d(26f), Ui.FADED)
            return
        }
        val s = profile.stars(lvl.id)
        for (k in 0 until 3) ui.star(g, x + d(20f) + k * d(21f), y + ch - d(16f), d(9f), k < s)
        if (profile.hardWon(lvl.id)) ui.skull(g, x + cw - d(44f), y + ch - d(16f), d(17f), Ui.RED)
        ui.medal(g, x + cw - d(20f), y + ch - d(16f), d(19f), profile.medal(lvl.id))
    }

    private fun drawSheet(g: Gfx, lvl: LevelDef) {
        ui.begin()
        g.fillRect(0f, 0f, ui.w, ui.h, Ui.SCRIM)
        ui.area("scrim", 0f, 0f, ui.w, ui.h, silent = true) { sheet = null }
        val pw = min(ui.w - d(28f), d(380f))
        val ph = min(ui.h - ui.top - ui.bottom - d(20f), d(580f))
        val px = (ui.w - pw) / 2f
        val py = ui.top + (ui.h - ui.top - ui.bottom - ph) / 2f
        ui.area("sheet", px, py, pw, ph, silent = true) {}
        val top = ui.panel(g, px, py, pw, ph, title = lvl.name.t)
        ui.tag(g, number(lvl.id), px + d(20f), py + d(26f), d(13f))
        ui.button(g, "sheetclose", px + pw - d(58f), py + d(9f), d(40f), d(34f), "", Style.GHOST, icon = Icon.CROSS) {
            sheet = null
        }
        // The map on the left, what is new on the right.
        val thumbW = pw * 0.38f
        val thumbH = thumbW * ROWS / COLS
        val ty = top + d(16f)
        framedThumb(g, thumb(lvl), px + d(18f), ty, thumbW, thumbH)
        val ix = px + d(32f) + thumbW
        val iw = px + pw - d(16f) - ix
        var iy = ty
        ui.icon(g, Icon.FLAG, ix + d(9f), iy + d(10f), d(17f), Ui.ACCENT)
        ui.textMid(g, Tx.WAVES_COUNT.f(lvl.plan.count), ix + d(24f), iy + d(10f), d(16f), Ui.TEXT, Align.LEFT, maxW = iw - d(24f), font = Font.DISPLAY)
        iy += d(30f)
        val stars = profile.stars(lvl.id)
        for (k in 0 until 3) ui.star(g, ix + d(10f) + k * d(21f), iy + d(4f), d(9f), k < stars)
        if (profile.hardWon(lvl.id)) ui.skull(g, ix + d(80f), iy + d(4f), d(16f), Ui.RED)
        ui.medal(g, ix + d(102f), iy + d(4f), d(18f), profile.medal(lvl.id))
        iy += d(26f)
        val fresh = EnemyType.entries.filter { it.campaign == lvl.id }
        if (fresh.isNotEmpty()) {
            ui.caption(g, Tx.NEW_ENEMIES.t, ix, iy + d(10f), maxW = iw)
            iy += d(18f)
            for ((k, e) in fresh.withIndex()) {
                val ex = ix + k * d(38f)
                ui.well(g, ex, iy, d(34f), d(34f))
                renderer.drawEnemyIcon(g, e, ex + d(17f), iy + d(18f), d(26f), clock)
            }
            iy += d(44f)
        }
        val tower = TowerType.entries.firstOrNull { it.unlock == lvl.id && lvl.id > 1 }
        if (tower != null) {
            ui.caption(g, Tx.NEW_TOWER.t, ix, iy + d(10f), maxW = iw)
            iy += d(18f)
            ui.well(g, ix, iy, d(34f), d(34f))
            renderer.drawTowerIcon(g, tower, 0, ix + d(17f), iy + d(18f), d(28f), clock)
            ui.textMid(g, tower.title.t, ix + d(42f), iy + d(17f), d(12.5f), Ui.TEXT, Align.LEFT, maxW = iw - d(42f))
            iy += d(44f)
        }
        val ability = Ability.entries.firstOrNull { it.unlock == lvl.id }
        if (ability != null) {
            ui.caption(g, Tx.NEW_ABILITY.t, ix, iy + d(10f), maxW = iw)
            iy += d(18f)
            ui.well(g, ix, iy, d(34f), d(34f))
            renderer.drawAbilityIcon(g, ability, ix + d(17f), iy + d(18f), d(20f), Ui.TEXT)
            ui.textMid(g, ability.title.t, ix + d(42f), iy + d(17f), d(12.5f), Ui.TEXT, Align.LEFT, maxW = iw - d(42f))
            iy += d(44f)
        }

        var y = max(ty + thumbH, iy) + d(18f)
        ui.caption(g, Tx.DIFFICULTY.t, px + d(18f), y + d(10f))
        y += d(18f)
        val hardOpen = stars > 0
        ui.segmented(
            g, "diff", px + d(16f), y, pw - d(32f), d(40f), Difficulty.entries.map { it.title.t }, sheetDifficulty.ordinal,
            { it != Difficulty.HARD.ordinal || hardOpen }, size = 13f,
        ) { sheetDifficulty = Difficulty.entries[it] }
        y += d(48f)
        val diffHint = if (!hardOpen && sheetDifficulty != Difficulty.HARD) Tx.HARD_LOCKED.t else sheetDifficulty.hint.t
        ui.textMid(g, diffHint, px + pw / 2f, y + d(6f), d(11.5f), Ui.DIM, maxW = pw - d(32f), font = Font.BODY)
        y += d(22f)
        val challenge = lvl.challenge
        if (challenge != null) {
            val rowH = d(60f)
            ui.plate(g, px + d(16f), y, pw - d(32f), rowH, Ui.PANEL_LIGHT)
            ui.toggle(g, "chal", px + d(16f), y, pw - d(32f), rowH, "", sheetChallenge) { sheetChallenge = it }
            ui.medal(g, px + d(38f), y + rowH / 2f, d(28f), profile.medal(lvl.id))
            ui.text(g, Tx.CHALLENGE.t + ": " + challenge.title, px + d(60f), y + d(26f), d(13f), Ui.TEXT, maxW = pw - d(140f))
            ui.text(g, Tx.CHALLENGE_HINT.t, px + d(60f), y + d(44f), d(10.5f), Ui.DIM, maxW = pw - d(140f), font = Font.BODY)
            y += rowH + d(8f)
        }
        val by = max(y + d(4f), py + ph - d(72f))
        ui.button(g, "fight", px + d(16f), by, pw - d(32f), d(54f), Tx.FIGHT.t, Style.PRIMARY, size = 21f) {
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
        val rows = settingsHeight()
        // What follows the controls depends on the phone: last frame's height is used.
        val content = if (settingsBottom > 0f) settingsBottom else rows + d(250f)
        val off = ui.beginScroll(g, Screen.SETTINGS.name, 0f, top, ui.w, viewH, content)
        var y = top - off + d(2f)
        ui.panel(g, x - d(4f), y, w + d(8f), rows + d(26f))
        settingsRows(g, x + d(10f), y + d(14f), w - d(20f))
        y += rows + d(40f)
        val support = game.supportUrl
        if (support != null) {
            ui.button(g, "support", x, y, w, d(54f), Tx.SUPPORT.t, Style.PRIMARY, size = 16f, icon = Icon.HEART) { game.openUrl(support) }
            y += d(72f)
        }
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
        y += d(66f)
        ui.textMid(g, Tx.VERSION.f(Game.VERSION), ui.w / 2f, y + d(6f), d(12f), Ui.FADED)
        settingsBottom = y + d(36f) - (top - off)
        ui.endScroll(g, Screen.SETTINGS.name)
    }

    private fun settingsHeight(): Float = d(70f) + d(64f) * 2 + d(52f) * 4

    /** The settings rows from the top at [y0]. */
    private fun settingsRows(g: Gfx, x: Float, y0: Float, w: Float) {
        var y = y0
        ui.caption(g, Tx.LANGUAGE.t, x + d(4f), y + d(12f))
        ui.segmented(g, "lang", x, y + d(22f), w, d(40f), listOf("Русский", "English"), I18n.lang.ordinal) {
            game.setLanguage(Language.entries[it])
        }
        y += d(72f)
        ui.slider(g, "sfx", x, y, w, d(58f), Tx.SOUND.t, profile.sound, Icon.SOUND) { profile.sound = it }
        y += d(64f)
        ui.slider(g, "music", x, y, w, d(58f), Tx.MUSIC.t, profile.music, Icon.MUSIC) { profile.music = it }
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
    }

    /** Settings as a dialog over the paused battle. */
    fun settingsPanel(g: Gfx, onBack: () -> Unit) {
        val pw = min(ui.w - d(32f), d(360f))
        val inner = settingsHeight()
        val ph = min(ui.h - ui.top - ui.bottom - d(24f), inner + d(150f))
        val px = (ui.w - pw) / 2f
        val py = ui.top + (ui.h - ui.top - ui.bottom - ph) / 2f
        val listTop = ui.panel(g, px, py, pw, ph, title = Tx.SETTINGS_BTN.t) + d(8f)
        val listH = py + ph - d(76f) - listTop
        val off = ui.beginScroll(g, "battle-settings", px, listTop, pw, listH, inner)
        settingsRows(g, px + d(16f), listTop - off, pw - d(32f))
        ui.endScroll(g, "battle-settings")
        ui.button(g, "sback", px + d(16f), py + ph - d(64f), pw - d(32f), d(48f), Tx.RESUME.t, Style.PRIMARY, icon = Icon.PLAY) { onBack() }
    }

    // --- perks ----------------------------------------------------------------------------

    fun perks(g: Gfx) {
        val top = ui.header(g, Tx.UPGRADES.t) { game.go(Screen.MENU) }
        val x = d(14f)
        val w = ui.w - d(28f)
        ui.panel(g, x, top, w, d(80f), Ui.ACCENT)
        ui.icon(g, Icon.RANKS, x + d(30f), top + d(29f), d(26f), Ui.GOLD)
        ui.textMid(g, Tx.POINTS.f(profile.freePoints), x + d(54f), top + d(29f), d(18f), Ui.GOLD, Align.LEFT, maxW = w - d(70f), font = Font.DISPLAY)
        ui.paragraph(g, Tx.POINTS_HINT.t, x + d(18f), top + d(44f), w - d(36f), d(11f), Ui.DIM, spacing = 1.2f)
        val listTop = top + d(92f)
        val rowH = d(78f)
        val bottomH = d(66f)
        val viewH = ui.h - ui.bottom - listTop - bottomH
        val content = Perk.entries.size * (rowH + d(8f))
        val off = ui.beginScroll(g, Screen.PERKS.name, 0f, listTop, ui.w, viewH, content)
        for ((i, p) in Perk.entries.withIndex()) {
            val y = listTop - off + i * (rowH + d(8f))
            if (y > listTop + viewH || y + rowH < listTop) continue
            val rank = profile.perk(p)
            ui.plate(g, x, y, w, rowH, Ui.PANEL)
            val bw = d(88f)
            val tw = w - bw - d(40f)
            ui.text(g, p.title.t, x + d(14f), y + d(25f), d(15.5f), Ui.TEXT, maxW = tw)
            ui.text(g, p.hint.t, x + d(14f), y + d(44f), d(11.5f), Ui.DIM, maxW = tw, font = Font.BODY)
            for (k in 0 until p.maxRank) {
                val rx = x + d(14f) + k * d(20f)
                if (k < rank) {
                    ui.chamfer(g, rx, y + d(54f), d(16f), d(10f), d(3f), Ui.EDGE)
                    ui.chamfer(g, rx + d(1f), y + d(55f), d(14f), d(8f), d(2.5f), Ui.ACCENT)
                } else {
                    ui.well(g, rx, y + d(54f), d(16f), d(10f), d(3f))
                }
            }
            val bx = x + w - bw - d(12f)
            if (rank >= p.maxRank) {
                ui.tag(g, Tx.MAX.t, bx + bw / 2f - g.textWidth(Tx.MAX.t, d(13f), Font.DISPLAY) / 2f - d(6f), y + rowH / 2f, d(13f), Ui.PANEL_HI, Ui.GOLD)
            } else {
                ui.button(g, "buy${p.name}", bx, y + d(20f), bw, d(38f), "${p.cost}", Style.PRIMARY, profile.freePoints >= p.cost, size = 16f, icon = Icon.STAR) {
                    if (profile.buyPerk(p)) game.sound(Sfx.UPGRADE, 0.6f)
                }
            }
        }
        ui.endScroll(g, Screen.PERKS.name)
        val by = ui.h - ui.bottom - bottomH + d(10f)
        ui.button(g, "respec", x, by, w, d(44f), Tx.RESPEC.t, Style.GHOST, profile.spent > 0, size = 14f, icon = Icon.UNDO) { profile.resetPerks() }
    }

    // --- achievements ---------------------------------------------------------------------

    fun achievements(g: Gfx) {
        val top = ui.header(g, Tx.ACHIEVEMENTS.t) { game.go(Screen.MENU) }
        val done = Achievement.entries.count { profile.achieved(it) }
        val count = Tx.ACH_COUNT.f(done, Achievement.entries.size)
        ui.tag(g, count, ui.w - d(12f), ui.top + d(30f), d(13f), Ui.PANEL_HI, Ui.GOLD, right = true)
        val listTop = top - d(4f)
        val rowH = d(72f)
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
            ui.plate(g, x, y, w, rowH, if (got) Ui.PANEL_LIGHT else Ui.PANEL, if (got) Ui.GOLD else Ui.EDGE)
            ui.well(g, x + d(10f), y + d(12f), d(44f), rowH - d(24f), d(6f))
            ui.icon(g, Icon.MEDAL, x + d(32f), y + rowH / 2f + d(1f), d(30f), if (got) Ui.GOLD else Ui.FADED)
            val tx = x + d(66f)
            val tw = w - d(78f)
            ui.text(g, a.title.t, tx, y + d(24f), d(15f), if (got) Ui.GOLD else Ui.TEXT, maxW = tw)
            ui.text(g, a.hint.t, tx, y + d(41f), d(11.5f), Ui.DIM, maxW = tw, font = Font.BODY)
            val now = min(a.progress(profile), a.goal)
            val text = "$now/${a.goal}"
            val cw = g.textWidth(text, d(11f), Font.DISPLAY)
            ui.bar(g, tx, y + d(50f), tw - cw - d(12f), d(9f), now / a.goal.toFloat(), if (got) Ui.GOLD else Ui.ACCENT)
            ui.text(g, text, tx + tw, y + d(58.5f), d(11f), if (got) Ui.GOLD else Ui.DIM, Align.RIGHT, font = Font.DISPLAY)
        }
        ui.endScroll(g, Screen.ACHIEVEMENTS.name)
    }

    // --- codex ----------------------------------------------------------------------------

    /** One card of the codex: its picture, a line about it, its numbers and longer notes. */
    private class Entry(
        val key: String,
        val known: Boolean,
        val name: String,
        val draw: (Gfx, Float, Float, Float) -> Unit,
        val hint: String,
        val stats: () -> List<String>,
        val notes: () -> List<String> = { emptyList() },
    )

    private fun codexEntries(): List<Entry> {
        val progress = profile.progress
        return when (codexTab) {
            0 -> EnemyType.entries.map { e ->
                Entry(e.key, profile.seen(e.key), e.title.t, { g, cx, cy, s -> renderer.drawEnemyIcon(g, e, cx, cy, s, clock) }, e.hint.t, {
                    buildList {
                        add(Tx.HEALTH.f(e.hp.roundToInt()))
                        add(Tx.SPEED.f(Num.one(e.speed)))
                        add(Tx.ARMOR.f(e.armor.roundToInt()))
                        add(Tx.REWARD.f(e.reward))
                        add(Tx.BASE_DAMAGE.f(e.damage))
                        if (e.boss) add(Tx.BOSS.t)
                        if (e.air) add(Tx.FLYING.t)
                        if (e.shield > 0f) add(Tx.SHIELDED.f(Num.percent(e.shield)))
                        if (e.stealth) add(Tx.STEALTHY.t)
                    }
                })
            }
            1 -> TowerType.entries.map { t ->
                val b = t.branches
                Entry(
                    t.key, t.unlock <= progress, t.title.t, { g, cx, cy, s -> renderer.drawTowerIcon(g, t, 2, cx, cy, s, clock) }, t.hint.t,
                    { listOf(Tx.COST.f(t.levels[0].cost)) },
                    { listOf(b[0].title.t + ": " + b[0].hint.t, b[1].title.t + ": " + b[1].hint.t) },
                )
            }
            else -> Ability.entries.map { a ->
                Entry(a.key, a.unlock <= progress, a.title.t, { g, cx, cy, s -> renderer.drawAbilityIcon(g, a, cx, cy, s * 0.7f, Ui.TEXT) }, a.hint.t, {
                    listOf(Tx.COOLDOWN.f(a.cooldown.roundToInt()))
                })
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
        ui.segmented(g, "ctab", d(12f), top, ui.w - d(24f), d(40f), listOf(Tx.TAB_ENEMIES.t, Tx.TAB_TOWERS.t, Tx.TAB_ABILITIES.t), codexTab, size = 13f) {
            codexTab = it
            codexPick = -1
            ui.resetScroll(Screen.CODEX.name)
        }
        val entries = codexEntries()
        val detailH = d(196f)
        val gridTop = top + d(52f)
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
            ui.plate(g, x, y, cw, ch, if (picked) Ui.PANEL_LIGHT else Ui.PANEL, if (picked) Ui.ACCENT else Ui.EDGE)
            if (e.known) {
                e.draw(g, x + cw / 2f, y + cw / 2f + d(2f), cw * 0.6f)
                ui.textMid(g, e.name, x + cw / 2f, y + ch - d(13f), d(10.5f), Ui.TEXT, maxW = cw - d(8f))
            } else {
                ui.textMid(g, "?", x + cw / 2f, y + cw / 2f, d(28f), Ui.FADED, font = Font.DISPLAY)
                ui.textMid(g, "???", x + cw / 2f, y + ch - d(13f), d(10.5f), Ui.FADED)
            }
        }
        ui.endScroll(g, Screen.CODEX.name)

        val dy = ui.h - ui.bottom - detailH
        ui.panel(g, d(10f), dy, ui.w - d(20f), detailH - d(10f))
        val pick = entries.getOrNull(codexPick)
        if (pick == null) {
            ui.textMid(g, Tx.TAP_FOR_DETAILS.t, ui.w / 2f, dy + detailH / 2f - d(4f), d(13f), Ui.DIM, maxW = ui.w - d(56f))
            return
        }
        if (!pick.known) {
            val text = if (codexTab == 0) Tx.NOT_MET.t else Tx.UNLOCKS_ON.f(unlockLevel(pick.key))
            ui.lock(g, ui.w / 2f, dy + detailH / 2f - d(26f), d(24f), Ui.FADED)
            ui.textMid(g, text, ui.w / 2f, dy + detailH / 2f + d(10f), d(14f), Ui.DIM, maxW = ui.w - d(56f))
            return
        }
        val photo = d(76f)
        ui.well(g, d(26f), dy + d(22f), photo, photo, d(6f))
        pick.draw(g, d(26f) + photo / 2f, dy + d(22f) + photo / 2f + d(1f), d(54f))
        val tx = d(26f) + photo + d(14f)
        val tw = ui.w - tx - d(28f)
        ui.text(g, pick.name, tx, dy + d(38f), d(18f), Ui.TEXT, maxW = tw, font = Font.DISPLAY)
        var y = dy + d(46f)
        y += ui.paragraph(g, pick.hint, tx, y, tw, d(11.5f), Ui.TEXT, spacing = 1.2f) + d(6f)
        val left = d(26f)
        val wide = ui.w - left - d(28f)
        y = max(y, dy + d(22f) + photo + d(10f))
        y += ui.chips(g, pick.stats(), left, y, wide, size = d(10.5f), wrap = true) + d(6f)
        for (note in pick.notes()) {
            if (y > dy + detailH - d(30f)) break
            y += ui.paragraph(g, note, left, y, wide, d(10.5f), Ui.DIM, spacing = 1.15f)
        }
    }

    // --- daily ----------------------------------------------------------------------------

    fun daily(g: Gfx) {
        val top = ui.header(g, Tx.DAILY.t) { game.go(Screen.MENU) }
        val day = game.today
        val level = Levels.daily(day, profile.progress)
        val mods = Mod.forDay(day)
        val dateText = date(day)
        ui.tag(g, dateText, ui.w - d(12f), ui.top + d(30f), d(12f), Ui.PANEL_HI, Ui.TEXT, right = true)
        val x = d(14f)
        val w = ui.w - d(28f)
        val thumbH = min(d(230f), (ui.h - ui.bottom - top) * 0.34f)
        val thumbW = thumbH * COLS / ROWS
        val ty = top + d(4f)
        framedThumb(g, thumb(level), (ui.w - thumbW) / 2f, ty, thumbW, thumbH)
        var y = ty + thumbH + d(12f)
        val source = Levels.all.firstOrNull { it.ascii == level.ascii }
        if (source != null) ui.textMid(g, Tx.DAILY_MAP.f(source.name.t), ui.w / 2f, y + d(6f), d(12f), Ui.DIM)
        y += d(22f)
        ui.caption(g, Tx.DAILY_TWISTS.t, x + d(4f), y + d(10f), Ui.ACCENT)
        y += d(18f)
        for (m in mods) {
            ui.plate(g, x, y, w, d(52f), Ui.PANEL)
            ui.icon(g, Icon.WARNING, x + d(24f), y + d(25f), d(22f), Ui.ACCENT)
            ui.text(g, m.title.t, x + d(46f), y + d(22f), d(14.5f), Ui.TEXT, maxW = w - d(58f))
            ui.text(g, m.hint.t, x + d(46f), y + d(39f), d(11.5f), Ui.DIM, maxW = w - d(58f), font = Font.BODY)
            y += d(58f)
        }
        y += d(6f)
        val won = profile.dailyWon(day)
        val status = if (won) Tx.DAILY_WON.t else Tx.DAILY_BEST.f(profile.dailyBest(day))
        if (won) ui.icon(g, Icon.CHECK, ui.w / 2f - g.textWidth(status, d(14f)) / 2f - d(14f), y + d(8f), d(16f), Ui.GREEN)
        ui.textMid(g, status, ui.w / 2f, y + d(8f), d(14f), if (won) Ui.GREEN else Ui.TEXT, maxW = w)
        y += d(24f)
        val streak = Tx.STREAK.f(profile.dailyStreak(day))
        y += ui.chips(g, listOf(streak), (ui.w - g.textWidth(streak, d(11f)) - d(12f)) / 2f, y, w, Ui.ACCENT) + d(6f)
        val reward = if (game.vip) Tx.DAILY_REWARD_VIP.t else Tx.DAILY_REWARD.t
        ui.textMid(g, reward, ui.w / 2f, y + d(8f), d(11.5f), Ui.DIM, maxW = w, font = Font.BODY)
        val by = max(y + d(26f), ui.h - ui.bottom - d(72f))
        ui.button(g, "dstart", x, by, w, d(56f), Tx.START.t, Style.PRIMARY, size = 20f, icon = Icon.PLAY) { game.startDaily() }
    }

    // --- VIP ------------------------------------------------------------------------------

    /** The crown in the main menu's corner: gold when VIP is on. */
    private fun vipBadge(g: Gfx) {
        val on = game.vip
        val bw = d(84f)
        val bh = d(32f)
        val bx = ui.w - d(12f) - bw
        val top = ui.top + d(8f)
        ui.area("vip", bx, top, bw, bh) {
            game.checkVip()
            open(Screen.VIP)
        }
        val by = top + if (ui.down("vip")) d(2f) else 0f
        ui.chamfer(g, bx, by, bw, bh, d(7f), Ui.EDGE)
        if (on) {
            ui.chamfer(g, bx + d(1f), by + d(1f), bw - d(2f), bh - d(2f), d(7f), Ui.ACCENT_LIT)
            ui.chamfer(g, bx + d(1f), by + d(2.5f), bw - d(2f), bh - d(3.5f), d(7f), Ui.GOLD)
        } else {
            ui.chamfer(g, bx + d(1f), by + d(1f), bw - d(2f), bh - d(2f), d(7f), Ui.BAND)
            ui.outline(g, bx + d(2.5f), by + d(2.5f), bw - d(5f), bh - d(5f), d(6f), Ui.GOLD, d(1.5f))
        }
        val ink = if (on) Ui.DARK_TEXT else Ui.GOLD
        ui.icon(g, Icon.CROWN, bx + d(24f), by + bh / 2f, d(18f), ink)
        ui.textMid(g, Tx.VIP.t, bx + d(38f), by + bh / 2f, d(15f), ink, Align.LEFT, font = Font.DISPLAY)
    }

    /** What VIP changes, row by row: the name, without VIP, with VIP (null draws a tick). */
    private fun vipRows(): List<Triple<String, String, String?>> {
        val free = Rules()
        val vip = Rules(vip = true)
        fun percent(k: Float): Int = Math.round(abs(k - 1f) * 100f)
        return listOf(
            Triple(Tx.VIP_ROW_START.t, "—", "+${percent(Vip.START_MONEY)}%"),
            Triple(Tx.VIP_ROW_BOUNTY.t, "—", "+${percent(Vip.BOUNTY)}%"),
            Triple(Tx.VIP_ROW_COOLDOWN.t, "—", "−${percent(Vip.COOLDOWN)}%"),
            Triple(Tx.VIP_ROW_SPEED.t, Tx.VIP_UP_TO.f(free.maxSpeed), Tx.VIP_UP_TO.f(vip.maxSpeed)),
            Triple(Tx.VIP_ROW_UNDO.t, Tx.VIP_SECONDS.f(free.undoTime.toInt()), Tx.VIP_SECONDS.f(vip.undoTime.toInt())),
            Triple(Tx.VIP_ROW_DAILY.t, "1", "${Vip.DAILY_POINTS}"),
            Triple(Tx.VIP_ROW_CROWN.t, "—", null),
        )
    }

    fun vip(g: Gfx) {
        val top = ui.header(g, Tx.VIP.t) { game.go(Screen.MENU) }
        val active = game.vip
        val shop = game.vipShop
        val busy = clock - vipBusyAt < VIP_WAIT
        val x = d(14f)
        val w = ui.w - d(28f)
        val cx = ui.w / 2f
        val viewH = ui.h - ui.bottom - top
        val off = ui.beginScroll(g, Screen.VIP.name, 0f, top, ui.w, viewH, if (vipBottom > 0f) vipBottom else viewH)
        var y = top - off

        // The crown, and the price or how long VIP lasts.
        g.glow(cx, y + d(42f), d(74f), Colors.alpha(Ui.GOLD, 0.22f))
        ui.icon(g, Icon.CROWN, cx, y + d(42f), d(60f), Ui.GOLD)
        ui.textMid(g, Tx.VIP_TITLE.t, cx, y + d(96f), d(24f), Ui.GOLD, font = Font.DISPLAY)
        val line = if (active) Tx.VIP_ACTIVE.f(date(profile.vipUntil)) else Tx.VIP_PRICE.f(Vip.PRICE_RUBLES)
        ui.textMid(g, line, cx, y + d(124f), d(16f), if (active) Ui.GREEN else Ui.TEXT)
        y += d(146f)

        // Without and with VIP, side by side.
        val rows = vipRows()
        val rowH = d(40f)
        val headH = d(38f)
        val tableH = headH + rows.size * rowH + d(12f)
        ui.panel(g, x, y, w, tableH)
        val freeX = x + w * 0.62f
        val vipX = x + w * 0.85f
        val colW = w * 0.25f
        ui.chamfer(g, vipX - colW / 2f, y + d(8f), colW, tableH - d(16f), d(6f), Colors.alpha(Ui.GOLD, 0.1f))
        ui.outline(g, vipX - colW / 2f, y + d(8f), colW, tableH - d(16f), d(6f), Colors.alpha(Ui.GOLD, 0.55f), d(1.5f))
        ui.textMid(g, Tx.VIP_WITHOUT.t.uppercase(), freeX, y + d(25f), d(11f), Ui.DIM, maxW = colW)
        ui.icon(g, Icon.CROWN, vipX - d(16f), y + d(24f), d(15f), Ui.GOLD)
        ui.textMid(g, Tx.VIP.t, vipX + d(9f), y + d(25f), d(14f), Ui.GOLD, font = Font.DISPLAY)
        var ry = y + headH
        for ((i, row) in rows.withIndex()) {
            if (i > 0) g.fillRect(x + d(14f), ry, w - d(28f), max(1f, d(1f)), Colors.alpha(Ui.PANEL_HI, 0.7f))
            val my = ry + rowH / 2f
            ui.textMid(g, row.first, x + d(16f), my, d(13f), Ui.TEXT, Align.LEFT, maxW = freeX - colW / 2f - x - d(20f))
            ui.textMid(g, row.second, freeX, my, d(13.5f), Ui.DIM, maxW = colW - d(8f))
            val with = row.third
            if (with == null) ui.icon(g, Icon.CHECK, vipX, my, d(18f), Ui.GOLD) else ui.textMid(g, with, vipX, my, d(15f), Ui.GOLD, maxW = colW - d(8f))
            ry += rowH
        }
        y += tableH + d(16f)

        // Buying it, or getting back one paid before.
        if (!active) {
            val here = shop == VipShop.HERE
            val sub = if (here) Tx.VIP_PRICE.f(Vip.PRICE_RUBLES) else Tx.VIP_ELSEWHERE.t
            ui.button(g, "vipbuy", x, y, w, d(58f), Tx.VIP_BUY.t, Style.PRIMARY, here && !busy, size = 19f, icon = Icon.CROWN, sub = sub) {
                vipBusyAt = clock
                game.buyVip { vipBusyAt = -100f }
            }
            y += d(74f)
        }
        if (shop == VipShop.HERE) {
            ui.button(g, "viprestore", x, y, w, d(44f), Tx.VIP_RESTORE.t, Style.GHOST, !busy, size = 13f, icon = Icon.UNDO) {
                vipBusyAt = clock
                game.checkVip { found ->
                    vipBusyAt = -100f
                    game.toast(if (found) Tx.VIP_RESTORED.t else Tx.VIP_NOT_FOUND.t, if (found) Icon.CROWN else Icon.WARNING)
                }
            }
            y += d(58f)
            y += ui.paragraph(g, Tx.VIP_NOTE.t, cx, y, w - d(16f), d(11.5f), Ui.DIM, Align.CENTER)
        }
        vipBottom = y + d(20f) - (top - off)
        ui.endScroll(g, Screen.VIP.name)
    }

    private companion object {
        /** Seconds the store gets to answer before the VIP buttons work again. */
        const val VIP_WAIT = 30f
    }
}
