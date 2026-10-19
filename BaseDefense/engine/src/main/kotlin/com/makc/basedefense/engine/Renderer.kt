package com.makc.basedefense.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Paints a world in map cells: call it inside a transform where one unit is
 * one cell. Pictures come from the sprite atlas; a [Gfx] without it gets the
 * same world painted in plain shapes.
 */
class Renderer {
    private val xs = FloatArray(24)
    private val ys = FloatArray(24)
    private val tmp = FloatArray(4)
    private val art = Art.load()

    /** The world drawn last; pits and icons take its ground. */
    private var biome = Biome.GREEN

    /** The player's camouflage: the squads' sandbags and each commander's uniform. */
    var camo = Camo.FIELD
    val outfits = Array(CommanderType.entries.size) { Camo.FIELD }

    private fun art(g: Gfx): Art? = if (g.sprites) art else null

    /** Draws [s] at its own size times [k]. */
    private fun Gfx.put(s: Sprite, x: Float, y: Float, k: Float = 1f, degrees: Float = 0f, alpha: Float = 1f) =
        sprite(s, x, y, s.cellsW * k, s.cellsH * k, degrees, alpha)

    /** Colors of one world. */
    class Palette(
        val grassA: Int,
        val grassB: Int,
        val tuft: Int,
        val roadEdge: Int,
        val road: Int,
        val roadDot: Int,
        val water: Int,
        val waterLight: Int,
        val rock: Int,
        val rockLight: Int,
        val snow: Boolean,
        /** Its water is lava. */
        val lava: Boolean = false,
    )

    fun palette(b: Biome): Palette = when (b) {
        Biome.GREEN -> GREEN
        Biome.DESERT -> DESERT
        Biome.WINTER -> WINTER
        Biome.VOLCANO -> VOLCANO
        Biome.RUINS -> RUINS
    }

    /** [pxPerCell] is how big a cell is on screen, for the cached terrain picture. */
    fun drawWorld(g: Gfx, w: World, time: Float, pxPerCell: Float) {
        val map = w.map
        val a = art(g)
        biome = map.biome
        g.cached(terrainKey(map), COLS.toFloat(), ROWS.toFloat(), pxPerCell) { drawTerrain(it, map, detail = true) }
        drawWaves(g, map, time)
        drawSpawns(g, w.map, time, w.spawning)
        if (a != null) for (b in w.blasts) if (b.ground) drawScorch(g, a, b)
        for (p in w.patches) drawPatch(g, p, time)
        for (c in w.corpses) drawCorpse(g, c)
        for (t in w.traps) drawTrap(g, t, time)
        drawBase(g, w.map, time, w.baseFlash)
        for (t in w.towers) if (t.type.role == Role.ROAD) drawTower(g, t, time)
        for (t in w.towers) if (t.type.role != Role.ROAD) drawTower(g, t, time)
        for (e in w.enemies) {
            if (e.type.air) drawShadow(g, e) else drawEnemy(g, e, time)
        }
        for (s in w.sentries) drawSentry(g, s)
        w.commander?.let { drawCommander(g, it, time) }
        for (s in w.smokes) drawSmoke(g, s, time)
        for (b in w.bombs) drawBomb(g, b)
        for (b in w.lava) drawLavaBomb(g, b, time)
        for (a in w.ambushes) drawHatch(g, a, time)
        for (p in w.projectiles) drawProjectile(g, p)
        for (t in w.tracers) drawTracer(g, t)
        if (a != null) for (b in w.blasts) drawFireball(g, a, b)
        for (p in w.particles) g.fillCircle(p.x, p.y, p.size, Colors.alpha(p.color, p.life / p.maxLife))
        for (b in w.bolts) {
            val f = (b.life / 0.16f).coerceIn(0f, 1f)
            g.polyline(b.xs, b.ys, b.xs.size, Colors.alpha(b.color, 0.35f * f), 0.18f)
            g.polyline(b.xs, b.ys, b.xs.size, Colors.alpha(WHITE, f), 0.05f)
        }
        for (e in w.enemies) if (e.type.air) drawEnemy(g, e, time)
        for (r in w.rings) {
            val k = r.life / r.maxLife
            g.strokeCircle(r.x, r.y, max(0.02f, r.maxR * (1f - k)), Colors.alpha(r.color, k), 0.06f)
        }
        for (e in w.enemies) drawHealth(g, e)
        w.plane?.let { drawPlane(g, it) }
        if (w.freezeTimer > 0f) drawFrost(g, min(1f, w.freezeTimer / 0.6f), time)
        if (w.weather > 0f) drawWeather(g, map.biome, w.weatherLevel, time)
    }

    fun terrainKey(map: GameMap): String = "map-${map.level.id}-${map.level.plan.seed}-${map.version}"

    // --- terrain --------------------------------------------------------------------------

    /** Everything on the map that never moves: ground, water, roads, bridges and obstacles. */
    fun drawTerrain(g: Gfx, map: GameMap, detail: Boolean) {
        val pal = palette(map.biome)
        val a = art(g)
        if (a != null && detail) {
            drawTiles(g, a, map, pal)
            return
        }
        drawGround(g, map, pal, detail)
        drawRoads(g, map, pal, detail)
        drawDecor(g, map, pal)
    }

    /** The map from the atlas: a ground or road piece per cell, water, then what stands on it. */
    private fun drawTiles(g: Gfx, a: Art, map: GameMap, pal: Palette) {
        val b = map.biome.ordinal
        val joins = roadJoins(map)
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val h = hash(c, r)
            val road = if (map.isRoad(c, r) && map.terrain(c, r) != '=') a.road[b][joins[r * COLS + c]] else null
            // A hair bigger than a cell, so no seam shows between neighbours.
            if (road != null) {
                g.sprite(road, c + 0.51f, r + 0.51f, 1.02f, 1.02f)
            } else {
                g.sprite(a.ground[b][h and 1], c + 0.51f, r + 0.51f, 1.02f, 1.02f, ((h ushr 4) and 3) * 90f)
            }
        }
        drawWater(g, map, pal)
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (map.terrain(c, r) == 'i') drawIce(g, c, r, joins[r * COLS + c])
        }
        drawBridges(g, map, pal)
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val h = hash(c, r)
            val x = c + 0.5f
            val y = r + 0.5f
            when (map.terrain(c, r)) {
                'T' -> a.trees[b].let { g.put(it[(h ushr 8).mod(it.size)], x, y) }
                'R' -> a.rocks[b].let { g.put(it[(h ushr 8).mod(it.size)], x, y) }
                'C' -> g.put(a.cactus, x, y)
            }
        }
    }

    /** For every road cell, the sides its roads leave by: north 1, east 2, south 4, west 8. */
    private fun roadJoins(map: GameMap): IntArray {
        val joins = IntArray(COLS * ROWS)
        fun side(c: Int, r: Int, bit: Int) {
            if (map.inside(c, r)) joins[r * COLS + c] = joins[r * COLS + c] or bit
        }
        for (p in map.level.paths) {
            var c = p[0]
            var r = p[1]
            for (i in 1 until p.size / 2) {
                val c2 = p[i * 2]
                val r2 = p[i * 2 + 1]
                val dc = Integer.signum(c2 - c)
                val dr = Integer.signum(r2 - r)
                val out = if (dc > 0) 2 else if (dc < 0) 8 else if (dr > 0) 4 else 1
                val back = if (dc > 0) 8 else if (dc < 0) 2 else if (dr > 0) 1 else 4
                while (c != c2 || r != r2) {
                    side(c, r, out)
                    c += dc
                    r += dr
                    side(c, r, back)
                }
            }
        }
        return joins
    }

    private fun isWater(map: GameMap, c: Int, r: Int): Boolean =
        !map.inside(c, r) || map.terrain(c, r).let { it == '~' || it == '=' }

    /** Water over the ground, with a light rim where it meets the land and round outer corners. */
    private fun drawWater(g: Gfx, map: GameMap, pal: Palette) {
        val rad = 0.32f
        val wide = 0.08f
        // Opaque, so where two rims meet no brighter seam shows. Lava has a dark crust instead.
        val rim = if (pal.lava) LAVA_CRUST else Colors.mix(pal.water, pal.waterLight, 0.85f)
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (!isWater(map, c, r)) continue
            val x = c.toFloat()
            val y = r.toFloat()
            val n = !isWater(map, c, r - 1)
            val s = !isWater(map, c, r + 1)
            val w = !isWater(map, c - 1, r)
            val e = !isWater(map, c + 1, r)
            // Reaching a little into water next door, so no seam shows.
            val x0 = if (w) x else x - 0.01f
            val x1 = if (e) x + 1f else x + 1.01f
            val y0 = if (n) y else y - 0.01f
            val y1 = if (s) y + 1f else y + 1.01f
            g.fillRoundRect(x0, y0, x1 - x0, y1 - y0, rad, pal.water)
            if (!(n && w)) g.fillRect(x0, y0, rad, rad, pal.water)
            if (!(n && e)) g.fillRect(x1 - rad, y0, rad, rad, pal.water)
            if (!(s && w)) g.fillRect(x0, y1 - rad, rad, rad, pal.water)
            if (!(s && e)) g.fillRect(x1 - rad, y1 - rad, rad, rad, pal.water)
            val top = if (n) rad else 0f
            val bottom = if (s) rad else 0f
            val left = if (w) rad else 0f
            val right = if (e) rad else 0f
            if (n) g.fillRect(x + left, y, 1f - left - right, wide, rim)
            if (s) g.fillRect(x + left, y + 1f - wide, 1f - left - right, wide, rim)
            if (w) g.fillRect(x, y + top, wide, 1f - top - bottom, rim)
            if (e) g.fillRect(x + 1f - wide, y + top, wide, 1f - top - bottom, rim)
            if (n && w) cornerRim(g, x + rad, y + rad, rad, wide, 180f, rim, pal.water)
            if (n && e) cornerRim(g, x + 1f - rad, y + rad, rad, wide, 270f, rim, pal.water)
            if (s && e) cornerRim(g, x + 1f - rad, y + 1f - rad, rad, wide, 0f, rim, pal.water)
            if (s && w) cornerRim(g, x + rad, y + 1f - rad, rad, wide, 90f, rim, pal.water)
        }
    }

    private fun cornerRim(g: Gfx, cx: Float, cy: Float, r: Float, wide: Float, start: Float, rim: Int, water: Int) {
        g.fillArc(cx, cy, r, start, 90f, rim)
        g.fillArc(cx, cy, r - wide, start, 90f, water)
    }

    /** A glaze of ice over the road part of a cell, along the sides it [joins]. */
    private fun drawIce(g: Gfx, c: Int, r: Int, joins: Int) {
        val ice = Colors.alpha(ICE_ROAD, 0.78f)
        val half = 0.3f
        val x = c + 0.5f
        val y = r + 0.5f
        g.fillRoundRect(x - half, y - half, half * 2f, half * 2f, 0.12f, ice)
        if (joins and 1 != 0) g.fillRect(x - half, r.toFloat(), half * 2f, 0.5f, ice)
        if (joins and 4 != 0) g.fillRect(x - half, y, half * 2f, 0.52f, ice)
        if (joins and 8 != 0) g.fillRect(c.toFloat(), y - half, 0.5f, half * 2f, ice)
        if (joins and 2 != 0) g.fillRect(x, y - half, 0.52f, half * 2f, ice)
        val h = hash(c, r)
        for (k in 0 until 3) {
            val gx = c + 0.22f + ((h ushr (k * 5)) and 31) / 31f * 0.46f
            val gy = r + 0.24f + ((h ushr (k * 5 + 2)) and 31) / 31f * 0.46f
            g.line(gx, gy, gx + 0.16f, gy - 0.1f, Colors.alpha(WHITE, 0.85f), 0.03f)
        }
    }

    private fun drawWaves(g: Gfx, map: GameMap, time: Float) {
        val pal = palette(map.biome)
        if (pal.lava) return drawLavaFlow(g, map, pal, time)
        val speed = if (pal.snow) 0.02f else 0.12f
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (map.terrain(c, r) != '~') continue
            val h = hash(c, r)
            for (k in 0 until 2) {
                val off = ((h ushr (k * 5)) and 31) / 31f
                val x = c + ((off + time * speed) % 1f) * 0.75f
                val y = r + 0.3f + k * 0.42f
                g.line(x, y, x + 0.22f, y, pal.waterLight, 0.04f)
            }
        }
    }

    /** Lava glows, its bright streaks creep along and bubbles swell and pop. */
    private fun drawLavaFlow(g: Gfx, map: GameMap, pal: Palette, time: Float) {
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (map.terrain(c, r) != '~') continue
            val h = hash(c, r)
            g.glow(c + 0.5f, r + 0.5f, 0.75f, Colors.alpha(pal.waterLight, 0.16f + 0.08f * sin(time * 1.7f + (h and 15))))
            for (k in 0 until 2) {
                val off = ((h ushr (k * 5)) and 31) / 31f
                val x = c + 0.1f + ((off + time * 0.05f) % 1f) * 0.65f
                val y = r + 0.3f + k * 0.4f
                g.line(x, y, x + 0.22f, y + 0.03f, Colors.alpha(pal.waterLight, 0.8f), 0.05f)
            }
            val pop = (time * 0.55f + ((h ushr 12) and 63) / 63f) % 1f
            val bx = c + 0.25f + ((h ushr 18) and 15) / 15f * 0.5f
            val by = r + 0.25f + ((h ushr 22) and 15) / 15f * 0.5f
            g.fillCircle(bx, by, 0.03f + 0.07f * pop, Colors.alpha(pal.waterLight, 0.9f * (1f - pop)))
        }
    }

    private fun drawGround(g: Gfx, map: GameMap, pal: Palette, detail: Boolean) {
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val t = map.terrain(c, r)
            if (t == '~' || t == '=') {
                g.fillRect(c.toFloat(), r.toFloat(), 1.02f, 1.02f, pal.water)
                if (pal.snow && detail) {
                    // Cracks in the ice.
                    val h = hash(c, r)
                    val x0 = c + ((h and 15) / 15f) * 0.8f + 0.1f
                    g.line(x0, r + 0.1f, x0 + 0.2f, r + 0.5f, Colors.alpha(WHITE, 0.45f), 0.025f)
                    g.line(x0 + 0.2f, r + 0.5f, x0 + 0.05f, r + 0.9f, Colors.alpha(WHITE, 0.45f), 0.025f)
                }
                continue
            }
            g.fillRect(c.toFloat(), r.toFloat(), 1.02f, 1.02f, if ((r + c) % 2 == 0) pal.grassA else pal.grassB)
            if (!detail || t == '#' || t == 'B' || t == 'i') continue
            val h = hash(c, r)
            when (map.biome) {
                Biome.GREEN -> for (k in 0 until 3) {
                    val x = c + 0.15f + ((h ushr (k * 6)) and 63) / 63f * 0.7f
                    val y = r + 0.15f + ((h ushr (k * 6 + 3)) and 63) / 63f * 0.7f
                    g.line(x, y, x - 0.03f, y - 0.1f, pal.tuft, 0.035f)
                    g.line(x, y, x + 0.04f, y - 0.09f, pal.tuft, 0.035f)
                }
                Biome.DESERT -> {
                    for (k in 0 until 2) {
                        val x = c + 0.15f + ((h ushr (k * 6)) and 63) / 63f * 0.7f
                        val y = r + 0.15f + ((h ushr (k * 6 + 3)) and 63) / 63f * 0.7f
                        g.fillCircle(x, y, 0.035f, pal.tuft)
                    }
                    // A dune ripple.
                    val y = r + 0.3f + ((h ushr 20) and 7) / 7f * 0.4f
                    g.line(c + 0.15f, y, c + 0.45f, y - 0.05f, Colors.alpha(pal.tuft, 0.6f), 0.03f)
                    g.line(c + 0.45f, y - 0.05f, c + 0.75f, y, Colors.alpha(pal.tuft, 0.6f), 0.03f)
                }
                Biome.WINTER -> for (k in 0 until 2) {
                    val x = c + 0.15f + ((h ushr (k * 6)) and 63) / 63f * 0.6f
                    val y = r + 0.2f + ((h ushr (k * 6 + 3)) and 63) / 63f * 0.6f
                    g.line(x, y, x + 0.22f, y, pal.tuft, 0.035f)
                }
                Biome.VOLCANO -> for (k in 0 until 2) {
                    val x = c + 0.15f + ((h ushr (k * 6)) and 63) / 63f * 0.7f
                    val y = r + 0.15f + ((h ushr (k * 6 + 3)) and 63) / 63f * 0.7f
                    g.fillCircle(x, y, 0.03f, pal.tuft)
                }
                Biome.RUINS -> {
                    // A crack in the concrete and a few chips of brick.
                    val x = c + 0.2f + (h and 15) / 15f * 0.5f
                    val y = r + 0.2f + ((h ushr 4) and 15) / 15f * 0.5f
                    g.line(x, y, x + 0.12f, y + 0.08f, pal.tuft, 0.025f)
                    g.line(x + 0.12f, y + 0.08f, x + 0.18f, y + 0.22f, pal.tuft, 0.025f)
                    for (k in 0 until 2) {
                        val bx = c + 0.15f + ((h ushr (k * 6 + 9)) and 63) / 63f * 0.7f
                        val by = r + 0.15f + ((h ushr (k * 6 + 12)) and 63) / 63f * 0.7f
                        g.fillRect(bx, by, 0.06f, 0.04f, BRICK)
                    }
                }
            }
        }
    }

    private fun drawRoads(g: Gfx, map: GameMap, pal: Palette, detail: Boolean) {
        for (p in map.paths) g.polyline(p.xs, p.ys, p.size, pal.roadEdge, 0.94f)
        for (p in map.paths) g.polyline(p.xs, p.ys, p.size, pal.road, 0.78f)
        if (detail) {
            for ((pi, p) in map.paths.withIndex()) {
                var d = 0.3f
                var i = 0
                while (d < p.length) {
                    p.pointAt(d, tmp)
                    val h = hash(i, pi + 7)
                    val side = ((h and 15) / 15f - 0.5f) * 0.5f
                    g.fillCircle(tmp[0] - tmp[3] * side, tmp[1] + tmp[2] * side, 0.035f + (h ushr 4 and 3) * 0.008f, pal.roadDot)
                    d += 0.45f
                    i++
                }
            }
        }
        // Icy stretches glint.
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (map.terrain(c, r) != 'i') continue
            g.fillRect(c + 0.11f, r + 0.11f, 0.78f, 0.78f, Colors.alpha(ICE_ROAD, 0.85f))
            val h = hash(c, r)
            for (k in 0 until 3) {
                val x = c + 0.2f + ((h ushr (k * 5)) and 31) / 31f * 0.5f
                val y = r + 0.2f + ((h ushr (k * 5 + 2)) and 31) / 31f * 0.5f
                g.line(x, y, x + 0.16f, y - 0.1f, Colors.alpha(WHITE, 0.8f), 0.03f)
            }
        }
        drawBridges(g, map, pal)
    }

    private fun drawBridges(g: Gfx, map: GameMap, pal: Palette) {
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (map.terrain(c, r) != '=') continue
            val vertical = map.isRoad(c, r - 1) || map.isRoad(c, r + 1)
            if (vertical) {
                g.fillRect(c + 0.08f, r.toFloat(), 0.84f, 1.02f, BRIDGE)
                for (k in 0 until 5) g.line(c + 0.08f, r + 0.1f + k * 0.2f, c + 0.92f, r + 0.1f + k * 0.2f, BRIDGE_GAP, 0.025f)
                g.line(c + 0.08f, r.toFloat(), c + 0.08f, r + 1f, BRIDGE_RAIL, 0.07f)
                g.line(c + 0.92f, r.toFloat(), c + 0.92f, r + 1f, BRIDGE_RAIL, 0.07f)
            } else {
                g.fillRect(c.toFloat(), r + 0.08f, 1.02f, 0.84f, BRIDGE)
                for (k in 0 until 5) g.line(c + 0.1f + k * 0.2f, r + 0.08f, c + 0.1f + k * 0.2f, r + 0.92f, BRIDGE_GAP, 0.025f)
                g.line(c.toFloat(), r + 0.08f, c + 1f, r + 0.08f, BRIDGE_RAIL, 0.07f)
                g.line(c.toFloat(), r + 0.92f, c + 1f, r + 0.92f, BRIDGE_RAIL, 0.07f)
            }
            if (pal.snow) g.fillRect(c + 0.15f, r + 0.15f, 0.3f, 0.12f, Colors.alpha(WHITE, 0.7f))
        }
    }

    private fun drawDecor(g: Gfx, map: GameMap, pal: Palette) {
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val cx = c + 0.5f
            val cy = r + 0.5f
            when (map.terrain(c, r)) {
                'T' -> when (map.biome) {
                    Biome.WINTER -> drawPine(g, cx, cy)
                    Biome.DESERT -> drawPalm(g, cx, cy, hash(c, r))
                    Biome.VOLCANO -> drawCharred(g, cx, cy)
                    Biome.RUINS -> drawWreck(g, cx, cy, hash(c, r))
                    Biome.GREEN -> {
                        g.fillCircle(cx + 0.07f, cy + 0.1f, 0.42f, SHADOW)
                        g.fillCircle(cx, cy, 0.42f, TREE)
                        g.fillCircle(cx - 0.16f, cy + 0.12f, 0.2f, TREE_DARK)
                        g.fillCircle(cx - 0.1f, cy - 0.12f, 0.2f, TREE_LIGHT)
                        g.fillCircle(cx + 0.14f, cy - 0.02f, 0.13f, TREE_LIGHT)
                    }
                }
                'R' -> drawRock(g, cx, cy, hash(c, r), pal)
                'C' -> drawCactus(g, cx, cy, hash(c, r))
            }
        }
    }

    private fun drawRock(g: Gfx, cx: Float, cy: Float, h: Int, pal: Palette) {
        for (k in 0 until 7) {
            val a = k / 7f * 6.283f
            val rr = 0.3f + ((h ushr (k * 3)) and 7) / 7f * 0.1f
            xs[k] = cx + cos(a) * rr + 0.05f
            ys[k] = cy + sin(a) * rr * 0.85f + 0.07f
        }
        g.fillPolygon(xs, ys, 7, SHADOW)
        for (k in 0 until 7) {
            xs[k] -= 0.05f
            ys[k] -= 0.07f
        }
        g.fillPolygon(xs, ys, 7, pal.rock)
        g.fillCircle(cx - 0.08f, cy - 0.08f, 0.13f, pal.rockLight)
        if (pal.snow) {
            g.fillCircle(cx - 0.05f, cy - 0.16f, 0.15f, 0xFFF4F8FB.toInt())
            g.fillCircle(cx + 0.1f, cy - 0.12f, 0.1f, 0xFFF4F8FB.toInt())
        }
    }

    private fun drawPine(g: Gfx, cx: Float, cy: Float) {
        g.fillCircle(cx + 0.08f, cy + 0.12f, 0.38f, SHADOW)
        for (k in 0 until 3) {
            val w = 0.44f - k * 0.11f
            val top = cy - 0.05f - k * 0.17f
            xs[0] = cx - w
            ys[0] = top + 0.32f
            xs[1] = cx
            ys[1] = top - 0.12f
            xs[2] = cx + w
            ys[2] = top + 0.32f
            g.fillPolygon(xs, ys, 3, PINE)
            xs[0] = cx - w * 0.55f
            ys[0] = top + 0.06f
            xs[2] = cx + w * 0.55f
            ys[2] = top + 0.06f
            g.fillPolygon(xs, ys, 3, 0xFFF2F6FA.toInt())
        }
    }

    /** A burnt tree with embers still glowing in it. */
    private fun drawCharred(g: Gfx, cx: Float, cy: Float) {
        g.fillCircle(cx + 0.07f, cy + 0.1f, 0.38f, SHADOW)
        g.fillCircle(cx, cy, 0.38f, CHARRED)
        g.fillCircle(cx - 0.12f, cy + 0.1f, 0.18f, CHARRED_DARK)
        g.fillCircle(cx + 0.1f, cy - 0.12f, 0.05f, EMBER)
        g.fillCircle(cx - 0.15f, cy - 0.06f, 0.035f, EMBER)
    }

    /** A burnt-out car left in the street, turned a little. */
    private fun drawWreck(g: Gfx, cx: Float, cy: Float, h: Int) {
        g.save()
        g.translate(cx, cy)
        g.rotate(((h and 7) - 3.5f) * 6f + (if (h and 8 == 0) 0f else 90f))
        g.fillRoundRect(-0.36f + 0.05f, -0.2f + 0.07f, 0.72f, 0.4f, 0.1f, SHADOW)
        g.fillRoundRect(-0.36f, -0.2f, 0.72f, 0.4f, 0.1f, WRECK)
        g.fillRoundRect(-0.16f, -0.16f, 0.34f, 0.32f, 0.06f, WRECK_DARK)
        g.fillRoundRect(-0.1f, -0.12f, 0.2f, 0.24f, 0.04f, 0xFF2A2624.toInt())
        g.fillCircle(-0.26f, -0.12f, 0.035f, RUST)
        g.fillCircle(0.25f, 0.1f, 0.045f, RUST)
        g.restore()
    }

    private fun drawPalm(g: Gfx, cx: Float, cy: Float, h: Int) {
        g.fillCircle(cx + 0.1f, cy + 0.12f, 0.35f, SHADOW)
        g.line(cx, cy + 0.3f, cx + 0.05f, cy - 0.05f, 0xFF8A6A3C.toInt(), 0.09f)
        val turn = (h and 7) * 0.4f
        for (k in 0 until 5) {
            val a = turn + k * 1.256f
            g.line(cx + 0.05f, cy - 0.05f, cx + 0.05f + cos(a) * 0.38f, cy - 0.05f + sin(a) * 0.3f, 0xFF4F8A35.toInt(), 0.1f)
        }
        g.fillCircle(cx + 0.05f, cy - 0.05f, 0.07f, 0xFF6B4A22.toInt())
    }

    private fun drawCactus(g: Gfx, cx: Float, cy: Float, h: Int) {
        val flip = if (h and 1 == 0) 1f else -1f
        g.fillRoundRect(cx - 0.1f + 0.06f, cy - 0.38f + 0.08f, 0.2f, 0.72f, 0.1f, SHADOW)
        g.fillRoundRect(cx - 0.3f * flip - 0.07f, cy - 0.22f, 0.14f, 0.3f, 0.07f, CACTUS)
        g.fillRoundRect(cx + 0.27f * flip - 0.07f, cy - 0.3f, 0.14f, 0.24f, 0.07f, CACTUS)
        g.line(cx - 0.3f * flip, cy + 0.05f, cx - 0.05f * flip, cy + 0.05f, CACTUS, 0.13f)
        g.line(cx + 0.27f * flip, cy - 0.08f, cx + 0.05f * flip, cy - 0.08f, CACTUS, 0.13f)
        g.fillRoundRect(cx - 0.1f, cy - 0.38f, 0.2f, 0.72f, 0.1f, CACTUS)
        g.line(cx - 0.02f, cy - 0.32f, cx - 0.02f, cy + 0.28f, CACTUS_LIGHT, 0.04f)
        if (h and 6 == 0) g.fillCircle(cx, cy - 0.38f, 0.06f, 0xFFE85A8C.toInt())
    }

    private fun drawSpawns(g: Gfx, map: GameMap, time: Float, active: Boolean) {
        for (p in map.paths) {
            val x0 = p.xs[0]
            val y0 = p.ys[0]
            val (ex, ey, start) = when {
                y0 < 0f -> Triple(x0, 0f, 0f)
                y0 > ROWS -> Triple(x0, ROWS.toFloat(), 180f)
                x0 < 0f -> Triple(0f, y0, -90f)
                else -> Triple(COLS.toFloat(), y0, 90f)
            }
            if (active) g.glow(ex, ey, 1.1f + 0.15f * sin(time * 8f), Colors.alpha(RED, 0.55f))
            g.fillArc(ex, ey, 0.62f, start, 180f, 0xFF262019.toInt())
            g.fillArc(ex, ey, 0.46f, start, 180f, 0xFF0B0907.toInt())
        }
    }

    fun drawBase(g: Gfx, map: GameMap, time: Float, flash: Float) {
        val x = map.baseCol + 0.5f
        val y = map.baseRow + 0.5f
        val a = art(g)
        if (a != null) {
            g.put(a.base, x, y)
            drawFlag(g, x + 0.3f, y - 0.3f, time)
            if (flash > 0f) g.fillCircle(x, y, 0.5f, Colors.alpha(RED, flash * 1.2f))
            return
        }
        g.fillRoundRect(x - 0.47f + 0.05f, y - 0.47f + 0.07f, 0.94f, 0.94f, 0.12f, SHADOW)
        g.fillRoundRect(x - 0.47f, y - 0.47f, 0.94f, 0.94f, 0.12f, 0xFF4A4740.toInt())
        // Plank walls with a corrugated roof.
        g.fillRoundRect(x - 0.38f, y - 0.38f, 0.76f, 0.76f, 0.06f, 0xFF8A6A44.toInt())
        for (k in 0 until 5) {
            val xx = x - 0.3f + k * 0.15f
            g.line(xx, y - 0.34f, xx, y + 0.34f, 0xFF6E5233.toInt(), 0.03f)
        }
        val roof = if (map.biome == Biome.WINTER) 0xFFEFF4F8.toInt() else 0xFF7C8186.toInt()
        g.fillRoundRect(x - 0.24f, y - 0.24f, 0.48f, 0.48f, 0.04f, roof)
        g.line(x - 0.24f, y, x + 0.24f, y, Colors.darker(roof, 0.2f), 0.04f)
        g.strokeRoundRect(x - 0.38f, y - 0.38f, 0.76f, 0.76f, 0.06f, 0xFF4E3A24.toInt(), 0.05f)
        drawFlag(g, x + 0.3f, y - 0.3f, time)
        if (flash > 0f) g.fillRoundRect(x - 0.47f, y - 0.47f, 0.94f, 0.94f, 0.12f, Colors.alpha(RED, flash * 1.4f))
    }

    /** A flag on a pole standing at ([px], [py]). */
    private fun drawFlag(g: Gfx, px: Float, py: Float, time: Float) {
        g.line(px, py, px, py - 0.55f, 0xFFD8D2C4.toInt(), 0.04f)
        for (k in 0..4) {
            val f = k / 4f
            xs[k] = px + f * 0.38f
            ys[k] = py - 0.55f + sin(time * 6f - f * 4f) * 0.04f * f
        }
        for (k in 0..4) {
            xs[9 - k] = xs[k]
            ys[9 - k] = ys[k] + 0.2f
        }
        g.fillPolygon(xs, ys, 10, FLAG)
    }

    private fun drawPatch(g: Gfx, p: Patch, time: Float) {
        val k = (p.life / p.maxLife).coerceIn(0f, 1f)
        val fade = min(1f, k * 3f)
        val a = art(g)
        if (a != null) {
            val size = p.radius * 2.3f
            g.sprite(a.oil, p.x, p.y, size, size, (p.x * 400f + p.y * 300f) % 360f, 0.75f * fade)
            g.glow(p.x, p.y, p.radius * 1.3f, Colors.alpha(FIRE, 0.45f * fade))
            for (j in 0 until 3) {
                val ang = j * 2.1f + time * 1.5f
                val fx = p.x + cos(ang) * p.radius * 0.45f
                val fy = p.y + sin(ang) * p.radius * 0.35f
                val frame = ((time * 10f + j * 1.7f).toInt()).mod(a.fire.size)
                val s = 0.42f * fade * (0.85f + 0.15f * sin(time * 13f + j))
                g.put(a.fire[frame], fx, fy - 0.08f, s / a.fire[frame].cellsH * 0.9f)
            }
            return
        }
        g.fillCircle(p.x, p.y, p.radius, Colors.alpha(0xFF2A1A10.toInt(), 0.5f * fade))
        g.glow(p.x, p.y, p.radius * 1.3f, Colors.alpha(FIRE, 0.55f * fade))
        for (j in 0 until 3) {
            val a = j * 2.1f + time * 2f
            val fx = p.x + cos(a) * p.radius * 0.5f
            val fy = p.y + sin(a) * p.radius * 0.4f
            g.fillCircle(fx, fy - 0.05f * abs(sin(time * 9f + j)), 0.07f * fade, Colors.alpha(0xFFFFC14A.toInt(), fade))
        }
    }

    /** The black mark an explosion leaves on the ground. */
    private fun drawScorch(g: Gfx, a: Art, b: Blast) {
        val fade = min(1f, (b.life - b.age) / 1.5f) * min(1f, b.age / 0.15f)
        val size = b.radius * 1.3f + 0.2f
        g.sprite(a.scorch, b.x, b.y, size, size, b.angle, 0.45f * fade)
    }

    /** The fireball, frame by frame, then its smoke. */
    private fun drawFireball(g: Gfx, a: Art, b: Blast) {
        val t = b.age / Blast.FIRE_LIFE
        if (t >= 1f) return
        val size = b.radius * 2f + 0.3f
        val smoke = a.smoke[min(4, (t * 5f).toInt() + 1)]
        g.sprite(smoke, b.x, b.y - t * 0.15f, size * (0.8f + t * 0.5f), size * (0.8f + t * 0.5f), b.angle + 40f, 0.7f * (1f - t))
        val fire = a.blast[min(4, (t * 5f).toInt())]
        g.sprite(fire, b.x, b.y, size * (0.75f + t * 0.4f), size * (0.75f + t * 0.4f), b.angle, 1f - t * t)
    }

    private fun drawCorpse(g: Gfx, c: Corpse) {
        val k = (c.life / c.maxLife).coerceIn(0f, 1f)
        val a = min(1f, k * 2.5f)
        val r = c.type.radius
        val art = art(g)
        if (c.type.boss && art != null) {
            // The burnt-out wreck.
            g.sprite(art.scorch, c.x, c.y, r * 3.4f, r * 3.4f, 30f, 0.8f * a)
            val s = art.enemies[c.type.ordinal]
            val deg = Math.toDegrees(c.angle.toDouble()).toFloat() + 12f
            g.put(s, c.x, c.y, enemyScale(c.type), deg, 0.85f * a)
            g.fillCircle(c.x, c.y, r * 1.05f, Colors.alpha(0xFF141414.toInt(), 0.55f * a))
            return
        }
        if (c.type.boss) {
            g.fillCircle(c.x, c.y, r * 1.3f, Colors.alpha(0xFF1A1A1A.toInt(), 0.55f * a))
            g.save()
            g.translate(c.x, c.y)
            g.rotate(Math.toDegrees(c.angle.toDouble()).toFloat() + 12f)
            g.fillRoundRect(-r, -r * 0.6f, r * 2f, r * 1.2f, 0.1f, Colors.alpha(0xFF2C2C26.toInt(), 0.9f * a))
            g.fillCircle(-0.05f, 0f, r * 0.35f, Colors.alpha(0xFF141414.toInt(), a))
            g.restore()
            return
        }
        g.fillCircle(c.x + 0.03f, c.y + 0.03f, r * 1.05f, Colors.alpha(0xFF000000.toInt(), 0.25f * a))
        g.fillCircle(c.x, c.y, r * 0.8f, Colors.alpha(Colors.darker(c.type.color, 0.55f), 0.7f * a))
    }

    // --- towers ---------------------------------------------------------------------------

    fun drawTower(g: Gfx, t: Tower, time: Float) {
        val k = (t.age / 0.35f).coerceIn(0f, 1f)
        val pop = if (k >= 1f) 1f else 0.55f + 0.45f * k + sin(k * PI.toFloat()) * 0.22f
        g.save()
        g.translate(t.x, t.y)
        g.scale(pop)
        // Overcharged by the engineer: a crackling ring under the squad.
        if (t.haste > 0f) g.strokeCircle(0f, 0f, 0.47f, Colors.alpha(World.BOLT, 0.45f + 0.3f * sin(time * 14f + t.x)), 0.04f)
        drawTowerShape(g, t.type, t.level, t.branch, 0f, 0f, t.angle, t.flash, t.burning, time, t.spinAngle, t.armed, t.shots)
        if (t.jammed > 0f) {
            g.fillCircle(0f, 0f, 0.44f, Colors.alpha(0xFF000000.toInt(), 0.35f))
            val ph = time * 20f
            for (j in 0 until 3) {
                val x0 = -0.25f + j * 0.25f
                g.line(x0, -0.32f, x0 + 0.08f, -0.22f + 0.04f * sin(ph + j), JAM, 0.035f)
                g.line(x0 + 0.08f, -0.22f + 0.04f * sin(ph + j), x0 + 0.02f, -0.12f, JAM, 0.035f)
            }
        }
        g.restore()
    }

    /** One tower of [type] at a level or branch, centred on (x, y). */
    fun drawTowerShape(
        g: Gfx,
        type: TowerType,
        level: Int,
        branch: Branch?,
        x: Float,
        y: Float,
        angle: Float,
        flash: Float,
        burning: Float,
        time: Float,
        spin: Float = 0f,
        armed: Boolean = true,
        shots: Int = 0,
    ) {
        val a = art(g)
        if (a != null) {
            drawTowerArt(g, a, type, level, branch, x, y, angle, flash, burning, time, armed, shots)
            return
        }
        if (type.role == Role.ROAD) {
            drawMine(g, level, branch, x, y, armed, flash, time)
            return
        }
        g.fillRoundRect(x - 0.42f + 0.04f, y - 0.42f + 0.07f, 0.84f, 0.84f, 0.16f, SHADOW)
        val elite = branch != null || type.vip
        g.fillRoundRect(x - 0.42f, y - 0.42f, 0.84f, 0.84f, 0.16f, if (elite) PLATE_ELITE else PLATE)
        g.strokeRoundRect(x - 0.42f, y - 0.42f, 0.84f, 0.84f, 0.16f, if (elite) GOLD_EDGE else PLATE_EDGE, 0.04f)
        for (k in 0 until 4) {
            val rx = if (k % 2 == 0) -0.3f else 0.3f
            val ry = if (k < 2) -0.3f else 0.3f
            g.fillCircle(x + rx, y + ry, 0.03f, RIVET)
        }
        if (branch != null) {
            star(g, x, y + 0.33f, 0.075f, PIP)
        } else {
            for (i in 0..level) g.fillCircle(x + (i - level / 2f) * 0.13f, y + 0.33f, 0.045f, PIP)
        }

        g.save()
        g.translate(x, y)
        val turns = type != TowerType.TESLA && type != TowerType.RADAR && type != TowerType.QUARRY
        if (turns) g.rotate(angle)
        val tier = if (branch != null) 3 else level
        g.scale(1f + 0.07f * tier)
        val c = type.color
        when (type) {
            TowerType.TURRET -> drawTurret(g, c, level, branch, flash, spin)
            TowerType.SHOTGUN -> {
                g.fillRoundRect(-0.3f, -0.08f, 0.14f, 0.16f, 0.04f, Colors.darker(c, 0.4f))
                when (branch) {
                    Branch.SLUG -> g.fillRoundRect(0f, -0.07f, 0.5f, 0.14f, 0.05f, GUN)
                    Branch.ASSAULT -> {
                        g.fillRoundRect(0f, -0.13f, 0.4f, 0.11f, 0.04f, GUN)
                        g.fillRoundRect(0f, 0.02f, 0.4f, 0.11f, 0.04f, GUN)
                        g.fillCircle(-0.02f, 0.2f, 0.11f, Colors.darker(c, 0.45f))
                    }
                    else -> {
                        g.fillRoundRect(0f, -0.13f, 0.38f, 0.11f, 0.04f, GUN)
                        g.fillRoundRect(0f, 0.02f, 0.38f, 0.11f, 0.04f, GUN)
                    }
                }
                g.fillRoundRect(-0.2f, -0.19f, 0.32f, 0.38f, 0.08f, c)
                g.fillRoundRect(-0.15f, -0.14f, 0.12f, 0.12f, 0.03f, Colors.lighter(c, 0.3f))
                if (flash > 0f) g.glow(0.5f, 0f, 0.38f, Colors.alpha(MUZZLE, 0.9f))
            }
            TowerType.FLAMER -> {
                val tank = when (branch) {
                    Branch.CRYO -> 0xFF5FB8E8.toInt()
                    Branch.NAPALM -> 0xFFFF7A1A.toInt()
                    else -> c
                }
                g.fillCircle(-0.1f, 0f, 0.2f, Colors.darker(tank, 0.35f))
                xs[0] = 0.02f; ys[0] = -0.08f
                xs[1] = 0.38f; ys[1] = -0.05f
                xs[2] = 0.38f; ys[2] = 0.05f
                xs[3] = 0.02f; ys[3] = 0.08f
                g.fillPolygon(xs, ys, 4, GUN)
                g.fillCircle(-0.1f, 0f, 0.15f, tank)
                if (branch != null) {
                    g.fillCircle(-0.22f, -0.15f, 0.08f, tank)
                    g.fillCircle(-0.22f, 0.15f, 0.08f, tank)
                }
                g.fillCircle(-0.14f, -0.05f, 0.06f, Colors.lighter(tank, 0.4f))
                val flicker = 0.07f + 0.02f * sin(time * 30f)
                val fire = if (branch == Branch.CRYO) 0xFF8FDCFF.toInt() else FIRE
                g.glow(0.42f, 0f, if (burning > 0f) 0.4f else flicker * 2f, Colors.alpha(fire, 0.95f))
                g.fillCircle(0.41f, 0f, flicker * 0.6f, if (branch == Branch.CRYO) 0xFFE6F8FF.toInt() else 0xFFFFE6A0.toInt())
            }
            TowerType.ROCKET -> {
                g.fillRoundRect(-0.23f, -0.23f, 0.46f, 0.46f, 0.07f, c)
                when (branch) {
                    Branch.HEAVY -> {
                        g.fillRoundRect(-0.12f, -0.1f, 0.52f, 0.2f, 0.08f, GUN)
                        g.fillCircle(0.4f, 0f, 0.08f, if (flash > 0f) MUZZLE else 0xFFB23A2E.toInt())
                    }
                    Branch.CLUSTER -> for (k in 0 until 6) {
                        val tx = -0.02f + (k / 3) * 0.16f
                        val ty = (k % 3 - 1) * 0.12f
                        g.fillCircle(tx + 0.12f, ty, 0.055f, GUN)
                        g.fillCircle(tx + 0.12f, ty, 0.025f, if (flash > 0f) MUZZLE else 0xFFB23A2E.toInt())
                    }
                    else -> {
                        val tubes = if (level >= 2) 3 else 2
                        for (k in 0 until tubes) {
                            val ty = (k - (tubes - 1) / 2f) * 0.13f
                            g.fillRoundRect(-0.08f, ty - 0.05f, 0.44f, 0.1f, 0.03f, GUN)
                            g.fillCircle(0.36f, ty, 0.035f, if (flash > 0f) MUZZLE else 0xFFB23A2E.toInt())
                        }
                    }
                }
                g.fillRoundRect(-0.2f, -0.2f, 0.12f, 0.4f, 0.03f, Colors.darker(c, 0.3f))
            }
            TowerType.SAM -> {
                g.fillRoundRect(-0.26f, -0.24f, 0.5f, 0.48f, 0.07f, c)
                g.fillRoundRect(-0.26f, -0.24f, 0.12f, 0.48f, 0.04f, Colors.darker(c, 0.3f))
                when (branch) {
                    Branch.LONGBOW -> {
                        g.fillRoundRect(-0.1f, -0.06f, 0.56f, 0.12f, 0.05f, GUN)
                        g.fillRoundRect(0.02f, -0.035f, 0.42f, 0.07f, 0.03f, 0xFFF0F0F0.toInt())
                        g.fillCircle(0.44f, 0f, 0.035f, 0xFFD32F2F.toInt())
                    }
                    Branch.SALVO -> for (k in 0 until 6) {
                        val tx = if (k < 3) 0.13f else -0.03f
                        val ty = (k % 3 - 1) * 0.13f
                        g.fillCircle(tx, ty, 0.065f, GUN)
                        g.fillCircle(tx, ty, 0.033f, 0xFFF0F0F0.toInt())
                    }
                    else -> for (k in 0 until 4) {
                        val tx = if (k < 2) 0.12f else -0.03f
                        val ty = if (k % 2 == 0) -0.1f else 0.1f
                        g.fillCircle(tx, ty, 0.075f, GUN)
                        g.fillCircle(tx, ty, 0.04f, 0xFFF0F0F0.toInt())
                    }
                }
            }
            TowerType.TESLA -> {
                val glow = if (branch == Branch.STUN) 0xFFFFE36B.toInt() else BOLT
                g.fillCircle(0f, 0f, 0.28f, 0xFF30353B.toInt())
                g.strokeCircle(0f, 0f, 0.22f, COPPER, 0.05f)
                g.strokeCircle(0f, 0f, 0.14f, COPPER, 0.05f)
                if (branch == Branch.STORM) g.strokeCircle(0f, 0f, 0.3f, COPPER, 0.035f)
                val pulse = 0.3f + 0.05f * sin(time * 7f)
                g.glow(0f, 0f, pulse + 0.06f * tier, Colors.alpha(glow, 0.7f))
                g.fillCircle(0f, 0f, 0.09f + 0.015f * tier, 0xFFE8FBFF.toInt())
                val a = time * 5f
                val arcs = if (branch != null) 3 else 2
                for (k in 0 until arcs) {
                    val aa = a + k * 6.283f / arcs
                    g.line(cos(aa) * 0.12f, sin(aa) * 0.12f, cos(aa + 0.4f) * 0.27f, sin(aa + 0.4f) * 0.27f, Colors.alpha(glow, 0.8f), 0.025f)
                }
            }
            TowerType.SNIPER -> {
                g.fillCircle(0f, 0f, 0.24f, Colors.darker(c, 0.35f))
                val long = if (branch == Branch.HEAVY_CAL) 0.62f else 0.5f + 0.03f * level
                val thick = if (branch == Branch.HEAVY_CAL) 0.1f else 0.065f
                g.fillRoundRect(0f, -thick / 2f, long, thick, 0.02f, GUN)
                if (branch == Branch.HEAVY_CAL) g.fillRect(long - 0.08f, -0.08f, 0.08f, 0.16f, GUN)
                g.fillRoundRect(-0.16f, -0.16f, 0.3f, 0.32f, 0.08f, c)
                // Camouflage blots and the scope.
                g.fillCircle(-0.08f, -0.06f, 0.06f, Colors.darker(c, 0.3f))
                g.fillCircle(0.04f, 0.07f, 0.05f, Colors.lighter(c, 0.2f))
                g.fillRoundRect(-0.02f, -0.15f, 0.2f, 0.07f, 0.03f, 0xFF1B1E21.toInt())
                g.fillCircle(0.18f, -0.115f, 0.03f, if (branch == Branch.MARKER) 0xFFFF3B3B.toInt() else 0xFF7FD3FF.toInt())
                if (flash > 0f) g.glow(long + 0.05f, 0f, 0.3f, Colors.alpha(MUZZLE, 0.9f))
            }
            TowerType.RAIL -> {
                // Two rails with gold coils round a charged core, and a dark armoured body.
                g.fillCircle(0f, 0f, 0.26f, Colors.darker(RAIL_BODY, 0.4f))
                g.fillRoundRect(-0.02f, -0.12f, 0.58f, 0.07f, 0.02f, GUN)
                g.fillRoundRect(-0.02f, 0.05f, 0.58f, 0.07f, 0.02f, GUN)
                g.fillRoundRect(0.02f, -0.025f, 0.5f, 0.05f, 0.02f, Colors.alpha(c, 0.9f))
                for (k in 0 until 3) g.fillRect(0.1f + k * 0.13f, -0.15f, 0.04f, 0.3f, COIL)
                g.fillRoundRect(-0.2f, -0.17f, 0.3f, 0.34f, 0.08f, RAIL_BODY)
                g.glow(-0.05f, 0f, 0.14f + 0.03f * sin(time * 6f), Colors.alpha(c, 0.6f))
                if (flash > 0f) g.glow(0.58f, 0f, 0.42f, Colors.alpha(c, 0.95f))
            }
            TowerType.RADAR -> drawRadar(g, c, branch, tier, time)
            TowerType.QUARRY -> drawQuarry(g, c, branch, tier, flash, time)
            TowerType.MINES -> Unit
        }
        g.restore()
    }

    /**
     * A squad in its pit: a soldier for each level, three for a specialisation with
     * the special one first. They stand still and turn to [angle]; [shots] says
     * whose turn it was to fire.
     */
    private fun drawTowerArt(
        g: Gfx,
        a: Art,
        type: TowerType,
        level: Int,
        branch: Branch?,
        x: Float,
        y: Float,
        angle: Float,
        flash: Float,
        burning: Float,
        time: Float,
        armed: Boolean,
        shots: Int,
    ) {
        if (type.role == Role.ROAD) {
            drawMineArt(g, a, level, branch, x, y, armed, flash, time)
            return
        }
        // A specialised squad, or one only VIP brings, digs in behind sandbags with a gold edge.
        val elite = branch != null || type.vip
        g.put(if (elite) a.pitElite[biome.ordinal] else a.pit[biome.ordinal], x, y)
        a.camo(camo, elite)?.let { g.put(it, x, y) }
        val props = type == TowerType.RADAR || type == TowerType.QUARRY
        if (props) drawProps(g, a, type, branch, x, y, time)
        val n = if (branch != null) 3 else level + 1
        val spots = if (props) AROUND[n - 1] else SPOTS[n - 1]
        val k = SQUAD_SCALE[n - 1]
        for (i in 0 until n) {
            val ox = spots[i * 2]
            val oy = spots[i * 2 + 1]
            val sx = x + ox
            val sy = y + oy
            val s = if (branch != null && i == 0) a.trooper(branch) else a.trooper(type)
            val out = Math.toDegrees(atan2(oy, ox).toDouble()).toFloat()
            val face = when (type) {
                // Signallers look around, the supply team at its work.
                TowerType.RADAR -> out + 25f * sin(time * 0.7f + i * 2f)
                TowerType.QUARRY -> out + 180f
                else -> angle
            }
            g.put(s, sx, sy, k, face)
            if (props) continue
            // Soldiers face right in the atlas, so the right edge of what shows is the muzzle.
            val rad = Math.toRadians(face.toDouble())
            val dx = cos(rad).toFloat()
            val dy = sin(rad).toFloat()
            val tip = (s.shownDx + s.shownW / 2f) * k
            val mx = sx + dx * tip
            val my = sy + dy * tip
            when (type) {
                TowerType.FLAMER -> if (burning > 0f) {
                    if (branch == Branch.CRYO && i == 0) {
                        g.glow(mx, my, 0.25f, Colors.alpha(0xFF8FDCFF.toInt(), 0.9f))
                    } else {
                        val f = a.fire[((time * 14f).toInt() + i).mod(a.fire.size)]
                        g.put(f, mx + dx * 0.14f, my + dy * 0.14f, 0.3f / f.cellsH, face + 90f, 0.9f)
                    }
                }
                TowerType.TESLA -> {
                    val glow = if (branch == Branch.STUN && i == 0) 0xFFFFE36B.toInt() else BOLT
                    g.glow(mx - dx * 0.04f, my - dy * 0.04f, 0.1f + 0.03f * sin(time * 9f + i), Colors.alpha(glow, 0.7f))
                }
                TowerType.TURRET, TowerType.SHOTGUN, TowerType.SNIPER -> if (flash > 0f && i == shots % n) {
                    val big = type == TowerType.SNIPER || branch == Branch.SLUG || branch == Branch.PIERCER
                    g.put(if (big) a.flashBig else a.flash, mx + dx * 0.08f, my + dy * 0.08f, if (big) 0.7f else 0.55f, face, min(1f, flash / 0.05f))
                }
                TowerType.RAIL -> {
                    // The charge hums at the muzzle and flares when the slug leaves.
                    g.glow(mx - dx * 0.05f, my - dy * 0.05f, 0.08f + 0.02f * sin(time * 8f + i), Colors.alpha(BOLT, 0.75f))
                    if (flash > 0f && i == shots % n) g.glow(mx + dx * 0.1f, my + dy * 0.1f, 0.4f, Colors.alpha(BOLT, min(1f, flash / 0.08f)))
                }
                else -> Unit
            }
        }
        if (branch != null) {
            star(g, x, y + 0.4f, 0.075f, PIP)
        } else {
            for (i in 0..level) g.fillCircle(x + (i - level / 2f) * 0.13f, y + 0.4f, 0.045f, PIP)
        }
        if (type == TowerType.QUARRY && flash > 0f) g.glow(x, y, 0.5f, Colors.alpha(GOLD, flash))
    }

    /** What a squad that does not shoot works with, in the middle of its pit. */
    private fun drawProps(g: Gfx, a: Art, type: TowerType, branch: Branch?, x: Float, y: Float, time: Float) {
        if (type == TowerType.RADAR) {
            when (branch) {
                Branch.COMMAND -> {
                    g.put(a.table, x, y)
                    drawFlag(g, x + 0.06f, y - 0.06f, time)
                }
                Branch.JAMMER -> {
                    g.put(a.jamDevice, x, y)
                    val r = (time * 0.8f) % 1f
                    g.strokeCircle(x, y, 0.2f + r * 0.3f, Colors.alpha(type.color, 1f - r), 0.025f)
                    if ((time * 2.5f).toInt() % 2 == 0) g.glow(x, y, 0.1f, Colors.alpha(0xFFFF3B3B.toInt(), 0.8f))
                }
                else -> g.put(a.dish, x, y, 1f, (time * 90f) % 360f)
            }
            return
        }
        when (branch) {
            Branch.DRILL -> {
                g.put(a.drill, x, y, 0.55f)
                g.put(a.bit, x, y, 0.6f, (time * 220f) % 360f)
            }
            Branch.BANK -> {
                g.put(a.bank, x, y, 0.62f)
                g.put(a.dial, x, y, 0.55f, (time * 40f) % 360f)
            }
            else -> g.put(a.quarry, x, y, 0.55f)
        }
    }

    private fun drawMineArt(g: Gfx, a: Art, level: Int, branch: Branch?, x: Float, y: Float, armed: Boolean, flash: Float, time: Float) {
        if (branch == Branch.SPIKES) {
            g.put(a.spikes, x, y, 1.25f)
        } else {
            val big = branch == Branch.LANDMINE
            g.put(if (big) a.mineBig else a.mine, x, y, if (big) 1.1f else 1f + 0.12f * level, 0f, if (armed) 1f else 0.6f)
            val on = armed && (time * 2f).toInt() % 2 == 0
            if (on) g.glow(x, y, 0.16f, Colors.alpha(0xFFFF3B3B.toInt(), 0.7f))
            if (flash > 0f) g.strokeCircle(x, y, 0.3f, Colors.alpha(0xFFFF6B4A.toInt(), flash * 3f), 0.03f)
        }
        // The minelayer keeps to the roadside, in a corner of the cell, watching his mine.
        g.put(if (branch != null) a.trooper(branch) else a.trooper(TowerType.MINES), x - 0.3f, y + 0.3f, 0.55f, -45f)
    }

    private fun drawTurret(g: Gfx, c: Int, level: Int, branch: Branch?, flash: Float, spin: Float) {
        g.fillCircle(0f, 0f, 0.27f, Colors.darker(c, 0.45f))
        when (branch) {
            Branch.MINIGUN -> {
                g.fillRoundRect(0.05f, -0.11f, 0.42f, 0.22f, 0.06f, GUN)
                val rot = spin % 360f
                for (k in 0 until 3) {
                    val off = sin(Math.toRadians((rot + k * 120f).toDouble()).toFloat()) * 0.07f
                    g.line(0.12f, off, 0.5f, off, 0xFF4A5056.toInt(), 0.035f)
                }
            }
            Branch.PIERCER -> {
                g.fillRoundRect(0.05f, -0.065f, 0.52f, 0.13f, 0.03f, GUN)
                g.fillRect(0.5f, -0.09f, 0.07f, 0.18f, GUN)
            }
            else -> if (level == 0) {
                g.fillRoundRect(0.05f, -0.05f, 0.38f, 0.1f, 0.03f, GUN)
            } else {
                val len = if (level == 2) 0.45f else 0.4f
                g.fillRoundRect(0.05f, -0.12f, len, 0.085f, 0.03f, GUN)
                g.fillRoundRect(0.05f, 0.035f, len, 0.085f, 0.03f, GUN)
                if (level == 2) g.fillRect(0.42f, -0.14f, 0.07f, 0.28f, GUN)
            }
        }
        g.fillCircle(0f, 0f, 0.2f, c)
        g.fillCircle(-0.05f, -0.05f, 0.1f, Colors.lighter(c, 0.3f))
        g.fillCircle(0.09f, 0f, 0.04f, GUN)
        if (flash > 0f) {
            val at = if (branch == Branch.PIERCER) 0.62f else 0.55f
            g.glow(at, 0f, 0.3f, Colors.alpha(MUZZLE, 0.9f))
            g.fillCircle(at - 0.05f, 0f, 0.07f, 0xFFFFFBE0.toInt())
        }
    }

    private fun drawRadar(g: Gfx, c: Int, branch: Branch?, tier: Int, time: Float) {
        when (branch) {
            Branch.COMMAND -> {
                // A tent with a flag on top.
                xs[0] = -0.3f; ys[0] = 0.22f
                xs[1] = 0f; ys[1] = -0.26f
                xs[2] = 0.3f; ys[2] = 0.22f
                g.fillPolygon(xs, ys, 3, 0xFF5D6B3A.toInt())
                g.line(0f, -0.26f, 0f, 0.22f, 0xFF3C4526.toInt(), 0.03f)
                g.line(0.02f, -0.26f, 0.02f, -0.5f, 0xFFD8D2C4.toInt(), 0.03f)
                val wave = sin(time * 6f) * 0.03f
                xs[0] = 0.03f; ys[0] = -0.5f
                xs[1] = 0.25f; ys[1] = -0.46f + wave
                xs[2] = 0.03f; ys[2] = -0.38f
                g.fillPolygon(xs, ys, 3, 0xFFE0443A.toInt())
                return
            }
            Branch.JAMMER -> {
                g.fillCircle(0f, 0f, 0.2f, Colors.darker(c, 0.4f))
                g.line(0f, 0.15f, 0f, -0.4f, 0xFFB8C0C8.toInt(), 0.04f)
                for (k in 0 until 3) {
                    val yy = -0.1f - k * 0.1f
                    val w = 0.18f - k * 0.04f
                    g.line(-w, yy, w, yy, 0xFFB8C0C8.toInt(), 0.03f)
                }
                val blink = if ((time * 2.5f).toInt() % 2 == 0) 0xFFFF3B3B.toInt() else 0xFF5A1010.toInt()
                g.fillCircle(0f, -0.42f, 0.045f, blink)
                val r = (time * 0.8f) % 1f
                g.strokeCircle(0f, 0f, 0.15f + r * 0.25f, Colors.alpha(c, 1f - r), 0.025f)
                return
            }
            else -> Unit
        }
        g.fillCircle(0f, 0f, 0.24f, Colors.darker(c, 0.4f))
        g.fillCircle(0f, 0f, 0.08f, GUN)
        g.save()
        g.rotate((time * 90f) % 360f)
        val dish = 0.26f + 0.03f * tier
        g.fillArc(0f, 0f, dish, -50f, 100f, c)
        g.fillArc(0f, 0f, dish * 0.75f, -50f, 100f, Colors.lighter(c, 0.25f))
        g.line(0f, 0f, dish, 0f, GUN, 0.03f)
        g.restore()
    }

    private fun drawQuarry(g: Gfx, c: Int, branch: Branch?, tier: Int, flash: Float, time: Float) {
        if (branch == Branch.BANK) {
            g.fillRoundRect(-0.28f, -0.26f, 0.56f, 0.52f, 0.06f, 0xFF6E7378.toInt())
            g.strokeRoundRect(-0.28f, -0.26f, 0.56f, 0.52f, 0.06f, 0xFF3E4246.toInt(), 0.04f)
            g.strokeCircle(0f, 0f, 0.15f, 0xFF3E4246.toInt(), 0.04f)
            g.save()
            g.rotate((time * 40f) % 360f)
            for (k in 0 until 3) {
                val a = k * 2.094f
                g.line(0f, 0f, cos(a) * 0.12f, sin(a) * 0.12f, 0xFF3E4246.toInt(), 0.03f)
            }
            g.restore()
            g.fillCircle(0.2f, -0.18f, 0.08f, GOLD)
            if (flash > 0f) g.glow(0f, 0f, 0.5f, Colors.alpha(GOLD, flash))
            return
        }
        // A pit with a derrick and a turning wheel.
        g.fillCircle(0f, 0.05f, 0.26f, 0xFF4A3B2A.toInt())
        g.fillCircle(0f, 0.05f, 0.17f, 0xFF2E241A.toInt())
        val h = if (branch == Branch.DRILL) 0.5f else 0.36f + 0.03f * tier
        g.line(-0.2f, 0.18f, 0f, -h, c, 0.05f)
        g.line(0.2f, 0.18f, 0f, -h, c, 0.05f)
        g.line(-0.12f, -0.05f, 0.12f, -0.05f, c, 0.04f)
        g.save()
        g.translate(0f, -h + 0.06f)
        g.rotate((time * 200f) % 360f)
        g.strokeCircle(0f, 0f, 0.09f, Colors.darker(c, 0.3f), 0.03f)
        g.line(-0.09f, 0f, 0.09f, 0f, Colors.darker(c, 0.3f), 0.025f)
        g.line(0f, -0.09f, 0f, 0.09f, Colors.darker(c, 0.3f), 0.025f)
        g.restore()
        if (branch == Branch.DRILL) {
            val bob = sin(time * 10f) * 0.03f
            g.fillRoundRect(-0.04f, -0.05f + bob, 0.08f, 0.24f, 0.03f, 0xFFB8C0C8.toInt())
        }
        g.fillCircle(0.2f, 0.2f, 0.06f, GOLD)
        if (flash > 0f) g.glow(0f, 0f, 0.5f, Colors.alpha(GOLD, flash))
    }

    private fun drawMine(g: Gfx, level: Int, branch: Branch?, x: Float, y: Float, armed: Boolean, flash: Float, time: Float) {
        if (branch == Branch.SPIKES) {
            for (k in 0 until 5) {
                val px = x - 0.32f + k * 0.16f
                xs[0] = px - 0.06f; ys[0] = y + 0.1f
                xs[1] = px; ys[1] = y - 0.12f
                xs[2] = px + 0.06f; ys[2] = y + 0.1f
                g.fillPolygon(xs, ys, 3, 0xFF9AA2AA.toInt())
            }
            g.fillRoundRect(x - 0.38f, y + 0.08f, 0.76f, 0.07f, 0.03f, 0xFF4A4F55.toInt())
            return
        }
        val big = branch == Branch.LANDMINE
        val r = if (big) 0.3f else 0.18f + 0.025f * level
        g.fillCircle(x + 0.03f, y + 0.04f, r, SHADOW)
        if (big) {
            g.fillRoundRect(x - r, y - r, r * 2f, r * 2f, 0.06f, 0xFF4B5233.toInt())
            g.strokeRoundRect(x - r, y - r, r * 2f, r * 2f, 0.06f, 0xFF2E331F.toInt(), 0.03f)
        } else {
            g.fillCircle(x, y, r, 0xFF4B5233.toInt())
            g.strokeCircle(x, y, r * 0.7f, 0xFF2E331F.toInt(), 0.025f)
        }
        val on = armed && (time * 2f).toInt() % 2 == 0
        g.fillCircle(x, y, 0.05f, if (armed) (if (on) 0xFFFF3B3B.toInt() else 0xFF9A1E1E.toInt()) else 0xFF3A3A3A.toInt())
        if (on) g.glow(x, y, 0.16f, Colors.alpha(0xFFFF3B3B.toInt(), 0.5f))
        if (flash > 0f) g.strokeCircle(x, y, r + 0.08f, Colors.alpha(0xFFFF6B4A.toInt(), flash * 3f), 0.03f)
    }

    // --- enemies --------------------------------------------------------------------------

    // --- the commander --------------------------------------------------------------------

    /** The commander with his ring of rank, a pip for each level, and his rally when it is on. */
    fun drawCommander(g: Gfx, c: Commander, time: Float) {
        if (c.rally > 0f) {
            val k = min(1f, c.rally / 0.5f)
            g.fillCircle(c.x, c.y, World.RALLY_RADIUS, Colors.alpha(GOLD, 0.06f * k))
            g.strokeCircle(c.x, c.y, World.RALLY_RADIUS, Colors.alpha(GOLD, (0.35f + 0.15f * sin(time * 6f)) * k), 0.04f)
        }
        if (c.overcharge > 0f) {
            val k = min(1f, c.overcharge / 0.5f)
            g.fillCircle(c.x, c.y, World.OVERCHARGE_RADIUS, Colors.alpha(World.BOLT, 0.05f * k))
            g.strokeCircle(c.x, c.y, World.OVERCHARGE_RADIUS, Colors.alpha(World.BOLT, (0.35f + 0.2f * sin(time * 11f)) * k), 0.035f)
        }
        val glow = (c.promoted / World.PROMOTED_GLOW).coerceIn(0f, 1f)
        if (glow > 0f) g.glow(c.x, c.y, 0.55f + 0.35f * glow, Colors.alpha(GOLD, 0.6f * glow))
        g.fillCircle(c.x + 0.04f, c.y + 0.07f, 0.3f, SHADOW)
        g.strokeCircle(c.x, c.y, 0.34f, Colors.alpha(GOLD, 0.9f), 0.045f)
        // A little bob in his step.
        val bob = if (c.moving) sin(c.walked * 14f) * 0.025f else 0f
        val a = art(g)
        if (a != null) {
            val s = a.commander(c.type, outfits[c.type.ordinal])
            g.put(s, c.x, c.y + bob, HERO_SCALE, c.angle)
            if (c.flash > 0f) {
                val rad = Math.toRadians(c.angle.toDouble())
                val dx = cos(rad).toFloat()
                val dy = sin(rad).toFloat()
                val tip = (s.shownDx + s.shownW / 2f) * HERO_SCALE
                val mx = c.x + dx * (tip + 0.08f)
                val my = c.y + bob + dy * (tip + 0.08f)
                when (c.type) {
                    CommanderType.ENGINEER -> g.glow(mx, my, 0.18f, Colors.alpha(BOLT, 0.9f))
                    CommanderType.GROM -> g.put(a.flashBig, mx, my, 0.7f, c.angle, min(1f, c.flash / 0.05f))
                    CommanderType.SERGEANT -> g.put(a.flash, mx, my, 0.55f, c.angle, min(1f, c.flash / 0.05f))
                    CommanderType.SOKOLOVA -> g.put(a.flashBig, mx, my, 0.6f, c.angle, min(1f, c.flash / 0.05f))
                }
            }
        } else {
            drawHeroShape(g, c.type, c.x, c.y + bob, c.angle, c.flash)
        }
        for (i in 0..c.level) g.fillCircle(c.x + (i - c.level / 2f) * 0.11f, c.y + 0.43f, 0.042f, PIP)
        if (c.jammed > 0f) {
            val ph = time * 20f
            for (j in 0 until 3) {
                val x0 = c.x - 0.25f + j * 0.25f
                g.line(x0, c.y - 0.4f, x0 + 0.08f, c.y - 0.3f + 0.04f * sin(ph + j), JAM, 0.035f)
                g.line(x0 + 0.08f, c.y - 0.3f + 0.04f * sin(ph + j), x0 + 0.02f, c.y - 0.2f, JAM, 0.035f)
            }
        }
    }

    private fun drawHeroShape(g: Gfx, type: CommanderType, x: Float, y: Float, angle: Float, flash: Float) {
        val body = heroColor(type)
        g.save()
        g.translate(x, y)
        g.rotate(angle)
        g.fillRoundRect(0.02f, -0.05f, if (type == CommanderType.GROM) 0.42f else 0.36f, 0.1f, 0.03f, GUN)
        g.fillCircle(0f, 0f, 0.22f, Colors.darker(body, 0.3f))
        g.fillCircle(0f, 0f, 0.17f, body)
        if (flash > 0f) g.glow(0.42f, 0f, 0.22f, Colors.alpha(if (type == CommanderType.ENGINEER) BOLT else MUZZLE, 0.9f))
        g.restore()
        star(g, x, y, 0.08f, PIP)
    }

    /** The engineer's machine gun on its tripod, with a bar of the time it has left. */
    /** Grom's charge on the road; its light blinks once it is armed. */
    private fun drawTrap(g: Gfx, t: Trap, time: Float) {
        val a = art(g)
        val ready = t.arming <= 0f
        if (a != null) {
            g.put(a.mineBig, t.x, t.y, 0.75f, 0f, if (ready) 1f else 0.6f)
        } else {
            g.fillCircle(t.x, t.y, 0.24f, 0xFF3A3F33.toInt())
            g.strokeCircle(t.x, t.y, 0.24f, 0xFF1C1F18.toInt(), 0.05f)
        }
        if (ready && sin(time * 9f) > 0f) g.fillCircle(t.x, t.y - 0.02f, 0.06f, World.RED)
    }

    fun drawSentry(g: Gfx, s: Sentry) {
        g.fillCircle(s.x + 0.03f, s.y + 0.05f, 0.22f, SHADOW)
        val a = art(g)
        if (a != null) {
            g.put(a.sentry, s.x, s.y, 1f, s.angle)
            if (s.flash > 0f) {
                val rad = Math.toRadians(s.angle.toDouble())
                val tip = (a.sentry.shownDx + a.sentry.shownW / 2f) + 0.06f
                g.put(a.flash, s.x + cos(rad).toFloat() * tip, s.y + sin(rad).toFloat() * tip, 0.45f, s.angle)
            }
        } else {
            g.save()
            g.translate(s.x, s.y)
            g.rotate(s.angle)
            g.fillRoundRect(-0.16f, -0.12f, 0.3f, 0.24f, 0.05f, 0xFF4A5056.toInt())
            g.fillRoundRect(0f, -0.04f, 0.34f, 0.08f, 0.03f, GUN)
            if (s.flash > 0f) g.glow(0.38f, 0f, 0.2f, Colors.alpha(MUZZLE, 0.9f))
            g.restore()
        }
        val f = (s.life / s.maxLife).coerceIn(0f, 1f)
        g.fillRect(s.x - 0.2f, s.y + 0.3f, 0.4f, 0.05f, 0x99000000.toInt())
        g.fillRect(s.x - 0.2f, s.y + 0.3f, 0.4f * f, 0.05f, GOLD)
    }

    /** A commander facing up, fitted into [size]: his portrait in menus and panels. */
    fun drawCommanderIcon(g: Gfx, type: CommanderType, cx: Float, cy: Float, size: Float, alpha: Float = 1f, outfit: Camo = outfits[type.ordinal]) {
        val a = art(g)
        g.save()
        g.translate(cx, cy)
        if (a != null) {
            val s = a.commander(type, outfit)
            g.scale(size / max(s.shownW, s.shownH))
            g.put(s, -s.shownDy, s.shownDx, 1f, -90f, alpha)
        } else {
            g.scale(size * 1.9f)
            drawHeroShape(g, type, 0f, 0f, -90f, 0f)
        }
        g.restore()
    }

    fun heroColor(type: CommanderType): Int = when (type) {
        CommanderType.SERGEANT -> 0xFF5E7A3A.toInt()
        CommanderType.ENGINEER -> 0xFFD9822B.toInt()
        CommanderType.GROM -> 0xFF3E4654.toInt()
        CommanderType.SOKOLOVA -> 0xFF3F6B5E.toInt()
    }

    /** Sokolova's smoke screen: grey puffs rolling about, thickening at first and thinning at the end. */
    private fun drawSmoke(g: Gfx, s: Smoke, time: Float) {
        val k = min(1f, (s.maxLife - s.life) / 0.3f) * min(1f, s.life)
        val r = World.SMOKE_RADIUS
        for (i in 0 until 7) {
            val a = i * 0.9f + time * 0.25f + s.x
            val d = r * (0.3f + 0.04f * ((i * 37) % 10))
            g.fillCircle(s.x + cos(a) * d, s.y + sin(a) * d * 0.85f, r * (0.45f + 0.08f * sin(time * 1.3f + i)), Colors.alpha(SMOKE, 0.26f * k))
        }
        g.fillCircle(s.x, s.y, r * 0.55f, Colors.alpha(SMOKE, 0.32f * k))
    }

    private fun drawShadow(g: Gfx, e: Enemy) {
        val a = art(g)
        if (a != null && e.type == EnemyType.DRONE) {
            val deg = Math.toDegrees(atan2(e.dirY, e.dirX).toDouble()).toFloat()
            g.put(a.droneShadow, e.x + 0.2f, e.y + 0.35f, enemyScale(e.type), deg)
            return
        }
        g.fillCircle(e.x + 0.2f, e.y + 0.35f, e.type.radius * 0.85f, SHADOW)
    }

    /** How much bigger than in the atlas each enemy is drawn. */
    private fun enemyScale(type: EnemyType): Float = when (type) {
        EnemyType.APC -> 1.05f
        EnemyType.HELI -> 1.05f
        EnemyType.DRONE -> 0.75f
        EnemyType.TITAN -> 0.9f
        EnemyType.DIGGER -> 0.8f
        EnemyType.JUGGERNAUT -> 1.25f
        EnemyType.BIKER -> 0.95f
        EnemyType.FLAME_TANK -> 1f
        EnemyType.AIRSHIP -> 1f
        // Drawn half again as big in the atlas.
        EnemyType.MOTHER -> type.radius / 0.38f
        // People: their shoulders are about 0.26 cells across in the atlas.
        else -> type.radius / 0.26f
    }

    private fun drawEnemyArt(g: Gfx, a: Art, type: EnemyType, x: Float, y: Float, angle: Float, time: Float, alpha: Float) {
        val sway = when (type) {
            EnemyType.SWARM, EnemyType.MOTHER -> sin(time * 10f) * 7f
            EnemyType.BRUTE -> sin(time * 6f) * 2.5f
            EnemyType.APC, EnemyType.HELI, EnemyType.DRONE, EnemyType.DIGGER, EnemyType.JUGGERNAUT, EnemyType.FLAME_TANK -> 0f
            EnemyType.AIRSHIP -> sin(time * 1.2f) * 2f
            EnemyType.BIKER -> sin(time * 9f) * 2f
            EnemyType.TITAN -> sin(time * 3f) * 1.5f
            else -> sin(time * 12f) * 4f
        }
        g.put(a.enemies[type.ordinal], x, y, enemyScale(type), angle + sway, alpha)
        if (type == EnemyType.AIRSHIP) propellers(g, x, y, angle + sway, time, alpha)
        if (type == EnemyType.HELI) {
            g.save()
            g.translate(x, y)
            g.rotate((time * 900f) % 360f)
            g.fillCircle(0f, 0f, 0.8f, Colors.alpha(0xFF0E1216.toInt(), 0.14f * alpha))
            for (k in 0 until 2) {
                val b = if (k == 0) 0f else 90f
                g.save()
                g.rotate(b)
                g.fillRoundRect(-0.8f, -0.04f, 1.6f, 0.08f, 0.04f, Colors.alpha(0xFF2A3036.toInt(), 0.8f * alpha))
                g.restore()
            }
            g.fillCircle(0f, 0f, 0.07f, Colors.alpha(0xFF3A4046.toInt(), alpha))
            g.restore()
        }
    }

    fun drawEnemy(g: Gfx, e: Enemy, time: Float) {
        val x = e.x
        val y = e.y
        val r = e.type.radius
        if (e.underground) return drawMound(g, e, time)
        val hidden = e.type.stealth && !e.revealed
        val spawn = (e.age / 0.25f).coerceIn(0f, 1f)
        if (!e.type.air && !hidden) g.fillCircle(x + 0.04f, y + 0.07f, r, SHADOW)
        val angle = Math.toDegrees(atan2(e.dirY, e.dirX).toDouble()).toFloat()
        val alpha = if (hidden) 0.3f + 0.12f * sin(time * 5f + e.lane * 20f) else 1f
        if (spawn < 1f) {
            g.save()
            g.translate(x, y)
            g.scale(0.4f + 0.6f * spawn)
            drawEnemyBody(g, e.type, 0f, 0f, angle, time + e.lane * 10f, alpha)
            g.restore()
        } else {
            drawEnemyBody(g, e.type, x, y, angle, time + e.lane * 10f, alpha)
        }
        if (e.frostTimer > 0f) g.fillCircle(x, y, r * 0.95f, Colors.alpha(0xFFBFE6FF.toInt(), 0.35f))
        if (e.burnTimer > 0f) g.glow(x, y, r * 1.3f, Colors.alpha(FIRE, 0.45f))
        if (e.hitFlash > 0f && !hidden) g.fillCircle(x, y, r * 0.9f, Colors.alpha(WHITE, 0.55f))
        if (e.slowTimer > 0f) g.strokeCircle(x, y, r + 0.06f, Colors.alpha(BOLT, 0.75f), 0.04f)
        if (e.healPulse > 0f) g.strokeCircle(x, y, r + 0.12f, Colors.alpha(World.HEAL, e.healPulse), 0.04f)
        if (e.maxShield > 0f && e.shield > 0f) {
            val k = e.shield / e.maxShield
            g.strokeCircle(x, y, r + 0.1f, Colors.alpha(SHIELD_GLOW, 0.25f + 0.5f * k + (if (e.shieldFlash > 0f) 0.3f else 0f)), 0.05f)
        }
        if (e.markTimer > 0f) {
            val rr = r + 0.16f
            g.strokeCircle(x, y, rr, Colors.alpha(MARK, 0.85f), 0.03f)
            g.line(x - rr - 0.08f, y, x - rr + 0.06f, y, MARK, 0.03f)
            g.line(x + rr - 0.06f, y, x + rr + 0.08f, y, MARK, 0.03f)
            g.line(x, y - rr - 0.08f, x, y - rr + 0.06f, MARK, 0.03f)
        }
        if (e.stunTimer > 0f) {
            for (k in 0 until 3) {
                val a = time * 6f + k * 2.094f
                star(g, x + cos(a) * r * 0.9f, y - r - 0.12f + sin(a) * 0.06f, 0.05f, 0xFFFFE36B.toInt())
            }
        }
        if (e.jamPulse > 0f) {
            val k = e.jamPulse / 0.5f
            g.strokeCircle(x, y, r + 0.35f * (1f - k), Colors.alpha(JAM, k), 0.04f)
        }
    }

    /** A digger underground: only the earth it pushes up shows, moving along the road. */
    private fun drawMound(g: Gfx, e: Enemy, time: Float) {
        val r = e.type.radius
        g.fillCircle(e.x + 0.03f, e.y + 0.05f, r * 0.95f, SHADOW)
        g.fillCircle(e.x, e.y, r * 0.9f, MOUND)
        g.fillCircle(e.x - r * 0.25f, e.y - r * 0.2f, r * 0.45f, MOUND_LIGHT)
        for (k in 0 until 3) {
            val a = time * 5f + k * 2.1f + e.lane * 20f
            g.fillCircle(e.x + cos(a) * r * 0.8f, e.y + sin(a) * r * 0.8f, 0.035f, MOUND_LIGHT)
        }
    }

    fun drawEnemyBody(g: Gfx, type: EnemyType, x: Float, y: Float, angle: Float, time: Float, alpha: Float = 1f) {
        val a = art(g)
        if (a != null) {
            drawEnemyArt(g, a, type, x, y, angle, time, alpha)
            return
        }
        val r = type.radius
        g.save()
        g.translate(x, y)
        g.rotate(angle)
        when (type) {
            EnemyType.BANDIT -> {
                g.fillRoundRect(-0.15f, -r, 0.28f, r * 2f, 0.12f, type.color)
                g.line(0.05f, r * 0.62f, 0.32f, r * 0.62f, GUN, 0.06f)
                g.fillCircle(0.03f, 0f, r * 0.56f, SKIN)
                g.fillArc(0.03f, 0f, r * 0.56f, 90f, 180f, 0xFFB8332B.toInt())
            }
            EnemyType.RUNNER -> {
                val stride = sin(time * 18f) * 0.06f
                g.line(-0.12f, -0.08f, -0.3f + stride, -0.1f, Colors.alpha(type.color, 0.6f), 0.05f)
                g.line(-0.12f, 0.08f, -0.3f - stride, 0.1f, Colors.alpha(type.color, 0.6f), 0.05f)
                g.fillRoundRect(-0.12f, -r * 0.9f, 0.22f, r * 1.8f, 0.1f, type.color)
                g.fillCircle(0.04f, 0f, r * 0.55f, SKIN)
                g.fillArc(0.04f, 0f, r * 0.55f, 90f, 180f, 0xFF2E2E2E.toInt())
            }
            EnemyType.SWARM -> {
                val wob = 1f + 0.08f * sin(time * 14f)
                g.fillCircle(0f, 0f, r * wob, type.color)
                g.fillCircle(-0.04f, -0.05f, r * 0.45f, Colors.darker(type.color, 0.3f))
                g.fillCircle(r * 0.5f, -r * 0.35f, 0.03f, 0xFFFFE04A.toInt())
                g.fillCircle(r * 0.5f, r * 0.35f, 0.03f, 0xFFFFE04A.toInt())
            }
            EnemyType.BRUTE -> {
                g.fillCircle(0f, 0f, r, Colors.darker(type.color, 0.25f))
                g.fillRoundRect(-r * 0.75f, -r, r * 1.3f, r * 0.55f, 0.08f, type.color)
                g.fillRoundRect(-r * 0.75f, r * 0.45f, r * 1.3f, r * 0.55f, 0.08f, type.color)
                g.fillCircle(0.05f, 0f, r * 0.5f, 0xFF3E454C.toInt())
                g.line(0.12f, -r * 0.3f, 0.12f, r * 0.3f, 0xFFFF9F1A.toInt(), 0.05f)
                g.strokeCircle(0f, 0f, r, 0xFF2A2F35.toInt(), 0.04f)
            }
            EnemyType.MEDIC -> {
                g.fillRoundRect(-0.15f, -r, 0.28f, r * 2f, 0.12f, type.color)
                g.fillRect(-0.12f, -0.035f, 0.16f, 0.07f, 0xFFD32F2F.toInt())
                g.fillRect(-0.075f, -0.08f, 0.07f, 0.16f, 0xFFD32F2F.toInt())
                g.fillCircle(0.08f, 0f, r * 0.45f, SKIN)
            }
            EnemyType.DRONE -> {
                g.line(-r, -r, r, r, GUN, 0.06f)
                g.line(-r, r, r, -r, GUN, 0.06f)
                for (k in 0 until 4) {
                    val px = if (k % 2 == 0) -r else r
                    val py = if (k < 2) -r else r
                    g.fillCircle(px, py, r * 0.42f, Colors.alpha(0xFFB0B8C0.toInt(), 0.35f))
                    g.strokeCircle(px, py, r * 0.42f, Colors.alpha(0xFF2B3036.toInt(), 0.7f), 0.02f)
                }
                g.fillRoundRect(-0.1f, -0.1f, 0.2f, 0.2f, 0.05f, type.color)
                g.fillCircle(0.07f, 0f, 0.035f, if ((time * 3f).toInt() % 2 == 0) 0xFFFF3B3B.toInt() else 0xFF5A1010.toInt())
            }
            EnemyType.SHIELD -> {
                g.fillRoundRect(-0.16f, -r * 0.95f, 0.3f, r * 1.9f, 0.12f, type.color)
                g.fillCircle(0f, 0f, r * 0.5f, SKIN)
                g.fillArc(0f, 0f, r * 0.5f, 90f, 180f, 0xFF2D3A47.toInt())
                // The riot shield in front.
                g.fillRoundRect(0.16f, -r * 1.05f, 0.11f, r * 2.1f, 0.04f, 0xFF2F3C4A.toInt())
                g.strokeRoundRect(0.16f, -r * 1.05f, 0.11f, r * 2.1f, 0.04f, 0xFF9FC6E8.toInt(), 0.025f)
            }
            EnemyType.GHOST -> {
                val body = Colors.alpha(type.color, alpha)
                xs[0] = -r * 1.3f; ys[0] = -r * 0.7f
                xs[1] = r * 0.4f; ys[1] = -r * 0.9f
                xs[2] = r * 0.4f; ys[2] = r * 0.9f
                xs[3] = -r * 1.3f; ys[3] = r * 0.7f
                xs[4] = -r * 1.0f + sin(time * 8f) * 0.04f; ys[4] = 0f
                g.fillPolygon(xs, ys, 5, Colors.alpha(Colors.darker(type.color, 0.3f), alpha))
                g.fillCircle(0.02f, 0f, r * 0.8f, body)
                g.fillCircle(0.08f, 0f, r * 0.5f, Colors.alpha(0xFF1E1530.toInt(), alpha))
                g.fillCircle(0.12f, -0.06f, 0.025f, Colors.alpha(0xFFE8D8FF.toInt(), alpha))
                g.fillCircle(0.12f, 0.06f, 0.025f, Colors.alpha(0xFFE8D8FF.toInt(), alpha))
            }
            EnemyType.SAPPER -> {
                g.fillRoundRect(-0.27f, -0.12f, 0.15f, 0.24f, 0.04f, 0xFF4A4F55.toInt())
                g.line(-0.2f, -0.1f, -0.3f, -0.3f, 0xFF9AA2AA.toInt(), 0.025f)
                g.fillCircle(-0.3f, -0.3f, 0.03f, if ((time * 4f).toInt() % 2 == 0) 0xFFFFD23A.toInt() else 0xFF6A5410.toInt())
                g.fillRoundRect(-0.14f, -r * 0.95f, 0.28f, r * 1.9f, 0.12f, type.color)
                g.line(-0.1f, -r * 0.5f, -0.1f, r * 0.5f, 0xFF2E2E2E.toInt(), 0.04f)
                g.fillCircle(0.05f, 0f, r * 0.5f, 0xFF3A3F45.toInt())
                g.fillRoundRect(0.08f, -0.06f, 0.08f, 0.12f, 0.03f, 0xFF7FD3FF.toInt())
            }
            EnemyType.MOTHER -> {
                for (k in 0 until 3) {
                    val lx = -0.15f + k * 0.15f
                    val step = sin(time * 10f + k) * 0.04f
                    g.line(lx, -r * 0.6f, lx + step, -r * 1.15f, 0xFF4A4020.toInt(), 0.04f)
                    g.line(lx, r * 0.6f, lx - step, r * 1.15f, 0xFF4A4020.toInt(), 0.04f)
                }
                val swell = 1f + 0.05f * sin(time * 4f)
                g.fillCircle(-0.12f, 0f, r * 0.85f * swell, type.color)
                for (k in 0 until 4) {
                    val a = k * 1.57f + 0.6f
                    g.fillCircle(-0.12f + cos(a) * r * 0.45f, sin(a) * r * 0.45f, r * 0.22f, Colors.lighter(type.color, 0.35f))
                }
                g.fillCircle(r * 0.55f, 0f, r * 0.4f, Colors.darker(type.color, 0.35f))
                g.line(r * 0.8f, -0.08f, r * 1.05f, -0.14f, 0xFF2E2810.toInt(), 0.04f)
                g.line(r * 0.8f, 0.08f, r * 1.05f, 0.14f, 0xFF2E2810.toInt(), 0.04f)
            }
            EnemyType.APC -> {
                g.fillRoundRect(-0.6f, -0.43f, 1.2f, 0.14f, 0.05f, 0xFF2E3320.toInt())
                g.fillRoundRect(-0.6f, 0.29f, 1.2f, 0.14f, 0.05f, 0xFF2E3320.toInt())
                val tread = (time * 2f) % 0.2f
                var tx = -0.58f + tread
                while (tx < 0.58f) {
                    g.line(tx, -0.43f, tx, -0.29f, 0xFF1B1E13.toInt(), 0.025f)
                    g.line(tx, 0.29f, tx, 0.43f, 0xFF1B1E13.toInt(), 0.025f)
                    tx += 0.2f
                }
                g.fillRoundRect(-0.55f, -0.32f, 1.1f, 0.64f, 0.12f, type.color)
                g.fillRoundRect(0.25f, -0.25f, 0.25f, 0.5f, 0.08f, Colors.darker(type.color, 0.2f))
                g.fillRoundRect(0f, -0.05f, 0.62f, 0.1f, 0.03f, GUN)
                g.fillCircle(-0.05f, 0f, 0.22f, Colors.darker(type.color, 0.3f))
                g.fillCircle(-0.1f, -0.06f, 0.08f, Colors.lighter(type.color, 0.2f))
            }
            EnemyType.SALAMANDER -> {
                g.fillRoundRect(-0.15f, -r, 0.28f, r * 2f, 0.12f, type.color)
                g.fillCircle(0.03f, 0f, r * 0.56f, 0xFFDDE2E8.toInt())
                g.fillArc(0.03f, 0f, r * 0.56f, -60f, 120f, 0xFFF2B33A.toInt())
            }
            EnemyType.DIGGER -> {
                g.fillRoundRect(-0.3f, -0.26f, 0.5f, 0.12f, 0.04f, 0xFF3A3F45.toInt())
                g.fillRoundRect(-0.3f, 0.14f, 0.5f, 0.12f, 0.04f, 0xFF3A3F45.toInt())
                g.fillRoundRect(-0.28f, -0.17f, 0.42f, 0.34f, 0.06f, type.color)
                xs[0] = 0.14f; ys[0] = -0.13f
                xs[1] = 0.42f; ys[1] = 0f
                xs[2] = 0.14f; ys[2] = 0.13f
                g.fillPolygon(xs, ys, 3, 0xFF8A9098.toInt())
                g.fillCircle(-0.08f, 0f, 0.06f, 0xFFFFD25A.toInt())
            }
            EnemyType.JUGGERNAUT -> {
                g.fillRoundRect(-r, -r, r * 2f, r * 0.4f, 0.05f, 0xFF3A3F45.toInt())
                g.fillRoundRect(-r, r * 0.6f, r * 2f, r * 0.4f, 0.05f, 0xFF3A3F45.toInt())
                g.fillRoundRect(-r * 0.9f, -r * 0.7f, r * 1.8f, r * 1.4f, 0.08f, type.color)
                g.fillRoundRect(-r * 0.7f, -r * 0.45f, r * 0.6f, r * 0.9f, 0.05f, 0xFF3E424A.toInt())
                g.fillRoundRect(r * 0.05f, -r * 0.45f, r * 0.6f, r * 0.9f, 0.05f, 0xFF3E424A.toInt())
            }
            EnemyType.TITAN -> {
                g.fillRoundRect(-0.62f, -0.62f, 1.1f, 0.2f, 0.05f, 0xFF3A3F45.toInt())
                g.fillRoundRect(-0.62f, 0.42f, 1.1f, 0.2f, 0.05f, 0xFF3A3F45.toInt())
                g.fillRoundRect(-0.55f, -0.48f, 1.0f, 0.96f, 0.12f, type.color)
                for (k in 0 until 4) g.line(-0.4f + k * 0.18f, -0.25f, -0.48f + k * 0.18f, 0.25f, 0xFF2A2A2E.toInt(), 0.07f)
                g.fillRoundRect(0.2f, -0.42f, 0.5f, 0.12f, 0.04f, GUN)
                g.fillRoundRect(0.2f, 0.3f, 0.5f, 0.12f, 0.04f, GUN)
                g.fillRoundRect(0.22f, -0.12f, 0.16f, 0.24f, 0.05f, 0xFFD62E28.toInt())
            }
            EnemyType.BIKER -> {
                g.fillRoundRect(-0.36f, -0.06f, 0.72f, 0.12f, 0.06f, GUN)
                g.fillCircle(-0.3f, 0f, 0.09f, 0xFF15181B.toInt())
                g.fillCircle(0.3f, 0f, 0.09f, 0xFF15181B.toInt())
                g.fillRoundRect(-0.14f, -0.1f, 0.34f, 0.2f, 0.08f, type.color)
                g.line(0.2f, -0.17f, 0.2f, 0.17f, GUN, 0.04f)
                g.fillCircle(-0.04f, 0f, 0.11f, 0xFF2E2E2E.toInt())
            }
            EnemyType.FLAME_TANK -> {
                g.fillRoundRect(-r, -r, r * 2f, r * 0.42f, 0.05f, 0xFF3A3F45.toInt())
                g.fillRoundRect(-r, r * 0.58f, r * 2f, r * 0.42f, 0.05f, 0xFF3A3F45.toInt())
                g.fillRoundRect(-r * 0.85f, -r * 0.65f, r * 1.7f, r * 1.3f, 0.08f, type.color)
                g.fillRoundRect(-r * 0.7f, -r * 0.4f, r * 0.5f, r * 0.8f, 0.06f, 0xFFD8A040.toInt())
                g.fillCircle(r * 0.1f, 0f, r * 0.38f, Colors.darker(type.color, 0.25f))
                g.line(r * 0.2f, 0f, r * 1.15f, 0f, GUN, 0.07f)
                g.glow(r * 1.2f, 0f, 0.14f, Colors.alpha(FIRE, 0.5f + 0.3f * sin(time * 20f)))
            }
            EnemyType.AIRSHIP -> {
                g.fillRoundRect(-0.9f, -0.34f, 1.8f, 0.68f, 0.34f, type.color)
                g.fillRoundRect(-0.7f, -0.2f, 1.4f, 0.16f, 0.08f, Colors.darker(type.color, 0.12f))
                xs[0] = -0.95f; ys[0] = 0f
                xs[1] = -1.15f; ys[1] = -0.36f
                xs[2] = -0.7f; ys[2] = 0f
                xs[3] = -1.15f; ys[3] = 0.36f
                g.fillPolygon(xs, ys, 4, 0xFFB8332B.toInt())
                g.fillRoundRect(-0.25f, -0.1f, 0.5f, 0.2f, 0.05f, GUN)
            }
            EnemyType.HELI -> {
                g.fillRoundRect(-0.85f, -0.05f, 0.6f, 0.1f, 0.04f, type.color)
                g.line(-0.85f, -0.17f, -0.85f, 0.17f, Colors.alpha(GUN, 0.8f), 0.04f)
                g.fillRoundRect(-0.38f, -0.21f, 0.78f, 0.42f, 0.2f, type.color)
                g.fillRoundRect(0.12f, -0.15f, 0.25f, 0.3f, 0.13f, 0xFF9FD3F0.toInt())
                g.fillCircle(-0.05f, 0f, 0.07f, GUN)
                g.save()
                g.rotate((time * 900f) % 360f)
                g.fillCircle(0f, 0f, 0.78f, Colors.alpha(0xFF0E1216.toInt(), 0.12f))
                g.line(-0.78f, 0f, 0.78f, 0f, Colors.alpha(0xFF15191D.toInt(), 0.75f), 0.07f)
                g.line(0f, -0.78f, 0f, 0.78f, Colors.alpha(0xFF15191D.toInt(), 0.75f), 0.07f)
                g.restore()
            }
        }
        g.restore()
    }

    private fun drawHealth(g: Gfx, e: Enemy) {
        val total = e.maxHp + e.maxShield
        if (e.hp + e.shield >= total - 0.01f) return
        if ((e.type.stealth && !e.revealed) || e.underground) return
        val r = e.type.radius
        val w = if (e.type.boss) 1.3f else max(0.5f, r * 2.2f)
        val h = if (e.type.boss) 0.13f else 0.08f
        val top = e.y - r - (if (e.type.air) 0.32f else 0.18f)
        val left = e.x - w / 2f
        g.fillRect(left - 0.02f, top - 0.02f, w + 0.04f, h + 0.04f, Colors.alpha(0xFF000000.toInt(), 0.7f))
        val f = (e.hp / e.maxHp).coerceIn(0f, 1f)
        val color = when {
            f > 0.6f -> 0xFF6BD66B.toInt()
            f > 0.3f -> 0xFFFFD54A.toInt()
            else -> 0xFFFF4B4B.toInt()
        }
        g.fillRect(left, top, w * f, h, color)
        if (e.maxShield > 0f && e.shield > 0f) {
            g.fillRect(left, top - h * 0.7f, w * (e.shield / e.maxShield), h * 0.6f, SHIELD_GLOW)
        }
    }

    // --- shots and effects ----------------------------------------------------------------

    private fun drawProjectile(g: Gfx, p: Projectile) {
        when (p.kind) {
            Shot.BULLET -> {
                val len = if (p.big) 0.3f else 0.22f
                g.line(p.x - cos(p.angle) * len, p.y - sin(p.angle) * len, p.x, p.y, if (p.big) 0xFFFFD27A.toInt() else TRACER, if (p.big) 0.09f else 0.06f)
            }
            Shot.ROCKET, Shot.MISSILE -> {
                val a = art(g)
                if (a != null) {
                    val k = (if (p.kind == Shot.MISSILE) 0.55f else 0.68f) * (if (p.big) 1.2f else 1f)
                    val back = 0.2f * k / 0.68f
                    g.glow(p.x - cos(p.angle) * back, p.y - sin(p.angle) * back, 0.16f * k / 0.68f, Colors.alpha(FIRE, 0.9f))
                    g.put(if (p.big) a.rocketBig else a.rocket, p.x, p.y, k, Math.toDegrees(p.angle.toDouble()).toFloat())
                    return
                }
                g.save()
                g.translate(p.x, p.y)
                g.rotate(Math.toDegrees(p.angle.toDouble()).toFloat())
                if (p.big) g.scale(1.5f)
                g.glow(-0.17f, 0f, 0.14f, Colors.alpha(FIRE, 0.9f))
                if (p.kind == Shot.ROCKET) {
                    g.fillRoundRect(-0.13f, -0.045f, 0.26f, 0.09f, 0.04f, 0xFF3A3A3A.toInt())
                    g.fillCircle(0.12f, 0f, 0.045f, 0xFFB23A2E.toInt())
                } else {
                    g.fillRoundRect(-0.11f, -0.03f, 0.22f, 0.06f, 0.03f, 0xFFF0F0F0.toInt())
                    g.fillCircle(0.11f, 0f, 0.03f, 0xFFD32F2F.toInt())
                }
                g.restore()
            }
            Shot.BOMBLET -> {
                // A commander's grenade is bigger than a bomblet.
                val r = if (p.big) 0.09f else 0.06f
                g.fillCircle(p.x, p.y, r, 0xFF2E2E2E.toInt())
                g.fillCircle(p.x - r * 0.25f, p.y - r * 0.25f, r * 0.42f, 0xFF6A6A6A.toInt())
            }
        }
    }

    private fun drawTracer(g: Gfx, t: Tracer) {
        val k = (t.life / t.maxLife).coerceIn(0f, 1f)
        when (t.kind) {
            1 -> g.line(t.x1, t.y1, t.x2, t.y2, Colors.alpha(0xFFFFF1C8.toInt(), k), 0.07f)
            2 -> {
                g.line(t.x1, t.y1, t.x2, t.y2, Colors.alpha(0xFFFFFFFF.toInt(), k * 0.9f), 0.04f)
                g.line(t.x1, t.y1, t.x2, t.y2, Colors.alpha(0xFFBFE6FF.toInt(), k * 0.35f), 0.12f)
            }
            3 -> {
                g.line(t.x1, t.y1, t.x2, t.y2, Colors.alpha(MARK, k), 0.04f)
                g.line(t.x1, t.y1, t.x2, t.y2, Colors.alpha(MARK, k * 0.3f), 0.12f)
            }
            4 -> {
                // The railgun's slug: a wide halo that narrows as it fades, round a white-hot core.
                g.line(t.x1, t.y1, t.x2, t.y2, Colors.alpha(BOLT, k * 0.35f), 0.08f + 0.22f * k)
                g.line(t.x1, t.y1, t.x2, t.y2, Colors.alpha(BOLT, k * 0.9f), 0.02f + 0.07f * k)
                g.line(t.x1, t.y1, t.x2, t.y2, Colors.alpha(0xFFFFFFFF.toInt(), k), 0.01f + 0.035f * k)
            }
            else -> g.line(t.x1, t.y1, t.x2, t.y2, Colors.alpha(TRACER, k), 0.035f)
        }
    }

    private fun drawBomb(g: Gfx, b: Bomb) {
        if (b.timer > b.fall) return
        val f = 1f - b.timer / b.fall
        g.fillCircle(b.x, b.y, 0.08f + 0.18f * f, Colors.alpha(0xFF000000.toInt(), 0.25f + 0.3f * f))
        val by = b.y - (1f - f) * 1.2f
        g.fillRoundRect(b.x - 0.06f, by - 0.12f, 0.12f, 0.24f, 0.06f, 0xFF2B2F33.toInt())
        g.fillRect(b.x - 0.08f, by - 0.16f, 0.16f, 0.05f, 0xFF4A5056.toInt())
    }

    /** A lava bomb on its way down: the red mark where it lands and the glowing rock above it. */
    private fun drawLavaBomb(g: Gfx, b: Lava, time: Float) {
        val f = 1f - b.timer / b.fall
        val pulse = 0.5f + 0.5f * sin(time * 18f)
        g.fillCircle(b.x, b.y, World.LAVA_RADIUS * f, Colors.alpha(RED, 0.14f))
        g.strokeCircle(b.x, b.y, World.LAVA_RADIUS, Colors.alpha(RED, 0.3f + 0.4f * f * pulse), 0.04f)
        val bx = b.x + (1f - f) * 0.8f
        val by = b.y - (1f - f) * 3f
        g.glow(bx, by, 0.4f, Colors.alpha(FIRE, 0.65f))
        g.fillCircle(bx, by, 0.12f, LAVA_ROCK)
        g.fillCircle(bx - 0.03f, by - 0.03f, 0.07f, 0xFFFFB347.toInt())
    }

    /** A cellar hatch about to burst open: a red ring that tightens, and the lid lifting. */
    private fun drawHatch(g: Gfx, a: Ambush, time: Float) {
        val f = (1f - a.timer / World.AMBUSH_WARN).coerceIn(0f, 1f)
        val pulse = 0.5f + 0.5f * sin(time * 14f)
        g.strokeCircle(a.x, a.y, 0.5f - 0.15f * f, Colors.alpha(World.AMBUSH, 0.45f + 0.45f * pulse), 0.05f)
        g.fillCircle(a.x, a.y, 0.26f, 0xFF1E1C1A.toInt())
        val lift = f * 0.16f
        g.fillCircle(a.x + lift, a.y - lift, 0.24f, 0xFF5E5A54.toInt())
        g.strokeCircle(a.x + lift, a.y - lift, 0.24f, 0xFF3A3632.toInt(), 0.03f)
        g.line(a.x + lift - 0.14f, a.y - lift, a.x + lift + 0.14f, a.y - lift, 0xFF3A3632.toInt(), 0.03f)
        g.line(a.x + lift, a.y - lift - 0.14f, a.x + lift, a.y - lift + 0.14f, 0xFF3A3632.toInt(), 0.03f)
    }

    /** The airship's two propellers at its tail, spinning. */
    private fun propellers(g: Gfx, x: Float, y: Float, angle: Float, time: Float, alpha: Float) {
        g.save()
        g.translate(x, y)
        g.rotate(angle)
        for (side in listOf(-0.36f, 0.36f)) {
            g.save()
            g.translate(-0.62f, side)
            g.rotate((time * 1100f) % 360f)
            g.fillCircle(0f, 0f, 0.17f, Colors.alpha(0xFF0E1216.toInt(), 0.16f * alpha))
            g.line(-0.17f, 0f, 0.17f, 0f, Colors.alpha(0xFF15191D.toInt(), 0.75f * alpha), 0.04f)
            g.restore()
        }
        g.restore()
    }

    private fun drawPlane(g: Gfx, p: Plane) {
        val a = art(g)
        if (a != null) {
            g.put(a.planeShadow, p.x + 0.6f, p.y + 0.9f, 2.1f, -90f)
            g.put(a.plane, p.x, p.y, 2.1f, -90f)
            return
        }
        // Its shadow first, offset as if the sun is low.
        drawPlaneShape(g, p.x + 0.6f, p.y + 0.9f, Colors.alpha(0xFF000000.toInt(), 0.25f), Colors.alpha(0xFF000000.toInt(), 0.25f))
        drawPlaneShape(g, p.x, p.y, 0xFF5B6670.toInt(), 0xFF3A434B.toInt())
    }

    private fun drawPlaneShape(g: Gfx, x: Float, y: Float, body: Int, wing: Int) {
        xs[0] = x; ys[0] = y - 0.9f
        xs[1] = x + 1.0f; ys[1] = y + 0.15f
        xs[2] = x + 0.12f; ys[2] = y
        xs[3] = x + 0.35f; ys[3] = y + 0.7f
        xs[4] = x; ys[4] = y + 0.55f
        xs[5] = x - 0.35f; ys[5] = y + 0.7f
        xs[6] = x - 0.12f; ys[6] = y
        xs[7] = x - 1.0f; ys[7] = y + 0.15f
        g.fillPolygon(xs, ys, 8, wing)
        g.fillRoundRect(x - 0.12f, y - 0.9f, 0.24f, 1.6f, 0.12f, body)
    }

    private fun drawFrost(g: Gfx, k: Float, time: Float) {
        g.fillRect(0f, 0f, COLS.toFloat(), ROWS.toFloat(), Colors.alpha(0xFFBFE6FF.toInt(), 0.16f * k))
        for (i in 0 until 14) {
            val h = hash(i, 91)
            val x = ((h and 1023) / 1023f) * COLS
            val y = (((h ushr 10) and 1023) / 1023f * ROWS + time * 0.3f) % ROWS
            snowflake(g, x, y, 0.09f, Colors.alpha(WHITE, 0.8f * k))
        }
    }

    private fun drawWeather(g: Gfx, biome: Biome, level: Float, time: Float) {
        if (level <= 0f) return
        when (biome) {
            Biome.DESERT -> {
                g.fillRect(0f, 0f, COLS.toFloat(), ROWS.toFloat(), Colors.alpha(World.SAND, 0.42f * level))
                for (i in 0 until 60) {
                    val h = hash(i, 17)
                    val y = ((h and 1023) / 1023f) * ROWS
                    val speed = 5f + ((h ushr 10) and 7)
                    val x = (((h ushr 14) and 1023) / 1023f * (COLS + 3) + time * speed) % (COLS + 3) - 1.5f
                    g.line(x, y, x + 0.7f, y + 0.06f, Colors.alpha(0xFFFFF0CC.toInt(), 0.7f * level), 0.05f)
                }
            }
            Biome.WINTER -> {
                g.fillRect(0f, 0f, COLS.toFloat(), ROWS.toFloat(), Colors.alpha(WHITE, 0.3f * level))
                for (i in 0 until 70) {
                    val h = hash(i, 29)
                    val fall = 1.2f + ((h ushr 10) and 7) * 0.2f
                    val y = (((h and 1023) / 1023f) * (ROWS + 2) + time * fall) % (ROWS + 2) - 1f
                    val x = (((h ushr 14) and 1023) / 1023f * (COLS + 2) + time * 1.6f + sin(time + i) * 0.2f) % (COLS + 2) - 1f
                    g.fillCircle(x, y, 0.035f + ((h ushr 24) and 3) * 0.012f, Colors.alpha(WHITE, 0.9f * level))
                }
            }
            Biome.VOLCANO -> {
                g.fillRect(0f, 0f, COLS.toFloat(), ROWS.toFloat(), Colors.alpha(0xFF5A1E0A.toInt(), 0.24f * level))
                for (i in 0 until 55) {
                    val h = hash(i, 43)
                    val fall = 0.5f + ((h ushr 10) and 7) * 0.12f
                    val y = (((h and 1023) / 1023f) * (ROWS + 2) + time * fall) % (ROWS + 2) - 1f
                    val x = (((h ushr 14) and 1023) / 1023f * (COLS + 2) + sin(time * 0.8f + i) * 0.3f) % (COLS + 2) - 1f
                    val ember = (h ushr 24) and 7 == 0
                    g.fillCircle(x, y, if (ember) 0.035f else 0.045f, Colors.alpha(if (ember) 0xFFFFA040.toInt() else 0xFF9A928C.toInt(), 0.85f * level))
                }
            }
            Biome.RUINS -> {
                // Dust and smoke drift across the streets while the ambush is on.
                g.fillRect(0f, 0f, COLS.toFloat(), ROWS.toFloat(), Colors.alpha(0xFF2A2622.toInt(), 0.2f * level))
                for (i in 0 until 40) {
                    val h = hash(i, 57)
                    val y = ((h and 1023) / 1023f) * ROWS
                    val speed = 0.4f + ((h ushr 10) and 7) * 0.1f
                    val x = (((h ushr 14) and 1023) / 1023f * (COLS + 2) + time * speed) % (COLS + 2) - 1f
                    g.fillCircle(x, y, 0.06f + ((h ushr 24) and 3) * 0.02f, Colors.alpha(0xFFB4AEA6.toInt(), 0.35f * level))
                }
            }
            Biome.GREEN -> Unit
        }
    }

    // --- icons ----------------------------------------------------------------------------

    fun drawTowerIcon(g: Gfx, type: TowerType, level: Int, cx: Float, cy: Float, size: Float, time: Float, branch: Branch? = null) {
        g.save()
        g.translate(cx, cy)
        g.scale(size)
        // Mines are small and dark: show them on a patch of road.
        if (type.role == Role.ROAD && art(g) != null) g.fillRoundRect(-0.4f, -0.4f, 0.8f, 0.8f, 0.14f, GREEN.road)
        drawTowerShape(g, type, level, branch, 0f, 0f, -45f, 0f, 0f, time, spin = time * 300f)
        g.restore()
    }

    /** Draws [type] so that it fits a circle [size] across, whatever its real size. */
    fun drawEnemyIcon(g: Gfx, type: EnemyType, cx: Float, cy: Float, size: Float, time: Float) {
        val a = art(g)
        if (a != null) {
            // Facing up, with the part that shows in the middle of the icon.
            val s = a.enemies[type.ordinal]
            val k = enemyScale(type)
            g.save()
            g.translate(cx, cy)
            g.scale(size / (max(s.shownW, s.shownH) * k))
            drawEnemyArt(g, a, type, -s.shownDy * k, s.shownDx * k, -90f, time, 0.95f)
            g.restore()
            return
        }
        val extent = when (type) {
            EnemyType.APC -> 0.62f
            EnemyType.HELI -> 0.85f
            EnemyType.DRONE -> type.radius * 1.42f
            EnemyType.MOTHER -> type.radius * 1.15f
            EnemyType.GHOST -> type.radius * 1.3f
            EnemyType.SAPPER -> 0.34f
            EnemyType.TITAN -> 0.7f
            EnemyType.DIGGER -> 0.42f
            EnemyType.BIKER -> 0.42f
            EnemyType.FLAME_TANK -> 0.56f
            EnemyType.AIRSHIP -> 0.95f
            else -> type.radius
        }
        g.save()
        g.translate(cx, cy)
        g.scale(size / (2f * extent))
        drawEnemyBody(g, type, 0f, 0f, -90f, time, alpha = 0.95f)
        g.restore()
    }

    fun drawAbilityIcon(g: Gfx, a: Ability, cx: Float, cy: Float, size: Float, color: Int) {
        val s = size / 2f
        when (a) {
            Ability.AIRSTRIKE -> {
                g.save()
                g.translate(cx, cy)
                g.scale(s / 1.0f)
                xs[0] = 0f; ys[0] = -0.9f
                xs[1] = 0.85f; ys[1] = 0.2f
                xs[2] = 0.12f; ys[2] = 0.05f
                xs[3] = 0.3f; ys[3] = 0.75f
                xs[4] = 0f; ys[4] = 0.6f
                xs[5] = -0.3f; ys[5] = 0.75f
                xs[6] = -0.12f; ys[6] = 0.05f
                xs[7] = -0.85f; ys[7] = 0.2f
                g.fillPolygon(xs, ys, 8, color)
                g.restore()
            }
            Ability.FREEZE -> snowflake(g, cx, cy, s * 0.95f, color)
            Ability.REPAIR -> {
                // A red-cross kit: lives back for the base.
                val t = s * 0.36f
                g.fillRoundRect(cx - t, cy - s * 0.85f, t * 2f, s * 1.7f, t * 0.4f, color)
                g.fillRoundRect(cx - s * 0.85f, cy - t, s * 1.7f, t * 2f, t * 0.4f, color)
            }
        }
    }

    /** A rock, tree or cactus ([kind] as on the map) fitting [size]. */
    fun drawObstacle(g: Gfx, kind: Char, biome: Biome, cx: Float, cy: Float, size: Float) {
        val a = art(g)
        if (a != null) {
            val s = when (kind) {
                'T' -> a.trees[biome.ordinal][0]
                'C' -> a.cactus
                else -> a.rocks[biome.ordinal][0]
            }
            val k = size / max(s.shownW, s.shownH)
            g.sprite(s, cx - s.shownDx * k, cy - s.shownDy * k, s.cellsW * k, s.cellsH * k)
            return
        }
        g.save()
        g.translate(cx, cy)
        g.scale(size)
        val pal = palette(biome)
        when (kind) {
            'T' -> when (biome) {
                Biome.WINTER -> drawPine(g, 0f, 0f)
                Biome.DESERT -> drawPalm(g, 0f, 0f, 3)
                Biome.VOLCANO -> drawCharred(g, 0f, 0f)
                Biome.RUINS -> drawWreck(g, 0f, 0f, 3)
                Biome.GREEN -> {
                    g.fillCircle(0f, 0f, 0.42f, TREE)
                    g.fillCircle(-0.16f, 0.12f, 0.2f, TREE_DARK)
                    g.fillCircle(-0.1f, -0.12f, 0.2f, TREE_LIGHT)
                    g.fillCircle(0.14f, -0.02f, 0.13f, TREE_LIGHT)
                }
            }
            'C' -> drawCactus(g, 0f, 0f, 2)
            else -> drawRock(g, 0f, 0f, 12345, pal)
        }
        g.restore()
    }

    fun drawMapThumb(g: Gfx, map: GameMap, x: Float, y: Float, w: Float, h: Float) {
        val cell = min(w / COLS, h / ROWS)
        g.save()
        g.translate(x + (w - cell * COLS) / 2f, y + (h - cell * ROWS) / 2f)
        g.scale(cell)
        g.cached("thumb-${map.level.id}-${map.level.plan.seed}", COLS.toFloat(), ROWS.toFloat(), cell) {
            drawTerrain(it, map, detail = false)
            drawBase(it, map, 0f, 0f)
        }
        g.restore()
    }

    fun star(g: Gfx, cx: Float, cy: Float, r: Float, color: Int) {
        for (k in 0 until 10) {
            val a = -PI / 2 + k * PI / 5
            val rr = if (k % 2 == 0) r else r * 0.45f
            xs[k] = cx + (cos(a) * rr).toFloat()
            ys[k] = cy + (sin(a) * rr).toFloat()
        }
        g.fillPolygon(xs, ys, 10, color)
    }

    fun snowflake(g: Gfx, cx: Float, cy: Float, r: Float, color: Int) {
        val w = max(0.01f, r * 0.16f)
        for (k in 0 until 3) {
            val a = k * PI.toFloat() / 3f
            val dx = cos(a) * r
            val dy = sin(a) * r
            g.line(cx - dx, cy - dy, cx + dx, cy + dy, color, w)
            for (side in listOf(-1f, 1f)) {
                val bx = cx + dx * 0.55f * side
                val by = cy + dy * 0.55f * side
                val a2 = a + side * 0f
                g.line(bx, by, bx + cos(a2 + 0.7f) * r * 0.3f * side, by + sin(a2 + 0.7f) * r * 0.3f * side, color, w * 0.8f)
                g.line(bx, by, bx + cos(a2 - 0.7f) * r * 0.3f * side, by + sin(a2 - 0.7f) * r * 0.3f * side, color, w * 0.8f)
            }
        }
    }

    private fun hash(a: Int, b: Int): Int {
        var h = a * 73_856_093 xor b * 19_349_663
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        return h xor (h ushr 15)
    }

    companion object {
        /** Where 1, 2 or 3 soldiers stand in their pit, the special one first. */
        private val SPOTS = arrayOf(
            floatArrayOf(0f, 0f),
            floatArrayOf(-0.19f, 0.06f, 0.19f, -0.06f),
            floatArrayOf(0f, -0.19f, -0.2f, 0.13f, 0.2f, 0.13f),
        )

        /** The same around something standing in the middle of the pit. */
        private val AROUND = arrayOf(
            floatArrayOf(0f, 0.25f),
            floatArrayOf(-0.24f, 0.12f, 0.24f, 0.12f),
            floatArrayOf(0f, 0.27f, -0.25f, -0.1f, 0.25f, -0.1f),
        )
        private val SQUAD_SCALE = floatArrayOf(0.86f, 0.7f, 0.62f)

        /** A commander stands a head taller than his men. */
        private const val HERO_SCALE = 1.0f

        // The colours of the atlas tiles, for maps painted without them.
        val GREEN = Palette(
            grassA = 0xFF39C272.toInt(), grassB = 0xFF35B86B.toInt(), tuft = 0xFF2FA862.toInt(),
            roadEdge = 0xFF7D9698.toInt(), road = 0xFF89A4A6.toInt(), roadDot = 0xFF7D9698.toInt(),
            water = 0xFF3E9FDA.toInt(), waterLight = 0xFFA6DCF6.toInt(),
            rock = 0xFF90ADAF.toInt(), rockLight = 0xFFAFC7C9.toInt(), snow = false,
        )
        val DESERT = Palette(
            grassA = 0xFFE7D4A9.toInt(), grassB = 0xFFE1CC9F.toInt(), tuft = 0xFFCDB68A.toInt(),
            roadEdge = 0xFFB89668.toInt(), road = 0xFFD1AA76.toInt(), roadDot = 0xFFB89668.toInt(),
            water = 0xFF2DB2C6.toInt(), waterLight = 0xFFB4EEF3.toInt(),
            rock = 0xFFAA8265.toInt(), rockLight = 0xFFC49C7C.toInt(), snow = false,
        )
        val WINTER = Palette(
            grassA = 0xFFE5EBF1.toInt(), grassB = 0xFFDCE4EB.toInt(), tuft = 0xFFC6D0DA.toInt(),
            roadEdge = 0xFF7D9698.toInt(), road = 0xFF89A4A6.toInt(), roadDot = 0xFF7D9698.toInt(),
            water = 0xFFA4D3EC.toInt(), waterLight = 0xFFE3F4FC.toInt(),
            rock = 0xFF838A95.toInt(), rockLight = 0xFF9EA5AF.toInt(), snow = true,
        )

        val VOLCANO = Palette(
            grassA = 0xFF4D433E.toInt(), grassB = 0xFF493F3A.toInt(), tuft = 0xFFE2702A.toInt(),
            roadEdge = 0xFF776C64.toInt(), road = 0xFF8A7D74.toInt(), roadDot = 0xFF776C64.toInt(),
            water = 0xFFE2571E.toInt(), waterLight = 0xFFFFC14D.toInt(),
            rock = 0xFF46484F.toInt(), rockLight = 0xFF5E616A.toInt(), snow = false, lava = true,
        )

        val RUINS = Palette(
            grassA = 0xFF8C8882.toInt(), grassB = 0xFF86827C.toInt(), tuft = 0xFF68645E.toInt(),
            roadEdge = 0xFF46484C.toInt(), road = 0xFF55585D.toInt(), roadDot = 0xFFC9B458.toInt(),
            water = 0xFF4C6A73.toInt(), waterLight = 0xFF8EA8AF.toInt(),
            rock = 0xFFA39D95.toInt(), rockLight = 0xFFC0BAB2.toInt(), snow = false,
        )

        val TREE = 0xFF1E9E55.toInt()
        val TREE_DARK = 0xFF167A41.toInt()
        val TREE_LIGHT = 0xFF33C06F.toInt()
        val PINE = 0xFF2C6147.toInt()
        val CACTUS = 0xFF4E9845.toInt()
        val CACTUS_LIGHT = 0xFF6FB862.toInt()
        val CHARRED = 0xFF392D28.toInt()
        val WRECK = 0xFF5A3A2C.toInt()
        val WRECK_DARK = 0xFF3A2A22.toInt()
        val RUST = 0xFFA4552C.toInt()
        val BRICK = 0xFF9A5A44.toInt()
        val CHARRED_DARK = 0xFF231B18.toInt()
        val EMBER = 0xFFFF8A2A.toInt()
        val LAVA_CRUST = 0xFF2E2420.toInt()
        val LAVA_ROCK = 0xFF3A1E14.toInt()
        val MOUND = 0xFF5E4A3A.toInt()
        val MOUND_LIGHT = 0xFF8A7058.toInt()
        val ICE_ROAD = 0xFFBFE3F5.toInt()
        val BRIDGE = 0xFF8B5E34.toInt()
        val BRIDGE_GAP = 0xFF5A3B20.toInt()
        val BRIDGE_RAIL = 0xFF4A2F18.toInt()
        val SHADOW = 0x55000000
        val PLATE = 0xFF3B4148.toInt()
        val PLATE_ELITE = 0xFF464035.toInt()
        val SMOKE = 0xFFC9CEC6.toInt()
        val RAIL_BODY = 0xFF2A2D33.toInt()
        val COIL = 0xFFD9A93A.toInt()
        val PLATE_EDGE = 0xFF262B30.toInt()
        val GOLD_EDGE = 0xFFC9A23A.toInt()
        val RIVET = 0xFF5A626B.toInt()
        val PIP = 0xFFFFD54A.toInt()
        val GOLD = 0xFFFFD54A.toInt()
        val GUN = 0xFF24282C.toInt()
        val SKIN = 0xFFD9A066.toInt()
        val COPPER = 0xFFC07A3A.toInt()
        val MUZZLE = 0xFFFFD86A.toInt()
        val FIRE = 0xFFFF8A2A.toInt()
        val BOLT = 0xFF6FE3FF.toInt()
        val TRACER = 0xFFFFE9A8.toInt()
        val FLAG = 0xFF6BD66B.toInt()
        val RED = 0xFFFF4B4B.toInt()
        val WHITE = 0xFFFFFFFF.toInt()
        val SHIELD_GLOW = 0xFF7FC4FF.toInt()
        val MARK = 0xFFFF3B3B.toInt()
        val JAM = 0xFFFFD23A.toInt()
    }
}
