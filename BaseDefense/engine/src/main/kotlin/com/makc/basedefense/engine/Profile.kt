package com.makc.basedefense.engine

import kotlin.math.max
import kotlin.math.min

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

    fun unlocked(id: Int): Boolean = admin || id <= 1 || stars(id - 1) > 0

    /** Hard opens on a level once it is won. */
    fun hardOpen(id: Int): Boolean = admin || stars(id) > 0

    /** The highest campaign level the player may play. */
    val progress: Int
        get() {
            if (admin) return Levels.all.size
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

    /** Stars, hard wins, medals and daily wins all count, VIP's gifts, points bought in the starter pack or given by codes. */
    val points: Int
        get() = totalStars + hardWins + medals + stat(Stat.DAILY_WINS) + stat(Stat.VIP_POINTS) + stat(Stat.WEEKLY_POINTS) +
            stat(Stat.PASS_POINTS) + stat(Stat.AD_POINTS) + stat(Stat.ORDER_POINTS) + boughtPoints + giftPoints +
            (if (admin) Promo.ADMIN_POINTS else 0)

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

    /** The codex shows it: met, or everything is open to the admin. */
    fun known(key: String): Boolean = admin || seen(key)

    fun markSeen(key: String) {
        if (!seen(key)) p.putInt("seen_$key", 1)
    }

    // --- endless and daily ----------------------------------------------------------------

    var bestEndless: Int
        get() = p.getInt("best_endless", 0)
        set(v) = p.putInt("best_endless", max(v, bestEndless))

    val endlessUnlocked: Boolean get() = admin || stars(3) > 0

    val dailyUnlocked: Boolean get() = admin || stars(2) > 0

    val weeklyUnlocked: Boolean get() = admin || stars(Weekly.UNLOCK) > 0

    /** The best medal of [week]'s operation: 0 none, 1 bronze, 2 silver, 3 gold. */
    fun weeklyMedal(week: Int): Int = if (p.getInt("weekly_week", -1) == week) p.getInt("weekly_medal", 0) else 0

    /** Records a won operation; gives the upgrade points its new medals brought. */
    fun recordWeekly(week: Int, medal: Int): Int {
        val before = weeklyMedal(week)
        if (medal <= before) return 0
        val points = (before + 1..medal).sumOf { Weekly.POINTS[it] }
        p.putInt("weekly_week", week)
        p.putInt("weekly_medal", medal)
        addStat(Stat.WEEKLY_POINTS, points)
        if (medal == Weekly.GOLD) addStat(Stat.WEEKLY_GOLD, 1)
        return points
    }

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

    fun vip(today: Int): Boolean = admin || today <= vipUntil

    /** VIP is paid up to [until]; a paid period is never cut short. */
    fun grantVip(until: Int) {
        if (until > vipUntil) p.putInt("vip_until", until)
    }

    val vipForever: Boolean get() = admin || vipUntil >= Vip.FOREVER

    // --- purchases and commanders ---------------------------------------------------------

    /** Bought once and kept, even when progress is reset; the admin has it all. */
    fun owned(product: Product): Boolean = admin || bought(product)

    private fun bought(product: Product): Boolean = p.getInt("own_${product.id}", 0) != 0

    /** Hands over what [product] gives, once; buying the subscription goes through [grantVip] instead. */
    fun grant(product: Product) {
        if (product == Product.PASS) {
            p.putInt("pass_season", Season.of(p.today()))
            return
        }
        if (product.subscription || bought(product)) return
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

    /** Rewarded adverts watched on [today]. */
    fun adsWatched(today: Int): Int = if (p.getInt("ads_day", -1) == today) p.getInt("ads_today", 0) else 0

    fun countAd(today: Int) {
        val n = adsWatched(today) + 1
        p.putInt("ads_day", today)
        p.putInt("ads_today", n)
    }

    /** The upgrade point an advert gives once a day. */
    fun adPointTaken(today: Int): Boolean = p.getInt("ad_point_day", -1) == today

    fun takeAdPoint(today: Int) {
        if (adPointTaken(today)) return
        p.putInt("ad_point_day", today)
        addStat(Stat.AD_POINTS, Ads.DAILY_POINTS)
    }

    /** VIP's present for the day; true when one was given just now. */
    fun vipGift(today: Int): Boolean {
        if (!vip(today) || p.getInt("vip_gift_day", -1) == today) return false
        p.putInt("vip_gift_day", today)
        addStat(Stat.VIP_POINTS, Vip.DAILY_GIFT)
        return true
    }

    // --- camouflage -----------------------------------------------------------------------

    /** Every level of campaign world [w] (0 is the first) is won. */
    fun worldWon(w: Int): Boolean = (1..Levels.PER_WORLD).all { stars(w * Levels.PER_WORLD + it) > 0 }

    /** Levels of world [w] won so far. */
    fun worldWins(w: Int): Int = (1..Levels.PER_WORLD).count { stars(w * Levels.PER_WORLD + it) > 0 }

    /** Given by a reward, like the season pass's. */
    fun grantCamo(c: Camo) = p.putInt("own_${c.key}", 1)

    fun camoOpen(c: Camo): Boolean = when {
        c == Camo.FIELD || admin -> true
        c.world?.let { worldWon(it) } == true -> true
        c.product?.let { owned(it) } == true -> true
        c == Camo.PARADE && vip(p.today()) -> true
        else -> p.getInt("own_${c.key}", 0) != 0
    }

    /** A commander of the top rank wears his parade uniform whatever else he has. */
    fun outfitOpen(commander: CommanderType, c: Camo): Boolean = camoOpen(c) || (c == Camo.PARADE && rank(commander) >= Ranks.MAX)

    /** The squads' camouflage the player picked, whether open or not. */
    var camo: Camo
        get() = p.getString("camo")?.let { name -> Camo.entries.firstOrNull { it.name == name } } ?: Camo.FIELD
        set(v) = p.putString("camo", v.name)

    fun outfit(commander: CommanderType): Camo =
        p.getString("outfit_${commander.name}")?.let { name -> Camo.entries.firstOrNull { it.name == name } } ?: Camo.FIELD

    fun setOutfit(commander: CommanderType, c: Camo) = p.putString("outfit_${commander.name}", c.name)

    /** What shows on the map: the pick, or the field set while the pick is closed. */
    val battleCamo: Camo get() = camo.takeIf { camoOpen(it) } ?: Camo.FIELD

    fun battleOutfit(commander: CommanderType): Camo = outfit(commander).takeIf { outfitOpen(commander, it) } ?: Camo.FIELD

    // --- season pass ----------------------------------------------------------------------

    /** Season points earned this month. */
    fun seasonXp(today: Int): Int = if (p.getInt("pass_xp_season", -1) == Season.of(today)) p.getInt("pass_xp", 0) else 0

    fun seasonTier(today: Int): Int = min(Season.TIERS, seasonXp(today) / Season.XP_PER_TIER)

    /** Adds a battle's season points, half again with VIP; gives how many were added. */
    fun addSeasonXp(today: Int, xp: Int, vip: Boolean): Int {
        val add = if (vip) (xp * Season.VIP_XP).toInt() else xp
        p.putInt("pass_xp", seasonXp(today) + add)
        p.putInt("pass_xp_season", Season.of(today))
        return add
    }

    /** The premium track is bought for this month. */
    fun passPremium(today: Int): Boolean = admin || p.getInt("pass_season", -1) == Season.of(today)

    private fun claimKey(today: Int, premium: Boolean) = "pass_${if (premium) "paid" else "free"}_${Season.of(today)}"

    fun claimed(today: Int, tier: Int, premium: Boolean): Boolean = p.getInt(claimKey(today, premium), 0) and (1 shl (tier - 1)) != 0

    /** Whether [tier]'s reward on that track is there to take now. */
    fun claimable(today: Int, tier: Int, premium: Boolean): Boolean {
        val reward = if (premium) Season.premium(tier) else Season.free(tier)
        return reward != null && tier <= seasonTier(today) && !claimed(today, tier, premium) && (!premium || passPremium(today))
    }

    /** Hands over a reached tier's reward, once; false when it is not there to take. */
    fun claim(today: Int, tier: Int, premium: Boolean): Boolean {
        if (!claimable(today, tier, premium)) return false
        val reward = (if (premium) Season.premium(tier) else Season.free(tier)) ?: return false
        val key = claimKey(today, premium)
        p.putInt(key, p.getInt(key, 0) or (1 shl (tier - 1)))
        give(reward, today) { addStat(Stat.PASS_POINTS, it) }
        return true
    }

    /** Hands over [reward]; its upgrade points go where [addPoints] puts them. */
    private fun give(reward: Reward, today: Int, addPoints: (Int) -> Unit) {
        when (reward.kind) {
            RewardKind.POINTS -> addPoints(reward.amount)
            RewardKind.SERVICE -> addService(battleCommander, reward.amount)
            RewardKind.VIP_DAYS -> grantVip(max(vipUntil, today - 1) + reward.amount)
            RewardKind.CAMO -> if (camoOpen(Camo.URBAN)) addPoints(Season.CAMO_POINTS) else grantCamo(Camo.URBAN)
            RewardKind.SEASON -> addSeasonXp(today, reward.amount, vip = false)
        }
    }

    // --- daily orders and supplies --------------------------------------------------------

    /** Today's orders, chosen for how far the player had got when the day began. */
    fun orders(today: Int): List<Order> {
        if (p.getInt("orders_day", -1) != today) {
            p.putInt("orders_day", today)
            p.putInt("orders_progress", progress)
            for (i in 0 until Orders.COUNT) p.putInt("order_$i", 0)
            p.putInt("orders_taken", 0)
        }
        return Orders.of(today, p.getInt("orders_progress", progress))
    }

    /** How far order [i] of today has got. */
    fun orderCount(today: Int, i: Int): Int = if (p.getInt("orders_day", -1) == today) p.getInt("order_$i", 0) else 0

    fun orderDone(today: Int, i: Int): Boolean = orderCount(today, i) >= orders(today)[i].goal

    private fun taken(today: Int, bit: Int): Boolean = p.getInt("orders_day", -1) == today && p.getInt("orders_taken", 0) and (1 shl bit) != 0

    fun orderTaken(today: Int, i: Int): Boolean = taken(today, i)

    fun bonusTaken(today: Int): Boolean = taken(today, Orders.COUNT)

    /** Counts a finished battle towards today's orders; gives the orders it carried out. */
    fun tally(today: Int, t: Tally): List<Order> {
        val list = orders(today)
        val done = ArrayList<Order>()
        for ((i, o) in list.withIndex()) {
            val before = orderCount(today, i)
            if (before >= o.goal) continue
            val now = minOf(o.goal, before + o.kind.count(t))
            p.putInt("order_$i", now)
            if (now >= o.goal) {
                done += o
                addStat(Stat.ORDERS_DONE, 1)
            }
        }
        return done
    }

    /** Takes the reward of order [i], or with [i] = [Orders.COUNT] the bonus for all three; false when it is not there. */
    fun takeOrder(today: Int, i: Int): Boolean {
        val list = orders(today)
        val ready = if (i == Orders.COUNT) list.indices.all { orderDone(today, it) } else i in list.indices && orderDone(today, i)
        if (!ready || taken(today, i)) return false
        p.putInt("orders_taken", p.getInt("orders_taken", 0) or (1 shl i))
        if (i == Orders.COUNT) addStat(Stat.ORDER_POINTS, Orders.BONUS_POINTS) else give(list[i].reward, today) { addStat(Stat.ORDER_POINTS, it) }
        return true
    }

    /** Rewards of today's orders done and not yet taken, the bonus included. */
    fun ordersToTake(today: Int): Int {
        val list = orders(today)
        val each = list.indices.count { orderDone(today, it) && !taken(today, it) }
        val bonus = if (list.indices.all { orderDone(today, it) } && !bonusTaken(today)) 1 else 0
        return each + bonus
    }

    /** The day supplies were last offered in a window. */
    var supplyOffered: Int
        get() = p.getInt("supply_offered", -1)
        set(v) = p.putInt("supply_offered", v)

    /** Which day of the week's supplies comes next, from 0. */
    val supplyDay: Int get() = p.getInt("supply_count", 0) % Supply.DAYS

    /** Today's supplies are there to take. */
    fun supplyReady(today: Int): Boolean = p.getInt("supply_last", -1) < today

    /** Takes today's supplies; gives what they were, or null when they were taken already. */
    fun takeSupply(today: Int): List<Reward>? {
        if (!supplyReady(today)) return null
        val rewards = Supply.REWARDS[supplyDay]
        p.putInt("supply_last", today)
        p.putInt("supply_count", p.getInt("supply_count", 0) + 1)
        for (r in rewards) give(r, today) { addStat(Stat.ORDER_POINTS, it) }
        return rewards
    }

    /** Rewards reached and not yet taken, on both tracks. */
    fun unclaimed(today: Int): Int = (1..Season.TIERS).sumOf { t ->
        (if (claimable(today, t, false)) 1 else 0) + (if (claimable(today, t, true)) 1 else 0)
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

    /** The backstory has been told; it is told once, to a new player. */
    var storySeen: Boolean
        get() = p.getInt("story_seen", 0) != 0
        set(v) = p.putInt("story_seen", if (v) 1 else 0)

    /** The last version whose news the player has been shown. */
    var newsSeen: String?
        get() = p.getString("news_seen")
        set(v) = p.putString("news_seen", v)

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

    fun rank(c: CommanderType): Int = if (admin) Ranks.MAX else Ranks.of(service(c))

    /** Adds [n] to [c]'s service; gives his new rank when it went up, else 0. */
    fun addService(c: CommanderType, n: Int): Int {
        val before = rank(c)
        p.putInt("service_${c.name}", service(c) + n)
        val after = rank(c)
        if (after > before) maxStat(Stat.BEST_RANK, after)
        return if (after > before) after else 0
    }

    // --- promo codes ----------------------------------------------------------------------

    /** Admin mode: every level, world, mode, squad, commander, rank, look, VIP and upgrade open. */
    var admin: Boolean
        get() = p.getInt("admin", 0) != 0
        set(v) {
            p.putInt("admin", if (v) 1 else 0)
            // Upgrades bought with the admin's points do not outlast them.
            if (!v && freePoints < 0) resetPerks()
        }

    /** The admin's code was entered on this phone, so admin mode can be switched back on. */
    val adminKnown: Boolean get() = p.getInt("admin_known", 0) != 0

    /** Upgrade points given by codes; resetting progress keeps them. */
    val giftPoints: Int get() = p.getInt("gift_points", 0)

    private fun codeKey(code: PromoCode) = "code_${code.hash.take(16)}"

    fun codeUsed(code: PromoCode): Boolean = p.getInt(codeKey(code), 0) != 0

    /** Hands over what [code] gives, once a code; the admin's may be entered again. */
    fun redeem(code: PromoCode, today: Int): PromoResult {
        if (today > code.until) return PromoResult.EXPIRED
        if (code.admin) {
            p.putInt("admin_known", 1)
            admin = true
            return PromoResult.ADMIN
        }
        if (codeUsed(code)) return PromoResult.USED
        p.putInt(codeKey(code), 1)
        for (r in code.rewards) give(r, today) { p.putInt("gift_points", giftPoints + it) }
        return PromoResult.DONE
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
        p.putInt("weekly_week", -1)
        p.putInt("weekly_medal", 0)
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
    const val VOLCANO = "tip:volcano"
    const val ZOOM = "tip:zoom"
    const val COMMANDER = "tip:commander"
    val keys = listOf(ABILITIES, BRANCHES, OBSTACLES, WEATHER, WINTER, VOLCANO, ZOOM, COMMANDER)
}
