package com.makc.basedefense.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reminders while the game is closed, the store's rating window and its new versions. */
class RemindersTest {
    @Test
    fun aNewPlayerIsCalledBackToTheCampaign() {
        val platform = SilentPlatform()
        val game = Game(platform)
        game.pause()
        val r = platform.reminders
        assertEquals(listOf(platform.day + 1, platform.day + Reminders.QUIET_DAYS), r.map { it.day })
        assertEquals(Tx.REMIND_CAMPAIGN.t, r[0].title)
        assertEquals(Tx.REMIND_CAMPAIGN_TEXT.f(1), r[0].text)
    }

    @Test
    fun aStreakIsGuardedAndTomorrowsChallengeAnnounced() {
        val platform = SilentPlatform()
        platform.putInt("stars_1", 3)
        platform.putInt("stars_2", 3)
        val profile = Profile(platform)
        profile.recordDaily(platform.day - 2, 10, true)
        profile.recordDaily(platform.day - 1, 10, true)
        var r = Reminders.plan(profile, platform.day, vip = false)
        assertEquals(Tx.REMIND_STREAK.f(2), r[0].title)
        assertEquals(platform.day to Reminders.STREAK_AT, r[0].day to r[0].minute)
        assertEquals(Tx.REMIND_DAILY.t, r[1].title)
        val mods = Mod.forDay(platform.day + 1).joinToString(", ") { it.title.t }
        assertEquals(Tx.REMIND_DAILY_TEXT.f(mods), r[1].text)
        assertEquals(Tx.REMIND_DAILY_VIP.f(mods), Reminders.plan(profile, platform.day, vip = true)[1].text, "VIP hears of the gift too")

        // Once today's challenge is won, the streak needs no reminder.
        profile.recordDaily(platform.day, 10, true)
        r = Reminders.plan(profile, platform.day, vip = false)
        assertTrue(r.none { it.title == Tx.REMIND_STREAK.f(3) })

        profile.reminders = false
        assertTrue(Reminders.plan(profile, platform.day, vip = false).isEmpty(), "turned off, nothing is planned")
    }

    @Test
    fun theStoreAsksForARatingAfterTheThirdWinAndTheFifteenth() {
        val platform = SilentPlatform()
        val game = Game(platform)
        for (wins in 1..20) {
            game.profile.addStat(Stat.WINS, 1)
            game.askReviewIfDue()
            val expected = when {
                wins < 3 -> 0
                wins < 15 -> 1
                else -> 2
            }
            assertEquals(expected, platform.reviewsAsked, "after $wins wins")
        }
    }

    @Test
    fun allowingRemindersAsksThePhone() {
        val platform = SilentPlatform()
        platform.remindersAllowed = false
        platform.allowsReminders = false
        val game = Game(platform)
        var heard: Boolean? = null
        game.allowReminders { heard = it }
        assertEquals(false, heard)
        assertTrue(!game.profile.reminders, "the phone said no, so they stay off")
        platform.allowsReminders = true
        game.allowReminders { heard = it }
        assertEquals(true, heard)
        assertTrue(game.profile.reminders)
    }
}
