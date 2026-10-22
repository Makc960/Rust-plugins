package com.makc.basedefense.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The fourth world: eruptions and the enemies that come with them, on its first map. */
class VolcanoTest {
    private val fields = Levels.all.first { it.id == 25 }

    private fun world(): World {
        val w = World(fields, Rules())
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

    /** Keeps [e] where it stands. */
    private fun hold(e: Enemy) {
        e.slowAmount = 1f
        e.slowTimer = 100f
    }

    @Test
    fun theVolcanoIsTheFourthWorld() {
        val volcano = Levels.all.filter { it.biome == Biome.VOLCANO }
        assertEquals((25..32).toList(), volcano.map { it.id })
        assertTrue(volcano.all { it.world == 3 })
        assertTrue(volcano.any { l -> l.plan.bosses.values.any { EnemyType.TITAN in it } }, "the titan leads somewhere")
        assertTrue(EnemyType.TITAN.boss)
    }

    @Test
    fun salamandersShrugOffFireButNotFrost() {
        val w = world()
        w.build(TowerType.FLAMER, 2, 1)!!
        val s = w.spawnForTest(EnemyType.SALAMANDER, 1.5f)
        val b = w.spawnForTest(EnemyType.BANDIT, 1.5f)
        hold(s)
        hold(b)
        run(w, 1f)
        assertEquals(s.maxHp, s.hp, "flames do nothing to a fire suit")
        assertEquals(0f, s.burnTimer)
        assertTrue(b.hp < b.maxHp || !b.alive, "but they burn a bandit")

        val w2 = world()
        val cryo = w2.build(TowerType.FLAMER, 2, 1)!!
        w2.upgrade(cryo)
        w2.upgrade(cryo)
        assertTrue(w2.branch(cryo, Branch.CRYO))
        val s2 = w2.spawnForTest(EnemyType.SALAMANDER, 1.5f)
        hold(s2)
        run(w2, 1f)
        assertTrue(s2.hp < s2.maxHp, "frost gets through")
    }

    @Test
    fun diggersDiveOutOfReach() {
        val w = world()
        w.build(TowerType.TURRET, 2, 1)!!
        val d = w.spawnForTest(EnemyType.DIGGER, 1.5f)
        hold(d)
        d.burrow = 0f
        run(w, 0.05f)
        assertTrue(d.underground)
        assertFalse(d.visible, "no squad can see it")
        assertEquals(0f, w.damage(d, 500f, null), "nothing reaches it underground")
        run(w, World.BURROW_TIME - 0.2f)
        assertEquals(d.maxHp, d.hp, "the riflemen had nothing to shoot at")
        run(w, 0.4f)
        assertFalse(d.underground, "it comes back up")
        assertTrue(d.visible)
        run(w, 1f)
        assertTrue(d.hp < d.maxHp, "and gets shot")
    }

    @Test
    fun juggernautsCannotBeSlowedStunnedOrPushed() {
        val w = world()
        val tesla = w.build(TowerType.TESLA, 2, 1)!!
        w.upgrade(tesla)
        w.upgrade(tesla)
        assertTrue(w.branch(tesla, Branch.STUN))
        val shotgun = w.build(TowerType.SHOTGUN, 4, 1)!!
        w.upgrade(shotgun)
        w.upgrade(shotgun)
        assertTrue(w.branch(shotgun, Branch.ASSAULT))
        val j = w.spawnForTest(EnemyType.JUGGERNAUT, 1.2f)
        w.cooldowns.fill(0f)
        assertTrue(w.freeze())
        val before = j.dist
        run(w, 1f)
        assertEquals(0f, j.slowTimer, "neither the freeze nor the lightning slows it")
        assertEquals(0f, j.stunTimer, "the overload does not stun it")
        assertEquals(0f, j.knock, "the assault pellets do not push it")
        assertTrue(j.hp < j.maxHp, "though it does get hurt")
        assertEquals(before + EnemyType.JUGGERNAUT.speed, j.dist, 0.05f, "it walks at full speed")
    }

    @Test
    fun theTitanStompsAndCarriesSalamanders() {
        val w = world()
        val near = w.build(TowerType.TURRET, 2, 1)!!
        val far = w.build(TowerType.TURRET, 8, 12)!!
        val titan = w.spawnForTest(EnemyType.TITAN, 1.5f)
        hold(titan)
        titan.jamTimer = 0f
        run(w, 0.05f)
        assertTrue(near.jammed > 0f, "the stomp knocks out the squad next to it")
        assertEquals(0f, far.jammed, "but not one across the map")
        w.damage(titan, 1e7f, null)
        run(w, 1f / 60f)
        assertEquals(World.TITAN_CREW, w.enemies.count { it.type == EnemyType.SALAMANDER })
        assertEquals(1, w.titanKills)
        assertEquals(1, w.bossKills)
    }

    @Test
    fun eruptionsRainLavaOnEnemiesAndSometimesOnSquads() {
        val w = world()
        var t = 0f
        while (!w.eruption && t < 120f) {
            w.step(0.1f)
            t += 0.1f
        }
        assertTrue(w.eruption, "the volcano erupts within two minutes")
        val b = w.spawnForTest(EnemyType.BANDIT, 4f)
        val s = w.spawnForTest(EnemyType.SALAMANDER, 4f)
        b.hp = 1e6f
        hold(b)
        hold(s)
        run(w, 4f)
        assertTrue(b.hp < 1e6f, "lava burns a bandit")
        assertEquals(s.maxHp, s.hp, "not a salamander")

        // Where a bomb lands on a squad, the squad is out for a moment.
        val squad = w.build(TowerType.TURRET, 2, 1)!!
        w.lava += Lava(squad.x, squad.y, 0.1f)
        run(w, 0.2f)
        assertTrue(squad.jammed > 0f)
        val green = World(Levels.all[0], Rules())
        green.beginForTest()
        run(green, 120f)
        assertFalse(green.eruption)
    }

    @Test
    fun diggersAndLavaAreSaved() {
        val w = world()
        val d = w.spawnForTest(EnemyType.DIGGER, 2f)
        d.under = 1f
        w.lava += Lava(4f, 5f, 0.7f)
        val text = w.save()
        val copy = World(fields, Rules())
        copy.restore(text)
        assertEquals(text, copy.save(), "restoring loses nothing that is saved")
        assertTrue(copy.enemies.single().underground)
        assertEquals(1, copy.lava.size)
    }
}
