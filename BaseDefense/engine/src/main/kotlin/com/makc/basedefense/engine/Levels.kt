package com.makc.basedefense.engine

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

const val COLS = 9
const val ROWS = 15

/** A road through cell centres; the first corner sits just outside the map, the last is the base. */
class Path(corners: IntArray) {
    val size = corners.size / 2
    val xs = FloatArray(size) { corners[it * 2] + 0.5f }
    val ys = FloatArray(size) { corners[it * 2 + 1] + 0.5f }
    private val cum = FloatArray(size)
    val length: Float

    init {
        for (i in 1 until size) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        length = cum[size - 1]
    }

    /** Fills [out] with x, y and the unit direction of travel at distance [d]. */
    fun pointAt(d: Float, out: FloatArray) {
        val dd = d.coerceIn(0f, length)
        var i = 1
        while (i < size - 1 && cum[i] < dd) i++
        val seg = cum[i] - cum[i - 1]
        val t = if (seg > 0f) (dd - cum[i - 1]) / seg else 0f
        val dx = xs[i] - xs[i - 1]
        val dy = ys[i] - ys[i - 1]
        out[0] = xs[i - 1] + dx * t
        out[1] = ys[i - 1] + dy * t
        out[2] = if (seg > 0f) dx / seg else 0f
        out[3] = if (seg > 0f) dy / seg else 1f
    }
}

enum class Biome(val title: L) {
    GREEN(L("Зелёная долина", "Green Valley")),
    DESERT(L("Пустыня", "Desert")),
    WINTER(L("Север", "The North")),
}

/**
 * The grid of a level. Terrain comes from the ASCII picture: '.' grass you can
 * build on, '#' road, 'i' icy road, 'B' base, '~' water, '=' bridge, and
 * obstacles that can be cleared for coins: 'T' tree, 'R' rock, 'C' cactus.
 */
class GameMap(val level: LevelDef) {
    val paths = level.paths.map { Path(it) }
    private val terrain = Array(ROWS) { r -> CharArray(COLS) { c -> level.ascii[r][c] } }
    private val road = BooleanArray(COLS * ROWS)
    val baseCol: Int
    val baseRow: Int

    /** Goes up whenever terrain changes, so cached pictures of the map can be redrawn. */
    var version = 0
        private set

    init {
        for (p in level.paths) {
            for (i in 0 until p.size / 2 - 1) {
                var c = p[i * 2]
                var r = p[i * 2 + 1]
                val c2 = p[i * 2 + 2]
                val r2 = p[i * 2 + 3]
                require(c == c2 || r == r2) { "Level ${level.id}: road segments must be straight" }
                val dc = Integer.signum(c2 - c)
                val dr = Integer.signum(r2 - r)
                while (true) {
                    if (inside(c, r)) road[r * COLS + c] = true
                    if (c == c2 && r == r2) break
                    c += dc
                    r += dr
                }
            }
        }
        val last = level.paths[0]
        baseCol = last[last.size - 2]
        baseRow = last[last.size - 1]
    }

    val biome: Biome get() = level.biome

    fun inside(c: Int, r: Int): Boolean = c in 0 until COLS && r in 0 until ROWS

    fun isRoad(c: Int, r: Int): Boolean = inside(c, r) && road[r * COLS + c]

    fun isIce(c: Int, r: Int): Boolean = inside(c, r) && terrain[r][c] == 'i'

    fun terrain(c: Int, r: Int): Char = terrain[r][c]

    fun buildable(c: Int, r: Int): Boolean = inside(c, r) && !road[r * COLS + c] && terrain[r][c] == '.'

    /** Road cells where mines can go: anywhere but the base itself. */
    fun mineable(c: Int, r: Int): Boolean = isRoad(c, r) && terrain[r][c] != 'B'

    fun obstacle(c: Int, r: Int): Boolean = inside(c, r) && terrain[r][c] in OBSTACLES

    fun clear(c: Int, r: Int) {
        if (!obstacle(c, r)) return
        terrain[r][c] = '.'
        version++
    }

    val buildableCount: Int get() = (0 until ROWS).sumOf { r -> (0 until COLS).count { c -> buildable(c, r) } }

    companion object {
        const val OBSTACLES = "TRC"
    }
}

class Group(
    val type: EnemyType,
    val count: Int,
    val interval: Float,
    val delay: Float,
    /** Which road; -1 alternates between all of them. */
    val path: Int,
    val hpMul: Float,
    /** Merged groups pay for every enemy they stand in for. */
    val rewardMul: Float = 1f,
)

class Wave(val index: Int, val groups: List<Group>, val fresh: EnemyType?, val bosses: List<EnemyType>) {
    val total = groups.sumOf { it.count }

    /** Enemy types in this wave with their counts, in order of appearance. */
    val preview: List<Pair<EnemyType, Int>> = run {
        val counts = LinkedHashMap<EnemyType, Int>()
        for (g in groups) counts[g.type] = (counts[g.type] ?: 0) + g.count
        counts.toList()
    }

    private fun share(pick: (EnemyType) -> Boolean): Float {
        var some = 0f
        var all = 0f
        for (g in groups) {
            val t = g.count * g.type.hp * g.hpMul
            all += t
            if (pick(g.type)) some += t
        }
        return if (all > 0f) some / all else 0f
    }

    val airShare: Float = share { it.air }
    val armorShare: Float = share { it.armor >= 4f }
}

/**
 * How a level's waves are built. Each enemy type joins at its wave in [intro];
 * [count] 0 means endless. Types in [favor] show up in every wave they can.
 */
class WavePlan(
    val count: Int,
    val intro: List<Pair<EnemyType, Int>>,
    val budget: Float,
    val growth: Float,
    val hp: Float,
    val hpGrowth: Float,
    val bosses: Map<Int, List<EnemyType>> = emptyMap(),
    val seed: Int,
    val favor: List<EnemyType> = emptyList(),
    /** Bosses on every n-th wave besides [bosses]; 0 means none. */
    val bossEvery: Int = 0,
)

object WaveGen {
    /** Bosses already have huge base health; this keeps late ones killable. */
    private const val BOSS_HP = 0.6f
    private const val MAX_GROUP = 40

    fun generate(plan: WavePlan, i: Int, paths: Int): Wave {
        val rng = Random(plan.seed * 7919 + i * 104_729)
        val available = plan.intro.filter { it.second <= i }.map { it.first }
        val fresh = plan.intro.firstOrNull { it.second == i && i > 0 }?.first
        val budget = plan.budget * (1f + plan.growth * i)
        // Health grows faster than income, so a defence that coasts eventually breaks.
        val hpMul = plan.hp * plan.hpGrowth.pow(i)

        val shares = ArrayList<Pair<EnemyType, Float>>()
        if (fresh != null) shares += fresh to 0.5f
        val favored = plan.favor.filter { it in available && it != fresh }
        for (f in favored) shares += f to 0.3f / favored.size
        val others = available.filter { it != fresh && it !in favored }.shuffled(rng)
        val extra = if (others.isEmpty()) 0 else 1 + rng.nextInt(min(2, others.size))
        val left = 1f - shares.sumOf { it.second.toDouble() }.toFloat()
        val weights = FloatArray(extra) { 0.4f + rng.nextFloat() * 0.6f }
        val sum = weights.sum()
        for (k in 0 until extra) shares += others[k] to left * weights[k] / sum
        if (extra == 0 && shares.isNotEmpty() && left > 0f) {
            val (type, s) = shares[0]
            shares[0] = type to s + left
        }

        val groups = ArrayList<Group>()
        var delay = 0.5f
        for ((type, share) in shares) {
            var count = max(1, (budget * share / type.threat).roundToInt())
            var groupHp = hpMul
            var reward = 1f
            // Past a point, fewer and tougher enemies: a phone has to draw all of them.
            if (count > MAX_GROUP) {
                reward = count / MAX_GROUP.toFloat()
                groupHp *= reward
                count = MAX_GROUP
            }
            val path = if (paths <= 1) 0 else rng.nextInt(paths + 1) - 1
            groups += Group(type, count, type.interval, delay, path, groupHp, reward)
            delay += count * type.interval * 0.65f + 1f
        }

        val bosses = plan.bosses[i] ?: periodicBosses(plan, i)
        for ((k, boss) in bosses.withIndex()) {
            val bossHp = hpMul * BOSS_HP * (if (plan.count == 0) 1f + 0.15f * (i / 10) else 1f)
            groups += Group(boss, 1, 1f, delay * 0.5f + k * 4f, if (paths <= 1) 0 else k % paths, bossHp)
        }
        return Wave(i, groups, fresh, bosses)
    }

    private fun periodicBosses(plan: WavePlan, i: Int): List<EnemyType> {
        val every = plan.bossEvery
        if (every <= 0 || i % every != every - 1) return emptyList()
        return when ((i / every) % 3) {
            0 -> listOf(EnemyType.APC)
            1 -> listOf(EnemyType.HELI)
            else -> listOf(EnemyType.APC, EnemyType.HELI)
        }
    }
}

class LevelDef(
    val id: Int,
    val name: L,
    val biome: Biome,
    val ascii: List<String>,
    val paths: List<IntArray>,
    val startMoney: Int,
    val plan: WavePlan,
    val challenge: Challenge? = null,
) {
    val endless: Boolean get() = plan.count == 0
    val daily: Boolean get() = id == Levels.DAILY_ID
    val campaign: Boolean get() = id in 1..Levels.all.size

    /** 0, 1 or 2 for campaign levels, -1 for the others. */
    val world: Int get() = if (campaign) (id - 1) / Levels.PER_WORLD else -1
}

object Levels {
    const val PER_WORLD = 8
    const val ENDLESS_ID = 100
    const val DAILY_ID = 200

    private val B = EnemyType.BANDIT
    private val RU = EnemyType.RUNNER
    private val SW = EnemyType.SWARM
    private val BR = EnemyType.BRUTE
    private val ME = EnemyType.MEDIC
    private val DR = EnemyType.DRONE
    private val SH = EnemyType.SHIELD
    private val GH = EnemyType.GHOST
    private val SA = EnemyType.SAPPER
    private val MO = EnemyType.MOTHER
    private val APC = EnemyType.APC
    private val HELI = EnemyType.HELI

    private val G = Biome.GREEN
    private val D = Biome.DESERT
    private val W = Biome.WINTER

    /** Every enemy of the later worlds, joining one by one. */
    private val ALL = listOf(B to 0, RU to 0, SW to 0, BR to 0, DR to 1, ME to 2, SH to 3, GH to 4, SA to 5, MO to 6)

    val all: List<LevelDef> = listOf(
        // --- Green Valley -------------------------------------------------------------------
        LevelDef(
            1, L("Побережье", "Coast"), G,
            listOf(
                ".#.....T.",
                ".#.......",
                ".#..R....",
                ".#######.",
                ".......#.",
                "T......#.",
                "...R...#.",
                ".#######.",
                ".#......T",
                ".#.......",
                ".#...R...",
                ".######..",
                "......#..",
                "......B..",
                "~~~~~~~~~",
            ),
            listOf(intArrayOf(1, -1, 1, 3, 7, 3, 7, 7, 1, 7, 1, 11, 6, 11, 6, 13)),
            startMoney = 160,
            plan = WavePlan(8, listOf(B to 0, RU to 3), budget = 7f, growth = 0.32f, hp = 1f, hpGrowth = 1.1f, seed = 1),
            challenge = Challenge.maxTowers(6),
        ),
        LevelDef(
            2, L("Лес", "Forest"), G,
            listOf(
                "TT..T..TT",
                "#######.T",
                "T.....#..",
                "..T...#.T",
                "..#####..",
                "T.#....T.",
                "..#..T...",
                "..######.",
                "T......#.",
                "..T....#T",
                "..######.",
                "T.#....T.",
                "..#..T...",
                "..B.....T",
                "TT...TT.T",
            ),
            listOf(intArrayOf(-1, 1, 6, 1, 6, 4, 2, 4, 2, 7, 7, 7, 7, 10, 2, 10, 2, 13)),
            startMoney = 170,
            plan = WavePlan(10, listOf(B to 0, RU to 1, SW to 4), budget = 8f, growth = 0.33f, hp = 1.05f, hpGrowth = 1.11f, seed = 2),
            challenge = Challenge.NO_SELL,
        ),
        LevelDef(
            3, L("Каньон", "Canyon"), G,
            listOf(
                "R#..R...R",
                ".#..R....",
                ".#..####.",
                ".#..#..#.",
                "R#..#..#R",
                ".#..#..#.",
                ".#R.#R.#.",
                ".#..#..#.",
                ".#..#..#.",
                "R#..#..#R",
                ".#..#..#.",
                ".#..#R.#.",
                ".####..#.",
                "R......B.",
                "RR.R..RRR",
            ),
            listOf(intArrayOf(1, -1, 1, 12, 4, 12, 4, 2, 7, 2, 7, 13)),
            startMoney = 200,
            plan = WavePlan(12, listOf(B to 0, RU to 0, SW to 2, BR to 4), budget = 9f, growth = 0.34f, hp = 1.1f, hpGrowth = 1.13f, seed = 3),
            challenge = Challenge.only(TowerType.TURRET, TowerType.SHOTGUN, TowerType.ROCKET),
        ),
        LevelDef(
            4, L("Руины", "Ruins"), G,
            listOf(
                "..R...R..",
                ".R..T....",
                "#######..",
                "......#.R",
                ".T....#..",
                "......#..",
                "..#####..",
                "..#...R..",
                "R.#......",
                "..#..T...",
                "..#####..",
                "......#.R",
                ".R....#..",
                "......#..",
                "..R...B.R",
            ),
            listOf(intArrayOf(-1, 2, 6, 2, 6, 6, 2, 6, 2, 10, 6, 10, 6, 14)),
            startMoney = 220,
            plan = WavePlan(
                12, listOf(B to 0, RU to 0, SW to 1, DR to 3, BR to 5, ME to 7),
                budget = 9.5f, growth = 0.35f, hp = 1.15f, hpGrowth = 1.125f, seed = 4,
            ),
            challenge = Challenge.NO_LEAKS,
        ),
        LevelDef(
            5, L("Развилка", "Fork"), G,
            listOf(
                ".#.R...#.",
                ".#.....#.",
                ".#..T..#.",
                ".#.....#R",
                ".#..R..#.",
                ".####..#.",
                "....#..#.",
                "R...#..#.",
                "....####.",
                ".T..#....",
                "....#..T.",
                "..R.#....",
                "....#.R..",
                "....B....",
                ".T.....T.",
            ),
            listOf(
                intArrayOf(1, -1, 1, 5, 4, 5, 4, 13),
                intArrayOf(7, -1, 7, 8, 4, 8, 4, 13),
            ),
            startMoney = 260,
            plan = WavePlan(
                14, listOf(B to 0, RU to 0, SW to 0, BR to 3, DR to 5, ME to 7),
                budget = 10f, growth = 0.36f, hp = 1.2f, hpGrowth = 1.115f, seed = 5,
            ),
            challenge = Challenge.LOW_BUDGET,
        ),
        LevelDef(
            6, L("Мост", "Bridge"), G,
            listOf(
                "T...#...T",
                "....#.R..",
                ".T..#....",
                ".####..T.",
                ".#.......",
                "~=~~~~~~~",
                "~=~~~~~~~",
                ".#######.",
                ".R.....#.",
                "~~~~~~~=~",
                "..T....#.",
                "....####.",
                ".R..#..T.",
                "....#....",
                "T...B...T",
            ),
            listOf(intArrayOf(4, -1, 4, 3, 1, 3, 1, 7, 7, 7, 7, 11, 4, 11, 4, 14)),
            startMoney = 260,
            plan = WavePlan(
                14, listOf(B to 0, RU to 0, SW to 0, BR to 2, ME to 4, DR to 6),
                budget = 10.5f, growth = 0.37f, hp = 1.25f, hpGrowth = 1.11f,
                bosses = mapOf(13 to listOf(APC)), seed = 6,
            ),
            challenge = Challenge.NO_ABILITIES,
        ),
        LevelDef(
            7, L("Аэродром", "Airfield"), G,
            listOf(
                "..R...T..",
                "########.",
                ".......#.",
                "T..R...#.",
                ".......#.",
                ".#######.",
                ".#.......",
                ".#..R..T.",
                ".#.......",
                ".#######.",
                ".......#.",
                "R......#R",
                "...T...#.",
                ".B######.",
                "..R....T.",
            ),
            listOf(intArrayOf(-1, 1, 7, 1, 7, 5, 1, 5, 1, 9, 7, 9, 7, 13, 1, 13)),
            startMoney = 300,
            plan = WavePlan(
                15, listOf(B to 0, RU to 0, DR to 1, SW to 2, BR to 4, ME to 6),
                budget = 11f, growth = 0.38f, hp = 1.3f, hpGrowth = 1.125f,
                bosses = mapOf(14 to listOf(HELI)), seed = 7,
            ),
            challenge = Challenge.maxTowers(10),
        ),
        LevelDef(
            8, L("Последний рубеж", "Last Stand"), G,
            listOf(
                "R#.....#R",
                ".#..R..#.",
                ".#.....#.",
                ".#.T.T.#.",
                ".###.###.",
                "...#.#...",
                "R..#.#..R",
                "...#.#...",
                "...###...",
                ".T..#..T.",
                "....#....",
                "..R.#.R..",
                "....#....",
                "....B....",
                "RR.....RR",
            ),
            listOf(
                intArrayOf(1, -1, 1, 4, 3, 4, 3, 8, 4, 8, 4, 13),
                intArrayOf(7, -1, 7, 4, 5, 4, 5, 8, 4, 8, 4, 13),
            ),
            startMoney = 340,
            plan = WavePlan(
                18, listOf(B to 0, RU to 0, SW to 0, BR to 1, DR to 2, ME to 4),
                budget = 12f, growth = 0.4f, hp = 1.35f, hpGrowth = 1.09f,
                bosses = mapOf(9 to listOf(APC), 17 to listOf(HELI, APC)), seed = 8,
            ),
            challenge = Challenge.only(TowerType.ROCKET, TowerType.SAM, TowerType.TESLA),
        ),

        // --- Desert -------------------------------------------------------------------------
        LevelDef(
            9, L("Дюны", "Dunes"), D,
            listOf(
                "..C...R..",
                "...C.....",
                "######..C",
                ".....#...",
                "C....#.C.",
                ".....#...",
                ".#####...",
                ".#...C..R",
                ".#.......",
                ".#..R..C.",
                ".#######.",
                ".......#.",
                "C...C..#.",
                ".......B.",
                "..R..C..C",
            ),
            listOf(intArrayOf(-1, 2, 5, 2, 5, 6, 1, 6, 1, 10, 7, 10, 7, 13)),
            startMoney = 300,
            plan = WavePlan(
                14, listOf(B to 0, RU to 0, SW to 0, BR to 2, DR to 3, ME to 6),
                budget = 12f, growth = 0.38f, hp = 1.35f, hpGrowth = 1.1f, seed = 9,
            ),
            challenge = Challenge.NO_SELL,
        ),
        LevelDef(
            10, L("Оазис", "Oasis"), D,
            listOf(
                "C...#...C",
                "....#..R.",
                ".####....",
                ".#....C..",
                ".#.......",
                ".#.~~~..R",
                ".#.~~~...",
                ".##===##.",
                "...~~~.#.",
                "C..~~~.#.",
                ".......#.",
                "..R....#.",
                "....####.",
                "....#..C.",
                "C...B....",
            ),
            listOf(intArrayOf(4, -1, 4, 2, 1, 2, 1, 7, 7, 7, 7, 12, 4, 12, 4, 14)),
            startMoney = 320,
            plan = WavePlan(
                14, listOf(B to 0, RU to 0, SW to 0, BR to 0, DR to 2, SH to 4, ME to 7),
                budget = 12.5f, growth = 0.38f, hp = 1.4f, hpGrowth = 1.1f, seed = 10,
            ),
            challenge = Challenge.NO_LEAKS,
        ),
        LevelDef(
            11, L("Ущелье", "Gorge"), D,
            listOf(
                "R#RR.RR.R",
                ".#.......",
                ".#..C....",
                ".#......R",
                ".#######.",
                "R......#.",
                "..C.R..#.",
                ".......#R",
                "R......#.",
                ".#######.",
                ".#...R...",
                ".#..C...R",
                ".#.......",
                ".####B...",
                "RR.R..RRR",
            ),
            listOf(intArrayOf(1, -1, 1, 4, 7, 4, 7, 9, 1, 9, 1, 13, 5, 13)),
            startMoney = 330,
            plan = WavePlan(
                15, listOf(B to 0, RU to 0, SW to 0, BR to 0, DR to 0, SH to 2, ME to 5),
                budget = 13f, growth = 0.39f, hp = 1.45f, hpGrowth = 1.1f,
                bosses = mapOf(14 to listOf(APC)), seed = 11,
            ),
            challenge = Challenge.only(TowerType.MINES, TowerType.SNIPER, TowerType.TURRET),
        ),
        LevelDef(
            12, L("Мираж", "Mirage"), D,
            listOf(
                ".#.....#.",
                ".#..C..#.",
                ".#.....#.",
                ".#..R..#C",
                ".#.....#.",
                ".#######.",
                "C.....##.",
                "......##.",
                "..######.",
                "..#...#..",
                "R.#...#.R",
                "..#####..",
                "....#....",
                "....B....",
                "C.R...R.C",
            ),
            listOf(
                intArrayOf(1, -1, 1, 5, 6, 5, 6, 11, 4, 11, 4, 13),
                intArrayOf(7, -1, 7, 8, 2, 8, 2, 11, 4, 11, 4, 13),
            ),
            startMoney = 360,
            plan = WavePlan(
                15, listOf(B to 0, RU to 0, SW to 0, BR to 0, GH to 2, DR to 4, SH to 6, ME to 8),
                budget = 13f, growth = 0.39f, hp = 1.5f, hpGrowth = 1.1f, seed = 12,
            ),
            challenge = Challenge.maxTowers(10),
        ),
        LevelDef(
            13, L("Караван", "Caravan"), D,
            listOf(
                "C...R...C",
                "########.",
                ".......#.",
                "..C....#R",
                ".#######.",
                ".#....C..",
                ".#..R....",
                ".#######.",
                "C......#.",
                "...R...#.",
                ".#######.",
                ".#..C...R",
                ".#.......",
                ".###B....",
                "..C...R..",
            ),
            listOf(intArrayOf(-1, 1, 7, 1, 7, 4, 1, 4, 1, 7, 7, 7, 7, 10, 1, 10, 1, 13, 4, 13)),
            startMoney = 360,
            plan = WavePlan(
                16, listOf(B to 0, RU to 0, SW to 0, BR to 0, DR to 0, SA to 2, GH to 4, SH to 6, ME to 8),
                budget = 13.5f, growth = 0.39f, hp = 1.55f, hpGrowth = 1.095f, seed = 13,
            ),
            challenge = Challenge.NO_ABILITIES,
        ),
        LevelDef(
            14, L("Буря", "Storm"), D,
            listOf(
                "C..R...C.",
                "....C....",
                "........R",
                "####.....",
                "...#..C..",
                "...#..###",
                "...#..#..",
                "R..#..#.C",
                "...#..#..",
                "...####..",
                "....#....",
                "..C.#..R.",
                "....#....",
                "....B....",
                "R.C....CR",
            ),
            listOf(
                intArrayOf(-1, 3, 3, 3, 3, 9, 4, 9, 4, 13),
                intArrayOf(9, 5, 6, 5, 6, 9, 4, 9, 4, 13),
            ),
            startMoney = 380,
            plan = WavePlan(
                16, listOf(B to 0, RU to 0, SW to 0, BR to 0, DR to 1, SH to 2, MO to 3, GH to 5, SA to 6, ME to 7),
                budget = 14f, growth = 0.39f, hp = 1.6f, hpGrowth = 1.095f,
                bosses = mapOf(15 to listOf(HELI)), seed = 14,
            ),
            challenge = Challenge.maxTowers(12),
        ),
        LevelDef(
            15, L("Перекрёсток", "Crossroads"), D,
            listOf(
                "C...#..R.",
                "....#....",
                ".R..#..C.",
                "....#....",
                "C...#...R",
                ".#######.",
                ".#..#..#.",
                ".#..#..#.",
                ".#..#..#C",
                ".#..#..#.",
                ".####..#.",
                "R......#.",
                "....####.",
                "..C.#....",
                "....B..C.",
            ),
            listOf(intArrayOf(4, -1, 4, 10, 1, 10, 1, 5, 7, 5, 7, 12, 4, 12, 4, 14)),
            startMoney = 380,
            plan = WavePlan(
                17, ALL, budget = 14f, growth = 0.39f, hp = 1.65f, hpGrowth = 1.09f,
                bosses = mapOf(8 to listOf(APC), 16 to listOf(HELI)), seed = 15,
            ),
            challenge = Challenge.only(TowerType.FLAMER, TowerType.TESLA, TowerType.SAM),
        ),
        LevelDef(
            16, L("Великая пустыня", "Great Desert"), D,
            listOf(
                "C#.....#C",
                ".#..R..#.",
                ".#.....#.",
                ".###.###.",
                "...#.#...",
                "R..#C#..R",
                "...#.#...",
                ".###.###.",
                ".#.....#.",
                ".#..R..#.",
                ".#.....#.",
                ".#######.",
                "....#....",
                "....B....",
                "C.R...R.C",
            ),
            listOf(
                intArrayOf(1, -1, 1, 3, 3, 3, 3, 7, 1, 7, 1, 11, 4, 11, 4, 13),
                intArrayOf(7, -1, 7, 3, 5, 3, 5, 7, 7, 7, 7, 11, 4, 11, 4, 13),
            ),
            startMoney = 400,
            plan = WavePlan(
                18, ALL, budget = 14.5f, growth = 0.4f, hp = 1.6f, hpGrowth = 1.085f,
                bosses = mapOf(9 to listOf(APC), 17 to listOf(HELI, APC)), seed = 16,
            ),
            challenge = Challenge.NO_LEAKS,
        ),

        // --- The North ----------------------------------------------------------------------
        LevelDef(
            17, L("Перевал", "Mountain Pass"), W,
            listOf(
                "T...#..T.",
                "..R.#....",
                ".T..#..R.",
                ".####....",
                ".#....T..",
                ".i..~~~..",
                ".i..~~~.T",
                ".i.......",
                ".#######.",
                "T......#.",
                "..R....i.",
                ".T.....i.",
                "...#####.",
                "...#..T..",
                "R..B...R.",
            ),
            listOf(intArrayOf(4, -1, 4, 3, 1, 3, 1, 8, 7, 8, 7, 12, 3, 12, 3, 14)),
            startMoney = 400,
            plan = WavePlan(
                16, ALL, budget = 14.5f, growth = 0.4f, hp = 1.75f, hpGrowth = 1.09f, seed = 17,
            ),
            challenge = Challenge.maxTowers(12),
        ),
        LevelDef(
            18, L("Замёрзшая река", "Frozen River"), W,
            listOf(
                ".T..R..T.",
                "T........",
                "#######..",
                "......#.T",
                ".R....#..",
                "......i..",
                "..#####..",
                "..#..T..R",
                "T.#......",
                "~~=~~~~~~",
                "~~=~~~~~~",
                "..#..R..T",
                "..#####..",
                "T.....#..",
                "..R...B.T",
            ),
            listOf(intArrayOf(-1, 2, 6, 2, 6, 6, 2, 6, 2, 12, 6, 12, 6, 14)),
            startMoney = 400,
            plan = WavePlan(
                17, ALL, budget = 15f, growth = 0.4f, hp = 1.8f, hpGrowth = 1.09f, seed = 18,
            ),
            challenge = Challenge.NO_SELL,
        ),
        LevelDef(
            19, L("Ледник", "Glacier"), W,
            listOf(
                "T#.....#T",
                ".i..R..#.",
                ".i.....i.",
                ".i..T..i.",
                ".####..i.",
                "T...#..i.",
                "....#..i.",
                "..R.#..#.",
                "....####T",
                ".T..#....",
                "....i..R.",
                "R...i....",
                "....#..T.",
                "....B....",
                "T.R...R.T",
            ),
            listOf(
                intArrayOf(1, -1, 1, 4, 4, 4, 4, 13),
                intArrayOf(7, -1, 7, 8, 4, 8, 4, 13),
            ),
            startMoney = 420,
            plan = WavePlan(
                17, ALL, budget = 15f, growth = 0.4f, hp = 1.72f, hpGrowth = 1.09f,
                bosses = mapOf(16 to listOf(APC)), seed = 19,
            ),
            challenge = Challenge.maxTowers(12),
        ),
        LevelDef(
            20, L("Метель", "Blizzard"), W,
            listOf(
                "T..R...T.",
                "########.",
                "..T....#.",
                ".R.....i.",
                ".####..i.",
                ".#..#..#.",
                ".#..#..#T",
                ".i..#..#.",
                ".i..#.R#.",
                ".#..#..#.",
                ".#..B..#.",
                ".#.....i.",
                ".#..T..#.",
                ".#######.",
                "T..R..T..",
            ),
            listOf(intArrayOf(-1, 1, 7, 1, 7, 13, 1, 13, 1, 4, 4, 4, 4, 10)),
            startMoney = 420,
            plan = WavePlan(
                18, ALL, budget = 15f, growth = 0.4f, hp = 1.9f, hpGrowth = 1.085f,
                bosses = mapOf(17 to listOf(HELI)), seed = 20,
            ),
            challenge = Challenge.LOW_BUDGET,
        ),
        LevelDef(
            21, L("Северная застава", "Northern Outpost"), W,
            listOf(
                "T..R...T.",
                ".......R.",
                "...####..",
                ".T.#..i..",
                "...#..#.T",
                "R..#..#..",
                "#######..",
                "...#....T",
                ".T.i..R..",
                "...i.....",
                "...#..T..",
                "...####..",
                "R.....#..",
                "..T...B.T",
                ".R.......",
            ),
            listOf(intArrayOf(-1, 6, 6, 6, 6, 2, 3, 2, 3, 11, 6, 11, 6, 13)),
            startMoney = 420,
            plan = WavePlan(
                18, ALL, budget = 15.5f, growth = 0.4f, hp = 1.95f, hpGrowth = 1.085f,
                bosses = mapOf(9 to listOf(APC), 17 to listOf(HELI)), seed = 21,
            ),
            challenge = Challenge.NO_ABILITIES,
        ),
        LevelDef(
            22, L("Ледяной лабиринт", "Ice Maze"), W,
            listOf(
                "T#..R..T.",
                ".#.......",
                ".#iiiii#.",
                "T......#.",
                "..R....#T",
                ".#######.",
                ".#..T....",
                ".#....R..",
                ".#iiiii#.",
                "T......#.",
                "..R....#.",
                ".#######.",
                ".#..T...R",
                ".###B....",
                "T...R..T.",
            ),
            listOf(intArrayOf(1, -1, 1, 2, 7, 2, 7, 5, 1, 5, 1, 8, 7, 8, 7, 11, 1, 11, 1, 13, 4, 13)),
            startMoney = 420,
            plan = WavePlan(
                19, ALL, budget = 15.5f, growth = 0.4f, hp = 2f, hpGrowth = 1.085f,
                bosses = mapOf(9 to listOf(HELI), 18 to listOf(APC)), seed = 22,
            ),
            challenge = Challenge.only(TowerType.TESLA, TowerType.MINES, TowerType.TURRET),
        ),
        LevelDef(
            23, L("Полярная ночь", "Polar Night"), W,
            listOf(
                "T..R..T..",
                "....T....",
                "####....R",
                "...#.....",
                "...#.####",
                "R..i.#..T",
                "...#.i...",
                "...###...",
                ".T..#..T.",
                "....i....",
                "..R.i.R..",
                "....#....",
                "T...#...T",
                "....B....",
                ".R.T..R..",
            ),
            listOf(
                intArrayOf(-1, 2, 3, 2, 3, 7, 4, 7, 4, 13),
                intArrayOf(9, 4, 5, 4, 5, 7, 4, 7, 4, 13),
            ),
            startMoney = 420,
            plan = WavePlan(
                19, ALL, budget = 15f, growth = 0.4f, hp = 1.65f, hpGrowth = 1.085f,
                bosses = mapOf(18 to listOf(APC, HELI)), seed = 23, favor = listOf(GH),
            ),
            challenge = Challenge.NO_LEAKS,
        ),
        LevelDef(
            24, L("Цитадель", "Citadel"), W,
            listOf(
                "T#.....#T",
                ".#..R..#.",
                ".###.###.",
                "...i.i...",
                "R..#.#..R",
                "...#.#...",
                ".###.###.",
                ".#.....#.",
                ".i..T..i.",
                ".#.....#.",
                ".#######.",
                "R...#...R",
                "..T.i.T..",
                "....B....",
                "T.R...R.T",
            ),
            listOf(
                intArrayOf(1, -1, 1, 2, 3, 2, 3, 6, 1, 6, 1, 10, 4, 10, 4, 13),
                intArrayOf(7, -1, 7, 2, 5, 2, 5, 6, 7, 6, 7, 10, 4, 10, 4, 13),
            ),
            startMoney = 500,
            plan = WavePlan(
                20, ALL, budget = 16f, growth = 0.4f, hp = 1.8f, hpGrowth = 1.08f,
                bosses = mapOf(6 to listOf(APC), 13 to listOf(HELI), 19 to listOf(APC, HELI)), seed = 24,
            ),
            challenge = Challenge.maxTowers(14),
        ),
    )

    private val SPIRAL = listOf(
        "#........",
        "#.#######",
        "#.#.....#",
        "#.#.###.#",
        "#.#.#.#.#",
        "#.#.#.#.#",
        "#.#.#.#.#",
        "#.#.#.#.#",
        "#.#.B.#.#",
        "#.#...#.#",
        "#.#.R.#.#",
        "#.#####.#",
        "#.......#",
        "#########",
        "T..R...T.",
    )

    /** Endless defence on a spiral around the base; enemies the player has met join over time. */
    fun endless(progress: Int): LevelDef {
        val joins = listOf(B to 0, RU to 1, SW to 3, DR to 5, BR to 7, ME to 9, SH to 11, GH to 13, SA to 15, MO to 17)
        return LevelDef(
            ENDLESS_ID, L("Бесконечная оборона", "Endless defence"), G, SPIRAL,
            listOf(intArrayOf(0, -1, 0, 13, 8, 13, 8, 1, 2, 1, 2, 11, 6, 11, 6, 3, 4, 3, 4, 8)),
            startMoney = 320,
            plan = WavePlan(
                0, joins.filter { it.first.campaign <= max(progress, 4) },
                budget = 9f, growth = 0.4f, hp = 1.1f, hpGrowth = 1.12f, seed = 100, bossEvery = 10,
            ),
        )
    }

    /** Today's challenge: one of the maps the player has reached, with the enemies they know. */
    fun daily(day: Int, progress: Int): LevelDef {
        val reached = all.take(progress.coerceIn(1, all.size))
        val rng = Random(day * 104_723 + 17)
        val source = reached[rng.nextInt(reached.size)]
        val known = EnemyType.entries.filter { !it.boss && it.campaign <= progress }.sortedBy { it.campaign }
        val intro = known.mapIndexed { i, type -> type to if (i < 2) 0 else min(6, 1 + (i - 2) * 5 / max(1, known.size - 2)) }
        val mods = Mod.forDay(day)
        val bossesKnown = progress >= EnemyType.HELI.campaign
        val bosses = when {
            !bossesKnown -> emptyMap()
            Mod.BOSS_RUSH in mods -> emptyMap()
            else -> mapOf(9 to listOf(if (rng.nextBoolean()) APC else HELI), 19 to listOf(APC, HELI))
        }
        val p = progress.coerceIn(1, all.size)
        return LevelDef(
            DAILY_ID, L("Задание дня", "Daily challenge"), source.biome, source.ascii, source.paths,
            startMoney = 260 + 8 * p,
            plan = WavePlan(
                20, intro, budget = 8.5f + 0.3f * p, growth = 0.38f, hp = 1f + 0.035f * p, hpGrowth = 1.085f,
                bosses = bosses, seed = 1000 + day % 100_000, bossEvery = if (Mod.BOSS_RUSH in mods && bossesKnown) 5 else 0,
            ),
        )
    }

    fun byId(id: Int, progress: Int = all.size): LevelDef? =
        if (id == ENDLESS_ID) endless(progress) else all.firstOrNull { it.id == id }
}
