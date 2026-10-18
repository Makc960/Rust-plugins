package com.makc.basedefense.engine

/**
 * The atlas pictures the renderer draws, looked up by name once. [load] gives
 * null when the atlas is missing or lacks any of them; the game then paints shapes.
 */
internal class Art private constructor() {
    private val biomes = Biome.entries.map { it.name.lowercase() }

    private fun need(name: String): Sprite = Sprites[name] ?: throw Missing(name)

    private fun list(prefix: String, n: Int) = Array(n) { need("$prefix.$it") }

    val ground = Array(biomes.size) { list("ground.${biomes[it]}", 2) }

    /** Road pieces by biome and by the sides they join: north 1, east 2, south 4, west 8. */
    val road = Array(biomes.size) { b -> Array(16) { m -> if (m == 0) null else need("road.${biomes[b]}.$m") } }
    val trees = arrayOf(list("tree.green", 3), list("tree.desert", 2), list("tree.winter", 2))
    val rocks = Array(biomes.size) { list("rock.${biomes[it]}", 2) }
    val cactus = need("cactus")
    val base = need("base")
    val scorch = need("scorch")
    val oil = need("oil")
    val fire = list("fire", 3)
    val blast = list("blast", 5)
    val smoke = list("smoke", 5)
    val flash = need("flash")
    val flashBig = need("flash.big")
    val plate = need("plate")
    val plateElite = need("plate.elite")
    private val towerHeads = TowerType.entries.map { headName(it)?.let(::need) }
    private val branchHeads = Branch.entries.map { headName(it)?.let(::need) }
    val mine = need("mine")
    val mineBig = need("mine.big")
    val spikes = need("spikes")
    val quarry = need("quarry")
    val drill = need("quarry.drill")
    val bit = need("quarry.bit")
    val bank = need("quarry.bank")
    val dial = need("quarry.dial")
    val enemies = EnemyType.entries.map { need("enemy." + it.name.lowercase()) }
    val droneShadow = need("shadow.drone")
    val plane = need("plane")
    val planeShadow = need("shadow.plane")
    val rocket = need("rocket")
    val rocketBig = need("rocket.big")

    /** The turning top of a tower; null for mines and quarries, which are drawn whole. */
    fun head(type: TowerType, branch: Branch?): Sprite? =
        if (branch != null) branchHeads[branch.ordinal] else towerHeads[type.ordinal]

    private class Missing(name: String) : Exception(name)

    companion object {
        fun load(): Art? = try {
            if (Sprites.count == 0) null else Art()
        } catch (e: Missing) {
            null
        }

        private fun headName(t: TowerType): String? = when (t) {
            TowerType.MINES, TowerType.QUARRY -> null
            else -> "head." + t.name.lowercase()
        }

        private fun headName(b: Branch): String? = when (b) {
            Branch.LANDMINE, Branch.SPIKES, Branch.DRILL, Branch.BANK -> null
            Branch.HEAVY_CAL -> "head.heavycal"
            else -> "head." + b.name.lowercase()
        }
    }
}
