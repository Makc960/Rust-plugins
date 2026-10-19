package com.makc.basedefense.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Plays every level with bots: a decent player must win, a lazy one must not breeze through. */
class BalanceTest {
    @Test
    fun goodPlayerWinsEveryLevel() {
        val lost = ArrayList<Int>()
        for (level in Levels.all) {
            val w = Sim.play(level, Sim.fresh(level), { Bot(it) })
            Sim.report("bot ${level.id} ${level.name.ru}", w)
            if (w.phase != Phase.WON) lost += level.id
        }
        assertTrue(lost.isEmpty(), "every level must be winnable, the bot lost $lost")
    }

    @Test
    fun doingNothingLoses() {
        for (level in Levels.all) {
            val w = Sim.play(level, Sim.fresh(level), null)
            assertEquals(Phase.LOST, w.phase, "level ${level.id} without towers")
        }
    }

    @Test
    fun turretsOnlyWithoutUpgradesStruggles() {
        var lost = 0
        for (level in Levels.all) {
            val w = Sim.play(level, Sim.fresh(level), { Bot(it, upgrades = false, types = listOf(TowerType.TURRET), abilities = false) })
            Sim.report("lazy ${level.id}", w)
            if (w.phase == Phase.LOST) lost++
        }
        assertTrue(lost >= 12, "plain turrets should fail on the later levels, lost only $lost")
    }

    @Test
    fun endlessGetsHardButLastsAWhile() {
        val w = Sim.play(Levels.endless(Levels.all.size), Rules(), { Bot(it) }, maxWaves = 60)
        Sim.report("endless", w)
        assertTrue(w.wavesStarted >= 15, "endless bot reached only ${w.wavesStarted}")
        assertTrue(w.phase == Phase.LOST, "endless must end eventually")
    }

    @Test
    fun dailyIsBeatable() {
        var won = 0
        val days = 6
        for (day in 0 until days) {
            val d = 21_000 + day * 3
            val progress = 6 + day * 3
            val level = Levels.daily(d, progress)
            val rules = Rules(mods = Mod.forDay(d), progress = progress)
            val w = Sim.play(level, rules, { Bot(it) })
            Sim.report("daily ${Mod.forDay(d).joinToString { it.name }} p$progress", w)
            if (w.phase == Phase.WON) won++
        }
        assertTrue(won >= days / 2, "the bot won only $won of $days dailies")
    }

    @Test
    fun weeklyOperationsAreHardButBeatable() {
        val medals = ArrayList<Int>()
        for (k in 0 until 6) {
            val week = 3000 + k
            val progress = 8 + k * 3
            val level = Levels.weekly(week, progress)
            val rules = Rules(mods = Mod.forWeek(week), progress = progress)
            val w = Sim.play(level, rules, { Bot(it) })
            Sim.report("weekly ${Mod.forWeek(week).joinToString { it.name }} p$progress", w)
            medals += if (w.phase == Phase.WON) Weekly.medal(w.lives, w.maxLives) else 0
        }
        assertTrue(medals.count { it > 0 } >= 3, "the bot won only ${medals.count { it > 0 }} of 6 operations: $medals")
        assertTrue(medals.count { it == Weekly.GOLD } < 6, "gold should not come every week: $medals")
    }
}

object Sim {
    /** Rules for someone playing the campaign in order: only what the level unlocks. */
    fun fresh(level: LevelDef, difficulty: Difficulty = Difficulty.NORMAL, challenge: Boolean = false): Rules =
        Rules(difficulty = difficulty, challenge = if (challenge) level.challenge else null, progress = level.id)

    fun play(level: LevelDef, rules: Rules, bot: ((World) -> Bot)?, maxWaves: Int = Int.MAX_VALUE, dt: Float = 1f / 60f): World {
        val world = World(level, rules)
        val player = bot?.invoke(world)
        while (world.time < 6000f && !world.over) {
            if (player != null) player.update(dt) else if (world.phase == Phase.READY) world.callWave()
            world.step(dt)
            if (world.wavesStarted > maxWaves) break
        }
        return world
    }

    fun report(name: String, w: World) {
        val kinds = w.towers.groupBy { it.type }.entries.joinToString(" ") { (k, v) ->
            "${k.name.take(3)}${v.size}" + v.mapNotNull { it.branch?.name?.take(3) }.let { if (it.isEmpty()) "" else it.joinToString("/", "(", ")") }
        }
        println(
            "%-28s %-5s waves %2d/%-2d lives %2d/%d money %4d kills %4d time %4.0fs | %s".format(
                name, w.phase, w.wavesStarted, w.totalWaves, w.lives, w.maxLives, w.money, w.kills, w.time, kinds,
            ),
        )
    }
}
