package com.makc.basedefense.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The boss rush: bosses only, against the clock, a new one each day. */
class BossRushTest {
    @Test
    fun everyWaveIsLedByBossesThePlayerHasMet() {
        for (progress in listOf(BossRush.UNLOCK, 20, 30, 40)) {
            val met = BossRush.bosses(progress)
            for (day in 20_000 until 20_010) {
                val level = Levels.bossRush(day, progress)
                assertEquals(BossRush.WAVES, level.plan.count)
                assertTrue(Levels.all.take(progress).any { it.ascii == level.ascii }, "a map the player has reached")
                val w = World(level)
                for (i in 0 until BossRush.WAVES) {
                    val bosses = assertNotNull(w.wave(i)).bosses
                    assertTrue(bosses.isNotEmpty(), "wave $i at $progress has a boss")
                    assertTrue(bosses.all { it in met }, "only bosses met by level $progress: $bosses")
                    if (i < 3) assertTrue(bosses.none { it == EnemyType.TITAN || it == EnemyType.AIRSHIP }, "the heavy ones come later")
                }
                assertEquals(met.toSet(), assertNotNull(w.wave(BossRush.WAVES - 1)).bosses.toSet(), "every kind at the end")
                for ((type, _) in level.plan.intro) assertTrue(type.campaign <= progress, "escorts the player knows")
            }
            assertEquals(Levels.bossRush(20_000, progress).ascii, Levels.bossRush(20_000, progress).ascii, "the same all day")
        }
        assertTrue((20_000 until 20_030).map { Levels.bossRush(it, 40).ascii }.toSet().size >= 8, "and a different map on other days")
        assertEquals(listOf(EnemyType.APC, EnemyType.HELI), BossRush.bosses(BossRush.UNLOCK))
    }

    @Test
    fun medalsGoByTime() {
        val p = BossRush.UNLOCK
        assertEquals(Weekly.GOLD, BossRush.medal(BossRush.goldTime(p), p))
        assertEquals(Weekly.SILVER, BossRush.medal(BossRush.goldTime(p) + 1, p))
        assertEquals(Weekly.SILVER, BossRush.medal(BossRush.silverTime(p), p))
        assertEquals(Weekly.BRONZE, BossRush.medal(BossRush.silverTime(p) + 1, p))
        assertTrue(BossRush.goldTime(40) > BossRush.goldTime(p), "more kinds of boss take longer")
        assertEquals("2:05", BossRush.clock(125))
    }

    @Test
    fun theDaysBestIsKept() {
        val platform = SilentPlatform()
        val profile = Profile(platform)
        assertFalse(profile.bossRushUnlocked)
        platform.putInt("stars_${BossRush.UNLOCK}", 1)
        assertTrue(profile.bossRushUnlocked)
        val day = platform.day
        assertEquals(0, profile.bossBest(day))
        assertTrue(profile.recordBossRush(day, 200, Weekly.SILVER))
        assertFalse(profile.recordBossRush(day, 230, Weekly.BRONZE), "a slower win is not the best")
        assertEquals(200, profile.bossBest(day))
        assertEquals(Weekly.SILVER, profile.bossMedal(day))
        assertTrue(profile.recordBossRush(day, 140, Weekly.GOLD))
        assertTrue(profile.recordBossRush(day, 130, Weekly.GOLD))
        assertEquals(1, profile.stat(Stat.BOSS_RUSH_GOLD), "gold counts once a day")
        assertTrue(profile.achieved(Achievement.BOSS_RUSH) || Achievement.BOSS_RUSH in profile.checkAchievements())
        assertEquals(0, profile.bossBest(day + 1), "a new day, a new rush")
        assertEquals(0, profile.bossMedal(day + 1))
    }

    @Test
    fun theBotBeatsMostRushes() {
        for (progress in listOf(12, 24, 40)) {
            var won = 0
            for (day in 20_000 until 20_005) {
                val w = Sim.play(Levels.bossRush(day, progress), Rules(progress = progress), { Bot(it) })
                Sim.report("rush p$progress d$day ${w.battleTime.toInt()}s", w)
                if (w.phase == Phase.WON) {
                    won++
                    assertTrue(w.battleTime > BossRush.goldTime(progress), "gold takes calling waves early")
                }
            }
            assertTrue(won >= 3, "the bot won only $won of 5 rushes at level $progress")
        }
    }

    @Test
    fun aRushIsSavedAndResumed() {
        val platform = SilentPlatform()
        platform.putInt("stars_${BossRush.UNLOCK}", 1)
        val g = Game(platform)
        g.startBossRush()
        val s = assertNotNull(g.session)
        assertEquals(Mode.BOSSES, s.mode)
        s.world.callWave()
        repeat(120) { s.world.step(1f / 30f) }
        val text = s.save()
        val back = assertNotNull(Session.load(text, object : WorldListener {}))
        assertEquals(Mode.BOSSES, back.mode)
        assertEquals(Levels.BOSS_RUSH_ID, back.level.id)
        assertEquals(text, back.save())
    }
}
