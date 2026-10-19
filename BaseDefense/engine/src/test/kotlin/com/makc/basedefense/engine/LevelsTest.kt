package com.makc.basedefense.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LevelsTest {
    private val levels = Levels.all + Levels.endless(Levels.all.size) + (0 until 20).map { Levels.daily(20_000 + it, Levels.all.size) }

    @Test
    fun mapsAreWellFormed() {
        for (level in levels) {
            assertEquals(ROWS, level.ascii.size, "level ${level.id} rows")
            for (row in level.ascii) assertEquals(COLS, row.length, "level ${level.id} row '$row'")
            val map = GameMap(level)
            for (r in 0 until ROWS) for (c in 0 until COLS) {
                val ch = level.ascii[r][c]
                assertTrue(ch in ".#iB~=TRC", "level ${level.id}: unknown terrain '$ch'")
                val drawnAsRoad = ch == '#' || ch == '=' || ch == 'B' || ch == 'i'
                assertEquals(drawnAsRoad, map.isRoad(c, r), "level ${level.id}: cell ($c,$r) is '$ch'")
            }
            assertEquals('B', level.ascii[map.baseRow][map.baseCol], "level ${level.id} base")
            for (p in level.paths) {
                assertTrue(!map.inside(p[0], p[1]), "level ${level.id}: road must start outside the map")
                assertEquals(map.baseCol, p[p.size - 2], "level ${level.id}: every road ends at the base")
                assertEquals(map.baseRow, p[p.size - 1], "level ${level.id}: every road ends at the base")
            }
            assertTrue(map.buildableCount >= 40, "level ${level.id} has only ${map.buildableCount} free cells")
        }
    }

    @Test
    fun worldsHaveTheirOwnLook() {
        assertEquals(32, Levels.all.size)
        for (level in Levels.all) {
            assertEquals(level.id - 1, Levels.all.indexOf(level), "levels go in order")
            assertEquals(Biome.entries[level.world], level.biome, "level ${level.id} biome")
            val cacti = level.ascii.sumOf { row -> row.count { it == 'C' } }
            val ice = level.ascii.sumOf { row -> row.count { it == 'i' } }
            if (level.biome == Biome.DESERT) assertTrue(cacti > 0, "desert level ${level.id} has cacti") else assertEquals(0, cacti)
            if (level.biome != Biome.WINTER) assertEquals(0, ice, "only the north has ice, level ${level.id}")
            if (level.biome == Biome.VOLCANO) assertTrue(level.ascii.any { '~' in it }, "volcano level ${level.id} has lava")
            assertTrue(level.challenge != null, "level ${level.id} has a challenge")
        }
        assertTrue(Levels.all.filter { it.biome == Biome.WINTER }.count { l -> l.ascii.any { 'i' in it } } >= 6)
    }

    @Test
    fun wavesAreGenerated() {
        for (level in levels) {
            val world = World(level)
            val count = if (level.endless) 40 else level.plan.count
            for (i in 0 until count) {
                val w = world.wave(i)!!
                assertTrue(w.total > 0, "level ${level.id} wave $i is empty")
                for ((index, bosses) in level.plan.bosses) {
                    if (index == i) assertEquals(bosses.toSet(), w.bosses.toSet())
                }
            }
            if (!level.endless) assertEquals(null, world.wave(level.plan.count))
        }
    }

    @Test
    fun enemiesArriveWhenTheCampaignIntroducesThem() {
        for (type in EnemyType.entries) {
            val first = Levels.all.first { l ->
                l.plan.intro.any { it.first == type } || l.plan.bosses.values.any { type in it }
            }
            assertEquals(type.campaign, first.id, "$type first shows up on level ${first.id}")
        }
        for (type in TowerType.entries) assertTrue(type.unlock in 1..Levels.all.size)
        for (a in Ability.entries) assertTrue(a.unlock in 1..Levels.all.size)
    }

    @Test
    fun endlessAndDailyOnlyUseKnownEnemies() {
        for (progress in 1..Levels.all.size) {
            val endless = Levels.endless(progress)
            for ((type, _) in endless.plan.intro) assertTrue(type.campaign <= maxOf(progress, 4), "endless at $progress sends $type")
            for (day in 0 until 5) {
                val daily = Levels.daily(30_000 + day, progress)
                for ((type, at) in daily.plan.intro) {
                    assertTrue(type.campaign <= progress, "daily at $progress sends $type")
                    assertTrue(at <= 6, "daily introduces $type at wave $at")
                }
                assertEquals(20, daily.plan.count)
            }
        }
        assertEquals(Levels.daily(12_345, 10).ascii, Levels.daily(12_345, 10).ascii, "the same day gives the same map")
        val maps = (0 until 30).map { Levels.daily(40_000 + it, 24).ascii }.toSet()
        assertTrue(maps.size >= 8, "daily maps vary: ${maps.size}")
    }

    @Test
    fun dailyModsDoNotClash() {
        for (day in 0 until 400) {
            val mods = Mod.forDay(day)
            assertEquals(2, mods.toSet().size)
            assertTrue(!(Mod.RICH in mods && Mod.POOR in mods))
            assertTrue(!(Mod.FAST in mods && Mod.TOUGH in mods))
        }
    }
}
