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

/**
 * The grid of a level. Terrain comes from the ASCII picture: '.' grass you can
 * build on, '#' road, 'B' base, 'T' tree, 'R' rock, '~' water, '=' bridge.
 */
class GameMap(val level: LevelDef) {
    val paths = level.paths.map { Path(it) }
    private val terrain = Array(ROWS) { r -> CharArray(COLS) { c -> level.ascii[r][c] } }
    private val road = BooleanArray(COLS * ROWS)
    val baseCol: Int
    val baseRow: Int

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

    fun inside(c: Int, r: Int): Boolean = c in 0 until COLS && r in 0 until ROWS

    fun isRoad(c: Int, r: Int): Boolean = inside(c, r) && road[r * COLS + c]

    fun terrain(c: Int, r: Int): Char = terrain[r][c]

    fun buildable(c: Int, r: Int): Boolean = inside(c, r) && !road[r * COLS + c] && terrain[r][c] == '.'

    val buildableCount: Int get() = (0 until ROWS).sumOf { r -> (0 until COLS).count { c -> buildable(c, r) } }
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

    val airShare: Float = run {
        var air = 0f
        var all = 0f
        for (g in groups) {
            val t = g.count * g.type.hp * g.hpMul
            all += t
            if (g.type.air) air += t
        }
        if (all > 0f) air / all else 0f
    }

    val armorShare: Float = run {
        var armored = 0f
        var all = 0f
        for (g in groups) {
            val t = g.count * g.type.hp * g.hpMul
            all += t
            if (g.type.armor >= 4f) armored += t
        }
        if (all > 0f) armored / all else 0f
    }
}

/**
 * How a level's waves are built. Each enemy type joins at its wave in [intro];
 * [count] 0 means endless.
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
        val others = available.filter { it != fresh }.shuffled(rng)
        val extra = if (others.isEmpty()) 0 else 1 + rng.nextInt(min(2, others.size))
        val left = 1f - shares.sumOf { it.second.toDouble() }.toFloat()
        val weights = FloatArray(extra) { 0.4f + rng.nextFloat() * 0.6f }
        val sum = weights.sum()
        for (k in 0 until extra) shares += others[k] to left * weights[k] / sum

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

        val bosses = plan.bosses[i] ?: endlessBosses(plan, i)
        for ((k, boss) in bosses.withIndex()) {
            val bossHp = hpMul * BOSS_HP * (if (plan.count == 0) 1f + 0.15f * (i / 10) else 1f)
            groups += Group(boss, 1, 1f, delay * 0.5f + k * 4f, if (paths <= 1) 0 else k % paths, bossHp)
        }
        return Wave(i, groups, fresh, bosses)
    }

    private fun endlessBosses(plan: WavePlan, i: Int): List<EnemyType> {
        if (plan.count != 0 || i % 10 != 9) return emptyList()
        return when ((i / 10) % 3) {
            0 -> listOf(EnemyType.APC)
            1 -> listOf(EnemyType.HELI)
            else -> listOf(EnemyType.APC, EnemyType.HELI)
        }
    }
}

class LevelDef(
    val id: Int,
    val name: String,
    val ascii: List<String>,
    val paths: List<IntArray>,
    val startMoney: Int,
    val lives: Int,
    val plan: WavePlan,
) {
    val endless: Boolean get() = plan.count == 0
}

object Levels {
    private val B = EnemyType.BANDIT
    private val RU = EnemyType.RUNNER
    private val SW = EnemyType.SWARM
    private val BR = EnemyType.BRUTE
    private val ME = EnemyType.MEDIC
    private val DR = EnemyType.DRONE
    private val APC = EnemyType.APC
    private val HELI = EnemyType.HELI

    val all: List<LevelDef> = listOf(
        LevelDef(
            1, "Побережье",
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
            startMoney = 160, lives = 20,
            plan = WavePlan(8, listOf(B to 0, RU to 3), budget = 7f, growth = 0.32f, hp = 1f, hpGrowth = 1.1f, seed = 1),
        ),
        LevelDef(
            2, "Лес",
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
            startMoney = 170, lives = 20,
            plan = WavePlan(10, listOf(B to 0, RU to 1, SW to 4), budget = 8f, growth = 0.33f, hp = 1.05f, hpGrowth = 1.11f, seed = 2),
        ),
        LevelDef(
            3, "Каньон",
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
            startMoney = 200, lives = 20,
            plan = WavePlan(12, listOf(B to 0, RU to 0, SW to 2, BR to 4), budget = 9f, growth = 0.34f, hp = 1.1f, hpGrowth = 1.13f, seed = 3),
        ),
        LevelDef(
            4, "Радтаун",
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
            startMoney = 220, lives = 20,
            plan = WavePlan(12, listOf(B to 0, RU to 0, SW to 1, DR to 3, BR to 5, ME to 7), budget = 9.5f, growth = 0.35f, hp = 1.15f, hpGrowth = 1.125f, seed = 4),
        ),
        LevelDef(
            5, "Развилка",
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
            startMoney = 260, lives = 20,
            plan = WavePlan(14, listOf(B to 0, RU to 0, SW to 0, BR to 3, DR to 5, ME to 7), budget = 10f, growth = 0.36f, hp = 1.2f, hpGrowth = 1.115f, seed = 5),
        ),
        LevelDef(
            6, "Мост",
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
            startMoney = 260, lives = 20,
            plan = WavePlan(
                14, listOf(B to 0, RU to 0, SW to 0, BR to 2, ME to 4, DR to 6), budget = 10.5f, growth = 0.37f, hp = 1.25f, hpGrowth = 1.11f,
                bosses = mapOf(13 to listOf(APC)), seed = 6,
            ),
        ),
        LevelDef(
            7, "Аэродром",
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
            startMoney = 300, lives = 20,
            plan = WavePlan(
                15, listOf(B to 0, RU to 0, DR to 1, SW to 2, BR to 4, ME to 6), budget = 11f, growth = 0.38f, hp = 1.3f, hpGrowth = 1.125f,
                bosses = mapOf(14 to listOf(HELI)), seed = 7,
            ),
        ),
        LevelDef(
            8, "Последний рубеж",
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
            startMoney = 340, lives = 20,
            plan = WavePlan(
                18, listOf(B to 0, RU to 0, SW to 0, BR to 1, DR to 2, ME to 4), budget = 12f, growth = 0.4f, hp = 1.35f, hpGrowth = 1.09f,
                bosses = mapOf(9 to listOf(APC), 17 to listOf(HELI, APC)), seed = 8,
            ),
        ),
    )

    /** Endless defence on a spiral around the base. */
    val endless = LevelDef(
        100, "Бесконечная оборона",
        listOf(
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
        ),
        listOf(intArrayOf(0, -1, 0, 13, 8, 13, 8, 1, 2, 1, 2, 11, 6, 11, 6, 3, 4, 3, 4, 8)),
        startMoney = 320, lives = 20,
        plan = WavePlan(
            0, listOf(B to 0, RU to 1, SW to 3, DR to 5, BR to 7, ME to 9), budget = 9f, growth = 0.4f, hp = 1.1f, hpGrowth = 1.12f, seed = 100,
        ),
    )

    fun byId(id: Int): LevelDef? = if (id == endless.id) endless else all.firstOrNull { it.id == id }
}
