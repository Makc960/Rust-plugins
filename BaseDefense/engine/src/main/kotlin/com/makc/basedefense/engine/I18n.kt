package com.makc.basedefense.engine

import java.util.Locale

enum class Language { RU, EN }

object I18n {
    var lang = Language.RU
}

/** A piece of text in both languages; [t] picks the current one. */
class L(val ru: String, val en: String) {
    val t: String get() = if (I18n.lang == Language.EN) en else ru

    /** [t] with `%s`/`%d` filled in. */
    fun f(vararg args: Any): String = String.format(Locale.ROOT, t, *args)

    override fun toString(): String = t
}

/** Numbers the way the current language writes them: 2,5 in Russian, 2.5 in English. */
object Num {
    fun one(v: Float): String {
        val t = Math.round(v * 10f)
        val whole = t / 10
        val frac = Math.abs(t % 10)
        if (frac == 0) return "$whole"
        val sep = if (I18n.lang == Language.EN) "." else ","
        val sign = if (v < 0f && whole == 0) "-" else ""
        return "$sign$whole$sep$frac"
    }

    fun int(v: Float): String = Math.round(v).toString()

    fun percent(v: Float): String = "${Math.round(v * 100f)}%"
}

/** The Russian noun that goes with [n]: [one] for 1 and 21, [few] for 2 to 4, [many] for the rest and for 11 to 14. */
fun ruPlural(n: Int, one: String, few: String, many: String): String {
    val tens = n % 100
    val units = n % 10
    return when {
        tens in 11..14 -> many
        units == 1 -> one
        units in 2..4 -> few
        else -> many
    }
}
