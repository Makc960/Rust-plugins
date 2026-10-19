package com.makc.basedefense.engine

import java.time.LocalDate
import kotlin.math.min

/**
 * What a tier of the season pass, an order or a day's supplies gives: upgrade points, the battle
 * commander's service, days of VIP, a camouflage or season points.
 */
enum class RewardKind { POINTS, SERVICE, VIP_DAYS, CAMO, SEASON }

class Reward(val kind: RewardKind, val amount: Int = 0) {
    /** The urban camouflage; a player who has it already gets points instead. */
    val camo: Camo? get() = if (kind == RewardKind.CAMO) Camo.URBAN else null
}

/**
 * The season pass: a season is a calendar month. Battles bring season points, every
 * [XP_PER_TIER] of them open a tier, and each tier has a free reward on even tiers and a
 * premium one on every tier for those who bought the pass ([Product.PASS]) this month.
 */
object Season {
    const val TIERS = 30
    const val XP_PER_TIER = 250

    /** VIP earns half as many again. */
    const val VIP_XP = 1.5f

    /** Points a player who owns the urban set gets for the last premium tier instead. */
    const val CAMO_POINTS = 5

    /** Months since January 1970: the season [day] falls in. */
    fun of(day: Int): Int {
        val d = LocalDate.ofEpochDay(day.toLong())
        return (d.year - 1970) * 12 + d.monthValue - 1
    }

    fun firstDay(season: Int): Int = LocalDate.of(1970 + season / 12, season % 12 + 1, 1).toEpochDay().toInt()

    fun daysLeft(day: Int): Int = firstDay(of(day) + 1) - day

    /** The season's name: its month. */
    fun name(season: Int): L = MONTHS[season % 12]

    /** Season points a finished battle brings; [score] is stars, a medal or waves held. */
    fun xp(mode: Mode, won: Boolean, score: Int): Int = when (mode) {
        Mode.CAMPAIGN -> if (won) 60 + 30 * score else 20
        Mode.DAILY -> if (won) 150 else 30
        Mode.WEEKLY -> if (won) 50 + 100 * score else 40
        Mode.ENDLESS -> min(300, 10 * score)
        Mode.TUTORIAL -> 0
    }

    /** The free reward of [tier] (1 to [TIERS]): every second tier. */
    fun free(tier: Int): Reward? = if (tier % 2 != 0) null else FREE[tier / 2 - 1]

    fun premium(tier: Int): Reward = PREMIUM[tier - 1]

    private fun points(n: Int) = Reward(RewardKind.POINTS, n)
    private fun service(n: Int) = Reward(RewardKind.SERVICE, n)
    private fun vip(days: Int) = Reward(RewardKind.VIP_DAYS, days)

    private val FREE = listOf(
        points(1), service(200), points(1), service(200), points(2), service(250), points(1), service(250),
        points(1), points(2), service(300), points(1), service(300), points(1), points(3),
    )

    private val PREMIUM = listOf(
        service(300), points(1), service(300), points(1), vip(2), points(1), service(400), points(1), service(400), points(2),
        service(400), points(1), service(400), points(1), vip(3), points(2), service(500), points(1), service(500), points(2),
        service(500), points(1), service(500), points(2), vip(5), points(2), service(600), points(2), service(600), Reward(RewardKind.CAMO),
    )

    private val MONTHS = listOf(
        L("Январь", "January"), L("Февраль", "February"), L("Март", "March"), L("Апрель", "April"),
        L("Май", "May"), L("Июнь", "June"), L("Июль", "July"), L("Август", "August"),
        L("Сентябрь", "September"), L("Октябрь", "October"), L("Ноябрь", "November"), L("Декабрь", "December"),
    )

    val months: List<L> get() = MONTHS
}
