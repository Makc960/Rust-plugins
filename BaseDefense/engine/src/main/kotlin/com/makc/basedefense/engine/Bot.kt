package com.makc.basedefense.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A simple player: spends money on whatever buys the most effective damage per
 * coin where the road is densest, looking a few waves ahead (armor, air, ghosts,
 * bosses), and fires its abilities when a crowd or a boss shows up.
 * It plays the menu demo and proves in tests that every level can be won.
 */
class Bot(
    private val world: World,
    private val upgrades: Boolean = true,
    types: List<TowerType> = TowerType.entries,
    private val abilities: Boolean = true,
) {
    private val types = types.filter { it in world.rules.towers }
    private var think = 0f
    private val samplesX: FloatArray
    private val samplesY: FloatArray
    private val cover = HashMap<Int, Int>()

    /** Health share and traits of the enemies to come. */
    private class Foe(val share: Float, val hp: Float, val armor: Float, val air: Boolean, val stealth: Boolean, val boss: Boolean)

    private var foes: List<Foe> = emptyList()
    private var hasRadar = false

    /** How badly the defence lacks damage against fliers and walkers; 1 is balanced. */
    private var needAir = 1f
    private var needGround = 1f

    private companion object {
        /** Wait for the dream buy when it is this much better than the affordable one. */
        const val SAVE_RATIO = 1.25f
        const val DIVERSITY = 0.9f
        const val MINE_REACH = 1.0f
        const val SPIKE_REACH = 1.2f

        /** Past this many road samples in range a tower rarely runs out of targets anyway. */
        const val SATURATION = 40f
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
            repeat(8) { act() }
            world.callWave()
            return
        }
        think -= dt
        if (think > 0f) return
        think = 0.5f
        if (abilities) useAbilities()
        for (k in 0 until 4) if (!act()) break
    }

    private fun useAbilities() {
        if (world.lives <= world.maxLives * 0.6f) world.repair()
        if (world.abilityReady(Ability.FREEZE)) {
            val close = world.enemies.count { it.alive && it.remaining < 5f }
            val boss = world.enemies.any { it.alive && it.type.boss && it.remaining < it.path.length * 0.45f }
            if (close >= 6 || boss) world.freeze()
        }
        if (world.abilityReady(Ability.AIRSTRIKE)) {
            var best: Enemy? = null
            var bestWeight = 0f
            for (e in world.enemies) {
                if (!e.alive || e.type.air || e.x < 0f || e.y < 0f || e.x > COLS || e.y > ROWS) continue
                var weight = 0f
                for (o in world.enemies) {
                    if (!o.alive || o.type.air) continue
                    val dx = o.x - e.x
                    val dy = o.y - e.y
                    if (dx * dx + dy * dy <= 1.2f) weight += o.hp + o.shield
                }
                if (weight > bestWeight) {
                    bestWeight = weight
                    best = e
                }
            }
            val e = best ?: return
            if (bestWeight >= world.airstrikeDamage * 2.5f || (e.type.boss && e.remaining < e.path.length * 0.6f)) {
                // The bombs land about a second later: aim where the crowd will be.
                val lead = world.speedOf(e) * 1.0f
                world.airstrike(e.x + e.dirX * lead, e.y + e.dirY * lead)
            }
        }
    }

    private fun act(): Boolean {
        if (world.over) return false
        foes = lookAhead()
        hasRadar = world.towers.any { it.type == TowerType.RADAR }
        updateNeeds()

        var bestScore = 0f
        var bestAction: (() -> Unit)? = null
        // The best buy if money were no object: worth saving for when it beats what we can afford.
        var dream = 0f
        val canBuildMore = !world.towerLimitReached
        for (type in types) {
            if (!canBuildMore) break
            if (world.quarryLimitReached(type)) continue
            val cost = world.buildCost(type)
            val (cell, value) = bestCell(type)
            if (cell < 0 || value <= 0f) continue
            val score = value / cost
            if (cost > world.money) {
                dream = max(dream, score)
                continue
            }
            if (score > bestScore) {
                bestScore = score
                val c = cell % COLS
                val r = cell / COLS
                bestAction = { world.build(type, c, r) }
            }
        }
        // A rock or a tree in the best spot can be worth clearing.
        if (canBuildMore) {
            val clearCost = world.clearCost()
            for (type in types) {
                if (type.role != Role.ATTACK) continue
                val cost = world.buildCost(type) + clearCost
                if (cost > world.money) continue
                var bestObstacle = -1
                var bestValue = 0f
                for (r in 0 until ROWS) for (c in 0 until COLS) {
                    if (!world.map.obstacle(c, r)) continue
                    val v = power(type, type.levels[0], null) * reachOf(type, c, r, type.levels[0].range)
                    if (v > bestValue) {
                        bestValue = v
                        bestObstacle = r * COLS + c
                    }
                }
                val score = bestValue / cost * 0.9f
                if (bestObstacle >= 0 && score > bestScore) {
                    bestScore = score
                    bestAction = { world.clear(bestObstacle % COLS, bestObstacle / COLS) }
                }
            }
        }
        if (upgrades) {
            for (t in world.towers) {
                if (t.type !in types) continue
                if (t.canUpgrade) {
                    val cost = world.upgradeCost(t)
                    val score = gain(t, t.type.levels[t.level + 1], null) / cost * 1.1f
                    if (cost > world.money) {
                        dream = max(dream, score)
                    } else if (score > bestScore) {
                        bestScore = score
                        bestAction = { world.upgrade(t) }
                    }
                } else if (t.atFork && world.rules.branches) {
                    for (b in t.type.branches) {
                        val cost = world.branchCost(b)
                        val score = gain(t, b.stats, b) / cost * 1.1f
                        if (cost > world.money) {
                            dream = max(dream, score)
                        } else if (score > bestScore) {
                            bestScore = score
                            bestAction = { world.branch(t, b) }
                        }
                    }
                }
            }
        }
        if (dream > bestScore * SAVE_RATIO) return false
        val action = bestAction ?: return false
        action()
        return true
    }

    /** Compares what share of the enemies fly with what share of our damage can reach them. */
    private fun updateNeeds() {
        needAir = 1f
        needGround = 1f
        val airShare = foes.filter { it.air }.sumOf { it.share.toDouble() }.toFloat()
        if (airShare <= 0.01f || airShare >= 0.99f) return
        var capAir = 0f
        var capGround = 0f
        for (t in world.towers) {
            if (t.type.role == Role.SUPPORT || t.type.role == Role.ECONOMY) continue
            val r = reachOf(t.type, t.col, t.row, t.stats.range)
            capAir += power(t.type, t.stats, t.branch, varied = false, only = true) * r
            capGround += power(t.type, t.stats, t.branch, varied = false, only = false) * r
        }
        val total = capAir + capGround
        if (total <= 0f) return
        needAir = (airShare / max(0.04f, capAir / total)).coerceIn(0.5f, 3.5f)
        needGround = ((1f - airShare) / max(0.04f, capGround / total)).coerceIn(0.5f, 3.5f)
    }

    /** How much better the tower gets with [next] stats, in the units of [bestCell]. */
    private fun gain(t: Tower, next: TowerLevel, branch: Branch?): Float {
        if (t.type == TowerType.QUARRY) return income(next, branch) - income(t.stats, t.branch)
        if (t.type == TowerType.RADAR) return radarValue(t.col, t.row, next, branch, t) - radarValue(t.col, t.row, t.stats, t.branch, t)
        val now = power(t.type, t.stats, t.branch) * reachOf(t.type, t.col, t.row, t.stats.range)
        val then = power(t.type, next, branch) * reachOf(t.type, t.col, t.row, next.range)
        return then - now
    }

    private fun bestCell(type: TowerType): Pair<Int, Float> {
        val s = type.levels[0]
        if (type == TowerType.QUARRY) {
            for (r in 0 until ROWS) for (c in 0 until COLS) {
                if (world.canPlace(type, c, r) && coverage(c, r, 2.5f) == 0) return r * COLS + c to income(s, null)
            }
            for (r in 0 until ROWS) for (c in 0 until COLS) if (world.canPlace(type, c, r)) return r * COLS + c to income(s, null)
            return -1 to 0f
        }
        var best = -1
        var bestValue = 0f
        val p = if (type == TowerType.RADAR) 1f else power(type, s, null)
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (!world.canPlace(type, c, r)) continue
            val v = if (type == TowerType.RADAR) radarValue(c, r, s, null, null) else p * reachOf(type, c, r, s.range)
            if (v > bestValue) {
                bestValue = v
                best = r * COLS + c
            }
        }
        return best to bestValue
    }

    private fun reachOf(type: TowerType, c: Int, r: Int, range: Float): Float = when (type) {
        TowerType.MINES -> saturate(coverage(c, r, if (range < 0.8f) SPIKE_REACH else MINE_REACH) * 1.5f)
        else -> saturate(coverage(c, r, range).toFloat())
    }

    private fun saturate(c: Float): Float = c / (1f + c / SATURATION)

    /** Coins a quarry brings over the rest of the level, in the same units as combat value. */
    private fun income(s: TowerLevel, branch: Branch?): Float {
        // A coin later is worth less than a gun now, and nothing at all before there is a defence.
        if (world.towers.count { it.type.role == Role.ATTACK } < 4) return 0f
        val left = if (world.endless) 20 else world.totalWaves - world.wavesStarted
        var perWave = s.damage
        if (branch == Branch.BANK) perWave += min(s.extra2, world.money * s.extra)
        return perWave * left * 2.2f
    }

    /** What a radar at (c, r) adds: revealed ghosts and a damage bonus for the towers around it. */
    private fun radarValue(c: Int, r: Int, s: TowerLevel, branch: Branch?, self: Tower?): Float {
        val ghosts = foes.filter { it.stealth }.sumOf { it.share.toDouble() }.toFloat()
        val reach = s.range
        // The first radar matters most: without it ghosts walk past every gun.
        val ghostGain = if (hasRadar && self == null) ghosts * 0.4f else ghosts * 3f
        var sum = 0f
        for (t in world.towers) {
            if (t === self || t.type.role != Role.ATTACK) continue
            val dx = t.x - (c + 0.5f)
            val dy = t.y - (r + 0.5f)
            if (dx * dx + dy * dy > reach * reach) continue
            val value = power(t.type, t.stats, t.branch, varied = false) * reachOf(t.type, t.col, t.row, t.stats.range)
            val auraGain = max(0f, s.extra - t.aura)
            sum += value * (auraGain + ghostGain + (if (branch == Branch.COMMAND) s.extra2 else 0f))
        }
        if (branch == Branch.JAMMER) sum += saturate(coverage(c, r, reach).toFloat()) * 10f
        val same = world.towers.count { it.type == TowerType.RADAR && it !== self }
        return sum * DIVERSITY.pow(same * 3)
    }

    /** The next three waves plus what is already on the road, weighted by health. */
    private fun lookAhead(): List<Foe> {
        val weights = HashMap<EnemyType, Float>()
        val counts = HashMap<EnemyType, Float>()
        for (k in 0 until 3) {
            val w = world.wave(world.wavesStarted + k) ?: break
            val decay = 1f / (1 + k)
            for (g in w.groups) {
                weights[g.type] = (weights[g.type] ?: 0f) + g.count * g.type.hp * (1f + g.type.shield) * g.hpMul * world.rules.hpMul * decay
                counts[g.type] = (counts[g.type] ?: 0f) + g.count * decay
            }
        }
        for (e in world.enemies) {
            weights[e.type] = (weights[e.type] ?: 0f) + e.hp + e.shield
            counts[e.type] = (counts[e.type] ?: 0f) + 1f
        }
        val total = weights.values.sum()
        if (total <= 0f) return listOf(Foe(1f, 100f, 0f, false, false, false))
        return weights.map { (type, v) -> Foe(v / total, v / max(1f, counts[type] ?: 1f), type.armor, type.air, type.stealth, type.boss) }
    }

    /**
     * Damage per hit after armor, averaged over the coming enemies the tower can reach;
     * [only] limits it to fliers (true) or walkers (false), otherwise each side is weighted by need.
     */
    private fun hit(type: TowerType, dmg: Float, armorPierce: Float = 0f, aimed: Boolean = true, bossMul: Float = 1f, only: Boolean? = null): Float {
        var sum = 0f
        for (f in foes) {
            if (if (f.air) !type.hitsAir else !type.hitsGround) continue
            if (only != null && f.air != only) continue
            if (f.stealth && aimed && !hasRadar) continue
            val d = dmg * (if (f.boss) bossMul else 1f)
            val need = if (only != null) 1f else if (f.air) needAir else needGround
            // A shot cannot do more than kill: big guns waste most of it on small fry.
            sum += f.share * need * min(f.hp, max(d * 0.2f, d - f.armor * (1f - armorPierce)))
        }
        return sum
    }

    private fun reach(type: TowerType, only: Boolean?): Float = foes.sumOf { f ->
        val ok = (if (f.air) type.hitsAir else type.hitsGround) && (only == null || f.air == only)
        val need = if (only != null) 1f else if (f.air) needAir else needGround
        (if (ok) f.share * need else 0f).toDouble()
    }.toFloat()

    /** Rough effective damage per second against the coming mix of enemies. */
    private fun power(type: TowerType, s: TowerLevel, branch: Branch?, varied: Boolean = true, only: Boolean? = null): Float {
        // Like a person, prefer a mix: every tower of the same kind is worth a bit less.
        val variety = if (varied) DIVERSITY.pow(world.towers.count { it.type == type }) else 1f
        fun h(dmg: Float, armorPierce: Float = 0f, aimed: Boolean = true, bossMul: Float = 1f) = hit(type, dmg, armorPierce, aimed, bossMul, only)
        val v = when (type) {
            TowerType.TURRET -> when (branch) {
                Branch.MINIGUN -> s.rate * 0.8f * h(s.damage)
                Branch.PIERCER -> s.rate * h(s.damage, armorPierce = 1f)
                else -> s.rate * h(s.damage)
            }
            TowerType.SHOTGUN -> when (branch) {
                Branch.SLUG -> s.rate * h(s.damage, aimed = false) * 1.7f
                Branch.ASSAULT -> s.rate * s.extra * h(s.damage, aimed = false) * 0.75f
                else -> s.rate * s.extra * h(s.damage, aimed = false) * 0.7f
            }
            TowerType.FLAMER -> when (branch) {
                Branch.CRYO -> s.damage * 1.6f * reach(type, only) * 1.6f
                Branch.NAPALM -> (s.damage * (1f + World.AFTERBURN) + World.NAPALM_DPS) * 1.6f * reach(type, only)
                else -> s.damage * (1f + World.AFTERBURN) * 1.6f * reach(type, only)
            }
            TowerType.ROCKET -> when (branch) {
                Branch.CLUSTER -> s.rate * (h(s.damage) * 1.5f + World.CLUSTER_BOMBLETS * h(s.extra2, aimed = false) * 0.35f)
                Branch.HEAVY -> s.rate * h(s.damage, bossMul = World.HEAVY_BOSS) * 1.8f
                else -> s.rate * h(s.damage) * 1.5f
            }
            TowerType.SAM -> when (branch) {
                Branch.SALVO -> s.rate * s.extra * h(s.damage) * 0.8f
                Branch.LONGBOW -> s.rate * h(s.damage, bossMul = World.LONGBOW_BOSS)
                else -> s.rate * h(s.damage)
            }
            TowerType.TESLA -> {
                val chain = (1f - World.TESLA_DECAY.pow(s.extra)) / (1f - World.TESLA_DECAY) * 0.75f
                s.rate * h(s.damage) * max(1f, chain) * (if (branch == Branch.STUN) 1.15f else 1f)
            }
            TowerType.SNIPER -> when (branch) {
                Branch.HEAVY_CAL -> s.rate * h(s.damage, armorPierce = 1f)
                Branch.MARKER -> s.rate * h(s.damage, armorPierce = 0.5f) * 1.2f
                else -> s.rate * h(s.damage, armorPierce = 0.5f)
            }
            TowerType.MINES -> when (branch) {
                Branch.SPIKES -> s.damage * reach(type, only) * 1.3f
                else -> s.rate * h(s.damage, aimed = false) * 1.8f
            }
            TowerType.RADAR, TowerType.QUARRY -> 0f
        }
        return variety * v
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
