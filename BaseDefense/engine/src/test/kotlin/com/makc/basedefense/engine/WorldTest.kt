package com.makc.basedefense.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules of the fight, one mechanic at a time, on the first map. */
class WorldTest {
    private val coast = Levels.all[0]

    private fun world(rules: Rules = Rules(), level: LevelDef = coast): World {
        val w = World(level, rules)
        w.money = 5000
        w.beginForTest()
        return w
    }

    private fun run(w: World, seconds: Float) {
        var t = 0f
        while (t < seconds) {
            w.step(1f / 60f)
            t += 1f / 60f
        }
    }

    @Test
    fun shieldsSoakDamageAndRecharge() {
        val w = world()
        val e = w.spawnForTest(EnemyType.SHIELD, 1f)
        assertEquals(e.maxHp * 0.8f, e.shield, 0.01f)
        w.damage(e, 60f, null)
        assertEquals(e.maxHp, e.hp, 0.01f, "the shield takes the hit")
        assertTrue(e.shield < e.maxShield)
        run(w, 1.5f)
        val low = e.shield
        run(w, 4f)
        assertTrue(e.shield > low, "the shield comes back after a pause")
        w.damage(e, e.maxShield * 2f + e.maxHp * 2f, null)
        assertFalse(e.alive)
    }

    @Test
    fun ghostsNeedARadar() {
        val w = world()
        w.build(TowerType.TURRET, 2, 1)!!
        val g = w.spawnForTest(EnemyType.GHOST, 1.5f)
        g.slowAmount = 1f
        g.slowTimer = 100f
        run(w, 1f)
        assertTrue(w.projectiles.isEmpty() && g.hp == g.maxHp, "turrets cannot see a ghost")
        w.build(TowerType.RADAR, 2, 2)!!
        run(w, 1f)
        assertTrue(g.revealed)
        assertTrue(g.hp < g.maxHp || !g.alive, "with a radar the turret shoots")
    }

    @Test
    fun sappersJamAndJammersStopThem() {
        val w = world()
        val t = w.build(TowerType.TURRET, 2, 1)!!
        val s = w.spawnForTest(EnemyType.SAPPER, 2f)
        s.jamTimer = 0f
        s.slowAmount = 1f
        s.slowTimer = 100f
        run(w, 0.1f)
        assertTrue(t.jammed > 0f, "a sapper next to a tower jams it")

        val w2 = world(Rules(progress = 24))
        val t2 = w2.build(TowerType.TURRET, 2, 1)!!
        val radar = w2.build(TowerType.RADAR, 2, 2)!!
        w2.upgrade(radar)
        w2.upgrade(radar)
        assertTrue(w2.branch(radar, Branch.JAMMER))
        val s2 = w2.spawnForTest(EnemyType.SAPPER, 2f)
        s2.jamTimer = 0f
        run(w2, 0.2f)
        assertEquals(0f, t2.jammed, "a jammer protects the towers around it")
    }

    @Test
    fun motherReleasesASwarm() {
        val w = world()
        val m = w.spawnForTest(EnemyType.MOTHER, 3f)
        w.damage(m, 1e6f, null)
        run(w, 1f / 60f)
        assertEquals(World.MOTHER_BROOD, w.enemies.count { it.type == EnemyType.SWARM })
    }

    @Test
    fun minesGoOnTheRoadAndRearm() {
        val w = world()
        assertNull(w.build(TowerType.MINES, 2, 2), "mines do not go on grass")
        assertFalse(w.canPlace(TowerType.TURRET, 1, 1), "guns do not go on the road")
        val mine = assertNotNull(w.build(TowerType.MINES, 1, 1))
        val e = w.spawnForTest(EnemyType.BANDIT, 1f)
        run(w, 1.5f)
        assertTrue(e.hp < e.maxHp || !e.alive, "the mine went off")
        assertFalse(mine.armed)
        run(w, 3.2f)
        assertTrue(mine.armed, "the mine re-arms")
    }

    @Test
    fun abilitiesWork() {
        val w = world()
        assertFalse(w.abilityReady(Ability.AIRSTRIKE), "abilities start half charged")
        w.cooldowns.fill(0f)
        val crowd = (0 until 5).map { w.spawnForTest(EnemyType.BANDIT, 3f + it * 0.1f) }
        for (e in crowd) {
            e.slowAmount = 1f
            e.slowTimer = 100f
        }
        assertTrue(w.airstrike(crowd[2].x, crowd[2].y))
        assertFalse(w.abilityReady(Ability.AIRSTRIKE))
        run(w, 2f)
        assertTrue(crowd.all { !it.alive }, "three bombs flatten a crowd of bandits")

        val runner = w.spawnForTest(EnemyType.RUNNER, 1f)
        assertTrue(w.freeze())
        assertTrue(w.speedOf(runner) < EnemyType.RUNNER.speed * 0.35f)

        w.lives = w.maxLives
        assertFalse(w.abilityReady(Ability.REPAIR), "nothing to repair")
        w.lives = w.maxLives - 5
        assertTrue(w.repair())
        assertEquals(w.maxLives - 2, w.lives)

        val quiet = world(Rules(challenge = Challenge.NO_ABILITIES))
        quiet.cooldowns.fill(0f)
        assertFalse(quiet.freeze())
    }

    @Test
    fun branchesComeAfterLevelThree() {
        val w = world(Rules(progress = 24))
        val t = w.build(TowerType.ROCKET, 2, 2)!!
        assertFalse(w.canBranch(t, Branch.HEAVY))
        assertTrue(w.upgrade(t))
        assertTrue(w.upgrade(t))
        assertFalse(w.upgrade(t), "level 3 is the last plain level")
        assertTrue(t.atFork)
        assertFalse(w.canBranch(t, Branch.MINIGUN), "a branch belongs to one tower")
        assertTrue(w.branch(t, Branch.HEAVY))
        assertEquals(4, t.tier)
        assertEquals(Branch.HEAVY.stats.damage, t.stats.damage)

        val early = world(Rules(progress = 2))
        val t2 = early.build(TowerType.ROCKET, 2, 2)!!
        early.upgrade(t2)
        early.upgrade(t2)
        assertFalse(early.canBranch(t2, Branch.HEAVY), "branches unlock on level $BRANCH_UNLOCK")
    }

    @Test
    fun buildingRules() {
        val w = world()
        val t = w.build(TowerType.TURRET, 2, 2)!!
        val before = w.money
        assertTrue(w.undo(t))
        assertEquals(before + w.buildCost(TowerType.TURRET), w.money, "undo gives everything back")
        val t2 = w.build(TowerType.TURRET, 2, 2)!!
        w.upgrade(t2)
        assertFalse(w.undo(t2), "an upgraded tower cannot be undone")
        val invested = t2.invested
        val m = w.money
        assertTrue(w.sell(t2))
        assertEquals(m + (invested * 0.7f).toInt(), w.money)

        val noSell = world(Rules(challenge = Challenge.NO_SELL))
        val t3 = noSell.build(TowerType.TURRET, 2, 2)!!
        noSell.upgrade(t3)
        assertFalse(noSell.sell(t3))

        val few = world(Rules(challenge = Challenge.maxTowers(2)))
        assertNotNull(few.build(TowerType.TURRET, 2, 2))
        assertNotNull(few.build(TowerType.TURRET, 3, 2))
        assertNull(few.build(TowerType.TURRET, 5, 2))

        val only = world(Rules(challenge = Challenge.only(TowerType.ROCKET)))
        assertNull(only.build(TowerType.TURRET, 2, 2))
        assertNotNull(only.build(TowerType.ROCKET, 2, 2))

        val q = world(Rules(progress = 24))
        var built = 0
        for (r in 0 until ROWS) for (c in 0 until COLS) if (q.build(TowerType.QUARRY, c, r) != null) built++
        assertEquals(World.MAX_QUARRIES, built)
    }

    @Test
    fun perksAndDifficultyChangeTheNumbers() {
        val perks = Perks.decode("3.3.3.2.2.3.2.2")
        val rules = Rules(difficulty = Difficulty.HARD, perks = perks)
        val w = World(coast, rules)
        assertEquals(Difficulty.HARD.lives + 9, w.maxLives)
        assertEquals(Math.round(coast.startMoney * Difficulty.HARD.money / 5f) * 5 + 90, w.money)
        assertEquals(Math.round(50 * 0.9f), w.buildCost(TowerType.TURRET))
        assertEquals("3.3.3.2.2.3.2.2", perks.encode())
        assertEquals(0, Perks.decode("9.-1.x")[Perk.LIVES], "bad values are clamped")
    }

    @Test
    fun obstaclesCanBeCleared() {
        val w = world()
        assertEquals('R', coast.ascii[2][4])
        assertFalse(w.map.buildable(4, 2))
        val v = w.map.version
        val m = w.money
        assertTrue(w.clear(4, 2))
        assertEquals(m - w.clearCost(), w.money)
        assertTrue(w.map.buildable(4, 2))
        assertTrue(w.map.version > v)
        assertFalse(w.clear(4, 2))
        assertFalse(w.clear(0, 14), "water stays")
    }

    @Test
    fun iceMakesThemSlide() {
        val pass = Levels.all.first { it.id == 17 }
        val w = world(level = pass)
        val e = w.spawnForTest(EnemyType.BANDIT, 0f)
        // Find the stretch of ice on the road and stand there.
        var d = 0f
        while (d < e.path.length) {
            e.dist = d
            val p = FloatArray(4)
            e.path.pointAt(d, p)
            e.x = p[0]
            e.y = p[1]
            if (w.map.isIce(p[0].toInt(), p[1].toInt())) break
            d += 0.25f
        }
        assertTrue(d < e.path.length, "level 17 has ice on its road")
        assertEquals(EnemyType.BANDIT.speed * World.ICE_SPEED, w.speedOf(e), 0.001f)
    }

    @Test
    fun weatherComesToTheDesert() {
        val w = world(level = Levels.all.first { it.id == 9 })
        var stormy = false
        var t = 0f
        while (t < 90f) {
            w.step(0.1f)
            if (w.sandstorm) stormy = true
            t += 0.1f
        }
        assertTrue(stormy, "a sandstorm blows within a minute and a half")
        val green = world()
        run(green, 60f)
        assertEquals(0f, green.weather)
    }

    @Test
    fun saveAndRestoreKeepTheFight() {
        val level = Levels.all.first { it.id == 12 }
        val rules = Sim.fresh(level)
        val w = World(level, rules)
        val bot = Bot(w)
        while (w.wavesStarted < 4 && !w.over) {
            bot.update(1f / 60f)
            w.step(1f / 60f)
        }
        // Somewhere in the middle of a wave.
        repeat(400) {
            bot.update(1f / 60f)
            w.step(1f / 60f)
        }
        assertTrue(w.enemies.isNotEmpty() && w.towers.isNotEmpty())
        w.clear(0, 6)
        val text = w.save()
        val copy = World(level, rules)
        copy.restore(text)
        assertEquals(text, copy.save(), "restoring loses nothing that is saved")
        assertEquals(w.money, copy.money)
        assertEquals(w.towers.size, copy.towers.size)
        assertEquals(w.enemies.count { it.alive }, copy.enemies.size)
        assertEquals(w.map.buildable(0, 6), copy.map.buildable(0, 6))
        // And the restored game plays on to the end.
        val bot2 = Bot(copy)
        while (!copy.over && copy.time < 6000f) {
            bot2.update(1f / 60f)
            copy.step(1f / 60f)
        }
        assertEquals(Phase.WON, copy.phase)
    }
}
