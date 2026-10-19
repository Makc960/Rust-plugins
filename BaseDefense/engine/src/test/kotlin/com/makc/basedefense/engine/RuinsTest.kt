package com.makc.basedefense.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The fifth world: two roads at once, ambushes out of the cellars, and the city's own enemies. */
class RuinsTest {
    private val outskirts = Levels.all.first { it.id == 33 }

    private fun world(): World {
        val w = World(outskirts, Rules())
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
    fun theRuinsAreTheFifthWorld() {
        val ruins = Levels.all.filter { it.biome == Biome.RUINS }
        assertEquals((33..40).toList(), ruins.map { it.id })
        assertTrue(ruins.all { it.world == 4 && it.paths.size == 2 })
        assertTrue(ruins.any { l -> l.plan.bosses.values.any { EnemyType.AIRSHIP in it } }, "the airship leads somewhere")
        assertTrue(EnemyType.AIRSHIP.boss && EnemyType.AIRSHIP.air)
        assertEquals(Chapter.RUINS, Chapter.after(4), "and the story ends after it")
    }

    @Test
    fun bikersShakeOffHalfOfASlow() {
        val w = world()
        val biker = w.spawnForTest(EnemyType.BIKER, 1.5f)
        val runner = w.spawnForTest(EnemyType.RUNNER, 1.5f, path = 1)
        w.cooldowns.fill(0f)
        assertTrue(w.freeze())
        assertEquals(runner.slowAmount * World.NIMBLE, biker.slowAmount, 0.001f)
        assertTrue(biker.slowAmount > 0f, "but it is slowed a little")
    }

    @Test
    fun flameTanksBurnOutMinesAndShrugOffFlames() {
        val w = world()
        val path = w.map.paths[0]
        val p = FloatArray(4)
        path.pointAt(4f, p)
        val mine = assertNotNull(w.build(TowerType.MINES, p[0].toInt(), p[1].toInt()), "a mine on the road")
        val tank = w.spawnForTest(EnemyType.FLAME_TANK, 1.5f)
        tank.hp = 1e6f
        run(w, 6f)
        assertTrue(mine.jammed > 0f, "the tank burns the mine out before it goes off")
        assertTrue(tank.dist > 4.5f, "and drives on over it")
        assertTrue(tank.hp > 1e6f - 1f, "untouched")

        val w2 = world()
        w2.build(TowerType.FLAMER, 1, 1)!!
        val t2 = w2.spawnForTest(EnemyType.FLAME_TANK, 1.5f)
        hold(t2)
        run(w2, 1f)
        assertEquals(t2.maxHp, t2.hp, "flames do nothing to it")
    }

    @Test
    fun theAirshipDropsParatroopers() {
        val w = world()
        val ship = w.spawnForTest(EnemyType.AIRSHIP, 4f)
        hold(ship)
        ship.jamTimer = 0f
        run(w, 0.05f)
        val troopers = w.enemies.filter { it.type == EnemyType.BANDIT }
        assertEquals(World.DROP_TROOPERS, troopers.size, "a drop right under it")
        assertTrue(troopers.all { abs(it.dist - ship.dist) < 1f && it.pathIndex == ship.pathIndex })
        run(w, World.DROP_EVERY + 0.1f)
        assertEquals(2 * World.DROP_TROOPERS, w.enemies.count { it.type == EnemyType.BANDIT }, "and another a few seconds on")
        w.damage(ship, 1e7f, null)
        run(w, 1f / 60f)
        assertEquals(1, w.airshipKills)
        assertEquals(1, w.bossKills)
    }

    @Test
    fun ambushesComeOutOfTheCellarsMidRoad() {
        val w = World(outskirts, Rules())
        w.lives = 1_000_000
        w.callWave()
        var t = 0f
        while (w.ambushes.isEmpty() && t < 240f) {
            w.step(0.05f)
            t += 0.05f
        }
        assertTrue(w.ambushing, "an ambush comes within four minutes")
        assertEquals(w.map.paths.size, w.ambushes.size, "a hatch on each road")
        for (a in w.ambushes) {
            val road = w.map.paths[a.path]
            assertTrue(a.dist in road.length * World.AMBUSH_FROM..road.length * World.AMBUSH_TO, "somewhere in the middle of the road")
        }
        val hatch = w.ambushes[0]
        val before = w.enemies.count { it.pathIndex == hatch.path && abs(it.dist - hatch.dist) < 2f }
        val n = w.enemies.size
        run(w, World.AMBUSH_WARN + 0.1f)
        assertTrue(w.ambushes.isEmpty())
        assertTrue(w.enemies.size > n, "enemies burst out")
        assertTrue(w.enemies.count { it.pathIndex == hatch.path && abs(it.dist - hatch.dist) < 2f } > before, "right where the hatch was")

        // Before a wave or once it is beaten, the cellars are empty; and away from the city there are none.
        val calm = world()
        calm.ambushes += Ambush(4.5f, 5.5f, 0, 8f, 0.1f)
        run(calm, 0.5f)
        assertTrue(calm.enemies.isEmpty())
        val green = World(Levels.all[0], Rules())
        green.lives = 1_000_000
        green.callWave()
        run(green, 120f)
        assertFalse(green.ambushing)
        assertTrue(green.ambushes.isEmpty())
    }

    @Test
    fun ambushesAndAirshipsAreSaved() {
        val w = world()
        w.ambushes += Ambush(4.5f, 5.5f, 1, 8f, 1.2f)
        val ship = w.spawnForTest(EnemyType.AIRSHIP, 2f)
        w.damage(ship, 1e7f, null)
        run(w, 1f / 60f)
        val text = w.save()
        val copy = World(outskirts, Rules())
        copy.restore(text)
        assertEquals(text, copy.save(), "restoring loses nothing that is saved")
        assertEquals(1, copy.ambushes.size)
        assertEquals(1, copy.ambushes[0].path)
        assertEquals(1, copy.airshipKills)
    }

    @Test
    fun theBotTakesTheCity() {
        val lost = ArrayList<Int>()
        for (level in Levels.all.filter { it.biome == Biome.RUINS }) {
            val w = Sim.play(level, Sim.fresh(level), { Bot(it) })
            Sim.report("bot ${level.id} ${level.name.ru}", w)
            if (w.phase != Phase.WON) lost += level.id
        }
        assertTrue(lost.isEmpty(), "the bot lost $lost")
    }
}
