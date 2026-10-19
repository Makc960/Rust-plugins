package com.makc.basedefense.engine

import kotlin.math.max

/** The player's settings and progress, kept in the phone's preferences. */
class Profile(private val p: Platform) {
    var language: Language
        get() = when (p.getInt("lang", -1)) {
            Language.RU.ordinal -> Language.RU
            Language.EN.ordinal -> Language.EN
            else -> if (p.systemLanguage().lowercase() in RUSSIAN_READERS) Language.RU else Language.EN
        }
        set(v) = p.putInt("lang", v.ordinal)

    /** Effects volume, 0 to 100. The first version only had sound on or off. */
    var sound: Int
        get() = p.getInt("sfx_volume", if (p.getInt("sound", 1) == 0) 0 else 80)
        set(v) = p.putInt("sfx_volume", v.coerceIn(0, 100))

    var music: Int
        get() = p.getInt("music_volume", 60)
        set(v) = p.putInt("music_volume", v.coerceIn(0, 100))

    var vibration: Boolean
        get() = p.getInt("vibration", 1) != 0
        set(v) = p.putInt("vibration", if (v) 1 else 0)

    /** Fewer particles for weak phones. */
    var lite: Boolean
        get() = p.getInt("lite", 0) != 0
        set(v) = p.putInt("lite", if (v) 1 else 0)

    var damageNumbers: Boolean
        get() = p.getInt("damage_numbers", 0) != 0
        set(v) = p.putInt("damage_numbers", if (v) 1 else 0)

    /** Cards about new enemies and towers. */
    var tips: Boolean
        get() = p.getInt("tips", 1) != 0
        set(v) = p.putInt("tips", if (v) 1 else 0)

    /** Notifications about the daily challenge and the like, where the phone allows them. */
    var reminders: Boolean
        get() = p.getInt("reminders", 1) != 0
        set(v) = p.putInt("reminders", if (v) 1 else 0)

    // --- campaign -------------------------------------------------------------------------

    fun stars(id: Int): Int = p.getInt("stars_$id", 0)

    fun hardWon(id: Int): Boolean = p.getInt("hard_$id", 0) != 0

    fun medal(id: Int): Boolean = p.getInt("chal_$id", 0) != 0

    fun unlocked(id: Int): Boolean = id <= 1 || stars(id - 1) > 0

    /** The highest campaign level the player may play. */
    val progress: Int
        get() {
            var n = 1
            while (n < Levels.all.size && stars(n) > 0) n++
            return n
        }

    val totalStars: Int get() = Levels.all.sumOf { stars(it.id) }

    fun worldStars(world: Int): Int = Levels.all.filter { it.world == world }.sumOf { stars(it.id) }

    val hardWins: Int get() = Levels.all.count { hardWon(it.id) }

    val medals: Int get() = Levels.all.count { medal(it.id) }

    /** Records a campaign win; returns true when it improved anything. */
    fun recordWin(id: Int, stars: Int, difficulty: Difficulty, challenge: Boolean): Boolean {
        var better = false
        if (stars > stars(id)) {
            p.putInt("stars_$id", stars)
            better = true
        }
        if (difficulty == Difficulty.HARD && !hardWon(id)) {
            p.putInt("hard_$id", 1)
            better = true
        }
        if (challenge && !medal(id)) {
            p.putInt("chal_$id", 1)
            better = true
        }
        return better
    }

    // --- perks ----------------------------------------------------------------------------

    fun perk(perk: Perk): Int = p.getInt("perk_${perk.name}", 0).coerceIn(0, perk.maxRank)

    val perks: Perks get() = Perks.decode(Perk.entries.joinToString(".") { perk(it).toString() })

    /** Stars, hard wins, medals and daily wins all count, VIP's gifts and points bought in the starter pack. */
    val points: Int get() = totalStars + hardWins + medals + stat(Stat.DAILY_WINS) + stat(Stat.VIP_POINTS) + boughtPoints

    /** Upgrade points from purchases; resetting progress keeps them. */
    val boughtPoints: Int get() = p.getInt("bought_points", 0)

    val spent: Int get() = Perk.entries.sumOf { perk(it) * it.cost }

    val freePoints: Int get() = points - spent

    fun buyPerk(perk: Perk): Boolean {
        val rank = perk(perk)
        if (rank >= perk.maxRank || freePoints < perk.cost) return false
        p.putInt("perk_${perk.name}", rank + 1)
        return true
    }

    fun resetPerks() {
        for (perk in Perk.entries) p.putInt("perk_${perk.name}", 0)
    }

    // --- stats and achievements -----------------------------------------------------------

    fun stat(s: Stat): Int = p.getInt("stat_${s.name}", 0)

    fun addStat(s: Stat, n: Int) {
        if (n != 0) p.putInt("stat_${s.name}", stat(s) + n)
    }

    fun maxStat(s: Stat, v: Int) {
        if (v > stat(s)) p.putInt("stat_${s.name}", v)
    }

    fun orStat(s: Stat, bits: Int) {
        val now = stat(s)
        if (now or bits != now) p.putInt("stat_${s.name}", now or bits)
    }

    fun achieved(a: Achievement): Boolean = p.getInt(a.key, 0) != 0

    /** Marks and returns the achievements that are done now but were not before. */
    fun checkAchievements(): List<Achievement> {
        val fresh = Achievement.entries.filter { !achieved(it) && it.progress(this) >= it.goal }
        for (a in fresh) {
            p.putInt(a.key, 1)
            p.achievement(a.name.lowercase())
        }
        return fresh
    }

    // --- codex and tips -------------------------------------------------------------------

    fun seen(key: String): Boolean = p.getInt("seen_$key", 0) != 0

    fun markSeen(key: String) {
        if (!seen(key)) p.putInt("seen_$key", 1)
    }

    // --- endless and daily ----------------------------------------------------------------

    var bestEndless: Int
        get() = p.getInt("best_endless", 0)
        set(v) = p.putInt("best_endless", max(v, bestEndless))

    val endlessUnlocked: Boolean get() = stars(3) > 0

    val dailyUnlocked: Boolean get() = stars(2) > 0

    /** Waves held in today's daily challenge, or 0. */
    fun dailyBest(day: Int): Int = if (p.getInt("daily_day", -1) == day) p.getInt("daily_best", 0) else 0

    fun dailyWon(day: Int): Boolean = p.getInt("daily_won", -1) == day

    /** Records a daily attempt; a win extends the streak once a day. */
    fun recordDaily(day: Int, waves: Int, won: Boolean, vip: Boolean = false) {
        if (waves > dailyBest(day)) {
            p.putInt("daily_day", day)
            p.putInt("daily_best", waves)
        }
        if (won && !dailyWon(day)) {
            val last = p.getInt("daily_won", -10)
            val streak = if (last == day - 1) p.getInt("daily_streak", 0) + 1 else 1
            p.putInt("daily_won", day)
            p.putInt("daily_streak", streak)
            maxStat(Stat.DAILY_STREAK, streak)
            addStat(Stat.DAILY_WINS, 1)
            if (vip) addStat(Stat.VIP_POINTS, Vip.DAILY_POINTS - 1)
        }
    }

    /** Current streak of daily wins, still alive if yesterday was won. */
    fun dailyStreak(today: Int): Int {
        val last = p.getInt("daily_won", -10)
        return if (last >= today - 1) p.getInt("daily_streak", 0) else 0
    }

    // --- VIP ------------------------------------------------------------------------------

    /** The last day VIP is paid for, in days since 1970; 0 when never. Resetting progress keeps it. */
    val vipUntil: Int get() = p.getInt("vip_until", 0)

    fun vip(today: Int): Boolean = today <= vipUntil

    /** VIP is paid up to [until]; a paid period is never cut short. */
    fun grantVip(until: Int) {
        if (until > vipUntil) p.putInt("vip_until", until)
    }

    val vipForever: Boolean get() = vipUntil >= Vip.FOREVER

    // --- purchases and commanders ---------------------------------------------------------

    /** Bought once and kept, even when progress is reset. */
    fun owned(product: Product): Boolean = p.getInt("own_${product.id}", 0) != 0

    /** Hands over what [product] gives, once; buying the subscription goes through [grantVip] instead. */
    fun grant(product: Product) {
        if (product.subscription || owned(product)) return
        p.putInt("own_${product.id}", 1)
        when (product) {
            Product.VIP_FOREVER -> grantVip(Vip.FOREVER)
            Product.STARTER -> {
                grant(Product.ENGINEER)
                p.putInt("bought_points", boughtPoints + Product.STARTER_POINTS)
            }
            else -> Unit
        }
    }

    /** Free from the start, earned with stars, bought, or lent by VIP while it lasts. */
    fun commanderOpen(c: CommanderType): Boolean = commanderOwned(c) || vip(p.today())

    /** Open for good: free, earned or bought. */
    fun commanderOwned(c: CommanderType): Boolean = totalStars >= c.stars || (c.product != null && owned(c.product))

    /** VIP's present for the day; true when one was given just now. */
    fun vipGift(today: Int): Boolean {
        if (!vip(today) || p.getInt("vip_gift_day", -1) == today) return false
        p.putInt("vip_gift_day", today)
        addStat(Stat.VIP_POINTS, Vip.DAILY_GIFT)
        return true
    }

    // --- pop-ups --------------------------------------------------------------------------

    /** The day the VIP offer last popped up. */
    var promoDay: Int
        get() = p.getInt("promo_day", -1000)
        set(v) = p.putInt("promo_day", v)

    /** The last paid day the "VIP has ended" reminder was shown for. */
    var endedShown: Int
        get() = p.getInt("vip_ended_shown", 0)
        set(v) = p.putInt("vip_ended_shown", v)

    /** The tutorial was offered once, and whether it was finished. */
    var tutorialOffered: Boolean
        get() = p.getInt("tutorial_offered", 0) != 0
        set(v) = p.putInt("tutorial_offered", if (v) 1 else 0)

    /** The game asked once whether it may send reminders. */
    var remindersAsked: Boolean
        get() = p.getInt("reminders_asked", 0) != 0
        set(v) = p.putInt("reminders_asked", if (v) 1 else 0)

    /** The wins counted when the store's rating window was last opened; 0 is never. */
    var reviewAskedAt: Int
        get() = p.getInt("review_asked_at", 0)
        set(v) = p.putInt("review_asked_at", v)

    var tutorialDone: Boolean
        get() = p.getInt("tutorial_done", 0) != 0
        set(v) = p.putInt("tutorial_done", if (v) 1 else 0)

    /** The commander the player picked, whether open or not. */
    var commander: CommanderType
        get() = p.getString("commander")?.let { name -> CommanderType.entries.firstOrNull { it.name == name } } ?: CommanderType.SERGEANT
        set(v) = p.putString("commander", v.name)

    /** Who goes into battle: the one picked, or the sergeant while the pick is closed. */
    val battleCommander: CommanderType get() = commander.takeIf { commanderOpen(it) } ?: CommanderType.SERGEANT

    /** Service [c] earned in the battles he led; it decides his rank. */
    fun service(c: CommanderType): Int = p.getInt("service_${c.name}", 0)

    fun rank(c: CommanderType): Int = Ranks.of(service(c))

    /** Adds [n] to [c]'s service; gives his new rank when it went up, else 0. */
    fun addService(c: CommanderType, n: Int): Int {
        val before = rank(c)
        p.putInt("service_${c.name}", service(c) + n)
        val after = rank(c)
        if (after > before) maxStat(Stat.BEST_RANK, after)
        return if (after > before) after else 0
    }

    // --- the game in progress -------------------------------------------------------------

    var save: String?
        get() = p.getString("save")
        set(v) = p.putString("save", v)

    /** Forgets progress, perks, stats and achievements; settings stay. */
    fun resetProgress() {
        for (l in Levels.all) {
            p.putInt("stars_${l.id}", 0)
            p.putInt("hard_${l.id}", 0)
            p.putInt("chal_${l.id}", 0)
        }
        resetPerks()
        for (s in Stat.entries) p.putInt("stat_${s.name}", 0)
        for (a in Achievement.entries) p.putInt(a.key, 0)
        for (key in Codex.keys) p.putInt("seen_$key", 0)
        for (key in Tips.keys) p.putInt("seen_$key", 0)
        p.putInt("best_endless", 0)
        p.putInt("daily_day", -1)
        p.putInt("daily_best", 0)
        p.putInt("daily_won", -10)
        p.putInt("daily_streak", 0)
        for (c in CommanderType.entries) p.putInt("service_${c.name}", 0)
        p.putString("save", null)
    }

    private companion object {
        val RUSSIAN_READERS = setOf("ru", "be", "kk", "ky")
    }
}

/** Everything the codex can show, by key. */
object Codex {
    val keys: List<String>
        get() = EnemyType.entries.map { it.key } + TowerType.entries.map { it.key } +
            Branch.entries.map { it.key } + Ability.entries.map { it.key }
}

/** One-time hints shown during play. */
object Tips {
    const val ABILITIES = "tip:abilities"
    const val BRANCHES = "tip:branches"
    const val OBSTACLES = "tip:obstacles"
    const val WEATHER = "tip:weather"
    const val WINTER = "tip:winter"
    const val ZOOM = "tip:zoom"
    const val COMMANDER = "tip:commander"
    val keys = listOf(ABILITIES, BRANCHES, OBSTACLES, WEATHER, WINTER, ZOOM, COMMANDER)
}
