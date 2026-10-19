package com.makc.basedefense.engine

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** A part of the story: the prologue before the first battle, then a chapter after each world. */
enum class Chapter(val heading: L, val world: Int) {
    PROLOGUE(L("ПРЕДЫСТОРИЯ", "BACKSTORY"), -1),
    VALLEY(L("ГЛАВА 1", "CHAPTER 1"), 0),
    DESERT(L("ГЛАВА 2", "CHAPTER 2"), 1),
    NORTH(L("ГЛАВА 3", "CHAPTER 3"), 2),
    VOLCANO(L("ГЛАВА 4", "CHAPTER 4"), 3),
    ;

    companion object {
        /** The chapter told when the last level of [world] is won, if there is one. */
        fun after(world: Int): Chapter? = entries.firstOrNull { it.world == world && world >= 0 }
    }
}

/**
 * The story, told in pictures: the prologue once to a new player after the intro, a chapter
 * after each world's last level, and all of it so far again from the settings. Each page is
 * a scene played live with the game's own art while the camera drifts over it, with a few
 * lines typed under it on a field report. A tap finishes the lines or turns the page; the
 * last page, or the skip button, leads on.
 */
internal class Story(private val game: Game) : WorldListener {
    private val ui get() = game.ui
    private val renderer get() = game.renderer

    /** One picture: a live scene, where the camera starts and drifts to, its colour, how loud it is and what goes on in it. */
    private class Scene(
        val world: World,
        val from: FloatArray,
        val to: FloatArray,
        val tint: Int,
        val loud: Float,
        val direct: (Scene) -> Unit = {},
    ) {
        /** When the scene next does something, in its world's seconds. */
        var due = 0f
        var big = 0f
        var count = 0
        val cells = ArrayList<Int>()
    }

    private class Page(val chapter: Chapter, val title: L, val text: L, val stage: () -> Scene)

    private var pages: List<Page> = emptyList()
    private var told: List<Chapter> = emptyList()

    /** How many pages this telling has. */
    val pageCount: Int get() = pages.size

    /** The page open now, from 0. */
    var page = 0
        private set
    private var pageTime = 0f
    private var time = 0f
    private var acc = 0f
    private var scene: Scene? = null
    private var shown = 0f
    private var leaving = false
    private var back = Screen.MENU
    private var sirens = 0
    private var rnd = Random(SEED)

    /** All of this page's lines are typed out. */
    val typed: Boolean get() = shown >= text.length

    private val text: String get() = pages[page].text.t

    private fun d(v: Float) = ui.d(v)

    override fun sound(sfx: Sfx, volume: Float) {
        val s = scene ?: return
        if (s.loud <= 0f || leaving) return
        // One siren is drama; one for every armoured car is noise.
        if (sfx == Sfx.SIREN && sirens++ > 0) return
        game.sound(sfx, volume * s.loud)
    }

    private var after: (() -> Unit)? = null

    /** Tells [chapters] from their first page; [then] is the screen it leads to, unless there is [next] to do instead. */
    fun start(chapters: List<Chapter>, then: Screen, next: (() -> Unit)? = null) {
        told = chapters
        after = next
        pages = chapters.flatMap { pagesOf(it) }
        back = then
        leaving = false
        rnd = Random(SEED)
        open(0)
    }

    private fun open(p: Int) {
        page = p
        pageTime = 0f
        shown = 0f
        acc = 0f
        sirens = 0
        scene = null
        scene = pages[p].stage()
    }

    /** A tap: the lines are typed out at once, or the page turns. */
    fun tap() {
        if (leaving) return
        if (!typed) {
            shown = text.length.toFloat()
            return
        }
        if (page < pages.size - 1) {
            game.sound(Sfx.CLICK, 0.5f)
            open(page + 1)
        } else {
            finish()
        }
    }

    fun skip() = finish()

    private fun finish() {
        if (leaving) return
        leaving = true
        for (c in told) game.profile.markSeen(c)
        val next = after
        if (next != null) next() else game.go(back)
    }

    fun update(dt: Float) {
        time += dt
        pageTime += dt
        if (pageTime > TYPE_DELAY) shown = min(text.length.toFloat(), shown + dt * TYPE_SPEED)
        val s = scene ?: return
        acc += dt
        while (acc >= Game.STEP) {
            if (s.world.time >= s.due) s.direct(s)
            s.world.step(Game.STEP)
            acc -= Game.STEP
        }
    }

    // --- the scenes -----------------------------------------------------------------------

    private fun world(level: Int, commander: Boolean = false): World {
        val w = World(Levels.all.first { it.id == level }, Rules(commander = if (commander) CommanderType.SERGEANT else null), this)
        w.money = 100_000
        w.lite = game.profile.lite
        return w
    }

    private fun pagesOf(c: Chapter): List<Page> {
        fun page(i: Int, titles: List<L>, texts: List<L>, stage: () -> Scene) = Page(c, titles[i], texts[i], stage)
        return when (c) {
            Chapter.PROLOGUE -> listOf(
                page(0, Tx.STORY_CHAPTERS, Tx.STORY_TEXTS) { peace() },
                page(1, Tx.STORY_CHAPTERS, Tx.STORY_TEXTS) { horde() },
                page(2, Tx.STORY_CHAPTERS, Tx.STORY_TEXTS) { armour() },
                page(3, Tx.STORY_CHAPTERS, Tx.STORY_TEXTS) { digIn() },
                page(4, Tx.STORY_CHAPTERS, Tx.STORY_TEXTS) { fight(COAST, RAIDERS, 0, plane = true) },
            )
            Chapter.VALLEY -> listOf(
                page(0, Tx.STORY_VALLEY_TITLES, Tx.STORY_VALLEY_TEXTS) { fight(8, RAIDERS, Colors.alpha(DUSK, 0.1f), plane = true) },
                page(1, Tx.STORY_VALLEY_TITLES, Tx.STORY_VALLEY_TEXTS) { march(9, HORDE, Colors.alpha(EMBER, 0.22f)) },
            )
            Chapter.DESERT -> listOf(
                page(0, Tx.STORY_DESERT_TITLES, Tx.STORY_DESERT_TEXTS) {
                    quiet(16, listOf(TowerType.SNIPER, TowerType.ROCKET, TowerType.TURRET), Colors.alpha(DUSK, 0.14f))
                },
                page(1, Tx.STORY_DESERT_TITLES, Tx.STORY_DESERT_TEXTS) { march(17, NORTHERNERS, Colors.alpha(NIGHT, 0.25f)) },
            )
            Chapter.NORTH -> listOf(
                page(0, Tx.STORY_NORTH_TITLES, Tx.STORY_NORTH_TEXTS) {
                    quiet(24, listOf(TowerType.TESLA, TowerType.SAM, TowerType.SNIPER), Colors.alpha(NIGHT, 0.2f))
                },
                page(1, Tx.STORY_NORTH_TITLES, Tx.STORY_NORTH_TEXTS) {
                    bosses(25, listOf(EnemyType.TITAN), FIRE_WALKERS, Colors.alpha(EMBER, 0.16f))
                },
            )
            Chapter.VOLCANO -> listOf(
                page(0, Tx.STORY_VOLCANO_TITLES, Tx.STORY_VOLCANO_TEXTS) { fight(32, FIRE_WALKERS, Colors.alpha(EMBER, 0.12f), plane = true) },
                page(1, Tx.STORY_VOLCANO_TITLES, Tx.STORY_VOLCANO_TEXTS) {
                    quiet(COAST, listOf(TowerType.TURRET, TowerType.ROCKET, TowerType.SNIPER, TowerType.TESLA, TowerType.SHOTGUN), Colors.alpha(DUSK, 0.12f))
                },
            )
        }
    }

    // --- the prologue's own scenes, framed by hand -----------------------------------------

    /** The base by the sea on a quiet evening: two squads on guard, the sergeant walking his rounds. */
    private fun peace(): Scene {
        val w = world(COAST, commander = true)
        val posts = posts(w)
        for (cell in posts.take(2)) w.build(TowerType.TURRET, cell % COLS, cell / COLS)?.age = 10f
        return Scene(w, floatArrayOf(4.9f, 11.2f, 6.6f), floatArrayOf(4.6f, 10.6f, 8f), Colors.alpha(DUSK, 0.12f), 0f) { rounds(it) }.also {
            it.cells += posts.drop(2)
            it.due = 1.5f
        }
    }

    /** The Horde on the march through the dunes. */
    private fun horde(): Scene {
        val w = world(DUNES)
        w.beginShow()
        for (k in 0 until 18) w.send(pick(HORDE), 3f + k * 0.75f)
        return Scene(w, floatArrayOf(3.6f, 4.2f, 6.4f), floatArrayOf(3.2f, 5.4f, 7.6f), Colors.alpha(EMBER, 0.24f), 0.3f) { s ->
            w.send(pick(HORDE), 0f)
            if (rnd.nextFloat() < 0.3f) w.send(EnemyType.SWARM, 0f).also { w.send(EnemyType.SWARM, 0f) }
            s.due = w.time + 0.55f
        }
    }

    /** Armour and helicopters over the frozen river. */
    private fun armour(): Scene {
        val w = world(RIVER)
        w.beginShow()
        w.send(EnemyType.APC, 16.5f)
        w.send(EnemyType.HELI, 13f)
        for (dist in floatArrayOf(11.5f, 12.4f, 14.2f, 18f, 18.8f, 19.6f, 20.4f)) w.send(pick(MACHINES), dist)
        return Scene(w, floatArrayOf(4f, 8.8f, 6.8f), floatArrayOf(3.6f, 9.8f, 7.8f), Colors.alpha(NIGHT, 0.3f), 0.35f) { s ->
            if (w.time >= s.big) {
                w.send(if (s.count++ % 2 == 0) EnemyType.HELI else EnemyType.APC, 9f)
                s.big = w.time + 8f
            }
            w.send(pick(MACHINES), 9f)
            s.due = w.time + 1.1f
        }.also { it.big = 7f }
    }

    /** The sergeant has his volunteers dig in by the base, one squad after another. */
    private fun digIn(): Scene {
        val w = world(COAST, commander = true)
        return Scene(w, floatArrayOf(5f, 11.4f, 5.6f), floatArrayOf(4.6f, 10.8f, 6.8f), 0, 0.6f) { s ->
            val cell = s.cells.getOrNull(s.count)
            if (cell != null) {
                w.build(DIG_IN[s.count % DIG_IN.size], cell % COLS, cell / COLS)
                s.count++
                s.due = w.time + 0.85f
            } else {
                s.due = Float.MAX_VALUE
            }
        }.also {
            it.cells += posts(w)
            it.due = 0.6f
        }
    }

    // --- scenes for any map ---------------------------------------------------------------

    /** Squads at their posts by the base and the sergeant on his rounds: a quiet day after a fight. */
    private fun quiet(level: Int, garrison: List<TowerType>, tint: Int): Scene {
        val w = world(level, commander = true)
        val posts = posts(w)
        for ((i, cell) in posts.take(garrison.size).withIndex()) {
            val t = w.build(garrison[i], cell % COLS, cell / COLS) ?: continue
            repeat(i % 3) { w.upgrade(t) }
            t.age = 10f
        }
        val (bx, by) = base(w)
        return Scene(w, floatArrayOf(bx, by - 2f, 6.4f), floatArrayOf(bx, by - 3f, 8f), tint, 0f) { rounds(it) }.also {
            it.cells += posts.drop(garrison.size)
            it.due = 1.2f
        }
    }

    /** An army on the march along the road into the map. */
    private fun march(level: Int, pool: List<EnemyType>, tint: Int): Scene {
        val w = world(level)
        w.beginShow()
        for (k in 0 until 18) w.send(pick(pool), 3f + k * 0.75f)
        val a = along(w, 5f)
        val b = along(w, 9f)
        return Scene(w, floatArrayOf(a[0], a[1], 6.4f), floatArrayOf(b[0], b[1], 7.6f), tint, 0.3f) { s ->
            w.send(pick(pool), 0f)
            s.due = w.time + 0.6f
        }
    }

    /** Bosses rolling in with their escort. */
    private fun bosses(level: Int, big: List<EnemyType>, pool: List<EnemyType>, tint: Int): Scene {
        val w = world(level)
        w.beginShow()
        w.send(big[0], 10f)
        for (dist in floatArrayOf(6f, 7.2f, 8.4f, 11.5f, 12.6f, 13.8f)) w.send(pick(pool), dist)
        val a = along(w, 9f)
        val b = along(w, 12f)
        return Scene(w, floatArrayOf(a[0], a[1], 6.8f), floatArrayOf(b[0], b[1], 7.8f), tint, 0.35f) { s ->
            if (w.time >= s.big) {
                w.send(big[s.count++ % big.size], 3f)
                s.big = w.time + 9f
            }
            w.send(pick(pool), 3f)
            s.due = w.time + 1.1f
        }.also { it.big = 6f }
    }

    /** Squads by the base fighting off an attack, and the plane coming over now and then. */
    private fun fight(level: Int, pool: List<EnemyType>, tint: Int, plane: Boolean): Scene {
        val w = world(level, commander = true)
        w.beginShow()
        for ((i, cell) in posts(w).withIndex()) {
            val t = w.build(SQUADS[i % SQUADS.size], cell % COLS, cell / COLS) ?: continue
            repeat(i % 3) { w.upgrade(t) }
            t.age = 10f
        }
        val path = w.map.paths[0]
        val entry = max(0f, path.length - 18f)
        for (k in 0 until 10) w.send(pick(pool), entry + 1f + k * 1.6f)
        val (bx, by) = base(w)
        return Scene(w, floatArrayOf(bx - 0.5f, by - 3f, 6.8f), floatArrayOf(bx - 0.6f, by - 3.8f, 8.6f), tint, 0.4f) { s ->
            if (plane && w.time >= s.big) {
                // The plane goes for the one nearest the base.
                val target = w.enemies.filter { it.alive && it.onScreen() }.maxByOrNull { it.dist }
                if (target != null) w.flyover(target.x, target.y, World.AIRSTRIKE_DAMAGE)
                s.big = w.time + 6.5f
            }
            w.send(pick(pool), entry)
            s.due = w.time + 0.7f
        }.also { it.big = 2.5f }
    }

    private fun Enemy.onScreen(): Boolean = x in 0f..COLS.toFloat() && y in 0f..ROWS.toFloat()

    /** The sergeant walks from post to post. */
    private fun rounds(s: Scene) {
        val cell = s.cells.getOrNull(s.count++ % max(1, s.cells.size)) ?: return
        s.world.moveCommander(cell % COLS + 0.5f, cell / COLS + 0.5f)
        s.due = s.world.time + 3.4f
    }

    private fun base(w: World): Pair<Float, Float> = (w.map.baseCol + 0.5f) to (w.map.baseRow + 0.5f)

    /** The point [dist] cells along the main road. */
    private fun along(w: World, dist: Float): FloatArray {
        val p = FloatArray(4)
        w.map.paths[0].pointAt(dist, p)
        return p
    }

    private fun pick(pool: List<EnemyType>): EnemyType = pool[rnd.nextInt(pool.size)]

    /** Grass by the road near the base, the cells that cover the most road first. */
    private fun posts(w: World): List<Int> {
        val bx = w.map.baseCol
        val by = w.map.baseRow
        val cells = ArrayList<Int>()
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (w.canPlace(TowerType.TURRET, c, r) && max(abs(c - bx), abs(r - by)) <= POST_REACH) cells += r * COLS + c
        }
        fun road(cell: Int): Int {
            var n = 0
            for (dr in -1..1) for (dc in -1..1) if (w.map.isRoad(cell % COLS + dc, cell / COLS + dr)) n++
            return n
        }
        return cells.filter { road(it) > 0 }.sortedByDescending { road(it) * 10 - abs(it % COLS - bx) - abs(it / COLS - by) }.take(POSTS)
    }

    // --- drawing --------------------------------------------------------------------------

    fun render(g: Gfx) {
        val s = scene ?: return
        // Anywhere on the screen turns the page; the buttons drawn later win where they are.
        ui.area("story", 0f, 0f, ui.w, ui.h, silent = true) { tap() }
        val x = d(16f)
        val w = ui.w - d(32f)
        val top = ui.top + d(62f)
        val navY = ui.h - ui.bottom - d(64f)
        val size = d(14.5f)
        val most = pages.maxOf { ui.lines(g, it.text.t, size, w - d(8f) - TEXT_LEFT * d(1f) - d(20f)).size }
        val paperH = d(34f) + LINE_STEP * d(1f) * most
        val room = navY - d(18f) - paperH - d(26f) - top
        val ph = min(room, w * 1.3f)
        val py = top + (room - ph) / 2f
        val paperY = py + ph + d(26f)

        ui.text(g, pages[page].chapter.heading.t, x, ui.top + d(36f), d(20f), Ui.GOLD, font = Font.DISPLAY, maxW = ui.w - d(170f))
        drawPicture(g, s, x, py, w, ph)
        val chapter = pages[page].title.t
        val tapeSize = d(15f)
        ui.tape(g, chapter, x + d(14f) + (g.textWidth(chapter, tapeSize, Font.DISPLAY) + tapeSize * 1.1f) / 2f, py + d(2f), tapeSize, -3f)
        drawReport(g, x + d(4f), paperY, w - d(8f), paperH, size)
        drawNav(g, navY)
        ui.button(g, "storySkip", ui.w - d(16f) - d(136f), ui.top + d(12f), d(136f), d(38f), Tx.STORY_SKIP.t, Style.GHOST, size = 12f) { skip() }
    }

    private fun drawPicture(g: Gfx, s: Scene, x: Float, y: Float, w: Float, h: Float) {
        g.fillRect(x - d(4f), y - d(4f), w + d(8f), h + d(8f), Ui.EDGE)
        g.fillRect(x - d(1.5f), y - d(1.5f), w + d(3f), h + d(3f), Ui.PANEL_HI)
        // The new page slides in from the right as it fades up.
        val turn = smooth(pageTime / TURN)
        g.save()
        g.clip(x, y, w, h)
        g.fillRect(x, y, w, h, Ui.BG_DEEP)
        val k = smooth(pageTime / PAN)
        val across = s.from[2] + (s.to[2] - s.from[2]) * k
        val cell = w / across
        val halfW = across / 2f
        val halfH = h / cell / 2f
        val cx = clampView(s.from[0] + (s.to[0] - s.from[0]) * k, halfW, COLS.toFloat())
        val cy = clampView(s.from[1] + (s.to[1] - s.from[1]) * k, halfH, ROWS.toFloat())
        val shake = s.world.shake * cell * 0.12f
        g.translate(x + w / 2f - cx * cell + sin(time * 90f) * shake + (1f - turn) * d(36f), y + h / 2f - cy * cell + sin(time * 77f) * shake)
        g.scale(cell)
        renderer.drawWorld(g, s.world, time, w / min(s.from[2], s.to[2]))
        g.restore()

        g.save()
        g.clip(x, y, w, h)
        if (s.tint != 0) g.fillRect(x, y, w, h, s.tint)
        // Dark at the edges, like an old photograph.
        shade(g, x, y, w, h * 0.22f, 0.55f, 0f)
        shade(g, x, y + h * 0.72f, w, h * 0.28f, 0f, 0.6f)
        if (turn < 1f) g.fillRect(x, y, w, h, Colors.alpha(Ui.BG_DEEP, 1f - turn))
        g.restore()
    }

    /** Keeps the view on the map where the map is big enough for it. */
    private fun clampView(c: Float, half: Float, size: Float): Float = if (half * 2f >= size) size / 2f else c.coerceIn(half, size - half)

    /** Darkens a band [h] high from [from] at its top to [to] at its bottom. */
    private fun shade(g: Gfx, x: Float, y: Float, w: Float, h: Float, from: Float, to: Float) {
        // Whole pixels, so that neighbouring strips neither overlap nor leave a gap.
        var top = floor(y)
        for (i in 0 until STRIPS) {
            val bottom = floor(y + h * (i + 1) / STRIPS)
            if (bottom > top) g.fillRect(x, top, w, bottom - top, Colors.alpha(SHADE, from + (to - from) * (i + 0.5f) / STRIPS))
            top = bottom
        }
    }

    /** The lines on a field report, typed out letter by letter with a caret after the last. */
    private fun drawReport(g: Gfx, x: Float, y: Float, w: Float, h: Float, size: Float) {
        ui.paper(g, x, y, w, h)
        val lines = ui.lines(g, text, size, w - TEXT_LEFT * d(1f) - d(20f))
        var left = shown.toInt()
        // Clear of the paper clip.
        val tx = x + TEXT_LEFT * d(1f)
        var ty = y + d(26f)
        var caretX = tx
        var caretY = ty
        for (line in lines) {
            if (left <= 0) break
            val part = if (left >= line.length) line else line.substring(0, left)
            g.text(part, tx, ty, size, Ui.INK, Align.LEFT, Font.BODY)
            caretX = tx + g.textWidth(part, size, Font.BODY) + d(2f)
            caretY = ty
            left -= line.length + 1
            ty += LINE_STEP * d(1f)
        }
        if (!typed || (time * 2f).toInt() % 2 == 0) g.fillRect(caretX, caretY - size * 0.8f, d(2f), size * 0.95f, Colors.alpha(Ui.INK, 0.8f))
    }

    /** A dot for each page, and the button that turns it. */
    private fun drawNav(g: Gfx, y: Float) {
        val bw = d(158f)
        val bh = d(50f)
        val bx = ui.w - d(16f) - bw
        if (pages.size <= MAX_DOTS) {
            for (i in pages.indices) {
                val dx = d(28f) + i * d(18f)
                val r = if (i == page) d(5.5f) else d(3.5f)
                g.fillCircle(dx, y + bh / 2f, r + d(1.5f), Ui.EDGE)
                g.fillCircle(dx, y + bh / 2f, r, if (i == page) Ui.ACCENT else if (i < page) Ui.DIM else Ui.FADED)
            }
        } else {
            ui.textMid(g, "${page + 1} / ${pages.size}", d(24f), y + bh / 2f, d(15f), Ui.DIM, Align.LEFT, font = Font.DISPLAY)
        }
        val last = page == pages.size - 1
        ui.button(
            g, "storyNext", bx, y, bw, bh, if (last) Tx.STORY_GO.t else Tx.STORY_NEXT.t, if (typed) Style.PRIMARY else Style.NORMAL,
            size = 16f, icon = if (last) Icon.FLAG else Icon.PLAY,
        ) { tap() }
    }

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    companion object {
        private const val SEED = 1945
        private const val MAX_DOTS = 7

        private const val COAST = 1
        private const val DUNES = 9
        private const val RIVER = 18

        /** Letters a second, and the pause before they start. */
        private const val TYPE_SPEED = 38f
        private const val TYPE_DELAY = 0.45f

        /** Seconds the camera takes to drift, and the page to turn. */
        private const val PAN = 9f
        private const val TURN = 0.35f
        private const val LINE_STEP = 22f
        private const val TEXT_LEFT = 46f
        private const val POSTS = 6
        private const val POST_REACH = 4
        private const val STRIPS = 32

        private val HORDE = listOf(EnemyType.BANDIT, EnemyType.BANDIT, EnemyType.RUNNER, EnemyType.SWARM, EnemyType.SWARM, EnemyType.BRUTE)
        private val MACHINES = listOf(EnemyType.BANDIT, EnemyType.BANDIT, EnemyType.DRONE, EnemyType.BRUTE)
        private val RAIDERS = listOf(EnemyType.BANDIT, EnemyType.RUNNER, EnemyType.BRUTE, EnemyType.SWARM, EnemyType.DRONE)
        private val DIG_IN = listOf(TowerType.TURRET, TowerType.ROCKET, TowerType.SHOTGUN, TowerType.SNIPER, TowerType.TURRET, TowerType.TESLA)
        private val SQUADS = listOf(TowerType.TURRET, TowerType.ROCKET, TowerType.SHOTGUN, TowerType.SNIPER, TowerType.TESLA, TowerType.FLAMER)
        private val NORTHERNERS = listOf(EnemyType.BRUTE, EnemyType.MEDIC, EnemyType.SHIELD, EnemyType.BANDIT, EnemyType.RUNNER)
        private val FIRE_WALKERS = listOf(EnemyType.SALAMANDER, EnemyType.SALAMANDER, EnemyType.JUGGERNAUT, EnemyType.DIGGER)

        private val SHADE = 0xFF0E1009.toInt()
        private val DUSK = 0xFFFFB45A.toInt()
        private val EMBER = 0xFF8A2A10.toInt()
        private val NIGHT = 0xFF0E2440.toInt()
    }
}
