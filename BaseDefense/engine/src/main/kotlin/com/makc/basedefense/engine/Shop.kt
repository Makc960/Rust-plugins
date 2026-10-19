package com.makc.basedefense.engine

/** Where this build lets players buy VIP and the rest of the shop. */
enum class Shop {
    /** Nowhere, and the shop stays hidden: Google Play allows no outside payments. */
    NONE,

    /** In the game's RuStore version; this build only shows what is on offer. */
    ELSEWHERE,

    /** Right here, through RuStore. */
    HERE,
}

/** How a purchase ended. */
enum class Payment { PAID, CANCELLED, FAILED }

/** Everything on sale, by its product ID in the RuStore Console; all but VIP are bought once and kept. */
enum class Product(val id: String, val rubles: Int, val subscription: Boolean = false) {
    VIP_MONTH("vip_month", 50, subscription = true),
    VIP_FOREVER("vip_forever", 399),

    /** The engineer and [STARTER_POINTS] upgrade points, once. */
    STARTER("starter_pack", 99),
    ENGINEER("commander_engineer", 149),
    GROM("commander_grom", 149),

    /** Camouflage sets: every commander's uniform and the squads' sandbags. */
    SKIN_NIGHT("skin_night", 99),
    SKIN_PARADE("skin_parade", 149),
    ;

    companion object {
        const val STARTER_POINTS = 15

        fun byId(id: String): Product? = entries.firstOrNull { it.id == id }
    }
}

/** What a store says the player has paid for. */
class Purchases(
    /** The last day the VIP subscription is paid for (days since 1970), or null. */
    val vipUntil: Int?,
    val owned: Set<Product>,
)
