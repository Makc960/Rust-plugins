package com.makc.basedefense.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Plays every level with bots: a decent player must win, a lazy one must not breeze through. */
class BalanceTest {
    private fun play(level: LevelDef, bot: ((World) -> Bot)?, maxWaves: Int = Int.MAX_VALUE): World {
        val world = World(level)
        val player = bot?.invoke(world)
        val dt = 1f / 60f
        while (world.time < 6000f && world.phase != Phase.WON && world.phase != Phase.LOST) {
            if (player != null) player.update(dt) else if (world.phase == Phase.READY) world.callWave()
            world.step(dt)
            if (world.wavesStarted > maxWaves) break
        }
        return world
    }

    private fun report(name: String, w: World) {
        println(
            "%-22s %-5s waves %2d/%-2d lives %2d/%d money %4d towers %2d kills %4d time %4.0fs".format(
                name, w.phase, w.wavesStarted, w.totalWaves, w.lives, w.maxLives, w.money, w.towers.size, w.kills, w.time,
            ),
        )
    }

    @Test
    fun goodPlayerWinsEveryLevel() {
        for (level in Levels.all) {
            val w = play(level, { Bot(it) })
            report("bot ${level.id} ${level.name}", w)
            assertEquals(Phase.WON, w.phase, "level ${level.id} must be winnable")
        }
    }

    @Test
    fun doingNothingLoses() {
        for (level in Levels.all) {
            val w = play(level, null)
            report("idle ${level.id}", w)
            assertEquals(Phase.LOST, w.phase, "level ${level.id} without towers")
        }
    }

    @Test
    fun turretsOnlyWithoutUpgradesStruggles() {
        var lost = 0
        for (level in Levels.all) {
            val w = play(level, { Bot(it, upgrades = false, types = listOf(TowerType.TURRET)) })
            report("lazy ${level.id}", w)
            if (w.phase == Phase.LOST) lost++
        }
        assertTrue(lost >= 3, "plain turrets should fail on the later levels, lost only $lost")
    }

    @Test
    fun endlessGetsHardButLastsAWhile() {
        val w = play(Levels.endless, { Bot(it) }, maxWaves = 60)
        report("endless", w)
        assertTrue(w.wavesStarted >= 15, "endless bot reached only ${w.wavesStarted}")
    }
}
