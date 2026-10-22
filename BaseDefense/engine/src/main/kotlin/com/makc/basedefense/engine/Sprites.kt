package com.makc.basedefense.engine

/**
 * One picture in the sprite atlas, by its place in atlas pixels, with the box
 * of the part that shows ([vx], [vy], [vw], [vh] inside it).
 */
class Sprite(val name: String, val x: Int, val y: Int, val w: Int, val h: Int, vx: Int, vy: Int, vw: Int, vh: Int) {
    /** Its own size in map cells. */
    val cellsW: Float = w / Sprites.PX
    val cellsH: Float = h / Sprites.PX

    /** The part that shows: its size in cells and how far its middle is from the picture's. */
    val shownW: Float = vw / Sprites.PX
    val shownH: Float = vh / Sprites.PX
    val shownDx: Float = (vx + vw / 2f - w / 2f) / Sprites.PX
    val shownDy: Float = (vy + vh / 2f - h / 2f) / Sprites.PX
}

/**
 * The game's pictures: one image, [IMAGE], and its index, [INDEX], both made
 * from Kenney's CC0 packs by art/build_atlas.py. A [Gfx] that can draw them
 * says so with [Gfx.sprites]; without them the renderer paints shapes.
 */
object Sprites {
    const val PX = 128f
    const val IMAGE = "/sprites/atlas.png"
    const val INDEX = "/sprites/atlas.txt"

    private val all: Map<String, Sprite> by lazy { load() }

    operator fun get(name: String): Sprite? = all[name]

    val count: Int get() = all.size

    private fun load(): Map<String, Sprite> {
        val text = Sprites::class.java.getResourceAsStream(INDEX)?.use { it.readBytes().decodeToString() } ?: return emptyMap()
        val out = HashMap<String, Sprite>()
        for (line in text.lineSequence()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val p = line.trim().split(' ')
            if (p.size != 9) continue
            val n = IntArray(8) { p[it + 1].toInt() }
            out[p[0]] = Sprite(p[0], n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7])
        }
        return out
    }
}
