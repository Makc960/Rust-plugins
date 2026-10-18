package com.makc.basedefense.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The battle screen: the map, the HUD, the shop and everything that pops up over them. */
internal class PlayScreen(private val game: Game) : WorldListener {
    private val ui get() = game.ui
    private val profile get() = game.profile
    private val renderer get() = game.renderer
    private val clock get() = game.clock

    private enum class Overlay { NONE, PAUSE, SETTINGS, RESULT, CARD }

    private var overlay = Overlay.NONE
    private var session: Session? = null
    internal val world: World? get() = session?.world

    private var speed = 1
    private var acc = 0f
    internal var placing: TowerType? = null
    internal var selected: Tower? = null
    internal var obstacle = -1
    internal var aiming = false
    private var aimX = -1f
    private var aimY = -1f
    private var ghostCol = -1
    private var ghostRow = -1
    private var touchingMap = false
    private var dragged = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastBuilt: Tower? = null
    private var lastBuiltAt = -10f
    private var sellArmed: Tower? = null
    private var sellArmedAt = -10f
    private var notice: String? = null
    private var noticeAt = -10f
    private var moneyFlash = 0f
    private var endDelay = 0f
    private var slowmo = 0f

    // Camera: the map point in the middle of the view and how far it is zoomed in.
    private var zoom = 1f
    private var camX = COLS / 2f
    private var camY = ROWS / 2f

    // Results.
    private var resultAt = 0f
    private var resultStars = 0
    private var resultHard = false
    private var resultMedal = false
    private var resultRecord = false
    private var resultAchievements: List<Achievement> = emptyList()
    private var flushed = false

    private class Card(val kind: L, val title: String, val text: String, val icon: (Gfx, Float, Float, Float) -> Unit)

    private val cards = ArrayList<Card>()

    // Layout, in pixels.
    private var mapX = 0f
    private var mapY = 0f
    private var cell = 1f
    private var hudH = 0f
    private var stripY = 0f
    private var stripH = 0f
    private var shopY = 0f
    private var shopH = 0f

    private fun d(v: Float) = ui.d(v)

    // --- the world tells us ---------------------------------------------------------------

    override fun sound(sfx: Sfx, volume: Float) = game.sound(sfx, volume)

    override fun baseHit() = game.vibrate(35)

    override fun firstSight(type: EnemyType) {
        if (profile.seen(type.key)) return
        profile.markSeen(type.key)
        if (!profile.tips) return
        cards += Card(
            Tx.CARD_ENEMY, type.title.t,
            type.hint.t + "\n" + Tx.ENEMY_STATS.f(type.hp.roundToInt(), Num.one(type.speed), type.armor.roundToInt()),
        ) { g, cx, cy, s -> renderer.drawEnemyIcon(g, type, cx, cy, s, clock) }
    }

    override fun bossSpawned(e: Enemy) {
        slowmo = SLOWMO_TIME
        game.vibrate(90)
    }

    // --- lifecycle ------------------------------------------------------------------------

    fun begin(s: Session, resumed: Boolean) {
        session = s
        overlay = if (resumed) Overlay.PAUSE else Overlay.NONE
        speed = 1
        acc = 0f
        placing = null
        selected = null
        obstacle = -1
        aiming = false
        ghostCol = -1
        touchingMap = false
        lastBuilt = null
        sellArmed = null
        notice = null
        moneyFlash = 0f
        endDelay = 0f
        slowmo = 0f
        zoom = 1f
        camX = COLS / 2f
        camY = ROWS / 2f
        resultStars = 0
        resultHard = false
        resultMedal = false
        resultRecord = false
        resultAchievements = emptyList()
        flushed = false
        cards.clear()
        ui.resetScroll("shop")
        if (!resumed) introCards(s)
    }

    /** What this level brings for the first time: new towers, abilities and tips. */
    private fun introCards(s: Session) {
        if (s.mode != Mode.CAMPAIGN) return
        val id = s.level.id
        val tips = profile.tips
        fun tip(key: String, title: L, text: L) {
            if (profile.seen(key)) return
            profile.markSeen(key)
            if (tips) cards += Card(Tx.CARD_TIP, title.t, text.t) { g, cx, cy, sz -> ui.book(g, cx, cy, sz * 0.8f, Ui.ACCENT) }
        }
        for (t in TowerType.entries) {
            if (t.unlock != id || profile.seen(t.key)) continue
            profile.markSeen(t.key)
            if (tips && id > 1) {
                cards += Card(Tx.CARD_TOWER, t.title.t, t.hint.t) { g, cx, cy, sz -> renderer.drawTowerIcon(g, t, 0, cx, cy, sz, clock) }
            }
        }
        for (a in Ability.entries) {
            if (a.unlock != id || profile.seen(a.key)) continue
            profile.markSeen(a.key)
            if (tips) cards += Card(Tx.CARD_ABILITY, a.title.t, a.hint.t) { g, cx, cy, sz -> renderer.drawAbilityIcon(g, a, cx, cy, sz * 0.8f, Ui.TEXT) }
        }
        when (id) {
            2 -> tip(Tips.OBSTACLES, Tx.TIP_OBSTACLES, Tx.TIP_OBSTACLES_TEXT)
            BRANCH_UNLOCK -> tip(Tips.BRANCHES, Tx.TIP_BRANCHES, Tx.TIP_BRANCHES_TEXT)
            4 -> tip(Tips.ZOOM, Tx.TIP_ZOOM, Tx.TIP_ZOOM_TEXT)
            9 -> tip(Tips.WEATHER, Tx.TIP_DESERT, Tx.TIP_DESERT_TEXT)
            17 -> tip(Tips.WINTER, Tx.TIP_WINTER, Tx.TIP_WINTER_TEXT)
        }
    }

    fun update(dt: Float) {
        val wd = world ?: return
        if (moneyFlash > 0f) moneyFlash -= dt
        if (overlay == Overlay.NONE && cards.isNotEmpty()) overlay = Overlay.CARD
        if (overlay == Overlay.NONE) {
            var scale = speed.toFloat()
            if (slowmo > 0f) {
                slowmo -= dt
                scale = min(scale, SLOWMO_SCALE)
            }
            acc += dt * scale
            while (acc >= Game.STEP) {
                wd.step(Game.STEP)
                acc -= Game.STEP
                if (cards.isNotEmpty()) {
                    acc = 0f
                    break
                }
            }
        }
        if (selected?.let { it !in wd.towers } == true) selected = null
        if (obstacle >= 0 && !wd.map.obstacle(obstacle % COLS, obstacle / COLS)) obstacle = -1
        if (aiming && !wd.abilityReady(Ability.AIRSTRIKE)) aiming = false
        if (wd.over && overlay != Overlay.RESULT) {
            placing = null
            selected = null
            aiming = false
            obstacle = -1
            cards.clear()
            if (overlay == Overlay.CARD) overlay = Overlay.NONE
            endDelay += dt
            if (endDelay > 1.2f) finish(wd)
        }
    }

    /** Adds this battle's counters to the profile, once. */
    fun flushStats() {
        if (flushed) return
        val wd = world ?: return
        flushed = true
        profile.addStat(Stat.KILLS, wd.kills)
        profile.addStat(Stat.BOSS_KILLS, wd.bossKills)
        profile.addStat(Stat.GHOST_KILLS, wd.ghostKills)
        profile.addStat(Stat.BUILT, wd.built)
        profile.addStat(Stat.AIRSTRIKES, wd.airstrikes)
        profile.addStat(Stat.OBSTACLES, wd.cleared)
        profile.maxStat(Stat.MAX_MONEY, wd.maxMoney)
        profile.orStat(Stat.BRANCHES, wd.branchMask)
        profile.orStat(Stat.TOWER_TYPES, wd.typeMask)
    }

    private fun finish(wd: World) {
        overlay = Overlay.RESULT
        resultAt = clock
        val s = session ?: return
        flushStats()
        val won = wd.phase == Phase.WON
        when (s.mode) {
            Mode.CAMPAIGN -> if (won) {
                val share = wd.lives / wd.maxLives.toFloat()
                resultStars = when {
                    share >= 0.9f -> 3
                    share >= 0.5f -> 2
                    else -> 1
                }
                resultHard = s.difficulty == Difficulty.HARD
                resultMedal = s.challenge && !wd.challengeFailed
                profile.recordWin(s.level.id, resultStars, s.difficulty, resultMedal)
                profile.addStat(Stat.WINS, 1)
                if (wd.leaked == 0) profile.addStat(Stat.FLAWLESS, 1)
            }
            Mode.ENDLESS -> {
                val survived = max(0, wd.wavesStarted - 1)
                resultRecord = survived > profile.bestEndless
                profile.bestEndless = survived
                profile.maxStat(Stat.ENDLESS_BEST, survived)
            }
            Mode.DAILY -> {
                val waves = if (won) wd.totalWaves else max(0, wd.wavesStarted - 1)
                resultRecord = won && !profile.dailyWon(s.day)
                profile.recordDaily(s.day, waves, won)
                if (won && wd.leaked == 0) profile.addStat(Stat.FLAWLESS, 1)
            }
        }
        profile.save = null
        resultAchievements = profile.checkAchievements()
        if (resultAchievements.isNotEmpty()) game.sound(Sfx.ACHIEVEMENT, 0.8f)
    }

    /** Back button inside the battle. */
    fun back(): Boolean {
        when (overlay) {
            Overlay.RESULT -> leave()
            Overlay.PAUSE -> overlay = Overlay.NONE
            Overlay.SETTINGS -> overlay = Overlay.PAUSE
            Overlay.CARD -> closeCard()
            Overlay.NONE -> when {
                aiming -> aiming = false
                placing != null -> placing = null
                selected != null -> selected = null
                obstacle >= 0 -> obstacle = -1
                else -> openPause()
            }
        }
        return true
    }

    private fun openPause() {
        overlay = Overlay.PAUSE
        game.saveSession()
    }

    fun pauseFromSystem() {
        val wd = world ?: return
        if (wd.over) return
        if (overlay == Overlay.NONE) overlay = Overlay.PAUSE
        touchingMap = false
        game.saveSession()
    }

    private fun leave() {
        val s = session
        val to = if (s?.mode == Mode.CAMPAIGN) Screen.LEVELS else Screen.MENU
        if (s?.mode == Mode.DAILY && overlay == Overlay.RESULT) game.leaveBattle(Screen.DAILY) else game.leaveBattle(to)
    }

    private fun closeCard() {
        if (cards.isNotEmpty()) cards.removeAt(0)
        overlay = if (cards.isNotEmpty()) Overlay.CARD else Overlay.NONE
    }

    // --- touch ----------------------------------------------------------------------------

    fun touch(action: Touch, x: Float, y: Float, used: Boolean) {
        if (overlay != Overlay.NONE) {
            touchingMap = false
            return
        }
        when (action) {
            Touch.DOWN -> if (!used && inMap(x, y)) {
                touchingMap = true
                dragged = false
                downX = x
                downY = y
                lastX = x
                lastY = y
                mapTouch(Touch.DOWN, x, y)
            }
            Touch.MOVE -> if (touchingMap) {
                if (placing == null && !aiming && zoom > 1.01f) {
                    if (!dragged && hypot(x - downX, y - downY) > d(10f)) dragged = true
                    if (dragged) pan(x - lastX, y - lastY)
                } else {
                    mapTouch(Touch.MOVE, x, y)
                }
                lastX = x
                lastY = y
            }
            Touch.UP -> if (touchingMap) {
                touchingMap = false
                if (!dragged) mapTouch(Touch.UP, x, y)
                dragged = false
                ghostCol = -1
            }
            Touch.CANCEL -> {
                touchingMap = false
                dragged = false
                ghostCol = -1
            }
        }
    }

    fun pinch(scale: Float, fx: Float, fy: Float) {
        if (overlay != Overlay.NONE) return
        touchingMap = false
        ghostCol = -1
        val px = worldX(fx)
        val py = worldY(fy)
        zoom = (zoom * scale).coerceIn(1f, MAX_ZOOM)
        camX = px - (fx - mapX) / (cell * zoom) + COLS / (2f * zoom)
        camY = py - (fy - mapY) / (cell * zoom) + ROWS / (2f * zoom)
        clampCamera()
    }

    fun pan(dx: Float, dy: Float) {
        if (overlay != Overlay.NONE) return
        camX -= dx / (cell * zoom)
        camY -= dy / (cell * zoom)
        clampCamera()
    }

    private fun clampCamera() {
        val hw = COLS / (2f * zoom)
        val hh = ROWS / (2f * zoom)
        camX = camX.coerceIn(hw, COLS - hw)
        camY = camY.coerceIn(hh, ROWS - hh)
    }

    private val viewLeft: Float get() = camX - COLS / (2f * zoom)
    private val viewTop: Float get() = camY - ROWS / (2f * zoom)

    private fun screenX(wx: Float) = mapX + (wx - viewLeft) * cell * zoom

    private fun screenY(wy: Float) = mapY + (wy - viewTop) * cell * zoom

    private fun worldX(px: Float) = (px - mapX) / (cell * zoom) + viewLeft

    private fun worldY(py: Float) = (py - mapY) / (cell * zoom) + viewTop

    private fun inMap(x: Float, y: Float): Boolean =
        x >= mapX && y >= mapY && x < mapX + cell * COLS && y < mapY + cell * ROWS

    private fun mapTouch(action: Touch, x: Float, y: Float) {
        val wd = world ?: return
        val fx = worldX(x)
        val fy = worldY(y)
        val c = floor(fx).toInt()
        val r = floor(fy).toInt()
        if (aiming) {
            aimX = fx
            aimY = fy
            if (action == Touch.UP) {
                if (wd.airstrike(fx, fy)) {
                    aiming = false
                    game.vibrate(30)
                } else {
                    game.sound(Sfx.ERROR, 0.4f)
                }
            }
            return
        }
        val type = placing
        if (type != null) {
            if (action != Touch.UP) {
                ghostCol = c
                ghostRow = r
                return
            }
            ghostCol = -1
            val existing = wd.towerAt(c, r)
            when {
                wd.canBuild(type, c, r) -> {
                    lastBuilt = wd.build(type, c, r)
                    lastBuiltAt = clock
                    placing = null
                    game.vibrate(15)
                }
                existing != null -> {
                    placing = null
                    selected = existing
                }
                else -> {
                    game.sound(Sfx.ERROR, 0.4f)
                    when {
                        wd.money < wd.buildCost(type) -> moneyFlash = 0.6f
                        type.role == Role.ROAD && !wd.map.mineable(c, r) -> notify(Tx.ON_ROAD.t)
                    }
                }
            }
            return
        }
        if (action != Touch.UP) return
        val t = wd.towerAt(c, r)
        when {
            t != null -> {
                selected = if (t !== selected) t else null
                obstacle = -1
                sellArmed = null
            }
            wd.map.obstacle(c, r) -> {
                val cellId = r * COLS + c
                obstacle = if (obstacle == cellId) -1 else cellId
                selected = null
            }
            else -> {
                selected = null
                obstacle = -1
            }
        }
    }

    private fun notify(text: String) {
        notice = text
        noticeAt = clock
    }

    internal fun pickTower(type: TowerType) {
        val wd = world ?: return
        selected = null
        obstacle = -1
        aiming = false
        if (placing == type) {
            placing = null
            return
        }
        when {
            wd.towerLimitReached -> {
                notify(Tx.TOWER_LIMIT.f(wd.rules.maxTowers))
                game.sound(Sfx.ERROR, 0.4f)
            }
            wd.quarryLimitReached(type) -> {
                notify(Tx.QUARRY_LIMIT.f(World.MAX_QUARRIES))
                game.sound(Sfx.ERROR, 0.4f)
            }
            wd.money < wd.buildCost(type) -> {
                moneyFlash = 0.6f
                game.sound(Sfx.ERROR, 0.4f)
            }
            else -> {
                placing = type
                if (type.role == Role.ROAD) notify(Tx.ON_ROAD.t)
            }
        }
    }

    internal fun useAbility(a: Ability) {
        val wd = world ?: return
        when (a) {
            Ability.AIRSTRIKE -> if (wd.abilityReady(a)) {
                aiming = !aiming
                placing = null
                selected = null
                obstacle = -1
            } else {
                game.sound(Sfx.ERROR, 0.4f)
            }
            Ability.FREEZE -> if (wd.freeze()) game.vibrate(25) else game.sound(Sfx.ERROR, 0.4f)
            Ability.REPAIR -> if (wd.repair()) game.vibrate(25) else game.sound(Sfx.ERROR, 0.4f)
        }
    }

    private fun sell(wd: World, t: Tower) {
        if (t.invested >= SELL_CONFIRM && (sellArmed !== t || clock - sellArmedAt > SELL_WINDOW)) {
            sellArmed = t
            sellArmedAt = clock
            return
        }
        wd.sell(t)
        selected = null
        sellArmed = null
    }

    private val undoable: Tower?
        get() {
            val t = lastBuilt ?: return null
            val wd = world ?: return null
            if (t !in wd.towers || t.level != 0 || t.branch != null || clock - lastBuiltAt > UNDO_TIME) return null
            return t
        }

    // --- drawing --------------------------------------------------------------------------

    private fun layout() {
        hudH = d(46f)
        stripH = d(50f)
        shopH = d(110f)
        shopY = ui.h - ui.bottom - shopH
        stripY = shopY - stripH
        val mapTop = ui.top + hudH
        val mapH = stripY - mapTop
        cell = min(ui.w / COLS, mapH / ROWS)
        mapX = (ui.w - cell * COLS) / 2f
        mapY = mapTop + (mapH - cell * ROWS) / 2f
    }

    fun render(g: Gfx) {
        val wd = world ?: return
        layout()
        g.fillRect(0f, ui.top + hudH, ui.w, stripY - ui.top - hudH, 0xFF0B100E.toInt())

        g.save()
        g.clip(mapX, mapY, cell * COLS, cell * ROWS)
        if (wd.shake > 0f) {
            val k = wd.shake * cell * 0.12f
            g.translate(sin(clock * 90f) * k, sin(clock * 77f + 1f) * k)
        }
        g.translate(mapX, mapY)
        g.scale(cell * zoom)
        g.translate(-viewLeft, -viewTop)
        // The still terrain is kept as a picture; a few sizes are enough while zooming.
        val sharp = if (zoom < 1.25f) 1f else if (zoom < 1.75f) 1.5f else 2f
        renderer.drawWorld(g, wd, clock, min(cell * sharp, MAX_TERRAIN_PX))
        drawPlacement(g, wd)
        g.restore()

        drawTexts(g, wd)
        drawBossAlert(g, wd)
        drawBanner(g, wd)
        drawHint(g, wd)
        drawHud(g, wd)
        drawStrip(g, wd)
        val sel = selected
        when {
            sel != null -> drawTowerPanel(g, wd, sel)
            obstacle >= 0 -> drawObstaclePanel(g, wd)
            else -> drawShop(g, wd)
        }

        if (overlay != Overlay.NONE) {
            ui.begin()
            g.fillRect(0f, 0f, ui.w, ui.h, Ui.SCRIM)
            when (overlay) {
                Overlay.PAUSE -> drawPause(g)
                Overlay.SETTINGS -> game.menus.settingsPanel(g, inBattle = true) { overlay = Overlay.PAUSE }
                Overlay.RESULT -> drawResult(g, wd)
                Overlay.CARD -> drawCard(g)
                Overlay.NONE -> Unit
            }
        }
    }

    private fun drawPlacement(g: Gfx, wd: World) {
        val sel = selected
        if (sel != null) {
            val range = wd.rangeOf(sel)
            if (range > 0f) {
                g.fillCircle(sel.x, sel.y, range, Colors.alpha(Ui.WHITE, 0.1f))
                g.strokeCircle(sel.x, sel.y, range, Colors.alpha(Ui.WHITE, 0.6f), 0.04f)
            }
            g.strokeRoundRect(sel.x - 0.47f, sel.y - 0.47f, 0.94f, 0.94f, 0.18f, Ui.ACCENT, 0.06f)
        }
        if (obstacle >= 0) {
            val c = obstacle % COLS
            val r = obstacle / COLS
            g.strokeRoundRect(c + 0.04f, r + 0.04f, 0.92f, 0.92f, 0.18f, Ui.ACCENT, 0.06f)
        }
        if (aiming) {
            val pulse = 0.5f + 0.3f * sin(clock * 6f)
            if (touchingMap && aimX >= 0f) {
                g.fillCircle(aimX, aimY, World.AIRSTRIKE_RADIUS * 1.4f, Colors.alpha(Ui.RED, 0.18f))
                g.strokeCircle(aimX, aimY, World.AIRSTRIKE_RADIUS * 1.4f, Colors.alpha(Ui.RED, 0.9f), 0.05f)
                g.line(aimX - 0.5f, aimY, aimX + 0.5f, aimY, Ui.RED, 0.04f)
                g.line(aimX, aimY - 0.5f, aimX, aimY + 0.5f, Ui.RED, 0.04f)
            } else {
                g.strokeRoundRect(0.08f, 0.08f, COLS - 0.16f, ROWS - 0.16f, 0.3f, Colors.alpha(Ui.RED, pulse), 0.08f)
            }
            return
        }
        val type = placing ?: return
        val pulse = 0.3f + 0.2f * sin(clock * 5f)
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (!wd.canPlace(type, c, r)) continue
            g.strokeRoundRect(c + 0.1f, r + 0.1f, 0.8f, 0.8f, 0.14f, Colors.alpha(0xFFB8FFB8.toInt(), pulse), 0.045f)
            g.fillCircle(c + 0.5f, r + 0.5f, 0.07f, Colors.alpha(0xFFB8FFB8.toInt(), pulse + 0.15f))
        }
        if (ghostCol < 0 || !touchingMap) return
        val ok = wd.canBuild(type, ghostCol, ghostRow)
        val gx = ghostCol + 0.5f
        val gy = ghostRow + 0.5f
        val tint = if (ok) Ui.GREEN else Ui.RED
        g.fillRect(ghostCol.toFloat(), ghostRow.toFloat(), 1f, 1f, Colors.alpha(tint, 0.35f))
        val range = type.levels[0].range * wd.rules.rangeMul
        if (type.role == Role.ATTACK || type.role == Role.SUPPORT) {
            g.fillCircle(gx, gy, range, Colors.alpha(tint, 0.12f))
            g.strokeCircle(gx, gy, range, Colors.alpha(tint, 0.8f), 0.05f)
        }
        if (ok) renderer.drawTowerShape(g, type, 0, null, gx, gy, -90f, 0f, 0f, clock)
    }

    private fun drawTexts(g: Gfx, wd: World) {
        for (t in wd.texts) {
            val a = (t.life / 0.4f).coerceIn(0f, 1f)
            val size = d(12f) * t.size
            val tx = screenX(t.x)
            val ty = screenY(t.y)
            if (ty < mapY || ty > mapY + cell * ROWS + d(10f)) continue
            ui.text(g, t.text, tx + d(1f), ty + d(1f), size, Colors.alpha(0xFF000000.toInt(), a * 0.8f), Align.CENTER)
            ui.text(g, t.text, tx, ty, size, Colors.alpha(t.color, a), Align.CENTER)
        }
    }

    private fun drawBossAlert(g: Gfx, wd: World) {
        if (wd.bossAlert <= 0f) return
        val k = min(1f, wd.bossAlert / 0.6f) * (0.55f + 0.45f * abs(sin(clock * 7f)))
        val mw = cell * COLS
        val mh = cell * ROWS
        for (i in 0 until 4) {
            val inset = i * d(5f)
            g.strokeRoundRect(mapX + inset, mapY + inset, mw - inset * 2f, mh - inset * 2f, d(6f), Colors.alpha(Ui.RED, k * (0.5f - i * 0.11f)), d(6f))
        }
    }

    private fun drawBanner(g: Gfx, wd: World) {
        val b = wd.banner ?: return
        val a = min(1f, b.timer / 0.4f) * min(1f, (b.maxTimer - b.timer) / 0.15f + 0.3f)
        val title = b.title()
        val sub = b.sub?.invoke()
        val bw = min(ui.w - d(32f), max(g.textWidth(title, d(17f)), if (sub != null) g.textWidth(sub, d(12f)) else 0f) + d(36f))
        val bh = if (sub != null) d(56f) else d(38f)
        val x = (ui.w - bw) / 2f
        val y = mapY + d(12f)
        g.fillRoundRect(x, y, bw, bh, d(12f), Colors.alpha(0xE6101614.toInt(), a))
        g.strokeRoundRect(x, y, bw, bh, d(12f), Colors.alpha(b.color, a), d(1.5f))
        ui.textMid(g, title, ui.w / 2f, y + d(19f), d(17f), Colors.alpha(b.color, a), maxW = bw - d(20f))
        if (sub != null) ui.textMid(g, sub, ui.w / 2f, y + d(40f), d(12f), Colors.alpha(Ui.TEXT, a), maxW = bw - d(20f))
    }

    private fun drawHint(g: Gfx, wd: World) {
        val s = session ?: return
        if (s.mode != Mode.CAMPAIGN || s.level.id != 1 || wd.phase != Phase.READY) return
        val hint = when {
            placing != null -> Tx.HINT_DRAG.t
            wd.towers.isEmpty() -> Tx.HINT_PICK.t
            else -> Tx.HINT_START.t
        }
        val bw = min(ui.w - d(24f), g.textWidth(hint, d(13f)) + d(28f))
        val x = (ui.w - bw) / 2f
        val y = mapY + d(10f)
        g.fillRoundRect(x, y, bw, d(36f), d(18f), 0xE6101614.toInt())
        g.strokeRoundRect(x, y, bw, d(36f), d(18f), Ui.ACCENT, d(1.2f))
        ui.textMid(g, hint, ui.w / 2f, y + d(18f), d(13f), Ui.TEXT, maxW = bw - d(16f))
    }

    private fun drawHud(g: Gfx, wd: World) {
        val s = session ?: return
        g.fillRect(0f, 0f, ui.w, ui.top + hudH, Ui.PANEL)
        g.fillRect(0f, ui.top + hudH - d(1f), ui.w, d(1f), Ui.EDGE)
        val cy = ui.top + hudH / 2f
        val low = wd.lives <= wd.maxLives / 4
        ui.heart(g, d(22f), cy, d(18f), Ui.RED)
        ui.textMid(g, "${wd.lives}", d(37f), cy, d(17f), if (low && sin(clock * 10f) > 0f) Ui.RED else Ui.TEXT, Align.LEFT)
        ui.coin(g, d(86f), cy, d(18f))
        val moneyColor = if (moneyFlash > 0f && sin(clock * 30f) > 0f) Ui.RED else Ui.GOLD
        ui.textMid(g, "${wd.money}", d(100f), cy, d(17f), moneyColor, Align.LEFT)

        val waveText = if (wd.endless) Tx.WAVE_N.f(max(1, wd.wavesStarted)) else Tx.WAVE_N_OF.f(max(1, wd.wavesStarted), wd.totalWaves)
        val pauseX = ui.w - d(12f) - d(40f)
        val speedX = pauseX - d(8f) - d(50f)
        var badges = speedX - d(6f)
        if (s.challenge) {
            badges -= d(22f)
            ui.medal(g, badges + d(11f), cy, d(20f), !wd.challengeFailed)
        }
        if (s.difficulty == Difficulty.HARD) {
            badges -= d(22f)
            ui.skull(g, badges + d(11f), cy, d(18f), Ui.RED)
        }
        val waveLeft = d(150f)
        ui.textMid(g, waveText, (waveLeft + badges) / 2f, cy, d(14f), Ui.TEXT, maxW = max(d(40f), badges - waveLeft - d(4f)))
        ui.button(g, "speed", speedX, cy - d(17f), d(50f), d(34f), "×$speed", if (speed > 1) Style.PRIMARY else Style.NORMAL, size = 15f) {
            speed = if (speed >= 3) 1 else speed + 1
        }
        ui.button(g, "pause", pauseX, cy - d(17f), d(40f), d(34f), "", icon = { gg, x, y, sz, c -> ui.pauseIcon(gg, x, y, sz * 0.9f, c) }) {
            openPause()
        }
    }

    private fun drawStrip(g: Gfx, wd: World) {
        g.fillRect(0f, stripY, ui.w, stripH, 0xFF111816.toInt())
        val pad = d(6f)
        val bs = stripH - pad * 2f
        var x = d(8f)
        for (a in Ability.entries) {
            if (!wd.rules.unlocked(a)) continue
            drawAbility(g, wd, a, x, stripY + pad, bs)
            x += bs + d(6f)
        }
        val waveW = min(ui.w * 0.36f, d(170f))
        val waveX = ui.w - waveW - d(8f)
        drawStripMiddle(g, wd, x + d(2f), waveX - d(6f))

        val label: String
        var style = Style.PRIMARY
        when {
            wd.phase == Phase.READY -> label = Tx.START_WAVE.t
            wd.countdown >= 0f -> label = Tx.NEXT_WAVE_IN.f(wd.wavesStarted + 1, wd.countdown.toInt() + 1, wd.earlyBonus)
            wd.nextWave == null -> {
                label = Tx.FINAL.t
                style = Style.NORMAL
            }
            else -> {
                label = Tx.FIGHTING.t
                style = Style.NORMAL
            }
        }
        val pulse = wd.phase == Phase.READY && sin(clock * 5f) > 0f
        ui.button(g, "wave", waveX, stripY + pad, waveW, stripH - pad * 2f, label, if (pulse) Style.NORMAL else style, wd.canCallWave, size = 13f) {
            wd.callWave()
        }
    }

    private fun drawAbility(g: Gfx, wd: World, a: Ability, x: Float, y: Float, s: Float) {
        val allowed = wd.rules.usable(a)
        val ready = wd.abilityReady(a)
        val active = a == Ability.AIRSTRIKE && aiming
        val id = "ab${a.name}"
        ui.area(id, x, y, s, s, allowed) { useAbility(a) }
        val sink = if (ui.down(id)) d(2f) else 0f
        g.fillRoundRect(x, y + d(3f), s, s, d(10f), 0xFF0A0E0C.toInt())
        g.fillRoundRect(x, y + sink, s, s, d(10f), if (active) 0xFF4A2410.toInt() else Ui.PANEL_LIGHT)
        val edge = when {
            active -> Ui.RED
            ready -> Colors.mix(Ui.ACCENT, Ui.GOLD, 0.5f + 0.5f * sin(clock * 4f))
            else -> Ui.EDGE
        }
        g.strokeRoundRect(x, y + sink, s, s, d(10f), edge, if (ready || active) d(2f) else d(1f))
        val cx = x + s / 2f
        val cy = y + s / 2f + sink
        renderer.drawAbilityIcon(g, a, cx, cy, s * 0.56f, if (ready || active) Ui.TEXT else Ui.DIM)
        val left = wd.cooldowns[a.ordinal]
        if (allowed && left > 0f) {
            val sweep = 360f * left / wd.cooldownOf(a)
            g.fillArc(cx, cy, s * 0.46f, -90f, sweep, Colors.alpha(0xFF000000.toInt(), 0.5f))
            ui.textMid(g, "${ceil(left).toInt()}", cx, cy, d(12f), Ui.TEXT)
        }
        if (!allowed) ui.cross(g, cx, cy, s * 0.7f, Colors.alpha(Ui.RED, 0.85f))
    }

    private fun drawStripMiddle(g: Gfx, wd: World, left: Float, right: Float) {
        val cy = stripY + stripH / 2f
        val width = right - left
        if (width < d(30f)) return
        if (aiming) {
            ui.paragraph(g, Tx.AIM_AIRSTRIKE.t, left, cy - d(15f), width, d(11.5f), Ui.ACCENT, spacing = 1.15f)
            return
        }
        val undo = undoable
        if (undo != null) {
            val bh = stripH - d(14f)
            ui.button(g, "undo", left, cy - bh / 2f, width, bh, Tx.UNDO.t, size = 12f, icon = { gg, x, y, s, c -> ui.undoIcon(gg, x, y, s, c) }) {
                world?.undo(undo)
                lastBuilt = null
            }
            val f = 1f - (clock - lastBuiltAt) / UNDO_TIME
            g.fillRoundRect(left + d(6f), cy + bh / 2f - d(4f), (width - d(12f)) * f, d(2.5f), d(1.2f), Ui.ACCENT)
            return
        }
        val n = notice
        if (n != null && clock - noticeAt < NOTICE_TIME) {
            ui.paragraph(g, n, left, cy - d(15f), width, d(11.5f), Ui.ACCENT, spacing = 1.15f)
            return
        }
        val type = placing
        if (type != null) {
            ui.paragraph(g, type.hint.t, left, cy - d(15f), width, d(11f), Ui.TEXT, spacing = 1.15f)
            return
        }
        val next = wd.nextWave
        if (next == null) {
            ui.textMid(g, Tx.LAST_WAVE.t, left, cy, d(12f), Ui.ACCENT, Align.LEFT, maxW = width)
            return
        }
        ui.text(g, Tx.NEXT.t, left, cy - d(9f), d(9f), Ui.DIM, maxW = width)
        var x = left
        for ((t, n2) in next.preview.take(4)) {
            val label = "×$n2"
            val tw = g.textWidth(label, d(11f))
            if (x + d(20f) + tw > right) break
            renderer.drawEnemyIcon(g, t, x + d(9f), cy + d(7f), d(17f), clock)
            ui.text(g, label, x + d(19f), cy + d(11f), d(11f), if (t.boss) Ui.RED else Ui.TEXT)
            x += d(23f) + tw + d(4f)
        }
    }

    private fun drawShop(g: Gfx, wd: World) {
        g.fillRect(0f, shopY, ui.w, shopH + ui.bottom, Ui.PANEL)
        g.fillRect(0f, shopY, ui.w, d(1f), Ui.EDGE)
        val types = wd.rules.towers
        if (types.isEmpty()) return
        val margin = d(8f)
        val gap = d(6f)
        val fits = types.size <= 6
        val cw = if (fits) (ui.w - margin * 2 - gap * (types.size - 1)) / types.size else (ui.w - margin * 2 - gap * 5) / 5.5f
        val ch = shopH - d(14f)
        val content = margin * 2 + types.size * cw + (types.size - 1) * gap
        val off = ui.beginScroll(g, "shop", 0f, shopY, ui.w, shopH, content, horizontal = true)
        for ((i, type) in types.withIndex()) {
            val x = margin + i * (cw + gap) - off
            val y = shopY + d(7f)
            if (x > ui.w || x + cw < 0f) continue
            val cost = wd.buildCost(type)
            val blocked = wd.towerLimitReached || wd.quarryLimitReached(type)
            val affordable = wd.money >= cost && !blocked
            val active = placing == type
            val id = "shop${type.name}"
            ui.area(id, x, y, cw, ch) { pickTower(type) }
            val down = ui.down(id)
            g.fillRoundRect(x, y + (if (down) d(2f) else 0f), cw, ch, d(10f), if (active) 0xFF3A2A12.toInt() else Ui.PANEL_LIGHT)
            g.strokeRoundRect(x, y, cw, ch, d(10f), if (active) Ui.ACCENT else Ui.EDGE, if (active) d(2f) else d(1f))
            val icon = min(cw * 0.62f, d(40f))
            renderer.drawTowerIcon(g, type, 0, x + cw / 2f, y + d(8f) + icon / 2f, icon, clock)
            ui.textMid(g, type.short.t, x + cw / 2f, y + ch - d(31f), d(10.5f), if (affordable) Ui.TEXT else Ui.DIM, maxW = cw - d(6f))
            val costText = "$cost"
            ui.textMid(g, costText, x + cw / 2f + d(6f), y + ch - d(13f), d(13f), if (affordable) Ui.GOLD else Ui.DIM)
            ui.coin(g, x + cw / 2f - g.textWidth(costText, d(13f)) / 2f - d(4f), y + ch - d(13f), d(10f))
            if (type.role == Role.ROAD) {
                g.fillRoundRect(x + d(4f), y + d(4f), d(30f), d(13f), d(6f), 0xFF5A4630.toInt())
                ui.textMid(g, Tx.ROAD.t, x + d(19f), y + d(10.5f), d(8f), Ui.TEXT, maxW = d(28f))
            }
            if (!affordable) g.fillRoundRect(x, y, cw, ch, d(10f), 0x660E1412)
        }
        ui.endScroll(g, "shop")
    }

    private fun panelBase(g: Gfx) {
        g.fillRect(0f, shopY, ui.w, shopH + ui.bottom, Ui.PANEL)
        g.fillRect(0f, shopY, ui.w, d(1f), Ui.EDGE)
    }

    private fun drawTowerPanel(g: Gfx, wd: World, t: Tower) {
        panelBase(g)
        if (t.atFork && wd.rules.branches) drawFork(g, wd, t) else drawTowerInfo(g, wd, t)
    }

    private fun towerName(t: Tower): String = t.branch?.title?.t ?: "${t.type.title.t} · ${Tx.LEVEL_N.f(t.level + 1)}"

    private fun drawTowerInfo(g: Gfx, wd: World, t: Tower) {
        val x0 = d(12f)
        val icon = d(38f)
        renderer.drawTowerIcon(g, t.type, t.level, x0 + icon / 2f, shopY + d(8f) + icon / 2f, icon, clock, t.branch)
        val tx = x0 + icon + d(10f)
        val maxW = ui.w - tx - d(12f)
        ui.text(g, towerName(t), tx, shopY + d(21f), d(15f), Ui.TEXT, maxW = maxW)
        val tally = if (t.type == TowerType.QUARRY) Tx.EARNED.f(t.damageDealt.roundToInt()) else Tx.KILLS.f(t.kills)
        ui.text(g, statLine(wd, t.type, t.stats, t.branch) + " · " + tally, tx, shopY + d(38f), d(11.5f), Ui.DIM, maxW = maxW)
        val hint = when {
            t.canUpgrade -> Tx.NEXT_LEVEL.f(statLine(wd, t.type, t.type.levels[t.level + 1], null))
            t.branch != null -> t.branch!!.hint.t
            !wd.rules.branches -> Tx.BRANCH_LOCKED.f(BRANCH_UNLOCK)
            else -> t.type.hint.t
        }
        ui.text(g, hint, tx, shopY + d(54f), d(11f), 0xFF8FCF8F.toInt(), maxW = maxW)

        val by = shopY + d(66f)
        val bh = d(34f)
        val gap = d(6f)
        val aims = t.type.aims
        val total = ui.w - d(24f) - gap * (if (aims) 3 else 2)
        val closeW = d(42f)
        val modeW = if (aims) total * 0.27f else 0f
        val sellW = (total - closeW - modeW) * 0.42f
        val upW = total - closeW - modeW - sellW
        var x = d(12f)
        if (t.canUpgrade) {
            val cost = wd.upgradeCost(t)
            ui.button(g, "up", x, by, upW, bh, Tx.UPGRADE.f(cost), Style.PRIMARY, wd.money >= cost, size = 13f) { wd.upgrade(t) }
        } else {
            ui.button(g, "up", x, by, upW, bh, Tx.MAX.t, enabled = false, size = 13f) {}
        }
        x += upW + gap
        drawSellButton(g, wd, t, x, by, sellW, bh)
        x += sellW + gap
        if (aims) {
            ui.button(g, "mode", x, by + d(4f), modeW, bh - d(4f), t.mode.title.t, size = 12f) {
                t.mode = TargetMode.entries[(t.mode.ordinal + 1) % TargetMode.entries.size]
            }
            g.fillRoundRect(x + modeW / 2f - d(20f), by - d(5f), d(40f), d(12f), d(6f), Ui.PANEL)
            ui.textMid(g, Tx.TARGET.t, x + modeW / 2f, by + d(1f), d(8.5f), Ui.DIM, maxW = d(38f))
            x += modeW + gap
        }
        ui.button(g, "close", x, by, closeW, bh, "", Style.GHOST, icon = { gg, cx, cy, s, c -> ui.cross(gg, cx, cy, s * 0.8f, c) }) { selected = null }
    }

    private fun drawSellButton(g: Gfx, wd: World, t: Tower, x: Float, y: Float, bw: Float, bh: Float) {
        val value = wd.sellValue(t)
        if (!wd.rules.canSell) {
            ui.button(g, "sell", x, y, bw, bh, Tx.NO_SELLING.t, enabled = false, size = 12f) {}
            return
        }
        val armed = sellArmed === t && clock - sellArmedAt <= SELL_WINDOW
        ui.button(g, "sell", x, y, bw, bh, if (armed) Tx.SELL_SURE.f(value) else Tx.SELL.f(value), if (armed) Style.DANGER else Style.NORMAL, size = 12f) {
            sell(wd, t)
        }
    }

    private fun drawFork(g: Gfx, wd: World, t: Tower) {
        val x0 = d(12f)
        val icon = d(28f)
        renderer.drawTowerIcon(g, t.type, t.level, x0 + icon / 2f, shopY + d(6f) + icon / 2f, icon, clock)
        val closeW = d(38f)
        val sellW = d(96f)
        val bx = ui.w - d(12f) - closeW
        ui.button(g, "close", bx, shopY + d(6f), closeW, d(30f), "", Style.GHOST, icon = { gg, cx, cy, s, c -> ui.cross(gg, cx, cy, s * 0.8f, c) }) {
            selected = null
        }
        drawSellButton(g, wd, t, bx - d(6f) - sellW, shopY + d(6f), sellW, d(30f))
        val tx = x0 + icon + d(8f)
        val maxW = bx - d(6f) - sellW - tx - d(8f)
        ui.text(g, towerName(t), tx, shopY + d(18f), d(13.5f), Ui.TEXT, maxW = maxW)
        ui.text(g, Tx.CHOOSE_BRANCH.t, tx, shopY + d(33f), d(10.5f), Ui.ACCENT, maxW = maxW)

        val top = shopY + d(42f)
        val ch = shopH - d(48f)
        val gap = d(8f)
        val cw = (ui.w - d(24f) - gap) / 2f
        for ((k, b) in t.type.branches.withIndex()) {
            val x = d(12f) + k * (cw + gap)
            val cost = wd.branchCost(b)
            val ok = wd.money >= cost
            val id = "branch${b.name}"
            ui.area(id, x, top, cw, ch, ok) { wd.branch(t, b) }
            val sink = if (ui.down(id)) d(2f) else 0f
            g.fillRoundRect(x, top + sink, cw, ch, d(10f), if (ok) 0xFF2A2416.toInt() else Ui.PANEL_LIGHT)
            g.strokeRoundRect(x, top + sink, cw, ch, d(10f), if (ok) Ui.GOLD else Ui.EDGE, if (ok) d(1.5f) else d(1f))
            val bi = d(30f)
            renderer.drawTowerIcon(g, t.type, 2, x + d(6f) + bi / 2f, top + sink + d(6f) + bi / 2f, bi, clock, b)
            val lx = x + d(12f) + bi
            ui.text(g, b.title.t, lx, top + sink + d(16f), d(12.5f), if (ok) Ui.TEXT else Ui.DIM, maxW = cw - bi - d(18f))
            val costText = "$cost"
            ui.coin(g, lx + d(5f), top + sink + d(29f), d(10f))
            ui.text(g, costText, lx + d(13f), top + sink + d(33f), d(12f), if (ok) Ui.GOLD else Ui.DIM)
            val lines = ui.lines(g, b.hint.t, d(9.5f), cw - d(12f))
            for ((i, l) in lines.take(2).withIndex()) {
                ui.text(g, l, x + d(6f), top + sink + d(48f) + i * d(11.5f), d(9.5f), Ui.DIM, maxW = cw - d(12f))
            }
        }
    }

    private fun drawObstaclePanel(g: Gfx, wd: World) {
        panelBase(g)
        val c = obstacle % COLS
        val r = obstacle / COLS
        val kind = wd.map.terrain(c, r)
        val icon = d(44f)
        renderer.drawObstacle(g, kind, wd.map.biome, d(12f) + icon / 2f, shopY + d(10f) + icon / 2f, icon)
        val tx = d(12f) + icon + d(12f)
        val name = when (kind) {
            'T' -> Tx.TREE
            'C' -> Tx.CACTUS
            else -> Tx.ROCK
        }
        ui.text(g, name.t, tx, shopY + d(26f), d(15f), Ui.TEXT, maxW = ui.w - tx - d(12f))
        ui.text(g, Tx.CLEAR_HINT.t, tx, shopY + d(45f), d(11.5f), Ui.DIM, maxW = ui.w - tx - d(12f))
        val by = shopY + d(66f)
        val bh = d(34f)
        val closeW = d(42f)
        val cost = wd.clearCost()
        ui.button(g, "clear", d(12f), by, ui.w - d(30f) - closeW, bh, Tx.CLEAR.f(cost), Style.PRIMARY, wd.canClear(c, r), size = 13f) {
            wd.clear(c, r)
            obstacle = -1
        }
        ui.button(g, "close", ui.w - d(12f) - closeW, by, closeW, bh, "", Style.GHOST, icon = { gg, cx, cy, s, cc -> ui.cross(gg, cx, cy, s * 0.8f, cc) }) {
            obstacle = -1
        }
    }

    fun statLine(wd: World, type: TowerType, s: TowerLevel, branch: Branch?): String {
        val dmg = s.damage * wd.rules.damageMul
        val range = s.range * wd.rules.rangeMul
        val parts = ArrayList<String>()
        when (type) {
            TowerType.TURRET, TowerType.SAM, TowerType.SNIPER -> {
                parts += Tx.DMG.f(Num.int(dmg))
                parts += Tx.PER_SECOND.f(Num.one(s.rate))
                if (branch == Branch.SALVO) parts += Tx.MISSILES.f(s.extra.toInt())
                parts += Tx.RANGE.f(Num.one(range))
            }
            TowerType.SHOTGUN -> {
                if (branch == Branch.SLUG) {
                    parts += Tx.DMG.f(Num.int(dmg))
                    parts += Tx.PIERCE.f(s.extra.toInt())
                } else {
                    parts += Tx.PELLETS.f(s.extra.toInt(), Num.int(dmg))
                }
                parts += Tx.PER_SECOND.f(Num.one(s.rate))
                parts += Tx.RANGE.f(Num.one(range))
            }
            TowerType.FLAMER -> {
                parts += Tx.FIRE.f(Num.int(dmg))
                if (branch == Branch.CRYO) parts += Tx.SLOW.f(Num.percent(s.extra2))
                parts += Tx.RANGE.f(Num.one(range))
            }
            TowerType.ROCKET -> {
                parts += Tx.DMG.f(Num.int(dmg))
                parts += Tx.BLAST.f(Num.one(s.extra))
                parts += Tx.RANGE.f(Num.one(range))
            }
            TowerType.TESLA -> {
                parts += Tx.DMG.f(Num.int(dmg))
                parts += Tx.CHAIN.f(s.extra.toInt())
                parts += if (branch == Branch.STUN) Tx.STUN.f(Num.one(s.extra2)) else Tx.SLOW.f(Num.percent(s.extra2))
            }
            TowerType.MINES -> {
                if (branch == Branch.SPIKES) {
                    parts += Tx.FIRE.f(Num.int(dmg))
                    parts += Tx.SLOW.f(Num.percent(s.extra))
                } else {
                    parts += Tx.DMG.f(Num.int(dmg))
                    parts += Tx.BLAST.f(Num.one(s.range))
                    parts += Tx.REARM.f(Num.one(1f / s.rate))
                }
            }
            TowerType.RADAR -> {
                parts += Tx.AURA.f(Num.percent(s.extra))
                parts += Tx.RANGE.f(Num.one(range))
                if (branch == Branch.JAMMER) parts += Tx.SLOW.f(Num.percent(s.extra2))
            }
            TowerType.QUARRY -> parts += Tx.COINS_PER_WAVE.f(s.damage.toInt())
        }
        return parts.joinToString(" · ")
    }

    // --- overlays -------------------------------------------------------------------------

    /** Draws a centred dialog [ph] high; returns its x, y and width. */
    private fun panelRect(g: Gfx, ph: Float): FloatArray {
        val pw = min(ui.w - d(40f), d(340f))
        val px = (ui.w - pw) / 2f
        val py = max(ui.top + d(10f), (ui.h - ph) / 2f)
        ui.panel(g, px, py, pw, ph)
        return floatArrayOf(px, py, pw)
    }

    private fun drawPause(g: Gfx) {
        val s = session ?: return
        val info = buildList {
            if (s.mode == Mode.CAMPAIGN) add(s.difficulty.title.t)
            if (s.challenge) s.level.challenge?.let { add(it.title) }
            for (m in s.mods) add(m.title.t)
        }
        val ph = d(330f) + (if (info.isNotEmpty()) d(22f) else 0f)
        val (px, py, pw) = panelRect(g, ph)
        ui.textMid(g, Tx.PAUSED.t, ui.w / 2f, py + d(36f), d(24f), Ui.TEXT)
        var y = py + d(58f)
        if (info.isNotEmpty()) {
            ui.textMid(g, info.joinToString(" · "), ui.w / 2f, y + d(6f), d(12f), Ui.ACCENT, maxW = pw - d(30f))
            y += d(22f)
        }
        val bx = px + d(24f)
        val bw = pw - d(48f)
        ui.button(g, "resume", bx, y, bw, d(50f), Tx.RESUME.t, Style.PRIMARY, size = 17f, icon = { gg, cx, cy, sz, c -> ui.play(gg, cx, cy, sz, c) }) {
            overlay = Overlay.NONE
        }
        y += d(60f)
        ui.button(g, "restart", bx, y, bw, d(44f), Tx.RESTART.t) { game.restart() }
        y += d(54f)
        ui.button(g, "psettings", bx, y, bw, d(44f), Tx.SETTINGS_BTN.t, icon = { gg, cx, cy, sz, c -> ui.gear(gg, cx, cy, sz, c) }) {
            overlay = Overlay.SETTINGS
        }
        y += d(54f)
        ui.button(g, "quit", bx, y, bw, d(44f), Tx.TO_MENU.t, Style.GHOST) {
            overlay = Overlay.NONE
            leave()
        }
        y += d(52f)
        ui.paragraph(g, Tx.SAVED_NOTE.t, ui.w / 2f, y, pw - d(40f), d(11f), Ui.DIM, Align.CENTER)
    }

    private fun drawCard(g: Gfx) {
        val card = cards.firstOrNull() ?: run {
            overlay = Overlay.NONE
            return
        }
        val pw = min(ui.w - d(40f), d(340f))
        val textH = ui.lines(g, card.text, d(13.5f), pw - d(48f)).size * d(13.5f) * 1.35f
        val ph = d(250f) + textH
        val (px, py) = panelRect(g, ph)
        ui.textMid(g, card.kind.t, ui.w / 2f, py + d(28f), d(13f), Ui.ACCENT)
        val iconY = py + d(92f)
        g.fillCircle(ui.w / 2f, iconY, d(46f), Ui.PANEL_LIGHT)
        g.strokeCircle(ui.w / 2f, iconY, d(46f), Ui.EDGE, d(1.2f))
        card.icon(g, ui.w / 2f, iconY, d(64f))
        ui.textMid(g, card.title, ui.w / 2f, py + d(162f), d(21f), Ui.TEXT, maxW = pw - d(30f))
        ui.paragraph(g, card.text, ui.w / 2f, py + d(176f), pw - d(48f), d(13.5f), Ui.DIM, Align.CENTER)
        ui.button(g, "gotit", px + d(24f), py + ph - d(66f), pw - d(48f), d(48f), Tx.GOT_IT.t, Style.PRIMARY, size = 16f) { closeCard() }
    }

    private fun drawResult(g: Gfx, wd: World) {
        val s = session ?: return
        val won = wd.phase == Phase.WON
        val next = if (s.mode == Mode.CAMPAIGN && won) Levels.all.firstOrNull { it.id == s.level.id + 1 } else null
        val lines = ArrayList<Pair<String, Int>>()
        when (s.mode) {
            Mode.CAMPAIGN -> {
                if (!won) lines += Tx.WAVE_OF.f(wd.wavesStarted, wd.totalWaves) to Ui.TEXT
                lines += Tx.LIVES.f(wd.lives, wd.maxLives) to Ui.DIM
                lines += Tx.KILLED.f(wd.kills) to Ui.DIM
                if (won && resultHard) lines += Tx.HARD_WIN.t to Ui.RED
                if (s.challenge) lines += (if (won && resultMedal) Tx.MEDAL_WON.t to Ui.GOLD else Tx.CHALLENGE_FAILED.t to Ui.DIM)
            }
            Mode.ENDLESS -> {
                lines += (if (resultRecord) Tx.NEW_RECORD.t to Ui.GOLD else Tx.RECORD.f(profile.bestEndless) to Ui.DIM)
                lines += Tx.KILLED.f(wd.kills) to Ui.DIM
            }
            Mode.DAILY -> {
                if (won) {
                    lines += Tx.DAILY_DONE.t to Ui.GOLD
                    lines += Tx.STREAK.f(profile.dailyStreak(s.day)) to Ui.TEXT
                } else {
                    lines += Tx.WAVE_OF.f(wd.wavesStarted, wd.totalWaves) to Ui.TEXT
                }
                lines += Tx.KILLED.f(wd.kills) to Ui.DIM
            }
        }
        val achievements = resultAchievements.take(3)
        val buttonsH = if (next != null) d(160f) else d(110f)
        val headH = if (s.mode == Mode.ENDLESS || (s.mode == Mode.CAMPAIGN && won)) d(150f) else d(80f)
        val achH = if (achievements.isEmpty()) 0f else d(26f) + achievements.size * d(22f)
        val ph = headH + lines.size * d(22f) + achH + buttonsH + d(20f)
        val (px, py, pw) = panelRect(g, ph)
        val since = clock - resultAt
        val title = if (won) Tx.VICTORY.t else Tx.DEFEAT.t
        ui.textMid(g, title, ui.w / 2f, py + d(40f), d(26f), if (won) Ui.GREEN else Ui.RED)
        var y = py + d(70f)
        if (s.mode == Mode.CAMPAIGN && won) {
            for (k in 0 until 3) {
                val appear = ((since - 0.25f - k * 0.3f) * 5f).coerceIn(0f, 1f)
                val pop = if (appear < 1f) appear * 1.2f else 1f
                ui.star(g, ui.w / 2f + (k - 1) * d(54f), y + d(30f), d(22f) * max(0.4f, pop), k < resultStars && appear > 0f)
            }
            y += d(80f)
        } else if (s.mode == Mode.ENDLESS) {
            ui.textMid(g, Tx.HELD.t, ui.w / 2f, y + d(8f), d(14f), Ui.DIM)
            ui.textMid(g, Tx.WAVES_COUNT.f(max(0, wd.wavesStarted - 1)), ui.w / 2f, y + d(44f), d(30f), Ui.TEXT)
            y += d(80f)
        } else {
            y += d(10f)
        }
        for ((text, color) in lines) {
            ui.textMid(g, text, ui.w / 2f, y + d(8f), d(14f), color, maxW = pw - d(30f))
            y += d(22f)
        }
        if (achievements.isNotEmpty()) {
            y += d(6f)
            ui.textMid(g, Tx.NEW_ACHIEVEMENTS.t, ui.w / 2f, y + d(6f), d(12f), Ui.ACCENT)
            y += d(20f)
            for (a in achievements) {
                ui.medal(g, px + d(36f), y + d(8f), d(16f), true)
                ui.textMid(g, a.title.t, px + d(52f), y + d(8f), d(13f), Ui.TEXT, Align.LEFT, maxW = pw - d(70f))
                y += d(22f)
            }
        }
        y += d(14f)
        val bx = px + d(24f)
        val bw = pw - d(48f)
        if (next != null) {
            ui.button(g, "next", bx, y, bw, d(48f), Tx.NEXT_LEVEL_BTN.f(next.name.t.uppercase()), Style.PRIMARY, size = 15f) {
                game.startCampaign(next, s.difficulty.takeIf { profile.stars(next.id) > 0 || it != Difficulty.HARD } ?: Difficulty.NORMAL, false)
            }
            y += d(58f)
            ui.button(g, "again", bx, y, bw, d(42f), Tx.AGAIN.t) { game.restart() }
        } else {
            ui.button(g, "again", bx, y, bw, d(48f), Tx.AGAIN.t, Style.PRIMARY, size = 16f) { game.restart() }
        }
        y += if (next != null) d(52f) else d(58f)
        val backLabel = if (s.mode == Mode.CAMPAIGN) Tx.TO_LEVELS.t else Tx.TO_MENU.t
        ui.button(g, "levels", bx, y, bw, d(42f), backLabel, Style.GHOST) { leave() }
    }

    companion object {
        const val MAX_ZOOM = 2.5f
        const val MAX_TERRAIN_PX = 190f
        const val UNDO_TIME = 4f
        const val SELL_CONFIRM = 150
        const val SELL_WINDOW = 2.5f
        const val NOTICE_TIME = 2.5f
        const val SLOWMO_TIME = 0.6f
        const val SLOWMO_SCALE = 0.35f
    }
}
