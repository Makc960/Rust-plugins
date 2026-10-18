package com.makc.basedefense.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Paints a world in map cells: call it inside a transform where one unit is
 * one cell. Every shape is drawn here, no images.
 */
class Renderer {
    private val xs = FloatArray(16)
    private val ys = FloatArray(16)
    private val tmp = FloatArray(4)

    /** [pxPerCell] is how big a cell is on screen, for the cached terrain picture. */
    fun drawWorld(g: Gfx, w: World, time: Float, pxPerCell: Float) {
        val map = w.map
        g.cached("map-${map.level.id}", COLS.toFloat(), ROWS.toFloat(), pxPerCell) { drawTerrain(it, map, detail = true) }
        drawWaves(g, map, time)
        drawSpawns(g, w.map, time, w.spawning)
        drawBase(g, w.map, time, w.baseFlash)
        for (t in w.towers) drawTower(g, t.type, t.level, t.x, t.y, t.angle, t.flash, t.burning, time)
        for (e in w.enemies) {
            if (e.type.air) drawShadow(g, e) else drawEnemy(g, e, time)
        }
        for (p in w.projectiles) drawProjectile(g, p)
        for (t in w.tracers) g.line(t.x1, t.y1, t.x2, t.y2, Colors.alpha(TRACER, t.life / 0.09f), 0.035f)
        for (p in w.particles) g.fillCircle(p.x, p.y, p.size, Colors.alpha(p.color, p.life / p.maxLife))
        for (b in w.bolts) {
            val f = (b.life / 0.16f).coerceIn(0f, 1f)
            g.polyline(b.xs, b.ys, b.xs.size, Colors.alpha(BOLT, 0.35f * f), 0.18f)
            g.polyline(b.xs, b.ys, b.xs.size, Colors.alpha(WHITE, f), 0.05f)
        }
        for (e in w.enemies) if (e.type.air) drawEnemy(g, e, time)
        for (r in w.rings) {
            val k = r.life / r.maxLife
            g.strokeCircle(r.x, r.y, max(0.02f, r.maxR * (1f - k)), Colors.alpha(r.color, k), 0.06f)
        }
        for (e in w.enemies) drawHealth(g, e)
    }

    // --- terrain --------------------------------------------------------------------------

    /** Everything on the map that never moves: grass, water, roads, bridges, trees and rocks. */
    fun drawTerrain(g: Gfx, map: GameMap, detail: Boolean) {
        drawGround(g, map, detail)
        drawRoads(g, map, detail)
        drawDecor(g, map)
    }

    private fun drawWaves(g: Gfx, map: GameMap, time: Float) {
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (map.terrain(c, r) != '~') continue
            val h = hash(c, r)
            for (k in 0 until 2) {
                val off = ((h ushr (k * 5)) and 31) / 31f
                val x = c + ((off + time * 0.12f) % 1f) * 0.75f
                val y = r + 0.3f + k * 0.42f
                g.line(x, y, x + 0.22f, y, WATER_LIGHT, 0.04f)
            }
        }
    }

    private fun drawGround(g: Gfx, map: GameMap, detail: Boolean) {
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val t = map.terrain(c, r)
            if (t == '~' || t == '=') {
                g.fillRect(c.toFloat(), r.toFloat(), 1.02f, 1.02f, WATER)
            } else {
                g.fillRect(c.toFloat(), r.toFloat(), 1.02f, 1.02f, if ((r + c) % 2 == 0) GRASS_A else GRASS_B)
                if (detail && t != '#' && t != 'B') {
                    val h = hash(c, r)
                    for (k in 0 until 3) {
                        val x = c + 0.15f + ((h ushr (k * 6)) and 63) / 63f * 0.7f
                        val y = r + 0.15f + ((h ushr (k * 6 + 3)) and 63) / 63f * 0.7f
                        g.line(x, y, x - 0.03f, y - 0.1f, TUFT, 0.035f)
                        g.line(x, y, x + 0.04f, y - 0.09f, TUFT, 0.035f)
                    }
                }
            }
        }
    }

    private fun drawRoads(g: Gfx, map: GameMap, detail: Boolean) {
        for (p in map.paths) g.polyline(p.xs, p.ys, p.size, ROAD_EDGE, 0.94f)
        for (p in map.paths) g.polyline(p.xs, p.ys, p.size, ROAD, 0.78f)
        if (detail) {
            for ((pi, p) in map.paths.withIndex()) {
                var d = 0.3f
                var i = 0
                while (d < p.length) {
                    p.pointAt(d, tmp)
                    val h = hash(i, pi + 7)
                    val side = ((h and 15) / 15f - 0.5f) * 0.5f
                    g.fillCircle(tmp[0] - tmp[3] * side, tmp[1] + tmp[2] * side, 0.035f + (h ushr 4 and 3) * 0.008f, ROAD_DOT)
                    d += 0.45f
                    i++
                }
            }
        }
        // Bridges over water.
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
        }
    }

    private fun drawDecor(g: Gfx, map: GameMap) {
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val cx = c + 0.5f
            val cy = r + 0.5f
            when (map.terrain(c, r)) {
                'T' -> {
                    g.fillCircle(cx + 0.07f, cy + 0.1f, 0.42f, SHADOW)
                    g.fillCircle(cx, cy, 0.42f, TREE)
                    g.fillCircle(cx - 0.16f, cy + 0.12f, 0.2f, TREE_DARK)
                    g.fillCircle(cx - 0.1f, cy - 0.12f, 0.2f, TREE_LIGHT)
                    g.fillCircle(cx + 0.14f, cy - 0.02f, 0.13f, TREE_LIGHT)
                }
                'R' -> {
                    val h = hash(c, r)
                    for (k in 0 until 7) {
                        val a = k / 7f * 6.283f
                        val rr = 0.3f + ((h ushr (k * 3)) and 7) / 7f * 0.1f
                        xs[k] = cx + cos(a) * rr
                        ys[k] = cy + sin(a) * rr * 0.85f
                    }
                    for (k in 0 until 7) {
                        xs[k] += 0.05f
                        ys[k] += 0.07f
                    }
                    g.fillPolygon(xs, ys, 7, SHADOW)
                    for (k in 0 until 7) {
                        xs[k] -= 0.05f
                        ys[k] -= 0.07f
                    }
                    g.fillPolygon(xs, ys, 7, ROCK)
                    g.fillCircle(cx - 0.08f, cy - 0.08f, 0.13f, ROCK_LIGHT)
                }
            }
        }
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
        g.fillRoundRect(x - 0.47f + 0.05f, y - 0.47f + 0.07f, 0.94f, 0.94f, 0.12f, SHADOW)
        g.fillRoundRect(x - 0.47f, y - 0.47f, 0.94f, 0.94f, 0.12f, 0xFF4A4740.toInt())
        // Plank walls with a corrugated roof.
        g.fillRoundRect(x - 0.38f, y - 0.38f, 0.76f, 0.76f, 0.06f, 0xFF8A6A44.toInt())
        for (k in 0 until 5) {
            val xx = x - 0.3f + k * 0.15f
            g.line(xx, y - 0.34f, xx, y + 0.34f, 0xFF6E5233.toInt(), 0.03f)
        }
        g.fillRoundRect(x - 0.24f, y - 0.24f, 0.48f, 0.48f, 0.04f, 0xFF7C8186.toInt())
        g.line(x - 0.24f, y, x + 0.24f, y, 0xFF5E6368.toInt(), 0.04f)
        g.strokeRoundRect(x - 0.38f, y - 0.38f, 0.76f, 0.76f, 0.06f, 0xFF4E3A24.toInt(), 0.05f)
        // Flag.
        val px = x + 0.3f
        val py = y - 0.3f
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
        if (flash > 0f) g.fillRoundRect(x - 0.47f, y - 0.47f, 0.94f, 0.94f, 0.12f, Colors.alpha(RED, flash * 1.4f))
    }

    // --- towers ---------------------------------------------------------------------------

    fun drawTower(g: Gfx, type: TowerType, level: Int, x: Float, y: Float, angle: Float, flash: Float, burning: Float, time: Float) {
        g.fillRoundRect(x - 0.42f + 0.04f, y - 0.42f + 0.07f, 0.84f, 0.84f, 0.16f, SHADOW)
        g.fillRoundRect(x - 0.42f, y - 0.42f, 0.84f, 0.84f, 0.16f, PLATE)
        g.strokeRoundRect(x - 0.42f, y - 0.42f, 0.84f, 0.84f, 0.16f, PLATE_EDGE, 0.04f)
        for (k in 0 until 4) {
            val rx = if (k % 2 == 0) -0.3f else 0.3f
            val ry = if (k < 2) -0.3f else 0.3f
            g.fillCircle(x + rx, y + ry, 0.03f, RIVET)
        }
        for (i in 0..level) g.fillCircle(x + (i - level / 2f) * 0.13f, y + 0.33f, 0.045f, PIP)

        g.save()
        g.translate(x, y)
        if (type != TowerType.TESLA) g.rotate(angle)
        g.scale(1f + 0.07f * level)
        val c = type.color
        when (type) {
            TowerType.TURRET -> {
                g.fillCircle(0f, 0f, 0.27f, Colors.darker(c, 0.45f))
                if (level == 0) {
                    g.fillRoundRect(0.05f, -0.05f, 0.38f, 0.1f, 0.03f, GUN)
                } else {
                    val len = if (level == 2) 0.45f else 0.4f
                    g.fillRoundRect(0.05f, -0.12f, len, 0.085f, 0.03f, GUN)
                    g.fillRoundRect(0.05f, 0.035f, len, 0.085f, 0.03f, GUN)
                    if (level == 2) g.fillRect(0.42f, -0.14f, 0.07f, 0.28f, GUN)
                }
                g.fillCircle(0f, 0f, 0.2f, c)
                g.fillCircle(-0.05f, -0.05f, 0.1f, Colors.lighter(c, 0.3f))
                g.fillCircle(0.09f, 0f, 0.04f, GUN)
                if (flash > 0f) {
                    g.glow(0.55f, 0f, 0.3f, Colors.alpha(MUZZLE, 0.9f))
                    g.fillCircle(0.5f, 0f, 0.07f, 0xFFFFFBE0.toInt())
                }
            }
            TowerType.SHOTGUN -> {
                g.fillRoundRect(-0.3f, -0.08f, 0.14f, 0.16f, 0.04f, Colors.darker(c, 0.4f))
                g.fillRoundRect(0f, -0.13f, 0.38f, 0.11f, 0.04f, GUN)
                g.fillRoundRect(0f, 0.02f, 0.38f, 0.11f, 0.04f, GUN)
                g.fillRoundRect(-0.2f, -0.19f, 0.32f, 0.38f, 0.08f, c)
                g.fillRoundRect(-0.15f, -0.14f, 0.12f, 0.12f, 0.03f, Colors.lighter(c, 0.3f))
                if (flash > 0f) g.glow(0.5f, 0f, 0.38f, Colors.alpha(MUZZLE, 0.9f))
            }
            TowerType.FLAMER -> {
                g.fillCircle(-0.1f, 0f, 0.2f, Colors.darker(c, 0.35f))
                xs[0] = 0.02f; ys[0] = -0.08f
                xs[1] = 0.38f; ys[1] = -0.05f
                xs[2] = 0.38f; ys[2] = 0.05f
                xs[3] = 0.02f; ys[3] = 0.08f
                g.fillPolygon(xs, ys, 4, GUN)
                g.fillCircle(-0.1f, 0f, 0.15f, c)
                g.fillCircle(-0.14f, -0.05f, 0.06f, Colors.lighter(c, 0.4f))
                val flicker = 0.07f + 0.02f * sin(time * 30f)
                g.glow(0.42f, 0f, if (burning > 0f) 0.4f else flicker * 2f, Colors.alpha(FIRE, 0.95f))
                g.fillCircle(0.41f, 0f, flicker * 0.6f, 0xFFFFE6A0.toInt())
            }
            TowerType.ROCKET -> {
                g.fillRoundRect(-0.23f, -0.23f, 0.46f, 0.46f, 0.07f, c)
                val tubes = if (level >= 2) 3 else 2
                for (k in 0 until tubes) {
                    val ty = (k - (tubes - 1) / 2f) * 0.13f
                    g.fillRoundRect(-0.08f, ty - 0.05f, 0.44f, 0.1f, 0.03f, GUN)
                    g.fillCircle(0.36f, ty, 0.035f, if (flash > 0f) MUZZLE else 0xFFB23A2E.toInt())
                }
                g.fillRoundRect(-0.2f, -0.2f, 0.12f, 0.4f, 0.03f, Colors.darker(c, 0.3f))
            }
            TowerType.SAM -> {
                g.fillRoundRect(-0.26f, -0.24f, 0.5f, 0.48f, 0.07f, c)
                g.fillRoundRect(-0.26f, -0.24f, 0.12f, 0.48f, 0.04f, Colors.darker(c, 0.3f))
                for (k in 0 until 4) {
                    val tx = if (k < 2) 0.12f else -0.03f
                    val ty = if (k % 2 == 0) -0.1f else 0.1f
                    g.fillCircle(tx, ty, 0.075f, GUN)
                    g.fillCircle(tx, ty, 0.04f, 0xFFF0F0F0.toInt())
                }
            }
            TowerType.TESLA -> {
                g.fillCircle(0f, 0f, 0.28f, 0xFF30353B.toInt())
                g.strokeCircle(0f, 0f, 0.22f, COPPER, 0.05f)
                g.strokeCircle(0f, 0f, 0.14f, COPPER, 0.05f)
                val pulse = 0.3f + 0.05f * sin(time * 7f)
                g.glow(0f, 0f, pulse + 0.06f * level, Colors.alpha(BOLT, 0.7f))
                g.fillCircle(0f, 0f, 0.09f + 0.015f * level, 0xFFE8FBFF.toInt())
                val a = time * 5f
                for (k in 0 until 2) {
                    val aa = a + k * 3.1f
                    g.line(cos(aa) * 0.12f, sin(aa) * 0.12f, cos(aa + 0.4f) * 0.27f, sin(aa + 0.4f) * 0.27f, Colors.alpha(BOLT, 0.8f), 0.025f)
                }
            }
        }
        g.restore()
    }

    // --- enemies --------------------------------------------------------------------------

    private fun drawShadow(g: Gfx, e: Enemy) {
        val (x, y) = laneOf(e)
        val r = e.type.radius
        g.fillCircle(x + 0.2f, y + 0.35f, r * 0.85f, SHADOW)
    }

    private fun laneOf(e: Enemy): Pair<Float, Float> = (e.x - e.dirY * e.lane) to (e.y + e.dirX * e.lane)

    fun drawEnemy(g: Gfx, e: Enemy, time: Float) {
        val (x, y) = laneOf(e)
        val r = e.type.radius
        if (!e.type.air) g.fillCircle(x + 0.04f, y + 0.07f, r, SHADOW)
        val angle = Math.toDegrees(atan2(e.dirY, e.dirX).toDouble()).toFloat()
        drawEnemyBody(g, e.type, x, y, angle, time + e.lane * 10f)
        if (e.burnTimer > 0f) g.glow(x, y, r * 1.3f, Colors.alpha(FIRE, 0.45f))
        if (e.hitFlash > 0f) g.fillCircle(x, y, r * 0.9f, Colors.alpha(WHITE, 0.55f))
        if (e.slowTimer > 0f) g.strokeCircle(x, y, r + 0.06f, Colors.alpha(BOLT, 0.75f), 0.04f)
        if (e.healPulse > 0f) g.strokeCircle(x, y, r + 0.12f, Colors.alpha(World.HEAL, e.healPulse), 0.04f)
    }

    fun drawEnemyBody(g: Gfx, type: EnemyType, x: Float, y: Float, angle: Float, time: Float) {
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
        if (e.hp >= e.maxHp) return
        val (x, y) = laneOf(e)
        val r = e.type.radius
        val w = if (e.type.boss) 1.3f else max(0.5f, r * 2.2f)
        val h = if (e.type.boss) 0.13f else 0.08f
        val top = y - r - (if (e.type.air) 0.32f else 0.18f)
        val f = (e.hp / e.maxHp).coerceIn(0f, 1f)
        g.fillRect(x - w / 2f - 0.02f, top - 0.02f, w + 0.04f, h + 0.04f, Colors.alpha(0xFF000000.toInt(), 0.7f))
        val color = when {
            f > 0.6f -> 0xFF6BD66B.toInt()
            f > 0.3f -> 0xFFFFD54A.toInt()
            else -> 0xFFFF4B4B.toInt()
        }
        g.fillRect(x - w / 2f, top, w * f, h, color)
    }

    // --- shots ----------------------------------------------------------------------------

    private fun drawProjectile(g: Gfx, p: Projectile) {
        when (p.kind) {
            Shot.BULLET -> {
                g.line(p.x - cos(p.angle) * 0.22f, p.y - sin(p.angle) * 0.22f, p.x, p.y, TRACER, 0.06f)
            }
            Shot.ROCKET, Shot.MISSILE -> {
                g.save()
                g.translate(p.x, p.y)
                g.rotate(Math.toDegrees(p.angle.toDouble()).toFloat())
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
        }
    }

    // --- icons ----------------------------------------------------------------------------

    fun drawTowerIcon(g: Gfx, type: TowerType, level: Int, cx: Float, cy: Float, size: Float, time: Float) {
        g.save()
        g.translate(cx, cy)
        g.scale(size)
        drawTower(g, type, level, 0f, 0f, -45f, 0f, 0f, time)
        g.restore()
    }

    /** Draws [type] so that it fits a circle [size] across, whatever its real size. */
    fun drawEnemyIcon(g: Gfx, type: EnemyType, cx: Float, cy: Float, size: Float, time: Float) {
        val extent = when (type) {
            EnemyType.APC -> 0.62f
            EnemyType.HELI -> 0.85f
            EnemyType.DRONE -> type.radius * 1.42f
            else -> type.radius
        }
        g.save()
        g.translate(cx, cy)
        g.scale(size / (2f * extent))
        drawEnemyBody(g, type, 0f, 0f, -90f, time)
        g.restore()
    }

    fun drawMapThumb(g: Gfx, map: GameMap, x: Float, y: Float, w: Float, h: Float) {
        val cell = min(w / COLS, h / ROWS)
        g.save()
        g.translate(x + (w - cell * COLS) / 2f, y + (h - cell * ROWS) / 2f)
        g.scale(cell)
        g.cached("thumb-${map.level.id}", COLS.toFloat(), ROWS.toFloat(), cell) {
            drawTerrain(it, map, detail = false)
            drawBase(it, map, 0f, 0f)
        }
        g.restore()
    }

    private fun hash(a: Int, b: Int): Int {
        var h = a * 73_856_093 xor b * 19_349_663
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        return h xor (h ushr 15)
    }

    companion object {
        val GRASS_A = 0xFF3F6B36.toInt()
        val GRASS_B = 0xFF3B6533.toInt()
        val TUFT = 0xFF538745.toInt()
        val ROAD_EDGE = 0xFF6B5638.toInt()
        val ROAD = 0xFFA58A62.toInt()
        val ROAD_DOT = 0xFF8D7451.toInt()
        val WATER = 0xFF1F5C80.toInt()
        val WATER_LIGHT = 0xFF4A8DB5.toInt()
        val TREE = 0xFF285A2C.toInt()
        val TREE_DARK = 0xFF1E4722.toInt()
        val TREE_LIGHT = 0xFF3D7A3A.toInt()
        val ROCK = 0xFF7A7F84.toInt()
        val ROCK_LIGHT = 0xFF999EA3.toInt()
        val BRIDGE = 0xFF8B5E34.toInt()
        val BRIDGE_GAP = 0xFF5A3B20.toInt()
        val BRIDGE_RAIL = 0xFF4A2F18.toInt()
        val SHADOW = 0x55000000
        val PLATE = 0xFF3B4148.toInt()
        val PLATE_EDGE = 0xFF262B30.toInt()
        val RIVET = 0xFF5A626B.toInt()
        val PIP = 0xFFFFD54A.toInt()
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
    }
}
