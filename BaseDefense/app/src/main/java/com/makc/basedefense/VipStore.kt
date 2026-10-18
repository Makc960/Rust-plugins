package com.makc.basedefense

import android.content.Intent
import com.makc.basedefense.engine.Payment
import com.makc.basedefense.engine.VipShop

/**
 * Where this build sells VIP. RuStore builds do it through the Pay SDK
 * (src/rustore); the others only tell where to get it (src/nostore).
 */
interface VipStore {
    val shop: VipShop

    /** [done] gets how the payment ended and the last paid day (days since 1970, 0 if unknown). */
    fun buy(done: (Payment, Int) -> Unit) = done(Payment.FAILED, 0)

    /** [done] gets the last day an active subscription is paid for, or null. */
    fun check(done: (Int?) -> Unit) = done(null)

    /** A payment app returning to the game, or the game opened by a link. */
    fun handleIntent(intent: Intent) = Unit
}
