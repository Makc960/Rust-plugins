package com.makc.basedefense.engine

/**
 * A camouflage set: the colour of the squads' sandbags and of a commander's uniform.
 * Only looks; it changes nothing in a battle.
 */
enum class Camo(val title: L) {
    FIELD(L("Полевая", "Field")),

    /** Earned by winning every level of the desert. */
    DESERT(L("Пустынная", "Desert")),

    /** Earned by winning every level of the north. */
    WINTER(L("Зимняя", "Winter")),

    /** Bought: [Product.SKIN_NIGHT]. */
    NIGHT(L("Ночная", "Night")),

    /** A reward of the season pass. */
    URBAN(L("Городская", "Urban")),

    /** Bought ([Product.SKIN_PARADE]) or lent by VIP; a commander of the top rank wears his own. */
    PARADE(L("Парадная", "Parade")),
    ;

    /** The sprite name's ending; the field set has none. */
    val suffix: String get() = if (this == FIELD) "" else "." + name.lowercase()

    val key: String get() = "camo_" + name.lowercase()

    /** The campaign world whose every level opens it, if any. */
    val world: Int?
        get() = when (this) {
            DESERT -> 1
            WINTER -> 2
            else -> null
        }

    val product: Product?
        get() = when (this) {
            NIGHT -> Product.SKIN_NIGHT
            PARADE -> Product.SKIN_PARADE
            else -> null
        }
}
