package com.makc.basedefense.engine

import kotlin.math.max

/**
 * The boss rush: bosses and their escorts, wave after wave, on a map of the day. The faster
 * the last of them falls, the better the medal; the best time of each day is kept.
 */
object BossRush {
    const val WAVES = 8

    /** The campaign level whose stars open it: both first bosses have been met by then. */
    const val UNLOCK = 8

    /**
     * Seconds of battle a gold or a silver medal allows someone at campaign level [progress]:
     * silver about what waves coming on their own take, gold only for calling some early.
     * Every kind of boss past the first two adds a little; any win earns bronze.
     */
    fun goldTime(progress: Int): Int = GOLD_TIME + EXTRA_KIND * max(0, bosses(progress).size - 2)

    fun silverTime(progress: Int): Int = SILVER_TIME + EXTRA_KIND * max(0, bosses(progress).size - 2)

    private const val GOLD_TIME = 150
    private const val SILVER_TIME = 195
    private const val EXTRA_KIND = 10

    /** The medal a win in [seconds] earns, as [Weekly.BRONZE] to [Weekly.GOLD]. */
    fun medal(seconds: Int, progress: Int): Int = when {
        seconds <= goldTime(progress) -> Weekly.GOLD
        seconds <= silverTime(progress) -> Weekly.SILVER
        else -> Weekly.BRONZE
    }

    /** The bosses someone who has reached campaign level [progress] has met. */
    fun bosses(progress: Int): List<EnemyType> = EnemyType.entries.filter { it.boss && it.campaign <= progress }

    /** Minutes and seconds, for the clock and the records. */
    fun clock(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
}
