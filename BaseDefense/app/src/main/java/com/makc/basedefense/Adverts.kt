package com.makc.basedefense

/**
 * Rewarded videos: from the Yandex Advertising Network in RuStore builds that have
 * an ad unit (src/rustore), none anywhere else (src/nostore).
 */
interface Adverts {
    /** This build plays them at all. */
    val enabled: Boolean get() = false

    /** One is loaded and can play right away. */
    val ready: Boolean get() = false

    /** Plays one; [done] hears true when the player watched it to the reward. */
    fun show(done: (Boolean) -> Unit) = done(false)
}
