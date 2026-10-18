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

    @Test
    fun icon() {
        val size = 512
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        // Drawn in shapes, which stay sharp this big, like the launcher icon.
        val g = object : Gfx by AwtGfx(img) {
            override val sprites: Boolean get() = false
        }
        val r = Renderer()
        g.fillRect(0f, 0f, size.toFloat(), size.toFloat(), 0xFF1E3324.toInt())
        // A strip of road behind the tower.
        g.save()
        g.scale(size / 4f)
        g.fillRect(0f, 2.55f, 4f, 0.9f, 0xFF6B5638.toInt())
        g.fillRect(0f, 2.62f, 4f, 0.76f, 0xFFA58A62.toInt())
        g.restore()
        g.glow(size / 2f, size * 0.45f, size * 0.42f, 0x55FF9F1A)
        r.drawTowerIcon(g, TowerType.TURRET, 2, size / 2f, size * 0.47f, size * 0.78f, 0.6f, Branch.MINIGUN)
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
        g.glow(w * 0.3f, h * 0.5f, w * 0.45f, 0xCC0E1412.toInt())
        for ((lang, y) in listOf(Language.RU to 0f)) {
            I18n.lang = lang
            val size = 96f
            g.text(Tx.TITLE_1.t, 60f + 5f, h * 0.45f + 5f + y, size, 0xFF000000.toInt())
            g.text(Tx.TITLE_1.t, 60f, h * 0.45f + y, size, 0xFFFF9F1A.toInt())
            g.text(Tx.TITLE_2.t, 60f + 5f, h * 0.45f + size + 5f + y, size, 0xFF000000.toInt())
            g.text(Tx.TITLE_2.t, 60f, h * 0.45f + size + y, size, 0xFFF1EDE4.toInt())
        }
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
