package com.makc.basedefense

import android.content.Intent
import com.makc.basedefense.engine.Payment
import com.makc.basedefense.engine.Product
import com.makc.basedefense.engine.Purchases
import com.makc.basedefense.engine.Shop

/**
 * Where this build sells VIP and the rest of the shop. RuStore builds do it
 * through the Pay SDK (src/rustore); the others only tell where to buy (src/nostore).
 * Built with a shop server, any of them sells through it instead ([ShopServer]).
 */
interface Store {
    val shop: Shop

    /** [done] gets how the payment ended and, for the subscription, the last paid day (days since 1970, 0 if unknown). */
    fun buy(product: Product, done: (Payment, Int) -> Unit) = done(Payment.FAILED, 0)

    /** [done] gets what is paid for, or null when the store cannot tell. */
    fun check(done: (Purchases?) -> Unit) = done(null)

    /** The code purchases are kept under, where the store keeps them by code; see [com.makc.basedefense.engine.Platform.purchaseCode]. */
    val code: String? get() = null

    /** Makes [code] this phone's purchase code; [done] gets what is bought under it, or null. */
    fun useCode(code: String, done: (Purchases?) -> Unit) = done(null)

    /** A payment app returning to the game, or the game opened by a link. */
    fun handleIntent(intent: Intent) = Unit

    /** The game is in front again, maybe back from paying in the browser. */
    fun resume() = Unit

    /** Opens the store's own window for rating the game, where it has one. */
    fun askReview() = Unit

    /** [done] hears whether the store has a newer version of the game. */
    fun checkUpdate(done: (Boolean) -> Unit) = done(false)

    /** Installs the newer version found by [checkUpdate]. */
    fun startUpdate() = Unit
}
