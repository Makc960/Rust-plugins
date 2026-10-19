package com.makc.basedefense.engine

import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * What plays while the game opens: a plane bombs the road, the title lands with
 * the blast, the commanders line up and a bar fills, then the main menu, or for
 * a new player the [Story] first. A tap or the back button skips it.
 */
internal class Intro(private val game: Game) : WorldListener {
    private val ui get() = game.ui
    private val renderer get() = game.renderer
    private var world: World? = null
    private var acc = 0f

    /** Setting the scene makes no noise. */
    private var quiet = true

    /** Seconds since the intro began. */
    var time = 0f
        private set

    /** When the middle bomb goes off and the title lands; known once the plane is on its way. */
    private var boomAt = BOOM_GUESS
    private var struck = false
    private var landed = false
    private var stamped = false
    private var leaving = false

    private val stampAt: Float get() = boomAt + STAMP_DELAY
    private val crewAt: Float get() = boomAt + CREW_DELAY
    private val fullAt: Float get() = boomAt + FILL_DELAY
    private val endAt: Float get() = fullAt + HOLD

    private val xs = FloatArray(4)
    private val ys = FloatArray(4)

    private fun d(v: Float) = ui.d(v)

    override fun sound(sfx: Sfx, volume: Float) {
        if (!quiet) game.sound(sfx, volume)
    }

    fun update(dt: Float) {
        val w = world ?: stage().also { world = it }
        time += dt
        if (!struck && time >= STRIKE_AT) {
            struck = true
            boomAt = time + w.flyover(COLS / 2f, titleRow(), 0f)
        }
        acc += dt
        while (acc >= Game.STEP) {
            w.step(Game.STEP)
            acc -= Game.STEP
        }
        if (!landed && time >= boomAt) {
            landed = true
            game.vibrate(30)
        }
        if (!stamped && time >= stampAt) {
            stamped = true
            game.sound(Sfx.BUILD, 0.9f)
        }
        if (time >= endAt) skip()
    }

    fun skip() {
        if (leaving) return
        leaving = true
        if (game.storyDue) game.tellPrologue(Screen.MENU) else game.go(Screen.MENU)
    }

    /** The first map of the world the player has reached, with a few squads dug in by the road. */
    private fun stage(): World {
        val reached = ((game.profile.progress - 1) / Levels.PER_WORLD).coerceIn(0, Biome.entries.size - 1)
        val w = World(Levels.all[reached * Levels.PER_WORLD], Rules(), this)
        w.money = 100_000
        for (type in SQUADS) {
            val cell = bestCell(w)
            if (cell < 0) break
            w.build(type, cell % COLS, cell / COLS)
        }
        quiet = false
        return w
    }

    /** The free grass cell in sight that covers the most road. */
    private fun bestCell(w: World): Int {
        val p = FloatArray(4)
        var best = -1
        var most = 0
        for (r in 2 until ROWS - 3) for (c in 1 until COLS - 1) {
            if (!w.canPlace(TowerType.TURRET, c, r)) continue
            var n = 0
            for (path in w.map.paths) {
                var dist = 0f
                while (dist <= path.length) {
                    path.pointAt(dist, p)
                    if (hypot(p[0] - c - 0.5f, p[1] - r - 0.5f) <= SQUAD_REACH) n++
                    dist += 0.5f
                }
            }
            if (n > most) {
                most = n
                best = r * COLS + c
            }
        }
        return best
    }

    // --- drawing --------------------------------------------------------------------------

    private val cover: Float get() = max(ui.w / COLS, ui.h / ROWS)

    /** The camera closes in at the start and draws back to the whole map. */
    private fun zoom(t: Float): Float = 1f + ZOOM * (1f - smooth(t / CAMERA_TIME))

    private val titleY: Float get() = ui.h * TITLE_AT

    private val big: Float get() = min(d(60f), ui.w / 6f)

    /** The middle of the commanders' row, kept clear of the tagline on short screens. */
    private val crewY: Float get() = max(ui.h * CREW_AT, titleY + big * 1.82f + d(88f))

    /** The map row under the title when the bomb lands there. */
    private fun titleRow(): Float = ROWS / 2f + (titleY - big * 0.35f - ui.h / 2f) / (cover * zoom(BOOM_GUESS))

    fun render(g: Gfx) {
        val w = world
        // It rises out of the ground colour of Android's launch screen.
        if (w == null) {
            g.fillRect(0f, 0f, ui.w, ui.h, Ui.BG)
            return
        }
        drawMap(g, w)
        drawTitle(g)
        drawCrew(g)
        drawBar(g)
        val flash = (time - boomAt) / FLASH
        if (flash in 0f..1f) g.fillRect(0f, 0f, ui.w, ui.h, Colors.alpha(FLASH_COLOR, 0.3f * (1f - flash)))
        if (time < FADE_IN) g.fillRect(0f, 0f, ui.w, ui.h, Colors.alpha(Ui.BG, 1f - time / FADE_IN))
        ui.area("intro", 0f, 0f, ui.w, ui.h, silent = true) { if (time > SKIP_AFTER) skip() }
    }

    private fun drawMap(g: Gfx, w: World) {
        val cell = cover * zoom(time)
        val k = w.shake * cover * 0.15f
        val ox = ui.w / 2f + sin(time * 90f) * k - COLS / 2f * cell
        val oy = ui.h / 2f + sin(time * 77f + 1f) * k - ROWS / 2f * cell
        g.save()
        g.translate(ox, oy)
        g.scale(cell)
        renderer.drawWorld(g, w, time, cover)
        g.restore()
        // A dark map, lit up by the fires, sinking to the shade the menu keeps it in.
        g.fillRect(0f, 0f, ui.w, ui.h, Colors.alpha(SHADE, 0.6f + 0.16f * smooth(time / endAt)))
        for (b in w.blasts) {
            val t = b.age / FIRE_LIGHT
            if (t < 1f) g.glow(ox + b.x * cell, oy + b.y * cell, b.radius * cell * 2.4f, Colors.alpha(FIRE, 0.5f * (1f - t)))
        }
        shade(g, 0f, ui.h * 0.16f, 0.55f, 0f)
        shade(g, ui.h * 0.7f, ui.h, 0f, 0.6f)
    }

    /** Darkens the band from [top] to [bottom], [from] dark at its top to [to] at its bottom. */
    private fun shade(g: Gfx, top: Float, bottom: Float, from: Float, to: Float) {
        val strip = (bottom - top) / STRIPS
        for (i in 0 until STRIPS) {
            g.fillRect(0f, top + strip * i, ui.w, strip + 1f, Colors.alpha(SHADE, from + (to - from) * (i + 0.5f) / STRIPS))
        }
    }

    private fun drawTitle(g: Gfx) {
        val fall = (time - boomAt + DROP) / DROP
        if (fall <= 0f) return
        val cx = ui.w / 2f
        val ty = titleY
        val maxW = ui.w - d(40f)
        val since = time - boomAt
        // The blast behind the letters, and its ring running out.
        if (since > 0f) {
            val u = since / RING
            if (u < 1f) {
                g.glow(cx, ty - big * 0.35f, ui.w * 0.75f, Colors.alpha(FIRE, 0.6f * (1f - u)))
                g.strokeCircle(cx, ty - big * 0.35f, d(30f) + ui.w * 0.8f * u, Colors.alpha(FLASH_COLOR, 0.55f * (1f - u)), d(1f) + d(7f) * (1f - u))
            }
        }
        // Falls from above the screen and squashes a little on landing.
        val k = min(1f, fall)
        var s = 1f + (TITLE_FROM - 1f) * (1f - k * k)
        if (since in 0f..SETTLE) s -= 0.05f * sin(PI.toFloat() * since / SETTLE)
        val a = min(1f, k * 3f)
        g.save()
        g.translate(cx, ty)
        g.scale(s)
        ui.text(g, Tx.TITLE_1.t, d(3f), d(4f), big, Colors.alpha(Ui.EDGE, a), Align.CENTER, maxW, Font.DISPLAY)
        ui.text(g, Tx.TITLE_1.t, 0f, 0f, big, Colors.alpha(Ui.TEXT, a), Align.CENTER, maxW, Font.DISPLAY)
        g.restore()

        // The yellow plate is stamped on under it.
        val stamp = (time - stampAt) / STAMP
        if (stamp > 0f) {
            val sk = min(1f, stamp)
            g.save()
            g.translate(cx, ty + big * 0.8f)
            g.scale(1f + 0.8f * (1f - sk * sk))
            ui.tape(g, Tx.TITLE_2.t, 0f, 0f, ui.fit(g, Tx.TITLE_2.t, big * 0.9f, maxW - big, Font.DISPLAY), -3f)
            g.restore()
        }
        val line = ((time - stampAt - TAGLINE_DELAY) / RISE).coerceIn(0f, 1f)
        if (line > 0f) ui.textMid(g, Tx.TAGLINE.t.uppercase(), cx, ty + big * 1.82f, d(12f), Colors.alpha(Ui.DIM, line), maxW = maxW)
    }

    /** The three commanders come up one after another. */
    private fun drawCrew(g: Gfx) {
        val since = time - crewAt
        if (since <= 0f) return
        val cy = crewY
        val n = CommanderType.entries.size
        val slot = min((ui.w - d(24f)) / n, d(124f))
        val left = ui.w / 2f - slot * n / 2f
        val r = min(d(37f), slot * 0.42f)
        val head = min(1f, since / RISE)
        ui.textMid(g, Tx.INTRO_COMMANDERS.t.uppercase(), ui.w / 2f, cy - d(58f), d(11f), Colors.alpha(Ui.GOLD, head), maxW = ui.w - d(32f))
        for ((i, c) in CommanderType.entries.withIndex()) {
            val k = ((since - i * CREW_GAP) / RISE).coerceIn(0f, 1f)
            if (k <= 0f) continue
            val e = 1f - (1f - k) * (1f - k)
            val x = left + slot * (i + 0.5f)
            val y = cy + d(22f) * (1f - e)
            g.fillCircle(x, y, r, Colors.alpha(Ui.EDGE, e))
            g.fillCircle(x, y, r - d(3f), Colors.alpha(Ui.PANEL_DARK, e))
            g.strokeCircle(x, y, r - d(3f), Colors.alpha(renderer.heroColor(c), e), d(2f))
            renderer.drawCommanderIcon(g, c, x, y, r * 1.46f, e)
            val words = c.title.t.split(' ', limit = 2)
            ui.textMid(g, words[0].uppercase(), x, y + d(50f), d(9.5f), Colors.alpha(Ui.DIM, e), maxW = slot - d(8f))
            ui.textMid(g, words.getOrElse(1) { "" }, x, y + d(66f), d(14f), Colors.alpha(Ui.TEXT, e), maxW = slot - d(8f), font = Font.DISPLAY)
        }
    }

    /** Loading, of a sort: hazard stripes crawl along a bar that fills by the time the show is over. */
    private fun drawBar(g: Gfx) {
        val p = smooth((time - FILL_FROM) / (fullAt - FILL_FROM))
        val a = min(1f, time / FADE_IN)
        val bw = min(ui.w - d(72f), d(300f))
        val bh = d(12f)
        val x = (ui.w - bw) / 2f
        val y = ui.h - ui.bottom - d(78f)
        val done = p >= 1f
        ui.text(g, if (done) Tx.INTRO_READY.t else Tx.INTRO_LOADING.t, x, y - d(9f), d(11f), Colors.alpha(if (done) Ui.GOLD else Ui.DIM, a), font = Font.DISPLAY)
        ui.text(g, "${(p * 100f).toInt()}%", x + bw, y - d(9f), d(11f), Colors.alpha(Ui.TEXT, a), Align.RIGHT, font = Font.DISPLAY)
        ui.chamfer(g, x, y, bw, bh, d(3f), Colors.alpha(Ui.EDGE, a))
        ui.chamfer(g, x + d(2f), y + d(2f), bw - d(4f), bh - d(4f), d(2f), Colors.alpha(Ui.WELL, a))
        val fw = (bw - d(4f)) * p
        if (fw > 0f) {
            val fy = y + d(2f)
            val fh = bh - d(4f)
            g.save()
            g.clip(x + d(2f), fy, fw, fh)
            g.fillRect(x, fy, bw, fh, Ui.ACCENT)
            val step = d(12f)
            var sx = x - step * 2f + (time * d(24f)) % step
            while (sx < x + fw + step) {
                xs[0] = sx; ys[0] = fy
                xs[1] = sx + step / 2f; ys[1] = fy
                xs[2] = sx + step / 2f - fh; ys[2] = fy + fh
                xs[3] = sx - fh; ys[3] = fy + fh
                g.fillPolygon(xs, ys, 4, Ui.ACCENT_DOWN)
                sx += step
            }
            g.fillRect(x, fy, bw, d(1.5f), Ui.ACCENT_LIT)
            g.restore()
        }
        ui.textMid(g, Tx.VERSION.f(Game.VERSION), ui.w / 2f, y + bh + d(16f), d(10.5f), Colors.alpha(Ui.FADED, a))
    }

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private companion object {
        val SQUADS = listOf(TowerType.ROCKET, TowerType.TURRET, TowerType.SNIPER)
        const val SQUAD_REACH = 2.2f

        /** Seconds: the plane comes in, then everything waits on its middle bomb. */
        const val STRIKE_AT = 0.05f
        const val BOOM_GUESS = 1.4f
        const val DROP = 0.2f
        const val SETTLE = 0.16f
        const val FLASH = 0.22f
        const val FIRE_LIGHT = 0.8f
        const val RING = 0.55f
        const val STAMP_DELAY = 0.3f
        const val STAMP = 0.14f
        const val TAGLINE_DELAY = 0.2f
        const val CREW_DELAY = 0.7f
        const val CREW_GAP = 0.13f
        const val RISE = 0.35f
        const val FILL_FROM = 0.15f
        const val FILL_DELAY = 1.55f
        const val HOLD = 0.55f
        const val FADE_IN = 0.45f
        const val SKIP_AFTER = 0.25f
        const val CAMERA_TIME = 4.5f
        const val ZOOM = 0.12f

        /** The title starts this many times its size, above the reader. */
        const val TITLE_FROM = 2.6f

        /** Where the title and the commanders stand, as parts of the screen's height. */
        const val TITLE_AT = 0.3f
        const val CREW_AT = 0.585f
        const val STRIPS = 10

        val SHADE = 0xFF0E1009.toInt()
        val FIRE = 0xFFFF9A3C.toInt()
        val FLASH_COLOR = 0xFFFFF1C8.toInt()
    }
}
