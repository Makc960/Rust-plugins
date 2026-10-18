package com.makc.basedefense.engine

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Drives the game like a player (taps, drags, waits) and renders every screen
 * to PNG. Screenshots go to the folder in the `screens.dir` property when set.
 */
class ScreensTest {
    private val out = System.getProperty("screens.dir")?.let { File(it).apply { mkdirs() } }

    class Phone(val w: Int, val h: Int, val density: Float, val top: Int, val bottom: Int)

    private val tall = Phone(1080, 2400, 2.75f, 80, 60)
    private val small = Phone(720, 1280, 2f, 48, 0)

    @AfterTest
    fun russianAgain() {
        I18n.lang = Language.RU
    }

    private fun game(phone: Phone, platform: Platform = SilentPlatform()): Game =
        Game(platform).apply { resize(phone.w, phone.h, phone.density, phone.top, phone.bottom) }

    private fun frame(game: Game, phone: Phone): BufferedImage {
        val img = BufferedImage(phone.w, phone.h, BufferedImage.TYPE_INT_ARGB)
        game.render(AwtGfx(img))
        return img
    }

    private fun shot(game: Game, phone: Phone, name: String): BufferedImage {
        val img = frame(game, phone)
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

    /** Taps the control with [id] as drawn in a fresh frame. */
    private fun press(game: Game, phone: Phone, id: String, settle: Float = 0.4f) {
        frame(game, phone)
        val (x, y) = game.ui.centre(id) ?: fail("no control '$id' on screen, have ${game.ui.ids()}")
        tap(game, x, y)
        run(game, settle)
    }

    private fun has(game: Game, phone: Phone, id: String): Boolean {
        frame(game, phone)
        return game.ui.centre(id) != null
    }

    /** Plays the current battle with a bot for [seconds] of game time. */
    private fun botPlay(game: Game, seconds: Float, until: (World) -> Boolean = { false }) {
        val w = game.play.world!!
        val bot = Bot(w)
        var t = 0f
        while (t < seconds && !w.over && !until(w)) {
            bot.update(1f / 60f)
            w.step(1f / 60f)
            t += 1f / 60f
        }
        game.update(0.01f)
    }

    private fun unlockAll(p: Platform) {
        for (l in Levels.all) if (l.id <= 23) p.putInt("stars_${l.id}", 3)
        for (key in Codex.keys) p.putInt("seen_$key", 1)
    }

    /** Pixel position of a map cell on the battle screen at zoom 1. */
    private fun cellCentre(phone: Phone, c: Int, r: Int): Pair<Float, Float> {
        val d = phone.density
        val shopY = phone.h - phone.bottom - 110 * d
        val stripY = shopY - 50 * d
        val mapTop = phone.top + 46 * d
        val mapH = stripY - mapTop
        val cell = minOf(phone.w / COLS.toFloat(), mapH / ROWS)
        val mapX = (phone.w - cell * COLS) / 2f
        val mapY = mapTop + (mapH - cell * ROWS) / 2f
        return (mapX + (c + 0.5f) * cell) to (mapY + (r + 0.5f) * cell)
    }

    @Test
    fun firstLevelByTouch() {
        for ((tag, phone) in listOf("tall" to tall, "small" to small)) {
            val g = game(phone)
            run(g, 20f)
            shot(g, phone, "${tag}_01_menu")
            press(g, phone, "play")
            assertEquals(Screen.LEVELS, g.screen)
            shot(g, phone, "${tag}_02_levels")
            press(g, phone, "level1")
            shot(g, phone, "${tag}_03_sheet")
            press(g, phone, "fight")
            assertEquals(Screen.PLAY, g.screen)
            val w = g.play.world!!
            shot(g, phone, "${tag}_04_ready")

            press(g, phone, "shopTURRET", 0f)
            assertEquals(TowerType.TURRET, g.play.placing)
            shot(g, phone, "${tag}_05_placing")

            val (x, y) = cellCentre(phone, 2, 2)
            g.touch(Touch.DOWN, x, y)
            g.touch(Touch.MOVE, x, y)
            shot(g, phone, "${tag}_06_drag")
            g.touch(Touch.UP, x, y)
            assertEquals(1, w.towers.size, "the turret is built where the finger was lifted")
            assertEquals(2, w.towers[0].col)
            val money = w.money
            shot(g, phone, "${tag}_07_undo")
            press(g, phone, "undo", 0f)
            assertEquals(0, w.towers.size, "undo takes the tower back")
            assertEquals(money + 50, w.money)

            press(g, phone, "shopTURRET", 0f)
            tap(g, x, y)
            assertEquals(1, w.towers.size, "a plain tap on a cell builds too")
            tap(g, x, y)
            assertEquals(w.towers[0], g.play.selected)
            shot(g, phone, "${tag}_08_panel")
            press(g, phone, "up", 0f)
            assertEquals(1, w.towers[0].level, "upgrade button works")

            press(g, phone, "wave", 0f)
            assertEquals(Phase.RUNNING, w.phase)
            // The first bandit walks in and its card pops up.
            run(g, 4f)
            assertTrue(has(g, phone, "gotit"), "a card about the bandit")
            shot(g, phone, "${tag}_09_card")
            press(g, phone, "gotit", 0f)
            g.profile.tips = false
            botPlay(g, 60f)
            run(g, 0.5f)
            shot(g, phone, "${tag}_10_battle")

            press(g, phone, "pause", 0f)
            shot(g, phone, "${tag}_11_pause")
            press(g, phone, "psettings", 0f)
            shot(g, phone, "${tag}_12_battle_settings")
            press(g, phone, "sback", 0f)
            press(g, phone, "resume", 0f)
            val before = w.time
            run(g, 1f)
            assertTrue(w.time > before, "game runs again after resuming")
            botPlay(g, 2000f)
            run(g, 3f)
            assertEquals(Phase.WON, w.phase)
            shot(g, phone, "${tag}_13_win")
            assertTrue(g.profile.stars(1) > 0)
            assertTrue(g.profile.achieved(Achievement.FIRST_WIN))
        }
    }

    @Test
    fun laterWorldsLookRight() {
        val platform = SilentPlatform()
        unlockAll(platform)
        val g = game(tall, platform)
        run(g, 1f)
        press(g, tall, "play")
        for (w in 0 until 3) {
            press(g, tall, "world$w", 0f)
            shot(g, tall, "tall_20_world${w + 1}")
        }
        // Dunes in a sandstorm, the north in a blizzard, a boss and branches everywhere.
        for ((id, name) in listOf(14 to "desert", 23 to "winter")) {
            g.startCampaign(Levels.all.first { it.id == id }, Difficulty.NORMAL, false)
            run(g, 0.5f)
            g.profile.tips = false
            botPlay(g, 400f) { it.weather > 2f && it.wavesStarted > 4 }
            run(g, 0.4f)
            shot(g, tall, "tall_21_$name")
            botPlay(g, 3000f) { it.enemies.any { e -> e.type.boss && e.y > 2f } }
            shot(g, tall, "tall_22_${name}_boss")
        }
    }

    @Test
    fun branchesAbilitiesAndSelling() {
        val platform = SilentPlatform()
        unlockAll(platform)
        val g = game(tall, platform)
        g.profile.tips = false
        g.startCampaign(Levels.all.first { it.id == 10 }, Difficulty.NORMAL, false)
        run(g, 0.5f)
        val w = g.play.world!!
        w.money = 3000
        val t = w.build(TowerType.ROCKET, 2, 4)!!
        w.upgrade(t)
        w.upgrade(t)
        val (x, y) = cellCentre(tall, 2, 4)
        frame(g, tall)
        tap(g, x, y)
        assertEquals(t, g.play.selected)
        shot(g, tall, "tall_30_fork")
        press(g, tall, "branchHEAVY", 0f)
        assertEquals(Branch.HEAVY, t.branch)
        shot(g, tall, "tall_31_branch")

        // Selling an expensive tower asks twice.
        val money = w.money
        press(g, tall, "sell", 0f)
        assertEquals(1, w.towers.size, "the first tap only asks")
        shot(g, tall, "tall_32_sell_confirm")
        press(g, tall, "sell", 0f)
        assertEquals(0, w.towers.size)
        assertTrue(w.money > money)

        // Airstrike: tap the ability, then the map.
        press(g, tall, "wave", 0f)
        botPlay(g, 30f)
        w.cooldowns.fill(0f)
        val strikes = w.airstrikes
        press(g, tall, "abAIRSTRIKE", 0f)
        assertTrue(g.play.aiming)
        val (ax, ay) = cellCentre(tall, 4, 3)
        g.touch(Touch.DOWN, ax, ay)
        g.touch(Touch.MOVE, ax, ay)
        shot(g, tall, "tall_33_aiming")
        g.touch(Touch.UP, ax, ay)
        assertTrue(!g.play.aiming && w.airstrikes == strikes + 1, "the airstrike was called")
        run(g, 0.5f)
        shot(g, tall, "tall_34_airstrike")
        press(g, tall, "abFREEZE", 0f)
        assertTrue(w.freezeTimer > 0f)
        run(g, 0.3f)
        shot(g, tall, "tall_35_freeze")

        // Clearing a rock.
        w.money = 1000
        val rock = (0 until ROWS).flatMap { r -> (0 until COLS).map { c -> c to r } }.first { (c, r) -> w.map.obstacle(c, r) }
        val (rx, ry) = cellCentre(tall, rock.first, rock.second)
        tap(g, rx, ry)
        assertEquals(rock.second * COLS + rock.first, g.play.obstacle)
        shot(g, tall, "tall_36_obstacle")
        press(g, tall, "clear", 0f)
        assertTrue(w.map.buildable(rock.first, rock.second))

        // Zooming in keeps the touched cell under the fingers.
        g.pinch(2f, rx, ry)
        g.pan(30f, 0f)
        run(g, 0.2f)
        shot(g, tall, "tall_37_zoom")
    }

    @Test
    fun menusAndSettings() {
        val platform = SilentPlatform()
        unlockAll(platform)
        val g = game(tall, platform)
        g.profile.addStat(Stat.KILLS, 1500)
        g.profile.checkAchievements()
        run(g, 1f)
        shot(g, tall, "tall_40_menu_progress")
        press(g, tall, "perks")
        shot(g, tall, "tall_41_perks")
        val free = g.profile.freePoints
        press(g, tall, "buyDAMAGE", 0f)
        assertEquals(1, g.profile.perk(Perk.DAMAGE))
        assertEquals(free - Perk.DAMAGE.cost, g.profile.freePoints)
        g.back()
        run(g, 0.4f)
        press(g, tall, "ach")
        shot(g, tall, "tall_42_achievements")
        g.back()
        run(g, 0.4f)
        press(g, tall, "codex")
        press(g, tall, "entry7", 0f)
        shot(g, tall, "tall_43_codex")
        press(g, tall, "ctab1", 0f)
        press(g, tall, "entry6", 0f)
        shot(g, tall, "tall_44_codex_towers")
        g.back()
        run(g, 0.4f)
        press(g, tall, "daily")
        assertEquals(Screen.DAILY, g.screen)
        shot(g, tall, "tall_45_daily")
        g.back()
        run(g, 0.4f)

        press(g, tall, "settings")
        shot(g, tall, "tall_46_settings")
        val r = assertNotNull(g.ui.sliderRect("music"))
        g.touch(Touch.DOWN, r[0] + r[2] * 0.25f, r[1] + r[3] / 2f)
        g.touch(Touch.UP, r[0] + r[2] * 0.25f, r[1] + r[3] / 2f)
        assertTrue(g.profile.music in 15..35, "the music slider moved to ${g.profile.music}")
        press(g, tall, "vibe", 0f)
        assertTrue(!g.profile.vibration)
        press(g, tall, "lang1", 0f)
        assertEquals(Language.EN, I18n.lang)
        assertEquals(Language.EN, g.profile.language)
        shot(g, tall, "tall_47_settings_en")
        g.back()
        run(g, 0.4f)
        shot(g, tall, "tall_48_menu_en")
        press(g, tall, "play")
        press(g, tall, "world1", 0f)
        press(g, tall, "level12")
        shot(g, tall, "tall_49_sheet_en")
        press(g, tall, "diff2", 0f)
        press(g, tall, "chal", 0f)
        press(g, tall, "fight")
        val s = assertNotNull(g.session)
        assertEquals(Difficulty.HARD, s.difficulty)
        assertTrue(s.challenge)
        g.profile.tips = false
        botPlay(g, 120f)
        run(g, 0.4f)
        shot(g, tall, "tall_50_battle_en")
    }

    @Test
    fun aBattleSurvivesTheAppClosing() {
        val platform = SilentPlatform()
        unlockAll(platform)
        val g = game(tall, platform)
        g.profile.tips = false
        g.startCampaign(Levels.all.first { it.id == 13 }, Difficulty.NORMAL, true)
        run(g, 0.5f)
        press(g, tall, "wave", 0f)
        botPlay(g, 90f)
        val w = g.play.world!!
        val money = w.money
        val towers = w.towers.size
        val wave = w.wavesStarted
        g.pause()
        assertNotNull(platform.getString("save"))

        // A new start of the app finds the battle and carries on.
        val again = game(tall, platform)
        run(again, 0.5f)
        assertTrue(has(again, tall, "continue"))
        shot(again, tall, "tall_60_continue")
        press(again, tall, "continue")
        val w2 = again.play.world!!
        assertEquals(money, w2.money)
        assertEquals(towers, w2.towers.size)
        assertEquals(wave, w2.wavesStarted)
        assertTrue(again.session!!.challenge)
        shot(again, tall, "tall_61_resumed")
        press(again, tall, "resume", 0f)
        botPlay(again, 3000f)
        run(again, 3f)
        assertEquals(Phase.WON, w2.phase)
        assertNull(platform.getString("save"), "a finished battle leaves no save")
    }

    @Test
    fun endlessAndDailyResults() {
        val platform = SilentPlatform(day = 20_500)
        unlockAll(platform)
        val g = game(small, platform)
        g.profile.tips = false
        g.startEndless()
        run(g, 0.5f)
        botPlay(g, 6000f)
        run(g, 3f)
        assertTrue(g.profile.bestEndless > 5)
        shot(g, small, "small_70_endless_over")
        g.startDaily()
        run(g, 0.5f)
        botPlay(g, 6000f)
        run(g, 3f)
        shot(g, small, "small_71_daily_over")
        assertTrue(g.profile.dailyBest(20_500) > 0)
    }

    @Test
    fun musicFollowsTheScreen() {
        val platform = SilentPlatform()
        val g = game(small, platform)
        run(g, 0.2f)
        assertEquals(Track.MENU, platform.track)
        g.startCampaign(Levels.all[0], Difficulty.NORMAL, false)
        run(g, 0.5f)
        assertEquals(Track.BATTLE, platform.track)
        g.profile.music = 0
        run(g, 0.1f)
        assertNull(platform.track, "music off")
    }

    @Test
    fun soundsAndMusicAreMade() {
        for (s in Sfx.entries) {
            val pcm = Synth.render(s)
            assertTrue(pcm.size in 300..Synth.RATE * 2, "$s length ${pcm.size}")
            assertTrue(pcm.any { it > 8000 }, "$s is silent")
            val wav = Synth.wav(pcm)
            assertEquals(44 + pcm.size * 2, wav.size)
        }
        for (t in Track.entries) {
            val pcm = Music.render(t)
            val seconds = pcm.size / Music.RATE.toFloat()
            assertTrue(seconds in 20f..60f, "$t lasts $seconds s")
            assertTrue(pcm.any { it > 12000 }, "$t is too quiet")
            // The loop joins smoothly: no big jump from the last sample to the first.
            assertTrue(kotlin.math.abs(pcm.last() - pcm.first()) < 12000, "$t clicks at the loop point")
        }
    }
}
