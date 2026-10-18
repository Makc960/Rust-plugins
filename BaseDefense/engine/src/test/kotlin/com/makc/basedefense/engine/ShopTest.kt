package com.makc.basedefense.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the shop sells, what each purchase gives, and how purchases come back. */
class ShopTest {
    private fun stars(p: Platform, n: Int) {
        var left = n
        for (l in Levels.all) {
            val s = minOf(3, left)
            p.putInt("stars_${l.id}", s)
            left -= s
        }
    }

    @Test
    fun commandersOpenWithStarsPurchasesOrVip() {
        val platform = SilentPlatform(day = 1000)
        val p = Profile(platform)
        assertTrue(p.commanderOpen(CommanderType.SERGEANT), "the sergeant is free")
        assertFalse(p.commanderOpen(CommanderType.ENGINEER))
        p.commander = CommanderType.ENGINEER
        assertEquals(CommanderType.SERGEANT, p.battleCommander, "a closed pick falls back to the sergeant")
        stars(platform, CommanderType.ENGINEER.stars)
        assertTrue(p.commanderOpen(CommanderType.ENGINEER), "stars bring her")
        assertEquals(CommanderType.ENGINEER, p.battleCommander)
        assertFalse(p.commanderOpen(CommanderType.GROM))
        p.grant(Product.GROM)
        assertTrue(p.commanderOpen(CommanderType.GROM), "or a purchase")

        val lent = Profile(SilentPlatform(day = 1000))
        lent.grantVip(1010)
        assertTrue(CommanderType.entries.all { lent.commanderOpen(it) }, "VIP lends every commander")
        assertFalse(lent.commanderOwned(CommanderType.GROM))
        val later = SilentPlatform(day = 1020)
        later.putInt("vip_until", 1010)
        assertFalse(Profile(later).commanderOpen(CommanderType.GROM), "and takes them back when it ends")
    }

    @Test
    fun theStarterPackAndVipForever() {
        val platform = SilentPlatform(day = 1000)
        platform.shop = Shop.HERE
        val game = Game(platform)
        val points = game.profile.points
        var heard: Payment? = null
        game.buy(Product.STARTER) { heard = it }
        assertEquals(Payment.PAID, heard)
        assertTrue(game.profile.owned(Product.STARTER))
        assertTrue(game.profile.commanderOwned(CommanderType.ENGINEER), "the pack brings the engineer")
        assertEquals(points + Product.STARTER_POINTS, game.profile.points)
        game.profile.grant(Product.STARTER)
        assertEquals(points + Product.STARTER_POINTS, game.profile.points, "once only")

        game.buy(Product.VIP_FOREVER) { heard = it }
        assertTrue(game.profile.vipForever)
        assertTrue(game.profile.vip(1000 + 365 * 100), "VIP for good")

        game.profile.resetProgress()
        assertTrue(game.profile.owned(Product.STARTER) && game.profile.vipForever, "a reset keeps purchases")
        assertEquals(Product.STARTER_POINTS, game.profile.points, "and the points bought")
    }

    @Test
    fun purchasesComeBackOnANewPhone() {
        val store = SilentPlatform(day = 1000)
        store.owned += Product.GROM
        store.owned += Product.STARTER
        store.vipPaidUntil = 1015
        val game = Game(store)
        assertTrue(game.profile.owned(Product.GROM) && game.profile.owned(Product.STARTER), "the store is asked on start")
        assertTrue(game.vip)
        var found: Boolean? = null
        store.storeAnswers = false
        game.checkPurchases { found = it }
        assertEquals(false, found, "no answer, nothing found")
        assertTrue(game.profile.owned(Product.GROM), "and nothing is taken away")
    }

    @Test
    fun vipGivesAGiftADay() {
        val platform = SilentPlatform(day = 1000)
        val p = Profile(platform)
        assertFalse(p.vipGift(1000), "no VIP, no gift")
        p.grantVip(1005)
        val points = p.points
        assertTrue(p.vipGift(1000))
        assertFalse(p.vipGift(1000), "once a day")
        assertTrue(p.vipGift(1001))
        assertEquals(points + 2 * Vip.DAILY_GIFT, p.points)
        assertFalse(p.vipGift(1006), "not after VIP ends")
    }

    @Test
    fun everyProductHasAnIdAndAPrice() {
        assertEquals(Product.entries.size, Product.entries.map { it.id }.toSet().size)
        for (p in Product.entries) {
            assertTrue(p.rubles > 0 && p.id.matches(Regex("[a-z_]+")), "$p")
            assertEquals(p, Product.byId(p.id))
        }
        assertEquals(listOf(Product.VIP_MONTH), Product.entries.filter { it.subscription })
        for (c in CommanderType.entries) assertTrue(c.stars == 0 || c.product != null, "$c can be bought as well as earned")
    }
}
