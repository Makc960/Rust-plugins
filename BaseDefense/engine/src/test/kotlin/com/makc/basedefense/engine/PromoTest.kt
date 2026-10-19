package com.makc.basedefense.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Promo codes, and the admin's that opens everything. */
class PromoTest {
    private val gift = PromoCode(Promo.hash("VULKAN-3"), listOf(Reward(RewardKind.POINTS, 5), Reward(RewardKind.VIP_DAYS, 3)))
    private val admin = PromoCode(Promo.hash("TEST-ADMIN"), admin = true)

    @Test
    fun codesAreKeptAsHashesAndTypedAnyWay() {
        assertEquals(64, Promo.CODES.single { it.admin }.hash.length)
        for (weak in listOf("", "ADMIN", "admin", "basedefense", "1234", "ОБОРОНА")) {
            assertNull(Promo.find(weak), "'$weak' opens nothing")
        }
        val codes = listOf(gift, admin)
        for (typed in listOf("VULKAN-3", "vulkan3", "  Vulkan 3 ", "VUL-KAN-3")) assertEquals(gift, Promo.find(typed, codes), typed)
        assertNull(Promo.find("VULKAN-4", codes))
        assertNull(Promo.find("  - ", codes))
    }

    @Test
    fun aCodeGivesItsRewardsOnce() {
        val platform = SilentPlatform()
        val game = Game(platform)
        game.promoCodes = listOf(gift, admin)
        val points = game.profile.freePoints
        assertEquals(PromoResult.DONE, game.redeem("vulkan 3"))
        assertEquals(points + 5, game.profile.freePoints)
        assertTrue(game.profile.vip(platform.day + 2) && !game.profile.vip(platform.day + 3), "three days of VIP")
        assertEquals(PromoResult.USED, game.redeem("VULKAN-3"))
        assertEquals(points + 5, game.profile.freePoints)
        assertEquals(PromoResult.UNKNOWN, game.redeem("NOPE"))

        // Points from codes stay when progress is reset.
        game.profile.resetProgress()
        assertEquals(5, game.profile.freePoints)

        val old = PromoCode(Promo.hash("OLD"), listOf(Reward(RewardKind.POINTS, 1)), until = platform.day - 1)
        game.promoCodes = listOf(old)
        assertEquals(PromoResult.EXPIRED, game.redeem("old"))
    }

    @Test
    fun theAdminCodeOpensEverythingUntilSwitchedOff() {
        val platform = SilentPlatform()
        val game = Game(platform)
        val p = game.profile
        game.promoCodes = listOf(gift, admin)
        assertFalse(p.unlocked(9) || p.vip(platform.day) || p.adminKnown)

        assertEquals(PromoResult.ADMIN, game.redeem("test-admin"))
        assertTrue(p.admin && p.adminKnown)
        assertEquals(Levels.all.size, p.progress)
        assertTrue(Levels.all.all { p.unlocked(it.id) && p.hardOpen(it.id) }, "every level, on hard too")
        assertTrue(p.dailyUnlocked && p.weeklyUnlocked && p.endlessUnlocked)
        assertTrue(p.vip(platform.day) && p.vipForever)
        assertTrue(Product.entries.all { p.owned(it) })
        assertTrue(CommanderType.entries.all { p.commanderOwned(it) && p.rank(it) == Ranks.MAX })
        assertTrue(Camo.entries.all { p.camoOpen(it) })
        assertTrue(p.passPremium(platform.day))
        assertTrue(Codex.keys.all { p.known(it) })
        assertTrue(p.freePoints >= Promo.ADMIN_POINTS)
        for (perk in Perk.entries) repeat(perk.maxRank) { assertTrue(p.buyPerk(perk)) }
        assertEquals(TowerType.entries.toList(), Rules(progress = p.progress).towers, "every squad in battle")

        // Switched off, the game is the player's own again, and the upgrades the admin's points paid for go.
        game.setAdmin(false)
        assertFalse(p.admin || p.unlocked(9) || p.vip(platform.day) || p.owned(Product.GROM))
        assertEquals(1, p.rank(CommanderType.SERGEANT))
        assertEquals(0, p.spent)
        game.setAdmin(true)
        assertTrue(p.admin)
        // The code works again on its own too, unlike a gift.
        game.setAdmin(false)
        assertEquals(PromoResult.ADMIN, game.redeem("TEST ADMIN"))
        assertTrue(p.admin)
    }

    @Test
    fun aPurchaseInAdminModeIsKeptAfterIt() {
        val platform = SilentPlatform()
        val game = Game(platform)
        game.promoCodes = listOf(admin)
        game.redeem("TEST-ADMIN")
        game.profile.grant(Product.SKIN_NIGHT)
        game.setAdmin(false)
        assertTrue(game.profile.owned(Product.SKIN_NIGHT))
        assertFalse(game.profile.owned(Product.SKIN_PARADE))
    }
}
