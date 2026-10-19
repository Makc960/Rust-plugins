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

    internal var speed = 1
        private set
    private var acc = 0f
    internal var placing: TowerType? = null
    internal var selected: Tower? = null

    /** The commander is picked: a tap on the map sends him there. */
    internal var heroPicked = false

    /** Steps of the training battle, each waiting for one thing the player does. */
    internal enum class Lesson(val text: L) {
        INTRO(Tx.TUT_INTRO), PICK(Tx.TUT_PICK), PLACE(Tx.TUT_PLACE), START(Tx.TUT_START), WATCH(Tx.TUT_WATCH),
        SELECT(Tx.TUT_SELECT), UPGRADE(Tx.TUT_UPGRADE), HERO(Tx.TUT_HERO), MOVE(Tx.TUT_MOVE), WAVE2(Tx.TUT_WAVE2),
        SKILL(Tx.TUT_SKILL), STRIKE(Tx.TUT_STRIKE), SPEED(Tx.TUT_SPEED), FINISH(Tx.TUT_FINISH),
    }

    internal var lesson: Lesson? = null
        private set

    /** The grass cell the lesson suggests for the first squad, and where the commander stood. */
    internal var lessonCell = -1
        private set
    private var heroHomeX = 0f
    private var heroHomeY = 0f
    private var lessonPlateH = 0f
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

    /** When the store's rating window may open over the results of a won battle. */
    private var reviewAt = -1f

    /** Service the commander earned in the battle, and his new rank if it went up. */
    private var resultService = 0
    private var resultRankUp = 0

    /** Upgrade points a weekly operation's medals brought. */
    private var resultPoints = 0

    private class Card(val kind: L, val title: String, val text: String, val stats: List<String> = emptyList(), val icon: (Gfx, Float, Float, Float) -> Unit)

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
        val stats = listOf(Tx.HEALTH.f(type.hp.roundToInt()), Tx.SPEED.f(Num.one(type.speed)), Tx.ARMOR.f(type.armor.roundToInt()))
        cards += Card(Tx.CARD_ENEMY, type.title.t, type.hint.t, stats) { g, cx, cy, s -> renderer.drawEnemyIcon(g, type, cx, cy, s, clock) }
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
        heroPicked = false
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
        resultService = 0
        resultRankUp = 0
        resultPoints = 0
        reviewAt = -1f
        flushed = false
        cards.clear()
        ui.resetScroll("shop")
        if (!resumed) introCards(s)
        val hero = s.world.commander
        lesson = null
        if (s.mode == Mode.TUTORIAL) {
            lesson = Lesson.INTRO
            lessonCell = bestCell(s.world, TowerType.TURRET.levels[0].range)
            heroHomeX = hero?.tx ?: 0f
            heroHomeY = hero?.ty ?: 0f
            profile.markSeen(Tips.COMMANDER)
        }
        if (hero != null && !profile.seen(Tips.COMMANDER)) {
            profile.markSeen(Tips.COMMANDER)
            if (profile.tips) {
                cards += Card(Tx.CARD_TIP, Tx.TIP_COMMANDER.t, Tx.TIP_COMMANDER_TEXT.t) { g, cx, cy, sz -> renderer.drawCommanderIcon(g, hero.type, cx, cy, sz * 0.9f) }
            }
        }
    }

    /** What this level brings for the first time: new towers, abilities and tips. */
    private fun introCards(s: Session) {
        if (s.mode != Mode.CAMPAIGN) return
        val id = s.level.id
        val tips = profile.tips
        fun tip(key: String, title: L, text: L) {
            if (profile.seen(key)) return
            profile.markSeen(key)
            if (tips) cards += Card(Tx.CARD_TIP, title.t, text.t) { g, cx, cy, sz -> ui.icon(g, Icon.INFO, cx, cy, sz * 0.7f, Ui.ACCENT) }
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
        if (heroPicked && wd.commander == null) heroPicked = false
        updateLesson(wd)
        if (obstacle >= 0 && !wd.map.obstacle(obstacle % COLS, obstacle / COLS)) obstacle = -1
        if (aiming && !wd.abilityReady(Ability.AIRSTRIKE)) aiming = false
        if (reviewAt in 0f..clock && overlay == Overlay.RESULT) {
            reviewAt = -1f
            game.askReviewIfDue()
        }
        if (wd.over && overlay != Overlay.RESULT) {
            placing = null
            selected = null
            heroPicked = false
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
                reviewAt = clock + REVIEW_DELAY
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
                profile.recordDaily(s.day, waves, won, s.vip)
                if (won && wd.leaked == 0) profile.addStat(Stat.FLAWLESS, 1)
            }
            Mode.TUTORIAL -> {
                lesson = null
                if (won) profile.tutorialDone = true
            }
            Mode.WEEKLY -> if (won) {
                resultStars = Weekly.medal(wd.lives, wd.maxLives)
                resultPoints = profile.recordWeekly(Weekly.week(s.day), resultStars)
                if (wd.leaked == 0) profile.addStat(Stat.FLAWLESS, 1)
            }
        }
        val hero = wd.commander
        if (hero != null && s.mode != Mode.TUTORIAL) {
            resultService = Ranks.earned(hero, won)
            resultRankUp = profile.addService(hero.type, resultService)
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
                heroPicked -> heroPicked = false
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
        val missions = (s?.mode == Mode.DAILY || s?.mode == Mode.WEEKLY) && overlay == Overlay.RESULT
        if (missions) game.leaveBattle(Screen.DAILY) else game.leaveBattle(to)
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
        val hero = wd.commander
        val onHero = hero != null && hypot(fx - hero.x, fy - hero.y) < HERO_TAP
        if (hero != null && heroPicked) {
            // A tap on him lets him go; anywhere else he walks to.
            if (onHero) heroPicked = false else if (wd.moveCommander(fx, fy)) game.vibrate(10)
            return
        }
        if (onHero) {
            heroPicked = true
            selected = null
            obstacle = -1
            return
        }
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
        heroPicked = false
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
                heroPicked = false
                obstacle = -1
            } else {
                game.sound(Sfx.ERROR, 0.4f)
            }
            Ability.FREEZE -> if (wd.freeze()) game.vibrate(25) else game.sound(Sfx.ERROR, 0.4f)
            Ability.REPAIR -> if (wd.repair()) game.vibrate(25) else game.sound(Sfx.ERROR, 0.4f)
        }
    }

    // --- the tutorial ---------------------------------------------------------------------

    /** The grass cell that sees the most road: where the lesson puts the first squad. */
    private fun bestCell(wd: World, range: Float): Int {
        val p = FloatArray(4)
        var best = -1
        var most = -1
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (!wd.map.buildable(c, r)) continue
            var n = 0
            for (path in wd.map.paths) {
                var d = 0f
                while (d <= path.length) {
                    path.pointAt(d, p)
                    if (hypot(p[0] - c - 0.5f, p[1] - r - 0.5f) <= range) n++
                    d += 0.5f
                }
            }
            if (n > most) {
                most = n
                best = r * COLS + c
            }
        }
        return best
    }

    /** Moves the lesson on when its step is done, and helps where the step needs it. */
    private fun updateLesson(wd: World) {
        val step = lesson ?: return
        val hero = wd.commander
        val waveOver = wd.wavesStarted >= 1 && !wd.spawning && wd.enemies.isEmpty()
        val done = when (step) {
            Lesson.INTRO, Lesson.FINISH -> false
            Lesson.PICK -> placing != null || wd.towers.isNotEmpty()
            Lesson.PLACE -> wd.towers.isNotEmpty()
            Lesson.START -> wd.wavesStarted >= 1
            Lesson.WATCH -> waveOver
            Lesson.SELECT -> selected != null
            Lesson.UPGRADE -> wd.towers.any { it.level > 0 || it.branch != null }
            Lesson.HERO -> hero == null || heroPicked
            Lesson.MOVE -> hero == null || hero.tx != heroHomeX || hero.ty != heroHomeY
            Lesson.WAVE2 -> wd.wavesStarted >= 2
            // A missed grenade is no reason to get stuck: the lesson moves on when the wave is beaten.
            Lesson.SKILL -> hero == null || hero.skillCooldowns.any { it > 0f } || waveOver
            Lesson.STRIKE -> wd.airstrikes > 0
            Lesson.SPEED -> speed > 1
        }
        if (done) lesson = Lesson.entries[step.ordinal + 1]
        val now = lesson ?: return
        if (now == Lesson.PLACE && placing == null && wd.towers.isEmpty()) lesson = Lesson.PICK
        // The next wave waits while the player learns between waves.
        wd.holdWaves = now.ordinal in Lesson.WATCH.ordinal..Lesson.WAVE2.ordinal
        when (now) {
            Lesson.UPGRADE -> wd.towers.firstOrNull()?.let { if (it.canUpgrade && wd.money < wd.upgradeCost(it)) wd.money = wd.upgradeCost(it) }
            Lesson.STRIKE -> if (!aiming) wd.cooldowns[Ability.AIRSTRIKE.ordinal] = 0f
            else -> Unit
        }
    }

    /** Where the lesson points: a control by its id, or a spot on the map. */
    private fun lessonTarget(wd: World, step: Lesson): FloatArray? {
        fun spot(i: Int): FloatArray? = if (i < 0) null else {
            val c = i % COLS
            val r = i / COLS
            floatArrayOf(screenX(c.toFloat()), screenY(r.toFloat()), cell * zoom, cell * zoom)
        }
        val hero = wd.commander
        return when (step) {
            Lesson.PICK -> ui.rect("shop${TowerType.TURRET.name}")
            Lesson.PLACE -> spot(lessonCell)
            Lesson.START, Lesson.WAVE2 -> ui.rect("wave")
            Lesson.SELECT -> wd.towers.firstOrNull()?.let { spot(it.row * COLS + it.col) }
            Lesson.UPGRADE -> ui.rect("up")
            Lesson.HERO -> hero?.let {
                val r = cell * zoom * 0.6f
                floatArrayOf(screenX(it.x) - r, screenY(it.y) - r, r * 2f, r * 2f)
            }
            Lesson.SKILL -> if (heroPicked) ui.rect("skill0") else ui.rect("shopHero")
            Lesson.STRIKE -> if (aiming) null else ui.rect("ab${Ability.AIRSTRIKE.name}")
            Lesson.SPEED -> ui.rect("speed")
            else -> null
        }
    }

    /** The lesson's note over the map and a pulsing frame around what to press. */
    private fun drawLesson(g: Gfx, wd: World) {
        val step = lesson ?: return
        val target = lessonTarget(wd, step)
        if (target != null) {
            val pad = d(4f)
            val pulse = 0.55f + 0.45f * sin(clock * 6f)
            ui.outline(g, target[0] - pad, target[1] - pad, target[2] + pad * 2f, target[3] + pad * 2f, d(8f), Colors.alpha(Ui.GOLD, pulse), d(3f))
            // An arrow above it, bobbing.
            val ax = target[0] + target[2] / 2f
            val ay = target[1] - pad - d(6f) - d(5f) * abs(sin(clock * 4f))
            val s = d(10f)
            g.fillPolygon(floatArrayOf(ax - s, ax + s, ax), floatArrayOf(ay - s * 1.3f, ay - s * 1.3f, ay), 3, Ui.EDGE)
            g.fillPolygon(floatArrayOf(ax - s * 0.75f, ax + s * 0.75f, ax), floatArrayOf(ay - s * 1.15f, ay - s * 1.15f, ay - s * 0.2f), 3, Ui.GOLD)
        }
        val text = step.text.t
        val pw = min(ui.w - d(20f), d(380f))
        val withNext = step == Lesson.INTRO
        val textW = pw - d(32f) - (if (withNext) d(92f) else 0f)
        val textH = ui.lines(g, text, d(13f), textW).size * d(13f) * 1.25f
        val ph = max(d(54f), textH + d(34f))
        lessonPlateH = ph
        val px = (ui.w - pw) / 2f
        val py = mapY + d(8f)
        ui.chamfer(g, px, py, pw, ph, d(8f), Ui.EDGE)
        ui.chamfer(g, px + d(1f), py + d(1f), pw - d(2f), ph - d(2f), d(8f), Ui.ACCENT)
        ui.chamfer(g, px + d(1f), py + d(4f), pw - d(2f), ph - d(5f), d(8f), Colors.alpha(Ui.BAND, 0.97f))
        ui.tag(g, Tx.TUT_LABEL.f(step.ordinal + 1, Lesson.entries.size), px + d(12f), py + d(14f), d(9f))
        ui.paragraph(g, text, px + d(14f), py + d(24f), textW, d(13f), Ui.TEXT, spacing = 1.25f)
        if (withNext) {
            ui.button(g, "lessonNext", px + pw - d(98f), py + ph / 2f - d(19f), d(86f), d(38f), Tx.TUT_NEXT.t, Style.PRIMARY, size = 14f) {
                lesson = Lesson.PICK
            }
        }
    }

    /** Fires the commander's skill [k], or says why it cannot. */
    internal fun useSkill(k: Int) {
        val wd = world ?: return
        if (wd.useSkill(k)) {
            game.vibrate(20)
            return
        }
        game.sound(Sfx.ERROR, 0.4f)
        if (wd.skillReady(k)) notify(Tx.NO_TARGET.t)
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
            if (t !in wd.towers || t.level != 0 || t.branch != null || clock - lastBuiltAt > wd.rules.undoTime) return null
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
        g.fillRect(0f, ui.top + hudH, ui.w, stripY - ui.top - hudH, Ui.BG_DEEP)

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
        val hero = wd.commander
        when {
            heroPicked && hero != null -> drawCommanderPanel(g, wd, hero)
            sel != null -> drawTowerPanel(g, wd, sel)
            obstacle >= 0 -> drawObstaclePanel(g, wd)
            else -> drawShop(g, wd)
        }
        if (overlay == Overlay.NONE) drawLesson(g, wd)

        if (overlay != Overlay.NONE) {
            ui.begin()
            g.fillRect(0f, 0f, ui.w, ui.h, Ui.SCRIM)
            when (overlay) {
                Overlay.PAUSE -> drawPause(g)
                Overlay.SETTINGS -> game.menus.settingsPanel(g) { overlay = Overlay.PAUSE }
                Overlay.RESULT -> drawResult(g, wd)
                Overlay.CARD -> drawCard(g)
                Overlay.NONE -> Unit
            }
        }
    }

    private fun drawPlacement(g: Gfx, wd: World) {
        val hero = wd.commander
        if (hero != null && heroPicked) {
            val range = wd.commanderRange(hero)
            g.fillCircle(hero.x, hero.y, range, Colors.alpha(Ui.WHITE, 0.08f))
            g.strokeCircle(hero.x, hero.y, range, Colors.alpha(Ui.GOLD, 0.7f), 0.04f)
            g.strokeCircle(hero.x, hero.y, 0.44f, Ui.ACCENT, 0.06f)
        }
        if (hero != null && hero.moving) {
            g.line(hero.x, hero.y, hero.tx, hero.ty, Colors.alpha(Ui.GOLD, 0.45f), 0.04f)
            g.strokeCircle(hero.tx, hero.ty, 0.18f + 0.04f * sin(clock * 8f), Colors.alpha(Ui.GOLD, 0.9f), 0.05f)
            g.fillCircle(hero.tx, hero.ty, 0.05f, Ui.GOLD)
        }
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
            val size = d(12.5f) * t.size
            val tx = screenX(t.x)
            val ty = screenY(t.y)
            if (ty < mapY || ty > mapY + cell * ROWS + d(10f)) continue
            ui.text(g, t.text, tx + d(1.2f), ty + d(1.2f), size, Colors.alpha(Ui.EDGE, a * 0.85f), Align.CENTER, font = Font.DISPLAY)
            ui.text(g, t.text, tx, ty, size, Colors.alpha(t.color, a), Align.CENTER, font = Font.DISPLAY)
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

    /** A dark plate over the map with coloured bars at its ends. */
    private fun notePlate(g: Gfx, x: Float, y: Float, bw: Float, bh: Float, color: Int, a: Float) {
        ui.chamfer(g, x, y, bw, bh, d(8f), Colors.alpha(Ui.EDGE, a))
        ui.chamfer(g, x + d(1f), y + d(1f), bw - d(2f), bh - d(2f), d(8f), Colors.alpha(Ui.BAND, a * 0.95f))
        g.fillRect(x + d(6f), y + d(9f), d(4f), bh - d(18f), Colors.alpha(color, a))
        g.fillRect(x + bw - d(10f), y + d(9f), d(4f), bh - d(18f), Colors.alpha(color, a))
    }

    private fun drawBanner(g: Gfx, wd: World) {
        val b = wd.banner ?: return
        val a = min(1f, b.timer / 0.4f) * min(1f, (b.maxTimer - b.timer) / 0.15f + 0.3f)
        val title = b.title()
        val sub = b.sub?.invoke()
        val bw = min(ui.w - d(32f), max(g.textWidth(title, d(18f), Font.DISPLAY), if (sub != null) g.textWidth(sub, d(12.5f)) else 0f) + d(48f))
        val bh = if (sub != null) d(60f) else d(42f)
        val x = (ui.w - bw) / 2f
        // Under the tutorial's note, when there is one.
        val y = mapY + d(12f) + (if (lesson != null) lessonPlateH + d(4f) else 0f)
        notePlate(g, x, y, bw, bh, b.color, a)
        ui.textMid(g, title, ui.w / 2f, y + d(21f), d(18f), Colors.alpha(b.color, a), maxW = bw - d(32f), font = Font.DISPLAY)
        if (sub != null) ui.textMid(g, sub, ui.w / 2f, y + d(43f), d(12.5f), Colors.alpha(Ui.TEXT, a), maxW = bw - d(32f))
    }

    private fun drawHint(g: Gfx, wd: World) {
        val s = session ?: return
        if (s.mode != Mode.CAMPAIGN || s.level.id != 1 || wd.phase != Phase.READY) return
        val hint = when {
            placing != null -> Tx.HINT_DRAG.t
            wd.towers.isEmpty() -> Tx.HINT_PICK.t
            else -> Tx.HINT_START.t
        }
        val bw = min(ui.w - d(24f), g.textWidth(hint, d(13f)) + d(60f))
        val bh = d(40f)
        val x = (ui.w - bw) / 2f
        val y = mapY + d(10f)
        ui.chamfer(g, x, y, bw, bh, d(8f), Ui.EDGE)
        ui.chamfer(g, x + d(1f), y + d(1f), bw - d(2f), bh - d(2f), d(8f), Ui.ACCENT)
        ui.chamfer(g, x + d(36f), y + d(1f), bw - d(37f), bh - d(2f), d(8f), Colors.alpha(Ui.BAND, 0.96f))
        ui.icon(g, Icon.INFO, x + d(19f), y + bh / 2f, d(20f), Ui.DARK_TEXT)
        ui.textMid(g, hint, x + d(36f) + (bw - d(36f)) / 2f, y + bh / 2f, d(13f), Ui.TEXT, maxW = bw - d(52f))
    }

    /** A sunken readout with an icon and a number; returns its width. */
    private fun readout(g: Gfx, x: Float, cy: Float, minW: Float, value: String, color: Int, icon: (Float) -> Unit): Float {
        val w = max(minW, d(30f) + g.textWidth(value, d(17f), Font.DISPLAY) + d(10f))
        ui.well(g, x, cy - d(16f), w, d(32f))
        icon(x + d(15f))
        ui.textMid(g, value, x + d(29f), cy + d(1f), d(17f), color, Align.LEFT, font = Font.DISPLAY)
        return w
    }

    private fun drawHud(g: Gfx, wd: World) {
        val s = session ?: return
        val bottom = ui.top + hudH
        g.fillRect(0f, 0f, ui.w, bottom, Ui.BAND)
        g.fillRect(0f, bottom - d(3f), ui.w, d(1f), Ui.PANEL_HI)
        g.fillRect(0f, bottom - d(2f), ui.w, d(2f), Ui.EDGE)
        val cy = ui.top + hudH / 2f - d(1f)
        val low = wd.lives <= wd.maxLives / 4
        var x = d(8f)
        x += readout(g, x, cy, d(62f), "${wd.lives}", if (low && sin(clock * 10f) > 0f) Ui.RED else Ui.TEXT) { ix ->
            ui.heart(g, ix, cy + d(1f), d(18f), Ui.RED)
        } + d(6f)
        val moneyColor = if (moneyFlash > 0f && sin(clock * 30f) > 0f) Ui.RED else Ui.GOLD
        x += readout(g, x, cy, d(78f), "${wd.money}", moneyColor) { ix -> ui.coin(g, ix, cy + d(1f), d(19f)) } + d(6f)

        val pauseX = ui.w - d(10f) - d(40f)
        val speedX = pauseX - d(8f) - d(48f)
        var badges = speedX - d(4f)
        if (s.challenge) {
            badges -= d(24f)
            ui.medal(g, badges + d(12f), cy, d(22f), !wd.challengeFailed)
        }
        if (s.difficulty == Difficulty.HARD) {
            badges -= d(24f)
            ui.skull(g, badges + d(12f), cy, d(20f), Ui.RED)
        }
        if (s.vip) {
            badges -= d(26f)
            ui.icon(g, Icon.CROWN, badges + d(13f), cy, d(21f), Ui.GOLD)
        }
        val wave = max(1, wd.wavesStarted)
        val waveText = if (wd.endless) "$wave" else "$wave/${wd.totalWaves}"
        val room = badges - d(6f) - x
        if (room > d(56f)) {
            val ww = min(room, d(30f) + g.textWidth(waveText, d(17f), Font.DISPLAY) + d(12f))
            ui.well(g, x, cy - d(16f), ww, d(32f))
            ui.icon(g, Icon.FLAG, x + d(15f), cy, d(18f), Ui.ACCENT)
            ui.textMid(g, waveText, x + d(29f), cy + d(1f), d(17f), Ui.TEXT, Align.LEFT, maxW = ww - d(36f), font = Font.DISPLAY)
        }
        ui.button(g, "speed", speedX, cy - d(17f), d(48f), d(33f), "×$speed", if (speed > 1) Style.PRIMARY else Style.NORMAL, size = 15f) {
            speed = if (speed >= wd.rules.maxSpeed) 1 else speed + 1
        }
        ui.button(g, "pause", pauseX, cy - d(17f), d(40f), d(33f), "", icon = Icon.PAUSE) { openPause() }
    }

    private fun drawStrip(g: Gfx, wd: World) {
        g.fillRect(0f, stripY, ui.w, stripH, Ui.PANEL_DARK)
        g.fillRect(0f, stripY, ui.w, d(1f), Ui.EDGE)
        val pad = d(6f)
        val bs = stripH - pad * 2f - d(3f)
        var x = d(8f)
        for (a in Ability.entries) {
            if (!wd.rules.unlocked(a)) continue
            drawAbility(g, wd, a, x, stripY + pad, bs)
            x += bs + d(7f)
        }
        val waveW = min(ui.w * 0.38f, d(176f))
        val waveX = ui.w - waveW - d(8f)
        drawStripMiddle(g, wd, x + d(2f), waveX - d(6f))

        val label: String
        var sub: String? = null
        var style = Style.PRIMARY
        when {
            wd.phase == Phase.READY -> label = Tx.START_WAVE.t
            wd.countdown >= 0f -> {
                label = Tx.WAVE_N.f(wd.wavesStarted + 1)
                sub = Tx.NEXT_WAVE_IN.f(wd.countdown.toInt() + 1, wd.earlyBonus)
            }
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
        ui.button(g, "wave", waveX, stripY + pad, waveW, bs, label, if (pulse) Style.NORMAL else style, wd.canCallWave, size = 14f, sub = sub) {
            wd.callWave()
        }
    }

    private fun drawAbility(g: Gfx, wd: World, a: Ability, x: Float, y: Float, s: Float) {
        val allowed = wd.rules.usable(a)
        val ready = wd.abilityReady(a)
        val active = a == Ability.AIRSTRIKE && aiming
        val id = "ab${a.name}"
        ui.button(g, id, x, y, s, s, "", if (active) Style.DANGER else Style.NORMAL, allowed) { useAbility(a) }
        val sink = if (ui.down(id)) d(2f) else 0f
        if (ready && !active) ui.outline(g, x + d(1f), y + sink + d(1f), s - d(2f), s - d(2f), d(7f), Colors.mix(Ui.ACCENT, Ui.GOLD, 0.5f + 0.5f * sin(clock * 4f)), d(2f))
        val cx = x + s / 2f
        val cy = y + s / 2f + sink
        renderer.drawAbilityIcon(g, a, cx, cy, s * 0.56f, if (ready || active) Ui.TEXT else Ui.FADED)
        val left = wd.cooldowns[a.ordinal]
        if (allowed && left > 0f) {
            val sweep = 360f * left / wd.cooldownOf(a)
            g.fillArc(cx, cy, s * 0.44f, -90f, sweep, Colors.alpha(Ui.EDGE, 0.6f))
            ui.textMid(g, "${ceil(left).toInt()}", cx, cy, d(13f), Ui.TEXT, font = Font.DISPLAY)
        }
        if (!allowed) ui.icon(g, Icon.CROSS, cx, cy, s * 0.6f, Colors.alpha(Ui.RED, 0.85f))
    }

    private fun drawStripMiddle(g: Gfx, wd: World, left: Float, right: Float) {
        val cy = stripY + stripH / 2f
        val width = right - left
        if (width < d(30f)) return
        if (aiming) {
            ui.paragraph(g, Tx.AIM_AIRSTRIKE.t, left, cy - d(15f), width, d(11.5f), Ui.ACCENT, spacing = 1.15f, font = Font.BOLD)
            return
        }
        val undo = undoable
        if (undo != null) {
            val bh = stripH - d(15f)
            ui.button(g, "undo", left, cy - bh / 2f - d(1.5f), width, bh, Tx.UNDO.t, size = 12f, icon = Icon.UNDO) {
                world?.undo(undo)
                lastBuilt = null
            }
            val f = 1f - (clock - lastBuiltAt) / wd.rules.undoTime
            val sink = if (ui.down("undo")) d(2f) else 0f
            g.fillRect(left + d(7f), cy + bh / 2f - d(6f) + sink, (width - d(14f)) * f, d(2.5f), Ui.ACCENT)
            return
        }
        val n = notice
        if (n != null && clock - noticeAt < NOTICE_TIME) {
            ui.paragraph(g, n, left, cy - d(15f), width, d(11.5f), Ui.ACCENT, spacing = 1.15f, font = Font.BOLD)
            return
        }
        val type = placing
        if (type != null) {
            ui.paragraph(g, type.hint.t, left, cy - d(15f), width, d(11f), Ui.TEXT, spacing = 1.15f)
            return
        }
        val next = wd.nextWave
        if (next == null) {
            ui.textMid(g, Tx.LAST_WAVE.t, left, cy, d(13f), Ui.ACCENT, Align.LEFT, maxW = width, font = Font.DISPLAY)
            return
        }
        ui.caption(g, Tx.NEXT.t, left, cy - d(8f), maxW = width)
        var x = left
        for ((t, n2) in next.preview.take(4)) {
            val label = "×$n2"
            val tw = g.textWidth(label, d(11f), Font.DISPLAY)
            if (x + d(20f) + tw > right) break
            renderer.drawEnemyIcon(g, t, x + d(9f), cy + d(8f), d(17f), clock)
            ui.text(g, label, x + d(19f), cy + d(12f), d(11f), if (t.boss) Ui.RED else Ui.TEXT, font = Font.DISPLAY)
            x += d(23f) + tw + d(5f)
        }
    }

    private fun panelBase(g: Gfx) {
        g.fillRect(0f, shopY, ui.w, shopH + ui.bottom, Ui.PANEL)
        g.fillRect(0f, shopY, ui.w, d(2f), Ui.EDGE)
        g.fillRect(0f, shopY + d(2f), ui.w, d(1f), Ui.PANEL_HI)
    }

    private fun drawShop(g: Gfx, wd: World) {
        panelBase(g)
        val types = wd.rules.towers
        val hero = wd.commander
        // The commander's card comes first.
        val first = if (hero != null) 1 else 0
        val n = types.size + first
        if (n == 0) return
        val margin = d(8f)
        val gap = d(6f)
        val fits = n <= 6
        val cw = if (fits) (ui.w - margin * 2 - gap * (n - 1)) / n else (ui.w - margin * 2 - gap * 5) / 5.5f
        val ch = shopH - d(18f)
        val content = margin * 2 + n * cw + (n - 1) * gap
        val off = ui.beginScroll(g, "shop", 0f, shopY, ui.w, shopH, content, horizontal = true)
        if (hero != null) drawHeroCard(g, wd, hero, margin - off, shopY + d(9f), cw, ch)
        for ((i, type) in types.withIndex()) {
            val x = margin + (i + first) * (cw + gap) - off
            val y0 = shopY + d(9f)
            if (x > ui.w || x + cw < 0f) continue
            val cost = wd.buildCost(type)
            val blocked = wd.towerLimitReached || wd.quarryLimitReached(type)
            val affordable = wd.money >= cost && !blocked
            val active = placing == type
            val id = "shop${type.name}"
            ui.area(id, x, y0, cw, ch) { pickTower(type) }
            val y = y0 + if (ui.down(id)) d(2f) else 0f
            ui.plate(g, x, y, cw, ch, if (active) Ui.PANEL_HI else Ui.PANEL_LIGHT, if (active) Ui.ACCENT else Ui.EDGE)
            val icon = min(cw * 0.62f, d(40f))
            renderer.drawTowerIcon(g, type, 0, x + cw / 2f, y + d(7f) + icon / 2f, icon, clock)
            ui.textMid(g, type.short.t, x + cw / 2f, y + ch - d(30f), d(10.5f), if (affordable) Ui.TEXT else Ui.DIM, maxW = cw - d(8f))
            val costText = "$cost"
            val costW = g.textWidth(costText, d(13f), Font.DISPLAY)
            ui.coin(g, x + cw / 2f - costW / 2f - d(5f), y + ch - d(13f), d(11f))
            ui.textMid(g, costText, x + cw / 2f + d(5f), y + ch - d(13f), d(13f), if (affordable) Ui.GOLD else Ui.FADED, font = Font.DISPLAY)
            if (type.role == Role.ROAD) ui.tag(g, Tx.ROAD.t.uppercase(), x + d(4f), y + d(10f), d(7f))
            if (!affordable) ui.chamfer(g, x, y, cw, ch, d(6f), 0x66101309)
        }
        ui.endScroll(g, "shop")
    }

    /** The commander in the shop row: his portrait, his level and a light when a skill is ready. */
    private fun drawHeroCard(g: Gfx, wd: World, c: Commander, x: Float, y0: Float, cw: Float, ch: Float) {
        if (x > ui.w || x + cw < 0f) return
        ui.area("shopHero", x, y0, cw, ch) {
            heroPicked = !heroPicked
            placing = null
            selected = null
            obstacle = -1
            aiming = false
        }
        val y = y0 + if (ui.down("shopHero")) d(2f) else 0f
        ui.plate(g, x, y, cw, ch, if (heroPicked) Ui.PANEL_HI else Ui.PANEL_LIGHT, if (heroPicked) Ui.ACCENT else Ui.GOLD)
        val icon = min(cw * 0.62f, d(40f))
        renderer.drawCommanderIcon(g, c.type, x + cw / 2f, y + d(7f) + icon / 2f, icon)
        val name = c.type.title.t.substringAfterLast(' ')
        ui.textMid(g, name, x + cw / 2f, y + ch - d(30f), d(10.5f), Ui.TEXT, maxW = cw - d(8f))
        val next = c.nextXp
        val bw = cw - d(14f)
        if (next != null) {
            val from = Commander.XP[c.level]
            ui.bar(g, x + d(7f), y + ch - d(18f), bw, d(7f), (c.xp - from) / (next - from), Ui.GOLD)
        }
        ui.tag(g, Tx.LEVEL_SHORT.f(c.level + 1), x + d(4f), y + d(10f), d(7f), Ui.GOLD, Ui.DARK_TEXT)
        val ready = c.type.skills.indices.any { wd.skillReady(it) }
        if (ready) {
            g.fillCircle(x + cw - d(8f), y + d(9f), d(6f), Ui.EDGE)
            g.fillCircle(x + cw - d(8f), y + d(9f), d(4.5f), Colors.mix(Ui.ACCENT, Ui.GOLD, 0.5f + 0.5f * sin(clock * 5f)))
        }
    }

    /** What the commander's gun does now, as short pieces. */
    fun heroStats(wd: World, c: Commander): List<String> = buildList {
        add(Tx.DMG.f(Num.int(wd.commanderDamage(c))))
        add(Tx.PER_SECOND.f(Num.one(c.stats.rate)))
        add(Tx.RANGE.f(Num.one(wd.commanderRange(c))))
        when (c.type) {
            CommanderType.ENGINEER -> add(Tx.CHAIN.f(World.ZAP_JUMPS))
            CommanderType.GROM -> add(Tx.BLAST.f(Num.one(World.GROM_BLAST)))
            CommanderType.SERGEANT -> Unit
        }
    }

    private fun drawCommanderPanel(g: Gfx, wd: World, c: Commander) {
        panelBase(g)
        val x0 = d(12f)
        val icon = d(44f)
        ui.well(g, x0, shopY + d(12f), icon + d(8f), icon + d(8f), d(6f))
        renderer.drawCommanderIcon(g, c.type, x0 + d(4f) + icon / 2f, shopY + d(16f) + icon / 2f, icon)
        val tx = x0 + icon + d(18f)
        val maxW = ui.w - tx - d(12f)
        val name = c.type.title.t
        val sz = ui.fit(g, name, d(15f), maxW - d(56f), Font.DISPLAY)
        ui.textMid(g, name, tx, shopY + d(19f), sz, Ui.TEXT, Align.LEFT, font = Font.DISPLAY)
        ui.tag(g, Tx.LEVEL_SHORT.f(c.level + 1), tx + g.textWidth(name, sz, Font.DISPLAY) + d(8f), shopY + d(19f), d(10f), Ui.GOLD, Ui.DARK_TEXT)
        ui.chips(g, heroStats(wd, c) + Tx.KILLS.f(c.kills), tx, shopY + d(29f), maxW, size = d(10.5f))
        val next = c.nextXp
        var hx = tx
        if (next != null) {
            val from = Commander.XP[c.level]
            ui.bar(g, tx, shopY + d(53f), d(62f), d(8f), (c.xp - from) / (next - from), Ui.GOLD)
            hx += d(70f)
        }
        val line = when {
            c.jammed > 0f -> Tx.JAMMED.t
            !wd.skillsAllowed -> Tx.SKILLS_OFF.t
            else -> Tx.SEND_HINT.t
        }
        ui.text(g, line, hx, shopY + d(61f), d(10.5f), if (c.jammed > 0f) Ui.ACCENT else Ui.GREEN, maxW = ui.w - hx - d(12f), font = Font.BODY)

        val by = shopY + d(70f)
        val bh = d(32f)
        val gap = d(6f)
        val closeW = d(42f)
        val count = c.type.skills.size
        val sw = (ui.w - d(24f) - closeW - gap * count) / count
        var x = d(12f)
        for (k in 0 until count) {
            val skill = c.type.skills[k]
            if (!wd.skillOpen(k)) {
                // Comes with a higher rank: shown so the player knows what is ahead.
                ui.button(g, "skill$k", x, by, sw, bh, Tx.RANK_SHORT.f(Ranks.THIRD_SKILL), Style.NORMAL, false, size = 11f, icon = Icon.LOCK) {}
                x += sw + gap
                continue
            }
            val left = c.skillCooldowns[k]
            val usable = wd.skillsAllowed && wd.phase == Phase.RUNNING && c.jammed <= 0f && left <= 0f
            val label = if (left > 0f && wd.skillsAllowed) "${skill.title.t.uppercase()} ${ceil(left).toInt()}" else skill.title.t.uppercase()
            ui.button(g, "skill$k", x, by, sw, bh, label, if (usable) Style.PRIMARY else Style.NORMAL, usable, size = if (count > 2) 11f else 12f, icon = skill.icon) {
                useSkill(k)
            }
            if (left > 0f && wd.skillsAllowed) {
                val f = 1f - left / wd.skillCooldown(skill)
                val sink = if (ui.down("skill$k")) d(2f) else 0f
                g.fillRect(x + d(7f), by + bh - d(6f) + sink, (sw - d(14f)) * f, d(2.5f), Ui.ACCENT)
            }
            x += sw + gap
        }
        ui.button(g, "close", x, by, closeW, bh, "", Style.GHOST, icon = Icon.CROSS) { heroPicked = false }
    }

    private fun drawTowerPanel(g: Gfx, wd: World, t: Tower) {
        panelBase(g)
        if (t.atFork && wd.rules.branches) drawFork(g, wd, t) else drawTowerInfo(g, wd, t)
    }

    /** The squad's name with its rank: chevrons for its level, a star once specialised. */
    private fun drawName(g: Gfx, t: Tower, x: Float, cy: Float, size: Float, maxW: Float) {
        val name = t.branch?.title?.t ?: t.type.title.t
        val sz = ui.fit(g, name, size, maxW - d(40f), Font.DISPLAY)
        ui.textMid(g, name, x, cy, sz, Ui.TEXT, Align.LEFT, font = Font.DISPLAY)
        val after = x + g.textWidth(name, sz, Font.DISPLAY) + d(8f)
        if (t.branch != null) ui.star(g, after + d(7f), cy, d(7.5f), true) else ui.chevrons(g, after, cy, t.level + 1, d(11f), Ui.ACCENT)
    }

    private fun drawTowerInfo(g: Gfx, wd: World, t: Tower) {
        val x0 = d(12f)
        val icon = d(44f)
        ui.well(g, x0, shopY + d(12f), icon + d(8f), icon + d(8f), d(6f))
        renderer.drawTowerIcon(g, t.type, t.level, x0 + d(4f) + icon / 2f, shopY + d(17f) + icon / 2f, icon, clock, t.branch)
        val tx = x0 + icon + d(18f)
        val maxW = ui.w - tx - d(12f)
        drawName(g, t, tx, shopY + d(19f), d(15f), maxW)
        val tally = if (t.type == TowerType.QUARRY) Tx.EARNED.f(t.damageDealt.roundToInt()) else Tx.KILLS.f(t.kills)
        ui.chips(g, statParts(wd, t.type, t.stats, t.branch) + tally, tx, shopY + d(29f), maxW, size = d(10.5f))
        when {
            t.canUpgrade -> {
                ui.icon(g, Icon.RANKS, tx + d(7f), shopY + d(58.5f), d(13f), Ui.GREEN)
                ui.chips(g, statParts(wd, t.type, t.type.levels[t.level + 1], null), tx + d(17f), shopY + d(50f), maxW - d(17f), Ui.GREEN, d(10f), Ui.PANEL_DARK)
            }
            else -> {
                val hint = when {
                    t.branch != null -> t.branch!!.hint.t
                    !wd.rules.branches -> Tx.BRANCH_LOCKED.f(BRANCH_UNLOCK)
                    else -> t.type.hint.t
                }
                ui.text(g, hint, tx, shopY + d(62f), d(11f), Ui.GREEN, maxW = maxW, font = Font.BODY)
            }
        }

        val by = shopY + d(70f)
        val bh = d(32f)
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
            ui.button(g, "mode", x, by, modeW, bh, t.mode.title.t, size = 12f) {
                t.mode = TargetMode.entries[(t.mode.ordinal + 1) % TargetMode.entries.size]
            }
            val sink = if (ui.down("mode")) d(2f) else 0f
            ui.tag(g, Tx.TARGET.t, x + modeW / 2f - (g.textWidth(Tx.TARGET.t, d(7.5f), Font.DISPLAY) + d(7f)) / 2f, by + sink, d(7.5f), Ui.WELL, Ui.DIM)
            x += modeW + gap
        }
        ui.button(g, "close", x, by, closeW, bh, "", Style.GHOST, icon = Icon.CROSS) { selected = null }
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
        renderer.drawTowerIcon(g, t.type, t.level, x0 + icon / 2f, shopY + d(8f) + icon / 2f, icon, clock)
        val closeW = d(38f)
        val sellW = d(96f)
        val bx = ui.w - d(12f) - closeW
        ui.button(g, "close", bx, shopY + d(8f), closeW, d(28f), "", Style.GHOST, icon = Icon.CROSS) {
            selected = null
        }
        drawSellButton(g, wd, t, bx - d(6f) - sellW, shopY + d(8f), sellW, d(28f))
        val tx = x0 + icon + d(8f)
        val maxW = bx - d(6f) - sellW - tx - d(8f)
        drawName(g, t, tx, shopY + d(15f), d(13.5f), maxW)
        ui.text(g, Tx.CHOOSE_BRANCH.t, tx, shopY + d(36f), d(10.5f), Ui.ACCENT, maxW = maxW)

        val top = shopY + d(44f)
        val ch = shopH - d(52f)
        val gap = d(8f)
        val cw = (ui.w - d(24f) - gap) / 2f
        for ((k, b) in t.type.branches.withIndex()) {
            val x = d(12f) + k * (cw + gap)
            val cost = wd.branchCost(b)
            val ok = wd.money >= cost
            val id = "branch${b.name}"
            ui.area(id, x, top, cw, ch, ok) { wd.branch(t, b) }
            val y = top + if (ui.down(id)) d(2f) else 0f
            ui.plate(g, x, y, cw, ch, if (ok) Ui.PANEL_LIGHT else Ui.PANEL_DARK, if (ok) Ui.GOLD else Ui.EDGE)
            val bi = d(30f)
            renderer.drawTowerIcon(g, t.type, 2, x + d(7f) + bi / 2f, y + d(6f) + bi / 2f, bi, clock, b)
            val lx = x + d(14f) + bi
            ui.text(g, b.title.t, lx, y + d(17f), d(12.5f), if (ok) Ui.TEXT else Ui.DIM, maxW = cw - bi - d(20f), font = Font.DISPLAY)
            ui.coin(g, lx + d(5f), y + d(29f), d(10f))
            ui.text(g, "$cost", lx + d(13f), y + d(33.5f), d(12f), if (ok) Ui.GOLD else Ui.FADED, font = Font.DISPLAY)
            val lines = ui.lines(g, b.hint.t, d(9.5f), cw - d(14f))
            for ((i, l) in lines.take(2).withIndex()) {
                ui.text(g, l, x + d(7f), y + d(48f) + i * d(11.5f), d(9.5f), Ui.DIM, maxW = cw - d(14f), font = Font.BODY)
            }
        }
    }

    private fun drawObstaclePanel(g: Gfx, wd: World) {
        panelBase(g)
        val c = obstacle % COLS
        val r = obstacle / COLS
        val kind = wd.map.terrain(c, r)
        val icon = d(44f)
        ui.well(g, d(12f), shopY + d(12f), icon + d(8f), icon + d(8f), d(6f))
        renderer.drawObstacle(g, kind, wd.map.biome, d(16f) + icon / 2f, shopY + d(16f) + icon / 2f, icon)
        val tx = d(12f) + icon + d(18f)
        val name = when (kind) {
            'T' -> Tx.TREE
            'C' -> Tx.CACTUS
            else -> Tx.ROCK
        }
        ui.textMid(g, name.t, tx, shopY + d(24f), d(15f), Ui.TEXT, Align.LEFT, maxW = ui.w - tx - d(12f), font = Font.DISPLAY)
        ui.text(g, Tx.CLEAR_HINT.t, tx, shopY + d(50f), d(11.5f), Ui.DIM, maxW = ui.w - tx - d(12f), font = Font.BODY)
        val by = shopY + d(70f)
        val bh = d(32f)
        val closeW = d(42f)
        val cost = wd.clearCost()
        ui.button(g, "clear", d(12f), by, ui.w - d(30f) - closeW, bh, Tx.CLEAR.f(cost), Style.PRIMARY, wd.canClear(c, r), size = 13f) {
            wd.clear(c, r)
            obstacle = -1
        }
        ui.button(g, "close", ui.w - d(12f) - closeW, by, closeW, bh, "", Style.GHOST, icon = Icon.CROSS) {
            obstacle = -1
        }
    }

    /** What a squad level does, as short pieces: damage, rate, range... */
    fun statParts(wd: World, type: TowerType, s: TowerLevel, branch: Branch?): List<String> {
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
        return parts
    }

    // --- overlays -------------------------------------------------------------------------

    /** Draws a centred dialog [ph] high; returns its x, y, width and where its content starts. */
    private fun dialog(g: Gfx, ph: Float, title: String? = null, band: Int = Ui.BAND): FloatArray {
        val pw = min(ui.w - d(40f), d(340f))
        val px = (ui.w - pw) / 2f
        val py = max(ui.top + d(10f), (ui.h - ph) / 2f)
        val top = ui.panel(g, px, py, pw, ph, title = title, band = band)
        return floatArrayOf(px, py, pw, top)
    }

    private fun drawPause(g: Gfx) {
        val s = session ?: return
        val info = buildList {
            if (s.mode == Mode.CAMPAIGN) add(s.difficulty.title.t)
            if (s.challenge) s.level.challenge?.let { add(it.title) }
            for (m in s.mods) add(m.title.t)
        }
        val ph = d(354f) + (if (info.isNotEmpty()) d(32f) else 0f)
        val (px, _, pw, top) = dialog(g, ph, Tx.PAUSED.t)
        var y = top + d(16f)
        if (info.isNotEmpty()) {
            ui.chips(g, info, px + d(20f), y, pw - d(40f), Ui.ACCENT, d(11f), centre = true)
            y += d(32f)
        }
        val bx = px + d(22f)
        val bw = pw - d(44f)
        ui.button(g, "resume", bx, y, bw, d(52f), Tx.RESUME.t, Style.PRIMARY, size = 18f, icon = Icon.PLAY) {
            overlay = Overlay.NONE
        }
        y += d(64f)
        ui.button(g, "restart", bx, y, bw, d(46f), Tx.RESTART.t, icon = Icon.UNDO) { game.restart() }
        y += d(56f)
        ui.button(g, "psettings", bx, y, bw, d(46f), Tx.SETTINGS_BTN.t, icon = Icon.GEAR) {
            overlay = Overlay.SETTINGS
        }
        y += d(56f)
        ui.button(g, "quit", bx, y, bw, d(46f), Tx.TO_MENU.t, Style.GHOST, icon = Icon.HOME) {
            overlay = Overlay.NONE
            leave()
        }
        y += d(56f)
        ui.paragraph(g, Tx.SAVED_NOTE.t, ui.w / 2f, y, pw - d(44f), d(11.5f), Ui.DIM, Align.CENTER)
    }

    /** News from the front: a sheet of paper with a stamp, a photo and a few lines. */
    private fun drawCard(g: Gfx) {
        val card = cards.firstOrNull() ?: run {
            overlay = Overlay.NONE
            return
        }
        val pw = min(ui.w - d(40f), d(340f))
        val textW = pw - d(48f)
        val textH = ui.lines(g, card.text, d(13.5f), textW).size * d(13.5f) * 1.35f
        val statsH = if (card.stats.isEmpty()) 0f else d(30f)
        val ph = d(292f) + textH + statsH
        val px = (ui.w - pw) / 2f
        val py = max(ui.top + d(14f), (ui.h - ph) / 2f)
        val cx = ui.w / 2f
        ui.paper(g, px, py, pw, ph)
        ui.stamp(g, card.kind.t, cx, py + d(36f), d(13f))
        val photo = d(98f)
        val top = py + d(62f)
        g.fillRect(cx - photo / 2f + d(2f), top + d(3f), photo, photo, 0x40000000)
        g.fillRect(cx - photo / 2f, top, photo, photo, 0xFFF8F3E2.toInt())
        g.fillRect(cx - photo / 2f + d(5f), top + d(5f), photo - d(10f), photo - d(10f), Ui.PANEL)
        card.icon(g, cx, top + photo / 2f, d(66f))
        ui.textMid(g, card.title, cx, py + d(190f), d(21f), Ui.INK, maxW = pw - d(36f), font = Font.DISPLAY)
        var y = py + d(204f)
        y += ui.paragraph(g, card.text, cx, y, textW, d(13.5f), Colors.alpha(Ui.INK, 0.85f), Align.CENTER)
        if (card.stats.isNotEmpty()) {
            ui.chips(g, card.stats, px + d(20f), y + d(4f), pw - d(40f), Ui.INK, d(11f), Ui.PAPER_EDGE, centre = true)
        }
        ui.button(g, "gotit", px + d(24f), py + ph - d(70f), pw - d(48f), d(50f), Tx.GOT_IT.t, Style.PRIMARY, size = 17f) { closeCard() }
    }

    private fun drawResult(g: Gfx, wd: World) {
        val s = session ?: return
        val won = wd.phase == Phase.WON
        val next = if (s.mode == Mode.CAMPAIGN && won) Levels.all.firstOrNull { it.id == s.level.id + 1 } else null
        val lines = ArrayList<Pair<String, Int>>()
        when (s.mode) {
            Mode.CAMPAIGN -> {
                if (!won) lines += Tx.WAVE_OF.f(wd.wavesStarted, wd.totalWaves) to Ui.TEXT
                lines += Tx.LIVES.f(wd.lives, wd.maxLives) to Ui.TEXT
                lines += Tx.KILLED.f(wd.kills) to Ui.TEXT
                if (won && resultHard) lines += Tx.HARD_WIN.t to Ui.RED
                if (s.challenge) lines += (if (won && resultMedal) Tx.MEDAL_WON.t to Ui.GOLD else Tx.CHALLENGE_FAILED.t to Ui.DIM)
            }
            Mode.ENDLESS -> {
                lines += (if (resultRecord) Tx.NEW_RECORD.t to Ui.GOLD else Tx.RECORD.f(profile.bestEndless) to Ui.TEXT)
                lines += Tx.KILLED.f(wd.kills) to Ui.TEXT
            }
            Mode.DAILY -> {
                if (won) {
                    lines += Tx.DAILY_DONE.t to Ui.GOLD
                    lines += Tx.STREAK.f(profile.dailyStreak(s.day)) to Ui.TEXT
                } else {
                    lines += Tx.WAVE_OF.f(wd.wavesStarted, wd.totalWaves) to Ui.TEXT
                }
                lines += Tx.KILLED.f(wd.kills) to Ui.TEXT
            }
            Mode.TUTORIAL -> {
                if (won) lines += Tx.TUT_DONE_TEXT.t to Ui.GOLD
                lines += Tx.KILLED.f(wd.kills) to Ui.TEXT
            }
            Mode.WEEKLY -> {
                if (won) {
                    lines += Tx.WEEKLY_DONE.t to Ui.GOLD
                    lines += Tx.WEEKLY_MEDAL.f(Tx.MEDALS[resultStars - 1].t) to Ui.GOLD
                    if (resultPoints > 0) lines += Tx.POINTS_GAINED.f(resultPoints) to Ui.GOLD
                } else {
                    lines += Tx.WAVE_OF.f(wd.wavesStarted, wd.totalWaves) to Ui.TEXT
                }
                lines += Tx.LIVES.f(wd.lives, wd.maxLives) to Ui.TEXT
                lines += Tx.KILLED.f(wd.kills) to Ui.TEXT
            }
        }
        val hero = wd.commander
        if (hero != null && resultService > 0) lines += Tx.SERVICE.f(resultService) to Ui.TEXT
        if (hero != null && resultRankUp > 0) {
            lines += Tx.RANK_UP.f(resultRankUp) to Ui.GOLD
            RankPerk.at(resultRankUp)?.let { lines += it.text(hero.type) to Ui.GOLD }
        }
        // After a defeat, what VIP's second chance would have done.
        val offerVip = !won && s.mode != Mode.TUTORIAL && game.shop != Shop.NONE && !s.vip
        val achievements = resultAchievements.take(3)
        val rowH = d(26f)
        val tutorialWon = s.mode == Mode.TUTORIAL && won
        val buttonsH = (if (next != null || tutorialWon) d(172f) else d(118f)) + (if (offerVip) d(64f) else 0f)
        val headH = if (s.mode == Mode.ENDLESS || ((s.mode == Mode.CAMPAIGN || s.mode == Mode.WEEKLY) && won)) d(92f) else d(12f)
        val achH = if (achievements.isEmpty()) 0f else d(30f) + achievements.size * d(24f)
        val ph = d(56f) + headH + lines.size * rowH + achH + buttonsH + d(34f)
        val band = if (won) 0xFF3B5A24.toInt() else 0xFF6B2219.toInt()
        val title = if (tutorialWon) Tx.TUT_DONE.t else if (won) Tx.VICTORY.t else Tx.DEFEAT.t
        val (px, _, pw, top) = dialog(g, ph, title, band)
        val since = clock - resultAt
        var y = top + d(8f)
        if (s.mode == Mode.CAMPAIGN && won) {
            for (k in 0 until 3) {
                val appear = ((since - 0.25f - k * 0.3f) * 5f).coerceIn(0f, 1f)
                val pop = if (appear < 1f) appear * 1.2f else 1f
                val sx = ui.w / 2f + (k - 1) * d(60f)
                val sy = y + d(42f) - (if (k == 1) d(8f) else 0f)
                ui.star(g, sx, sy, d(24f), false)
                if (k < resultStars && appear > 0f) ui.star(g, sx, sy, d(24f) * max(0.4f, pop), true)
            }
            y += headH
        } else if (s.mode == Mode.WEEKLY && won) {
            for (k in 0 until 3) {
                val appear = ((since - 0.25f - k * 0.3f) * 5f).coerceIn(0f, 1f)
                val sx = ui.w / 2f + (k - 1) * d(60f)
                val sy = y + d(42f) - (if (k == 1) d(8f) else 0f)
                val earned = k < resultStars && appear > 0f
                ui.icon(g, Icon.MEDAL, sx, sy, d(46f) * (if (earned && appear < 1f) appear * 1.2f else 1f), if (earned) Ui.MEDAL_COLORS[k] else Ui.EMPTY)
            }
            y += headH
        } else if (s.mode == Mode.ENDLESS) {
            ui.caption(g, Tx.HELD.t, ui.w / 2f - g.textWidth(Tx.HELD.t.uppercase(), d(10.5f)) / 2f, y + d(20f))
            ui.textMid(g, Tx.WAVES_COUNT.f(max(0, wd.wavesStarted - 1)), ui.w / 2f, y + d(54f), d(30f), Ui.TEXT, font = Font.DISPLAY)
            y += headH
        } else {
            y += headH
        }
        // The report: what was counted on the left, the number on the right.
        val lx = px + d(26f)
        val rx = px + pw - d(26f)
        for ((text, color) in lines) {
            val split = text.indexOf(": ")
            if (split > 0) {
                ui.textMid(g, text.substring(0, split), lx, y + rowH / 2f, d(13.5f), Ui.DIM, Align.LEFT, maxW = pw * 0.6f)
                ui.textMid(g, text.substring(split + 2), rx, y + rowH / 2f, d(15f), color, Align.RIGHT, font = Font.DISPLAY)
            } else {
                ui.textMid(g, text, ui.w / 2f, y + rowH / 2f, d(14f), color, maxW = pw - d(52f))
            }
            g.fillRect(lx, y + rowH, rx - lx, max(1f, d(1f)), Colors.alpha(Ui.PANEL_HI, 0.7f))
            y += rowH
        }
        if (achievements.isNotEmpty()) {
            y += d(10f)
            ui.caption(g, Tx.NEW_ACHIEVEMENTS.t, lx, y + d(10f), Ui.ACCENT)
            y += d(20f)
            for (a in achievements) {
                ui.medal(g, lx + d(9f), y + d(12f), d(18f), true)
                ui.textMid(g, a.title.t, lx + d(24f), y + d(12f), d(13.5f), Ui.TEXT, Align.LEFT, maxW = pw - d(76f))
                y += d(24f)
            }
        }
        y += d(18f)
        val bx = px + d(22f)
        val bw = pw - d(44f)
        if (offerVip) {
            ui.button(g, "defeatVip", bx, y, bw, d(52f), Tx.VIP.t, Style.NORMAL, size = 16f, icon = Icon.CROWN, sub = Tx.DEFEAT_VIP.f(Vip.RESERVE_LIVES)) {
                game.checkPurchases()
                game.leaveBattle(Screen.VIP)
            }
            y += d(64f)
        }
        if (tutorialWon) {
            ui.button(g, "campaign", bx, y, bw, d(52f), Tx.TO_CAMPAIGN.t, Style.PRIMARY, size = 16f, icon = Icon.FLAG) {
                game.leaveBattle(Screen.LEVELS)
            }
            y += d(64f)
            ui.button(g, "again", bx, y, bw, d(44f), Tx.AGAIN.t, icon = Icon.UNDO) { game.restart() }
            y += d(54f)
        } else if (next != null) {
            ui.button(g, "next", bx, y, bw, d(52f), Tx.NEXT_LEVEL_BTN.f(next.name.t.uppercase()), Style.PRIMARY, size = 16f, icon = Icon.PLAY) {
                game.startCampaign(next, s.difficulty.takeIf { profile.stars(next.id) > 0 || it != Difficulty.HARD } ?: Difficulty.NORMAL, false)
            }
            y += d(64f)
            ui.button(g, "again", bx, y, bw, d(44f), Tx.AGAIN.t, icon = Icon.UNDO) { game.restart() }
            y += d(54f)
        } else {
            ui.button(g, "again", bx, y, bw, d(52f), Tx.AGAIN.t, Style.PRIMARY, size = 17f, icon = Icon.UNDO) { game.restart() }
            y += d(62f)
        }
        val backLabel = if (s.mode == Mode.CAMPAIGN) Tx.TO_LEVELS.t else Tx.TO_MENU.t
        ui.button(g, "levels", bx, y, bw, d(44f), backLabel, Style.GHOST, icon = if (s.mode == Mode.CAMPAIGN) Icon.FLAG else Icon.HOME) { leave() }
    }

    companion object {
        /** Seconds the stars of a win get before the rating window may cover them. */
        const val REVIEW_DELAY = 2.5f
        const val MAX_ZOOM = 2.5f
        const val MAX_TERRAIN_PX = 190f
        const val SELL_CONFIRM = 150
        const val SELL_WINDOW = 2.5f
        const val NOTICE_TIME = 2.5f
        const val SLOWMO_TIME = 0.6f
        const val SLOWMO_SCALE = 0.35f

        /** How close to the commander, in cells, a tap picks him. */
        const val HERO_TAP = 0.55f
    }
}
