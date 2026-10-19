package com.makc.basedefense.engine

import kotlin.math.abs
import kotlin.math.min

/** A commander's power with a cooldown, used from his panel in the battle; it grows with his level. */
enum class Skill(val title: L, val hint: L, val cooldown: Float) {
    GRENADE(L("Граната", "Grenade"), L("Бросок в самую гущу врагов", "Thrown into the thick of the enemy"), 12f),
    RALLY(L("Вперёд!", "Charge!"), L("Отряды рядом наносят на 30% больше урона", "Nearby squads deal 30% more damage"), 35f),
    SENTRY(L("Турель", "Sentry gun"), L("Пулемёт 15 секунд стреляет сам", "A machine gun fires on its own for 15 seconds"), 30f),
    EMP(L("ЭМИ", "EMP"), L("Оглушает врагов вокруг, боссов замедляет", "Stuns the enemies around, slows bosses"), 28f),
    PIERCE(L("Бронебой", "Armor piercer"), L("Выстрел по самому крепкому врагу, по боссам ×2", "A shot at the toughest enemy, ×2 against bosses"), 16f),
    BARRAGE(L("Шквал", "Barrage"), L("Шесть гранат по врагам вокруг", "Six grenades at the enemies around"), 32f),
    SUPPRESS(L("Подавление", "Suppression"), L("Враги вокруг замедлены и получают на 30% больше урона", "Enemies around are slowed and take 30% more damage"), 30f),
    OVERCHARGE(L("Перегрузка", "Overcharge"), L("Отряды рядом стреляют на 50% чаще", "Nearby squads fire 50% faster"), 34f),
    MINE(L("Фугас", "Land mine"), L("Заряд на дороге взрывается под врагом, по боссам ×2", "A charge on the road blows under an enemy, ×2 against bosses"), 26f),
    ;

    val key: String get() = "s:$name"
}

/** One of a commander's levels in a battle: his weapon gets better as he gains experience. */
class CommanderLevel(val damage: Float, val rate: Float, val range: Float)

/**
 * The hero who leads the squads: one is always free, the others join for stars
 * earned in the campaign or for a purchase.
 */
enum class CommanderType(
    val title: L,
    val role: L,
    val weapon: L,
    val hitsGround: Boolean,
    val hitsAir: Boolean,
    /** Cells per second. */
    val speed: Float,
    val levels: List<CommanderLevel>,
    val skills: List<Skill>,
    /** Campaign stars that bring him for free; 0 is from the start. */
    val stars: Int,
    val product: Product?,
) {
    SERGEANT(
        L("Сержант Волков", "Sergeant Volkov"), L("Надёжный стрелок, бьёт и по земле, и по воздуху", "A steady shot at ground and air alike"),
        L("Автомат", "Assault rifle"), true, true, 2.4f,
        listOf(CommanderLevel(13f, 3.1f, 2.6f), CommanderLevel(16f, 3.2f, 2.7f), CommanderLevel(20f, 3.3f, 2.8f), CommanderLevel(25f, 3.4f, 2.9f), CommanderLevel(31f, 3.5f, 3.0f)),
        listOf(Skill.GRENADE, Skill.RALLY, Skill.SUPPRESS), 0, null,
    ),
    ENGINEER(
        L("Инженер Ковалёва", "Engineer Kovaleva"), L("Молния бьёт по цепочке и замедляет, турель стреляет сама", "Her lightning chains and slows, her sentry gun fires on its own"),
        L("Шоковая винтовка", "Shock rifle"), true, true, 2.2f,
        listOf(CommanderLevel(7.5f, 2.5f, 2.6f), CommanderLevel(9f, 2.6f, 2.7f), CommanderLevel(11f, 2.7f, 2.8f), CommanderLevel(13.5f, 2.8f, 2.9f), CommanderLevel(17f, 2.9f, 3.0f)),
        listOf(Skill.SENTRY, Skill.EMP, Skill.OVERCHARGE), 30, Product.ENGINEER,
    ),
    GROM(
        L("Капитан Гром", "Captain Grom"), L("Взрывы по земле и охота на боссов, но не по воздуху", "Ground blasts and boss hunting, though nothing in the air"),
        L("Гранатомёт", "Grenade launcher"), true, false, 1.8f,
        listOf(CommanderLevel(40f, 0.78f, 3.0f), CommanderLevel(49f, 0.8f, 3.1f), CommanderLevel(60f, 0.82f, 3.2f), CommanderLevel(73f, 0.85f, 3.3f), CommanderLevel(90f, 0.88f, 3.4f)),
        listOf(Skill.PIERCE, Skill.BARRAGE, Skill.MINE), 50, Product.GROM,
    ),
    ;

    val key: String get() = "c:$name"
}

/** The commander on the map in one battle. */
class Commander(val type: CommanderType, var x: Float, var y: Float) {
    /** Where he walks to. */
    var tx = x
    var ty = y

    /** 0 to [MAX_LEVEL]; experience is the damage he dealt. */
    var level = 0
    var xp = 0f
    var cooldown = 0f

    /** Degrees, 0 = right, 90 = down. */
    var angle = -90f
    var flash = 0f
    var kills = 0
    var damageDealt = 0f
    var jammed = 0f
    var shots = 0

    /** Distance walked, for his steps. */
    var walked = 0f

    /** Counts down after a level-up, for the glow. */
    var promoted = 0f

    /** Seconds left of his rally and of his overcharge. */
    var rally = 0f
    var overcharge = 0f
    val skillCooldowns = FloatArray(3)

    /** Experience gained in this battle, for his service record. */
    var earned = 0f

    val stats: CommanderLevel get() = type.levels[level]
    val moving: Boolean get() = abs(tx - x) + abs(ty - y) > 0.01f

    /** How strong his skills are: 1 at the first level, 2 at the last. */
    val power: Float get() = 1f + 0.25f * level

    /** Experience for the next level, or null at the last. */
    val nextXp: Float? get() = XP.getOrNull(level + 1)

    companion object {
        const val MAX_LEVEL = 4

        /** Experience each level needs. */
        val XP = floatArrayOf(0f, 500f, 1500f, 3200f, 6000f)
    }
}

/**
 * A commander's rank, earned between battles: every battle he leads adds to his service,
 * and each rank brings something for good. The third skill comes with [THIRD_SKILL].
 */
enum class RankPerk(val rank: Int, val text: L) {
    DAMAGE(2, L("+10% к урону оружия", "+10% weapon damage")),
    COOLDOWN(3, L("Навыки перезаряжаются на 10% быстрее", "Skills recharge 10% faster")),
    START(4, L("Начинает бой на уровень выше", "Starts battles a level higher")),
    THIRD(5, L("Третий навык: %s", "Third skill: %s")),
    DAMAGE_MORE(6, L("Ещё +10% к урону", "Another +10% damage")),
    COOLDOWN_MORE(7, L("Навыки ещё на 10% быстрее", "Skills another 10% faster")),
    XP(8, L("+25% опыта в бою", "+25% experience in battle")),
    RANGE(9, L("+10% к дальности", "+10% range")),
    PARADE(10, L("Золотые погоны и парадная форма", "Gold insignia and a parade uniform")),
    ;

    /** What it gives [c], with his third skill named. */
    fun text(c: CommanderType): String = if (this == THIRD) text.f(c.skills[2].title.t) else text.t

    companion object {
        fun at(rank: Int): RankPerk? = entries.firstOrNull { it.rank == rank }
    }
}

object Ranks {
    const val MAX = 10
    const val THIRD_SKILL = 5

    /** Service each rank needs; rank 1 is where everyone starts. */
    val SERVICE = intArrayOf(0, 0, 300, 800, 1600, 2800, 4400, 6500, 9200, 12500, 16500)

    /** Battle experience per point of service, the bonus for a win and the most one battle gives. */
    const val XP_PER_SERVICE = 25f
    const val WIN_SERVICE = 60
    const val MOST = 600

    fun of(service: Int): Int = (MAX downTo 1).first { service >= SERVICE[it] }

    /** Service [c] earned in a battle he fought to the end. */
    fun earned(c: Commander, won: Boolean): Int = min(MOST, (c.earned / XP_PER_SERVICE).toInt() + if (won) WIN_SERVICE else 0)
}

/** The engineer's machine gun: fires by itself until its time runs out. */
class Sentry(val x: Float, val y: Float, var life: Float, val damage: Float) {
    val maxLife = life
    var cooldown = 0f
    var angle = -90f
    var flash = 0f
}

/** Grom's charge on the road: waits a moment to arm, then blows under the first walker. */
class Trap(val x: Float, val y: Float, val damage: Float, var life: Float) {
    var arming = Trap.ARMING

    companion object {
        const val ARMING = 0.6f
    }
}
