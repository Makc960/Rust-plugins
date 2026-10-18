package com.makc.espcamera

import android.content.Context

enum class Filter(val title: String) {
    ALL("ВСЁ"),
    PEOPLE("ЛЮДИ"),
    VEHICLES("ТЕХНИКА"),
    ANIMALS("ЗВЕРИ"),
    ITEMS("ВЕЩИ");

    fun accepts(kind: Kind): Boolean = when (this) {
        ALL -> true
        PEOPLE -> kind == Kind.PERSON
        VEHICLES -> kind == Kind.VEHICLE
        ANIMALS -> kind == Kind.ANIMAL
        ITEMS -> kind == Kind.ITEM
    }
}

/** How the camera picture itself is shown. */
enum class VisionMode(val title: String) {
    NORMAL("ОБЫЧНЫЙ"),
    THERMAL("ТЕПЛО"),
    NIGHT("НОЧЬ"),
    MATRIX("МАТРИЦА"),
}

/** What happens when the crosshair locks onto a new target. */
enum class Alert(val title: String) {
    OFF("ВЫКЛ"),
    VIBRATE("ВИБРО"),
    SOUND("ВИБРО+ЗВУК"),
}

inline fun <reified E : Enum<E>> E.next(): E {
    val all = enumValues<E>()
    return all[(ordinal + 1) % all.size]
}

/** Every switch in the app, kept between launches. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("esp", Context.MODE_PRIVATE)

    var filter = enumOr("filter", Filter.ALL)
    var vision = enumOr("vision", VisionMode.NORMAL)
    var alert = enumOr("alert", Alert.VIBRATE)
    var cornerBoxes = prefs.getBoolean("corners", true)
    var snaplines = prefs.getBoolean("lines", true)
    var skeleton = prefs.getBoolean("skeleton", true)
    var trails = prefs.getBoolean("trails", false)
    var radar = prefs.getBoolean("radar", true)
    var markers = prefs.getBoolean("markers", true)
    var accurate = prefs.getBoolean("accurate", false)
    var gpu = prefs.getBoolean("gpu", false)

    fun save() {
        prefs.edit()
            .putString("filter", filter.name)
            .putString("vision", vision.name)
            .putString("alert", alert.name)
            .putBoolean("corners", cornerBoxes)
            .putBoolean("lines", snaplines)
            .putBoolean("skeleton", skeleton)
            .putBoolean("trails", trails)
            .putBoolean("radar", radar)
            .putBoolean("markers", markers)
            .putBoolean("accurate", accurate)
            .putBoolean("gpu", gpu)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(key: String, default: E): E {
        val name = prefs.getString(key, null) ?: return default
        return enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}
