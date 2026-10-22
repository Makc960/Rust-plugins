package com.makc.basedefense.engine

/**
 * What the game reminds of while it is closed: a streak about to burn tonight, tomorrow's
 * daily challenge, and a call back after a few quiet days. Planned again every time the
 * game goes to the background, so only the latest plan counts.
 */
internal object Reminders {
    /** Minutes past midnight. */
    const val STREAK_AT = 20 * 60
    const val DAILY_AT = 18 * 60

    /** Days of silence before the call back. */
    const val QUIET_DAYS = 3

    fun plan(profile: Profile, today: Int, vip: Boolean): List<Reminder> {
        if (!profile.reminders) return emptyList()
        val out = ArrayList<Reminder>()
        val streak = profile.dailyStreak(today)
        if (profile.dailyUnlocked && streak >= 2 && !profile.dailyWon(today)) {
            out += Reminder(today, STREAK_AT, Tx.REMIND_STREAK.f(streak), Tx.REMIND_STREAK_TEXT.t)
        }
        val tomorrow = today + 1
        out += if (profile.dailyUnlocked) {
            val mods = Mod.forDay(tomorrow).joinToString(", ") { it.title.t }
            Reminder(tomorrow, DAILY_AT, Tx.REMIND_DAILY.t, (if (vip) Tx.REMIND_DAILY_VIP else Tx.REMIND_DAILY_TEXT).f(mods))
        } else {
            Reminder(tomorrow, DAILY_AT, Tx.REMIND_CAMPAIGN.t, Tx.REMIND_CAMPAIGN_TEXT.f(profile.progress))
        }
        out += Reminder(today + QUIET_DAYS, DAILY_AT, Tx.REMIND_BACK.t, Tx.REMIND_BACK_TEXT.t)
        return out
    }
}
