package com.makc.basedefense.engine

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The commander on the map: where he goes, how he fights and grows, his skills and his save. */
class CommandersTest {
    private val step = 1f / 60f

    private fun world(type: CommanderType, vip: Boolean = false, challenge: Challenge? = null, level: LevelDef = Levels.all[0]): World {
        val w = World(level, Rules(challenge = challenge, progress = 10, vip = vip, commander = type))
        w.beginForTest()
        return w
    }

    private fun run(w: World, seconds: Float) {
        var t = 0f
        while (t < seconds) {
            w.step(step)
            t += step
        }
    }

    /** How far along the first road its point nearest to (x, y) is. */
    private fun distNear(w: World, x: Float, y: Float): Float {
        val path = w.map.paths[0]
        val p = FloatArray(4)
        var best = 0f
        var bestD = Float.MAX_VALUE
        var d = 0f
        while (d <= path.length) {
            path.pointAt(d, p)
            val dd = hypot(p[0] - x, p[1] - y)
            if (dd < bestD) {
                bestD = dd
                best = d
            }
            d += 0.05f
        }
        return best
    }

    /** An enemy on the road at the point nearest the commander, standing still. */
    private fun nextTo(w: World, type: EnemyType, hpMul: Float = 1f, ahead: Float = 0f): Enemy {
        val c = w.commander!!
        val e = w.spawnForTest(type, distNear(w, c.x, c.y) + ahead, hpMul = hpMul)
        e.stunTimer = 1000f
        return e
    }

    @Test
    fun heStartsBesideTheRoadAndWalksWhereSent() {
        for (level in Levels.all) {
            val w = World(level, Rules(commander = CommanderType.SERGEANT))
            val c = assertNotNull(w.commander)
            val col = floor(c.x).toInt()
            val row = floor(c.y).toInt()
            assertTrue(w.map.inside(col, row), "level ${level.id}: on the map")
            assertTrue(hypot(c.x - w.map.baseCol - 0.5f, c.y - w.map.baseRow - 0.5f) < 4f, "level ${level.id}: near the base")
        }
        val w = World(Levels.all[0], Rules(commander = CommanderType.SERGEANT))
        val c = w.commander!!
        assertTrue(w.moveCommander(1.5f, 1.5f))
        run(w, 10f)
        assertEquals(Phase.READY, w.phase, "he takes up his post before the first wave")
        assertEquals(1.5f, c.x, 0.01f)
        assertEquals(1.5f, c.y, 0.01f)
        assertTrue(c.walked > 0f && !c.moving)
        w.moveCommander(-5f, 99f)
        assertEquals(World.COMMANDER_EDGE, c.tx)
        assertEquals(ROWS - World.COMMANDER_EDGE, c.ty, "he is kept on the map")
        assertNull(World(Levels.all[0], Rules()).commander, "no commander unless the rules bring one")
    }

    @Test
    fun heShootsGainsExperienceAndLevelsUp() {
        val w = world(CommanderType.SERGEANT)
        val c = w.commander!!
        val e = nextTo(w, EnemyType.BANDIT)
        run(w, 4f)
        assertFalse(e.alive, "he shot the bandit")
        assertEquals(1, c.kills)
        assertTrue(c.xp > 0f && c.xp == c.damageDealt, "experience is the damage he dealt")
        assertEquals(0, c.level)
        val before = w.commanderDamage(c)
        val brute = nextTo(w, EnemyType.BRUTE, hpMul = 30f)
        run(w, 40f)
        assertTrue(c.level >= 1, "a level after ${Commander.XP[1]} damage, has ${c.xp}")
        assertTrue(w.commanderDamage(c) > before, "a level makes his rifle hit harder")
        assertTrue(brute.hp < brute.maxHp)
    }

    @Test
    fun heFiresOnlyStandingAndGromCannotReachFliers() {
        val w = world(CommanderType.GROM)
        val c = w.commander!!
        nextTo(w, EnemyType.DRONE)
        run(w, 2f)
        assertTrue(w.projectiles.isEmpty(), "a grenade launcher does not reach a drone")
        assertTrue(w.useSkill(0), "but his armor piercer does")
        val walker = nextTo(w, EnemyType.BANDIT, hpMul = 50f)
        w.moveCommander(c.x + 2f, c.y)
        run(w, 0.3f)
        assertTrue(c.moving && w.projectiles.none { it.hero && it.kind == Shot.ROCKET }, "walking, he holds his fire")
        run(w, 3f)
        assertTrue(walker.hp < walker.maxHp || w.projectiles.any { it.hero }, "standing, he fires again")
    }

    @Test
    fun vipCommandersStartHigherAndLearnFaster() {
        val plain = world(CommanderType.SERGEANT)
        val vip = world(CommanderType.SERGEANT, vip = true)
        assertEquals(0, plain.commander!!.level)
        assertEquals(Vip.COMMANDER_LEVEL, vip.commander!!.level)
        val a = nextTo(plain, EnemyType.BRUTE, hpMul = 30f)
        val b = nextTo(vip, EnemyType.BRUTE, hpMul = 30f)
        val xpBefore = vip.commander!!.xp
        run(plain, 3f)
        run(vip, 3f)
        val dealtPlain = a.maxHp - a.hp
        val dealtVip = b.maxHp - b.hp
        assertTrue(dealtPlain > 0f && dealtVip > 0f)
        assertEquals(Vip.XP, (vip.commander!!.xp - xpBefore) / vip.commander!!.damageDealt, 0.01f)
        assertEquals(1f, plain.commander!!.xp / plain.commander!!.damageDealt, 0.01f)
    }

    @Test
    fun sergeantGrenadeAndRally() {
        val w = world(CommanderType.SERGEANT)
        val c = w.commander!!
        assertFalse(w.useSkill(0), "nothing to throw a grenade at")
        assertEquals(0f, c.skillCooldowns[0])
        val crowd = List(5) { nextTo(w, EnemyType.BANDIT, ahead = it * 0.15f) }
        assertTrue(w.useSkill(0))
        assertEquals(w.skillCooldown(Skill.GRENADE), c.skillCooldowns[0])
        assertFalse(w.useSkill(0), "then it recharges")
        run(w, 1f)
        assertTrue(crowd.count { !it.alive } >= 3, "the grenade blows up the crowd")

        w.money = 1000
        val spot = (0 until ROWS).flatMap { r -> (0 until COLS).map { cc -> cc to r } }
            .filter { (cc, r) -> w.map.buildable(cc, r) }
            .minBy { (cc, r) -> hypot(cc + 0.5f - c.x, r + 0.5f - c.y) }
        val t = assertNotNull(w.build(TowerType.TURRET, spot.first, spot.second))
        run(w, 0.1f)
        assertEquals(0f, t.aura)
        assertTrue(w.useSkill(1))
        run(w, 0.1f)
        assertEquals(World.RALLY_BONUS, t.aura, 0.001f)
        run(w, w.skillPower(c, Skill.RALLY) + 0.5f)
        assertEquals(0f, t.aura, "the rally wears off")
    }

    @Test
    fun engineerSentryAndEmp() {
        val w = world(CommanderType.ENGINEER)
        val c = w.commander!!
        assertTrue(w.useSkill(0), "a sentry gun goes up anywhere")
        assertEquals(1, w.sentries.size)
        val target = nextTo(w, EnemyType.BRUTE, hpMul = 20f)
        run(w, 3f)
        assertTrue(target.hp < target.maxHp)
        run(w, World.SENTRY_TIME)
        assertTrue(w.sentries.isEmpty(), "its time runs out")

        val runner = nextTo(w, EnemyType.RUNNER, hpMul = 20f)
        runner.stunTimer = 0f
        val boss = nextTo(w, EnemyType.APC)
        boss.stunTimer = 0f
        assertTrue(w.useSkill(1))
        assertTrue(runner.stunTimer > 1f, "the EMP stuns")
        assertTrue(boss.stunTimer <= 0f && boss.slowTimer > 0f, "bosses are only slowed")
    }

    @Test
    fun gromPiercerAndBarrage() {
        val w = world(CommanderType.GROM)
        val c = w.commander!!
        val weak = nextTo(w, EnemyType.BANDIT)
        val boss = nextTo(w, EnemyType.APC, ahead = 0.4f)
        // Worked out first: the shot itself may bring him a level.
        val shot = w.skillPower(c, Skill.PIERCE) * World.PIERCE_BOSS
        assertTrue(w.useSkill(0))
        assertEquals(boss.maxHp - shot, boss.hp, 1f)
        assertTrue(weak.hp == weak.maxHp, "the piercer goes for the toughest")
        val shots = w.projectiles.size
        assertTrue(w.useSkill(1))
        assertEquals(shots + World.BARRAGE_SHOTS, w.projectiles.size)
        assertTrue(w.projectiles.all { it.hero })
    }

    @Test
    fun sokolovaSnipesTheToughestFromAfar() {
        val w = world(CommanderType.SOKOLOVA)
        val c = w.commander!!
        assertTrue(w.commanderRange(c) > CommanderType.SERGEANT.levels[0].range + 0.8f, "she reaches far")
        val bandit = nextTo(w, EnemyType.BANDIT, hpMul = 20f)
        val brute = nextTo(w, EnemyType.BRUTE, hpMul = 20f, ahead = -0.5f)
        run(w, 0.1f)
        assertEquals(bandit.maxHp, bandit.hp, "she leaves the small fry")
        val hit = w.commanderDamage(c) - EnemyType.BRUTE.armor * World.SNIPER_PIERCE
        assertEquals(brute.maxHp - hit, brute.hp, 1f, "and hits the toughest, through half its armor")
        assertTrue(w.tracers.any { it.kind == 2 })
    }

    @Test
    fun sokolovaHealsSmokesAndHeadshots() {
        val w = World(Levels.all[0], Rules(progress = 10, commander = CommanderType.SOKOLOVA, rank = Ranks.THIRD_SKILL))
        w.beginForTest()
        val c = w.commander!!
        // First aid: lives back for the base and a dazed squad near her back in the fight.
        assertFalse(w.useSkill(0), "nothing to heal, nothing spent")
        w.lives -= 5
        w.money = 5000
        val spot = (0 until ROWS).flatMap { r -> (0 until COLS).map { c2 -> c2 to r } }
            .filter { (c2, r) -> w.canPlace(TowerType.TURRET, c2, r) }
            .minBy { (c2, r) -> hypot(c2 + 0.5f - c.x, r + 0.5f - c.y) }
        val squad = w.build(TowerType.TURRET, spot.first, spot.second)!!
        squad.jammed = 3f
        val lives = w.lives
        assertTrue(w.useSkill(0))
        assertEquals(lives + w.skillPower(c, Skill.AID).toInt(), w.lives)
        assertTrue(hypot(squad.x - c.x, squad.y - c.y) <= World.AID_RADIUS)
        assertEquals(0f, squad.jammed, "the dazed squad is back in the fight")

        // Smoke: whoever is in it is slowed hard, until it thins away.
        val a = nextTo(w, EnemyType.BANDIT, hpMul = 50f)
        val b = nextTo(w, EnemyType.RUNNER, hpMul = 50f, ahead = 0.3f)
        assertTrue(w.useSkill(1))
        assertEquals(1, w.smokes.size)
        run(w, 0.1f)
        assertTrue(a.slowTimer > 0f && a.slowAmount >= World.SMOKE_SLOW - 0.01f)
        assertTrue(b.slowTimer > 0f)
        val saved = World(Levels.all[0], Rules(progress = 10, commander = CommanderType.SOKOLOVA, rank = Ranks.THIRD_SKILL))
        saved.restore(w.save())
        assertEquals(1, saved.smokes.size, "the smoke is kept in a save")
        run(w, World.SMOKE_TIME * 2.5f)
        assertTrue(w.smokes.isEmpty())

        // Headshot: the toughest walker falls at once; a boss takes a heavy hit.
        val brute = nextTo(w, EnemyType.BRUTE, hpMul = 30f)
        assertTrue(w.useSkill(2))
        assertFalse(brute.alive, "one shot")
        val boss = nextTo(w, EnemyType.APC, hpMul = 10f)
        c.skillCooldowns[2] = 0f
        val power = w.skillPower(c, Skill.HEADSHOT)
        assertTrue(w.useSkill(2))
        assertTrue(boss.alive)
        assertEquals(boss.maxHp - power, boss.hp, power * 0.35f)
    }

    @Test
    fun skillsNeedTheBattleAndCountAsAbilities() {
        val ready = World(Levels.all[0], Rules(commander = CommanderType.SERGEANT))
        assertFalse(ready.skillReady(1), "not before the first wave")
        val silent = world(CommanderType.SERGEANT, challenge = Challenge.NO_ABILITIES)
        assertFalse(silent.skillsAllowed)
        assertFalse(silent.useSkill(1), "a challenge without abilities switches skills off")
    }

    @Test
    fun aSapperJamsHim() {
        val w = world(CommanderType.SERGEANT)
        val c = w.commander!!
        val sapper = nextTo(w, EnemyType.SAPPER, hpMul = 100f)
        sapper.jamTimer = 0f
        run(w, 0.2f)
        assertTrue(c.jammed > 0f)
        assertFalse(w.skillReady(1), "jammed, he cannot use his skills")
    }

    @Test
    fun heIsSavedWithTheBattle() {
        val w = world(CommanderType.ENGINEER, vip = true)
        val c = w.commander!!
        w.moveCommander(2f, 3f)
        nextTo(w, EnemyType.BRUTE, hpMul = 30f)
        w.useSkill(0)
        run(w, 3f)
        val back = World(Levels.all[0], Rules(progress = 10, vip = true, commander = CommanderType.ENGINEER))
        back.restore(w.save())
        val c2 = assertNotNull(back.commander)
        assertEquals(c.type, c2.type)
        assertEquals(c.x, c2.x, 0.001f)
        assertEquals(c.ty, c2.ty, 0.001f)
        assertEquals(c.level, c2.level)
        assertEquals(c.xp, c2.xp, 0.01f)
        assertEquals(c.kills, c2.kills)
        assertEquals(c.skillCooldowns[0], c2.skillCooldowns[0], 0.001f)
        assertEquals(w.sentries.size, back.sentries.size)
    }

    @Test
    fun theVipReserveSavesTheBaseOnce() {
        val w = World(Levels.all[0], Rules(vip = true))
        w.beginForTest()
        val path = w.map.paths[0]
        w.lives = 1
        w.spawnForTest(EnemyType.BANDIT, path.length - 0.05f)
        run(w, 0.5f)
        assertEquals(Phase.RUNNING, w.phase, "the reserve arrives")
        assertEquals(Vip.RESERVE_LIVES, w.lives)
        assertTrue(w.reserveUsed)
        val back = World(Levels.all[0], Rules(vip = true))
        back.restore(w.save())
        assertTrue(back.reserveUsed, "the reserve is spent for the rest of the battle")
        w.lives = 1
        w.spawnForTest(EnemyType.BANDIT, path.length - 0.05f)
        run(w, 0.5f)
        assertEquals(Phase.LOST, w.phase, "only once")
        val plain = World(Levels.all[0], Rules())
        plain.beginForTest()
        plain.lives = 1
        plain.spawnForTest(EnemyType.BANDIT, path.length - 0.05f)
        run(plain, 0.5f)
        assertEquals(Phase.LOST, plain.phase, "no reserve without VIP")
    }

    @Test
    fun aloneHeCannotHoldALevel() {
        for (type in CommanderType.entries) {
            val held = ArrayList<Int>()
            for (level in Levels.all) {
                val w = Sim.play(level, Rules(progress = level.id, commander = type), null)
                if (w.phase != Phase.LOST) held += level.id
            }
            assertTrue(held.isEmpty(), "$type alone held levels $held")
        }
    }

    @Test
    fun aCommanderHelpsALazyPlayer() {
        var without = 0
        var with = 0
        for (level in Levels.all) {
            val bot = { w: World -> Bot(w, upgrades = false, types = listOf(TowerType.TURRET), abilities = false) }
            if (Sim.play(level, Sim.fresh(level), bot).phase == Phase.WON) without++
            val rules = Rules(progress = level.id, commander = CommanderType.SERGEANT)
            if (Sim.play(level, rules, bot).phase == Phase.WON) with++
        }
        println("lazy wins without a commander $without, with the sergeant $with")
        assertTrue(with >= without, "the sergeant should not make things worse: $with < $without")
    }
}
