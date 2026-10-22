package com.makc.basedefense.engine

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

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
    private var commandersBottom = 0f

    /** A window over the main menu: the tutorial on the first visit, a new version, reminders once, VIP now and then. */
    private enum class Popup { WELCOME, NEWS, UPDATE, REMINDERS, VIP, ENDED, SUPPLY }

    /** The new version is offered once a run of the game. */
    private var updateOffered = false

    private var popup: Popup? = null
    private var popupAt = 0f

    /** When the store was last asked to sell or restore something; the shop buttons wait for its answer. */
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

    /** A fresh visit to the main menu: maybe a window is due, shown after a moment. */
    fun enterMenu() {
        if (popup == null) popup = pickPopup()
        popupAt = clock + POPUP_DELAY
    }

    fun menu(g: Gfx) {
        if (profile.vipGift(game.today)) game.toast(Tx.VIP_GIFT.f(Vip.DAILY_GIFT), Icon.CROWN)
        drawDemoBackground(g)
        val cx = ui.w / 2f
        val big = min(d(52f), ui.w / 6.6f)
        val ty = ui.top + max(d(70f), ui.h * 0.125f)
        val maxW = ui.w - d(48f)
        ui.text(g, Tx.TITLE_1.t, cx + d(3f), ty + d(4f), big, Ui.EDGE, Align.CENTER, maxW, Font.DISPLAY)
        ui.text(g, Tx.TITLE_1.t, cx, ty, big, Ui.TEXT, Align.CENTER, maxW, Font.DISPLAY)
        ui.tape(g, Tx.TITLE_2.t, cx, ty + big * 0.8f, ui.fit(g, Tx.TITLE_2.t, big * 0.9f, maxW - big, Font.DISPLAY), -3f)
        ui.textMid(g, Tx.TAGLINE.t.uppercase(), cx, ty + big * 1.82f, d(12f), Ui.DIM, maxW = maxW)
        if (game.shop != Shop.NONE || game.vip) vipBadge(g)
        passBadge(g)

        val saved = savedBattle()
        val bottomRow = ui.h - ui.bottom - d(128f)
        val top = ty + big * 2.3f
        val count = if (saved != null) 6 else 5
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
            world = ((profile.progress - 1) / Levels.PER_WORLD).coerceIn(0, Biome.entries.size - 1)
            sheet = null
            open(Screen.LEVELS)
        }
        y += bh + gap
        val dailyOpen = profile.dailyUnlocked
        ui.button(
            g, "daily", bx, y, bw, bh, Tx.MISSIONS.t, enabled = dailyOpen, size = 16f,
            icon = if (dailyOpen) Icon.CALENDAR else Icon.LOCK, sub = if (dailyOpen) null else Tx.DAILY_LOCKED.t,
        ) { open(Screen.DAILY) }
        val weekDue = profile.weeklyUnlocked && profile.weeklyMedal(Weekly.week(game.today)) == 0
        val ordersDue = profile.ordersToTake(game.today) > 0 || profile.supplyReady(game.today)
        if (dailyOpen && (!profile.dailyWon(game.today) || weekDue || ordersDue)) {
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
        y += bh + gap
        val leader = profile.battleCommander
        ui.button(g, "commanders", bx, y, bw, bh, Tx.COMMANDERS.t, size = 16f, icon = Icon.COMMANDER, sub = Tx.COMMANDERS_SUB.f(leader.title.t)) {
            open(Screen.COMMANDERS)
        }

        // The tutorial, looks, codex, achievements and settings along the bottom.
        val icons = listOf(
            Triple("tutorial", Tx.TUTORIAL.t, Icon.INFO),
            Triple("wardrobe", Tx.WARDROBE_BTN.t, Icon.BRUSH),
            Triple("codex", Tx.CODEX.t, Icon.BOOK),
            Triple("ach", Tx.ACHIEVEMENTS.t, Icon.TROPHY),
            Triple("settings", Tx.SETTINGS.t, Icon.GEAR),
        )
        val gapX = d(8f)
        val iconW = min(d(80f), (ui.w - d(28f) - gapX * (icons.size - 1)) / icons.size)
        val rowX = (ui.w - iconW * icons.size - gapX * (icons.size - 1)) / 2f
        for ((i, item) in icons.withIndex()) {
            val x = rowX + i * (iconW + gapX)
            ui.button(g, item.first, x, bottomRow, iconW, d(52f), "", icon = item.third) {
                when (item.first) {
                    "tutorial" -> game.startTutorial()
                    "wardrobe" -> open(Screen.WARDROBE)
                    "codex" -> {
                        codexPick = -1
                        open(Screen.CODEX)
                    }
                    "ach" -> open(Screen.ACHIEVEMENTS)
                    else -> open(Screen.SETTINGS)
                }
            }
            ui.textMid(g, item.second, x + iconW / 2f, bottomRow + d(70f), d(11f), Ui.DIM, maxW = iconW + d(2f))
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
        val p = popup
        if (p != null && clock >= popupAt) drawPopup(g, p)
    }

    /** Which window the menu should open now, if any; each is remembered so it does not nag. */
    private fun pickPopup(): Popup? {
        if (!profile.tutorialOffered && profile.totalStars == 0) {
            profile.tutorialOffered = true
            profile.newsSeen = Game.VERSION
            return Popup.WELCOME
        }
        // Who played an earlier version hears once what this one brings.
        if (profile.newsSeen != Game.VERSION) {
            profile.newsSeen = Game.VERSION
            if (profile.totalStars > 0) return Popup.NEWS
        }
        if (game.updateOut && !updateOffered) {
            updateOffered = true
            return Popup.UPDATE
        }
        // Supplies are offered once a day; not taken, they wait on the orders tab.
        if (profile.stat(Stat.WINS) >= 1 && profile.supplyReady(game.today) && profile.supplyOffered != game.today) {
            profile.supplyOffered = game.today
            return Popup.SUPPLY
        }
        if (!profile.remindersAsked && profile.reminders && !game.remindersAllowed && profile.stat(Stat.WINS) >= 1) {
            profile.remindersAsked = true
            return Popup.REMINDERS
        }
        if (game.shop == Shop.NONE || game.vip) return null
        val today = game.today
        val until = profile.vipUntil
        if (until > 0 && today - until in 1..ENDED_DAYS && profile.endedShown != until) {
            profile.endedShown = until
            // It counts as the offer too, so the next one waits its days.
            profile.promoDay = today
            return Popup.ENDED
        }
        if (profile.totalStars < PROMO_STARS || today - profile.promoDay < PROMO_GAP) return null
        profile.promoDay = today
        return Popup.VIP
    }

    private fun drawPopup(g: Gfx, p: Popup) {
        ui.begin()
        g.fillRect(0f, 0f, ui.w, ui.h, Ui.SCRIM)
        ui.area("popupScrim", 0f, 0f, ui.w, ui.h, silent = true) {}
        val pw = min(ui.w - d(36f), d(350f))
        val px = (ui.w - pw) / 2f
        val tw = pw - d(48f)
        val list = when (p) {
            Popup.VIP -> listOf(
                Tx.PROMO_RAIL.t,
                Tx.PROMO_1.t,
                Tx.PROMO_2.f(Vip.RESERVE_LIVES),
                Tx.PROMO_3.f(Math.round((Vip.START_MONEY - 1f) * 100f), Math.round((Vip.BOUNTY - 1f) * 100f)),
                Tx.PROMO_4.f(Vip.MAX_SPEED),
            )
            Popup.NEWS -> Tx.NEWS.map { it.t }
            else -> emptyList()
        }
        val text = when (p) {
            Popup.WELCOME -> Tx.WELCOME_TEXT.t
            Popup.UPDATE -> Tx.UPDATE_TEXT.t
            Popup.REMINDERS -> Tx.REMINDERS_TEXT.t
            Popup.ENDED -> Tx.ENDED_TEXT.t
            Popup.SUPPLY -> Tx.SUPPLY_TEXT.t
            Popup.VIP, Popup.NEWS -> null
        }
        val textH = if (text != null) ui.lines(g, text, d(13.5f), tw).size * d(13.5f) * 1.3f else 0f
        val listH = list.sumOf { ui.lines(g, it, d(12.5f), tw - d(28f)).size.toDouble() }.toFloat() * d(12.5f) * 1.25f + list.size * d(12f)
        val ph = d(56f) + d(110f) + textH + listH + d(140f)
        val py = max(ui.top + d(12f), (ui.h - ph) / 2f)
        ui.area("popup", px, py, pw, ph, silent = true) {}
        val title = when (p) {
            Popup.WELCOME -> Tx.WELCOME_TITLE.t
            Popup.NEWS -> Tx.NEWS_TITLE.f(Game.VERSION)
            Popup.UPDATE -> Tx.UPDATE_TITLE.t
            Popup.REMINDERS -> Tx.REMINDERS_TITLE.t
            Popup.VIP -> Tx.VIP_TITLE.t
            Popup.ENDED -> Tx.ENDED_TITLE.t
            Popup.SUPPLY -> Tx.SUPPLY_TITLE.t
        }
        val vipLike = p == Popup.VIP || p == Popup.ENDED
        val top = ui.panel(g, px, py, pw, ph, if (vipLike) Ui.GOLD else Ui.EDGE, title = title)
        val cx = ui.w / 2f
        var y = top + d(14f)
        if (p == Popup.WELCOME) {
            ui.well(g, cx - d(40f), y, d(80f), d(80f), d(8f))
            renderer.drawCommanderIcon(g, CommanderType.SERGEANT, cx, y + d(40f), d(66f))
        } else if (p == Popup.NEWS) {
            ui.well(g, cx - d(40f), y, d(80f), d(80f), d(8f))
            renderer.drawEnemyIcon(g, EnemyType.AIRSHIP, cx, y + d(40f), d(70f), clock)
        } else if (p == Popup.SUPPLY) {
            supplyRow(g, px + d(18f), y + d(6f), pw - d(36f), d(70f))
        } else {
            val icon = when (p) {
                Popup.UPDATE -> Icon.DOWNLOAD
                Popup.REMINDERS -> Icon.PHONE
                else -> Icon.CROWN
            }
            g.glow(cx, y + d(40f), d(62f), Colors.alpha(if (vipLike) Ui.GOLD else Ui.ACCENT, 0.25f))
            ui.icon(g, icon, cx, y + d(40f), d(56f), if (p == Popup.ENDED) Ui.DIM else if (vipLike) Ui.GOLD else Ui.ACCENT)
        }
        y += d(96f)
        if (text != null) y += ui.paragraph(g, text, cx, y, tw, d(13.5f), Ui.TEXT, Align.CENTER, spacing = 1.3f)
        for (line in list) {
            ui.icon(g, Icon.CHECK, px + d(34f), y + d(8f), d(15f), if (vipLike) Ui.GOLD else Ui.ACCENT)
            y += ui.paragraph(g, line, px + d(50f), y, tw - d(28f), d(12.5f), Ui.TEXT, spacing = 1.25f) + d(12f)
        }
        val bx = px + d(22f)
        val bw = pw - d(44f)
        val by = py + ph - d(126f)
        val here = game.shop == Shop.HERE
        val price = if (here) Tx.VIP_PRICE.f(Product.VIP_MONTH.rubles) else Tx.VIP_ELSEWHERE.t
        when (p) {
            Popup.WELCOME -> ui.button(g, "popupGo", bx, by, bw, d(56f), Tx.WELCOME_GO.t, Style.PRIMARY, size = 17f, icon = Icon.PLAY) {
                popup = null
                game.startTutorial()
            }
            Popup.NEWS -> ui.button(g, "popupGo", bx, by, bw, d(56f), Tx.NEWS_GO.t, Style.PRIMARY, size = 17f, icon = Icon.FLAG) {
                popup = null
                world = ((profile.progress - 1) / Levels.PER_WORLD).coerceIn(0, Biome.entries.size - 1)
                sheet = null
                open(Screen.LEVELS)
            }
            Popup.UPDATE -> ui.button(g, "popupGo", bx, by, bw, d(56f), Tx.UPDATE_GO.t, Style.PRIMARY, size = 17f, icon = Icon.DOWNLOAD) {
                popup = null
                game.startUpdate()
            }
            Popup.REMINDERS -> ui.button(g, "popupGo", bx, by, bw, d(56f), Tx.REMINDERS_ON.t, Style.PRIMARY, size = 17f, icon = Icon.PHONE) {
                popup = null
                game.allowReminders {}
            }
            Popup.SUPPLY -> ui.button(g, "popupGo", bx, by, bw, d(56f), Tx.PASS_TAKE.t, Style.PRIMARY, size = 17f, icon = Icon.STAR) {
                popup = null
                takeSupply()
            }
            Popup.VIP, Popup.ENDED -> ui.button(
                g, "popupGo", bx, by, bw, d(56f), if (p == Popup.VIP) Tx.PROMO_MORE.t else Tx.ENDED_RENEW.t, Style.PRIMARY, size = 17f, icon = Icon.CROWN, sub = price,
            ) {
                popup = null
                game.checkPurchases()
                open(Screen.VIP)
            }
        }
        val later = when (p) {
            Popup.WELCOME -> Tx.WELCOME_SKIP.t
            Popup.REMINDERS -> Tx.REMINDERS_NO.t
            else -> Tx.PROMO_LATER.t
        }
        ui.button(g, "popupLater", bx, by + d(68f), bw, d(44f), later, Style.GHOST, size = 14f) {
            if (p == Popup.REMINDERS) profile.reminders = false
            popup = null
        }
    }

    // --- levels ---------------------------------------------------------------------------

    private fun worldOpen(i: Int): Boolean = profile.unlocked(i * Levels.PER_WORLD + 1)

    fun levels(g: Gfx) {
        val headerBottom = ui.header(g, Tx.CAMPAIGN.t) { game.go(Screen.MENU) }
        starTotal(g, ui.w - d(12f), ui.top + d(30f))

        val tabs = Biome.entries.map { it.short.t }
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
        val hardOpen = profile.hardOpen(lvl.id)
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
        ui.button(g, "promo", x, y, w, d(50f), Tx.PROMO.t, Style.NORMAL, size = 15f, icon = Icon.STAR) { game.enterPromo() }
        y += d(64f)
        ui.button(g, "story", x, y, w, d(50f), Tx.STORY_TITLE.t, Style.NORMAL, size = 15f, icon = Icon.BOOK) { game.tellStory(Screen.SETTINGS) }
        y += d(64f)
        if (profile.adminKnown) {
            ui.toggle(g, "admin", x, y, w, d(46f), Tx.ADMIN_MODE.t, profile.admin) { game.setAdmin(it) }
            y += d(58f)
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

    private fun settingsHeight(): Float = d(70f) + d(64f) * 2 + d(52f) * 5

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
        y += d(52f)
        ui.toggle(g, "remind", x, y, w, d(46f), Tx.REMINDERS.t, profile.reminders && game.remindersAllowed) { on ->
            if (on) game.allowReminders {} else profile.reminders = false
        }
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
        // Once a day an advert brings a point, for players without VIP.
        val adPoint = game.adsOffered && !profile.adPointTaken(game.today)
        val rw = if (adPoint) (w - d(10f)) / 2f else w
        ui.button(g, "respec", x, by, rw, d(44f), Tx.RESPEC.t, Style.GHOST, profile.spent > 0, size = 14f, icon = Icon.UNDO) { profile.resetPerks() }
        if (adPoint) {
            val label = if (game.adReady) Tx.AD_POINT.f(Ads.DAILY_POINTS) else Tx.AD_WAIT.t
            ui.button(g, "adPoint", x + rw + d(10f), by, rw, d(44f), label, Style.PRIMARY, game.adReady, size = 14f, icon = Icon.VIDEO) {
                game.watchAd { profile.takeAdPoint(game.today) }
            }
        }
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
        /** Only VIP brings it: a crown on its card. */
        val crown: Boolean = false,
    )

    private fun codexEntries(): List<Entry> {
        val progress = profile.progress
        return when (codexTab) {
            0 -> EnemyType.entries.map { e ->
                Entry(e.key, profile.known(e.key), e.title.t, { g, cx, cy, s -> renderer.drawEnemyIcon(g, e, cx, cy, s, clock) }, e.hint.t, {
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
                        if (e.fireproof) add(Tx.FIREPROOF.t)
                        if (e.burrows) add(Tx.BURROWS.t)
                        if (e.unstoppable) add(Tx.UNSTOPPABLE.t)
                        if (e.nimble) add(Tx.NIMBLE.t)
                    }
                })
            }
            1 -> TowerType.entries.map { t ->
                val b = t.branches
                Entry(
                    t.key, t.unlock <= progress, t.title.t, { g, cx, cy, s -> renderer.drawTowerIcon(g, t, 2, cx, cy, s, clock) }, t.hint.t,
                    { listOf(Tx.COST.f(t.levels[0].cost)) },
                    {
                        if (b.isEmpty()) listOf(Tx.RAIL_NOTE.f(World.MAX_RAILS)) else listOf(b[0].title.t + ": " + b[0].hint.t, b[1].title.t + ": " + b[1].hint.t)
                    },
                    crown = t.vip,
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
                if (e.crown) ui.icon(g, Icon.CROWN, x + d(12f), y + d(12f), d(14f), Ui.GOLD)
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

    /** The missions screen: today's challenge or this week's operation. */
    private var missionsTab = 0

    fun daily(g: Gfx) {
        val header = ui.header(g, Tx.MISSIONS.t) { game.go(Screen.MENU) }
        val tabs = listOf(Tx.TAB_DAY.t, Tx.TAB_WEEK.t, Tx.TAB_ORDERS.t, Tx.TAB_BOSSES.t)
        ui.segmented(g, "missions", d(14f), header, ui.w - d(28f), d(40f), tabs, missionsTab, size = 13f) {
            missionsTab = it
        }
        val due = profile.ordersToTake(game.today) > 0 || profile.supplyReady(game.today)
        if (due) {
            val tx = d(14f) + (ui.w - d(28f)) * 5f / 8f + g.textWidth(Tx.TAB_ORDERS.t, d(13f), Font.DISPLAY) / 2f + d(8f)
            g.fillCircle(tx, header + d(10f), d(6f), Ui.EDGE)
            g.fillCircle(tx, header + d(10f), d(4.5f), Ui.RED)
        }
        val top = header + d(50f)
        when (missionsTab) {
            1 -> weekly(g, top)
            2 -> ordersTab(g, top)
            3 -> bossesTab(g, top)
            else -> dailyTab(g, top)
        }
    }

    /** Opens the missions screen on the orders. */
    fun openOrders() {
        missionsTab = 2
        open(Screen.DAILY)
    }

    /** Today's supplies, then the three orders of the day and the bonus for all of them. */
    private fun ordersTab(g: Gfx, top: Float) {
        val day = game.today
        val x = d(14f)
        val w = ui.w - d(28f)
        val viewH = ui.h - ui.bottom - top
        val off = ui.beginScroll(g, "orders", 0f, top, ui.w, viewH, if (ordersBottom > 0f) ordersBottom else viewH)
        var y = top - off + d(4f)

        // Supplies.
        val ready = profile.supplyReady(day)
        val next = Supply.REWARDS[profile.supplyDay]
        ui.plate(g, x, y, w, d(76f), Ui.PANEL)
        ui.icon(g, rewardIcon(next.first()), x + d(28f), y + d(38f), d(26f), if (ready) Ui.GOLD else Ui.DIM)
        ui.text(g, Tx.SUPPLY_TITLE.t, x + d(52f), y + d(24f), d(15f), Ui.TEXT, maxW = w - d(200f), font = Font.DISPLAY)
        val what = next.joinToString(", ") { game.describe(it) }
        ui.text(g, Tx.SUPPLY_DAY.f(profile.supplyDay + 1, Supply.DAYS), x + d(52f), y + d(43f), d(11.5f), Ui.ACCENT, maxW = w - d(200f))
        ui.text(g, if (ready) what else Tx.SUPPLY_TOMORROW.f(what), x + d(52f), y + d(60f), d(11f), Ui.DIM, maxW = w - d(200f), font = Font.BODY)
        if (ready) {
            ui.button(g, "supplyTake", x + w - d(132f), y + d(16f), d(120f), d(44f), Tx.PASS_TAKE.t, Style.PRIMARY, size = 14f) { takeSupply() }
        } else {
            ui.icon(g, Icon.CHECK, x + w - d(30f), y + d(38f), d(22f), Ui.GREEN)
        }
        y += d(90f)

        ui.text(g, Tx.ORDERS_TITLE.t, x + d(4f), y + d(16f), d(17f), Ui.GOLD, maxW = w, font = Font.DISPLAY)
        y += d(26f)
        y += ui.paragraph(g, Tx.ORDERS_SUB.t, x + d(4f), y, w - d(8f), d(11.5f), Ui.DIM) + d(10f)
        val orders = profile.orders(day)
        for ((i, o) in orders.withIndex()) {
            val count = profile.orderCount(day, i)
            val done = count >= o.goal
            val taken = profile.orderTaken(day, i)
            ui.plate(g, x, y, w, d(92f), if (done && !taken) Ui.PANEL_LIGHT else Ui.PANEL, if (done && !taken) Ui.GOLD else Ui.EDGE)
            val textW = w - d(150f)
            ui.paragraph(g, o.text, x + d(14f), y + d(8f), textW, d(14f), Ui.TEXT, spacing = 1.2f, font = Font.BOLD)
            ui.bar(g, x + d(14f), y + d(64f), textW - d(50f), d(8f), count / o.goal.toFloat(), if (done) Ui.GREEN else Ui.ACCENT)
            ui.text(g, "${min(count, o.goal)}/${o.goal}", x + d(14f) + textW - d(42f), y + d(72f), d(11.5f), Ui.DIM)
            val rx = x + w - d(128f)
            ui.icon(g, rewardIcon(o.reward), rx + d(12f), y + d(22f), d(18f), Ui.ACCENT)
            ui.text(g, rewardValue(o.reward), rx + d(26f), y + d(27f), d(14f), Ui.TEXT, maxW = d(96f), font = Font.DISPLAY)
            when {
                taken -> {
                    ui.icon(g, Icon.CHECK, rx + d(12f), y + d(62f), d(18f), Ui.GREEN)
                    ui.text(g, Tx.ORDER_TAKEN.t, rx + d(26f), y + d(67f), d(11f), Ui.GREEN, maxW = d(96f))
                }
                done -> ui.button(g, "order$i", rx, y + d(44f), d(116f), d(38f), Tx.PASS_TAKE.t, Style.PRIMARY, size = 13f) { takeOrder(i) }
                else -> ui.text(g, rewardCaption(o.reward), rx + d(2f), y + d(66f), d(10.5f), Ui.DIM, maxW = d(116f), font = Font.BODY)
            }
            y += d(100f)
        }
        val all = orders.indices.all { profile.orderDone(day, it) }
        val bonusTaken = profile.bonusTaken(day)
        ui.plate(g, x, y, w, d(62f), if (all && !bonusTaken) Ui.PANEL_LIGHT else Ui.PANEL, if (all && !bonusTaken) Ui.GOLD else Ui.EDGE)
        ui.icon(g, Icon.STAR, x + d(26f), y + d(31f), d(22f), if (all) Ui.GOLD else Ui.DIM)
        ui.text(g, Tx.ORDERS_BONUS.t, x + d(48f), y + d(27f), d(14f), Ui.TEXT, maxW = w - d(190f))
        ui.text(g, game.describe(Reward(RewardKind.POINTS, Orders.BONUS_POINTS)), x + d(48f), y + d(45f), d(11.5f), Ui.ACCENT, maxW = w - d(190f))
        when {
            bonusTaken -> ui.icon(g, Icon.CHECK, x + w - d(30f), y + d(31f), d(22f), Ui.GREEN)
            all -> ui.button(g, "orderBonus", x + w - d(128f), y + d(12f), d(116f), d(38f), Tx.PASS_TAKE.t, Style.PRIMARY, size = 13f) {
                takeOrder(Orders.COUNT)
            }
        }
        y += d(80f)
        ordersBottom = y - (top - off)
        ui.endScroll(g, "orders")
    }

    private var ordersBottom = 0f

    private fun takeOrder(i: Int) {
        if (!profile.takeOrder(game.today, i)) return
        game.sound(Sfx.ACHIEVEMENT, 0.6f)
        game.toast(Tx.PASS_CLAIMED.t, Icon.STAR)
    }

    private fun takeSupply() {
        val got = profile.takeSupply(game.today) ?: return
        game.sound(Sfx.ACHIEVEMENT, 0.7f)
        game.toast(Tx.SUPPLY_GOT.f(got.joinToString(", ") { game.describe(it) }), rewardIcon(got.first()))
    }

    /** The week of supplies: a cell a day, today's lit, the ones taken ticked. */
    private fun supplyRow(g: Gfx, x: Float, y: Float, w: Float, h: Float) {
        val gap = d(4f)
        val cw = (w - gap * (Supply.DAYS - 1)) / Supply.DAYS
        val today = profile.supplyDay
        val ready = profile.supplyReady(game.today)
        for (k in 0 until Supply.DAYS) {
            val cx = x + k * (cw + gap)
            val now = k == today && ready
            val past = k < today || (k == today && !ready)
            val r = Supply.REWARDS[k].first()
            ui.chamfer(g, cx, y, cw, h, d(5f), if (k == Supply.DAYS - 1) Ui.GOLD else Ui.EDGE)
            ui.chamfer(g, cx + d(1.5f), y + d(1.5f), cw - d(3f), h - d(3f), d(4f), if (now) Ui.ACCENT else Ui.PANEL)
            val ink = if (now) Ui.DARK_TEXT else if (past) Ui.DIM else Ui.TEXT
            ui.textMid(g, "${k + 1}", cx + cw / 2f, y + d(11f), d(10f), ink, maxW = cw - d(4f))
            ui.icon(g, rewardIcon(r), cx + cw / 2f, y + h / 2f + d(1f), min(cw * 0.5f, d(18f)), if (now) Ui.DARK_TEXT else if (past) Ui.FADED else Ui.ACCENT)
            ui.textMid(g, rewardValue(r), cx + cw / 2f, y + h - d(10f), d(9.5f), ink, maxW = cw - d(4f))
            if (past) ui.icon(g, Icon.CHECK, cx + cw - d(8f), y + d(8f), d(10f), Ui.GREEN)
        }
    }

    private fun dailyTab(g: Gfx, top: Float) {
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

    private fun weekly(g: Gfx, top: Float) {
        val x = d(14f)
        val w = ui.w - d(28f)
        if (!profile.weeklyUnlocked) {
            ui.lock(g, ui.w / 2f, top + d(60f), d(40f), Ui.DIM)
            ui.paragraph(g, Tx.WEEKLY_LOCKED.f(Weekly.UNLOCK), ui.w / 2f, top + d(96f), w - d(40f), d(14f), Ui.DIM, Align.CENTER)
            return
        }
        val day = game.today
        val week = Weekly.week(day)
        val level = Levels.weekly(week, profile.progress)
        ui.textMid(g, Tx.WEEKLY_NAME.f(Weekly.name(week).t).uppercase(), ui.w / 2f, top + d(12f), d(19f), Ui.GOLD, maxW = w, font = Font.DISPLAY)
        ui.textMid(g, Tx.WEEKLY_DAYS.f(Weekly.firstDay(week + 1) - day), ui.w / 2f, top + d(34f), d(11.5f), Ui.DIM, maxW = w)
        val thumbH = min(d(170f), (ui.h - ui.bottom - top) * 0.24f)
        val thumbW = thumbH * COLS / ROWS
        var y = top + d(48f)
        framedThumb(g, thumb(level), (ui.w - thumbW) / 2f, y, thumbW, thumbH)
        y += thumbH + d(10f)
        val source = Levels.all.firstOrNull { it.ascii == level.ascii }
        val about = listOfNotNull(
            source?.let { Tx.DAILY_MAP.f(it.name.t) },
            if (level.plan.bosses.isNotEmpty()) Tx.WEEKLY_BOSSES.f(Weekly.MID_BOSS + 1, Weekly.WAVES) else null,
        ).joinToString(" · ")
        ui.textMid(g, about, ui.w / 2f, y + d(6f), d(11.5f), Ui.DIM, maxW = w)
        y += d(20f)
        for (m in Mod.forWeek(week)) {
            ui.plate(g, x, y, w, d(46f), Ui.PANEL)
            ui.icon(g, Icon.WARNING, x + d(22f), y + d(23f), d(20f), Ui.ACCENT)
            ui.text(g, m.title.t, x + d(42f), y + d(20f), d(13.5f), Ui.TEXT, maxW = w - d(54f))
            ui.text(g, m.hint.t, x + d(42f), y + d(36f), d(11f), Ui.DIM, maxW = w - d(54f), font = Font.BODY)
            y += d(51f)
        }
        y += d(4f)
        ui.caption(g, Tx.WEEKLY_MEDALS.t, x + d(4f), y + d(10f), Ui.ACCENT)
        y += d(16f)
        val have = profile.weeklyMedal(week)
        for (k in 0 until 3) {
            val earned = k < have
            ui.icon(g, Icon.MEDAL, x + d(16f), y + d(14f), d(22f), if (earned) Ui.MEDAL_COLORS[k] else Ui.PANEL_HI)
            ui.textMid(g, Tx.MEDAL_RULES[k].t, x + d(34f), y + d(14f), d(13f), if (earned) Ui.TEXT else Ui.DIM, Align.LEFT, maxW = w * 0.55f)
            ui.textMid(g, Tx.WEEKLY_REWARD.f(Weekly.POINTS[k + 1]), x + w - d(4f), y + d(14f), d(11.5f), if (earned) Ui.GREEN else Ui.GOLD, Align.RIGHT)
            y += d(28f)
        }
        val by = max(y + d(14f), ui.h - ui.bottom - d(72f))
        ui.button(g, "wstart", x, by, w, d(56f), Tx.START.t, Style.PRIMARY, size = 20f, icon = Icon.PLAY) { game.startWeekly() }
    }

    /** The boss rush of the day: its map, the bosses that lead it, the medals for time and today's best. */
    private fun bossesTab(g: Gfx, top: Float) {
        val x = d(14f)
        val w = ui.w - d(28f)
        if (!profile.bossRushUnlocked) {
            ui.lock(g, ui.w / 2f, top + d(60f), d(40f), Ui.DIM)
            ui.paragraph(g, Tx.BOSS_RUSH_LOCKED.f(BossRush.UNLOCK), ui.w / 2f, top + d(96f), w - d(40f), d(14f), Ui.DIM, Align.CENTER)
            return
        }
        val day = game.today
        val level = Levels.bossRush(day, profile.progress)
        ui.tag(g, date(day), ui.w - d(12f), ui.top + d(30f), d(12f), Ui.PANEL_HI, Ui.TEXT, right = true)
        ui.textMid(g, Tx.BOSS_RUSH.t.uppercase(), ui.w / 2f, top + d(12f), d(19f), Ui.GOLD, maxW = w, font = Font.DISPLAY)
        var y = top + d(28f)
        y += ui.paragraph(g, Tx.BOSS_RUSH_ABOUT.f(BossRush.WAVES), ui.w / 2f, y, w - d(20f), d(11.5f), Ui.DIM, Align.CENTER) + d(8f)
        val thumbH = min(d(150f), (ui.h - ui.bottom - top) * 0.2f)
        val thumbW = thumbH * COLS / ROWS
        framedThumb(g, thumb(level), x + d(4f), y, thumbW, thumbH)
        // The bosses of the day, from the last wave, where every kind comes.
        val lx = x + thumbW + d(18f)
        ui.caption(g, Tx.BOSS_LINEUP.t, lx, y + d(10f), Ui.ACCENT)
        val lineup = level.plan.bosses[BossRush.WAVES - 1].orEmpty()
        val cell = min(d(56f), (x + w - lx) / max(1, lineup.size) - d(6f))
        for ((k, type) in lineup.withIndex()) {
            val cx = lx + cell / 2f + k * (cell + d(6f))
            ui.well(g, cx - cell / 2f, y + d(22f), cell, cell, d(6f))
            renderer.drawEnemyIcon(g, type, cx, y + d(22f) + cell / 2f, cell * 0.8f, clock)
        }
        val source = Levels.all.firstOrNull { it.ascii == level.ascii }
        if (source != null) ui.text(g, Tx.DAILY_MAP.f(source.name.t), lx, y + d(36f) + cell, d(11.5f), Ui.DIM, maxW = x + w - lx)
        y += thumbH + d(16f)
        ui.caption(g, Tx.BOSS_MEDALS.t, x + d(4f), y + d(10f), Ui.ACCENT)
        y += d(16f)
        val best = profile.bossBest(day)
        val have = profile.bossMedal(day)
        val p = profile.progress
        val rules = listOf(Tx.MEDAL_RULES[0].t, Tx.BOSS_UNDER.f(BossRush.clock(BossRush.silverTime(p))), Tx.BOSS_UNDER.f(BossRush.clock(BossRush.goldTime(p))))
        for (k in 0 until 3) {
            val earned = k < have
            ui.icon(g, Icon.MEDAL, x + d(16f), y + d(14f), d(22f), if (earned) Ui.MEDAL_COLORS[k] else Ui.PANEL_HI)
            ui.textMid(g, rules[k], x + d(34f), y + d(14f), d(13f), if (earned) Ui.TEXT else Ui.DIM, Align.LEFT, maxW = w - d(40f))
            y += d(28f)
        }
        y += d(6f)
        val status = if (best > 0) Tx.BOSS_BEST.f(BossRush.clock(best)) else Tx.BOSS_NOT_YET.t
        ui.textMid(g, status, ui.w / 2f, y + d(8f), d(14f), if (best > 0) Ui.GREEN else Ui.TEXT, maxW = w)
        val by = max(y + d(30f), ui.h - ui.bottom - d(72f))
        ui.button(g, "bstart", x, by, w, d(56f), Tx.START.t, Style.PRIMARY, size = 20f, icon = Icon.PLAY) { game.startBossRush() }
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
            game.checkPurchases()
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
            Triple(Tx.VIP_ROW_RAIL.t, "—", null),
            Triple(Tx.VIP_ROW_COMMANDERS.t, "—", null),
            Triple(Tx.VIP_ROW_RESERVE.t, "—", Tx.VIP_LIVES.f(Vip.RESERVE_LIVES)),
            Triple(Tx.VIP_ROW_START.t, "—", "+${percent(Vip.START_MONEY)}%"),
            Triple(Tx.VIP_ROW_BOUNTY.t, "—", "+${percent(Vip.BOUNTY)}%"),
            Triple(Tx.VIP_ROW_COOLDOWN.t, "—", "−${percent(Vip.COOLDOWN)}%"),
            Triple(Tx.VIP_ROW_SPEED.t, Tx.VIP_UP_TO.f(free.maxSpeed), Tx.VIP_UP_TO.f(vip.maxSpeed)),
            Triple(Tx.VIP_ROW_XP.t, "—", "+${percent(vip.xpMul)}%"),
            Triple(Tx.VIP_ROW_COMMANDER.t, Tx.VIP_LEVEL.f(free.commanderLevel + 1), Tx.VIP_LEVEL.f(vip.commanderLevel + 1)),
            Triple(Tx.VIP_ROW_UNDO.t, Tx.VIP_SECONDS.f(free.undoTime.toInt()), Tx.VIP_SECONDS.f(vip.undoTime.toInt())),
            Triple(Tx.VIP_ROW_DAILY.t, "1", "${Vip.DAILY_POINTS}"),
            Triple(Tx.VIP_ROW_GIFT.t, "—", "+${Vip.DAILY_GIFT}"),
            Triple(Tx.VIP_ROW_PARADE.t, "—", null),
            Triple(Tx.VIP_ROW_SEASON.t, "—", "+${percent(Season.VIP_XP)}%"),
            Triple(Tx.VIP_ROW_CROWN.t, "—", null),
        )
    }

    fun vip(g: Gfx) {
        val top = ui.header(g, Tx.VIP.t) { game.go(Screen.MENU) }
        val active = game.vip
        val shop = game.shop
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
        val line = when {
            profile.vipForever -> Tx.VIP_ACTIVE_FOREVER.t
            active -> Tx.VIP_ACTIVE.f(date(profile.vipUntil))
            else -> Tx.VIP_PRICE.f(Product.VIP_MONTH.rubles)
        }
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

        // Buying it by the month or for good, or getting back what was paid before.
        val here = shop == Shop.HERE
        if (!active) {
            val sub = if (here) Tx.VIP_PRICE.f(Product.VIP_MONTH.rubles) else Tx.VIP_ELSEWHERE.t
            ui.button(g, "vipbuy", x, y, w, d(58f), Tx.VIP_BUY.t, Style.PRIMARY, here && !busy, size = 19f, icon = Icon.CROWN, sub = sub) {
                buy(Product.VIP_MONTH)
            }
            y += d(70f)
        }
        if (!profile.vipForever) {
            val sub = if (here) Tx.VIP_ONCE.f(Product.VIP_FOREVER.rubles) else Tx.VIP_ELSEWHERE.t
            ui.button(g, "vipforever", x, y, w, d(52f), Tx.VIP_FOREVER.t, Style.NORMAL, here && !busy, size = 16f, icon = Icon.CROWN, sub = sub) {
                buy(Product.VIP_FOREVER)
            }
            y += d(66f)
        }
        if (here) {
            restoreButton(g, x, y, w, busy)
            y += d(58f)
            y += ui.paragraph(g, Tx.VIP_NOTE.t, cx, y, w - d(16f), d(11.5f), Ui.DIM, Align.CENTER)
        }
        vipBottom = y + d(20f) - (top - off)
        ui.endScroll(g, Screen.VIP.name)
    }

    private fun buy(product: Product) {
        vipBusyAt = clock
        game.buy(product) { vipBusyAt = -100f }
    }

    private fun restoreButton(g: Gfx, x: Float, y: Float, w: Float, busy: Boolean) {
        ui.button(g, "restore", x, y, w, d(44f), Tx.RESTORE.t, Style.GHOST, !busy, size = 13f, icon = Icon.UNDO) {
            vipBusyAt = clock
            game.checkPurchases { found ->
                vipBusyAt = -100f
                game.toast(if (found) Tx.RESTORED.t else Tx.NOT_FOUND.t, if (found) Icon.CHECK else Icon.WARNING)
            }
        }
    }

    // --- commanders -----------------------------------------------------------------------

    fun commanders(g: Gfx) {
        val top = ui.header(g, Tx.COMMANDERS.t) { game.go(Screen.MENU) }
        starTotal(g, ui.w - d(12f), ui.top + d(30f))
        val shop = game.shop
        val busy = clock - vipBusyAt < VIP_WAIT
        val x = d(14f)
        val w = ui.w - d(28f)
        val viewH = ui.h - ui.bottom - top
        val off = ui.beginScroll(g, Screen.COMMANDERS.name, 0f, top, ui.w, viewH, if (commandersBottom > 0f) commandersBottom else viewH)
        var y = top - off

        // The recruit pack, while the engineer in it is still new to the player.
        if (shop != Shop.NONE && !profile.owned(Product.STARTER) && !profile.commanderOwned(CommanderType.ENGINEER)) {
            val ph = d(150f)
            ui.panel(g, x, y, w, ph, Ui.GOLD)
            ui.well(g, x + d(14f), y + d(16f), d(56f), d(56f), d(6f))
            renderer.drawCommanderIcon(g, CommanderType.ENGINEER, x + d(42f), y + d(44f), d(46f))
            ui.text(g, Tx.STARTER.t, x + d(82f), y + d(34f), d(16f), Ui.GOLD, maxW = w - d(96f), font = Font.DISPLAY)
            ui.paragraph(g, Tx.STARTER_TEXT.f(Product.STARTER_POINTS), x + d(82f), y + d(42f), w - d(96f), d(11.5f), Ui.TEXT, spacing = 1.15f)
            val sub = if (shop == Shop.HERE) Tx.PRICE.f(Product.STARTER.rubles) else Tx.VIP_ELSEWHERE.t
            ui.button(g, "starter", x + d(14f), y + ph - d(64f), w - d(28f), d(50f), Tx.STARTER_BUY.t, Style.PRIMARY, shop == Shop.HERE && !busy, size = 16f, icon = Icon.CART, sub = sub) {
                buy(Product.STARTER)
            }
            y += ph + d(12f)
        }

        ui.button(g, "looks", x, y, w, d(48f), Tx.WARDROBE.t, Style.NORMAL, size = 15f, icon = Icon.BRUSH) {
            cameFrom = Screen.COMMANDERS
            ui.resetScroll(Screen.WARDROBE.name)
            game.go(Screen.WARDROBE)
        }
        y += d(60f)
        for (c in CommanderType.entries) {
            y += commanderCard(g, c, x, y, w, shop, busy) + d(12f)
        }
        if (shop == Shop.HERE) {
            restoreButton(g, x, y, w, busy)
            y += d(56f)
        }
        commandersBottom = y + d(12f) - (top - off)
        ui.endScroll(g, Screen.COMMANDERS.name)
    }

    /** One commander: who he is, his weapon and skills, and how to get him; returns its height. */
    private fun commanderCard(g: Gfx, c: CommanderType, x: Float, y: Float, w: Float, shop: Shop, busy: Boolean): Float {
        val open = profile.commanderOpen(c)
        val lent = open && !profile.commanderOwned(c)
        val picked = profile.battleCommander == c
        val textX = x + d(96f)
        val textW = w - d(108f)
        val roleH = ui.lines(g, c.role.t, d(11.5f), textW).size * d(11.5f) * 1.15f
        val skillH = d(36f)
        val rankH = d(48f)
        val ph = d(62f) + roleH + d(26f) + rankH + skillH * c.skills.size + d(64f) + (if (lent) d(18f) else 0f)
        ui.panel(g, x, y, w, ph, if (picked) Ui.GOLD else Ui.EDGE)
        ui.well(g, x + d(14f), y + d(16f), d(70f), d(70f), d(6f))
        renderer.drawCommanderIcon(g, c, x + d(49f), y + d(51f), d(58f))
        if (!open) ui.lock(g, x + d(74f), y + d(24f), d(16f), Ui.ACCENT)
        ui.text(g, c.title.t, textX, y + d(34f), d(17f), if (picked) Ui.GOLD else Ui.TEXT, maxW = textW, font = Font.DISPLAY)
        if (lent) ui.icon(g, Icon.CROWN, x + d(24f), y + d(26f), d(14f), Ui.GOLD)
        var ty = y + d(42f)
        ty += ui.paragraph(g, c.role.t, textX, ty, textW, d(11.5f), Ui.DIM, spacing = 1.15f)
        val gun = c.levels[0]
        val reach = if (c.hitsAir) Tx.GROUND_AIR.t else Tx.GROUND_ONLY.t
        ui.chips(g, listOf(c.weapon.t, Tx.DMG.f(Num.int(gun.damage)), Tx.PER_SECOND.f(Num.one(gun.rate)), reach), textX, ty + d(6f), textW, size = d(10f))
        var sy = max(ty + d(30f), y + d(96f))
        sy += rankRow(g, c, x + d(16f), sy, w - d(32f))
        ui.caption(g, Tx.SKILLS.t, x + d(16f), sy + d(4f))
        sy += d(12f)
        val rank = profile.rank(c)
        for ((k, skill) in c.skills.withIndex()) {
            val locked = k >= 2 && rank < Ranks.THIRD_SKILL
            ui.well(g, x + d(14f), sy, d(30f), d(30f), d(5f))
            ui.icon(g, if (locked) Icon.LOCK else skill.icon, x + d(29f), sy + d(15f), d(18f), if (locked) Ui.DIM else Ui.ACCENT)
            val title = if (locked) "${skill.title.t} · ${Tx.SKILL_FROM_RANK.f(Ranks.THIRD_SKILL)}" else skill.title.t
            ui.text(g, title, x + d(54f), sy + d(13f), d(13f), if (locked) Ui.DIM else Ui.TEXT, maxW = w - d(66f))
            ui.text(g, skill.hint.t, x + d(54f), sy + d(27f), d(10.5f), Ui.DIM, maxW = w - d(66f), font = Font.BODY)
            sy += skillH
        }
        ui.text(g, Tx.GROWS.t, x + d(16f), sy + d(10f), d(10.5f), Ui.FADED, maxW = w - d(32f), font = Font.BODY)
        val by = y + ph - d(50f)
        val bw = w - d(28f)
        when {
            picked -> {
                ui.tag(g, Tx.IN_BATTLE.t, x + w / 2f - (g.textWidth(Tx.IN_BATTLE.t, d(14f), Font.DISPLAY) + d(14f)) / 2f, by + d(19f), d(14f), Ui.GOLD, Ui.DARK_TEXT)
            }
            open -> {
                if (lent) ui.textMid(g, Tx.VIP_OPEN.t, x + w / 2f, by - d(9f), d(10.5f), Ui.GOLD, maxW = bw)
                ui.button(g, "take${c.name}", x + d(14f), by, bw, d(38f), Tx.TAKE.t, Style.PRIMARY, size = 15f, icon = Icon.COMMANDER) {
                    profile.commander = c
                    game.sound(Sfx.UPGRADE, 0.6f)
                }
            }
            else -> {
                val product = c.product
                val stars = Tx.FREE_FOR_STARS.f(c.stars, profile.totalStars)
                if (product != null && shop != Shop.NONE) {
                    val half = (bw - d(8f)) / 2f
                    ui.paragraph(g, stars, x + d(14f) + half / 2f, by + d(4f), half, d(10.5f), Ui.DIM, Align.CENTER)
                    val sub = if (shop == Shop.HERE) Tx.PRICE.f(product.rubles) else Tx.VIP_ELSEWHERE.t
                    ui.button(g, "buy${c.name}", x + d(22f) + half, by, half, d(38f), Tx.BUY.t, Style.PRIMARY, shop == Shop.HERE && !busy, size = 14f, sub = sub) {
                        buy(product)
                    }
                } else {
                    ui.textMid(g, stars, x + w / 2f, by + d(19f), d(12f), Ui.DIM, maxW = bw)
                }
            }
        }
        return ph
    }

    // --- season pass ----------------------------------------------------------------------

    /** The season pass in the menu's left corner: its tier, and a light while a reward waits. */
    private fun passBadge(g: Gfx) {
        val today = game.today
        val label = Tx.PASS_BADGE.f(profile.seasonTier(today))
        val bh = d(32f)
        val bw = d(44f) + g.textWidth(label, d(13f), Font.DISPLAY)
        val bx = d(12f)
        val top = ui.top + d(8f)
        ui.area("pass", bx, top, bw, bh) { openPass() }
        val by = top + if (ui.down("pass")) d(2f) else 0f
        ui.chamfer(g, bx, by, bw, bh, d(7f), Ui.EDGE)
        ui.chamfer(g, bx + d(1f), by + d(1f), bw - d(2f), bh - d(2f), d(7f), Ui.BAND)
        ui.outline(g, bx + d(2.5f), by + d(2.5f), bw - d(5f), bh - d(5f), d(6f), Ui.ACCENT, d(1.5f))
        ui.icon(g, Icon.DIAMOND, bx + d(19f), by + bh / 2f, d(16f), Ui.ACCENT)
        ui.textMid(g, label, bx + d(32f), by + bh / 2f, d(13f), Ui.TEXT, Align.LEFT, font = Font.DISPLAY)
        if (profile.unclaimed(today) > 0) {
            g.fillCircle(bx + bw - d(3f), by + d(3f), d(7f), Ui.EDGE)
            g.fillCircle(bx + bw - d(3f), by + d(3f), d(5f), Colors.mix(Ui.ACCENT, Ui.GOLD, 0.5f + 0.5f * sin(clock * 5f)))
        }
    }

    private fun openPass() {
        open(Screen.PASS)
        ui.scrollTo(Screen.PASS.name, max(0, profile.seasonTier(game.today) - 2) * passRow)
    }

    fun pass(g: Gfx) {
        val top = ui.header(g, Tx.PASS.t) { game.go(returnTo()) }
        val today = game.today
        val shop = game.shop
        val busy = clock - vipBusyAt < VIP_WAIT
        val premium = profile.passPremium(today)
        val x = d(14f)
        val w = ui.w - d(28f)
        val cx = ui.w / 2f

        // Above the list: the season, the tier reached and the premium track.
        val buyH = if (!premium && shop != Shop.NONE) d(88f) else if (premium) d(34f) else 0f
        val headH = d(112f) + buyH
        ui.panel(g, x, top + d(2f), w, headH - d(8f), Ui.ACCENT)
        var y = top + d(26f)
        ui.textMid(g, Tx.PASS_SEASON.f(Season.name(Season.of(today)).t).uppercase(), cx, y, d(17f), Ui.ACCENT, maxW = w - d(24f), font = Font.DISPLAY)
        y += d(20f)
        ui.textMid(g, Tx.PASS_DAYS.f(Season.daysLeft(today)), cx, y, d(11.5f), Ui.DIM, maxW = w - d(24f))
        y += d(22f)
        val tier = profile.seasonTier(today)
        val tagW = ui.tag(g, Tx.PASS_TIER_TAG.f(tier), x + d(16f), y + d(6f), d(12f), Ui.ACCENT)
        val xp = profile.seasonXp(today)
        val inTier = if (tier >= Season.TIERS) Season.XP_PER_TIER else xp - tier * Season.XP_PER_TIER
        val count = "$inTier / ${Season.XP_PER_TIER}"
        val countW = g.textWidth(count, d(11f))
        val barX = x + d(16f) + tagW + d(10f)
        ui.bar(g, barX, y + d(2f), x + w - d(16f) - countW - d(8f) - barX, d(9f), inTier / Season.XP_PER_TIER.toFloat(), Ui.ACCENT)
        ui.textMid(g, count, x + w - d(16f), y + d(6f), d(11f), Ui.DIM, Align.RIGHT)
        y += d(22f)
        ui.textMid(g, Tx.PASS_HOW.t, cx, y, d(10.5f), Ui.DIM, maxW = w - d(24f), font = Font.BODY)
        y += d(14f)
        if (premium) {
            ui.tag(g, Tx.PASS_ON.t, cx - (g.textWidth(Tx.PASS_ON.t, d(13f), Font.DISPLAY) + d(13f)) / 2f, y + d(12f), d(13f), Ui.GOLD)
        } else if (shop != Shop.NONE) {
            ui.paragraph(g, Tx.PASS_BUY_TEXT.t, cx, y, w - d(32f), d(10.5f), Ui.TEXT, Align.CENTER)
            val sub = if (shop == Shop.HERE) Tx.PRICE.f(Product.PASS.rubles) else Tx.VIP_ELSEWHERE.t
            ui.button(g, "passbuy", x + d(14f), y + d(30f), w - d(28f), d(46f), Tx.PASS_BUY.t, Style.PRIMARY, shop == Shop.HERE && !busy, size = 15f, icon = Icon.DIAMOND, sub = sub) {
                buy(Product.PASS)
            }
        }

        // The tiers, each with its free and premium rewards.
        val listTop = top + headH
        val viewH = ui.h - ui.bottom - listTop
        val content = d(30f) + Season.TIERS * passRow + d(12f)
        val off = ui.beginScroll(g, Screen.PASS.name, 0f, listTop, ui.w, viewH, content)
        y = listTop - off
        val tierW = d(40f)
        val cellW = (w - tierW - d(8f)) / 2f
        ui.caption(g, Tx.PASS_FREE.t, x + tierW + d(4f), y + d(18f))
        ui.caption(g, Tx.PASS_PREMIUM.t, x + tierW + cellW + d(12f), y + d(18f), Ui.GOLD)
        y += d(30f)
        for (t in 1..Season.TIERS) {
            val reached = t <= tier
            g.fillCircle(x + tierW / 2f, y + passRow / 2f - d(4f), d(16f), if (reached) Ui.ACCENT else Ui.PANEL_DARK)
            ui.textMid(g, "$t", x + tierW / 2f, y + passRow / 2f - d(4f), d(13f), if (reached) Ui.DARK_TEXT else Ui.DIM, font = Font.DISPLAY)
            rewardCell(g, t, false, x + tierW + d(4f), y, cellW, passRow - d(8f))
            rewardCell(g, t, true, x + tierW + cellW + d(8f), y, cellW, passRow - d(8f))
            y += passRow
        }
        ui.endScroll(g, Screen.PASS.name)
    }

    private fun rewardIcon(r: Reward): Icon = when (r.kind) {
        RewardKind.POINTS -> Icon.STAR
        RewardKind.SERVICE -> Icon.COMMANDER
        RewardKind.VIP_DAYS -> Icon.CROWN
        RewardKind.CAMO -> Icon.BRUSH
        RewardKind.SEASON -> Icon.DIAMOND
    }

    private fun rewardValue(r: Reward): String = when (r.kind) {
        RewardKind.POINTS, RewardKind.SERVICE, RewardKind.SEASON -> "+${r.amount}"
        RewardKind.VIP_DAYS -> Tx.VIP.t
        RewardKind.CAMO -> Camo.URBAN.title.t
    }

    private fun rewardCaption(r: Reward): String = when (r.kind) {
        RewardKind.POINTS -> Tx.REWARD_POINTS.t
        RewardKind.SERVICE -> Tx.REWARD_SERVICE.t
        RewardKind.VIP_DAYS -> Tx.REWARD_VIP.f(r.amount)
        RewardKind.CAMO -> Tx.REWARD_CAMO.t
        RewardKind.SEASON -> Tx.REWARD_SEASON.t
    }

    /** One reward: what it is, and whether it can be taken, was taken or waits for its tier or for premium. */
    private fun rewardCell(g: Gfx, tier: Int, premium: Boolean, x: Float, y: Float, w: Float, h: Float) {
        val today = game.today
        val reward = if (premium) Season.premium(tier) else Season.free(tier)
        if (reward == null) {
            ui.chamfer(g, x, y, w, h, d(6f), Colors.alpha(Ui.PANEL_DARK, 0.5f))
            return
        }
        val ready = profile.claimable(today, tier, premium)
        val taken = profile.claimed(today, tier, premium)
        val reached = tier <= profile.seasonTier(today)
        val locked = premium && !profile.passPremium(today)
        val id = "claim${if (premium) "P" else "F"}$tier"
        if (ready) {
            ui.area(id, x, y, w, h) {
                if (profile.claim(today, tier, premium)) {
                    game.sound(Sfx.ACHIEVEMENT, 0.6f)
                    game.toast(Tx.PASS_CLAIMED.t, Icon.DIAMOND)
                }
            }
        }
        val sink = if (ready && ui.down(id)) d(2f) else 0f
        val face = when {
            ready -> Ui.ACCENT
            premium -> Ui.PANEL_LIGHT
            else -> Ui.PANEL
        }
        ui.chamfer(g, x, y + sink, w, h, d(6f), Ui.EDGE)
        ui.chamfer(g, x + d(1.5f), y + d(1.5f) + sink, w - d(3f), h - d(3f), d(5f), Colors.alpha(face, if (reached || ready) 1f else 0.55f))
        val ink = if (ready) Ui.DARK_TEXT else if (reached) Ui.TEXT else Ui.DIM
        ui.icon(g, rewardIcon(reward), x + d(18f), y + h / 2f + sink, d(18f), if (ready) Ui.DARK_TEXT else if (premium) Ui.GOLD else Ui.ACCENT)
        val value = rewardValue(reward)
        val caption = rewardCaption(reward)
        val tx = x + d(34f)
        val tw = w - d(40f)
        ui.text(g, value, tx, y + h / 2f - d(1f) + sink, d(14f), ink, maxW = tw, font = Font.DISPLAY)
        ui.text(g, if (ready) Tx.PASS_TAKE.t else caption, tx, y + h / 2f + d(13f) + sink, d(9.5f), ink, maxW = tw, font = if (ready) Font.DISPLAY else Font.BODY)
        when {
            taken -> ui.icon(g, Icon.CHECK, x + w - d(13f), y + d(13f), d(14f), Ui.GREEN)
            locked -> ui.lock(g, x + w - d(13f), y + d(13f), d(12f), Ui.DIM)
        }
    }

    // --- looks --------------------------------------------------------------------------

    private var wardrobeBottom = 0f

    fun wardrobe(g: Gfx) {
        val top = ui.header(g, Tx.WARDROBE.t) { game.go(returnTo()) }
        val shop = game.shop
        val busy = clock - vipBusyAt < VIP_WAIT
        val x = d(14f)
        val w = ui.w - d(28f)
        val viewH = ui.h - ui.bottom - top
        val off = ui.beginScroll(g, Screen.WARDROBE.name, 0f, top, ui.w, viewH, if (wardrobeBottom > 0f) wardrobeBottom else viewH)
        var y = top - off + d(4f)
        ui.caption(g, Tx.CAMO_SQUADS.t, x + d(4f), y + d(12f))
        y += d(22f)
        val gap = d(10f)
        val cw = (w - gap) / 2f
        val ch = d(196f)
        for ((i, c) in Camo.entries.withIndex()) {
            camoCard(g, c, x + (i % 2) * (cw + gap), y + (i / 2) * (ch + gap), cw, ch, shop, busy)
        }
        y += (Camo.entries.size + 1) / 2 * (ch + gap) + d(6f)
        ui.caption(g, Tx.CAMO_COMMANDERS.t, x + d(4f), y + d(12f))
        y += d(22f)
        for (c in CommanderType.entries) y += outfitRow(g, c, x, y, w) + d(10f)
        if (shop == Shop.HERE) {
            restoreButton(g, x, y, w, busy)
            y += d(56f)
        }
        wardrobeBottom = y + d(12f) - (top - off)
        ui.endScroll(g, Screen.WARDROBE.name)
    }

    /** One camouflage for the squads: a squad wearing it, and how to get it. */
    private fun camoCard(g: Gfx, c: Camo, x: Float, y: Float, w: Float, h: Float, shop: Shop, busy: Boolean) {
        val open = profile.camoOpen(c)
        val worn = profile.battleCamo == c
        ui.panel(g, x, y, w, h, if (worn) Ui.GOLD else Ui.EDGE)
        val ps = d(84f)
        ui.well(g, x + (w - ps) / 2f, y + d(12f), ps, ps, d(8f))
        val was = renderer.camo
        renderer.camo = c
        renderer.drawTowerIcon(g, TowerType.TURRET, 1, x + w / 2f, y + d(12f) + ps / 2f, ps * 0.92f, clock)
        renderer.camo = was
        if (!open) ui.lock(g, x + w / 2f + ps / 2f - d(4f), y + d(18f), d(15f), Ui.ACCENT)
        ui.textMid(g, c.title.t, x + w / 2f, y + d(114f), d(15f), if (worn) Ui.GOLD else Ui.TEXT, maxW = w - d(16f), font = Font.DISPLAY)
        val by = y + h - d(52f)
        val bx = x + d(10f)
        val bw = w - d(20f)
        val product = c.product
        val world = c.world
        when {
            worn -> ui.tag(g, Tx.CAMO_PICKED.t, x + w / 2f - (g.textWidth(Tx.CAMO_PICKED.t, d(13f), Font.DISPLAY) + d(13f)) / 2f, by + d(20f), d(13f), Ui.GOLD)
            open -> ui.button(g, "camo${c.name}", bx, by, bw, d(40f), Tx.CAMO_PICK.t, Style.PRIMARY, size = 14f) {
                profile.camo = c
                game.sound(Sfx.UPGRADE, 0.6f)
            }
            world != null -> ui.paragraph(
                g, Tx.CAMO_WORLD.f(Biome.entries[world].title.t, profile.worldWins(world), Levels.PER_WORLD),
                x + w / 2f, by + d(4f), bw, d(11f), Ui.DIM, Align.CENTER,
            )
            product != null && shop != Shop.NONE -> {
                if (c == Camo.PARADE) ui.textMid(g, Tx.CAMO_VIP.t, x + w / 2f, by - d(10f), d(10.5f), Ui.GOLD, maxW = bw)
                val sub = if (shop == Shop.HERE) Tx.PRICE.f(product.rubles) else Tx.VIP_ELSEWHERE.t
                ui.button(g, "camo${c.name}", bx, by, bw, d(42f), Tx.BUY.t, Style.PRIMARY, shop == Shop.HERE && !busy, size = 14f, icon = Icon.CART, sub = sub) {
                    buy(product)
                }
            }
            else -> ui.paragraph(g, if (c == Camo.URBAN) Tx.CAMO_PASS.t else Tx.CAMO_PARADE.t, x + w / 2f, by + d(4f), bw, d(11f), Ui.DIM, Align.CENTER)
        }
    }

    /** One commander in every uniform, the worn one framed in gold; returns its height. */
    private fun outfitRow(g: Gfx, c: CommanderType, x: Float, y: Float, w: Float): Float {
        val h = d(124f)
        ui.panel(g, x, y, w, h)
        ui.text(g, c.title.t, x + d(16f), y + d(26f), d(15f), Ui.TEXT, maxW = w - d(32f), font = Font.DISPLAY)
        val n = Camo.entries.size
        val gap = d(6f)
        val sw = min(d(56f), (w - d(24f) - gap * (n - 1)) / n)
        var sx = x + (w - sw * n - gap * (n - 1)) / 2f
        val sy = y + d(40f)
        val worn = profile.battleOutfit(c)
        for (o in Camo.entries) {
            val open = profile.outfitOpen(c, o)
            ui.area("outfit${c.name}${o.name}", sx, sy, sw, sw + d(18f)) {
                if (open) {
                    profile.setOutfit(c, o)
                    game.sound(Sfx.UPGRADE, 0.5f)
                } else {
                    game.toast(outfitHint(o), Icon.LOCK)
                }
            }
            ui.well(g, sx, sy, sw, sw, d(6f))
            if (o == worn) ui.outline(g, sx - d(2f), sy - d(2f), sw + d(4f), sw + d(4f), d(7f), Ui.GOLD, d(2.5f))
            renderer.drawCommanderIcon(g, c, sx + sw / 2f, sy + sw / 2f, sw * 0.82f, if (open) 1f else 0.45f, o)
            if (!open) ui.lock(g, sx + sw - d(9f), sy + d(9f), d(12f), Ui.ACCENT)
            ui.textMid(g, o.title.t, sx + sw / 2f, sy + sw + d(11f), d(9.5f), if (o == worn) Ui.GOLD else Ui.DIM, maxW = sw + gap)
            sx += sw + gap
        }
        return h
    }

    /** How to get a uniform that is still closed. */
    private fun outfitHint(o: Camo): String {
        val world = o.world
        return when {
            world != null -> Tx.CAMO_WORLD.f(Biome.entries[world].title.t, profile.worldWins(world), Levels.PER_WORLD)
            o == Camo.PARADE -> Tx.CAMO_PARADE.t
            o.product != null -> Tx.CAMO_SET.f(o.title.t)
            else -> Tx.CAMO_PASS.t
        }
    }

    /** [c]'s rank, how far he is to the next and what it brings; returns its height. */
    private fun rankRow(g: Gfx, c: CommanderType, x: Float, y: Float, w: Float): Float {
        val rank = profile.rank(c)
        val service = profile.service(c)
        val top = rank >= Ranks.MAX
        val tagW = ui.tag(g, Tx.RANK.f(rank), x, y + d(9f), d(11f), if (top) Ui.GOLD else Ui.ACCENT)
        val barX = x + tagW + d(10f)
        val need = Ranks.SERVICE.getOrNull(rank + 1)
        if (need == null) {
            ui.textMid(g, Tx.RANK_TOP.t, barX, y + d(9f), d(12f), Ui.GOLD, Align.LEFT, font = Font.DISPLAY)
            ui.text(g, RankPerk.PARADE.text(c), x, y + d(34f), d(10.5f), Ui.GOLD, maxW = w, font = Font.BODY)
        } else {
            val from = Ranks.SERVICE[rank]
            val count = "$service / $need"
            val countW = g.textWidth(count, d(10.5f))
            ui.bar(g, barX, y + d(5f), w - (barX - x) - countW - d(10f), d(8f), (service - from) / (need - from).toFloat(), Ui.GOLD)
            ui.textMid(g, count, x + w, y + d(9f), d(10.5f), Ui.DIM, Align.RIGHT)
            val perk = RankPerk.at(rank + 1)?.text(c) ?: ""
            ui.text(g, Tx.RANK_NEXT.f(rank + 1, perk), x, y + d(34f), d(10.5f), Ui.DIM, maxW = w, font = Font.BODY)
        }
        return d(48f)
    }

    private val passRow: Float get() = d(62f)

    private companion object {
        /** Seconds the store gets to answer before the VIP buttons work again. */
        const val VIP_WAIT = 30f

        /** A window waits this long after the menu opens. */
        const val POPUP_DELAY = 1.2f

        /** The VIP offer shows to players with this many stars, at most every this many days. */
        const val PROMO_STARS = 6
        const val PROMO_GAP = 3

        /** Days after VIP runs out when the reminder may still show. */
        const val ENDED_DAYS = 14
    }
}
