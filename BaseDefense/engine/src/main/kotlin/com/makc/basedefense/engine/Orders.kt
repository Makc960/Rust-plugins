package com.makc.basedefense.engine

import kotlin.random.Random

/** What a daily order asks for, counted over the day's battles; [from] is the campaign level it waits for. */
enum class OrderKind(val text: L, val goals: IntArray, val from: Int = 1) {
    KILLS(L("Уничтожь %d врагов", "Destroy %d enemies"), intArrayOf(150, 250, 400)),
    BANDITS(L("Уничтожь %d бандитов", "Destroy %d bandits"), intArrayOf(60, 100)),
    SWARM(L("Уничтожь %d мутантов", "Destroy %d mutants"), intArrayOf(80, 150), 2),
    BRUTES(L("Уничтожь %d громил", "Destroy %d brutes"), intArrayOf(10, 20), 3),
    DRONES(L("Сбей %d дронов", "Shoot down %d drones"), intArrayOf(20, 40), 4),
    WINS(L("Победи в %d боях кампании", "Win %d campaign battles"), intArrayOf(1, 2, 3)),
    STARS(L("Пройди любой уровень на 3 звезды", "Win any level with 3 stars"), intArrayOf(1)),
    FLAWLESS(L("Победи, не пропустив ни одного врага", "Win without letting anyone through"), intArrayOf(1)),
    BUILD(L("Поставь %d отрядов", "Deploy %d squads"), intArrayOf(15, 25, 40)),
    AIRSTRIKES(L("Вызови %d авиаударов", "Call %d airstrikes"), intArrayOf(3, 5), Ability.AIRSTRIKE.unlock),
    DAILY(L("Пройди задание дня", "Win the daily challenge"), intArrayOf(1), 3),
    BOSSES(L("Победи %d боссов", "Defeat %d bosses"), intArrayOf(1, 2), EnemyType.APC.campaign),
    BRANCHES(L("Выбери %d специализаций", "Pick %d specialisations"), intArrayOf(2, 3), BRANCH_UNLOCK),
    ;

    /** How much of this order one battle did. */
    fun count(t: Tally): Int = when (this) {
        KILLS -> t.kills
        BANDITS -> t.killsOf[EnemyType.BANDIT.ordinal]
        SWARM -> t.killsOf[EnemyType.SWARM.ordinal]
        BRUTES -> t.killsOf[EnemyType.BRUTE.ordinal]
        DRONES -> t.killsOf[EnemyType.DRONE.ordinal]
        WINS -> if (t.campaignWin) 1 else 0
        STARS -> if (t.campaignWin && t.stars == 3) 1 else 0
        FLAWLESS -> if (t.won && t.flawless) 1 else 0
        BUILD -> t.built
        AIRSTRIKES -> t.airstrikes
        DAILY -> if (t.dailyWin) 1 else 0
        BOSSES -> t.bosses
        BRANCHES -> t.branches
    }
}

/** What one finished battle did, as the orders count it. */
class Tally(
    val kills: Int,
    val killsOf: IntArray,
    val won: Boolean,
    val campaignWin: Boolean,
    val stars: Int,
    val flawless: Boolean,
    val built: Int,
    val branches: Int,
    val airstrikes: Int,
    val bosses: Int,
    val dailyWin: Boolean,
) {
    companion object {
        fun of(w: World, won: Boolean, mode: Mode, stars: Int) = Tally(
            w.kills, w.killsOf.copyOf(), won, won && mode == Mode.CAMPAIGN, stars, w.leaked == 0, w.built,
            Integer.bitCount(w.branchMask), w.airstrikes, w.bossKills, won && mode == Mode.DAILY,
        )
    }
}

class Order(val kind: OrderKind, val goal: Int, val reward: Reward) {
    val text: String get() = kind.text.f(goal)
}

/**
 * Three orders a day from headquarters, the same all day and different the next; each brings
 * its reward when it is taken, and all three an upgrade point besides.
 */
object Orders {
    const val COUNT = 3
    const val BONUS_POINTS = 1

    private val REWARDS = listOf(Reward(RewardKind.SEASON, 150), Reward(RewardKind.SERVICE, 250), Reward(RewardKind.SEASON, 250))

    /** The orders of [day] for a player who had reached campaign level [progress] when it began. */
    fun of(day: Int, progress: Int): List<Order> {
        val rnd = Random(day * 7919L + 17L)
        val pool = OrderKind.entries.filter { it.from <= progress }.shuffled(rnd)
        return pool.take(COUNT).mapIndexed { i, k -> Order(k, k.goals[rnd.nextInt(k.goals.size)], REWARDS[i]) }
    }
}

/** Supplies for coming back: a reward for each day the game is opened, a week round, the seventh the best. */
object Supply {
    const val DAYS = 7

    val REWARDS: List<List<Reward>> = listOf(
        listOf(Reward(RewardKind.SEASON, 100)),
        listOf(Reward(RewardKind.SERVICE, 200)),
        listOf(Reward(RewardKind.POINTS, 1)),
        listOf(Reward(RewardKind.SEASON, 200)),
        listOf(Reward(RewardKind.SERVICE, 400)),
        listOf(Reward(RewardKind.POINTS, 1)),
        listOf(Reward(RewardKind.POINTS, 2), Reward(RewardKind.VIP_DAYS, 1)),
    )
}
