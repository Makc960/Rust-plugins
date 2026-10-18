package com.makc.basedefense.engine

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class Phase { READY, RUNNING, WON, LOST }

/** What a game reports to the screen around it. */
interface WorldListener {
    fun sound(sfx: Sfx, volume: Float) = Unit
    fun baseHit() = Unit

    /** An enemy kind walked onto the map for the first time in this game. */
    fun firstSight(type: EnemyType) = Unit
    fun bossSpawned(e: Enemy) = Unit

    companion object {
        val NONE = object : WorldListener {}
    }
}

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
    val maxShield = maxHp * type.shield
    var shield = maxShield
    var shieldWait = 0f
    var shieldFlash = 0f
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
    var stunTimer = 0f
    var stunGuard = 0f
    var markTimer = 0f
    var frostTimer = 0f
    var hitFlash = 0f
    var healTimer = 1f
    var healPulse = 0f
    var smoke = 0f
    var jamTimer = 2f
    var jamPulse = 0f
    var numberTimer = 0f
    var knock = 0f
    var age = 0f

    /** For ghosts: a radar sees it right now. */
    var revealed = false
    var seen = false

    val remaining: Float get() = path.length - dist
    val visible: Boolean get() = !type.stealth || revealed
    val stunned: Boolean get() = stunTimer > 0f
}

class Tower(val type: TowerType, val col: Int, val row: Int) {
    val x = col + 0.5f
    val y = row + 0.5f

    /** 0 to 2 for the three regular levels; a [branch] is the fourth. */
    var level = 0
    var branch: Branch? = null
    var invested = 0
    var cooldown = 0f

    /** Degrees, 0 = right, 90 = down. */
    var angle = -90f
    var mode = if (type == TowerType.SNIPER) TargetMode.STRONG else TargetMode.FIRST
    var kills = 0
    var damageDealt = 0f
    var flash = 0f
    var burning = 0f
    var tube = 0

    /** Counts shots, so the squad's soldiers can take turns firing. */
    var shots = 0
    var age = 0f
    var jammed = 0f

    /** Minigun barrels, from 0 at rest to 1 at full speed. */
    var spin = 0f
    var spinAngle = 0f

    /** Mines: ready to blow. */
    var armed = true
    var timer2 = 0f

    /** Bonuses from radars nearby, worked out every step. */
    var aura = 0f
    var rangeBonus = 0f

    val stats: TowerLevel get() = branch?.stats ?: type.levels[level]
    val canUpgrade: Boolean get() = branch == null && level < type.levels.size - 1
    val atFork: Boolean get() = branch == null && level == type.levels.size - 1

    /** 1 to 4. */
    val tier: Int get() = if (branch != null) 4 else level + 1
}

enum class Shot { BULLET, ROCKET, MISSILE, BOMBLET }

class Projectile(
    val kind: Shot,
    var x: Float,
    var y: Float,
    var target: Enemy?,
    val damage: Float,
    val speed: Float,
    val splash: Float,
    val source: Tower?,
) {
    var tx = target?.x ?: x
    var ty = target?.y ?: y
    var alive = true
    var trail = 0f
    var angle = 0f
    var pierce = false
    var bossMul = 1f

    /** Damage of each bomblet a cluster rocket releases; 0 for plain rockets. */
    var cluster = 0f
    var big = false
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

class FloatText(var x: Float, var y: Float, val text: String, val color: Int, var life: Float, val size: Float = 1f) {
    val maxLife = life
}

class Bolt(val xs: FloatArray, val ys: FloatArray, var life: Float, val color: Int)

/** 0 pellet, 1 slug, 2 sniper, 3 marking shot. */
class Tracer(val x1: Float, val y1: Float, val x2: Float, val y2: Float, var life: Float, val kind: Int = 0) {
    val maxLife = life
}

class Ring(val x: Float, val y: Float, val maxR: Float, var life: Float, val maxLife: Float, val color: Int)

/** A fireball; one on the ground leaves a scorch mark that slowly fades. */
class Blast(val x: Float, val y: Float, val radius: Float, val ground: Boolean) {
    var age = 0f

    /** Turns each one its own way without drawing on the fight's random numbers. */
    val angle = ((x * 977f + y * 613f) % 360f + 360f) % 360f
    val life: Float get() = if (ground) SCORCH_LIFE else FIRE_LIFE

    companion object {
        const val FIRE_LIFE = 0.5f
        const val SCORCH_LIFE = 4f
    }
}

/** A burning pool left by napalm. */
class Patch(val x: Float, val y: Float, var life: Float, val dps: Float, val radius: Float, val source: Tower?) {
    val maxLife = life
}

class Corpse(val x: Float, val y: Float, val type: EnemyType, val angle: Float, var life: Float) {
    val maxLife = life
}

class Bomb(val x: Float, val y: Float, var timer: Float, val damage: Float) {
    val fall = BOMB_FALL

    companion object {
        const val BOMB_FALL = 0.35f
    }
}

class Plane(val x: Float, var y: Float)

/** A big line of text across the map for a few seconds. */
class Banner(val title: () -> String, val sub: (() -> String)?, val color: Int, var timer: Float) {
    val maxTimer = timer
}

private class ActiveGroup(val group: Group, val wave: Int, val index: Int) {
    var timer = group.delay
    var spawned = 0
}

/**
 * One game of one level: everything that moves, fires and pays.
 * [step] advances it by a fixed time slice; nothing here knows about screens.
 */
class World(
    val level: LevelDef,
    val rules: Rules = Rules(),
    private val listener: WorldListener = WorldListener.NONE,
) {
    val map = GameMap(level)
    var money = rules.startMoney(level.startMoney)
    var lives = rules.lives
    val maxLives: Int = rules.lives
    var phase = Phase.READY
        private set
    var wavesStarted = 0
        private set
    var countdown = -1f
        private set
    var time = 0f
        private set

    /** Seconds of actual fighting: cooldowns and weather only run then. */
    var battleTime = 0f
        private set
    var kills = 0
        private set
    var leaked = 0
        private set

    // Counters for the player's profile.
    var bossKills = 0
        private set
    var ghostKills = 0
        private set
    var built = 0
        private set
    var airstrikes = 0
        private set
    var cleared = 0
        private set
    var maxMoney = money
        private set
    var branchMask = 0
        private set
    var typeMask = 0
        private set
    var abilitiesUsed = 0
        private set

    val enemies = ArrayList<Enemy>()
    val towers = ArrayList<Tower>()
    val projectiles = ArrayList<Projectile>()
    val particles = ArrayList<Particle>()
    val texts = ArrayList<FloatText>()
    val bolts = ArrayList<Bolt>()
    val tracers = ArrayList<Tracer>()
    val rings = ArrayList<Ring>()
    val blasts = ArrayList<Blast>()
    val patches = ArrayList<Patch>()
    val corpses = ArrayList<Corpse>()
    val bombs = ArrayList<Bomb>()
    var plane: Plane? = null
        private set

    var baseFlash = 0f
        private set
    var shake = 0f
        private set
    var banner: Banner? = null
        private set

    /** Seconds left of the current sandstorm or blizzard. */
    var weather = 0f
        private set
    private var weatherWait = FIRST_WEATHER
    var freezeTimer = 0f
        private set

    /** Counts down after a boss shows up, for the siren and the slow motion. */
    var bossAlert = 0f
        private set
    val cooldowns = FloatArray(Ability.entries.size) { Ability.entries[it].cooldown * rules.cooldownMul * 0.5f }

    /** Fewer particles for weak phones. */
    var lite = false
    var damageNumbers = false

    private val active = ArrayList<ActiveGroup>()
    private val incoming = ArrayList<Enemy>()
    private val incomingShots = ArrayList<Projectile>()
    private val remaining = HashMap<Int, Int>()
    private val waveCache = HashMap<Int, Wave>()
    private val clearedCells = ArrayList<Int>()
    private val sighted = HashSet<EnemyType>()
    private val radars = ArrayList<Tower>()
    private val rng = Random(level.plan.seed * 7919 + 1)
    private val fx = Random(level.plan.seed * 31 + 7)
    private val tmp = FloatArray(4)
    private val chain = ArrayList<Enemy>()
    private val hitList = ArrayList<Enemy>()

    val endless: Boolean get() = level.endless
    val totalWaves: Int get() = level.plan.count
    val challengeFailed: Boolean get() = rules.noLeaks && leaked > 0

    fun wave(i: Int): Wave? {
        if (!endless && i >= totalWaves) return null
        return waveCache.getOrPut(i) { WaveGen.generate(level.plan, i, map.paths.size) }
    }

    val nextWave: Wave? get() = wave(wavesStarted)

    val spawning: Boolean get() = active.isNotEmpty()

    val over: Boolean get() = phase == Phase.WON || phase == Phase.LOST

    val canCallWave: Boolean
        get() = nextWave != null && (phase == Phase.READY || (phase == Phase.RUNNING && countdown >= 0f))

    /** Money for calling the next wave before its timer runs out. */
    val earlyBonus: Int
        get() = if (phase == Phase.RUNNING && countdown > 0f) ceil(countdown).toInt() * EARLY_BONUS_PER_SECOND else 0

    val sandstorm: Boolean get() = weather > 0f && map.biome == Biome.DESERT
    val blizzard: Boolean get() = weather > 0f && map.biome == Biome.WINTER

    /** 0 to 1: how thick the weather is, fading in and out. */
    val weatherLevel: Float get() = if (weather <= 0f) 0f else min(1f, min(weather, WEATHER_TIME - weather) / 1.2f)

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
        remaining[w.index] = (remaining[w.index] ?: 0) + w.total
        for ((k, g) in w.groups.withIndex()) active += ActiveGroup(g, w.index, k)
        wavesStarted++
        val fresh = w.fresh?.takeIf { it.campaign == level.id }
        banner = when {
            w.bosses.isNotEmpty() -> Banner(
                { L("БОСС: %s", "BOSS: %s").f(w.bosses.joinToString(" + ") { it.title.t.uppercase() }) },
                { w.bosses.first().hint.t }, RED, 3.2f,
            )
            fresh != null -> Banner({ L("НОВЫЙ ВРАГ: %s", "NEW ENEMY: %s").f(fresh.title.t.uppercase()) }, { fresh.hint.t }, GOLD, 3.2f)
            else -> Banner({ L("ВОЛНА %d", "WAVE %d").f(w.index + 1) }, null, WHITE, 1.8f)
        }
        payIncome()
        sound(Sfx.WAVE, 0.7f)
    }

    /** Quarries pay at the start of every wave. */
    private fun payIncome() {
        for (t in towers) {
            if (t.type != TowerType.QUARRY) continue
            val s = t.stats
            var coins = s.damage.toInt()
            if (t.branch == Branch.BANK) coins += min(s.extra2, money * s.extra).toInt()
            money += coins
            t.damageDealt += coins
            t.flash = 0.6f
            texts += FloatText(t.x, t.y - 0.3f, "+$coins", GOLD, 1.3f)
        }
    }

    fun step(dt: Float) {
        time += dt
        for (t in towers) t.age += dt
        if (phase == Phase.RUNNING) {
            battleTime += dt
            for (i in cooldowns.indices) if (cooldowns[i] > 0f) cooldowns[i] = max(0f, cooldowns[i] - dt)
            updateWeather(dt)
            spawn(dt)
            updateSupport()
            updateEnemies(dt)
            for (t in towers) updateTower(t, dt)
            updateProjectiles(dt)
            updatePatches(dt)
            updateBombs(dt)
            enemies.removeAll { !it.alive }
            enemies.addAll(incoming)
            incoming.clear()
            if (countdown >= 0f) {
                countdown -= dt
                if (countdown <= 0f) startWave()
            }
            if (money > maxMoney) maxMoney = money
            if (lives <= 0) {
                lives = 0
                phase = Phase.LOST
                sound(Sfx.LOSE, 1f)
            } else if (active.isEmpty() && nextWave == null && enemies.isEmpty()) {
                phase = Phase.WON
                sound(Sfx.WIN, 1f)
            }
        } else {
            updateBombs(dt)
        }
        updateEffects(dt)
        banner?.let {
            it.timer -= dt
            if (it.timer <= 0f) banner = null
        }
        if (baseFlash > 0f) baseFlash -= dt
        if (shake > 0f) shake = max(0f, shake - dt)
        if (freezeTimer > 0f) freezeTimer -= dt
        if (bossAlert > 0f) bossAlert -= dt
    }

    private fun sound(sfx: Sfx, volume: Float) = listener.sound(sfx, volume)

    // --- building -------------------------------------------------------------------------

    fun towerAt(c: Int, r: Int): Tower? = towers.firstOrNull { it.col == c && it.row == r }

    fun buildCost(type: TowerType): Int = (type.levels[0].cost * rules.buildMul).roundToInt()

    fun upgradeCost(t: Tower): Int = if (t.canUpgrade) (t.type.levels[t.level + 1].cost * rules.upgradeMul).roundToInt() else 0

    fun branchCost(b: Branch): Int = (b.stats.cost * rules.upgradeMul).roundToInt()

    fun sellValue(t: Tower): Int = (t.invested * rules.refund).toInt()

    val towerLimitReached: Boolean get() = towers.size >= rules.maxTowers

    /** Quarries are capped so endless games are won with guns, not with a mining town. */
    fun quarryLimitReached(type: TowerType): Boolean =
        type == TowerType.QUARRY && towers.count { it.type == TowerType.QUARRY } >= MAX_QUARRIES

    /** The cell suits this kind of tower: grass for most, road for mines. */
    fun canPlace(type: TowerType, c: Int, r: Int): Boolean {
        if (over || towerAt(c, r) != null) return false
        return if (type.role == Role.ROAD) map.mineable(c, r) else map.buildable(c, r)
    }

    fun canBuild(type: TowerType, c: Int, r: Int): Boolean =
        type in rules.towers && !towerLimitReached && !quarryLimitReached(type) && canPlace(type, c, r) && money >= buildCost(type)

    fun build(type: TowerType, c: Int, r: Int): Tower? {
        if (!canBuild(type, c, r)) return null
        val cost = buildCost(type)
        money -= cost
        val t = Tower(type, c, r)
        t.invested = cost
        towers += t
        built++
        typeMask = typeMask or (1 shl type.ordinal)
        rings += Ring(t.x, t.y, 0.8f, 0.4f, 0.4f, Colors.alpha(WHITE, 0.8f))
        puff(t.x, t.y, if (lite) 4 else 8, if (map.biome == Biome.WINTER) 0xFFE8F0F8.toInt() else 0xFFB9A98A.toInt())
        sound(Sfx.BUILD, 0.7f)
        return t
    }

    fun upgrade(t: Tower): Boolean {
        val cost = upgradeCost(t)
        if (!t.canUpgrade || money < cost || t !in towers || over) return false
        money -= cost
        t.invested += cost
        t.level++
        t.age = 0f
        celebrate(t, GOLD)
        sound(Sfx.UPGRADE, 0.7f)
        return true
    }

    fun canBranch(t: Tower, b: Branch): Boolean =
        rules.branches && t.atFork && b.tower == t.type && money >= branchCost(b) && t in towers && !over

    fun branch(t: Tower, b: Branch): Boolean {
        if (!canBranch(t, b)) return false
        val cost = branchCost(b)
        money -= cost
        t.invested += cost
        t.branch = b
        t.age = 0f
        branchMask = branchMask or (1 shl b.ordinal)
        celebrate(t, 0xFFFFE9A0.toInt())
        rings += Ring(t.x, t.y, 1.5f, 0.7f, 0.7f, GOLD)
        sound(Sfx.UPGRADE, 0.9f)
        return true
    }

    private fun celebrate(t: Tower, color: Int) {
        rings += Ring(t.x, t.y, 1.0f, 0.5f, 0.5f, color)
        val n = if (lite) 5 else 10
        for (k in 0 until n) {
            val a = fx.nextFloat() * 6.283f
            particles += Particle(t.x, t.y, cos(a) * 1.4f, sin(a) * 1.4f, 0.5f, 0.5f, 0.06f, color, -0.05f)
        }
    }

    fun sell(t: Tower): Boolean {
        if (t !in towers || !rules.canSell || over) return false
        val value = sellValue(t)
        money += value
        towers.remove(t)
        texts += FloatText(t.x, t.y - 0.2f, "+$value", GOLD, 1.0f)
        puff(t.x, t.y, 10, 0xFF8A8A8A.toInt())
        sound(Sfx.SELL, 0.7f)
        return true
    }

    /** Takes back a tower placed by mistake: all its money returns. */
    fun undo(t: Tower): Boolean {
        if (t !in towers || t.level != 0 || t.branch != null || over) return false
        money += t.invested
        towers.remove(t)
        built--
        puff(t.x, t.y, 6, 0xFF8A8A8A.toInt())
        sound(Sfx.SELL, 0.5f)
        return true
    }

    fun clearCost(): Int = ((CLEAR_COST + CLEAR_STEP * map.biome.ordinal) * rules.buildMul).roundToInt()

    fun canClear(c: Int, r: Int): Boolean = !over && map.obstacle(c, r) && money >= clearCost()

    /** Pays to remove a rock, tree or cactus so the cell can be built on. */
    fun clear(c: Int, r: Int): Boolean {
        if (!canClear(c, r)) return false
        money -= clearCost()
        val kind = map.terrain(c, r)
        map.clear(c, r)
        clearedCells += r * COLS + c
        cleared++
        val color = when (kind) {
            'T' -> if (map.biome == Biome.WINTER) 0xFF2F5A4A.toInt() else 0xFF3E7A35.toInt()
            'C' -> 0xFF5E8F3A.toInt()
            else -> 0xFF8C8478.toInt()
        }
        puff(c + 0.5f, r + 0.5f, 14, color)
        rings += Ring(c + 0.5f, r + 0.5f, 0.7f, 0.4f, 0.4f, Colors.alpha(WHITE, 0.6f))
        sound(Sfx.CLEAR, 0.7f)
        return true
    }

    // --- abilities ------------------------------------------------------------------------

    fun cooldownOf(a: Ability): Float = a.cooldown * rules.cooldownMul

    fun abilityReady(a: Ability): Boolean =
        rules.usable(a) && cooldowns[a.ordinal] <= 0f && phase == Phase.RUNNING && (a != Ability.REPAIR || lives < maxLives)

    private fun use(a: Ability) {
        cooldowns[a.ordinal] = cooldownOf(a)
        abilitiesUsed++
    }

    val airstrikeDamage: Float get() = AIRSTRIKE_DAMAGE * (1f + 0.12f * max(0, wavesStarted - 1))

    /** A plane crosses the map and drops three bombs around ([x], [y]). */
    fun airstrike(x: Float, y: Float): Boolean {
        if (!abilityReady(Ability.AIRSTRIKE)) return false
        use(Ability.AIRSTRIKE)
        airstrikes++
        val start = ROWS + 2.5f
        plane = Plane(x, start)
        val dmg = airstrikeDamage
        for (k in 0 until 3) {
            val by = y + (1 - k) * 0.7f
            val bx = x + (fx.nextFloat() - 0.5f) * 0.3f
            bombs += Bomb(bx, by, (start - by) / PLANE_SPEED + Bomb.BOMB_FALL, dmg)
        }
        sound(Sfx.PLANE, 0.8f)
        return true
    }

    fun freeze(): Boolean {
        if (!abilityReady(Ability.FREEZE)) return false
        use(Ability.FREEZE)
        for (e in enemies) {
            if (!e.alive) continue
            slow(e, if (e.type.boss) 0.35f else 0.7f, FREEZE_TIME)
            e.frostTimer = max(e.frostTimer, FREEZE_TIME)
        }
        freezeTimer = FREEZE_TIME
        val n = if (lite) 20 else 60
        for (k in 0 until n) {
            particles += Particle(
                fx.nextFloat() * COLS, fx.nextFloat() * ROWS, (fx.nextFloat() - 0.5f) * 0.3f, 0.3f + fx.nextFloat() * 0.4f,
                1.6f, 1.6f, 0.05f + fx.nextFloat() * 0.04f, 0xFFE6F6FF.toInt(), 0f,
            )
        }
        sound(Sfx.FREEZE, 0.9f)
        return true
    }

    fun repair(): Boolean {
        if (!abilityReady(Ability.REPAIR)) return false
        use(Ability.REPAIR)
        val before = lives
        lives = min(maxLives, lives + REPAIR_LIVES)
        val bx = map.baseCol + 0.5f
        val by = map.baseRow + 0.5f
        rings += Ring(bx, by, 1.4f, 0.7f, 0.7f, HEAL)
        texts += FloatText(bx, by - 0.4f, "+${lives - before}", HEAL, 1.4f, 1.3f)
        for (k in 0 until 14) {
            val a = fx.nextFloat() * 6.283f
            particles += Particle(bx, by, cos(a) * 0.9f, sin(a) * 0.9f - 0.4f, 0.8f, 0.8f, 0.07f, HEAL, -0.04f)
        }
        sound(Sfx.REPAIR, 0.9f)
        return true
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
                spawnEnemy(a.group.type, p, a.group.hpMul * rules.hpMul, a.wave, 0f, a.group.rewardMul)
                a.spawned++
                a.timer += max(0.05f, a.group.interval)
            }
            if (a.spawned >= a.group.count) iter.remove()
        }
        if (active.isEmpty() && countdown < 0f && nextWave != null) countdown = BETWEEN_WAVES
    }

    private fun spawnEnemy(type: EnemyType, pathIndex: Int, hpMul: Float, wave: Int, dist: Float, rewardMul: Float = 1f): Enemy {
        val spread = if (type == EnemyType.SWARM) 0.36f else if (type.boss) 0f else 0.18f
        val reward = max(1, (type.reward * rewardMul).roundToInt())
        val e = Enemy(type, map.paths[pathIndex], pathIndex, hpMul, wave, (fx.nextFloat() - 0.5f) * spread, reward)
        e.dist = dist
        place(e)
        incoming += e
        if (type.boss) {
            bossAlert = BOSS_ALERT
            listener.bossSpawned(e)
            sound(Sfx.SIREN, 0.9f)
        }
        return e
    }

    private fun place(e: Enemy) {
        e.path.pointAt(e.dist, tmp)
        e.x = tmp[0] - tmp[3] * e.lane
        e.y = tmp[1] + tmp[2] * e.lane
        e.dirX = tmp[2]
        e.dirY = tmp[3]
    }

    private fun finish(e: Enemy) {
        val left = (remaining[e.wave] ?: 1) - 1
        remaining[e.wave] = left
        if (left <= 0) {
            remaining.remove(e.wave)
            val bonus = WAVE_BONUS + WAVE_BONUS_STEP * e.wave
            money += bonus
            texts += FloatText(
                map.baseCol + 0.5f, map.baseRow - 0.6f, L("Волна %d отбита  +%d", "Wave %d cleared  +%d").f(e.wave + 1, bonus),
                GOLD, 2.0f, 1.35f,
            )
        }
    }

    private fun updateWeather(dt: Float) {
        if (map.biome == Biome.GREEN) return
        if (weather > 0f) {
            weather -= dt
            if (weather <= 0f) {
                weather = 0f
                weatherWait = WEATHER_GAP + rng.nextFloat() * WEATHER_GAP_RANDOM
            }
            return
        }
        weatherWait -= dt
        if (weatherWait > 0f) return
        weather = WEATHER_TIME
        banner = if (map.biome == Biome.DESERT) {
            Banner({ L("ПЕСЧАНАЯ БУРЯ", "SANDSTORM").t }, { L("Дальность отрядов −30%", "Squad range −30%").t }, SAND, 2.6f)
        } else {
            Banner({ L("МЕТЕЛЬ", "BLIZZARD").t }, { L("Отряды стреляют на 25% реже", "Squads fire 25% slower").t }, ICE, 2.6f)
        }
    }

    // --- support towers -------------------------------------------------------------------

    private fun radarRange(r: Tower): Float = r.stats.range * rules.rangeMul

    private fun updateSupport() {
        radars.clear()
        for (t in towers) {
            t.aura = 0f
            t.rangeBonus = 0f
            if (t.type == TowerType.RADAR && t.jammed <= 0f) radars += t
        }
        if (radars.isEmpty()) {
            for (e in enemies) e.revealed = false
            return
        }
        for (r in radars) {
            val s = r.stats
            val reach = radarRange(r)
            for (t in towers) {
                if (t === r || t.type.role == Role.SUPPORT || t.type.role == Role.ECONOMY) continue
                if (!near(t.x, t.y, r.x, r.y, reach)) continue
                t.aura = max(t.aura, s.extra)
                if (r.branch == Branch.COMMAND) t.rangeBonus = max(t.rangeBonus, s.extra2)
            }
        }
        for (e in enemies) {
            if (!e.alive) continue
            var seenNow = false
            for (r in radars) {
                if (!near(e.x, e.y, r.x, r.y, radarRange(r) + e.type.radius)) continue
                seenNow = true
                if (r.branch == Branch.JAMMER) slow(e, r.stats.extra2 * bossK(e), 0.25f)
            }
            e.revealed = seenNow
        }
    }

    /** A sapper next to a jammer cannot do its job. */
    private fun suppressed(e: Enemy): Boolean =
        radars.any { it.branch == Branch.JAMMER && near(e.x, e.y, it.x, it.y, radarRange(it)) }

    // --- enemies --------------------------------------------------------------------------

    private fun bossK(e: Enemy): Float = if (e.type.boss) 0.5f else 1f

    private fun slow(e: Enemy, amount: Float, time: Float) {
        if (amount <= 0f) return
        if (e.slowTimer <= 0f || amount > e.slowAmount + 0.001f) {
            e.slowAmount = amount
            e.slowTimer = time
        } else if (amount >= e.slowAmount - 0.001f) {
            e.slowTimer = max(e.slowTimer, time)
        }
    }

    fun speedOf(e: Enemy): Float {
        if (e.stunTimer > 0f) return 0f
        var s = e.type.speed * rules.speedMul
        if (e.slowTimer > 0f) s *= 1f - e.slowAmount
        if (!e.type.air && map.isIce(floor(e.x).toInt(), floor(e.y).toInt())) s *= ICE_SPEED
        return s
    }

    private fun updateEnemies(dt: Float) {
        for (e in enemies) {
            if (!e.alive) continue
            e.age += dt
            if (e.hitFlash > 0f) e.hitFlash -= dt
            if (e.healPulse > 0f) e.healPulse -= dt
            if (e.slowTimer > 0f) e.slowTimer -= dt
            if (e.markTimer > 0f) e.markTimer -= dt
            if (e.frostTimer > 0f) e.frostTimer -= dt
            if (e.numberTimer > 0f) e.numberTimer -= dt
            if (e.jamPulse > 0f) e.jamPulse -= dt
            if (e.shieldFlash > 0f) e.shieldFlash -= dt
            if (e.stunTimer > 0f) {
                e.stunTimer -= dt
                if (e.stunTimer <= 0f) e.stunGuard = STUN_GUARD
            } else if (e.stunGuard > 0f) {
                e.stunGuard -= dt
            }
            if (e.burnTimer > 0f) {
                e.burnTimer -= dt
                damage(e, e.burnDps * dt, null, pierce = true)
                if (!e.alive) continue
                if (!lite && fx.nextFloat() < dt * 12f) flame(e.x, e.y, 1, 0.25f)
            }
            if (e.maxShield > 0f) {
                if (e.shieldWait > 0f) {
                    e.shieldWait -= dt
                } else if (e.shield < e.maxShield) {
                    e.shield = min(e.maxShield, e.shield + e.maxShield * SHIELD_REGEN * dt)
                }
            }
            when (e.type) {
                EnemyType.MEDIC -> heal(e, dt)
                EnemyType.SAPPER -> jam(e, dt)
                else -> Unit
            }
            if (e.type.boss) {
                e.smoke -= dt
                if (e.smoke <= 0f && e.hp < e.maxHp * 0.5f) {
                    e.smoke = if (lite) 0.3f else 0.12f
                    particles += Particle(e.x, e.y, (fx.nextFloat() - 0.5f) * 0.3f, -0.4f, 1.0f, 1.0f, 0.1f, 0xFF3C3C3C.toInt(), 0.25f)
                }
            }
            if (e.knock > 0f) {
                val k = min(e.knock, KNOCK_SPEED * dt)
                e.dist = max(0f, e.dist - k)
                e.knock -= k
            }
            e.dist += speedOf(e) * dt
            if (e.dist >= e.path.length) {
                reachBase(e)
                continue
            }
            place(e)
            if (!e.seen && onMap(e)) {
                e.seen = true
                if (sighted.add(e.type)) listener.firstSight(e.type)
            }
        }
    }

    private fun heal(e: Enemy, dt: Float) {
        e.healTimer -= dt
        if (e.healTimer > 0f) return
        e.healTimer = 1f
        e.healPulse = 0.6f
        rings += Ring(e.x, e.y, MEDIC_RADIUS, 0.6f, 0.6f, Colors.alpha(HEAL, 0.9f))
        for (o in enemies) {
            if (o === e || !o.alive || o.type.boss) continue
            if (near(o.x, o.y, e.x, e.y, MEDIC_RADIUS)) o.hp = min(o.maxHp, o.hp + MEDIC_HEAL * e.hpMul)
        }
    }

    private fun jam(e: Enemy, dt: Float) {
        e.jamTimer -= dt
        if (e.jamTimer > 0f || !onMap(e)) return
        if (suppressed(e)) {
            e.jamTimer = 1f
            rings += Ring(e.x, e.y, 0.5f, 0.3f, 0.3f, Colors.alpha(0xFF5FA8A0.toInt(), 0.8f))
            return
        }
        var hit = false
        for (t in towers) {
            if (!near(t.x, t.y, e.x, e.y, SAPPER_RADIUS)) continue
            t.jammed = max(t.jammed, JAM_TIME)
            hit = true
        }
        if (!hit) {
            e.jamTimer = 0.3f
            return
        }
        e.jamTimer = JAM_COOLDOWN
        e.jamPulse = 0.5f
        rings += Ring(e.x, e.y, SAPPER_RADIUS, 0.5f, 0.5f, Colors.alpha(0xFFFFD23A.toInt(), 0.9f))
        sound(Sfx.JAM, 0.6f)
    }

    private fun reachBase(e: Enemy) {
        e.alive = false
        lives -= e.type.damage
        val firstLeak = leaked == 0
        leaked += e.type.damage
        baseFlash = 0.5f
        shake = 0.35f
        rings += Ring(map.baseCol + 0.5f, map.baseRow + 0.5f, 1.2f, 0.5f, 0.5f, Colors.alpha(RED, 0.9f))
        texts += FloatText(map.baseCol + 0.5f, map.baseRow - 0.1f, "-${e.type.damage}", RED, 1.0f)
        sound(Sfx.BASE_HIT, 0.9f)
        listener.baseHit()
        if (firstLeak && rules.noLeaks) {
            banner = Banner({ L("ИСПЫТАНИЕ ПРОВАЛЕНО", "CHALLENGE FAILED").t }, { L("Враг дошёл до базы", "An enemy reached the base").t }, RED, 2.6f)
        }
        finish(e)
    }

    /** Hits [e] for [raw] damage before armor; returns what actually came off. */
    fun damage(e: Enemy, raw: Float, source: Tower?, pierce: Boolean = false, armorPierce: Float = 0f): Float {
        if (!e.alive || raw <= 0f) return 0f
        var amount = raw
        if (e.markTimer > 0f) amount *= MARK_BONUS
        if (e.frostTimer > 0f) amount *= FROST_BONUS
        var dealt = if (pierce) {
            amount
        } else {
            val armor = e.type.armor * (1f - armorPierce)
            max(amount * 0.2f, amount - armor)
        }
        val total = dealt
        if (e.maxShield > 0f) {
            e.shieldWait = SHIELD_DELAY
            if (e.shield > 0f) {
                val absorbed = min(e.shield, dealt)
                e.shield -= absorbed
                dealt -= absorbed
                e.shieldFlash = 0.12f
            }
        }
        e.hp -= dealt
        if (source != null) source.damageDealt += total
        if (!pierce) e.hitFlash = 0.07f
        if (damageNumbers && total >= 25f && e.numberTimer <= 0f) {
            e.numberTimer = 0.3f
            texts += FloatText(e.x + (fx.nextFloat() - 0.5f) * 0.3f, e.y - e.type.radius - 0.1f, total.roundToInt().toString(), 0xFFFFF1C8.toInt(), 0.6f, 0.75f)
        }
        if (e.hp <= 0f) kill(e, source)
        return total
    }

    private fun kill(e: Enemy, source: Tower?) {
        e.alive = false
        val reward = max(1, (e.reward * rules.rewardMul).roundToInt())
        money += reward
        kills++
        if (e.type.boss) bossKills++
        if (e.type == EnemyType.GHOST) ghostKills++
        if (source != null) source.kills++
        val n = if (e.type.boss) 30 else if (lite) 3 else 8
        for (k in 0 until n) {
            val a = fx.nextFloat() * 6.283f
            val v = 0.6f + fx.nextFloat() * (if (e.type.boss) 2.5f else 1.4f)
            particles += Particle(e.x, e.y, cos(a) * v, sin(a) * v, 0.45f, 0.45f, e.type.radius * 0.28f, e.type.color, -0.1f)
        }
        texts += FloatText(e.x, e.y - 0.2f, "+$reward", GOLD, 0.8f)
        sound(Sfx.COIN, 0.2f)
        if (!e.type.air) {
            corpses += Corpse(e.x, e.y, e.type, atan2(e.dirY, e.dirX), if (e.type.boss) 9f else CORPSE_TIME)
            if (corpses.size > MAX_CORPSES) corpses.removeAt(0)
        }
        if (e.type.boss) {
            explosion(e.x, e.y, 1.6f)
            shake = 0.5f
        }
        when (e.type) {
            EnemyType.APC -> brood(e, EnemyType.BANDIT, APC_CREW, 0.3f)
            EnemyType.MOTHER -> brood(e, EnemyType.SWARM, MOTHER_BROOD, 0.12f)
            else -> Unit
        }
        finish(e)
    }

    /** Enemies that climb out of a dead one. */
    private fun brood(e: Enemy, type: EnemyType, count: Int, gap: Float) {
        for (k in 0 until count) spawnEnemy(type, e.pathIndex, e.hpMul, e.wave, max(0f, e.dist - gap * k)).seen = true
        remaining[e.wave] = (remaining[e.wave] ?: 0) + count
        rings += Ring(e.x, e.y, 0.8f, 0.4f, 0.4f, Colors.alpha(type.color, 0.9f))
    }

    // --- towers ---------------------------------------------------------------------------

    private fun canHit(t: TowerType, e: Enemy): Boolean = if (e.type.air) t.hitsAir else t.hitsGround

    private fun onMap(e: Enemy): Boolean = e.x >= 0f && e.y >= 0f && e.x <= COLS && e.y <= ROWS

    private fun near(x1: Float, y1: Float, x2: Float, y2: Float, r: Float): Boolean {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy <= r * r
    }

    fun rangeOf(t: Tower): Float {
        if (t.type.role == Role.ROAD) return t.stats.range
        if (t.type == TowerType.RADAR) return radarRange(t)
        var r = t.stats.range * rules.rangeMul * (1f + t.rangeBonus)
        if (sandstorm) r *= STORM_RANGE
        return r
    }

    private fun rateOf(t: Tower): Float = t.stats.rate * (if (blizzard) BLIZZARD_RATE else 1f)

    fun damageOf(t: Tower): Float = t.stats.damage * rules.damageMul * (1f + t.aura)

    private fun findTarget(t: Tower, range: Float, skip: List<Enemy>? = null): Enemy? {
        var best: Enemy? = null
        var bestScore = 0f
        val r2 = range * range
        for (e in enemies) {
            if (!e.alive || !e.visible || !canHit(t.type, e) || !onMap(e)) continue
            val dx = e.x - t.x
            val dy = e.y - t.y
            val d2 = dx * dx + dy * dy
            if (d2 > r2) continue
            if (skip != null && e in skip) continue
            val score = when (t.mode) {
                TargetMode.FIRST -> -e.remaining
                TargetMode.LAST -> e.remaining
                TargetMode.STRONG -> e.hp + e.shield
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
        if (t.flash > 0f) t.flash -= dt
        if (t.burning > 0f) t.burning -= dt
        if (t.jammed > 0f) {
            t.jammed -= dt
            t.spin = max(0f, t.spin - dt)
            if (!lite && fx.nextFloat() < dt * 6f) {
                particles += Particle(t.x + (fx.nextFloat() - 0.5f) * 0.5f, t.y - 0.2f, 0f, -0.6f, 0.25f, 0.25f, 0.035f, 0xFFFFE070.toInt(), 0f)
            }
            return
        }
        if (t.cooldown > 0f) t.cooldown -= dt
        when (t.type.role) {
            Role.ROAD -> {
                updateMine(t, dt)
                return
            }
            Role.SUPPORT, Role.ECONOMY -> return
            Role.ATTACK -> Unit
        }
        val s = t.stats
        val range = rangeOf(t)
        val target = findTarget(t, range)
        if (t.branch == Branch.MINIGUN) {
            t.spin = if (target != null) min(1f, t.spin + dt / SPIN_UP) else max(0f, t.spin - dt / SPIN_DOWN)
            t.spinAngle += t.spin * dt * 1500f
        }
        if (target == null) return
        val aim = atan2(target.y - t.y, target.x - t.x)
        t.angle = Math.toDegrees(aim.toDouble()).toFloat()
        if (t.cooldown > 0f) return
        val dmg = damageOf(t)
        val mx = t.x + cos(aim) * 0.36f
        val my = t.y + sin(aim) * 0.36f
        var period = 1f / rateOf(t)
        when (t.type) {
            TowerType.TURRET -> {
                if (t.branch == Branch.MINIGUN) period = 1f / (rateOf(t) * (0.25f + 0.75f * t.spin))
                val p = Projectile(Shot.BULLET, mx, my, target, dmg, BULLET_SPEED, 0f, t)
                p.pierce = t.branch == Branch.PIERCER
                p.big = t.branch == Branch.PIERCER
                projectiles += p
                t.flash = 0.06f
                if (!lite && fx.nextFloat() < 0.6f) casing(t, aim)
                sound(Sfx.SHOOT, if (t.branch == Branch.MINIGUN) 0.14f else 0.25f)
            }
            TowerType.SHOTGUN -> {
                if (t.branch == Branch.SLUG) fireSlug(t, s, aim, dmg, range) else firePellets(t, s, aim, dmg, range)
                t.flash = 0.08f
                sound(Sfx.SHOTGUN, 0.45f)
            }
            TowerType.FLAMER -> {
                fireFlame(t, s, aim, dmg, range, target)
                t.burning = 0.15f
                period = FLAME_TICK / (if (blizzard) BLIZZARD_RATE else 1f)
                sound(Sfx.FLAME, 0.3f)
            }
            TowerType.ROCKET -> {
                val p = Projectile(Shot.ROCKET, mx, my, target, dmg, if (t.branch == Branch.HEAVY) 4.2f else ROCKET_SPEED, s.extra, t)
                if (t.branch == Branch.HEAVY) {
                    p.bossMul = HEAVY_BOSS
                    p.big = true
                }
                if (t.branch == Branch.CLUSTER) p.cluster = s.extra2 * rules.damageMul * (1f + t.aura)
                projectiles += p
                t.flash = 0.1f
                sound(Sfx.ROCKET, 0.45f)
            }
            TowerType.SAM -> fireMissiles(t, s, aim, target, dmg, range)
            TowerType.TESLA -> {
                fireTesla(t, s, target, dmg)
                sound(Sfx.ZAP, 0.4f)
            }
            TowerType.SNIPER -> fireSniper(t, s, target, dmg, mx, my)
            TowerType.MINES, TowerType.RADAR, TowerType.QUARRY -> Unit
        }
        t.shots++
        t.cooldown = max(t.cooldown, 0f) + period
    }

    private fun casing(t: Tower, aim: Float) {
        val side = aim + 1.57f
        particles += Particle(t.x, t.y, cos(side) * 1.2f, sin(side) * 1.2f - 0.3f, 0.3f, 0.3f, 0.03f, 0xFFE3B341.toInt(), 0f)
    }

    private fun firePellets(t: Tower, s: TowerLevel, aim: Float, dmg: Float, range: Float) {
        val pellets = s.extra.toInt()
        hitList.clear()
        for (p in 0 until pellets) {
            val a = aim + (rng.nextFloat() - 0.5f) * SHOTGUN_SPREAD
            val dx = cos(a)
            val dy = sin(a)
            var hit: Enemy? = null
            var hitT = range
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
            if (hit != null) {
                damage(hit, dmg, t)
                if (hit !in hitList) hitList += hit
            }
        }
        if (t.branch == Branch.ASSAULT) {
            for (e in hitList) if (e.alive && !e.type.boss) e.knock = max(e.knock, s.extra2)
        }
    }

    /** One heavy slug through everyone in a line, up to its pierce count. */
    private fun fireSlug(t: Tower, s: TowerLevel, aim: Float, dmg: Float, range: Float) {
        val dx = cos(aim)
        val dy = sin(aim)
        hitList.clear()
        val along = HashMap<Enemy, Float>()
        for (e in enemies) {
            if (!e.alive || e.type.air) continue
            val ex = e.x - t.x
            val ey = e.y - t.y
            val proj = ex * dx + ey * dy
            if (proj < 0f || proj > range) continue
            val perp2 = ex * ex + ey * ey - proj * proj
            val rr = e.type.radius + 0.14f
            if (perp2 <= rr * rr) {
                hitList += e
                along[e] = proj
            }
        }
        hitList.sortBy { along[it] }
        val n = min(hitList.size, s.extra.toInt())
        var end = range
        if (n == s.extra.toInt() && n > 0) end = along[hitList[n - 1]] ?: range
        for (k in 0 until n) damage(hitList[k], dmg, t)
        tracers += Tracer(t.x + dx * 0.3f, t.y + dy * 0.3f, t.x + dx * end, t.y + dy * end, 0.14f, 1)
    }

    private fun fireFlame(t: Tower, s: TowerLevel, aim: Float, dmg: Float, range: Float, target: Enemy) {
        val r2 = range * range
        val cryo = t.branch == Branch.CRYO
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
            damage(e, dmg * FLAME_TICK, t, pierce = true)
            if (s.extra > 0f) {
                e.burnDps = max(e.burnDps, dmg * AFTERBURN)
                e.burnTimer = max(e.burnTimer, s.extra)
            }
            if (cryo) {
                slow(e, s.extra2 * bossK(e), 0.6f)
                e.frostTimer = max(e.frostTimer, 1f)
            }
        }
        if (t.branch == Branch.NAPALM) {
            t.timer2 -= FLAME_TICK
            if (t.timer2 <= 0f) {
                t.timer2 = NAPALM_EVERY
                patches += Patch(target.x, target.y, NAPALM_LIFE, NAPALM_DPS * rules.damageMul * (1f + t.aura), NAPALM_RADIUS, t)
            }
        }
        val n = if (lite) 1 else 3
        for (k in 0 until n) {
            val a = aim + (fx.nextFloat() - 0.5f) * FLAME_CONE * 1.6f
            val v = range * (1.6f + fx.nextFloat() * 0.8f)
            val color = when {
                cryo -> if (fx.nextBoolean()) 0xFFBFEFFF.toInt() else 0xFF7FD3FF.toInt()
                fx.nextBoolean() -> 0xFFFFB13B.toInt()
                else -> 0xFFFF6A1A.toInt()
            }
            particles += Particle(
                t.x + cos(aim) * 0.35f, t.y + sin(aim) * 0.35f, cos(a) * v, sin(a) * v,
                0.42f, 0.42f, 0.09f, color, 0.35f,
            )
        }
    }

    private fun fireMissiles(t: Tower, s: TowerLevel, aim: Float, target: Enemy, dmg: Float, range: Float) {
        val count = if (t.branch == Branch.SALVO) s.extra.toInt() else 1
        chain.clear()
        chain += target
        while (chain.size < count) chain += findTarget(t, range, chain) ?: break
        for (k in 0 until count) {
            val tg = chain[k % chain.size]
            t.tube = (t.tube + 1) % 4
            val side = if (t.tube % 2 == 0) 0.12f else -0.12f
            val mx = t.x + cos(aim) * 0.36f - sin(aim) * side
            val my = t.y + sin(aim) * 0.36f + cos(aim) * side
            val p = Projectile(Shot.MISSILE, mx, my, tg, dmg, MISSILE_SPEED, 0f, t)
            if (t.branch == Branch.LONGBOW) {
                p.bossMul = LONGBOW_BOSS
                p.big = true
            }
            projectiles += p
        }
        sound(Sfx.MISSILE, 0.4f)
    }

    private fun fireTesla(t: Tower, s: TowerLevel, target: Enemy, damage: Float) {
        chain.clear()
        val jumps = s.extra.toInt()
        val stun = t.branch == Branch.STUN
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        xs += t.x
        ys += t.y - 0.22f
        var cur: Enemy = target
        var dmg = damage
        for (k in 0 until jumps) {
            chain += cur
            xs += cur.x
            ys += cur.y
            damage(cur, dmg, t)
            if (stun) {
                if (cur.type.boss) {
                    slow(cur, 0.5f, SLOW_TIME)
                } else if (cur.stunTimer <= 0f && cur.stunGuard <= 0f) {
                    cur.stunTimer = s.extra2
                }
            } else {
                slow(cur, s.extra2 * bossK(cur), SLOW_TIME)
            }
            dmg *= TESLA_DECAY
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
        bolts += Bolt(outX.toFloatArray(), outY.toFloatArray(), 0.16f, if (stun) 0xFFFFE36B.toInt() else BOLT)
    }

    private fun fireSniper(t: Tower, s: TowerLevel, target: Enemy, dmg: Float, mx: Float, my: Float) {
        val pierce = if (t.branch == Branch.HEAVY_CAL) 1f else 0.5f
        val marker = t.branch == Branch.MARKER
        if (marker) target.markTimer = max(target.markTimer, s.extra)
        damage(target, dmg, t, armorPierce = pierce)
        tracers += Tracer(mx, my, target.x, target.y, 0.16f, if (marker) 3 else 2)
        t.flash = 0.12f
        val n = if (lite) 2 else 5
        for (k in 0 until n) {
            val a = fx.nextFloat() * 6.283f
            particles += Particle(target.x, target.y, cos(a) * 1.2f, sin(a) * 1.2f, 0.25f, 0.25f, 0.04f, 0xFFFFE9B0.toInt(), 0f)
        }
        sound(Sfx.SNIPER, 0.5f)
    }

    private fun updateMine(t: Tower, dt: Float) {
        val s = t.stats
        if (t.branch == Branch.SPIKES) {
            val dps = damageOf(t)
            for (e in enemies) {
                if (!e.alive || e.type.air) continue
                if (!near(e.x, e.y, t.x, t.y, s.range + e.type.radius * 0.5f)) continue
                damage(e, dps * dt, t, pierce = true)
                slow(e, s.extra * bossK(e), 0.3f)
            }
            return
        }
        if (!t.armed) {
            if (t.cooldown <= 0f) {
                t.armed = true
                t.flash = 0.25f
            }
            return
        }
        var triggered = false
        for (e in enemies) {
            if (e.alive && !e.type.air && near(e.x, e.y, t.x, t.y, MINE_TRIGGER + e.type.radius * 0.5f)) {
                triggered = true
                break
            }
        }
        if (!triggered) return
        val dmg = damageOf(t)
        val blast = s.range
        for (e in enemies) {
            if (!e.alive || e.type.air) continue
            val dx = e.x - t.x
            val dy = e.y - t.y
            val d = sqrt(dx * dx + dy * dy)
            if (d > blast + e.type.radius) continue
            val k = (1f - 0.4f * d / blast).coerceIn(0.6f, 1f)
            damage(e, dmg * k, t)
        }
        explosion(t.x, t.y, blast * 0.9f)
        shake = max(shake, if (t.branch == Branch.LANDMINE) 0.3f else 0.15f)
        t.armed = false
        t.cooldown = 1f / rateOf(t)
        sound(Sfx.MINE, if (t.branch == Branch.LANDMINE) 0.8f else 0.55f)
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
                if (p.kind == Shot.ROCKET || p.kind == Shot.MISSILE) {
                    p.trail -= dt
                    if (p.trail <= 0f) {
                        p.trail = if (lite) 0.08f else 0.03f
                        val c = if (p.kind == Shot.ROCKET) 0xFF9A9A9A.toInt() else 0xFFE8E8E8.toInt()
                        particles += Particle(p.x, p.y, (fx.nextFloat() - 0.5f) * 0.2f, (fx.nextFloat() - 0.5f) * 0.2f, 0.5f, 0.5f, 0.05f, c, 0.16f)
                    }
                }
            }
        }
        projectiles.removeAll { !it.alive }
        projectiles.addAll(incomingShots)
        incomingShots.clear()
    }

    private fun retarget(p: Projectile): Enemy? {
        var best: Enemy? = null
        var bestD = 2.5f * 2.5f
        for (e in enemies) {
            if (!e.alive || !e.type.air || !e.visible) continue
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

    private fun splash(x: Float, y: Float, radius: Float, dmg: Float, source: Tower?, bossMul: Float = 1f) {
        for (e in enemies) {
            if (!e.alive || e.type.air) continue
            val dx = e.x - x
            val dy = e.y - y
            val d = sqrt(dx * dx + dy * dy)
            if (d > radius + e.type.radius) continue
            val k = (1f - 0.5f * d / radius).coerceIn(0.5f, 1f)
            damage(e, dmg * k * (if (e.type.boss) bossMul else 1f), source)
        }
    }

    private fun impact(p: Projectile) {
        p.alive = false
        when (p.kind) {
            Shot.BULLET -> {
                p.target?.let { if (it.alive) damage(it, p.damage, p.source, pierce = p.pierce) }
                particles += Particle(p.x, p.y, 0f, 0f, 0.08f, 0.08f, 0.07f, 0xFFFFE38A.toInt(), 0f)
            }
            Shot.ROCKET -> {
                explosion(p.x, p.y, p.splash)
                splash(p.x, p.y, p.splash, p.damage, p.source, p.bossMul)
                if (p.cluster > 0f) {
                    for (k in 0 until CLUSTER_BOMBLETS) {
                        val a = fx.nextFloat() * 6.283f
                        val r = 0.4f + fx.nextFloat() * 0.8f
                        val b = Projectile(Shot.BOMBLET, p.x, p.y, null, p.cluster, 3.2f + fx.nextFloat(), 0.5f, p.source)
                        b.tx = p.x + cos(a) * r
                        b.ty = p.y + sin(a) * r
                        incomingShots += b
                    }
                }
                shake = max(shake, if (p.big) 0.25f else 0.12f)
                sound(Sfx.BOOM, if (p.big) 0.8f else 0.5f)
            }
            Shot.MISSILE -> {
                p.target?.let { if (it.alive) damage(it, p.damage * (if (it.type.boss) p.bossMul else 1f), p.source) }
                explosion(p.x, p.y, 0.5f, ground = false)
                sound(Sfx.BOOM, 0.3f)
            }
            Shot.BOMBLET -> {
                explosion(p.x, p.y, p.splash)
                splash(p.x, p.y, p.splash, p.damage, p.source)
            }
        }
    }

    private fun updatePatches(dt: Float) {
        for (p in patches) {
            p.life -= dt
            for (e in enemies) {
                if (!e.alive || e.type.air) continue
                if (near(e.x, e.y, p.x, p.y, p.radius + e.type.radius * 0.5f)) damage(e, p.dps * dt, p.source, pierce = true)
            }
            if (!lite && fx.nextFloat() < dt * 8f) flame(p.x, p.y, 1, p.radius * 1.4f)
        }
        patches.removeAll { it.life <= 0f }
    }

    private fun updateBombs(dt: Float) {
        plane?.let {
            it.y -= PLANE_SPEED * dt
            if (it.y < -3f) plane = null
        }
        if (bombs.isEmpty()) return
        val iter = bombs.iterator()
        while (iter.hasNext()) {
            val b = iter.next()
            b.timer -= dt
            if (b.timer > 0f) continue
            iter.remove()
            explosion(b.x, b.y, AIRSTRIKE_RADIUS)
            splash(b.x, b.y, AIRSTRIKE_RADIUS, b.damage, null)
            shake = max(shake, 0.3f)
            sound(Sfx.BOOM, 0.8f)
        }
    }

    // --- effects --------------------------------------------------------------------------

    private fun explosion(x: Float, y: Float, radius: Float, ground: Boolean = true) {
        rings += Ring(x, y, radius, 0.35f, 0.35f, 0xFFFFC95A.toInt())
        blasts += Blast(x, y, radius, ground)
        if (blasts.size > MAX_BLASTS) blasts.removeAt(0)
        val n = ((10 + radius * 10) * (if (lite) 0.35f else 1f)).toInt()
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
        val smoke = if (lite) 1 else 4
        for (k in 0 until smoke) {
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
        val cap = if (lite) MAX_PARTICLES_LITE else MAX_PARTICLES
        if (particles.size > cap) particles.subList(0, particles.size - cap).clear()
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
            t.y -= (if (t.size > 1.2f) 0.35f else 0.7f) * dt
            t.life -= dt
        }
        texts.removeAll { it.life <= 0f }
        for (b in bolts) b.life -= dt
        bolts.removeAll { it.life <= 0f }
        for (t in tracers) t.life -= dt
        tracers.removeAll { it.life <= 0f }
        for (r in rings) r.life -= dt
        rings.removeAll { it.life <= 0f }
        for (c in corpses) c.life -= dt
        corpses.removeAll { it.life <= 0f }
        for (b in blasts) b.age += dt
        blasts.removeAll { it.age >= it.life }
    }

    // --- for tests ------------------------------------------------------------------------

    /** Starts the fight without sending a wave. */
    internal fun beginForTest() {
        phase = Phase.RUNNING
    }

    /** Puts an enemy [dist] cells along a road right away. */
    internal fun spawnForTest(type: EnemyType, dist: Float, path: Int = 0, hpMul: Float = 1f): Enemy {
        val e = spawnEnemy(type, path, hpMul, 0, dist)
        enemies.addAll(incoming)
        incoming.clear()
        remaining[0] = (remaining[0] ?: 0) + 1
        return e
    }

    // --- saving ---------------------------------------------------------------------------

    /** The state of the fight as text lines; effects in the air are left out. */
    fun save(): String {
        val sb = StringBuilder()
        fun put(key: String, vararg values: Any) {
            sb.append(key).append('=')
            values.joinTo(sb, ",")
            sb.append('\n')
        }
        put("money", money)
        put("lives", lives)
        put("phase", phase.name)
        put("waves", wavesStarted)
        put("countdown", countdown)
        put("time", time, battleTime)
        put("stats", kills, leaked, bossKills, ghostKills, built, airstrikes, cleared, maxMoney, branchMask, typeMask, abilitiesUsed)
        put("cool", *cooldowns.toTypedArray())
        put("weather", weather, weatherWait)
        if (clearedCells.isNotEmpty()) put("cleared", *clearedCells.toTypedArray())
        for ((w, n) in remaining) put("remain", w, n)
        for (t in towers) {
            put(
                "tower", t.type.name, t.col, t.row, t.level, t.branch?.name ?: "-", t.mode.name,
                t.invested, t.kills, t.damageDealt, t.cooldown, if (t.armed) 1 else 0,
            )
        }
        for (e in enemies) {
            if (!e.alive) continue
            put(
                "enemy", e.type.name, e.pathIndex, e.dist, e.hp, e.hpMul, e.wave, e.lane, e.reward, e.shield,
                e.slowTimer, e.slowAmount, e.burnTimer, e.burnDps, e.stunTimer, e.markTimer, e.frostTimer, e.jamTimer,
            )
        }
        for (a in active) put("group", a.wave, a.index, a.spawned, a.timer)
        for (p in patches) put("patch", p.x, p.y, p.life, p.dps, p.radius)
        for (b in bombs) put("bomb", b.x, b.y, b.timer, b.damage)
        return sb.toString()
    }

    /** Puts back a fight from [save]; meant for a fresh world of the same level and rules. */
    fun restore(text: String) {
        for (line in text.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq)
            val f = line.substring(eq + 1).split(',')
            fun i(k: Int) = f[k].toInt()
            fun x(k: Int) = f[k].toFloat()
            when (key) {
                "money" -> money = i(0)
                "lives" -> lives = i(0)
                "phase" -> phase = Phase.valueOf(f[0])
                "waves" -> wavesStarted = i(0)
                "countdown" -> countdown = x(0)
                "time" -> {
                    time = x(0)
                    battleTime = x(1)
                }
                "stats" -> {
                    kills = i(0)
                    leaked = i(1)
                    bossKills = i(2)
                    ghostKills = i(3)
                    built = i(4)
                    airstrikes = i(5)
                    cleared = i(6)
                    maxMoney = i(7)
                    branchMask = i(8)
                    typeMask = i(9)
                    abilitiesUsed = i(10)
                }
                "cool" -> for (k in cooldowns.indices) cooldowns[k] = f.getOrNull(k)?.toFloatOrNull() ?: 0f
                "weather" -> {
                    weather = x(0)
                    weatherWait = x(1)
                }
                "cleared" -> for (s in f) {
                    val cell = s.toInt()
                    map.clear(cell % COLS, cell / COLS)
                    clearedCells += cell
                }
                "remain" -> remaining[i(0)] = i(1)
                "tower" -> {
                    val t = Tower(TowerType.valueOf(f[0]), i(1), i(2))
                    t.level = i(3)
                    t.branch = if (f[4] == "-") null else Branch.valueOf(f[4])
                    t.mode = TargetMode.valueOf(f[5])
                    t.invested = i(6)
                    t.kills = i(7)
                    t.damageDealt = x(8)
                    t.cooldown = x(9)
                    t.armed = i(10) != 0
                    t.age = 10f
                    towers += t
                }
                "enemy" -> {
                    val path = i(1).coerceIn(0, map.paths.size - 1)
                    val e = Enemy(EnemyType.valueOf(f[0]), map.paths[path], path, x(4), i(5), x(6), i(7))
                    e.dist = x(2)
                    e.hp = x(3)
                    e.shield = x(8)
                    e.slowTimer = x(9)
                    e.slowAmount = x(10)
                    e.burnTimer = x(11)
                    e.burnDps = x(12)
                    e.stunTimer = x(13)
                    e.markTimer = x(14)
                    e.frostTimer = x(15)
                    e.jamTimer = x(16)
                    e.age = 10f
                    place(e)
                    e.seen = onMap(e)
                    if (e.seen) sighted += e.type
                    enemies += e
                }
                "group" -> {
                    val w = wave(i(0)) ?: continue
                    val g = w.groups.getOrNull(i(1)) ?: continue
                    val a = ActiveGroup(g, w.index, i(1))
                    a.spawned = i(2)
                    a.timer = x(3)
                    active += a
                }
                "patch" -> patches += Patch(x(0), x(1), x(2), x(3), x(4), null)
                "bomb" -> bombs += Bomb(x(0), x(1), x(2), x(3))
            }
        }
    }

    companion object {
        const val BETWEEN_WAVES = 14f
        const val EARLY_BONUS_PER_SECOND = 2
        const val WAVE_BONUS = 12
        const val WAVE_BONUS_STEP = 2
        const val APC_CREW = 4
        const val MOTHER_BROOD = 6
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
        const val TESLA_DECAY = 0.8f
        const val MAX_PARTICLES = 700
        const val MAX_BLASTS = 48
        const val MAX_PARTICLES_LITE = 150
        const val MAX_CORPSES = 40
        const val CORPSE_TIME = 4f
        const val SHIELD_DELAY = 3f
        const val SHIELD_REGEN = 0.25f
        const val SAPPER_RADIUS = 1.3f
        const val JAM_TIME = 4f
        const val JAM_COOLDOWN = 6f
        const val ICE_SPEED = 1.4f
        const val KNOCK_SPEED = 3f
        const val STUN_GUARD = 1.2f
        const val MARK_BONUS = 1.3f
        const val FROST_BONUS = 1.25f
        const val SPIN_UP = 1.5f
        const val SPIN_DOWN = 2f
        const val HEAVY_BOSS = 1.5f
        const val LONGBOW_BOSS = 2f
        const val CLUSTER_BOMBLETS = 6
        const val NAPALM_EVERY = 0.5f
        const val NAPALM_LIFE = 3f
        const val NAPALM_DPS = 35f
        const val NAPALM_RADIUS = 0.55f
        const val MINE_TRIGGER = 0.45f
        const val STORM_RANGE = 0.7f
        const val BLIZZARD_RATE = 0.75f
        const val FIRST_WEATHER = 35f
        const val WEATHER_TIME = 8f
        const val WEATHER_GAP = 40f
        const val WEATHER_GAP_RANDOM = 30f
        const val BOSS_ALERT = 2.4f
        const val AIRSTRIKE_DAMAGE = 220f
        const val AIRSTRIKE_RADIUS = 1.0f
        const val PLANE_SPEED = 12f
        const val FREEZE_TIME = 4f
        const val REPAIR_LIVES = 3
        const val CLEAR_COST = 40
        const val CLEAR_STEP = 10
        const val MAX_QUARRIES = 4

        val GOLD = 0xFFFFD54A.toInt()
        val RED = 0xFFFF4B4B.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val HEAL = 0xFF6BE07A.toInt()
        val BOLT = 0xFF6FE3FF.toInt()
        val SAND = 0xFFE8C27A.toInt()
        val ICE = 0xFFBFE6FF.toInt()
    }
}
