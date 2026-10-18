package com.makc.basedefense.engine

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives the game like a player (taps, drags, waits) and renders every screen
 * to PNG. Screenshots go to the folder in the `screens.dir` property when set.
 */
class ScreensTest {
    private val out = System.getProperty("screens.dir")?.let { File(it).apply { mkdirs() } }

    private class Phone(val w: Int, val h: Int, val density: Float, val top: Int, val bottom: Int)

    private val tall = Phone(1080, 2400, 2.75f, 80, 60)
    private val small = Phone(720, 1280, 2f, 48, 0)

    private fun game(phone: Phone, platform: Platform = SilentPlatform()): Game =
        Game(platform).apply { resize(phone.w, phone.h, phone.density, phone.top, phone.bottom) }

    private fun shot(game: Game, phone: Phone, name: String): BufferedImage {
        val img = BufferedImage(phone.w, phone.h, BufferedImage.TYPE_INT_ARGB)
        game.render(AwtGfx(img))
        out?.let { ImageIO.write(img, "png", File(it, "$name.png")) }
        return img
    }

    private fun run(game: Game, seconds: Float) {
        var t = 0f
        while (t < seconds) {
            game.update(1f / 30f)
            t += 1f / 30f
        }
    }

    private fun tap(game: Game, x: Float, y: Float) {
        game.touch(Touch.DOWN, x, y)
        game.touch(Touch.UP, x, y)
    }

    @Test
    fun screens() {
        for ((tag, phone) in listOf("tall" to tall, "small" to small)) {
            val platform = SilentPlatform()
            val g = game(phone, platform)
            run(g, 25f)
            shot(g, phone, "${tag}_1_menu")

            // "ИГРАТЬ" sits at half height.
            tap(g, phone.w / 2f, phone.h * 0.5f + 30 * phone.density)
            shot(g, phone, "${tag}_2_levels")

            g.startLevel(Levels.all[0])
            shot(g, phone, "${tag}_3_ready")

            g.placing = TowerType.TURRET
            val world = g.world!!
            shot(g, phone, "${tag}_4_placing")

            val bot = Bot(world)
            var t = 0f
            while (t < 70f) {
                bot.update(1f / 60f)
                world.step(1f / 60f)
                t += 1f / 60f
            }
            g.placing = null
            run(g, 0.5f)
            shot(g, phone, "${tag}_5_battle")

            g.selected = world.towers.first()
            shot(g, phone, "${tag}_6_selected")

            platform.putInt("stars_3", 2)
            g.startLevel(Levels.all.first { it.id == 7 })
            val w7 = g.world!!
            val bot7 = Bot(w7)
            t = 0f
            while (t < 140f) {
                bot7.update(1f / 60f)
                w7.step(1f / 60f)
                t += 1f / 60f
            }
            shot(g, phone, "${tag}_7_airfield")

            g.back()
            shot(g, phone, "${tag}_8_pause")
        }
    }

    @Test
    fun winScreenAndBosses() {
        val g = game(tall)
        g.startLevel(Levels.all.first { it.id == 8 })
        val w = g.world!!
        val bot = Bot(w)
        var bossShot = false
        while (w.phase != Phase.WON && w.phase != Phase.LOST && w.time < 4000f) {
            bot.update(1f / 60f)
            w.step(1f / 60f)
            if (!bossShot && w.enemies.any { it.type == EnemyType.HELI && it.y > 3f }) {
                g.update(0.01f)
                shot(g, tall, "tall_9_boss")
                bossShot = true
            }
        }
        assertEquals(Phase.WON, w.phase)
        assertTrue(bossShot, "the helicopter should show up on screen")
        run(g, 3f)
        shot(g, tall, "tall_10_win")
    }

    @Test
    fun playingByTouch() {
        val g = game(tall)
        g.startLevel(Levels.all[0])
        shot(g, tall, "touch_0")
        val w = g.world!!
        val d = tall.density
        val shopTop = tall.h - tall.bottom - 98 * d
        // First shop card is the turret.
        val cardW = (tall.w - 16 * d - 30 * d) / 6
        tap(g, 8 * d + cardW / 2, shopTop + 40 * d)
        assertEquals(TowerType.TURRET, g.placing)
        shot(g, tall, "touch_1")

        // Find a free cell next to the road and drag the tower there.
        val map = w.map
        var target: Pair<Int, Int>? = null
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (target == null && map.buildable(c, r) && (map.isRoad(c + 1, r) || map.isRoad(c - 1, r))) target = c to r
        }
        val (c, r) = assertNotNull(target)
        val cellPx = minOf(tall.w / COLS.toFloat(), (shopTop - 42 * d - (tall.top + 46 * d)) / ROWS)
        val mapX = (tall.w - cellPx * COLS) / 2f
        val mapTop = tall.top + 46 * d
        val mapY = mapTop + ((shopTop - 42 * d - mapTop) - cellPx * ROWS) / 2f
        val x = mapX + (c + 0.5f) * cellPx
        val y = mapY + (r + 0.5f) * cellPx
        g.touch(Touch.DOWN, x, y)
        g.touch(Touch.MOVE, x, y)
        shot(g, tall, "touch_2_drag")
        g.touch(Touch.UP, x, y)
        assertEquals(1, w.towers.size, "the turret is built where the finger was lifted")
        assertEquals(c, w.towers[0].col)
        assertEquals(r, w.towers[0].row)

        // Tap it to open the panel, then upgrade.
        tap(g, x, y)
        assertEquals(w.towers[0], g.selected)
        shot(g, tall, "touch_3_panel")
        val moneyBefore = w.money
        tap(g, 12 * d + 20 * d, shopTop + 62 * d + 15 * d)
        assertEquals(1, w.towers[0].level, "upgrade button works")
        assertEquals(moneyBefore - TowerType.TURRET.levels[1].cost, w.money)

        // Start the wave from the strip button.
        tap(g, tall.w - 20 * d, shopTop - 21 * d)
        assertEquals(Phase.RUNNING, w.phase)
        assertEquals(1, w.wavesStarted)

        // Pause and resume with the back button.
        g.back()
        g.back()
        g.back()
        val before = w.time
        run(g, 1f)
        assertTrue(w.time > before, "game runs again after resuming")
    }

    @Test
    fun soundsAreMade() {
        for (s in Sfx.entries) {
            val pcm = Synth.render(s)
            assertTrue(pcm.size in 300..Synth.RATE * 2, "$s length ${pcm.size}")
            assertTrue(pcm.any { it > 8000 }, "$s is silent")
            val wav = Synth.wav(pcm)
            assertEquals(44 + pcm.size * 2, wav.size)
        }
    }
}
