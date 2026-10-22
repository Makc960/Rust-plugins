package com.makc.basedefense.engine

import java.security.MessageDigest

/**
 * A promo code: what it gives, and the last day it works (days since 1970). The admin's opens
 * everything for as long as admin mode stays on; it can be entered again on any phone.
 */
class PromoCode(val hash: String, val rewards: List<Reward> = emptyList(), val until: Int = Int.MAX_VALUE, val admin: Boolean = false)

enum class PromoResult { DONE, ADMIN, USED, EXPIRED, UNKNOWN }

/**
 * Promo codes the game knows. Only each code's hash is kept, so the codes cannot be read
 * out of the app; a new code's hash is `sha256("basedefense:" + CODE)`, where CODE is the
 * code in capitals with only its letters and digits.
 */
object Promo {
    private const val SALT = "basedefense:"

    val CODES = listOf(
        // The admin's: everything open. The code itself is kept by the developer, not here.
        PromoCode("110ae8d410ef24a928855ab817678dccac02710740511ac5418b69142f6b30d3", admin = true),
    )

    /** Upgrade points the admin has besides his own: enough for every upgrade at its top rank. */
    val ADMIN_POINTS: Int get() = Perk.entries.sumOf { it.maxRank * it.cost }

    /** Capitals, letters and digits only: " vulkan-3 " and "VULKAN3" are the same code. */
    fun normalize(text: String): String = text.uppercase().filter { it.isLetterOrDigit() }

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest((SALT + normalize(text)).toByteArray()).joinToString("") { "%02x".format(it) }

    /** The code [text] stands for among [codes], or null. */
    fun find(text: String, codes: List<PromoCode> = CODES): PromoCode? {
        if (normalize(text).isEmpty()) return null
        val h = hash(text)
        return codes.firstOrNull { it.hash == h }
    }
}
