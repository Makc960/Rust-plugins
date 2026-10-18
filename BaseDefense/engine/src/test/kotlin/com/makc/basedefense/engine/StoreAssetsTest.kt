package com.makc.basedefense.engine

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pictures for the Google Play listing: the 512 px icon, the 1024x500 feature
 * graphic and phone screenshots in both languages, all drawn by the game itself.
 * They land in `build/store`; the ones in the repository's `store` folder are copies.
 */
class StoreAssetsTest {
    private val out = File(System.getProperty("screens.dir") ?: "build/screens").resolveSibling("store").apply { mkdirs() }

    @AfterTest
    fun russianAgain() {
        I18n.lang = Language.RU
    }

    private fun save(img: BufferedImage, name: String) {
        // Play wants plain RGB pictures without transparency.
        val rgb = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply {
            drawImage(img, 0, 0, null)
            dispose()
        }
        ImageIO.write(rgb, "png", File(out, "$name.png"))
    }

    /** A filled shape through the points (x, y, x, y...). */
    private fun shape(g: Gfx, color: Int, vararg xy: Float) {
        val n = xy.size / 2
        g.fillPolygon(FloatArray(n) { xy[it * 2] }, FloatArray(n) { xy[it * 2 + 1] }, n, color)
    }

    /** Points along a cubic curve, without its start. */
    private fun cubic(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float): List<Float> = buildList {
        for (i in 1..16) {
            val t = i / 16f
            val u = 1f - t
            add(u * u * u * x0 + 3f * u * u * t * x1 + 3f * u * t * t * x2 + t * t * t * x3)
            add(u * u * u * y0 + 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t * y3)
        }
    }

    @Test
    fun icon() {
        val size = 512
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = AwtGfx(img)
        // The launcher icon (res/drawable/ic_launcher_foreground.xml) in the same
        // shapes; the part a phone shows, 72 of its 108 units, fills the picture.
        g.fillRect(0f, 0f, size.toFloat(), size.toFloat(), 0xFF2E3524.toInt())
        g.scale(size / 72f)
        g.translate(-18f, -18f)
        shape(g, 0xFF0F120B.toInt(), 42f, 27f, 66f, 27f, 81f, 42f, 81f, 66f, 66f, 81f, 42f, 81f, 27f, 66f, 27f, 42f)
        shape(g, 0xFF9A6A10.toInt(), 42.6f, 28.5f, 65.4f, 28.5f, 79.5f, 42.6f, 79.5f, 65.4f, 65.4f, 79.5f, 42.6f, 79.5f, 28.5f, 65.4f, 28.5f, 42.6f)
        shape(g, 0xFFFFD978.toInt(), 42.6f, 28.5f, 65.4f, 28.5f, 79.5f, 42.6f, 79.5f, 63.6f, 65.4f, 77.7f, 42.6f, 77.7f, 28.5f, 63.6f, 28.5f, 42.6f)
        shape(g, 0xFFF2B634.toInt(), 43.1f, 30f, 64.9f, 30f, 78f, 43.1f, 78f, 63.1f, 64.9f, 76.2f, 43.1f, 76.2f, 30f, 63.1f, 30f, 43.1f)
        val ink = 0xFF22200F.toInt()
        val dome = listOf(39f, 54f) + cubic(39f, 54f, 39f, 41.5f, 45.5f, 34f, 54f, 34f) + cubic(54f, 34f, 62.5f, 34f, 69f, 41.5f, 69f, 54f)
        shape(g, ink, *dome.toFloatArray())
        shape(g, ink, 38f, 53f, 70f, 53f, 74.5f, 58.5f, 33.5f, 58.5f)
        g.fillRect(40.2f, 46.5f, 27.6f, 2.7f, 0xFFF2B634.toInt())
        shape(g, ink, 45f, 65.5f, 54f, 60.5f, 63f, 65.5f, 63f, 69f, 54f, 64f, 45f, 69f)
        shape(g, ink, 45f, 71.5f, 54f, 66.5f, 63f, 71.5f, 63f, 75f, 54f, 70f, 45f, 75f)
        save(img, "icon-512")
        assertTrue(File(out, "icon-512.png").length() > 1000)
    }

    @Test
    fun featureGraphic() {
        val w = 1024
        val h = 500
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = AwtGfx(img)
        // A real battle from the desert, seen from above, with the title over it.
        val level = Levels.all.first { it.id == 16 }
        val world = World(level, Rules(progress = 16))
        val bot = Bot(world)
        while (world.wavesStarted < 9 && !world.over) {
            bot.update(1f / 60f)
            world.step(1f / 60f)
        }
        repeat(500) {
            bot.update(1f / 60f)
            world.step(1f / 60f)
        }
        val cell = w / ROWS.toFloat()
        g.save()
        // The map is tall; turn it on its side to fill the wide banner.
        g.translate(w.toFloat(), (h - cell * COLS) / 2f)
        g.rotate(90f)
        g.scale(cell)
        Renderer().drawWorld(g, world, 3f, cell)
        g.restore()
        g.fillRect(0f, 0f, w.toFloat(), h.toFloat(), 0x40000000)
        g.glow(w * 0.28f, h * 0.5f, w * 0.5f, 0xD8101309.toInt())
        I18n.lang = Language.RU
        val ui = Ui {}.apply { dp = 2.4f }
        val size = 100f
        val x = 64f
        val y = h * 0.46f
        g.text(Tx.TITLE_1.t, x + 5f, y + 6f, size, Ui.EDGE, font = Font.DISPLAY)
        g.text(Tx.TITLE_1.t, x, y, size, Ui.TEXT, font = Font.DISPLAY)
        ui.tape(g, Tx.TITLE_2.t, x + g.textWidth(Tx.TITLE_1.t, size, Font.DISPLAY) / 2f, y + size * 0.82f, size * 0.9f, -3f)
        save(img, "feature-graphic")
    }

    private fun phone(platform: Platform): Game = Game(platform).apply { resize(1080, 1920, 2.6f, 0, 0) }

    private fun shot(game: Game, name: String) {
        val img = BufferedImage(1080, 1920, BufferedImage.TYPE_INT_ARGB)
        game.render(AwtGfx(img))
        save(img, name)
    }

    private fun run(game: Game, seconds: Float) {
        var t = 0f
        while (t < seconds) {
            game.update(1f / 30f)
            t += 1f / 30f
        }
    }

    private fun play(game: Game, seconds: Float, until: (World) -> Boolean = { false }) {
        val w = game.play.world!!
        val bot = Bot(w)
        var t = 0f
        while (t < seconds && !w.over && !until(w)) {
            bot.update(1f / 60f)
            w.step(1f / 60f)
            t += 1f / 60f
        }
        game.update(0.02f)
    }

    private fun tap(game: Game, id: String) {
        val img = BufferedImage(1080, 1920, BufferedImage.TYPE_INT_ARGB)
        game.render(AwtGfx(img))
        val (x, y) = game.ui.centre(id) ?: error("no $id")
        game.touch(Touch.DOWN, x, y)
        game.touch(Touch.UP, x, y)
    }

    @Test
    fun screenshots() {
        for (lang in Language.entries) {
            val tag = lang.name.lowercase()
            val platform = SilentPlatform(language = tag)
            for (l in Levels.all) if (l.id <= 20) platform.putInt("stars_${l.id}", if (l.id % 3 == 0) 2 else 3)
            for (l in listOf(1, 2, 3, 5, 9, 12)) platform.putInt("hard_$l", 1)
            for (l in listOf(1, 4, 7, 10)) platform.putInt("chal_$l", 1)
            for (key in Codex.keys) platform.putInt("seen_$key", 1)
            val g = phone(platform)
            g.profile.tips = false
            run(g, 30f)
            shot(g, "screen-$tag-1-menu")

            g.startCampaign(Levels.all.first { it.id == 12 }, Difficulty.NORMAL, false)
            run(g, 0.5f)
            play(g, 600f) { it.wavesStarted >= 7 && it.enemies.size > 12 }
            run(g, 0.2f)
            shot(g, "screen-$tag-2-desert")

            g.startCampaign(Levels.all.first { it.id == 20 }, Difficulty.NORMAL, false)
            run(g, 0.5f)
            play(g, 3000f) { it.enemies.any { e -> e.type == EnemyType.HELI && e.y > 4f } }
            run(g, 0.1f)
            shot(g, "screen-$tag-3-boss")

            g.startCampaign(Levels.all.first { it.id == 7 }, Difficulty.NORMAL, false)
            run(g, 0.5f)
            play(g, 3000f) { w -> w.wavesStarted >= 8 && w.towers.any { it.type == TowerType.ROCKET && it.branch == null } }
            val w7 = g.play.world!!
            val fork = w7.towers.first { it.type == TowerType.ROCKET && it.branch == null }
            w7.money += 1000
            while (fork.canUpgrade) w7.upgrade(fork)
            w7.money = 640
            g.play.selected = fork
            run(g, 0.1f)
            shot(g, "screen-$tag-4-branches")

            g.go(Screen.MENU)
            run(g, 0.5f)
            tap(g, "play")
            run(g, 0.5f)
            tap(g, "world2")
            run(g, 0.2f)
            shot(g, "screen-$tag-5-worlds")

            g.go(Screen.MENU)
            run(g, 0.5f)
            tap(g, "codex")
            run(g, 0.5f)
            tap(g, "entry7")
            shot(g, "screen-$tag-6-codex")
        }
    }
}
