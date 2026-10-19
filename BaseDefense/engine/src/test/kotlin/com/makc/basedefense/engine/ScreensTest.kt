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

    /** A game on [phone]; the news of this version count as read unless [news]. */
    private fun game(phone: Phone, platform: Platform = SilentPlatform(), news: Boolean = false): Game {
        if (!news && platform.getString("news_seen") == null) platform.putString("news_seen", Game.VERSION)
        return Game(platform).apply { resize(phone.w, phone.h, phone.density, phone.top, phone.bottom) }
    }

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
        for (l in Levels.all) if (l.id < Levels.all.size) p.putInt("stars_${l.id}", 3)
        for (key in Codex.keys) p.putInt("seen_$key", 1)
        p.putInt("seen_${Tips.COMMANDER}", 1)
    }

    /** Pixel position of a map point on the battle screen at zoom 1. */
    private fun mapPoint(phone: Phone, x: Float, y: Float): Pair<Float, Float> {
        val d = phone.density
        val shopY = phone.h - phone.bottom - 110 * d
        val stripY = shopY - 50 * d
        val mapTop = phone.top + 46 * d
        val mapH = stripY - mapTop
        val cell = minOf(phone.w / COLS.toFloat(), mapH / ROWS)
        val mapX = (phone.w - cell * COLS) / 2f
        val mapY = mapTop + (mapH - cell * ROWS) / 2f
        return (mapX + x * cell) to (mapY + y * cell)
    }

    private fun cellCentre(phone: Phone, c: Int, r: Int): Pair<Float, Float> = mapPoint(phone, c + 0.5f, r + 0.5f)

    /** Runs the game until [done], closing any card that pops up on the way. */
    private fun runUntil(game: Game, phone: Phone, seconds: Float, done: () -> Boolean) {
        var t = 0f
        while (t < seconds && !done()) {
            if (has(game, phone, "gotit")) press(game, phone, "gotit", 0f)
            game.update(1f / 30f)
            t += 1f / 30f
        }
    }

    @Test
    fun firstLevelByTouch() {
        for ((tag, phone) in listOf("tall" to tall, "small" to small)) {
            val g = game(phone)
            run(g, 20f)
            // A new player is offered the tutorial first.
            assertTrue(has(g, phone, "popupGo"), "the tutorial is offered")
            shot(g, phone, "${tag}_00_welcome")
            press(g, phone, "popupLater")
            shot(g, phone, "${tag}_01_menu")
            press(g, phone, "play")
            assertEquals(Screen.LEVELS, g.screen)
            shot(g, phone, "${tag}_02_levels")
            press(g, phone, "level1")
            shot(g, phone, "${tag}_03_sheet")
            press(g, phone, "fight")
            assertEquals(Screen.PLAY, g.screen)
            val w = g.play.world!!
            // The first battle with a commander explains him.
            assertTrue(has(g, phone, "gotit"), "a card about the commander")
            shot(g, phone, "${tag}_04_commander_card")
            press(g, phone, "gotit", 0f)
            assertNotNull(w.commander)
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
        for (w in Biome.entries.indices) {
            press(g, tall, "world$w", 0f)
            shot(g, tall, "tall_20_world${w + 1}")
        }
        // Dunes in a sandstorm, the north in a blizzard, the volcano erupting, a boss and branches everywhere.
        for ((id, name) in listOf(14 to "desert", 23 to "winter", 30 to "volcano")) {
            g.startCampaign(Levels.all.first { it.id == id }, Difficulty.NORMAL, false)
            run(g, 0.5f)
            g.profile.tips = false
            botPlay(g, 400f) { it.weather > 2f && it.wavesStarted > 4 }
            run(g, 0.4f)
            shot(g, tall, "tall_21_$name")
            botPlay(g, 3000f) { it.enemies.any { e -> e.type.boss && e.dist > 3f } }
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
        platform.supportUrl = "https://example.org/support"
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
        press(g, tall, "support", 0f)
        assertEquals(listOf("https://example.org/support"), platform.opened, "the support button opens the author's page")
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
    fun vipIsShownComparedAndBought() {
        val platform = SilentPlatform()
        unlockAll(platform)
        platform.shop = Shop.HERE
        val g = game(tall, platform)
        run(g, 2f)
        // Now and then the menu tells about VIP; not again for a few days.
        assertTrue(has(g, tall, "popupGo"), "the VIP offer pops up")
        shot(g, tall, "tall_79_vip_offer")
        press(g, tall, "popupLater")
        press(g, tall, "perks")
        g.back()
        run(g, 2f)
        assertTrue(!has(g, tall, "popupGo"), "the offer does not nag")
        shot(g, tall, "tall_80_menu_vip")
        press(g, tall, "vip")
        assertEquals(Screen.VIP, g.screen)
        shot(g, tall, "tall_81_vip")
        g.ui.scrollTo(Screen.VIP.name, 10_000f)
        press(g, tall, "vipbuy", 0.5f)
        assertTrue(g.vip, "paying turns VIP on")
        assertTrue(!has(g, tall, "vipbuy"), "nothing to buy while VIP is on")
        shot(g, tall, "tall_82_vip_on")
        g.back()
        run(g, 0.4f)
        shot(g, tall, "tall_83_menu_vip_on")

        g.profile.tips = false
        g.startCampaign(Levels.all.first { it.id == 5 }, Difficulty.NORMAL, false)
        run(g, 0.5f)
        repeat(3) { press(g, tall, "speed", 0f) }
        assertEquals(Vip.MAX_SPEED, g.play.speed, "VIP adds a fourth speed")
        press(g, tall, "speed", 0f)
        assertEquals(1, g.play.speed)
        assertEquals(Vip.COMMANDER_LEVEL, g.play.world!!.commander!!.level, "a VIP commander starts a level up")
        botPlay(g, 40f)
        shot(g, tall, "tall_84_battle_vip")

        // The APK from GitHub shows VIP but sends players to RuStore for it.
        val github = SilentPlatform()
        github.shop = Shop.ELSEWHERE
        val g2 = game(small, github)
        run(g2, 1f)
        press(g2, small, "vip")
        shot(g2, small, "small_81_vip_elsewhere")
        assertTrue(!has(g2, small, "restore"))

        // The Google Play build has no VIP at all.
        val play = game(tall, SilentPlatform())
        run(play, 1f)
        assertTrue(!has(play, tall, "vip"), "no VIP on Google Play")
    }

    @Test
    fun vipRemindersAfterADefeatAndWhenItEnds() {
        // A defeat in the RuStore version shows what VIP's second chance would do.
        val platform = SilentPlatform()
        unlockAll(platform)
        platform.shop = Shop.HERE
        val g = game(tall, platform)
        g.profile.tips = false
        g.startCampaign(Levels.all.first { it.id == 9 }, Difficulty.HARD, false)
        run(g, 0.5f)
        val w = g.play.world!!
        w.callWave()
        while (!w.over) w.step(1f / 60f)
        run(g, 3f)
        assertTrue(has(g, tall, "defeatVip"))
        shot(g, tall, "tall_85_defeat_vip")
        press(g, tall, "defeatVip")
        assertEquals(Screen.VIP, g.screen)

        // Once VIP has run out, the menu offers to renew it, once.
        val ended = SilentPlatform(day = 2000)
        unlockAll(ended)
        ended.shop = Shop.HERE
        ended.putInt("vip_until", 1995)
        val g2 = game(tall, ended)
        run(g2, 2f)
        assertTrue(has(g2, tall, "popupGo"))
        shot(g2, tall, "tall_86_vip_ended")
        press(g2, tall, "popupGo")
        assertEquals(Screen.VIP, g2.screen)
        val g3 = game(tall, ended)
        run(g3, 2f)
        assertTrue(!has(g3, tall, "popupGo"), "the reminder shows once")
    }

    @Test
    fun theIntroPlaysWhenTheGameOpens() {
        val platform = SilentPlatform()
        val g = Game(platform, showIntro = true).apply { resize(tall.w, tall.h, tall.density, tall.top, tall.bottom) }
        assertEquals(Screen.INTRO, g.screen)
        run(g, 0.7f)
        shot(g, tall, "tall_00_intro_plane")
        run(g, 0.75f)
        shot(g, tall, "tall_00_intro_blast")
        assertTrue(Sfx.PLANE in platform.played && Sfx.BOOM in platform.played, "the plane flies over and bombs")
        run(g, 1f)
        shot(g, tall, "tall_00_intro_title")
        run(g, 0.7f)
        shot(g, tall, "tall_00_intro_ready")
        run(g, 2.5f)
        assertEquals(Screen.MENU, g.screen, "the menu follows by itself")
        assertTrue(has(g, tall, "popupGo"), "and offers a new player the tutorial")

        // A tap skips it.
        val g2 = Game(SilentPlatform(), showIntro = true).apply { resize(small.w, small.h, small.density, small.top, small.bottom) }
        run(g2, 2.4f)
        shot(g2, small, "small_00_intro")
        press(g2, small, "intro", 0.5f)
        assertEquals(Screen.MENU, g2.screen)
    }

    @Test
    fun newVersionsAndRemindersAreOfferedOnce() {
        val platform = SilentPlatform()
        unlockAll(platform)
        platform.putInt("stat_WINS", 5)
        platform.updateOut = true
        platform.remindersAllowed = false
        val g = game(tall, platform)
        run(g, 2f)
        shot(g, tall, "tall_a0_update")
        press(g, tall, "popupGo")
        assertEquals(1, platform.updatesStarted, "the store is asked to install it")

        // The next visit to the menu asks about reminders.
        press(g, tall, "settings")
        assertTrue(has(g, tall, "remind"))
        press(g, tall, "back")
        run(g, 2f)
        shot(g, tall, "tall_a1_reminders")
        press(g, tall, "popupGo")
        assertTrue(g.profile.reminders && platform.remindersAllowed)
        g.pause()
        assertTrue(platform.reminders.isNotEmpty(), "planned when the game goes to the background")

        // Reminders are not asked about again; the update is offered on each start until it is in.
        platform.updateOut = false
        val g2 = game(tall, platform)
        run(g2, 2f)
        assertTrue(!has(g2, tall, "popupGo"))
    }

    @Test
    fun aWonBattleRaisesTheCommandersRank() {
        val platform = SilentPlatform()
        unlockAll(platform)
        platform.putInt("service_SERGEANT", Ranks.SERVICE[Ranks.THIRD_SKILL] - 100)
        val g = game(tall, platform)
        g.profile.tips = false
        g.startCampaign(Levels.all[0], Difficulty.NORMAL, false)
        run(g, 0.5f)
        val w = g.play.world!!
        w.commander!!.earned = 4000f
        w.winForTest()
        run(g, 4f)
        shot(g, tall, "tall_a2_rank_up")
        assertEquals(Ranks.THIRD_SKILL, g.profile.rank(CommanderType.SERGEANT))
    }

    @Test
    fun looksForSquadsAndCommanders() {
        val platform = SilentPlatform()
        unlockAll(platform)
        platform.shop = Shop.HERE
        platform.putInt("promo_day", platform.today())
        val g = game(tall, platform)
        run(g, 2f)
        press(g, tall, "wardrobe")
        assertEquals(Screen.WARDROBE, g.screen)
        press(g, tall, "camoDESERT")
        assertEquals(Camo.DESERT, g.profile.battleCamo)
        press(g, tall, "camoNIGHT")
        assertTrue(g.profile.camoOpen(Camo.NIGHT), "bought")
        shot(g, tall, "tall_a3_looks")
        g.ui.scrollTo(Screen.WARDROBE.name, 10_000f)
        press(g, tall, "outfitSERGEANTNIGHT", 0f)
        assertEquals(Camo.NIGHT, g.profile.battleOutfit(CommanderType.SERGEANT))
        press(g, tall, "outfitGROMURBAN", 0f)
        assertEquals(Camo.FIELD, g.profile.battleOutfit(CommanderType.GROM), "a closed uniform is not worn")
        shot(g, tall, "tall_a4_uniforms")
    }

    @Test
    fun advertsSaveABattleAndBringRewards() {
        val platform = SilentPlatform()
        unlockAll(platform)
        platform.ads = true
        val g = game(tall, platform)
        g.profile.tips = false
        g.startCampaign(Levels.all[0], Difficulty.NORMAL, false)
        run(g, 0.5f)
        val w = g.play.world!!
        w.callWave()
        run(g, 2f)
        w.lives = 0
        runUntil(g, tall, 4f) { has(g, tall, "revive") }
        shot(g, tall, "tall_a5_revive")
        press(g, tall, "revive")
        assertEquals(1, platform.adsShown)
        assertEquals(Phase.RUNNING, w.phase, "the reserves came and the fight goes on")
        assertEquals(Ads.REVIVE_LIVES, w.lives)
        assertTrue(w.revived)

        // The second fall is the end: the results offer the season points again instead.
        w.lives = 0
        runUntil(g, tall, 4f) { has(g, tall, "levels") }
        assertTrue(!has(g, tall, "revive"))
        val before = g.profile.seasonXp(platform.today())
        shot(g, tall, "tall_a6_double_season")
        press(g, tall, "doubleSeason")
        assertEquals(2, platform.adsShown)
        assertEquals(before + Season.xp(Mode.CAMPAIGN, false, 0), g.profile.seasonXp(platform.today()))
        assertTrue(!has(g, tall, "doubleSeason"), "once a battle")

        // An advert a day brings an upgrade point; a video not watched through brings nothing.
        g.go(Screen.PERKS)
        run(g, 1f)
        val points = g.profile.freePoints
        platform.adWatched = false
        press(g, tall, "adPoint")
        assertEquals(points, g.profile.freePoints)
        platform.adWatched = true
        shot(g, tall, "tall_a7_ad_point")
        press(g, tall, "adPoint")
        assertEquals(points + Ads.DAILY_POINTS, g.profile.freePoints)
        assertTrue(!has(g, tall, "adPoint"), "once a day")
        platform.day++
        assertTrue(has(g, tall, "adPoint"), "and again the next day")

        // VIP sees no adverts, and neither does anyone after a day's worth.
        g.profile.grantVip(platform.today() + 30)
        assertTrue(!has(g, tall, "adPoint"))
        platform.day += 31
        assertTrue(has(g, tall, "adPoint"), "VIP is over")
        repeat(Ads.PER_DAY) { g.profile.countAd(platform.today()) }
        assertTrue(!has(g, tall, "adPoint"))
    }

    @Test
    fun playersOfAnEarlierVersionHearTheNewsOnce() {
        val platform = SilentPlatform()
        unlockAll(platform)
        platform.putInt("tutorial_offered", 1)
        val g = game(tall, platform, news = true)
        run(g, 1.5f)
        assertTrue(has(g, tall, "popupGo"))
        shot(g, tall, "tall_a8_news")
        press(g, tall, "popupGo")
        assertEquals(Screen.LEVELS, g.screen)
        assertEquals(Game.VERSION, g.profile.newsSeen)
        val again = game(tall, platform, news = true)
        run(again, 1.5f)
        assertTrue(!has(again, tall, "popupGo"), "once")

        // A new player meets the tutorial offer, and never these news.
        val fresh = SilentPlatform()
        val g2 = game(tall, fresh, news = true)
        run(g2, 1.5f)
        press(g2, tall, "popupLater")
        fresh.putInt("stars_1", 3)
        val g3 = game(tall, fresh, news = true)
        run(g3, 1.5f)
        assertTrue(!has(g3, tall, "popupGo"))
    }

    @Test
    fun listsGlideAfterAFlickAndSpringBackFromTheEnds() {
        val platform = SilentPlatform()
        unlockAll(platform)
        val g = game(tall, platform)
        run(g, 1f)
        g.go(Screen.ACHIEVEMENTS)
        run(g, 1f)
        val id = Screen.ACHIEVEMENTS.name
        val x = tall.w / 2f
        fun drag(fromY: Float, step: Float, steps: Int, release: Boolean = true) {
            g.touch(Touch.DOWN, x, fromY)
            for (k in 1..steps) {
                g.touch(Touch.MOVE, x, fromY + k * step)
                g.update(1f / 60f)
                frame(g, tall)
            }
            if (release) g.touch(Touch.UP, x, fromY + steps * step)
        }
        fun settle(seconds: Float) {
            var t = 0f
            while (t < seconds) {
                g.update(1f / 60f)
                frame(g, tall)
                t += 1f / 60f
            }
        }

        // A quick flick up: the list goes on after the finger leaves, then comes to rest.
        frame(g, tall)
        drag(1900f, -90f, 4)
        val released = g.ui.scrollOffset(id)
        settle(0.3f)
        val gliding = g.ui.scrollOffset(id)
        assertTrue(gliding > released + 50f, "it glides on: $released then $gliding")
        settle(4f)
        val rest = g.ui.scrollOffset(id)
        settle(0.5f)
        assertEquals(rest, g.ui.scrollOffset(id), "and stops")

        // A touch on a gliding list holds it where it is.
        drag(1900f, -90f, 4)
        settle(0.1f)
        g.touch(Touch.DOWN, x, 1200f)
        val caught = g.ui.scrollOffset(id)
        settle(0.5f)
        assertEquals(caught, g.ui.scrollOffset(id))
        g.touch(Touch.UP, x, 1200f)

        // Pulled past the top it follows the finger only part of the way, and springs back.
        g.ui.scrollTo(id, 0f)
        frame(g, tall)
        drag(700f, 60f, 6, release = false)
        val pulled = g.ui.scrollOffset(id)
        assertTrue(pulled < 0f && pulled > -360f, "past the top, held back: $pulled")
        g.touch(Touch.UP, x, 1060f)
        settle(1.5f)
        assertEquals(0f, g.ui.scrollOffset(id))
    }

    @Test
    fun promoCodesFromTheSettings() {
        val platform = SilentPlatform()
        platform.putInt("stars_1", 3)
        val g = game(tall, platform)
        g.promoCodes = listOf(PromoCode(Promo.hash("TEST-ADMIN"), admin = true))
        run(g, 1f)
        press(g, tall, "settings")
        assertTrue(!has(g, tall, "admin"), "no admin switch before the code")
        platform.typed = "nothing"
        press(g, tall, "promo")
        assertTrue(!g.profile.admin)
        platform.typed = "test-admin"
        press(g, tall, "promo")
        assertTrue(g.profile.admin)
        run(g, 0.5f)
        shot(g, tall, "tall_a9_admin")

        // Every world is open, and so are the commanders and VIP.
        g.go(Screen.MENU)
        run(g, 1f)
        press(g, tall, "play")
        press(g, tall, "world3", 0f)
        assertTrue(has(g, tall, "level32"))
        shot(g, tall, "tall_b0_admin_volcano")

        g.go(Screen.SETTINGS)
        run(g, 1f)
        press(g, tall, "admin")
        assertTrue(!g.profile.admin && g.profile.adminKnown)
    }

    @Test
    fun theWeeklyOperation() {
        val platform = SilentPlatform()
        unlockAll(platform)
        platform.putInt("promo_day", platform.today())
        val g = game(tall, platform)
        run(g, 2f)
        press(g, tall, "daily")
        press(g, tall, "missions1")
        shot(g, tall, "tall_a5_weekly")
        press(g, tall, "wstart", 0.5f)
        val s = g.session!!
        assertEquals(Mode.WEEKLY, s.mode)
        val w = g.play.world!!
        w.winForTest()
        run(g, 4f)
        shot(g, tall, "tall_a6_weekly_won")
        assertEquals(Weekly.GOLD, g.profile.weeklyMedal(Weekly.week(platform.today())))
        assertTrue(g.profile.points >= Weekly.POINTS.sum())

        val small2 = SilentPlatform()
        small2.putInt("stars_1", 3)
        small2.putInt("stars_2", 3)
        small2.putInt("seen_${Tips.COMMANDER}", 1)
        small2.putInt("tutorial_offered", 1)
        val g2 = game(small, small2)
        run(g2, 1f)
        press(g2, small, "daily")
        press(g2, small, "missions1")
        shot(g2, small, "small_a5_weekly_locked")
        assertTrue(!has(g2, small, "wstart"), "locked until level ${Weekly.UNLOCK}")
    }

    @Test
    fun theSeasonPass() {
        val platform = SilentPlatform()
        unlockAll(platform)
        platform.shop = Shop.HERE
        platform.putInt("promo_day", platform.today())
        val g = game(tall, platform)
        g.profile.addSeasonXp(platform.today(), Season.XP_PER_TIER * 4 + 120, vip = false)
        run(g, 2f)
        shot(g, tall, "tall_a7_menu_season")
        press(g, tall, "pass")
        assertEquals(Screen.PASS, g.screen)
        g.ui.scrollTo(Screen.PASS.name, 0f)
        run(g, 0.1f)
        shot(g, tall, "tall_a8_pass")
        press(g, tall, "claimF2")
        assertTrue(g.profile.claimed(platform.today(), 2, premium = false))
        press(g, tall, "passbuy", 0.5f)
        assertTrue(g.profile.passPremium(platform.today()))
        g.ui.scrollTo(Screen.PASS.name, 0f)
        run(g, 0.1f)
        press(g, tall, "claimP1")
        shot(g, tall, "tall_a9_pass_premium")
    }

    @Test
    fun commandersInMenusAndBattle() {
        val platform = SilentPlatform()
        for (l in Levels.all) if (l.id <= 8) platform.putInt("stars_${l.id}", 3)
        for (key in Codex.keys) platform.putInt("seen_$key", 1)
        platform.putInt("seen_${Tips.COMMANDER}", 1)
        platform.shop = Shop.HERE
        platform.putInt("promo_day", platform.today())
        // A sergeant with some service, Grom with his third skill.
        platform.putInt("service_SERGEANT", 1100)
        platform.putInt("service_GROM", Ranks.SERVICE[Ranks.THIRD_SKILL])
        val g = game(tall, platform)
        run(g, 2f)
        shot(g, tall, "tall_87_menu_commanders")
        press(g, tall, "commanders")
        assertEquals(Screen.COMMANDERS, g.screen)
        shot(g, tall, "tall_88_commanders")
        g.ui.scrollTo(Screen.COMMANDERS.name, 10_000f)
        press(g, tall, "buyGROM", 0.5f)
        assertTrue(g.profile.owned(Product.GROM))
        press(g, tall, "takeGROM", 0f)
        assertEquals(CommanderType.GROM, g.profile.battleCommander)
        shot(g, tall, "tall_89_commanders_bought")
        g.ui.scrollTo(Screen.COMMANDERS.name, 0f)
        press(g, tall, "starter", 0.5f)
        assertTrue(g.profile.commanderOwned(CommanderType.ENGINEER), "the recruit pack brings the engineer")

        // In battle: pick him, send him, use a skill.
        g.profile.tips = false
        g.startCampaign(Levels.all.first { it.id == 6 }, Difficulty.NORMAL, false)
        run(g, 0.5f)
        val w = g.play.world!!
        val c = assertNotNull(w.commander)
        assertEquals(CommanderType.GROM, c.type)
        press(g, tall, "shopHero", 0f)
        assertTrue(g.play.heroPicked)
        shot(g, tall, "tall_90_commander_panel")
        // Beside the middle of the road, where the fighting will be.
        val road = w.map.paths[0]
        val p = FloatArray(4)
        road.pointAt(road.length * 0.5f, p)
        val (mx, my) = mapPoint(tall, (p[0] - p[3] * 0.8f).coerceIn(0.5f, COLS - 0.5f), (p[1] + p[2] * 0.8f).coerceIn(0.5f, ROWS - 0.5f))
        tap(g, mx, my)
        assertTrue(c.moving, "a tap on the map sends him")
        run(g, 0.4f)
        shot(g, tall, "tall_91_commander_moving")
        press(g, tall, "wave", 0f)
        botPlay(g, 30f)
        // A few raiders walk right up to him.
        var near = 0f
        var best = Float.MAX_VALUE
        var d = 0f
        while (d < road.length) {
            road.pointAt(d, p)
            val dd = kotlin.math.hypot(p[0] - c.x, p[1] - c.y)
            if (dd < best) {
                best = dd
                near = d
            }
            d += 0.1f
        }
        repeat(4) { w.spawnForTest(EnemyType.BRUTE, near - it * 0.3f) }
        press(g, tall, "skill1", 0f)
        assertTrue(c.skillCooldowns[1] > 0f, "the barrage went off")
        run(g, 0.25f)
        shot(g, tall, "tall_92_barrage")

        // The APK from GitHub shows commanders but sells them only in RuStore.
        val github = SilentPlatform()
        github.shop = Shop.ELSEWHERE
        github.putInt("seen_${Tips.COMMANDER}", 1)
        github.putInt("tutorial_offered", 1)
        val g2 = game(small, github)
        run(g2, 1f)
        press(g2, small, "commanders")
        shot(g2, small, "small_88_commanders_elsewhere")
        assertTrue(!has(g2, small, "restore"))
    }

    @Test
    fun theTutorialTeachesStepByStep() {
        val g = game(tall)
        run(g, 2f)
        press(g, tall, "popupGo")
        val s = assertNotNull(g.session)
        assertEquals(Mode.TUTORIAL, s.mode)
        val w = g.play.world!!
        shot(g, tall, "tall_93_tutorial_intro")
        assertEquals(PlayScreen.Lesson.INTRO, g.play.lesson)
        press(g, tall, "lessonNext", 0f)
        assertEquals(PlayScreen.Lesson.PICK, g.play.lesson)
        shot(g, tall, "tall_94_tutorial_pick")
        press(g, tall, "shopTURRET", 0.1f)
        assertEquals(PlayScreen.Lesson.PLACE, g.play.lesson)
        shot(g, tall, "tall_95_tutorial_place")
        val cell = g.play.lessonCell
        val (x, y) = cellCentre(tall, cell % COLS, cell / COLS)
        tap(g, x, y)
        run(g, 0.1f)
        assertEquals(1, w.towers.size)
        assertEquals(PlayScreen.Lesson.START, g.play.lesson)
        press(g, tall, "wave", 0.1f)
        assertEquals(PlayScreen.Lesson.WATCH, g.play.lesson)
        runUntil(g, tall, 180f) { g.play.lesson != PlayScreen.Lesson.WATCH }
        assertEquals(PlayScreen.Lesson.SELECT, g.play.lesson)
        assertTrue(w.holdWaves, "the next wave waits for the lesson")
        val t = w.towers[0]
        val (tx, ty) = cellCentre(tall, t.col, t.row)
        tap(g, tx, ty)
        run(g, 0.1f)
        assertEquals(PlayScreen.Lesson.UPGRADE, g.play.lesson)
        shot(g, tall, "tall_96_tutorial_upgrade")
        press(g, tall, "up", 0.1f)
        assertEquals(1, t.level)
        assertEquals(PlayScreen.Lesson.HERO, g.play.lesson)
        val c = w.commander!!
        val (hx, hy) = mapPoint(tall, c.x, c.y)
        tap(g, hx, hy)
        run(g, 0.1f)
        assertEquals(PlayScreen.Lesson.MOVE, g.play.lesson)
        shot(g, tall, "tall_97_tutorial_hero")
        val road = w.map.paths[0]
        val p = FloatArray(4)
        road.pointAt(road.length * 0.55f, p)
        val (rx, ry) = mapPoint(tall, (p[0] + 0.8f).coerceAtMost(COLS - 0.5f), p[1])
        tap(g, rx, ry)
        run(g, 0.1f)
        assertEquals(PlayScreen.Lesson.WAVE2, g.play.lesson)
        press(g, tall, "wave", 0.1f)
        assertEquals(PlayScreen.Lesson.SKILL, g.play.lesson)
        runUntil(g, tall, 180f) {
            if (w.skillReady(0) && w.enemies.any { kotlin.math.hypot(it.x - c.x, it.y - c.y) < w.commanderRange(c) && !it.type.air }) {
                shot(g, tall, "tall_98_tutorial_skill")
                press(g, tall, "skill0", 0f)
            }
            g.play.lesson != PlayScreen.Lesson.SKILL
        }
        assertEquals(PlayScreen.Lesson.STRIKE, g.play.lesson)
        press(g, tall, "abAIRSTRIKE", 0f)
        assertTrue(g.play.aiming)
        val (ax, ay) = cellCentre(tall, 4, 7)
        tap(g, ax, ay)
        run(g, 0.1f)
        assertEquals(PlayScreen.Lesson.SPEED, g.play.lesson)
        press(g, tall, "speed", 0.1f)
        assertEquals(PlayScreen.Lesson.FINISH, g.play.lesson)
        shot(g, tall, "tall_99_tutorial_finish")
        botPlay(g, 1000f)
        run(g, 3f)
        assertEquals(Phase.WON, w.phase)
        assertTrue(g.profile.tutorialDone)
        shot(g, tall, "tall_100_tutorial_done")
        press(g, tall, "campaign")
        assertEquals(Screen.LEVELS, g.screen)
        assertNull(g.profile.save, "the tutorial leaves no battle to continue")
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
