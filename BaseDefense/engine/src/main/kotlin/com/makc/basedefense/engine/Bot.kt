package com.makc.basedefense.engine

import kotlin.math.max
import kotlin.math.pow

/**
 * A simple player: spends money on whatever buys the most effective damage per
 * coin where the road is densest, looking a few waves ahead (armor, air, bosses).
 * It plays the menu demo and proves in tests that every level can be won.
 */
class Bot(private val world: World, private val upgrades: Boolean = true, private val types: List<TowerType> = TowerType.entries) {
    private var think = 0f
    private val samplesX: FloatArray
    private val samplesY: FloatArray
    private val cover = HashMap<Int, Int>()

    /** Health share, armor and flight of the enemies to come. */
    private class Foe(val share: Float, val armor: Float, val air: Boolean)

    private var foes: List<Foe> = emptyList()

    private companion object {
        /** Wait for the dream buy when it is this much better than the affordable one. */
        const val SAVE_RATIO = 1.25f
        const val DIVERSITY = 0.9f
    }

    init {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val p = FloatArray(4)
        for (path in world.map.paths) {
            var d = 0f
            while (d <= path.length) {
                path.pointAt(d, p)
                if (p[0] in 0f..COLS.toFloat() && p[1] in 0f..ROWS.toFloat()) {
                    xs += p[0]
                    ys += p[1]
                }
                d += 0.25f
            }
        }
        samplesX = xs.toFloatArray()
        samplesY = ys.toFloatArray()
    }

    fun update(dt: Float) {
        if (world.phase == Phase.READY) {
            repeat(6) { act() }
            world.callWave()
            return
        }
        think -= dt
        if (think > 0f) return
        think = 0.5f
        for (k in 0 until 4) if (!act()) break
    }

    private fun act(): Boolean {
        if (world.phase == Phase.WON || world.phase == Phase.LOST) return false
        foes = lookAhead()

        var bestScore = 0f
        var bestAction: (() -> Unit)? = null
        // The best buy if money were no object: worth saving for when it beats what we can afford.
        var dream = 0f
        for (type in types) {
            val cost = type.levels[0].cost
            if (cost > world.money) {
                val (_, cv) = bestCell(type)
                dream = max(dream, power(type, 0) * cv / cost)
                continue
            }
            val (bestCell, bestCover) = bestCell(type)
            if (bestCell < 0) continue
            val score = power(type, 0) * bestCover / cost
            if (score > bestScore) {
                bestScore = score
                val c = bestCell % COLS
                val r = bestCell / COLS
                bestAction = { world.build(type, c, r) }
            }
        }
        if (upgrades) {
            for (t in world.towers) {
                if (t.maxed) continue
                val now = power(t.type, t.level) * coverage(t.col, t.row, t.stats.range)
                val next = power(t.type, t.level + 1) * coverage(t.col, t.row, t.type.levels[t.level + 1].range)
                val score = (next - now) / t.upgradeCost * 1.1f
                if (t.upgradeCost > world.money) {
                    dream = max(dream, score)
                    continue
                }
                if (score > bestScore) {
                    bestScore = score
                    bestAction = { world.upgrade(t) }
                }
            }
        }
        if (dream > bestScore * SAVE_RATIO) return false
        val action = bestAction ?: return false
        action()
        return true
    }

    private fun bestCell(type: TowerType): Pair<Int, Int> {
        var bestCell = -1
        var bestCover = 0
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (!world.canPlace(c, r)) continue
            val cv = coverage(c, r, type.levels[0].range)
            if (cv > bestCover) {
                bestCover = cv
                bestCell = r * COLS + c
            }
        }
        return bestCell to bestCover
    }

    /** The next three waves plus what is already on the road, weighted by health. */
    private fun lookAhead(): List<Foe> {
        val weights = HashMap<EnemyType, Float>()
        for (k in 0 until 3) {
            val w = world.wave(world.wavesStarted + k) ?: break
            val decay = 1f / (1 + k)
            for (g in w.groups) weights[g.type] = (weights[g.type] ?: 0f) + g.count * g.type.hp * g.hpMul * decay
        }
        for (e in world.enemies) weights[e.type] = (weights[e.type] ?: 0f) + e.hp
        val total = weights.values.sum()
        if (total <= 0f) return listOf(Foe(1f, 0f, false))
        return weights.map { (type, v) -> Foe(v / total, type.armor, type.air) }
    }

    /** Damage per hit after armor, averaged over the coming enemies the tower can reach. */
    private fun hit(type: TowerType, dmg: Float): Float {
        var sum = 0f
        for (f in foes) {
            if (if (f.air) !type.hitsAir else !type.hitsGround) continue
            sum += f.share * max(dmg * 0.2f, dmg - f.armor)
        }
        return sum
    }

    private fun reach(type: TowerType): Float = foes.sumOf { f ->
        (if (if (f.air) type.hitsAir else type.hitsGround) f.share else 0f).toDouble()
    }.toFloat()

    /** Rough effective damage per second against the coming mix of enemies. */
    private fun power(type: TowerType, level: Int): Float {
        val s = type.levels[level]
        // Like a person, prefer a mix: every tower of the same kind is worth a bit less.
        val same = world.towers.count { it.type == type }
        val variety = DIVERSITY.pow(same)
        return variety * when (type) {
            TowerType.TURRET -> s.rate * hit(type, s.damage)
            TowerType.SHOTGUN -> s.rate * s.extra * hit(type, s.damage) * 0.7f
            TowerType.FLAMER -> s.damage * (1f + World.AFTERBURN) * 1.6f * reach(type)
            TowerType.ROCKET -> s.rate * hit(type, s.damage) * 1.5f
            TowerType.SAM -> s.rate * hit(type, s.damage)
            TowerType.TESLA -> s.rate * hit(type, s.damage) * (1f + 0.6f * (s.extra - 1f)) * 0.9f
        }
    }

    private fun coverage(c: Int, r: Int, range: Float): Int {
        val key = (r * COLS + c) * 1000 + (range * 100).toInt()
        return cover.getOrPut(key) {
            val x = c + 0.5f
            val y = r + 0.5f
            val r2 = range * range
            var n = 0
            for (i in samplesX.indices) {
                val dx = samplesX[i] - x
                val dy = samplesY[i] - y
                if (dx * dx + dy * dy <= r2) n++
            }
            n
        }
    }
}
