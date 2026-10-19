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

    /** Diggers: seconds until the next dive, and left underground. */
    var burrow = 2.5f
    var under = 0f

    /** For ghosts: a radar sees it right now. */
    var revealed = false
    var seen = false

    val remaining: Float get() = path.length - dist
    val underground: Boolean get() = under > 0f
    val visible: Boolean get() = (!type.stealth || revealed) && !underground
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

    /** Bonuses from radars nearby, worked out every step; [haste] is the engineer's overcharge. */
    var aura = 0f
    var rangeBonus = 0f
    var haste = 0f

    val stats: TowerLevel get() = branch?.stats ?: type.levels[level]
    val canUpgrade: Boolean get() = branch == null && level < type.levels.size - 1
    val atFork: Boolean get() = branch == null && level == type.levels.size - 1 && type.branches.isNotEmpty()

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

    /** Fired by the commander or his sentry gun: what it does is his experience. */
    var hero = false
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

/** 0 pellet, 1 slug, 2 sniper, 3 marking shot, 4 railgun. */
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

/** A lava bomb of an eruption, landing on ([x], [y]) when [timer] runs out. */
class Lava(val x: Float, val y: Float, var timer: Float, val fall: Float = timer)

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
    var titanKills = 0
        private set

    /** Kills of each kind of enemy, by [EnemyType.ordinal]. */
    val killsOf = IntArray(EnemyType.entries.size)
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

    /** The VIP reserve already came, so the next fall is the end. */
    var reserveUsed = false
        private set

    /** An advert already brought the reserves back once this battle. */
    var revived = false
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
    val lava = ArrayList<Lava>()
    var plane: Plane? = null
        private set

    /** The commander, when the rules bring one. */
    var commander: Commander? = rules.commander?.let { startCommander(it) }
        private set
    val sentries = ArrayList<Sentry>()
    val traps = ArrayList<Trap>()

    var baseFlash = 0f
        private set
    var shake = 0f
        private set
    var banner: Banner? = null
        private set

    /** Seconds left of the current sandstorm, blizzard or eruption. */
    var weather = 0f
        private set
    private var weatherWait = FIRST_WEATHER
    private var lavaWait = 0f
    var freezeTimer = 0f
        private set

    /** Counts down after a boss shows up, for the siren and the slow motion. */
    var bossAlert = 0f
        private set
    val cooldowns = FloatArray(Ability.entries.size) { Ability.entries[it].cooldown * rules.cooldownMul * 0.5f }

    /** The next wave's timer stands still: the tutorial waits for the player. */
    var holdWaves = false

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
    val eruption: Boolean get() = weather > 0f && map.biome == Biome.VOLCANO

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
            commander?.let { updateCommander(it, dt) }
            updateSentries(dt)
            updateTraps(dt)
            updateProjectiles(dt)
            updatePatches(dt)
            updateBombs(dt)
            updateLava(dt)
            enemies.removeAll { !it.alive }
            enemies.addAll(incoming)
            incoming.clear()
            if (countdown >= 0f && !holdWaves) {
                countdown -= dt
                if (countdown <= 0f) startWave()
            }
            if (money > maxMoney) maxMoney = money
            if (lives <= 0 && rules.reserve && !reserveUsed) {
                callReserve()
            } else if (lives <= 0) {
                lives = 0
                phase = Phase.LOST
                sound(Sfx.LOSE, 1f)
            } else if (active.isEmpty() && nextWave == null && enemies.isEmpty()) {
                phase = Phase.WON
                sound(Sfx.WIN, 1f)
            }
        } else {
            // Before the first wave the commander can already take up his post.
            if (phase == Phase.READY) commander?.let { walk(it, dt) }
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

    /** VIP's second chance: the base holds with a few fresh lives. */
    private fun callReserve() {
        reserveUsed = true
        reinforce(Vip.RESERVE_LIVES, L("Второй шанс VIP: +%d жизней", "VIP second chance: +%d lives"))
    }

    /** A lost battle goes on after an advert, with a few fresh lives; once a battle. */
    fun revive(): Boolean {
        if (phase != Phase.LOST || revived) return false
        revived = true
        phase = Phase.RUNNING
        reinforce(Ads.REVIVE_LIVES, L("Второй шанс: +%d жизней", "Second chance: +%d lives"))
        return true
    }

    private fun reinforce(fresh: Int, sub: L) {
        lives = min(maxLives, fresh)
        val bx = map.baseCol + 0.5f
        val by = map.baseRow + 0.5f
        rings += Ring(bx, by, 1.8f, 0.9f, 0.9f, GOLD)
        texts += FloatText(bx, by - 0.4f, "+$lives", GOLD, 1.6f, 1.4f)
        banner = Banner({ L("РЕЗЕРВ ВСТУПИЛ В БОЙ", "RESERVES ARRIVE").t }, { sub.f(fresh) }, GOLD, 3f)
        shake = max(shake, 0.3f)
        sound(Sfx.REPAIR, 1f)
    }

    // --- building -------------------------------------------------------------------------

    fun towerAt(c: Int, r: Int): Tower? = towers.firstOrNull { it.col == c && it.row == r }

    fun buildCost(type: TowerType): Int = (type.levels[0].cost * rules.buildMul).roundToInt()

    fun upgradeCost(t: Tower): Int = if (t.canUpgrade) (t.type.levels[t.level + 1].cost * rules.upgradeMul).roundToInt() else 0

    fun branchCost(b: Branch): Int = (b.stats.cost * rules.upgradeMul).roundToInt()

    fun sellValue(t: Tower): Int = (t.invested * rules.refund).toInt()

    val towerLimitReached: Boolean get() = towers.size >= rules.maxTowers

    /**
     * Quarries are capped so endless games are won with guns, not with a mining town;
     * railguns so that they stay a VIP's ace and do not become the whole defence.
     */
    fun limitReached(type: TowerType): Boolean = when (type) {
        TowerType.QUARRY -> towers.count { it.type == type } >= MAX_QUARRIES
        TowerType.RAIL -> towers.count { it.type == type } >= MAX_RAILS
        else -> false
    }

    /** The cell suits this kind of tower: grass for most, road for mines. */
    fun canPlace(type: TowerType, c: Int, r: Int): Boolean {
        if (over || towerAt(c, r) != null) return false
        return if (type.role == Role.ROAD) map.mineable(c, r) else map.buildable(c, r)
    }

    fun canBuild(type: TowerType, c: Int, r: Int): Boolean =
        type in rules.towers && !towerLimitReached && !limitReached(type) && canPlace(type, c, r) && money >= buildCost(type)

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
        val dust = when (map.biome) {
            Biome.WINTER -> 0xFFE8F0F8.toInt()
            Biome.VOLCANO -> ASH
            else -> 0xFFB9A98A.toInt()
        }
        puff(t.x, t.y, if (lite) 4 else 8, dust)
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
            'T' -> when (map.biome) {
                Biome.WINTER -> 0xFF2F5A4A.toInt()
                Biome.VOLCANO -> 0xFF3A2E28.toInt()
                else -> 0xFF3E7A35.toInt()
            }
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
        flyover(x, y, airstrikeDamage)
        return true
    }

    /** The plane and its three bombs, also flown in the intro; gives the seconds until the middle bomb goes off. */
    internal fun flyover(x: Float, y: Float, damage: Float): Float {
        val start = ROWS + 2.5f
        plane = Plane(x, start)
        for (k in 0 until 3) {
            val by = y + (1 - k) * 0.7f
            val bx = x + (fx.nextFloat() - 0.5f) * 0.3f
            bombs += Bomb(bx, by, (start - by) / PLANE_SPEED + Bomb.BOMB_FALL, damage)
        }
        sound(Sfx.PLANE, 0.8f)
        return (start - y) / PLANE_SPEED + Bomb.BOMB_FALL
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
            if (eruption) erupt(dt)
            if (weather <= 0f) {
                weather = 0f
                weatherWait = WEATHER_GAP + rng.nextFloat() * WEATHER_GAP_RANDOM
            }
            return
        }
        weatherWait -= dt
        if (weatherWait > 0f) return
        weather = WEATHER_TIME
        banner = when (map.biome) {
            Biome.DESERT -> Banner({ L("ПЕСЧАНАЯ БУРЯ", "SANDSTORM").t }, { L("Дальность отрядов −30%", "Squad range −30%").t }, SAND, 2.6f)
            Biome.VOLCANO -> Banner({ L("ИЗВЕРЖЕНИЕ", "ERUPTION").t }, { L("Лава жжёт врагов, но может задеть отряды", "Lava burns enemies but may hit squads").t }, LAVA, 2.6f)
            else -> Banner({ L("МЕТЕЛЬ", "BLIZZARD").t }, { L("Отряды стреляют на 25% реже", "Squads fire 25% slower").t }, ICE, 2.6f)
        }
        if (map.biome == Biome.VOLCANO) {
            lavaWait = LAVA_EVERY
            shake = max(shake, 0.4f)
            sound(Sfx.BOOM, 0.8f)
        }
    }

    /** Lava bombs rain down through an eruption: mostly where enemies walk, now and then on a squad. */
    private fun erupt(dt: Float) {
        lavaWait -= dt
        if (lavaWait > 0f) return
        lavaWait = LAVA_EVERY * (0.7f + rng.nextFloat() * 0.6f)
        val walkers = enemies.filter { it.alive && !it.type.air && onMap(it) }
        var x: Float
        var y: Float
        if (towers.isNotEmpty() && (walkers.isEmpty() || rng.nextFloat() < LAVA_ON_SQUADS)) {
            val t = towers[rng.nextInt(towers.size)]
            x = t.x
            y = t.y
        } else if (walkers.isNotEmpty()) {
            // Where it will be when the bomb lands.
            val e = walkers[rng.nextInt(walkers.size)]
            x = e.x + e.dirX * speedOf(e) * LAVA_FALL
            y = e.y + e.dirY * speedOf(e) * LAVA_FALL
        } else {
            return
        }
        x += (rng.nextFloat() - 0.5f) * 0.6f
        y += (rng.nextFloat() - 0.5f) * 0.6f
        lava += Lava(x.coerceIn(0.3f, COLS - 0.3f), y.coerceIn(0.3f, ROWS - 0.3f), LAVA_FALL)
    }

    val lavaDamage: Float get() = LAVA_DAMAGE * (1f + 0.1f * max(0, wavesStarted - 1))

    private fun updateLava(dt: Float) {
        if (lava.isEmpty()) return
        val iter = lava.iterator()
        while (iter.hasNext()) {
            val b = iter.next()
            b.timer -= dt
            if (b.timer > 0f) continue
            iter.remove()
            val dmg = lavaDamage
            for (e in enemies) {
                if (!e.alive || e.type.air || e.type.fireproof || !near(e.x, e.y, b.x, b.y, LAVA_RADIUS + e.type.radius)) continue
                damage(e, dmg, null, pierce = true)
                ignite(e, LAVA_BURN, LAVA_BURN_TIME)
            }
            for (t in towers) if (near(t.x, t.y, b.x, b.y, LAVA_RADIUS)) t.jammed = max(t.jammed, LAVA_JAM)
            commander?.let { if (near(it.x, it.y, b.x, b.y, LAVA_RADIUS)) it.jammed = max(it.jammed, LAVA_JAM) }
            patches += Patch(b.x, b.y, LAVA_POOL, LAVA_POOL_DPS, LAVA_RADIUS * 0.8f, null)
            explosion(b.x, b.y, LAVA_RADIUS)
            shake = max(shake, 0.15f)
            sound(Sfx.BOOM, 0.45f)
        }
    }

    /** Sets [e] on fire, unless it wears a fire suit. */
    private fun ignite(e: Enemy, dps: Float, time: Float) {
        if (e.type.fireproof) return
        e.burnDps = max(e.burnDps, dps)
        e.burnTimer = max(e.burnTimer, time)
    }

    // --- support towers -------------------------------------------------------------------

    private fun radarRange(r: Tower): Float = r.stats.range * rules.rangeMul

    private fun updateSupport() {
        radars.clear()
        for (t in towers) {
            t.aura = 0f
            t.rangeBonus = 0f
            t.haste = 0f
            if (t.type == TowerType.RADAR && t.jammed <= 0f) radars += t
        }
        if (radars.isEmpty()) {
            for (e in enemies) e.revealed = false
        } else {
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
        // The commander's rally adds to whatever a radar gives; the engineer's overcharge speeds them up.
        val c = commander ?: return
        if (c.rally <= 0f && c.overcharge <= 0f) return
        for (t in towers) {
            if (t.type.role == Role.SUPPORT || t.type.role == Role.ECONOMY) continue
            if (c.rally > 0f && near(t.x, t.y, c.x, c.y, RALLY_RADIUS)) t.aura += RALLY_BONUS
            if (c.overcharge > 0f && near(t.x, t.y, c.x, c.y, OVERCHARGE_RADIUS)) t.haste = OVERCHARGE_BONUS
        }
    }

    /** A sapper next to a jammer cannot do its job. */
    private fun suppressed(e: Enemy): Boolean =
        radars.any { it.branch == Branch.JAMMER && near(e.x, e.y, it.x, it.y, radarRange(it)) }

    // --- enemies --------------------------------------------------------------------------

    private fun bossK(e: Enemy): Float = if (e.type.boss) 0.5f else 1f

    private fun slow(e: Enemy, amount: Float, time: Float) {
        if (amount <= 0f || e.type.unstoppable) return
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
                EnemyType.TITAN -> stomp(e, dt)
                EnemyType.DIGGER -> burrow(e, dt)
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
        commander?.let {
            if (near(it.x, it.y, e.x, e.y, SAPPER_RADIUS)) {
                it.jammed = max(it.jammed, JAM_TIME)
                hit = true
            }
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

    /** The titan's stomp: every few seconds the ground shakes and the squads around it stop firing. */
    private fun stomp(e: Enemy, dt: Float) {
        e.jamTimer -= dt
        if (e.jamTimer > 0f || !onMap(e)) return
        e.jamTimer = STOMP_EVERY
        e.jamPulse = 0.5f
        for (t in towers) if (near(t.x, t.y, e.x, e.y, STOMP_RADIUS)) t.jammed = max(t.jammed, STOMP_TIME)
        commander?.let { if (near(it.x, it.y, e.x, e.y, STOMP_RADIUS)) it.jammed = max(it.jammed, STOMP_TIME) }
        rings += Ring(e.x, e.y, STOMP_RADIUS, 0.6f, 0.6f, Colors.alpha(DIRT, 0.95f))
        puff(e.x, e.y, if (lite) 6 else 14, DIRT)
        shake = max(shake, 0.3f)
        sound(Sfx.MINE, 0.7f)
    }

    /** Diggers dive now and then; underground nothing sees or hurts them, and the fire on them goes out. */
    private fun burrow(e: Enemy, dt: Float) {
        if (e.under > 0f) {
            e.under -= dt
            if (e.under <= 0f) {
                e.under = 0f
                e.burrow = BURROW_EVERY
                puff(e.x, e.y, if (lite) 3 else 7, DIRT)
            }
            return
        }
        if (!onMap(e)) return
        e.burrow -= dt
        if (e.burrow > 0f) return
        e.under = BURROW_TIME
        e.burnTimer = 0f
        puff(e.x, e.y, if (lite) 3 else 7, DIRT)
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

    /** Hits [e] for [raw] damage before armor; returns what actually came off. [hero] is the commander's. */
    fun damage(e: Enemy, raw: Float, source: Tower?, pierce: Boolean = false, armorPierce: Float = 0f, hero: Boolean = false): Float {
        if (!e.alive || raw <= 0f || e.underground) return 0f
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
        if (hero) commander?.let { gainXp(it, total) }
        if (!pierce) e.hitFlash = 0.07f
        if (damageNumbers && total >= 25f && e.numberTimer <= 0f) {
            e.numberTimer = 0.3f
            texts += FloatText(e.x + (fx.nextFloat() - 0.5f) * 0.3f, e.y - e.type.radius - 0.1f, total.roundToInt().toString(), 0xFFFFF1C8.toInt(), 0.6f, 0.75f)
        }
        if (e.hp <= 0f) kill(e, source, hero)
        return total
    }

    private fun kill(e: Enemy, source: Tower?, hero: Boolean) {
        e.alive = false
        val reward = max(1, (e.reward * rules.rewardMul).roundToInt())
        money += reward
        kills++
        if (e.type.boss) bossKills++
        if (e.type == EnemyType.GHOST) ghostKills++
        if (e.type == EnemyType.TITAN) titanKills++
        killsOf[e.type.ordinal]++
        if (source != null) source.kills++
        commander?.let {
            if (hero) {
                it.kills++
            } else if (near(e.x, e.y, it.x, it.y, commanderRange(it) + SHARED_REACH)) {
                // He learns from the fighting around him too.
                gainXp(it, e.maxHp * SHARED_XP, dealt = false)
            }
        }
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
            EnemyType.TITAN -> brood(e, EnemyType.SALAMANDER, TITAN_CREW, 0.25f)
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

    private fun rateOf(t: Tower): Float = t.stats.rate * (if (blizzard) BLIZZARD_RATE else 1f) * (1f + t.haste)

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
            TowerType.RAIL -> fireRail(t, aim, dmg)
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
                if (!e.alive || e.type.air || e.underground) continue
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
            for (e in hitList) if (e.alive && !e.type.boss && !e.type.unstoppable) e.knock = max(e.knock, s.extra2)
        }
    }

    /** One heavy slug through everyone in a line, up to its pierce count. */
    private fun fireSlug(t: Tower, s: TowerLevel, aim: Float, dmg: Float, range: Float) {
        val dx = cos(aim)
        val dy = sin(aim)
        hitList.clear()
        val along = HashMap<Enemy, Float>()
        for (e in enemies) {
            if (!e.alive || e.type.air || e.underground) continue
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
            if (!e.alive || e.type.air || e.underground) continue
            val dx = e.x - t.x
            val dy = e.y - t.y
            val d2 = dx * dx + dy * dy
            if (d2 > r2) continue
            var diff = atan2(dy, dx) - aim
            while (diff > 3.1416f) diff -= 6.2832f
            while (diff < -3.1416f) diff += 6.2832f
            if (abs(diff) > FLAME_CONE && d2 > 0.16f) continue
            if (e.type.fireproof && !cryo) continue
            damage(e, dmg * FLAME_TICK, t, pierce = true)
            if (s.extra > 0f) ignite(e, dmg * AFTERBURN, s.extra)
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
                if (cur.type.boss || cur.type.unstoppable) {
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
                if (!e.alive || e.underground || e in chain || !onMap(e)) continue
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
        bolt(xs, ys, 0.16f, if (stun) 0xFFFFE36B.toInt() else BOLT)
    }

    /** A jagged bolt through the hit points. */
    private fun bolt(xs: List<Float>, ys: List<Float>, life: Float, color: Int) {
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
        bolts += Bolt(outX.toFloatArray(), outY.toFloatArray(), life, color)
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

    /** A slug through everyone on the line, ground and air, up to the edge of the map; armor does not stop it. */
    private fun fireRail(t: Tower, aim: Float, dmg: Float) {
        val dx = cos(aim)
        val dy = sin(aim)
        val end = toEdge(t.x, t.y, dx, dy)
        for (e in enemies) {
            if (!e.alive || e.underground) continue
            val ex = e.x - t.x
            val ey = e.y - t.y
            val proj = ex * dx + ey * dy
            if (proj < 0f || proj > end + e.type.radius) continue
            val perp2 = ex * ex + ey * ey - proj * proj
            val rr = e.type.radius + RAIL_WIDTH
            if (perp2 > rr * rr) continue
            damage(e, dmg * (if (e.type.boss) RAIL_BOSS else 1f), t, armorPierce = 1f)
            val n = if (lite) 2 else 5
            for (k in 0 until n) {
                val a = fx.nextFloat() * 6.283f
                particles += Particle(e.x, e.y, cos(a) * 1.5f, sin(a) * 1.5f, 0.3f, 0.3f, 0.045f, BOLT, 0f)
            }
        }
        tracers += Tracer(t.x + dx * 0.36f, t.y + dy * 0.36f, t.x + dx * end, t.y + dy * end, RAIL_TRACE, 4)
        rings += Ring(t.x + dx * 0.4f, t.y + dy * 0.4f, 0.55f, 0.25f, 0.25f, Colors.alpha(BOLT, 0.9f))
        t.flash = 0.16f
        shake = max(shake, 0.12f)
        sound(Sfx.RAIL, 0.55f)
    }

    /** How far a line from (x, y) along (dx, dy) runs before it leaves the map. */
    private fun toEdge(x: Float, y: Float, dx: Float, dy: Float): Float {
        var t = Float.MAX_VALUE
        if (dx > 1e-4f) t = min(t, (COLS - x) / dx) else if (dx < -1e-4f) t = min(t, -x / dx)
        if (dy > 1e-4f) t = min(t, (ROWS - y) / dy) else if (dy < -1e-4f) t = min(t, -y / dy)
        return if (t == Float.MAX_VALUE) 0f else max(0f, t)
    }

    private fun updateMine(t: Tower, dt: Float) {
        val s = t.stats
        if (t.branch == Branch.SPIKES) {
            val dps = damageOf(t)
            for (e in enemies) {
                if (!e.alive || e.type.air || e.underground) continue
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
            if (e.alive && !e.type.air && !e.underground && near(e.x, e.y, t.x, t.y, MINE_TRIGGER + e.type.radius * 0.5f)) {
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

    // --- the commander ------------------------------------------------------------------

    private fun startCommander(type: CommanderType): Commander {
        // Beside the main road, a little before the base, on the side that is not water.
        val path = map.paths[0]
        val p = FloatArray(4)
        path.pointAt(path.length - COMMANDER_START, p)
        var x = 0f
        var y = 0f
        for (side in floatArrayOf(COMMANDER_SIDE, -COMMANDER_SIDE)) {
            x = (p[0] - p[3] * side).coerceIn(COMMANDER_EDGE, COLS - COMMANDER_EDGE)
            y = (p[1] + p[2] * side).coerceIn(COMMANDER_EDGE, ROWS - COMMANDER_EDGE)
            val c = floor(x).toInt()
            val r = floor(y).toInt()
            if (map.inside(c, r) && !map.isRoad(c, r) && map.terrain(c, r) != '~') break
        }
        val c = Commander(type, x, y)
        c.level = rules.commanderLevel.coerceIn(0, Commander.MAX_LEVEL)
        c.xp = Commander.XP[c.level]
        return c
    }

    /** Sends the commander to (x, y), kept on the map. */
    fun moveCommander(x: Float, y: Float): Boolean {
        val c = commander ?: return false
        if (over) return false
        c.tx = x.coerceIn(COMMANDER_EDGE, COLS - COMMANDER_EDGE)
        c.ty = y.coerceIn(COMMANDER_EDGE, ROWS - COMMANDER_EDGE)
        return true
    }

    fun commanderRange(c: Commander): Float {
        var r = c.stats.range * rules.rangeMul * rules.heroRangeMul
        if (sandstorm) r *= STORM_RANGE
        return r
    }

    fun commanderDamage(c: Commander): Float = c.stats.damage * rules.damageMul * rules.heroDamageMul

    fun skillCooldown(s: Skill): Float = s.cooldown * rules.cooldownMul * rules.heroCooldownMul

    /** Skills count as abilities: a challenge or a day without abilities switches them off too. */
    val skillsAllowed: Boolean get() = rules.abilitiesAllowed

    /** The third skill needs rank [Ranks.THIRD_SKILL]. */
    fun skillOpen(k: Int): Boolean = k < rules.heroSkills

    fun skillReady(k: Int): Boolean {
        val c = commander ?: return false
        return skillOpen(k) && skillsAllowed && phase == Phase.RUNNING && c.jammed <= 0f && c.skillCooldowns[k] <= 0f
    }

    /** What skill [s] gives at the commander's level: damage, or seconds for the rally, the stun, the suppression and the overcharge. */
    fun skillPower(c: Commander, s: Skill): Float = when (s) {
        Skill.GRENADE -> GRENADE_DAMAGE * c.power * rules.damageMul
        Skill.RALLY -> RALLY_TIME * c.power
        Skill.SENTRY -> SENTRY_DAMAGE * c.power * rules.damageMul
        Skill.EMP -> EMP_STUN + 0.3f * c.level
        Skill.PIERCE -> PIERCE_DAMAGE * c.power * rules.damageMul
        Skill.BARRAGE -> BARRAGE_DAMAGE * c.power * rules.damageMul
        Skill.SUPPRESS -> SUPPRESS_TIME * c.power
        Skill.OVERCHARGE -> OVERCHARGE_TIME * c.power
        Skill.MINE -> TRAP_DAMAGE * c.power * rules.damageMul
    }

    /** Fires the commander's skill [k]; false when it is not ready or has nothing to hit. */
    fun useSkill(k: Int): Boolean {
        val c = commander ?: return false
        if (!skillReady(k)) return false
        val skill = c.type.skills[k]
        val done = when (skill) {
            Skill.GRENADE -> grenade(c)
            Skill.RALLY -> rally(c)
            Skill.SENTRY -> sentry(c)
            Skill.EMP -> emp(c)
            Skill.PIERCE -> pierce(c)
            Skill.BARRAGE -> barrage(c)
            Skill.SUPPRESS -> suppress(c)
            Skill.OVERCHARGE -> overcharge(c)
            Skill.MINE -> mine(c)
        }
        if (done) c.skillCooldowns[k] = skillCooldown(skill)
        return done
    }

    private fun walk(c: Commander, dt: Float) {
        val dx = c.tx - c.x
        val dy = c.ty - c.y
        val d = sqrt(dx * dx + dy * dy)
        if (d < 0.005f) {
            c.x = c.tx
            c.y = c.ty
            return
        }
        val step = min(d, c.type.speed * dt)
        c.x += dx / d * step
        c.y += dy / d * step
        c.walked += step
        c.angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    }

    private fun gainXp(c: Commander, amount: Float, dealt: Boolean = true) {
        if (dealt) c.damageDealt += amount
        c.xp += amount * rules.xpMul
        c.earned += amount * rules.xpMul
        while (c.level < Commander.MAX_LEVEL && c.xp >= Commander.XP[c.level + 1]) {
            c.level++
            c.promoted = PROMOTED_GLOW
            rings += Ring(c.x, c.y, 1.2f, 0.6f, 0.6f, GOLD)
            texts += FloatText(c.x, c.y - 0.5f, L("Уровень %d!", "Level %d!").f(c.level + 1), GOLD, 1.5f, 1.25f)
            sound(Sfx.UPGRADE, 0.8f)
        }
    }

    /** He fires only when standing: walking, he just keeps his eyes on the enemy. */
    private fun updateCommander(c: Commander, dt: Float) {
        if (c.flash > 0f) c.flash -= dt
        if (c.promoted > 0f) c.promoted -= dt
        if (c.rally > 0f) c.rally -= dt
        if (c.overcharge > 0f) c.overcharge -= dt
        for (k in c.skillCooldowns.indices) if (c.skillCooldowns[k] > 0f) c.skillCooldowns[k] = max(0f, c.skillCooldowns[k] - dt)
        walk(c, dt)
        if (c.jammed > 0f) {
            c.jammed -= dt
            return
        }
        if (c.cooldown > 0f) c.cooldown -= dt
        if (c.moving) return
        val target = heroTarget(c, commanderRange(c)) ?: return
        val aim = atan2(target.y - c.y, target.x - c.x)
        c.angle = Math.toDegrees(aim.toDouble()).toFloat()
        if (c.cooldown > 0f) return
        val dmg = commanderDamage(c)
        val mx = c.x + cos(aim) * 0.32f
        val my = c.y + sin(aim) * 0.32f
        when (c.type) {
            CommanderType.SERGEANT -> {
                val p = Projectile(Shot.BULLET, mx, my, target, dmg, BULLET_SPEED, 0f, null)
                p.hero = true
                projectiles += p
                c.flash = 0.06f
                sound(Sfx.SHOOT, 0.25f)
            }
            CommanderType.ENGINEER -> {
                zap(c, target, dmg)
                c.flash = 0.1f
                sound(Sfx.ZAP, 0.3f)
            }
            CommanderType.GROM -> {
                val p = Projectile(Shot.ROCKET, mx, my, target, dmg, ROCKET_SPEED, GROM_BLAST, null)
                p.hero = true
                projectiles += p
                c.flash = 0.1f
                sound(Sfx.ROCKET, 0.4f)
            }
        }
        c.shots++
        c.cooldown = max(c.cooldown, 0f) + 1f / (c.stats.rate * (if (blizzard) BLIZZARD_RATE else 1f))
    }

    /** The enemy nearest the base he can hit in [range], or the toughest; [any] includes what his gun cannot reach. */
    private fun heroTarget(c: Commander, range: Float, toughest: Boolean = false, any: Boolean = false): Enemy? {
        var best: Enemy? = null
        var bestScore = 0f
        for (e in enemies) {
            if (!e.alive || !e.visible || !onMap(e)) continue
            if (!any && !(if (e.type.air) c.type.hitsAir else c.type.hitsGround)) continue
            if (!near(e.x, e.y, c.x, c.y, range)) continue
            val score = if (toughest) e.hp + e.shield else -e.remaining
            if (best == null || score > bestScore) {
                best = e
                bestScore = score
            }
        }
        return best
    }

    /** The engineer's lightning: a few jumps between enemies, each weaker, each slowing. */
    private fun zap(c: Commander, target: Enemy, damage: Float) {
        chain.clear()
        val xs = arrayListOf(c.x)
        val ys = arrayListOf(c.y - 0.15f)
        var cur = target
        var dmg = damage
        for (k in 0 until ZAP_JUMPS) {
            chain += cur
            xs += cur.x
            ys += cur.y
            damage(cur, dmg, null, hero = true)
            slow(cur, ZAP_SLOW * bossK(cur), SLOW_TIME)
            dmg *= TESLA_DECAY
            var next: Enemy? = null
            var best = TESLA_JUMP * TESLA_JUMP
            for (e in enemies) {
                if (!e.alive || !e.visible || e in chain || !onMap(e)) continue
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
        bolt(xs, ys, 0.14f, BOLT)
    }

    /** Into the spot where the most walkers are bunched up. */
    private fun grenade(c: Commander): Boolean {
        val reach = commanderRange(c) + GRENADE_REACH
        var best: Enemy? = null
        var most = 0
        for (e in enemies) {
            if (!e.alive || e.type.air || !e.visible || !onMap(e) || !near(e.x, e.y, c.x, c.y, reach)) continue
            var n = 0
            for (o in enemies) if (o.alive && !o.type.air && near(o.x, o.y, e.x, e.y, GRENADE_RADIUS)) n++
            if (n > most) {
                most = n
                best = e
            }
        }
        val t = best ?: return false
        val p = Projectile(Shot.BOMBLET, c.x, c.y, null, skillPower(c, Skill.GRENADE), GRENADE_SPEED, GRENADE_RADIUS, null)
        // Thrown a little ahead of where they walk.
        p.tx = t.x + t.dirX * speedOf(t) * 0.3f
        p.ty = t.y + t.dirY * speedOf(t) * 0.3f
        p.hero = true
        p.big = true
        projectiles += p
        c.angle = Math.toDegrees(atan2(p.ty - c.y, p.tx - c.x).toDouble()).toFloat()
        sound(Sfx.CLICK, 0.5f)
        return true
    }

    private fun rally(c: Commander): Boolean {
        c.rally = skillPower(c, Skill.RALLY)
        rings += Ring(c.x, c.y, RALLY_RADIUS, 0.6f, 0.6f, GOLD)
        texts += FloatText(c.x, c.y - 0.5f, Skill.RALLY.title.t, GOLD, 1.2f, 1.2f)
        sound(Sfx.WAVE, 0.6f)
        return true
    }

    /** One sentry at a time: a new one replaces the old. */
    private fun sentry(c: Commander): Boolean {
        sentries.clear()
        val x = (c.x + 0.35f).coerceIn(COMMANDER_EDGE, COLS - COMMANDER_EDGE)
        val y = (c.y + 0.25f).coerceIn(COMMANDER_EDGE, ROWS - COMMANDER_EDGE)
        sentries += Sentry(x, y, SENTRY_TIME, skillPower(c, Skill.SENTRY))
        rings += Ring(x, y, 0.7f, 0.4f, 0.4f, Colors.alpha(WHITE, 0.8f))
        puff(x, y, if (lite) 4 else 8, 0xFFB9A98A.toInt())
        sound(Sfx.BUILD, 0.7f)
        return true
    }

    private fun updateSentries(dt: Float) {
        if (sentries.isEmpty()) return
        val iter = sentries.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            s.life -= dt
            if (s.flash > 0f) s.flash -= dt
            if (s.life <= 0f) {
                puff(s.x, s.y, 6, 0xFF8A8A8A.toInt())
                iter.remove()
                continue
            }
            if (s.cooldown > 0f) s.cooldown -= dt
            var target: Enemy? = null
            var best = 0f
            for (e in enemies) {
                if (!e.alive || !e.visible || !onMap(e) || !near(e.x, e.y, s.x, s.y, SENTRY_RANGE)) continue
                if (target == null || -e.remaining > best) {
                    target = e
                    best = -e.remaining
                }
            }
            val t = target ?: continue
            val aim = atan2(t.y - s.y, t.x - s.x)
            s.angle = Math.toDegrees(aim.toDouble()).toFloat()
            if (s.cooldown > 0f) continue
            val p = Projectile(Shot.BULLET, s.x + cos(aim) * 0.3f, s.y + sin(aim) * 0.3f, t, s.damage, BULLET_SPEED, 0f, null)
            p.hero = true
            projectiles += p
            s.flash = 0.05f
            s.cooldown = max(s.cooldown, 0f) + 1f / (SENTRY_RATE * (if (blizzard) BLIZZARD_RATE else 1f))
            sound(Sfx.SHOOT, 0.15f)
        }
    }

    private fun emp(c: Commander): Boolean {
        val stun = skillPower(c, Skill.EMP)
        var hit = false
        for (e in enemies) {
            if (!e.alive || !onMap(e) || !near(e.x, e.y, c.x, c.y, EMP_RADIUS)) continue
            hit = true
            if (e.type.boss || e.type.unstoppable) slow(e, 0.5f, stun) else e.stunTimer = max(e.stunTimer, stun)
            damage(e, EMP_DAMAGE * c.power * rules.damageMul, null, pierce = true, hero = true)
        }
        if (!hit) return false
        rings += Ring(c.x, c.y, EMP_RADIUS, 0.5f, 0.5f, BOLT)
        rings += Ring(c.x, c.y, EMP_RADIUS * 0.6f, 0.4f, 0.4f, Colors.alpha(WHITE, 0.8f))
        sound(Sfx.ZAP, 0.9f)
        return true
    }

    /** At the toughest enemy within reach, flier or not, through any armor. */
    private fun pierce(c: Commander): Boolean {
        val t = heroTarget(c, commanderRange(c) * PIERCE_REACH, toughest = true, any = true) ?: return false
        val aim = atan2(t.y - c.y, t.x - c.x)
        c.angle = Math.toDegrees(aim.toDouble()).toFloat()
        val dmg = skillPower(c, Skill.PIERCE) * (if (t.type.boss) PIERCE_BOSS else 1f)
        tracers += Tracer(c.x + cos(aim) * 0.3f, c.y + sin(aim) * 0.3f, t.x, t.y, 0.22f, 2)
        explosion(t.x, t.y, 0.45f, ground = false)
        damage(t, dmg, null, armorPierce = 1f, hero = true)
        c.flash = 0.15f
        shake = max(shake, 0.2f)
        sound(Sfx.SNIPER, 0.9f)
        return true
    }

    private fun barrage(c: Commander): Boolean {
        val reach = commanderRange(c) * BARRAGE_REACH
        val targets = enemies.filter { it.alive && !it.type.air && it.visible && onMap(it) && near(it.x, it.y, c.x, c.y, reach) }
        if (targets.isEmpty()) return false
        val dmg = skillPower(c, Skill.BARRAGE)
        for (k in 0 until BARRAGE_SHOTS) {
            val a = fx.nextFloat() * 6.283f
            val p = Projectile(Shot.ROCKET, c.x + cos(a) * 0.2f, c.y + sin(a) * 0.2f, targets[k % targets.size], dmg, ROCKET_SPEED * (0.75f + 0.08f * k), BARRAGE_BLAST, null)
            p.hero = true
            projectiles += p
        }
        c.flash = 0.15f
        sound(Sfx.ROCKET, 0.8f)
        return true
    }

    /** Pins down everyone around: slowed for a moment, marked for longer, so every squad hits harder. */
    private fun suppress(c: Commander): Boolean {
        val mark = skillPower(c, Skill.SUPPRESS)
        var hit = 0
        for (e in enemies) {
            if (!e.alive || !e.visible || !onMap(e) || !near(e.x, e.y, c.x, c.y, SUPPRESS_RADIUS)) continue
            e.markTimer = max(e.markTimer, mark)
            slow(e, SUPPRESS_SLOW * bossK(e), SUPPRESS_SLOW_TIME)
            if (hit < SUPPRESS_TRACERS) tracers += Tracer(c.x, c.y, e.x, e.y, 0.12f, 1)
            hit++
        }
        if (hit == 0) return false
        rings += Ring(c.x, c.y, SUPPRESS_RADIUS, 0.5f, 0.5f, RED)
        texts += FloatText(c.x, c.y - 0.5f, Skill.SUPPRESS.title.t, RED, 1.2f, 1.2f)
        c.flash = 0.2f
        sound(Sfx.SHOTGUN, 0.8f)
        return true
    }

    private fun overcharge(c: Commander): Boolean {
        c.overcharge = skillPower(c, Skill.OVERCHARGE)
        rings += Ring(c.x, c.y, OVERCHARGE_RADIUS, 0.6f, 0.6f, BOLT)
        for (t in towers) {
            if (t.type.role == Role.SUPPORT || t.type.role == Role.ECONOMY || !near(t.x, t.y, c.x, c.y, OVERCHARGE_RADIUS)) continue
            bolt(listOf(c.x, t.x), listOf(c.y, t.y), 0.3f, BOLT)
        }
        texts += FloatText(c.x, c.y - 0.5f, Skill.OVERCHARGE.title.t, BOLT, 1.2f, 1.2f)
        sound(Sfx.ZAP, 0.8f)
        return true
    }

    /** On the road nearest to him, within reach; two at most, the older one goes. */
    private fun mine(c: Commander): Boolean {
        val reach = commanderRange(c) * TRAP_REACH
        val p = FloatArray(4)
        var bx = 0f
        var by = 0f
        var best = Float.MAX_VALUE
        for (path in map.paths) {
            var d = 0f
            while (d <= path.length) {
                path.pointAt(d, p)
                d += 0.2f
                if (p[0] < 0.3f || p[1] < 0.3f || p[0] > COLS - 0.3f || p[1] > ROWS - 0.3f) continue
                val dd = sqrt((p[0] - c.x) * (p[0] - c.x) + (p[1] - c.y) * (p[1] - c.y))
                if (dd < best) {
                    best = dd
                    bx = p[0]
                    by = p[1]
                }
            }
        }
        if (best > reach) return false
        if (traps.size >= MAX_TRAPS) traps.removeAt(0)
        traps += Trap(bx, by, skillPower(c, Skill.MINE), TRAP_LIFE)
        c.angle = Math.toDegrees(atan2(by - c.y, bx - c.x).toDouble()).toFloat()
        puff(bx, by, if (lite) 3 else 6, 0xFFB9A98A.toInt())
        sound(Sfx.BUILD, 0.6f)
        return true
    }

    private fun updateTraps(dt: Float) {
        if (traps.isEmpty()) return
        val iter = traps.iterator()
        while (iter.hasNext()) {
            val t = iter.next()
            t.life -= dt
            if (t.arming > 0f) t.arming -= dt
            if (t.life <= 0f) {
                puff(t.x, t.y, 4, 0xFF8A8A8A.toInt())
                iter.remove()
                continue
            }
            if (t.arming > 0f) continue
            if (enemies.none { it.alive && !it.type.air && !it.underground && near(it.x, it.y, t.x, t.y, TRAP_TRIGGER + it.type.radius) }) continue
            iter.remove()
            explosion(t.x, t.y, TRAP_RADIUS)
            splash(t.x, t.y, TRAP_RADIUS, t.damage, null, TRAP_BOSS, hero = true)
            shake = max(shake, 0.35f)
            sound(Sfx.MINE, 0.9f)
        }
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

    private fun splash(x: Float, y: Float, radius: Float, dmg: Float, source: Tower?, bossMul: Float = 1f, hero: Boolean = false) {
        for (e in enemies) {
            if (!e.alive || e.type.air) continue
            val dx = e.x - x
            val dy = e.y - y
            val d = sqrt(dx * dx + dy * dy)
            if (d > radius + e.type.radius) continue
            val k = (1f - 0.5f * d / radius).coerceIn(0.5f, 1f)
            damage(e, dmg * k * (if (e.type.boss) bossMul else 1f), source, hero = hero)
        }
    }

    private fun impact(p: Projectile) {
        p.alive = false
        when (p.kind) {
            Shot.BULLET -> {
                p.target?.let { if (it.alive) damage(it, p.damage, p.source, pierce = p.pierce, hero = p.hero) }
                particles += Particle(p.x, p.y, 0f, 0f, 0.08f, 0.08f, 0.07f, 0xFFFFE38A.toInt(), 0f)
            }
            Shot.ROCKET -> {
                explosion(p.x, p.y, p.splash)
                splash(p.x, p.y, p.splash, p.damage, p.source, p.bossMul, p.hero)
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
                p.target?.let { if (it.alive) damage(it, p.damage * (if (it.type.boss) p.bossMul else 1f), p.source, hero = p.hero) }
                explosion(p.x, p.y, 0.5f, ground = false)
                sound(Sfx.BOOM, 0.3f)
            }
            Shot.BOMBLET -> {
                explosion(p.x, p.y, p.splash)
                splash(p.x, p.y, p.splash, p.damage, p.source, hero = p.hero)
                if (p.big) shake = max(shake, 0.18f)
            }
        }
    }

    private fun updatePatches(dt: Float) {
        for (p in patches) {
            p.life -= dt
            for (e in enemies) {
                if (!e.alive || e.type.air || e.type.fireproof) continue
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

    internal fun winForTest() {
        phase = Phase.WON
    }

    /** Puts an enemy [dist] cells along a road right away. */
    internal fun spawnForTest(type: EnemyType, dist: Float, path: Int = 0, hpMul: Float = 1f): Enemy = send(type, dist, path, hpMul)

    /** A fight for show, as in the story: it runs, but waves come only when sent and the base cannot fall. */
    internal fun beginShow() {
        phase = Phase.RUNNING
        holdWaves = true
        lives = SHOW_LIVES
    }

    /** Puts an enemy [dist] cells along a road right away. */
    internal fun send(type: EnemyType, dist: Float, path: Int = 0, hpMul: Float = 1f): Enemy {
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
        put("stats", kills, leaked, bossKills, ghostKills, built, airstrikes, cleared, maxMoney, branchMask, typeMask, abilitiesUsed, titanKills)
        put("killsof", *EnemyType.entries.filter { killsOf[it.ordinal] > 0 }.map { "${it.name}:${killsOf[it.ordinal]}" }.toTypedArray())
        put("cool", *cooldowns.toTypedArray())
        put("weather", weather, weatherWait, lavaWait)
        if (reserveUsed) put("reserve", 1)
        if (revived) put("revived", 1)
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
                e.burrow, e.under,
            )
        }
        for (a in active) put("group", a.wave, a.index, a.spawned, a.timer)
        for (p in patches) put("patch", p.x, p.y, p.life, p.dps, p.radius)
        for (b in bombs) put("bomb", b.x, b.y, b.timer, b.damage)
        for (b in lava) put("lava", b.x, b.y, b.timer, b.fall)
        commander?.let {
            put(
                "hero", it.type.name, it.x, it.y, it.tx, it.ty, it.level, it.xp, it.cooldown, it.kills, it.damageDealt,
                it.rally, it.skillCooldowns[0], it.skillCooldowns[1], it.jammed, it.overcharge, it.skillCooldowns[2], it.earned,
            )
        }
        for (s in sentries) put("sentry", s.x, s.y, s.life, s.damage, s.cooldown)
        for (t in traps) put("trap", t.x, t.y, t.damage, t.life, t.arming)
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
                    titanKills = f.getOrNull(11)?.toIntOrNull() ?: 0
                }
                "killsof" -> for (pair in f) {
                    val type = EnemyType.entries.firstOrNull { it.name == pair.substringBefore(':') } ?: continue
                    killsOf[type.ordinal] = pair.substringAfter(':').toIntOrNull() ?: 0
                }
                "cool" -> for (k in cooldowns.indices) cooldowns[k] = f.getOrNull(k)?.toFloatOrNull() ?: 0f
                "weather" -> {
                    weather = x(0)
                    weatherWait = x(1)
                    lavaWait = f.getOrNull(2)?.toFloatOrNull() ?: 0f
                }
                "cleared" -> for (s in f) {
                    val cell = s.toInt()
                    map.clear(cell % COLS, cell / COLS)
                    clearedCells += cell
                }
                "remain" -> remaining[i(0)] = i(1)
                "reserve" -> reserveUsed = true
                "revived" -> revived = true
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
                    // Saved before the volcano: no diggers yet.
                    e.burrow = f.getOrNull(17)?.toFloatOrNull() ?: e.burrow
                    e.under = f.getOrNull(18)?.toFloatOrNull() ?: 0f
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
                "lava" -> lava += Lava(x(0), x(1), x(2), x(3))
                "hero" -> {
                    val c = Commander(CommanderType.valueOf(f[0]), x(1), x(2))
                    c.tx = x(3)
                    c.ty = x(4)
                    c.level = i(5).coerceIn(0, Commander.MAX_LEVEL)
                    c.xp = x(6)
                    c.cooldown = x(7)
                    c.kills = i(8)
                    c.damageDealt = x(9)
                    c.rally = x(10)
                    c.skillCooldowns[0] = x(11)
                    c.skillCooldowns[1] = x(12)
                    c.jammed = x(13)
                    // Saved before ranks came along: no overcharge, third skill or service yet.
                    c.overcharge = f.getOrNull(14)?.toFloatOrNull() ?: 0f
                    c.skillCooldowns[2] = f.getOrNull(15)?.toFloatOrNull() ?: 0f
                    c.earned = f.getOrNull(16)?.toFloatOrNull() ?: 0f
                    commander = c
                }
                "sentry" -> sentries += Sentry(x(0), x(1), x(2), x(3)).also { it.cooldown = x(4) }
                "trap" -> traps += Trap(x(0), x(1), x(2), x(3)).also { it.arming = x(4) }
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
        const val TITAN_CREW = 3
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
        const val STOMP_EVERY = 6.5f
        const val STOMP_RADIUS = 1.8f
        const val STOMP_TIME = 3f
        const val BURROW_EVERY = 3f
        const val BURROW_TIME = 1.6f
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

        // An eruption's lava bombs: how often, how long they fall, what they do where they land.
        const val LAVA_EVERY = 0.9f
        const val LAVA_FALL = 1.1f
        const val LAVA_RADIUS = 0.6f
        const val LAVA_DAMAGE = 70f
        const val LAVA_BURN = 25f
        const val LAVA_BURN_TIME = 2f
        const val LAVA_JAM = 2.5f
        const val LAVA_ON_SQUADS = 0.3f
        const val LAVA_POOL = 2f
        const val LAVA_POOL_DPS = 30f
        const val BOSS_ALERT = 2.4f
        const val AIRSTRIKE_DAMAGE = 220f
        const val AIRSTRIKE_RADIUS = 1.0f
        const val PLANE_SPEED = 12f
        const val FREEZE_TIME = 4f
        const val REPAIR_LIVES = 3
        const val CLEAR_COST = 40
        const val CLEAR_STEP = 10
        const val MAX_QUARRIES = 4
        const val SHOW_LIVES = 1_000_000

        // The railgun: how many fit on a map, how wide its slug cuts, what it does to a boss.
        const val MAX_RAILS = 2
        const val RAIL_WIDTH = 0.12f
        const val RAIL_BOSS = 2f
        const val RAIL_TRACE = 0.3f

        // The commander: where he starts, his weapons and his skills at the first level.
        const val COMMANDER_START = 2.2f
        const val COMMANDER_SIDE = 0.8f
        const val COMMANDER_EDGE = 0.35f
        const val PROMOTED_GLOW = 1.2f
        const val SHARED_XP = 0.3f
        const val SHARED_REACH = 1f
        const val GROM_BLAST = 0.75f
        const val ZAP_JUMPS = 3
        const val ZAP_SLOW = 0.15f
        const val GRENADE_DAMAGE = 150f
        const val GRENADE_RADIUS = 1.1f
        const val GRENADE_REACH = 0.5f
        const val GRENADE_SPEED = 6f
        const val RALLY_TIME = 6f
        const val RALLY_RADIUS = 2.5f
        const val RALLY_BONUS = 0.3f
        const val SENTRY_TIME = 15f
        const val SENTRY_DAMAGE = 9f
        const val SENTRY_RATE = 6f
        const val SENTRY_RANGE = 3f
        const val EMP_STUN = 1.2f
        const val EMP_RADIUS = 2.2f
        const val EMP_DAMAGE = 50f
        const val PIERCE_DAMAGE = 450f
        const val PIERCE_BOSS = 2f
        const val PIERCE_REACH = 1.3f
        const val BARRAGE_SHOTS = 6
        const val BARRAGE_DAMAGE = 90f
        const val BARRAGE_BLAST = 0.8f
        const val BARRAGE_REACH = 1.2f
        const val SUPPRESS_RADIUS = 2.6f
        const val SUPPRESS_TIME = 5f
        const val SUPPRESS_SLOW = 0.35f
        const val SUPPRESS_SLOW_TIME = 2.5f
        const val SUPPRESS_TRACERS = 6
        const val OVERCHARGE_RADIUS = 2.5f
        const val OVERCHARGE_TIME = 6f
        const val OVERCHARGE_BONUS = 0.5f
        const val TRAP_DAMAGE = 420f
        const val TRAP_BOSS = 2f
        const val TRAP_RADIUS = 1.2f
        const val TRAP_TRIGGER = 0.45f
        const val TRAP_LIFE = 40f
        const val TRAP_REACH = 1.2f
        const val MAX_TRAPS = 2

        val GOLD = 0xFFFFD54A.toInt()
        val RED = 0xFFFF4B4B.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val HEAL = 0xFF6BE07A.toInt()
        val BOLT = 0xFF6FE3FF.toInt()
        val SAND = 0xFFE8C27A.toInt()
        val ICE = 0xFFBFE6FF.toInt()
        val LAVA = 0xFFFF7A2A.toInt()
        val ASH = 0xFF8A7E76.toInt()
        val DIRT = 0xFF9A7A52.toInt()
    }
}
