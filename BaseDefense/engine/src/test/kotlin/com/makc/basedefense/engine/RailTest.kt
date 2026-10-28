package com.makc.basedefense.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The railgun: VIP's own squad, the strongest gun in the game, on the first map. */
class RailTest {
    private val coast = Levels.all[0]

    private fun world(vip: Boolean = true, challenge: Challenge? = null): World {
        val w = World(coast, Rules(vip = vip, challenge = challenge))
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
    fun onlyVipPlayersGetIt() {
        assertTrue(TowerType.RAIL.vip && TowerType.entries.count { it.vip } == 1)
        assertFalse(TowerType.RAIL in Rules().towers)
        assertTrue(TowerType.RAIL in Rules(vip = true).towers)
        assertTrue(Rules().offers(TowerType.RAIL, vip = true), "the battle can show it locked")
        assertNull(world(vip = false).build(TowerType.RAIL, 0, 3), "no railgun without VIP")
        assertNotNull(world().build(TowerType.RAIL, 0, 3))
        // A challenge for other squads keeps it out even with VIP.
        val only = world(challenge = Challenge.only(TowerType.TURRET, TowerType.SNIPER))
        assertFalse(TowerType.RAIL in only.rules.towers)
        assertNull(only.build(TowerType.RAIL, 0, 3))
    }

    @Test
    fun theSlugGoesThroughEveryoneOnItsLineToTheEdge() {
        val w = world()
        val rail = w.build(TowerType.RAIL, 0, 3)!!
        // The road runs along row 3 from the left: everyone on it is in line with the railgun.
        val brute = w.spawnForTest(EnemyType.BRUTE, 5f, hpMul = 3f)
        val drone = w.spawnForTest(EnemyType.DRONE, 6f, hpMul = 20f)
        val apc = w.spawnForTest(EnemyType.APC, 7f)
        val ghost = w.spawnForTest(EnemyType.GHOST, 8.5f, hpMul = 20f)
        val far = w.spawnForTest(EnemyType.BANDIT, 10f, hpMul = 20f)
        val aside = w.spawnForTest(EnemyType.BANDIT, 12f, hpMul = 20f)
        val all = listOf(brute, drone, apc, ghost, far, aside)
        all.forEach(::hold)
        for (e in all.dropLast(1)) assertEquals(3.5f, e.y, 0.1f, "${e.type} stands on row 3")
        assertTrue(far.x - rail.x > w.rangeOf(rail), "the last one is out of the squad's reach")

        run(w, 1f / 60f)
        val dmg = w.damageOf(rail)
        assertEquals(dmg, brute.maxHp - brute.hp, 0.5f, "armor does not stop it")
        assertEquals(dmg * World.RAIL_BOSS, apc.maxHp - apc.hp, 0.5f, "double against a boss, through its armor")
        assertTrue(drone.hp < drone.maxHp, "it hits fliers")
        assertTrue(ghost.hp < ghost.maxHp, "and a ghost that happens to be in the way")
        assertTrue(far.hp < far.maxHp, "and goes on past its reach")
        assertEquals(aside.maxHp, aside.hp, "but only along its line")
        val beam = w.tracers.single { it.kind == 4 }
        assertEquals(COLS.toFloat(), beam.x2, 0.01f, "the beam runs to the edge of the map")
        assertEquals(3.5f, beam.y2, 0.01f)
    }

    @Test
    fun threeStrongLevelsAndNoFork() {
        val w = world()
        val t = w.build(TowerType.RAIL, 0, 3)!!
        assertTrue(w.upgrade(t) && w.upgrade(t))
        assertFalse(t.canUpgrade || t.atFork)
        assertTrue(Branch.entries.none { w.canBranch(t, it) })
        assertEquals(3, t.tier)

        // Its shot is the heaviest of any squad's, specialisations included.
        val top = TowerType.RAIL.levels.last().damage
        val perSecond = setOf(Branch.NAPALM, Branch.CRYO, Branch.SPIKES)
        for (type in TowerType.entries) {
            if (type == TowerType.RAIL || type == TowerType.FLAMER || type == TowerType.QUARRY) continue
            for (s in type.levels) assertTrue(top > s.damage, "$type")
        }
        for (b in Branch.entries) if (b !in perSecond && b.tower != TowerType.QUARRY) assertTrue(top > b.stats.damage, "$b")
    }

    @Test
    fun atMostTwoOnAMap() {
        val w = world()
        assertNotNull(w.build(TowerType.RAIL, 0, 3))
        assertNotNull(w.build(TowerType.RAIL, 0, 4))
        assertTrue(w.limitReached(TowerType.RAIL))
        assertTrue(w.canPlace(TowerType.RAIL, 2, 4), "the cell is free")
        assertNull(w.build(TowerType.RAIL, 2, 4), "but a third railgun is not allowed")
        assertEquals(World.MAX_RAILS, w.towers.count { it.type == TowerType.RAIL })
        assertFalse(w.limitReached(TowerType.TURRET))
        assertNotNull(w.build(TowerType.TURRET, 2, 4))
    }

    @Test
    fun railgunsAreSaved() {
        val w = world()
        val t = w.build(TowerType.RAIL, 0, 3)!!
        w.upgrade(t)
        val text = w.save()
        val copy = World(coast, Rules(vip = true))
        copy.restore(text)
        assertEquals(text, copy.save(), "restoring loses nothing that is saved")
        val back = copy.towers.single()
        assertEquals(TowerType.RAIL, back.type)
        assertEquals(1, back.level)
    }

    @Test
    fun theArsenalNeedsNoVip() {
        val p = Profile(SilentPlatform())
        p.orStat(Stat.TOWER_TYPES, TowerType.entries.filter { !it.vip }.fold(0) { m, t -> m or (1 shl t.ordinal) })
        assertEquals(10, Achievement.ARSENAL.progress(p))
        p.orStat(Stat.TOWER_TYPES, 1 shl TowerType.RAIL.ordinal)
        assertEquals(10, Achievement.ARSENAL.progress(p), "the railgun is not one of the ten")
    }
}
