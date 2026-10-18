package com.makc.basedefense.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class Phase { READY, RUNNING, WON, LOST }

class Enemy(
    val type: EnemyType,
    val path: Path,
    val pathIndex: Int,
    val hpMul: Float,
    val wave: Int,
    val lane: Float,
    val reward: Int = type.reward,
) {
    val maxHp = type.hp * hpMul
    var hp = maxHp
    var dist = 0f
    var x = 0f
    var y = 0f
    var dirX = 0f
    var dirY = 1f
    var alive = true
    var slowTimer = 0f
    var slowAmount = 0f
    var burnTimer = 0f
    var burnDps = 0f
    var hitFlash = 0f
    var healTimer = 1f
    var healPulse = 0f
    var smoke = 0f

    val remaining: Float get() = path.length - dist
    val speed: Float get() = type.speed * (if (slowTimer > 0f) 1f - slowAmount else 1f)
}

class Tower(val type: TowerType, val col: Int, val row: Int) {
    val x = col + 0.5f
    val y = row + 0.5f
    var level = 0
    var invested = type.levels[0].cost
    var cooldown = 0f

    /** Degrees, 0 = right, 90 = down. */
    var angle = -90f
    var mode = TargetMode.FIRST
    var kills = 0
    var damageDealt = 0f
    var flash = 0f
    var burning = 0f
    var tube = 0

    val stats: TowerLevel get() = type.levels[level]
    val maxed: Boolean get() = level >= type.levels.size - 1
    val upgradeCost: Int get() = if (maxed) 0 else type.levels[level + 1].cost
    val sellValue: Int get() = (invested * SELL_SHARE).toInt()

    companion object {
        const val SELL_SHARE = 0.7f
    }
}

enum class Shot { BULLET, ROCKET, MISSILE }

class Projectile(
    val kind: Shot,
    var x: Float,
    var y: Float,
    var target: Enemy?,
    val damage: Float,
    val speed: Float,
    val splash: Float,
    val source: Tower,
) {
    var tx = target?.x ?: x
    var ty = target?.y ?: y
    var alive = true
    var trail = 0f
    var angle = 0f
}

class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float,
    val maxLife: Float,
    var size: Float,
    val color: Int,
    val grow: Float,
)

class FloatText(var x: Float, var y: Float, val text: String, val color: Int, var life: Float, val big: Boolean = false)

class Bolt(val xs: FloatArray, val ys: FloatArray, var life: Float)

class Tracer(val x1: Float, val y1: Float, val x2: Float, val y2: Float, var life: Float)

class Ring(val x: Float, val y: Float, val maxR: Float, var life: Float, val maxLife: Float, val color: Int)

private class ActiveGroup(val group: Group, val wave: Int) {
    var timer = group.delay
    var spawned = 0
}

/**
 * One game of one level: everything that moves, fires and pays.
 * [step] advances it by a fixed time slice; nothing here knows about screens.
 */
class World(val level: LevelDef, private val sound: (Sfx, Float) -> Unit = { _, _ -> }) {
    val map = GameMap(level)
    var money = level.startMoney
    var lives = level.lives
    val maxLives: Int get() = level.lives
    var phase = Phase.READY
        private set
    var wavesStarted = 0
        private set
    var countdown = -1f
        private set
    var time = 0f
        private set
    var kills = 0
        private set
    var leaked = 0
        private set

    val enemies = ArrayList<Enemy>()
    val towers = ArrayList<Tower>()
    val projectiles = ArrayList<Projectile>()
    val particles = ArrayList<Particle>()
    val texts = ArrayList<FloatText>()
    val bolts = ArrayList<Bolt>()
    val tracers = ArrayList<Tracer>()
    val rings = ArrayList<Ring>()

    var baseFlash = 0f
        private set
    var shake = 0f
        private set
    var banner: String? = null
        private set
    var bannerSub: String? = null
        private set
    var bannerTimer = 0f
        private set

    /** Called when an enemy reaches the base, for vibration. */
    var onBaseHit: (() -> Unit)? = null

    private val active = ArrayList<ActiveGroup>()
    private val incoming = ArrayList<Enemy>()
    private val remaining = HashMap<Int, Int>()
    private val waveCache = HashMap<Int, Wave>()
    private val rng = Random(level.id * 7919 + 1)
    private val fx = Random(level.id * 31 + 7)
    private val tmp = FloatArray(4)
    private val chain = ArrayList<Enemy>()

    val endless: Boolean get() = level.endless
    val totalWaves: Int get() = level.plan.count

    fun wave(i: Int): Wave? {
        if (!endless && i >= totalWaves) return null
        return waveCache.getOrPut(i) { WaveGen.generate(level.plan, i, map.paths.size) }
    }

    val nextWave: Wave? get() = wave(wavesStarted)

    val spawning: Boolean get() = active.isNotEmpty()

    val canCallWave: Boolean
        get() = nextWave != null && (phase == Phase.READY || (phase == Phase.RUNNING && countdown >= 0f))

    /** Money for calling the next wave before its timer runs out. */
    val earlyBonus: Int
        get() = if (phase == Phase.RUNNING && countdown > 0f) ceil(countdown).toInt() * EARLY_BONUS_PER_SECOND else 0

    fun callWave() {
        if (!canCallWave) return
        val bonus = earlyBonus
        if (bonus > 0) {
            money += bonus
            texts += FloatText(map.baseCol + 0.5f, map.baseRow - 0.2f, "+$bonus", GOLD, 1.2f)
        }
        startWave()
    }

    private fun startWave() {
        val w = nextWave ?: return
        phase = Phase.RUNNING
        countdown = -1f
        remaining[w.index] = w.total
        for (g in w.groups) active += ActiveGroup(g, w.index)
        wavesStarted++
        when {
            w.bosses.isNotEmpty() -> {
                banner = "БОСС: " + w.bosses.joinToString(" + ") { it.title.uppercase() }
                bannerSub = w.bosses.first().hint
            }
            w.fresh != null -> {
                banner = "НОВЫЙ ВРАГ: " + w.fresh.title.uppercase()
                bannerSub = w.fresh.hint
            }
            else -> {
                banner = "ВОЛНА ${w.index + 1}"
                bannerSub = null
            }
        }
        bannerTimer = if (bannerSub != null) 3.2f else 1.8f
        sound(Sfx.WAVE, 0.7f)
    }

    fun step(dt: Float) {
        time += dt
        if (phase == Phase.RUNNING) {
            spawn(dt)
            updateEnemies(dt)
            for (t in towers) updateTower(t, dt)
            updateProjectiles(dt)
            enemies.removeAll { !it.alive }
            enemies.addAll(incoming)
            incoming.clear()
            if (countdown >= 0f) {
                countdown -= dt
                if (countdown <= 0f) startWave()
            }
            if (lives <= 0) {
                lives = 0
                phase = Phase.LOST
                sound(Sfx.LOSE, 1f)
            } else if (active.isEmpty() && nextWave == null && enemies.isEmpty()) {
                phase = Phase.WON
                sound(Sfx.WIN, 1f)
            }
        }
        updateEffects(dt)
        if (bannerTimer > 0f) bannerTimer -= dt
        if (baseFlash > 0f) baseFlash -= dt
        if (shake > 0f) shake = max(0f, shake - dt)
    }

    // --- building -------------------------------------------------------------------------

    fun towerAt(c: Int, r: Int): Tower? = towers.firstOrNull { it.col == c && it.row == r }

    fun canPlace(c: Int, r: Int): Boolean =
        phase != Phase.WON && phase != Phase.LOST && map.buildable(c, r) && towerAt(c, r) == null

    fun canBuild(type: TowerType, c: Int, r: Int): Boolean = canPlace(c, r) && money >= type.levels[0].cost

    fun build(type: TowerType, c: Int, r: Int): Tower? {
        if (!canBuild(type, c, r)) return null
        money -= type.levels[0].cost
        val t = Tower(type, c, r)
        towers += t
        rings += Ring(t.x, t.y, 0.8f, 0.4f, 0.4f, Colors.alpha(WHITE, 0.8f))
        puff(t.x, t.y, 8, 0xFFB9A98A.toInt())
        sound(Sfx.BUILD, 0.7f)
        return t
    }

    fun upgrade(t: Tower): Boolean {
        if (t.maxed || money < t.upgradeCost || t !in towers) return false
        val cost = t.upgradeCost
        money -= cost
        t.invested += cost
        t.level++
        rings += Ring(t.x, t.y, 1.0f, 0.5f, 0.5f, GOLD)
        for (k in 0 until 10) {
            val a = fx.nextFloat() * 6.283f
            particles += Particle(t.x, t.y, cos(a) * 1.4f, sin(a) * 1.4f, 0.5f, 0.5f, 0.06f, GOLD, -0.05f)
        }
        sound(Sfx.UPGRADE, 0.7f)
        return true
    }

    fun sell(t: Tower) {
        if (t !in towers) return
        money += t.sellValue
        towers.remove(t)
        texts += FloatText(t.x, t.y - 0.2f, "+${t.sellValue}", GOLD, 1.0f)
        puff(t.x, t.y, 10, 0xFF8A8A8A.toInt())
        sound(Sfx.SELL, 0.7f)
    }

    // --- waves ----------------------------------------------------------------------------

    private fun spawn(dt: Float) {
        val iter = active.iterator()
        while (iter.hasNext()) {
            val a = iter.next()
            a.timer -= dt
            while (a.timer <= 0f && a.spawned < a.group.count) {
                val paths = map.paths.size
                val p = if (a.group.path >= 0) a.group.path % paths else (a.spawned + a.wave) % paths
                spawnEnemy(a.group.type, p, a.group.hpMul, a.wave, 0f, a.group.rewardMul)
                a.spawned++
                a.timer += max(0.05f, a.group.interval)
            }
            if (a.spawned >= a.group.count) iter.remove()
        }
        if (active.isEmpty() && countdown < 0f && nextWave != null) countdown = BETWEEN_WAVES
    }

    private fun spawnEnemy(type: EnemyType, pathIndex: Int, hpMul: Float, wave: Int, dist: Float, rewardMul: Float = 1f) {
        val spread = if (type == EnemyType.SWARM) 0.36f else if (type.boss) 0f else 0.18f
        val reward = max(1, (type.reward * rewardMul).roundToInt())
        val e = Enemy(type, map.paths[pathIndex], pathIndex, hpMul, wave, (fx.nextFloat() - 0.5f) * spread, reward)
        e.dist = dist
        place(e)
        incoming += e
    }

    private fun place(e: Enemy) {
        e.path.pointAt(e.dist, tmp)
        e.x = tmp[0]
        e.y = tmp[1]
        e.dirX = tmp[2]
        e.dirY = tmp[3]
    }

    private fun finish(e: Enemy) {
        val left = (remaining[e.wave] ?: 1) - 1
        remaining[e.wave] = left
        if (left == 0) {
            remaining.remove(e.wave)
            val bonus = WAVE_BONUS + WAVE_BONUS_STEP * e.wave
            money += bonus
            texts += FloatText(map.baseCol + 0.5f, map.baseRow - 0.6f, "Волна ${e.wave + 1} отбита  +$bonus", GOLD, 2.0f, big = true)
        }
    }

    // --- enemies --------------------------------------------------------------------------

    private fun updateEnemies(dt: Float) {
        for (e in enemies) {
            if (!e.alive) continue
            if (e.hitFlash > 0f) e.hitFlash -= dt
            if (e.healPulse > 0f) e.healPulse -= dt
            if (e.slowTimer > 0f) e.slowTimer -= dt
            if (e.burnTimer > 0f) {
                e.burnTimer -= dt
                damage(e, e.burnDps * dt, null, pierce = true)
                if (!e.alive) continue
                if (fx.nextFloat() < dt * 12f) flame(e.x, e.y, 1, 0.25f)
            }
            if (e.type == EnemyType.MEDIC) {
                e.healTimer -= dt
                if (e.healTimer <= 0f) {
                    e.healTimer = 1f
                    e.healPulse = 0.6f
                    rings += Ring(e.x, e.y, MEDIC_RADIUS, 0.6f, 0.6f, Colors.alpha(HEAL, 0.9f))
                    for (o in enemies) {
                        if (o === e || !o.alive || o.type.boss) continue
                        val dx = o.x - e.x
                        val dy = o.y - e.y
                        if (dx * dx + dy * dy <= MEDIC_RADIUS * MEDIC_RADIUS) o.hp = min(o.maxHp, o.hp + MEDIC_HEAL * e.hpMul)
                    }
                }
            }
            if (e.type.boss) {
                e.smoke -= dt
                if (e.smoke <= 0f && e.hp < e.maxHp * 0.5f) {
                    e.smoke = 0.12f
                    particles += Particle(e.x, e.y, (fx.nextFloat() - 0.5f) * 0.3f, -0.4f, 1.0f, 1.0f, 0.1f, 0xFF3C3C3C.toInt(), 0.25f)
                }
            }
            e.dist += e.speed * dt
            if (e.dist >= e.path.length) {
                reachBase(e)
                continue
            }
            place(e)
        }
    }

    private fun reachBase(e: Enemy) {
        e.alive = false
        lives -= e.type.damage
        leaked += e.type.damage
        baseFlash = 0.5f
        shake = 0.35f
        rings += Ring(map.baseCol + 0.5f, map.baseRow + 0.5f, 1.2f, 0.5f, 0.5f, Colors.alpha(RED, 0.9f))
        texts += FloatText(map.baseCol + 0.5f, map.baseRow - 0.1f, "-${e.type.damage}", RED, 1.0f)
        sound(Sfx.BASE_HIT, 0.9f)
        onBaseHit?.invoke()
        finish(e)
    }

    fun damage(e: Enemy, raw: Float, source: Tower?, pierce: Boolean = false) {
        if (!e.alive || raw <= 0f) return
        val dealt = if (pierce) raw else max(raw * 0.2f, raw - e.type.armor)
        e.hp -= dealt
        if (source != null) source.damageDealt += dealt
        if (!pierce) e.hitFlash = 0.07f
        if (e.hp <= 0f) kill(e, source)
    }

    private fun kill(e: Enemy, source: Tower?) {
        e.alive = false
        money += e.reward
        kills++
        if (source != null) source.kills++
        val n = if (e.type.boss) 30 else 8
        for (k in 0 until n) {
            val a = fx.nextFloat() * 6.283f
            val v = 0.6f + fx.nextFloat() * (if (e.type.boss) 2.5f else 1.4f)
            particles += Particle(e.x, e.y, cos(a) * v, sin(a) * v, 0.45f, 0.45f, e.type.radius * 0.28f, e.type.color, -0.1f)
        }
        texts += FloatText(e.x, e.y - 0.2f, "+${e.reward}", GOLD, 0.8f)
        sound(Sfx.COIN, 0.2f)
        if (e.type.boss) {
            explosion(e.x, e.y, 1.6f)
            shake = 0.5f
        }
        if (e.type == EnemyType.APC) {
            for (k in 0 until APC_CREW) spawnEnemy(EnemyType.BANDIT, e.pathIndex, e.hpMul, e.wave, max(0f, e.dist - 0.3f * k))
            remaining[e.wave] = (remaining[e.wave] ?: 0) + APC_CREW
        }
        finish(e)
    }

    // --- towers ---------------------------------------------------------------------------

    private fun canHit(t: TowerType, e: Enemy): Boolean = if (e.type.air) t.hitsAir else t.hitsGround

    private fun onMap(e: Enemy): Boolean = e.x >= 0f && e.y >= 0f && e.x <= COLS && e.y <= ROWS

    private fun findTarget(t: Tower, range: Float): Enemy? {
        var best: Enemy? = null
        var bestScore = 0f
        val r2 = range * range
        for (e in enemies) {
            if (!e.alive || !canHit(t.type, e) || !onMap(e)) continue
            val dx = e.x - t.x
            val dy = e.y - t.y
            val d2 = dx * dx + dy * dy
            if (d2 > r2) continue
            val score = when (t.mode) {
                TargetMode.FIRST -> -e.remaining
                TargetMode.LAST -> e.remaining
                TargetMode.STRONG -> e.hp
                TargetMode.CLOSE -> -d2
            }
            if (best == null || score > bestScore) {
                best = e
                bestScore = score
            }
        }
        return best
    }

    private fun updateTower(t: Tower, dt: Float) {
        if (t.cooldown > 0f) t.cooldown -= dt
        if (t.flash > 0f) t.flash -= dt
        if (t.burning > 0f) t.burning -= dt
        val s = t.stats
        val target = findTarget(t, s.range) ?: return
        val aim = atan2(target.y - t.y, target.x - t.x)
        t.angle = Math.toDegrees(aim.toDouble()).toFloat()
        if (t.cooldown > 0f) return
        val mx = t.x + cos(aim) * 0.36f
        val my = t.y + sin(aim) * 0.36f
        when (t.type) {
            TowerType.TURRET -> {
                projectiles += Projectile(Shot.BULLET, mx, my, target, s.damage, BULLET_SPEED, 0f, t)
                t.flash = 0.06f
                sound(Sfx.SHOOT, 0.25f)
            }
            TowerType.SHOTGUN -> {
                firePellets(t, s, aim)
                t.flash = 0.08f
                sound(Sfx.SHOTGUN, 0.45f)
            }
            TowerType.FLAMER -> {
                fireFlame(t, s, aim)
                t.burning = 0.15f
                sound(Sfx.FLAME, 0.3f)
            }
            TowerType.ROCKET -> {
                projectiles += Projectile(Shot.ROCKET, mx, my, target, s.damage, ROCKET_SPEED, s.extra, t)
                t.flash = 0.1f
                sound(Sfx.ROCKET, 0.45f)
            }
            TowerType.SAM -> {
                t.tube = (t.tube + 1) % 4
                val side = if (t.tube % 2 == 0) 0.12f else -0.12f
                val px = mx - sin(aim) * side
                val py = my + cos(aim) * side
                projectiles += Projectile(Shot.MISSILE, px, py, target, s.damage, MISSILE_SPEED, 0f, t)
                sound(Sfx.MISSILE, 0.4f)
            }
            TowerType.TESLA -> {
                fireTesla(t, s, target)
                sound(Sfx.ZAP, 0.4f)
            }
        }
        val period = if (t.type == TowerType.FLAMER) FLAME_TICK else 1f / s.rate
        t.cooldown = max(t.cooldown, 0f) + period
    }

    private fun firePellets(t: Tower, s: TowerLevel, aim: Float) {
        val pellets = s.extra.toInt()
        for (p in 0 until pellets) {
            val a = aim + (rng.nextFloat() - 0.5f) * SHOTGUN_SPREAD
            val dx = cos(a)
            val dy = sin(a)
            var hit: Enemy? = null
            var hitT = s.range
            for (e in enemies) {
                if (!e.alive || e.type.air) continue
                val ex = e.x - t.x
                val ey = e.y - t.y
                val proj = ex * dx + ey * dy
                if (proj < 0f || proj > hitT) continue
                val perp2 = ex * ex + ey * ey - proj * proj
                val rr = e.type.radius + 0.1f
                if (perp2 <= rr * rr) {
                    hit = e
                    hitT = proj
                }
            }
            tracers += Tracer(t.x + dx * 0.3f, t.y + dy * 0.3f, t.x + dx * hitT, t.y + dy * hitT, 0.09f)
            if (hit != null) damage(hit, s.damage, t)
        }
    }

    private fun fireFlame(t: Tower, s: TowerLevel, aim: Float) {
        val r2 = s.range * s.range
        for (e in enemies) {
            if (!e.alive || e.type.air) continue
            val dx = e.x - t.x
            val dy = e.y - t.y
            val d2 = dx * dx + dy * dy
            if (d2 > r2) continue
            var diff = atan2(dy, dx) - aim
            while (diff > 3.1416f) diff -= 6.2832f
            while (diff < -3.1416f) diff += 6.2832f
            if (abs(diff) > FLAME_CONE && d2 > 0.16f) continue
            damage(e, s.damage * FLAME_TICK, t, pierce = true)
            e.burnDps = max(e.burnDps, s.damage * AFTERBURN)
            e.burnTimer = max(e.burnTimer, s.extra)
        }
        val n = 3
        for (k in 0 until n) {
            val a = aim + (fx.nextFloat() - 0.5f) * FLAME_CONE * 1.6f
            val v = s.range * (1.6f + fx.nextFloat() * 0.8f)
            particles += Particle(
                t.x + cos(aim) * 0.35f, t.y + sin(aim) * 0.35f, cos(a) * v, sin(a) * v,
                0.42f, 0.42f, 0.09f, if (fx.nextBoolean()) 0xFFFFB13B.toInt() else 0xFFFF6A1A.toInt(), 0.35f,
            )
        }
    }

    private fun fireTesla(t: Tower, s: TowerLevel, target: Enemy) {
        chain.clear()
        val jumps = s.extra.toInt()
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        xs += t.x
        ys += t.y - 0.22f
        var cur: Enemy = target
        var dmg = s.damage
        for (k in 0 until jumps) {
            chain += cur
            xs += cur.x
            ys += cur.y
            damage(cur, dmg, t)
            cur.slowAmount = if (cur.type.boss) s.extra2 * 0.5f else s.extra2
            cur.slowTimer = SLOW_TIME
            dmg *= 0.8f
            var next: Enemy? = null
            var best = TESLA_JUMP * TESLA_JUMP
            for (e in enemies) {
                if (!e.alive || e in chain || !onMap(e)) continue
                val dx = e.x - cur.x
                val dy = e.y - cur.y
                val d2 = dx * dx + dy * dy
                if (d2 < best) {
                    best = d2
                    next = e
                }
            }
            cur = next ?: break
        }
        // Jagged bolt between the hit points.
        val outX = ArrayList<Float>()
        val outY = ArrayList<Float>()
        for (i in 0 until xs.size - 1) {
            val x1 = xs[i]
            val y1 = ys[i]
            val x2 = xs[i + 1]
            val y2 = ys[i + 1]
            val len = sqrt((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)).coerceAtLeast(0.01f)
            val nx = -(y2 - y1) / len
            val ny = (x2 - x1) / len
            val parts = 5
            for (p in 0 until parts) {
                val f = p / parts.toFloat()
                val off = if (p == 0) 0f else (fx.nextFloat() - 0.5f) * 0.32f
                outX += x1 + (x2 - x1) * f + nx * off
                outY += y1 + (y2 - y1) * f + ny * off
            }
        }
        outX += xs.last()
        outY += ys.last()
        bolts += Bolt(outX.toFloatArray(), outY.toFloatArray(), 0.16f)
    }

    // --- projectiles ----------------------------------------------------------------------

    private fun updateProjectiles(dt: Float) {
        for (p in projectiles) {
            if (!p.alive) continue
            val tg = p.target
            if (tg != null && tg.alive) {
                p.tx = tg.x
                p.ty = tg.y
            } else if (tg != null) {
                p.target = if (p.kind == Shot.MISSILE) retarget(p) else null
            }
            val dx = p.tx - p.x
            val dy = p.ty - p.y
            val d = sqrt(dx * dx + dy * dy)
            val step = p.speed * dt
            if (d > 0.001f) p.angle = atan2(dy, dx)
            if (d <= step + 0.05f) {
                p.x = p.tx
                p.y = p.ty
                impact(p)
            } else {
                p.x += dx / d * step
                p.y += dy / d * step
                if (p.kind != Shot.BULLET) {
                    p.trail -= dt
                    if (p.trail <= 0f) {
                        p.trail = 0.03f
                        val c = if (p.kind == Shot.ROCKET) 0xFF9A9A9A.toInt() else 0xFFE8E8E8.toInt()
                        particles += Particle(p.x, p.y, (fx.nextFloat() - 0.5f) * 0.2f, (fx.nextFloat() - 0.5f) * 0.2f, 0.5f, 0.5f, 0.05f, c, 0.16f)
                    }
                }
            }
        }
        projectiles.removeAll { !it.alive }
    }

    private fun retarget(p: Projectile): Enemy? {
        var best: Enemy? = null
        var bestD = 2.5f * 2.5f
        for (e in enemies) {
            if (!e.alive || !e.type.air) continue
            val dx = e.x - p.x
            val dy = e.y - p.y
            val d2 = dx * dx + dy * dy
            if (d2 < bestD) {
                bestD = d2
                best = e
            }
        }
        return best
    }

    private fun impact(p: Projectile) {
        p.alive = false
        when (p.kind) {
            Shot.BULLET -> {
                p.target?.let { if (it.alive) damage(it, p.damage, p.source) }
                particles += Particle(p.x, p.y, 0f, 0f, 0.08f, 0.08f, 0.07f, 0xFFFFE38A.toInt(), 0f)
            }
            Shot.ROCKET -> {
                explosion(p.x, p.y, p.splash)
                for (e in enemies) {
                    if (!e.alive || e.type.air) continue
                    val dx = e.x - p.x
                    val dy = e.y - p.y
                    val d = sqrt(dx * dx + dy * dy)
                    if (d > p.splash + e.type.radius) continue
                    val k = (1f - 0.5f * d / p.splash).coerceIn(0.5f, 1f)
                    damage(e, p.damage * k, p.source)
                }
                shake = max(shake, 0.12f)
                sound(Sfx.BOOM, 0.5f)
            }
            Shot.MISSILE -> {
                p.target?.let { if (it.alive) damage(it, p.damage, p.source) }
                explosion(p.x, p.y, 0.5f)
                sound(Sfx.BOOM, 0.3f)
            }
        }
    }

    // --- effects --------------------------------------------------------------------------

    private fun explosion(x: Float, y: Float, radius: Float) {
        rings += Ring(x, y, radius, 0.35f, 0.35f, 0xFFFFC95A.toInt())
        val n = (10 + radius * 10).toInt()
        for (k in 0 until n) {
            val a = fx.nextFloat() * 6.283f
            val v = (0.5f + fx.nextFloat()) * radius * 2.4f
            val c = when (fx.nextInt(3)) {
                0 -> 0xFFFFE07A.toInt()
                1 -> 0xFFFF9A3C.toInt()
                else -> 0xFFE0502A.toInt()
            }
            particles += Particle(x, y, cos(a) * v, sin(a) * v, 0.4f, 0.4f, 0.07f + radius * 0.04f, c, 0.1f)
        }
        for (k in 0 until 4) {
            val a = fx.nextFloat() * 6.283f
            particles += Particle(x, y, cos(a) * 0.3f, sin(a) * 0.3f, 0.9f, 0.9f, 0.12f * radius + 0.05f, 0xFF4A4A4A.toInt(), 0.3f)
        }
    }

    private fun flame(x: Float, y: Float, n: Int, spread: Float) {
        for (k in 0 until n) {
            particles += Particle(
                x + (fx.nextFloat() - 0.5f) * spread, y + (fx.nextFloat() - 0.5f) * spread,
                0f, -0.5f, 0.3f, 0.3f, 0.06f, 0xFFFF8A2A.toInt(), 0.1f,
            )
        }
    }

    private fun puff(x: Float, y: Float, n: Int, color: Int) {
        for (k in 0 until n) {
            val a = fx.nextFloat() * 6.283f
            particles += Particle(x, y, cos(a) * 0.9f, sin(a) * 0.9f, 0.45f, 0.45f, 0.08f, color, 0.15f)
        }
    }

    private fun updateEffects(dt: Float) {
        if (particles.size > MAX_PARTICLES) particles.subList(0, particles.size - MAX_PARTICLES).clear()
        for (p in particles) {
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vx *= 1f - 3f * dt
            p.vy *= 1f - 3f * dt
            p.size = max(0.005f, p.size + p.grow * dt)
            p.life -= dt
        }
        particles.removeAll { it.life <= 0f }
        for (t in texts) {
            t.y -= (if (t.big) 0.35f else 0.7f) * dt
            t.life -= dt
        }
        texts.removeAll { it.life <= 0f }
        for (b in bolts) b.life -= dt
        bolts.removeAll { it.life <= 0f }
        for (t in tracers) t.life -= dt
        tracers.removeAll { it.life <= 0f }
        for (r in rings) r.life -= dt
        rings.removeAll { it.life <= 0f }
    }

    companion object {
        const val BETWEEN_WAVES = 14f
        const val EARLY_BONUS_PER_SECOND = 2
        const val WAVE_BONUS = 12
        const val WAVE_BONUS_STEP = 2
        const val APC_CREW = 4
        const val MEDIC_RADIUS = 1.7f
        const val MEDIC_HEAL = 14f
        const val BULLET_SPEED = 15f
        const val ROCKET_SPEED = 5.5f
        const val MISSILE_SPEED = 7.5f
        const val SHOTGUN_SPREAD = 0.5f
        const val FLAME_TICK = 0.1f
        const val FLAME_CONE = 0.45f
        const val AFTERBURN = 0.4f
        const val SLOW_TIME = 1.2f
        const val TESLA_JUMP = 1.9f
        const val MAX_PARTICLES = 700

        val GOLD = 0xFFFFD54A.toInt()
        val RED = 0xFFFF4B4B.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val HEAL = 0xFF6BE07A.toInt()
    }
}
