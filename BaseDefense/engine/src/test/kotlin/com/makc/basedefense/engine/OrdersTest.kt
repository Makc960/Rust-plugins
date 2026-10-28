package com.makc.basedefense.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Daily orders from headquarters and the week of supplies for coming back. */
class OrdersTest {
    private fun tally(
        kills: Int = 0,
        bandits: Int = 0,
        won: Boolean = false,
        campaign: Boolean = true,
        stars: Int = 0,
        built: Int = 0,
    ): Tally {
        val of = IntArray(EnemyType.entries.size)
        of[EnemyType.BANDIT.ordinal] = bandits
        return Tally(kills, of, won, won && campaign, stars, flawless = false, built, 0, 0, 0, dailyWin = won && !campaign)
    }

    /** A tally that carries out any order at once. */
    private val everything: Tally
        get() = Tally(
            10_000, IntArray(EnemyType.entries.size) { 10_000 }, true, true, 3, true, 1000, 10, 100, 10, true,
        )

    @Test
    fun threeOrdersADayTheSameAllDay() {
        val day = 20_000
        val a = Orders.of(day, 32)
        assertEquals(Orders.COUNT, a.size)
        assertEquals(Orders.COUNT, a.map { it.kind }.distinct().size, "three different orders")
        assertEquals(a.map { it.text }, Orders.of(day, 32).map { it.text }, "the same all day")
        assertTrue((day until day + 7).map { Orders.of(it, 32).map { o -> o.kind } }.distinct().size > 1, "and others on other days")
        // A new player is not sent after bosses or specialisations he has not met.
        for (d in day until day + 60) {
            for (o in Orders.of(d, 1)) assertTrue(o.kind.from <= 1, "${o.kind} on day $d")
        }
        for (o in a) assertTrue(o.goal in o.kind.goals.toList())
    }

    @Test
    fun ordersCountBattlesAndPayOnce() {
        val platform = SilentPlatform()
        val p = Profile(platform)
        val day = platform.day
        val orders = p.orders(day)
        // The orders a player gets stay put for the day, even when he gets further.
        p.recordWin(1, 3, Difficulty.NORMAL, false)
        assertEquals(orders.map { it.text }, p.orders(day).map { it.text })
        assertEquals(0, p.ordersToTake(day))
        assertFalse(p.takeOrder(day, 0), "nothing to take yet")

        // Three good battles carry out any orders (a win counts once a battle).
        val done = (1..3).flatMap { p.tally(day, everything) }
        assertEquals(Orders.COUNT, done.size)
        assertTrue(p.tally(day, everything).isEmpty(), "each only once")
        assertEquals(Orders.COUNT + 1, p.ordersToTake(day), "three rewards and the bonus")
        val points = p.freePoints
        val xp = p.seasonXp(day)
        for (i in 0 until Orders.COUNT) assertTrue(p.takeOrder(day, i))
        assertFalse(p.takeOrder(day, 0), "taken once")
        assertTrue(p.seasonXp(day) > xp, "season points came")
        assertTrue(p.takeOrder(day, Orders.COUNT))
        assertEquals(points + Orders.BONUS_POINTS, p.freePoints)
        assertEquals(0, p.ordersToTake(day))
        assertEquals(Orders.COUNT, p.stat(Stat.ORDERS_DONE))

        // The next day brings new orders, with nothing done.
        p.orders(day + 1)
        assertTrue((0 until Orders.COUNT).none { p.orderDone(day + 1, it) })
        assertFalse(p.bonusTaken(day + 1))
    }

    @Test
    fun progressAddsUpOverBattles() {
        val p = Profile(SilentPlatform())
        val day = 20_123
        val orders = p.orders(day)
        val i = orders.indexOfFirst { it.kind == OrderKind.KILLS || it.kind == OrderKind.BUILD || it.kind == OrderKind.BANDITS }
        if (i < 0) return
        val o = orders[i]
        val half = tally(kills = o.goal / 2, bandits = o.goal / 2, built = o.goal / 2)
        p.tally(day, half)
        assertEquals(o.goal / 2, p.orderCount(day, i))
        assertFalse(p.orderDone(day, i))
        p.tally(day, half)
        p.tally(day, half)
        assertEquals(o.goal, p.orderCount(day, i), "stops at the goal")
        assertTrue(p.orderDone(day, i))
    }

    @Test
    fun suppliesComeOnceADayAWeekRound() {
        val platform = SilentPlatform()
        val p = Profile(platform)
        var day = platform.day
        assertTrue(p.supplyReady(day))
        val first = assertNotNull(p.takeSupply(day))
        assertEquals(Supply.REWARDS[0], first)
        assertNull(p.takeSupply(day), "once a day")
        assertFalse(p.supplyReady(day))
        // A missed day loses nothing: the next one is simply the next in the week.
        day += 3
        assertEquals(1, p.supplyDay)
        for (k in 1 until Supply.DAYS) {
            assertEquals(Supply.REWARDS[k], p.takeSupply(day))
            day++
        }
        assertTrue(p.vip(day - 1), "the seventh day brings a day of VIP")
        assertEquals(0, p.supplyDay, "and the week starts again")
        assertTrue(p.freePoints >= 4)
    }

    @Test
    fun rewardsAreWrittenWithTheRightWordForTheirNumber() {
        val words = listOf(1, 2, 5, 11, 21, 22, 100, 112).map { ruPlural(it, "очко", "очка", "очков") }
        assertEquals(listOf("очко", "очка", "очков", "очков", "очко", "очка", "очков", "очков"), words)
        val g = Game(SilentPlatform())
        assertEquals("+1 очко улучшений", g.describe(Reward(RewardKind.POINTS, 1)))
        assertEquals("+100 очков сезона", g.describe(Reward(RewardKind.SEASON, 100)))
        assertEquals("VIP на 1 день", g.describe(Reward(RewardKind.VIP_DAYS, 1)))
    }

    @Test
    fun battlesCountKillsOfEachKindThroughASave() {
        val w = World(Levels.all[0], Rules())
        w.beginForTest()
        val b = w.spawnForTest(EnemyType.BANDIT, 2f)
        val r = w.spawnForTest(EnemyType.RUNNER, 2.5f)
        w.damage(b, 1e6f, null)
        w.damage(r, 1e6f, null)
        assertEquals(1, w.killsOf[EnemyType.BANDIT.ordinal])
        assertEquals(1, w.killsOf[EnemyType.RUNNER.ordinal])
        val copy = World(Levels.all[0], Rules())
        copy.restore(w.save())
        assertEquals(1, copy.killsOf[EnemyType.BANDIT.ordinal])
        assertEquals(w.save(), copy.save())
        val t = Tally.of(copy, won = true, mode = Mode.CAMPAIGN, stars = 3)
        assertEquals(1, OrderKind.BANDITS.count(t))
        assertEquals(1, OrderKind.STARS.count(t))
        assertEquals(0, OrderKind.DAILY.count(t))
    }
}
