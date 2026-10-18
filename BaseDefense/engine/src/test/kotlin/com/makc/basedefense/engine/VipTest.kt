package com.makc.basedefense.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What the VIP subscription changes, and how it is bought, kept and lost. */
class VipTest {
    @Test
    fun vipRulesGiveTheListedBonuses() {
        val free = Rules()
        val vip = Rules(vip = true)
        assertEquals(Math.round(200 * Vip.START_MONEY / 5f) * 5, vip.startMoney(200))
        assertEquals(200, free.startMoney(200))
        assertEquals(free.rewardMul * Vip.BOUNTY, vip.rewardMul, 0.001f)
        assertEquals(free.cooldownMul * Vip.COOLDOWN, vip.cooldownMul, 0.001f)
        assertEquals(3, free.maxSpeed)
        assertEquals(Vip.MAX_SPEED, vip.maxSpeed)
        assertEquals(4f, free.undoTime)
        assertEquals(Vip.UNDO_TIME, vip.undoTime)
        val world = World(Levels.all[0], vip)
        assertEquals(vip.startMoney(Levels.all[0].startMoney), world.money, "a VIP battle starts richer")
    }

    @Test
    fun aPaidPeriodIsNeverCutShort() {
        val p = Profile(SilentPlatform())
        assertFalse(p.vip(100))
        p.grantVip(130)
        assertTrue(p.vip(100))
        assertTrue(p.vip(130))
        assertFalse(p.vip(131))
        p.grantVip(110)
        assertEquals(130, p.vipUntil, "an older answer from the store does not shorten VIP")
        p.resetProgress()
        assertEquals(130, p.vipUntil, "resetting progress keeps a paid subscription")
    }

    @Test
    fun dailyWinsGiveVipPlayersTwoPoints() {
        val free = Profile(SilentPlatform())
        free.recordDaily(500, 20, won = true)
        val vip = Profile(SilentPlatform())
        vip.recordDaily(500, 20, won = true, vip = true)
        assertEquals(1, free.points)
        assertEquals(Vip.DAILY_POINTS, vip.points)
        vip.recordDaily(500, 20, won = true, vip = true)
        assertEquals(Vip.DAILY_POINTS, vip.points, "once a day")
    }

    @Test
    fun buyingCheckingAndSaving() {
        val platform = SilentPlatform(day = 1000)
        platform.vipShop = VipShop.HERE
        platform.payment = Payment.CANCELLED
        val game = Game(platform)
        assertFalse(game.vip)
        var heard: Payment? = null
        game.buyVip { heard = it }
        assertEquals(Payment.CANCELLED, heard)
        assertFalse(game.vip, "a cancelled payment gives nothing")
        platform.payment = Payment.PAID
        game.buyVip { heard = it }
        assertEquals(Payment.PAID, heard)
        assertTrue(game.vip)
        assertEquals(1030, game.profile.vipUntil)

        // A new install learns about the subscription from the store.
        val again = SilentPlatform(day = 1000)
        again.vipPaidUntil = 1020
        assertTrue(Game(again).vip)

        // A battle remembers whether it began with VIP.
        game.startCampaign(Levels.all[0], Difficulty.NORMAL, false)
        val s = assertNotNull(game.session)
        assertTrue(s.vip)
        val back = assertNotNull(Session.load(s.save(), WorldListener.NONE))
        assertTrue(back.vip)
        assertEquals(Vip.MAX_SPEED, back.rules.maxSpeed)
    }
}
