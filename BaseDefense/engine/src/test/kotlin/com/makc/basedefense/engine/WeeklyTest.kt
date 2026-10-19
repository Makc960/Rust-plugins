package com.makc.basedefense.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The weekly operation: its week, its battle, its medals and their points. */
class WeeklyTest {
    @Test
    fun weeksRunMondayToSunday() {
        // 19 October 2026 was a Monday.
        val monday = 20_745
        assertEquals(Weekly.week(monday), Weekly.week(monday + 6))
        assertEquals(Weekly.week(monday) + 1, Weekly.week(monday + 7))
        assertEquals(Weekly.week(monday) - 1, Weekly.week(monday - 1))
        assertEquals(monday, Weekly.firstDay(Weekly.week(monday + 3)))
    }

    @Test
    fun medalsFollowTheLivesKept() {
        assertEquals(Weekly.GOLD, Weekly.medal(20, 20))
        assertEquals(Weekly.GOLD, Weekly.medal(18, 20))
        assertEquals(Weekly.SILVER, Weekly.medal(17, 20))
        assertEquals(Weekly.SILVER, Weekly.medal(10, 20))
        assertEquals(Weekly.BRONZE, Weekly.medal(9, 20))
    }

    @Test
    fun aBetterMedalPaysOnlyTheDifference() {
        val p = Profile(SilentPlatform())
        val points = p.points
        assertEquals(Weekly.POINTS[1], p.recordWeekly(100, Weekly.BRONZE))
        assertEquals(0, p.recordWeekly(100, Weekly.BRONZE), "the same medal twice pays once")
        assertEquals(Weekly.POINTS[2] + Weekly.POINTS[3], p.recordWeekly(100, Weekly.GOLD))
        assertEquals(points + Weekly.POINTS.sum(), p.points)
        assertEquals(Weekly.GOLD, p.weeklyMedal(100))
        assertEquals(0, p.weeklyMedal(101), "a new week starts clean")
        assertEquals(Weekly.POINTS[1], p.recordWeekly(101, Weekly.BRONZE))
        assertEquals(1, p.stat(Stat.WEEKLY_GOLD))
    }

    @Test
    fun theOperationIsTheSameAllWeekAndSaves() {
        val a = Levels.weekly(500, 24)
        val b = Levels.weekly(500, 24)
        assertEquals(a.ascii, b.ascii)
        assertEquals(Weekly.WAVES, a.plan.count)
        assertEquals(listOf(EnemyType.APC, EnemyType.HELI), a.plan.bosses[Weekly.WAVES - 1])
        assertEquals(Mod.forWeek(500), Mod.forWeek(500))
        assertNotEquals((0 until 20).map { Mod.forWeek(it) }, (0 until 20).map { Mod.forDay(it) }, "weeks are not days")

        val day = Weekly.firstDay(500) + 2
        val s = Session(Mode.WEEKLY, Levels.weekly(500, 24), Difficulty.NORMAL, false, day, 24, Perks.NONE, false, null, 1, WorldListener.NONE)
        assertEquals(Mod.forWeek(500), s.mods)
        val back = assertNotNull(Session.load(s.save(), WorldListener.NONE))
        assertEquals(Mode.WEEKLY, back.mode)
        assertEquals(a.ascii, back.level.ascii)
        assertTrue(back.mods == s.mods)
    }
}
