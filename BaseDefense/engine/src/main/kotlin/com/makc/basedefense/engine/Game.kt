package com.makc.basedefense.engine

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The whole game behind three calls from the phone: [update], [render] and
 * [touch]. Screens, buttons and saves live here; the battle itself is [World].
 */
class Game(private val platform: Platform) {
    private enum class Screen { MENU, LEVELS, PLAY }
    private enum class Overlay { NONE, PAUSE, RESULT }
    private enum class Style { PRIMARY, NORMAL, GHOST }

    private class Btn(val id: String, val x: Float, val y: Float, val w: Float, val h: Float, val enabled: Boolean, val action: () -> Unit) {
        fun contains(px: Float, py: Float): Boolean = px >= x && px <= x + w && py >= y && py <= y + h
    }

    private var w = 0f
    private var h = 0f
    private var dp = 1f
    private var top = 0f
    private var bottomInset = 0f

    private var screen = Screen.MENU
    private var overlay = Overlay.NONE
    private var clock = 0f

    internal var world: World? = null
        private set
    private var level: LevelDef? = null
    private var speed = 1
    private var acc = 0f
    internal var placing: TowerType? = null
    private var ghostCol = -1
    private var ghostRow = -1
    internal var selected: Tower? = null
    private var endDelay = 0f
    private var resultAt = 0f
    private var resultStars = 0
    private var newRecord = false
    private var moneyFlash = 0f

    private var demo: World? = null
    private var demoBot: Bot? = null

    private val buttons = ArrayList<Btn>()
    private var pressed: String? = null
    private var touchingMap = false
    private val lastPlayed = FloatArray(Sfx.entries.size) { -10f }
    private val renderer = Renderer()
    private val thumbs = HashMap<Int, GameMap>()

    // Play layout, in pixels.
    private var mapX = 0f
    private var mapY = 0f
    private var cell = 1f
    private var hudH = 0f
    private var stripY = 0f
    private var stripH = 0f
    private var shopY = 0f
    private var shopH = 0f

    fun resize(width: Int, height: Int, density: Float, insetTop: Int, insetBottom: Int) {
        w = width.toFloat()
        h = height.toFloat()
        dp = density
        top = insetTop.toFloat()
        bottomInset = insetBottom.toFloat()
    }

    fun update(dt: Float) {
        val step = dt.coerceIn(0f, 0.1f)
        clock += step
        if (moneyFlash > 0f) moneyFlash -= step
        when (screen) {
            Screen.MENU, Screen.LEVELS -> updateDemo(step)
            Screen.PLAY -> updatePlay(step)
        }
    }

    private fun updateDemo(dt: Float) {
        var d = demo
        if (d == null || d.phase == Phase.WON || d.phase == Phase.LOST || d.time > 240f) {
            d = World(Levels.all[(clock / 240f).toInt() % 3])
            demo = d
            demoBot = Bot(d)
        }
        acc += dt
        while (acc >= STEP) {
            demoBot?.update(STEP)
            d.step(STEP)
            acc -= STEP
        }
    }

    private fun updatePlay(dt: Float) {
        val wd = world ?: return
        if (overlay == Overlay.NONE) {
            acc += dt * speed
            while (acc >= STEP) {
                wd.step(STEP)
                acc -= STEP
            }
        }
        if (selected?.let { it !in wd.towers } == true) selected = null
        if ((wd.phase == Phase.WON || wd.phase == Phase.LOST) && overlay != Overlay.RESULT) {
            placing = null
            selected = null
            endDelay += dt
            if (endDelay > 1.2f) showResult(wd)
        }
    }

    // --- progress -------------------------------------------------------------------------

    private fun stars(id: Int): Int = platform.getInt("stars_$id", 0)

    private fun unlocked(id: Int): Boolean = id == 1 || stars(id - 1) > 0

    private val endlessUnlocked: Boolean get() = stars(3) > 0

    private val totalStars: Int get() = Levels.all.sumOf { stars(it.id) }

    private var soundOn: Boolean
        get() = platform.getInt("sound", 1) == 1
        set(value) = platform.putInt("sound", if (value) 1 else 0)

    private val best: Int get() = platform.getInt("best_endless", 0)

    private fun showResult(wd: World) {
        overlay = Overlay.RESULT
        resultAt = clock
        val lvl = level ?: return
        if (lvl.endless) {
            val survived = max(0, wd.wavesStarted - 1)
            newRecord = survived > best
            if (newRecord) platform.putInt("best_endless", survived)
        } else if (wd.phase == Phase.WON) {
            val share = wd.lives / wd.maxLives.toFloat()
            resultStars = when {
                share >= 0.9f -> 3
                share >= 0.5f -> 2
                else -> 1
            }
            if (resultStars > stars(lvl.id)) platform.putInt("stars_${lvl.id}", resultStars)
        }
    }

    // --- actions --------------------------------------------------------------------------

    internal fun startLevel(def: LevelDef) {
        level = def
        val wd = World(def) { s, v -> sound(s, v) }
        wd.onBaseHit = { platform.vibrate(35) }
        world = wd
        screen = Screen.PLAY
        overlay = Overlay.NONE
        speed = 1
        acc = 0f
        placing = null
        selected = null
        endDelay = 0f
        newRecord = false
        resultStars = 0
    }

    private fun sound(s: Sfx, volume: Float) {
        if (!soundOn) return
        val gap = when (s) {
            Sfx.SHOOT -> 0.06f
            Sfx.COIN -> 0.08f
            Sfx.FLAME -> 0.25f
            Sfx.ZAP, Sfx.BOOM, Sfx.MISSILE -> 0.07f
            else -> 0.03f
        }
        if (clock - lastPlayed[s.ordinal] < gap) return
        lastPlayed[s.ordinal] = clock
        platform.play(s, volume)
    }

    /** Back button: closes the innermost thing; false means leave the app. */
    fun back(): Boolean {
        when (screen) {
            Screen.MENU -> return false
            Screen.LEVELS -> screen = Screen.MENU
            Screen.PLAY -> when {
                overlay == Overlay.RESULT -> screen = Screen.LEVELS
                overlay == Overlay.PAUSE -> overlay = Overlay.NONE
                placing != null -> placing = null
                selected != null -> selected = null
                else -> overlay = Overlay.PAUSE
            }
        }
        return true
    }

    /** The app went to the background. */
    fun pause() {
        if (screen == Screen.PLAY && overlay == Overlay.NONE && world?.phase?.let { it == Phase.RUNNING || it == Phase.READY } == true) {
            overlay = Overlay.PAUSE
        }
    }

    fun touch(action: Touch, x: Float, y: Float) {
        when (action) {
            Touch.DOWN -> {
                val b = buttons.lastOrNull { it.contains(x, y) }
                if (b != null) {
                    pressed = b.id
                    return
                }
                if (screen == Screen.PLAY && overlay == Overlay.NONE && inMap(x, y)) {
                    touchingMap = true
                    mapTouch(action, x, y)
                }
            }
            Touch.MOVE -> if (touchingMap) mapTouch(action, x, y)
            Touch.UP -> {
                val p = pressed
                if (p != null) {
                    pressed = null
                    val b = buttons.lastOrNull { it.contains(x, y) }
                    if (b != null && b.id == p) {
                        if (b.enabled) {
                            sound(Sfx.CLICK, 0.5f)
                            b.action()
                        } else {
                            sound(Sfx.ERROR, 0.4f)
                        }
                    }
                    return
                }
                if (touchingMap) {
                    touchingMap = false
                    mapTouch(action, x, y)
                }
            }
            Touch.CANCEL -> {
                pressed = null
                touchingMap = false
                ghostCol = -1
            }
        }
    }

    private fun inMap(x: Float, y: Float): Boolean =
        x >= mapX && y >= mapY && x < mapX + cell * COLS && y < mapY + cell * ROWS

    private fun mapTouch(action: Touch, x: Float, y: Float) {
        val wd = world ?: return
        val c = floor((x - mapX) / cell).toInt()
        val r = floor((y - mapY) / cell).toInt()
        val type = placing
        if (type != null) {
            if (action != Touch.UP) {
                ghostCol = c
                ghostRow = r
                return
            }
            ghostCol = -1
            if (wd.canBuild(type, c, r)) {
                wd.build(type, c, r)
                placing = null
            } else {
                val t = wd.towerAt(c, r)
                if (t != null) {
                    placing = null
                    selected = t
                } else {
                    sound(Sfx.ERROR, 0.4f)
                    if (wd.money < type.levels[0].cost) moneyFlash = 0.6f
                }
            }
            return
        }
        if (action == Touch.UP) {
            val t = wd.towerAt(c, r)
            selected = if (t != null && t !== selected) t else null
        }
    }

    private fun pickTower(type: TowerType) {
        val wd = world ?: return
        selected = null
        if (placing == type) {
            placing = null
            return
        }
        if (wd.money < type.levels[0].cost) {
            moneyFlash = 0.6f
            sound(Sfx.ERROR, 0.4f)
            return
        }
        placing = type
    }

    // --- drawing --------------------------------------------------------------------------

    fun render(g: Gfx) {
        buttons.clear()
        g.fillRect(0f, 0f, w, h, BG)
        if (w <= 0f || h <= 0f) return
        when (screen) {
            Screen.MENU -> renderMenu(g)
            Screen.LEVELS -> renderLevels(g)
            Screen.PLAY -> renderPlay(g)
        }
    }

    private fun d(v: Float): Float = v * dp

    private fun text(g: Gfx, s: String, x: Float, y: Float, size: Float, color: Int, align: Align = Align.LEFT, maxW: Float = 0f) {
        var sz = size
        if (maxW > 0f) {
            val tw = g.textWidth(s, sz)
            if (tw > maxW) sz *= maxW / tw
        }
        g.text(s, x, y, sz, color, align)
    }

    /** Text whose middle sits on [cy]. */
    private fun textMid(g: Gfx, s: String, x: Float, cy: Float, size: Float, color: Int, align: Align = Align.CENTER, maxW: Float = 0f) {
        var sz = size
        if (maxW > 0f) {
            val tw = g.textWidth(s, sz)
            if (tw > maxW) sz *= maxW / tw
        }
        g.text(s, x, cy + sz * 0.36f, sz, color, align)
    }

    private fun button(
        g: Gfx,
        id: String,
        x: Float,
        y: Float,
        bw: Float,
        bh: Float,
        label: String,
        style: Style = Style.NORMAL,
        enabled: Boolean = true,
        size: Float = 15f,
        action: () -> Unit,
    ) {
        buttons += Btn(id, x, y, bw, bh, enabled, action)
        val down = pressed == id
        val radius = min(d(10f), bh / 2f)
        val sink = if (down) d(2f) else 0f
        when {
            !enabled -> {
                g.fillRoundRect(x, y, bw, bh, radius, PANEL_LIGHT)
                g.strokeRoundRect(x, y, bw, bh, radius, EDGE, d(1f))
            }
            style == Style.PRIMARY -> {
                g.fillRoundRect(x, y + d(3f), bw, bh, radius, ACCENT_DARK)
                g.fillRoundRect(x, y + sink, bw, bh, radius, if (down) ACCENT_DOWN else ACCENT)
            }
            style == Style.NORMAL -> {
                g.fillRoundRect(x, y + d(3f), bw, bh, radius, 0xFF0A0E0C.toInt())
                g.fillRoundRect(x, y + sink, bw, bh, radius, if (down) PANEL else PANEL_LIGHT)
                g.strokeRoundRect(x, y + sink, bw, bh, radius, EDGE, d(1f))
            }
            else -> {
                if (down) g.fillRoundRect(x, y, bw, bh, radius, Colors.alpha(WHITE, 0.08f))
                g.strokeRoundRect(x, y, bw, bh, radius, EDGE, d(1.2f))
            }
        }
        val color = when {
            !enabled -> DIM
            style == Style.PRIMARY -> 0xFF1E1404.toInt()
            else -> TEXT
        }
        textMid(g, label, x + bw / 2f, y + bh / 2f + sink, d(size), color, maxW = bw - d(14f))
    }

    private fun star(g: Gfx, cx: Float, cy: Float, r: Float, filled: Boolean) {
        val xs = FloatArray(10)
        val ys = FloatArray(10)
        for (k in 0 until 10) {
            val a = -Math.PI / 2 + k * Math.PI / 5
            val rr = if (k % 2 == 0) r else r * 0.45f
            xs[k] = cx + (kotlin.math.cos(a) * rr).toFloat()
            ys[k] = cy + (kotlin.math.sin(a) * rr).toFloat()
        }
        g.fillPolygon(xs, ys, 10, if (filled) GOLD else 0xFF3A4440.toInt())
    }

    private fun heart(g: Gfx, cx: Float, cy: Float, s: Float, color: Int) {
        g.fillCircle(cx - s * 0.27f, cy - s * 0.12f, s * 0.3f, color)
        g.fillCircle(cx + s * 0.27f, cy - s * 0.12f, s * 0.3f, color)
        val xs = floatArrayOf(cx - s * 0.55f, cx + s * 0.55f, cx)
        val ys = floatArrayOf(cy - s * 0.02f, cy - s * 0.02f, cy + s * 0.55f)
        g.fillPolygon(xs, ys, 3, color)
    }

    private fun coin(g: Gfx, cx: Float, cy: Float, s: Float) {
        g.fillCircle(cx, cy + s * 0.06f, s * 0.5f, 0xFF9C7A16.toInt())
        g.fillCircle(cx, cy, s * 0.5f, GOLD)
        g.strokeCircle(cx, cy, s * 0.34f, 0xFFC99A1E.toInt(), s * 0.08f)
    }

    private fun lock(g: Gfx, cx: Float, cy: Float, s: Float) {
        g.strokeCircle(cx, cy - s * 0.18f, s * 0.24f, DIM, s * 0.1f)
        g.fillRoundRect(cx - s * 0.36f, cy - s * 0.12f, s * 0.72f, s * 0.56f, s * 0.08f, DIM)
        g.fillCircle(cx, cy + s * 0.12f, s * 0.08f, PANEL)
    }

    // --- menu -----------------------------------------------------------------------------

    private fun drawDemoBackground(g: Gfx) {
        val d0 = demo ?: return
        val c = max(w / COLS, h / ROWS)
        g.save()
        g.translate((w - c * COLS) / 2f, (h - c * ROWS) / 2f)
        g.scale(c)
        renderer.drawWorld(g, d0, clock, c)
        g.restore()
        g.fillRect(0f, 0f, w, h, 0xA6080B0A.toInt())
    }

    private fun renderMenu(g: Gfx) {
        drawDemoBackground(g)
        val cx = w / 2f
        val ty = top + h * 0.17f
        val big = min(d(46f), w / 6.5f)
        text(g, "ОБОРОНА", cx + d(3f), ty + d(3f), big, 0xFF000000.toInt(), Align.CENTER)
        text(g, "ОБОРОНА", cx, ty, big, ACCENT, Align.CENTER)
        text(g, "БАЗЫ", cx + d(3f), ty + big + d(3f), big, 0xFF000000.toInt(), Align.CENTER)
        text(g, "БАЗЫ", cx, ty + big, big, TEXT, Align.CENTER)
        text(g, "башни · волны · боссы", cx, ty + big + d(30f), d(14f), DIM, Align.CENTER)

        val bw = min(w - d(64f), d(320f))
        val bx = (w - bw) / 2f
        var y = h * 0.5f
        button(g, "play", bx, y, bw, d(60f), "ИГРАТЬ", Style.PRIMARY, size = 20f) { screen = Screen.LEVELS }
        y += d(76f)
        val endlessLabel = if (endlessUnlocked) "БЕСКОНЕЧНЫЙ РЕЖИМ" else "БЕСКОНЕЧНЫЙ · ПОСЛЕ 3 УРОВНЯ"
        button(g, "endless", bx, y, bw, d(52f), endlessLabel, enabled = endlessUnlocked) { startLevel(Levels.endless) }
        y += d(68f)
        button(g, "sound", bx, y, bw, d(48f), if (soundOn) "ЗВУК: ВКЛ" else "ЗВУК: ВЫКЛ", Style.GHOST) { soundOn = !soundOn }

        val fy = h - bottomInset - d(28f)
        star(g, cx - d(52f), fy - d(5f), d(9f), true)
        text(g, "$totalStars / ${Levels.all.size * 3}", cx - d(38f), fy, d(14f), TEXT)
        if (best > 0) text(g, "Рекорд: $best волн", cx + d(40f), fy, d(14f), DIM)
    }

    private fun renderLevels(g: Gfx) {
        g.fillRect(0f, 0f, w, h, BG)
        val headerY = top + d(12f)
        button(g, "back", d(12f), headerY, d(46f), d(42f), "‹", size = 24f) { screen = Screen.MENU }
        textMid(g, "УРОВНИ", w / 2f, headerY + d(21f), d(20f), TEXT)
        star(g, w - d(70f), headerY + d(20f), d(9f), true)
        textMid(g, "$totalStars/${Levels.all.size * 3}", w - d(56f), headerY + d(21f), d(14f), TEXT, Align.LEFT)

        val margin = d(14f)
        val gap = d(12f)
        val gridTop = headerY + d(56f)
        val cols = if (w > h) 4 else 2
        val rows = (Levels.all.size + cols - 1) / cols
        val cw = (w - margin * 2 - gap * (cols - 1)) / cols
        val avail = h - bottomInset - gridTop - margin
        val ch = min(d(230f), (avail - gap * (rows - 1)) / rows)
        for ((i, lvl) in Levels.all.withIndex()) {
            val x = margin + (i % cols) * (cw + gap)
            val y = gridTop + (i / cols) * (ch + gap)
            val open = unlocked(lvl.id)
            buttons += Btn("level${lvl.id}", x, y, cw, ch, open) { startLevel(lvl) }
            val down = pressed == "level${lvl.id}"
            g.fillRoundRect(x, y + d(3f), cw, ch, d(12f), 0xFF0A0E0C.toInt())
            g.fillRoundRect(x, y + (if (down) d(2f) else 0f), cw, ch, d(12f), PANEL_LIGHT)
            g.strokeRoundRect(x, y, cw, ch, d(12f), if (open) EDGE else PANEL, d(1f))
            text(g, "${lvl.id} · ${lvl.name}", x + d(10f), y + d(22f), d(14f), if (open) TEXT else DIM, maxW = cw - d(20f))
            val thumbTop = y + d(32f)
            val thumbH = ch - d(32f) - d(30f)
            val map = thumbs.getOrPut(lvl.id) { GameMap(lvl) }
            renderer.drawMapThumb(g, map, x + d(8f), thumbTop, cw - d(16f), thumbH)
            if (!open) {
                g.fillRoundRect(x, y, cw, ch, d(12f), 0xB30E1412.toInt())
                lock(g, x + cw / 2f, y + ch / 2f, d(34f))
            } else {
                val s = stars(lvl.id)
                for (k in 0 until 3) star(g, x + cw / 2f + (k - 1) * d(22f), y + ch - d(15f), d(9f), k < s)
            }
        }
    }

    // --- play -----------------------------------------------------------------------------

    private fun layoutPlay() {
        hudH = d(46f)
        stripH = d(42f)
        shopH = d(98f)
        shopY = h - bottomInset - shopH
        stripY = shopY - stripH
        val mapTop = top + hudH
        val mapH = stripY - mapTop
        cell = min(w / COLS, mapH / ROWS)
        mapX = (w - cell * COLS) / 2f
        mapY = mapTop + (mapH - cell * ROWS) / 2f
    }

    private fun renderPlay(g: Gfx) {
        val wd = world ?: return
        layoutPlay()
        g.fillRect(0f, top + hudH, w, stripY - top - hudH, 0xFF0B100E.toInt())

        g.save()
        g.clip(mapX, mapY, cell * COLS, cell * ROWS)
        if (wd.shake > 0f) {
            val k = wd.shake * cell * 0.12f
            g.translate(sin(clock * 90f) * k, sin(clock * 77f + 1f) * k)
        }
        g.translate(mapX, mapY)
        g.scale(cell)
        renderer.drawWorld(g, wd, clock, cell)
        drawPlacement(g, wd)
        g.restore()

        for (t in wd.texts) {
            val a = (t.life / 0.4f).coerceIn(0f, 1f)
            val size = if (t.big) d(15f) else d(12f)
            val tx = mapX + t.x * cell
            val ty = mapY + t.y * cell
            text(g, t.text, tx + d(1f), ty + d(1f), size, Colors.alpha(0xFF000000.toInt(), a * 0.8f), Align.CENTER)
            text(g, t.text, tx, ty, size, Colors.alpha(t.color, a), Align.CENTER)
        }
        drawBanner(g, wd)
        drawHint(g, wd)
        drawHud(g, wd)
        drawStrip(g, wd)
        val sel = selected
        if (sel != null) drawTowerPanel(g, wd, sel) else drawShop(g, wd)

        if (overlay != Overlay.NONE) {
            buttons.clear()
            g.fillRect(0f, 0f, w, h, SCRIM)
            if (overlay == Overlay.PAUSE) drawPause(g) else drawResult(g, wd)
        }
    }

    private fun drawPlacement(g: Gfx, wd: World) {
        val sel = selected
        if (sel != null) {
            val range = sel.stats.range
            g.fillCircle(sel.x, sel.y, range, Colors.alpha(WHITE, 0.1f))
            g.strokeCircle(sel.x, sel.y, range, Colors.alpha(WHITE, 0.6f), 0.04f)
            g.strokeRoundRect(sel.x - 0.47f, sel.y - 0.47f, 0.94f, 0.94f, 0.18f, ACCENT, 0.06f)
        }
        val type = placing ?: return
        val pulse = 0.3f + 0.2f * sin(clock * 5f)
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (!wd.canPlace(c, r)) continue
            g.strokeRoundRect(c + 0.1f, r + 0.1f, 0.8f, 0.8f, 0.14f, Colors.alpha(0xFFB8FFB8.toInt(), pulse), 0.045f)
            g.fillCircle(c + 0.5f, r + 0.5f, 0.07f, Colors.alpha(0xFFB8FFB8.toInt(), pulse + 0.15f))
        }
        if (ghostCol < 0 || !touchingMap) return
        val ok = wd.canBuild(type, ghostCol, ghostRow)
        val gx = ghostCol + 0.5f
        val gy = ghostRow + 0.5f
        val tint = if (ok) 0xFF6BD66B.toInt() else 0xFFFF4B4B.toInt()
        g.fillRect(ghostCol.toFloat(), ghostRow.toFloat(), 1f, 1f, Colors.alpha(tint, 0.35f))
        val range = type.levels[0].range
        g.fillCircle(gx, gy, range, Colors.alpha(tint, 0.12f))
        g.strokeCircle(gx, gy, range, Colors.alpha(tint, 0.8f), 0.05f)
        if (ok) renderer.drawTower(g, type, 0, gx, gy, -90f, 0f, 0f, clock)
    }

    private fun drawBanner(g: Gfx, wd: World) {
        val title = wd.banner ?: return
        if (wd.bannerTimer <= 0f) return
        val a = min(1f, wd.bannerTimer / 0.4f)
        val sub = wd.bannerSub
        val bw = min(w - d(32f), max(g.textWidth(title, d(17f)), if (sub != null) g.textWidth(sub, d(12f)) else 0f) + d(36f))
        val bh = if (sub != null) d(56f) else d(38f)
        val x = (w - bw) / 2f
        val y = mapY + d(12f)
        g.fillRoundRect(x, y, bw, bh, d(12f), Colors.alpha(0xE6101614.toInt(), a))
        g.strokeRoundRect(x, y, bw, bh, d(12f), Colors.alpha(if (title.startsWith("БОСС")) RED else ACCENT, a), d(1.5f))
        textMid(g, title, w / 2f, y + d(19f), d(17f), Colors.alpha(if (title.startsWith("БОСС")) RED else ACCENT, a), maxW = bw - d(20f))
        if (sub != null) textMid(g, sub, w / 2f, y + d(40f), d(12f), Colors.alpha(TEXT, a), maxW = bw - d(20f))
    }

    private fun drawHint(g: Gfx, wd: World) {
        if (level?.id != 1 || wd.phase != Phase.READY) return
        val hint = when {
            placing != null -> "Проведи пальцем по траве у дороги и отпусти"
            wd.towers.isEmpty() -> "Выбери башню внизу и поставь её у дороги"
            else -> "Поставь ещё или жми «НАЧАТЬ ВОЛНУ»"
        }
        val bw = min(w - d(24f), g.textWidth(hint, d(13f)) + d(28f))
        val x = (w - bw) / 2f
        val y = mapY + d(10f)
        g.fillRoundRect(x, y, bw, d(36f), d(18f), 0xE6101614.toInt())
        g.strokeRoundRect(x, y, bw, d(36f), d(18f), ACCENT, d(1.2f))
        textMid(g, hint, w / 2f, y + d(18f), d(13f), TEXT, maxW = bw - d(16f))
    }

    private fun drawHud(g: Gfx, wd: World) {
        g.fillRect(0f, 0f, w, top + hudH, PANEL)
        g.fillRect(0f, top + hudH - d(1f), w, d(1f), EDGE)
        val cy = top + hudH / 2f
        val low = wd.lives <= wd.maxLives / 4
        heart(g, d(24f), cy, d(18f), RED)
        textMid(g, "${wd.lives}", d(40f), cy, d(17f), if (low && sin(clock * 10f) > 0f) RED else TEXT, Align.LEFT)
        coin(g, d(92f), cy, d(18f))
        val moneyColor = if (moneyFlash > 0f && sin(clock * 30f) > 0f) RED else GOLD
        textMid(g, "${wd.money}", d(106f), cy, d(17f), moneyColor, Align.LEFT)

        val waveText = when {
            wd.endless -> "ВОЛНА ${max(1, wd.wavesStarted)}"
            else -> "ВОЛНА ${max(1, wd.wavesStarted)}/${wd.totalWaves}"
        }
        val pauseX = w - d(12f) - d(40f)
        val speedX = pauseX - d(8f) - d(52f)
        textMid(g, waveText, (d(170f) + speedX) / 2f, cy, d(15f), TEXT, maxW = speedX - d(176f))
        button(g, "speed", speedX, cy - d(17f), d(52f), d(34f), "×$speed", if (speed > 1) Style.PRIMARY else Style.NORMAL, size = 15f) {
            speed = if (speed >= 3) 1 else speed + 1
        }
        button(g, "pause", pauseX, cy - d(17f), d(40f), d(34f), "II", size = 15f) { overlay = Overlay.PAUSE }
    }

    private fun drawStrip(g: Gfx, wd: World) {
        g.fillRect(0f, stripY, w, stripH, 0xFF111816.toInt())
        val cy = stripY + stripH / 2f
        val bw = w * 0.46f
        val bx = w - bw - d(8f)
        val type = placing
        if (type != null) {
            text(g, "${type.title}: ${type.hint}", d(12f), cy + d(4f), d(12f), TEXT, maxW = bx - d(20f))
        } else {
            val next = wd.nextWave
            if (next == null) {
                textMid(g, "Последняя волна!", d(12f), cy, d(13f), ACCENT, Align.LEFT)
            } else {
                text(g, "ДАЛЕЕ", d(12f), cy + d(4f), d(10f), DIM)
                var x = d(56f)
                for ((t, n) in next.preview.take(4)) {
                    if (x > bx - d(40f)) break
                    renderer.drawEnemyIcon(g, t, x + d(10f), cy, d(20f), clock)
                    text(g, "×$n", x + d(22f), cy + d(5f), d(12f), if (t.boss) RED else TEXT)
                    x += d(24f) + g.textWidth("×$n", d(12f)) + d(8f)
                }
            }
        }
        val label: String
        val enabled = wd.canCallWave
        var style = Style.PRIMARY
        when {
            wd.phase == Phase.READY -> label = "НАЧАТЬ ВОЛНУ"
            wd.countdown >= 0f -> label = "ВОЛНА ${wd.wavesStarted + 1} · ${wd.countdown.toInt() + 1}с · +${wd.earlyBonus}"
            wd.nextWave == null -> {
                label = "ФИНАЛ"
                style = Style.NORMAL
            }
            else -> {
                label = "ВОЛНА ИДЁТ…"
                style = Style.NORMAL
            }
        }
        val pulse = wd.phase == Phase.READY && sin(clock * 5f) > 0f
        button(g, "wave", bx, stripY + d(5f), bw, stripH - d(10f), label, if (pulse) Style.NORMAL else style, enabled, size = 13f) {
            wd.callWave()
        }
    }

    private fun drawShop(g: Gfx, wd: World) {
        g.fillRect(0f, shopY, w, shopH + bottomInset, PANEL)
        g.fillRect(0f, shopY, w, d(1f), EDGE)
        val types = TowerType.entries
        val margin = d(8f)
        val gap = d(6f)
        val cw = (w - margin * 2 - gap * (types.size - 1)) / types.size
        val ch = shopH - d(14f)
        for ((i, type) in types.withIndex()) {
            val x = margin + i * (cw + gap)
            val y = shopY + d(7f)
            val cost = type.levels[0].cost
            val affordable = wd.money >= cost
            val active = placing == type
            buttons += Btn("shop$i", x, y, cw, ch, true) { pickTower(type) }
            val down = pressed == "shop$i"
            g.fillRoundRect(x, y + (if (down) d(2f) else 0f), cw, ch, d(10f), if (active) 0xFF3A2A12.toInt() else PANEL_LIGHT)
            g.strokeRoundRect(x, y, cw, ch, d(10f), if (active) ACCENT else EDGE, if (active) d(2f) else d(1f))
            val icon = min(cw * 0.62f, d(40f))
            renderer.drawTowerIcon(g, type, 0, x + cw / 2f, y + d(8f) + icon / 2f, icon, clock)
            textMid(g, type.short, x + cw / 2f, y + ch - d(29f), d(10.5f), if (affordable) TEXT else DIM, maxW = cw - d(6f))
            textMid(g, "$cost", x + cw / 2f + d(6f), y + ch - d(12f), d(13f), if (affordable) GOLD else DIM)
            coin(g, x + cw / 2f - g.textWidth("$cost", d(13f)) / 2f - d(4f), y + ch - d(12f), d(10f))
            if (!affordable) g.fillRoundRect(x, y, cw, ch, d(10f), 0x660E1412)
        }
    }

    private fun drawTowerPanel(g: Gfx, wd: World, t: Tower) {
        g.fillRect(0f, shopY, w, shopH + bottomInset, PANEL)
        g.fillRect(0f, shopY, w, d(1f), EDGE)
        val x0 = d(12f)
        val icon = d(38f)
        renderer.drawTowerIcon(g, t.type, t.level, x0 + icon / 2f, shopY + d(8f) + icon / 2f, icon, clock)
        val tx = x0 + icon + d(10f)
        val maxW = w - tx - d(12f)
        text(g, "${t.type.title} · ур. ${t.level + 1}", tx, shopY + d(22f), d(15f), TEXT, maxW = maxW)
        text(g, statLine(t.type, t.stats) + " · убито ${t.kills}", tx, shopY + d(39f), d(11.5f), DIM, maxW = maxW)
        if (!t.maxed) {
            text(g, "Дальше: " + statLine(t.type, t.type.levels[t.level + 1]), tx, shopY + d(54f), d(11f), 0xFF8FCF8F.toInt(), maxW = maxW)
        } else {
            text(g, t.type.hint, tx, shopY + d(54f), d(11f), 0xFF8FCF8F.toInt(), maxW = maxW)
        }

        val by = shopY + d(62f)
        val bh = d(30f)
        val total = w - d(24f) - d(18f)
        val upW = total * 0.36f
        val sellW = total * 0.26f
        val modeW = total * 0.26f
        val closeW = total - upW - sellW - modeW
        var x = d(12f)
        val upLabel = if (t.maxed) "МАКСИМУМ" else "УЛУЧШИТЬ ${t.upgradeCost}"
        button(g, "up", x, by, upW, bh, upLabel, Style.PRIMARY, !t.maxed && wd.money >= t.upgradeCost, size = 13f) {
            wd.upgrade(t)
        }
        x += upW + d(6f)
        button(g, "sell", x, by, sellW, bh, "ПРОДАТЬ ${t.sellValue}", size = 13f) {
            wd.sell(t)
            selected = null
        }
        x += sellW + d(6f)
        button(g, "mode", x, by + d(4f), modeW, bh - d(4f), t.mode.title, size = 12f) {
            t.mode = TargetMode.entries[(t.mode.ordinal + 1) % TargetMode.entries.size]
        }
        g.fillRoundRect(x + modeW / 2f - d(18f), by - d(5f), d(36f), d(12f), d(6f), PANEL)
        textMid(g, "ЦЕЛЬ", x + modeW / 2f, by + d(1f), d(8.5f), DIM)
        x += modeW + d(6f)
        button(g, "close", x, by, closeW, bh, "×", Style.GHOST, size = 18f) { selected = null }
    }

    private fun statLine(type: TowerType, s: TowerLevel): String = when (type) {
        TowerType.TURRET -> "урон ${num(s.damage)} · ${one(s.rate)}/с · радиус ${one(s.range)}"
        TowerType.SHOTGUN -> "дробь ${s.extra.toInt()}×${num(s.damage)} · ${one(s.rate)}/с · радиус ${one(s.range)}"
        TowerType.FLAMER -> "огонь ${num(s.damage)}/с · радиус ${one(s.range)}"
        TowerType.ROCKET -> "урон ${num(s.damage)} · взрыв ${one(s.extra)} · радиус ${one(s.range)}"
        TowerType.SAM -> "урон ${num(s.damage)} · ${one(s.rate)}/с · радиус ${one(s.range)}"
        TowerType.TESLA -> "урон ${num(s.damage)} · цепь ${s.extra.toInt()} · замедл. ${(s.extra2 * 100).roundToInt()}%"
    }

    private fun num(v: Float): String = v.roundToInt().toString()

    private fun one(v: Float): String {
        val t = (v * 10f).roundToInt()
        return if (t % 10 == 0) "${t / 10}" else "${t / 10},${abs(t % 10)}"
    }

    private fun panel(g: Gfx, ph: Float): Pair<Float, Float> {
        val pw = min(w - d(40f), d(340f))
        val px = (w - pw) / 2f
        val py = (h - ph) / 2f
        g.fillRoundRect(px, py + d(4f), pw, ph, d(18f), 0xFF050706.toInt())
        g.fillRoundRect(px, py, pw, ph, d(18f), PANEL)
        g.strokeRoundRect(px, py, pw, ph, d(18f), EDGE, d(1.2f))
        return px to py
    }

    private fun drawPause(g: Gfx) {
        val ph = d(320f)
        val (px, py) = panel(g, ph)
        val pw = min(w - d(40f), d(340f))
        textMid(g, "ПАУЗА", w / 2f, py + d(40f), d(24f), TEXT)
        val bx = px + d(24f)
        val bw = pw - d(48f)
        var y = py + d(76f)
        button(g, "resume", bx, y, bw, d(50f), "ПРОДОЛЖИТЬ", Style.PRIMARY, size = 17f) { overlay = Overlay.NONE }
        y += d(60f)
        button(g, "restart", bx, y, bw, d(46f), "ЗАНОВО") { level?.let { startLevel(it) } }
        y += d(56f)
        button(g, "psound", bx, y, bw, d(46f), if (soundOn) "ЗВУК: ВКЛ" else "ЗВУК: ВЫКЛ") { soundOn = !soundOn }
        y += d(56f)
        button(g, "quit", bx, y, bw, d(46f), "ВЫЙТИ К УРОВНЯМ", Style.GHOST) {
            overlay = Overlay.NONE
            screen = if (level?.endless == true) Screen.MENU else Screen.LEVELS
        }
    }

    private fun drawResult(g: Gfx, wd: World) {
        val lvl = level ?: return
        val won = wd.phase == Phase.WON
        val next = Levels.all.firstOrNull { it.id == lvl.id + 1 }
        val ph = if (won && next != null) d(360f) else d(312f)
        val (px, py) = panel(g, ph)
        val pw = min(w - d(40f), d(340f))
        val since = clock - resultAt
        if (lvl.endless) {
            textMid(g, "БАЗА ПАЛА", w / 2f, py + d(42f), d(26f), RED)
            val survived = max(0, wd.wavesStarted - 1)
            textMid(g, "Продержались", w / 2f, py + d(92f), d(15f), DIM)
            textMid(g, "$survived волн", w / 2f, py + d(126f), d(32f), TEXT)
            textMid(g, if (newRecord) "Новый рекорд!" else "Рекорд: $best", w / 2f, py + d(162f), d(15f), if (newRecord) GOLD else DIM)
        } else {
            textMid(g, if (won) "ПОБЕДА!" else "БАЗА ПАЛА", w / 2f, py + d(42f), d(26f), if (won) GREEN else RED)
            if (won) {
                for (k in 0 until 3) {
                    val appear = ((since - 0.25f - k * 0.3f) * 5f).coerceIn(0f, 1f)
                    val pop = if (appear < 1f) appear * 1.2f else 1f
                    star(g, w / 2f + (k - 1) * d(54f), py + d(100f), d(22f) * max(0.4f, pop), k < resultStars && appear > 0f)
                }
            } else {
                textMid(g, "Волна ${wd.wavesStarted} из ${wd.totalWaves}", w / 2f, py + d(100f), d(17f), TEXT)
            }
            textMid(g, "Жизни: ${wd.lives}/${wd.maxLives}", w / 2f, py + d(146f), d(14f), DIM)
            textMid(g, "Убито врагов: ${wd.kills}", w / 2f, py + d(168f), d(14f), DIM)
        }
        val bx = px + d(24f)
        val bw = pw - d(48f)
        var y = py + d(196f)
        if (won && next != null) {
            button(g, "next", bx, y, bw, d(48f), "ДАЛЬШЕ: ${next.name.uppercase()}", Style.PRIMARY, size = 15f) { startLevel(next) }
            y += d(58f)
            button(g, "again", bx, y, bw, d(42f), "ЕЩЁ РАЗ") { startLevel(lvl) }
        } else {
            button(g, "again", bx, y, bw, d(48f), "ЕЩЁ РАЗ", Style.PRIMARY, size = 16f) { startLevel(lvl) }
            y += d(58f)
            button(g, "levels", bx, y, bw, d(42f), if (lvl.endless) "В МЕНЮ" else "К УРОВНЯМ") {
                overlay = Overlay.NONE
                screen = if (lvl.endless) Screen.MENU else Screen.LEVELS
            }
        }
        y += d(52f)
        if (won && next != null) {
            button(g, "levels", bx, y, bw, d(42f), "К УРОВНЯМ", Style.GHOST) {
                overlay = Overlay.NONE
                screen = Screen.LEVELS
            }
        }
    }

    companion object {
        const val STEP = 1f / 60f

        val BG = 0xFF0E1412.toInt()
        val PANEL = 0xFF151C19.toInt()
        val PANEL_LIGHT = 0xFF1F2925.toInt()
        val EDGE = 0xFF33413B.toInt()
        val TEXT = 0xFFF1EDE4.toInt()
        val DIM = 0xFF8E9A93.toInt()
        val ACCENT = 0xFFFF9F1A.toInt()
        val ACCENT_DOWN = 0xFFE88A0A.toInt()
        val ACCENT_DARK = 0xFF9C5E0B.toInt()
        val GOLD = 0xFFFFD54A.toInt()
        val RED = 0xFFFF4B4B.toInt()
        val GREEN = 0xFF6BD66B.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val SCRIM = 0xB3000000.toInt()
    }
}
