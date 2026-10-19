package com.makc.basedefense.engine

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The season pass: months, season points, tiers, rewards on both tracks and buying the premium one. */
class SeasonTest {
    private fun day(y: Int, m: Int, d: Int): Int = LocalDate.of(y, m, d).toEpochDay().toInt()

    @Test
    fun aSeasonIsACalendarMonth() {
        val oct = Season.of(day(2026, 10, 19))
        assertEquals(oct, Season.of(day(2026, 10, 1)))
        assertEquals(oct, Season.of(day(2026, 10, 31)))
        assertEquals(oct + 1, Season.of(day(2026, 11, 1)))
        assertEquals(day(2026, 10, 1), Season.firstDay(oct))
        assertEquals(13, Season.daysLeft(day(2026, 10, 19)))
        assertEquals("Октябрь", Season.name(oct).t)
        assertEquals(Season.TIERS, (1..Season.TIERS).count { Season.premium(it).amount >= 0 })
        assertEquals(Season.TIERS / 2, (1..Season.TIERS).count { Season.free(it) != null })
    }

    @Test
    fun battlesBringSeasonPointsAndTiers() {
        val platform = SilentPlatform(day = day(2026, 10, 19))
        val p = Profile(platform)
        assertEquals(150, Season.xp(Mode.CAMPAIGN, true, 3))
        assertEquals(20, Season.xp(Mode.CAMPAIGN, false, 0))
        assertEquals(350, Season.xp(Mode.WEEKLY, true, Weekly.GOLD))
        assertEquals(300, Season.xp(Mode.ENDLESS, false, 90), "endless counts its waves up to a cap")
        assertEquals(0, Season.xp(Mode.TUTORIAL, true, 0))
        assertEquals(100, p.addSeasonXp(platform.day, 100, vip = false))
        assertEquals(150, p.addSeasonXp(platform.day, 100, vip = true), "VIP earns half again")
        assertEquals(1, p.seasonTier(platform.day))
        assertEquals(0, p.seasonTier(day(2026, 11, 1)), "a new month starts a new season")
        p.addSeasonXp(platform.day, 1_000_000, vip = false)
        assertEquals(Season.TIERS, p.seasonTier(platform.day))
    }

    @Test
    fun rewardsAreTakenOnceAndPremiumNeedsThePass() {
        val platform = SilentPlatform(day = day(2026, 10, 19))
        platform.shop = Shop.HERE
        val game = Game(platform)
        val p = game.profile
        val today = platform.day
        p.addSeasonXp(today, Season.XP_PER_TIER * 5, vip = false)
        assertEquals(2, p.unclaimed(today), "two free rewards on five tiers")
        val points = p.points
        assertTrue(p.claim(today, 2, premium = false))
        assertFalse(p.claim(today, 2, premium = false), "once")
        assertEquals(points + Season.free(2)!!.amount, p.points)
        assertFalse(p.claim(today, 6, premium = false), "tier 6 is not reached")
        assertFalse(p.claim(today, 1, premium = true), "premium needs the pass")

        game.buy(Product.PASS) {}
        assertTrue(p.passPremium(today))
        assertEquals(6, p.unclaimed(today))
        val service = p.service(p.battleCommander)
        assertTrue(p.claim(today, 1, premium = true))
        assertEquals(service + Season.premium(1).amount, p.service(p.battleCommander))
        assertFalse(game.vip)
        assertTrue(p.claim(today, 5, premium = true))
        assertTrue(game.vip, "VIP days")
        assertEquals(today + Season.premium(5).amount - 1, p.vipUntil)

        p.addSeasonXp(today, 1_000_000, vip = false)
        assertTrue(p.claim(today, Season.TIERS, premium = true))
        assertTrue(p.camoOpen(Camo.URBAN), "the last premium tier brings the urban set")
        assertFalse(p.passPremium(day(2026, 11, 2)), "a pass lasts its month")
    }

    @Test
    fun thePassComesBackFromTheStoreForThisMonthOnly() {
        val platform = SilentPlatform(day = day(2026, 10, 19))
        platform.passDays += day(2026, 9, 28)
        assertFalse(assertNotNull(Game(platform)).profile.passPremium(platform.day), "last month's pass is over")
        platform.passDays += day(2026, 10, 3)
        assertTrue(Game(platform).profile.passPremium(platform.day))
    }
}
