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
            ChallengeKind.NO_SELL -> L("Без продажи башен", "No selling towers").t
            ChallengeKind.MAX_TOWERS -> L("Не больше %d башен", "At most %d towers").f(value)
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
    DAMAGE(L("Боеприпасы", "Ammunition"), L("+5% урона всем башням", "+5% damage for every tower"), 3, 3),
    RANGE(L("Оптика", "Optics"), L("+5% дальности башен", "+5% tower range"), 2, 3),
    DISCOUNT(L("Снабжение", "Logistics"), L("Башни и улучшения на 5% дешевле", "Towers and upgrades cost 5% less"), 2, 3),
    COOLDOWN(L("Связь", "Radio"), L("Способности заряжаются на 12% быстрее", "Abilities recharge 12% faster"), 3, 2),
    REFUND(L("Утилизация", "Salvage"), L("+5% денег при продаже башни", "+5% back when selling a tower"), 2, 2),
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
    FEW_TOWERS(L("Теснота", "Cramped"), L("Не больше 10 башен", "At most 10 towers")),
    CHEAP_UPGRADES(L("Мастерская", "Workshop"), L("Улучшения на 30% дешевле", "Upgrades cost 30% less")),
    BOSS_RUSH(L("Парад боссов", "Boss parade"), L("Босс каждые 5 волн", "A boss every 5 waves")),
    ;

    companion object {
        private val CONFLICTS = listOf(RICH to POOR, FAST to TOUGH, RICH to CHEAP_UPGRADES, NO_ABILITIES to FEW_TOWERS)

        /** Two mods that make sense together, the same for everybody on a given day. */
        fun forDay(day: Int): List<Mod> {
            val rng = Random(day * 7349 + 11)
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
    BUILDER(L("Строитель", "Builder"), L("Построй 200 башен", "Build 200 towers"), 200, { it.stat(Stat.BUILT) }),
    ARSENAL(L("Арсенал", "Arsenal"), L("Построй башни всех 10 видов", "Build all 10 kinds of tower"), 10, { Integer.bitCount(it.stat(Stat.TOWER_TYPES)) }),
    SPECIALIST(L("Специалист", "Specialist"), L("Выбери специализацию башни", "Pick a tower specialisation"), 1, { min(1, Integer.bitCount(it.stat(Stat.BRANCHES))) }),
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
        return Math.round(base * k / 5f) * 5 + 30 * perks[Perk.START_MONEY]
    }

    val rewardMul: Float = (1f + 0.1f * perks[Perk.BOUNTY]) * (if (difficulty == Difficulty.EASY) 1.1f else 1f)
    val damageMul: Float = 1f + 0.05f * perks[Perk.DAMAGE]
    val rangeMul: Float = 1f + 0.05f * perks[Perk.RANGE]
    val buildMul: Float = 1f - 0.05f * perks[Perk.DISCOUNT]
    val upgradeMul: Float = buildMul * (if (Mod.CHEAP_UPGRADES in mods) 0.7f else 1f)
    val refund: Float = 0.7f + 0.05f * perks[Perk.REFUND]
    val cooldownMul: Float = max(0.4f, 1f - 0.12f * perks[Perk.COOLDOWN])
}
