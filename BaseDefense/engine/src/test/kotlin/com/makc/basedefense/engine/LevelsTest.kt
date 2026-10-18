package com.makc.basedefense.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LevelsTest {
    private val levels = Levels.all + Levels.endless

    @Test
    fun mapsAreWellFormed() {
        for (level in levels) {
            assertEquals(ROWS, level.ascii.size, "level ${level.id} rows")
            for (row in level.ascii) assertEquals(COLS, row.length, "level ${level.id} row '$row'")
            val map = GameMap(level)
            for (r in 0 until ROWS) for (c in 0 until COLS) {
                val ch = level.ascii[r][c]
                val drawnAsRoad = ch == '#' || ch == '=' || ch == 'B'
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
}
