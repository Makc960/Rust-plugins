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

    /** Every tower is a squad in a pit: its soldier, and the special one a specialisation adds. */
    private val towerTroopers = TowerType.entries.map { need("trooper." + it.name.lowercase()) }
    private val branchTroopers = Branch.entries.map { need("trooper." + it.name.lowercase().replace("_", "")) }
    val pit = Array(biomes.size) { need("pit.${biomes[it]}") }
    val pitElite = Array(biomes.size) { need("pit.${biomes[it]}.elite") }
    val dish = need("prop.dish")
    val table = need("prop.table")
    val jamDevice = need("prop.jammer")
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
    private val commanders = CommanderType.entries.map { need("commander." + it.name.lowercase()) }
    val sentry = need("sentry")

    fun trooper(type: TowerType): Sprite = towerTroopers[type.ordinal]

    fun commander(type: CommanderType): Sprite = commanders[type.ordinal]

    fun trooper(branch: Branch): Sprite = branchTroopers[branch.ordinal]

    private class Missing(name: String) : Exception(name)

    companion object {
        fun load(): Art? = try {
            if (Sprites.count == 0) null else Art()
        } catch (e: Missing) {
            null
        }
    }
}
