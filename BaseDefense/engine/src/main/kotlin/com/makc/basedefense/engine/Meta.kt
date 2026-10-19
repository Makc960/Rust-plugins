package com.makc.basedefense.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Campaign level from which every tower gets its fourth-level specialisations. */
const val BRANCH_UNLOCK = 3

enum class Difficulty(val title: L, val hint: L, val hp: Float, val money: Float, val lives: Int) {
    EASY(L("Легко", "Easy"), L("Враги слабее, 30 жизней", "Weaker enemies, 30 lives"), 0.72f, 1.15f, 30),
    NORMAL(L("Нормально", "Normal"), L("Как задумано, 20 жизней", "As intended, 20 lives"), 1f, 1f, 20),
    HARD(L("Сложно", "Hard"), L("Враги крепче, денег меньше, 10 жизней", "Tougher enemies, less money, 10 lives"), 1.25f, 0.92f, 10),
}

enum class ChallengeKind { NO_SELL, MAX_TOWERS, NO_LEAKS, NO_ABILITIES, ONLY, LOW_BUDGET }

/** An extra rule for a level; winning with it on earns the level's medal. */
class Challenge(val kind: ChallengeKind, val value: Int = 0, val types: List<TowerType> = emptyList()) {
    val title: String
        get() = when (kind) {
            ChallengeKind.NO_SELL -> L("Без продажи отрядов", "No selling squads").t
            ChallengeKind.MAX_TOWERS -> L("Не больше %d отрядов", "At most %d squads").f(value)
            ChallengeKind.NO_LEAKS -> L("Ни один враг не дойдёт до базы", "Let no enemy reach the base").t
            ChallengeKind.NO_ABILITIES -> L("Без способностей", "No abilities").t
            ChallengeKind.ONLY -> {
                val names = types.joinToString(", ") { if (I18n.lang == Language.RU) it.short.t.lowercase() else it.short.t }
                L("Только: %s", "Only: %s").f(names)
            }
            ChallengeKind.LOW_BUDGET -> L("На 40% меньше денег на старте", "40% less starting money").t
        }

    companion object {
        val NO_SELL = Challenge(ChallengeKind.NO_SELL)
        val NO_LEAKS = Challenge(ChallengeKind.NO_LEAKS)
        val NO_ABILITIES = Challenge(ChallengeKind.NO_ABILITIES)
        val LOW_BUDGET = Challenge(ChallengeKind.LOW_BUDGET)

        fun maxTowers(n: Int) = Challenge(ChallengeKind.MAX_TOWERS, n)

        fun only(vararg types: TowerType) = Challenge(ChallengeKind.ONLY, types = types.toList())
    }
}

/** Permanent bonuses bought with stars, medals and wins. */
enum class Perk(val title: L, val hint: L, val maxRank: Int, val cost: Int) {
    START_MONEY(L("Запасы", "Supplies"), L("+30 монет на старте", "+30 starting coins"), 3, 2),
    LIVES(L("Укрепления", "Fortifications"), L("+3 жизни базе", "+3 base lives"), 3, 2),
    DAMAGE(L("Боеприпасы", "Ammunition"), L("+5% урона всем отрядам", "+5% damage for every squad"), 3, 3),
    RANGE(L("Оптика", "Optics"), L("+5% дальности отрядов", "+5% squad range"), 2, 3),
    DISCOUNT(L("Снабжение", "Logistics"), L("Отряды и улучшения на 5% дешевле", "Squads and upgrades cost 5% less"), 2, 3),
    COOLDOWN(L("Связь", "Radio"), L("Способности заряжаются на 12% быстрее", "Abilities recharge 12% faster"), 3, 2),
    REFUND(L("Утилизация", "Salvage"), L("+5% денег при продаже отряда", "+5% back when selling a squad"), 2, 2),
    BOUNTY(L("Трофеи", "Trophies"), L("+10% монет за врагов", "+10% coins per kill"), 2, 3),
}

class Perks(private val ranks: IntArray = IntArray(Perk.entries.size)) {
    operator fun get(p: Perk): Int = ranks.getOrElse(p.ordinal) { 0 }

    fun encode(): String = ranks.joinToString(".")

    companion object {
        val NONE = Perks()

        fun decode(s: String): Perks {
            val parts = s.split('.')
            return Perks(IntArray(Perk.entries.size) { i -> (parts.getOrNull(i)?.toIntOrNull() ?: 0).coerceIn(0, Perk.entries[i].maxRank) })
        }
    }
}

/** Twists for the daily challenge. */
enum class Mod(val title: L, val hint: L) {
    FAST(L("Спешка", "Rush"), L("Враги на 25% быстрее", "Enemies move 25% faster")),
    TOUGH(L("Закалка", "Hardened"), L("У врагов на 30% больше здоровья", "Enemies have 30% more health")),
    RICH(L("Казна", "Treasury"), L("В полтора раза больше денег на старте", "50% more starting money")),
    POOR(L("Нужда", "Scarcity"), L("На 30% меньше денег на старте", "30% less starting money")),
    NO_ABILITIES(L("Радиомолчание", "Radio silence"), L("Способности отключены", "Abilities are off")),
    FEW_TOWERS(L("Теснота", "Cramped"), L("Не больше 10 отрядов", "At most 10 squads")),
    CHEAP_UPGRADES(L("Мастерская", "Workshop"), L("Улучшения на 30% дешевле", "Upgrades cost 30% less")),
    BOSS_RUSH(L("Парад боссов", "Boss parade"), L("Босс каждые 5 волн", "A boss every 5 waves")),
    ;

    companion object {
        private val CONFLICTS = listOf(RICH to POOR, FAST to TOUGH, RICH to CHEAP_UPGRADES, NO_ABILITIES to FEW_TOWERS)

        /** Two mods that make sense together, the same for everybody on a given day. */
        fun forDay(day: Int): List<Mod> = pick(Random(day * 7349 + 11))

        /** The weekly operation's two, the same all week. */
        fun forWeek(week: Int): List<Mod> = pick(Random(week * 92_821 + 5))

        private fun pick(rng: Random): List<Mod> {
            val first = entries[rng.nextInt(entries.size)]
            val rest = entries.filter { it != first && (first to it) !in CONFLICTS && (it to first) !in CONFLICTS }
            return listOf(first, rest[rng.nextInt(rest.size)])
        }
    }
}

/** Counters kept across games, for achievements. */
enum class Stat {
    KILLS, BOSS_KILLS, GHOST_KILLS, BUILT, BRANCHES, TOWER_TYPES, AIRSTRIKES, OBSTACLES,
    WINS, FLAWLESS, MAX_MONEY, ENDLESS_BEST, DAILY_STREAK, DAILY_WINS,

    /** Extra upgrade points from daily wins with VIP. */
    VIP_POINTS,

    /** The highest rank any commander reached. */
    BEST_RANK,

    /** Upgrade points from weekly operations' medals, and gold medals won. */
    WEEKLY_POINTS,
    WEEKLY_GOLD,

    /** Upgrade points from season pass rewards. */
    PASS_POINTS,
}

/** What the VIP subscription gives; the rules and the comparison screen both read it here. */
object Vip {
    const val START_MONEY = 1.2f
    const val BOUNTY = 1.1f
    const val COOLDOWN = 0.8f
    const val MAX_SPEED = 4
    const val UNDO_TIME = 8f
    const val DAILY_POINTS = 2

    /** The commander starts a battle at this level (0 is the first), and gains experience this much faster. */
    const val COMMANDER_LEVEL = 1
    const val XP = 1.5f

    /** Once a battle, when the base falls, reserves bring this many lives. */
    const val RESERVE_LIVES = 5

    /** Upgrade points given on every day with VIP. */
    const val DAILY_GIFT = 1

    /** How long VIP lasts when a store says it is paid but not until when. */
    const val DAYS = 31

    /** The last day of VIP bought for good: far beyond any phone's calendar. */
    const val FOREVER = 2_000_000
}

enum class Achievement(val title: L, val hint: L, val goal: Int, val progress: (Profile) -> Int) {
    FIRST_WIN(L("Первая победа", "First victory"), L("Пройди любой уровень", "Win any level"), 1, { it.stat(Stat.WINS) }),
    GREEN_STARS(L("Хозяин долины", "Master of the valley"), L("Все звёзды Зелёной долины", "Every star in the Green Valley"), 24, { it.worldStars(0) }),
    DESERT_STARS(L("Пустынный лис", "Desert fox"), L("Все звёзды Пустыни", "Every star in the Desert"), 24, { it.worldStars(1) }),
    NORTH_STARS(L("Полярник", "Polar explorer"), L("Все звёзды Севера", "Every star in the North"), 24, { it.worldStars(2) }),
    ALL_STARS(L("Генерал", "General"), L("Все 72 звезды кампании", "All 72 campaign stars"), 72, { it.totalStars }),
    KILLS_1000(L("Тысяча", "A thousand down"), L("Уничтожь 1000 врагов", "Destroy 1000 enemies"), 1000, { it.stat(Stat.KILLS) }),
    KILLS_10000(L("Мясорубка", "Meat grinder"), L("Уничтожь 10 000 врагов", "Destroy 10,000 enemies"), 10000, { it.stat(Stat.KILLS) }),
    BOSSES(L("Охотник на боссов", "Boss hunter"), L("Победи 10 боссов", "Defeat 10 bosses"), 10, { it.stat(Stat.BOSS_KILLS) }),
    GHOSTS(L("Охотник за привидениями", "Ghostbuster"), L("Уничтожь 100 невидимок", "Destroy 100 ghosts"), 100, { it.stat(Stat.GHOST_KILLS) }),
    BUILDER(L("Строитель", "Builder"), L("Поставь 200 отрядов", "Deploy 200 squads"), 200, { it.stat(Stat.BUILT) }),
    ARSENAL(L("Арсенал", "Arsenal"), L("Поставь отряды всех 10 видов", "Deploy all 10 kinds of squad"), 10, { Integer.bitCount(it.stat(Stat.TOWER_TYPES)) }),
    SPECIALIST(L("Специалист", "Specialist"), L("Выбери специализацию отряда", "Pick a squad specialisation"), 1, { min(1, Integer.bitCount(it.stat(Stat.BRANCHES))) }),
    ALL_BRANCHES(L("Мастер на все руки", "Jack of all trades"), L("Попробуй все 20 специализаций", "Try all 20 specialisations"), 20, { Integer.bitCount(it.stat(Stat.BRANCHES)) }),
    AIRSTRIKES(L("Небесный гнев", "Wrath from above"), L("Вызови 30 авиаударов", "Call 30 airstrikes"), 30, { it.stat(Stat.AIRSTRIKES) }),
    HARD_WIN(L("Закалённый", "Hardened"), L("Пройди уровень на сложном", "Win a level on hard"), 1, { it.hardWins }),
    HARD_8(L("Железная воля", "Iron will"), L("Пройди 8 уровней на сложном", "Win 8 levels on hard"), 8, { it.hardWins }),
    CHALLENGES_5(L("Испытатель", "Challenger"), L("Получи 5 медалей испытаний", "Earn 5 challenge medals"), 5, { it.medals }),
    CHALLENGES_24(L("Без компромиссов", "No compromise"), L("Получи все 24 медали", "Earn all 24 medals"), 24, { it.medals }),
    FLAWLESS(L("Ни царапины", "Flawless"), L("Победи, не потеряв ни одной жизни", "Win without losing a single life"), 1, { it.stat(Stat.FLAWLESS) }),
    TYCOON(L("Богач", "Tycoon"), L("Накопи 2000 монет за один бой", "Hold 2000 coins at once"), 2000, { it.stat(Stat.MAX_MONEY) }),
    ENDLESS_20(L("Выживший", "Survivor"), L("Продержись 20 волн в бесконечном режиме", "Survive 20 endless waves"), 20, { it.stat(Stat.ENDLESS_BEST) }),
    ENDLESS_40(L("Несокрушимый", "Unbreakable"), L("Продержись 40 волн в бесконечном режиме", "Survive 40 endless waves"), 40, { it.stat(Stat.ENDLESS_BEST) }),
    DAILY_3(L("Привычка", "Habit"), L("Задание дня 3 дня подряд", "Win the daily 3 days in a row"), 3, { it.stat(Stat.DAILY_STREAK) }),
    DAILY_7(L("Неделя в строю", "A week on duty"), L("Задание дня 7 дней подряд", "Win the daily 7 days in a row"), 7, { it.stat(Stat.DAILY_STREAK) }),
    CLEARING(L("Расчистка", "Land clearing"), L("Убери 20 камней, деревьев или кактусов", "Clear 20 rocks, trees or cacti"), 20, { it.stat(Stat.OBSTACLES) }),
    RANK_5(L("Бывалый", "Seasoned"), L("Доведи командира до 5 ранга", "Raise a commander to rank 5"), 5, { it.stat(Stat.BEST_RANK) }),
    RANK_10(L("Ветеран", "Veteran"), L("Доведи командира до 10 ранга", "Raise a commander to rank 10"), 10, { it.stat(Stat.BEST_RANK) }),
    WEEKLY_GOLD(L("Золотая операция", "Golden operation"), L("Получи золото операции недели", "Win gold in a weekly operation"), 1, { it.stat(Stat.WEEKLY_GOLD) }),
    WEEKLY_GOLD_5(L("Генштаб", "General staff"), L("Получи золото пяти операций недели", "Win gold in five weekly operations"), 5, { it.stat(Stat.WEEKLY_GOLD) }),
    ;

    val key: String get() = "ach_$name"
}

/** Everything that changes how one game plays, besides the level itself. */
class Rules(
    val difficulty: Difficulty = Difficulty.NORMAL,
    val challenge: Challenge? = null,
    val perks: Perks = Perks.NONE,
    val mods: List<Mod> = emptyList(),
    /** Highest campaign level reached: towers and abilities unlock with it. */
    val progress: Int = 99,
    val vip: Boolean = false,
    /** Who leads the squads; null is nobody. */
    val commander: CommanderType? = null,
    /** His rank, 1 to [Ranks.MAX], from the battles he led before. */
    val rank: Int = 1,
) {
    val towers: List<TowerType> = TowerType.entries.filter {
        it.unlock <= progress && (challenge?.kind != ChallengeKind.ONLY || it in challenge.types)
    }
    val branches: Boolean = progress >= BRANCH_UNLOCK
    val abilitiesAllowed: Boolean = challenge?.kind != ChallengeKind.NO_ABILITIES && Mod.NO_ABILITIES !in mods

    fun unlocked(a: Ability): Boolean = a.unlock <= progress

    fun usable(a: Ability): Boolean = abilitiesAllowed && unlocked(a)

    val maxTowers: Int = min(
        if (challenge?.kind == ChallengeKind.MAX_TOWERS) challenge.value else Int.MAX_VALUE,
        if (Mod.FEW_TOWERS in mods) 10 else Int.MAX_VALUE,
    )
    val canSell: Boolean = challenge?.kind != ChallengeKind.NO_SELL
    val noLeaks: Boolean = challenge?.kind == ChallengeKind.NO_LEAKS

    val hpMul: Float = difficulty.hp * (if (Mod.TOUGH in mods) 1.3f else 1f)
    val speedMul: Float = if (Mod.FAST in mods) 1.25f else 1f
    val lives: Int = difficulty.lives + 3 * perks[Perk.LIVES]

    fun startMoney(base: Int): Int {
        var k = difficulty.money
        if (challenge?.kind == ChallengeKind.LOW_BUDGET) k *= 0.6f
        if (Mod.RICH in mods) k *= 1.5f
        if (Mod.POOR in mods) k *= 0.7f
        if (vip) k *= Vip.START_MONEY
        return Math.round(base * k / 5f) * 5 + 30 * perks[Perk.START_MONEY]
    }

    val rewardMul: Float = (1f + 0.1f * perks[Perk.BOUNTY]) * (if (difficulty == Difficulty.EASY) 1.1f else 1f) * (if (vip) Vip.BOUNTY else 1f)
    val damageMul: Float = 1f + 0.05f * perks[Perk.DAMAGE]
    val rangeMul: Float = 1f + 0.05f * perks[Perk.RANGE]
    val buildMul: Float = 1f - 0.05f * perks[Perk.DISCOUNT]
    val upgradeMul: Float = buildMul * (if (Mod.CHEAP_UPGRADES in mods) 0.7f else 1f)
    val refund: Float = 0.7f + 0.05f * perks[Perk.REFUND]
    val cooldownMul: Float = max(0.4f, 1f - 0.12f * perks[Perk.COOLDOWN]) * (if (vip) Vip.COOLDOWN else 1f)
    val maxSpeed: Int = if (vip) Vip.MAX_SPEED else 3
    val undoTime: Float = if (vip) Vip.UNDO_TIME else 4f
    val commanderLevel: Int = (if (vip) Vip.COMMANDER_LEVEL else 0) + (if (rank >= RankPerk.START.rank) 1 else 0)
    val xpMul: Float = (if (vip) Vip.XP else 1f) * (if (rank >= RankPerk.XP.rank) 1.25f else 1f)

    /** What the commander's rank adds to his weapon and skills. */
    val heroDamageMul: Float = 1f + 0.1f * listOf(RankPerk.DAMAGE, RankPerk.DAMAGE_MORE).count { rank >= it.rank }
    val heroCooldownMul: Float = 1f - 0.1f * listOf(RankPerk.COOLDOWN, RankPerk.COOLDOWN_MORE).count { rank >= it.rank }
    val heroRangeMul: Float = if (rank >= RankPerk.RANGE.rank) 1.1f else 1f
    val heroSkills: Int = if (rank >= Ranks.THIRD_SKILL) 3 else 2

    /** A second chance: reserves arrive once when the base falls. */
    val reserve: Boolean = vip
}
