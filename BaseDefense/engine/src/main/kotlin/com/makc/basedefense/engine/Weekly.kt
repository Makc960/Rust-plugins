package com.makc.basedefense.engine

/**
 * The weekly operation: one battle for the whole week, the same for everybody, harder
 * than a day's, with medals for how much of the base is left.
 */
object Weekly {
    const val WAVES = 15
    const val MID_BOSS = 7

    /** The campaign level whose stars open it. */
    const val UNLOCK = 4

    const val BRONZE = 1
    const val SILVER = 2
    const val GOLD = 3

    /** Upgrade points each medal brings the first time it is won in a week. */
    val POINTS = intArrayOf(0, 2, 1, 1)

    /** Weeks run Monday to Sunday; day 0, 1 January 1970, was a Thursday. */
    fun week(day: Int): Int = Math.floorDiv(day + 3, 7)

    fun firstDay(week: Int): Int = week * 7 - 3

    /** A won battle's medal by the lives left: half for silver, nine tenths for gold. */
    fun medal(lives: Int, maxLives: Int): Int {
        val share = lives / maxLives.toFloat()
        return when {
            share >= 0.9f -> GOLD
            share >= 0.5f -> SILVER
            else -> BRONZE
        }
    }

    private val NAMES = listOf(
        L("Гроза", "Thunder"), L("Вихрь", "Whirlwind"), L("Кремень", "Flint"), L("Стрела", "Arrow"),
        L("Полярная звезда", "North Star"), L("Сокол", "Falcon"), L("Тайфун", "Typhoon"), L("Эхо", "Echo"),
        L("Гранит", "Granite"), L("Метель", "Blizzard"), L("Сапсан", "Peregrine"), L("Зенит", "Zenith"),
    )

    /** The operation's code name for [week]. */
    fun name(week: Int): L = NAMES[Math.floorMod(week, NAMES.size)]

    val names: List<L> get() = NAMES
}
