package com.makc.basedefense

import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.makc.basedefense.engine.Payment
import com.makc.basedefense.engine.Product
import com.makc.basedefense.engine.Purchases
import com.makc.basedefense.engine.Shop
import com.makc.basedefense.engine.Vip
import ru.rustore.sdk.pay.RuStorePayClient
import ru.rustore.sdk.pay.model.PreferredPurchaseType
import ru.rustore.sdk.pay.model.ProductId
import ru.rustore.sdk.pay.model.ProductPurchase
import ru.rustore.sdk.pay.model.ProductPurchaseParams
import ru.rustore.sdk.pay.model.ProductPurchaseStatus
import ru.rustore.sdk.pay.model.ProductType
import ru.rustore.sdk.pay.model.RuStorePaymentException
import ru.rustore.sdk.pay.model.SdkTheme
import ru.rustore.sdk.pay.model.SubscriptionPurchase
import ru.rustore.sdk.pay.model.SubscriptionPurchaseStatus
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/** The RuStore build: VIP by the month or for good, commanders and the recruit pack, paid through RuStore's Pay SDK. */
object Stores {
    fun create(): Store = RuStore()
}

private class RuStore : Store {
    private val main = Handler(Looper.getMainLooper())
    private val client: RuStorePayClient get() = RuStorePayClient.instance

    override val shop = Shop.HERE

    override fun buy(product: Product, done: (Payment, Int) -> Unit) {
        try {
            client.getPurchaseInteractor()
                .purchase(ProductPurchaseParams(ProductId(product.id)), PreferredPurchaseType.ONE_STEP, SdkTheme.DARK)
                .addOnSuccessListener {
                    if (product.subscription) {
                        // The purchase result has no end date; the subscription itself does.
                        check { found -> done(Payment.PAID, found?.vipUntil ?: (today() + Vip.DAYS)) }
                    } else {
                        main.post { done(Payment.PAID, 0) }
                    }
                }
                .addOnFailureListener { e ->
                    main.post { done(if (e is RuStorePaymentException.ProductPurchaseCancelled) Payment.CANCELLED else Payment.FAILED, 0) }
                }
        } catch (e: RuntimeException) {
            main.post { done(Payment.FAILED, 0) }
        }
    }

    /** Asks for the subscription, then for what was bought once, and answers with both. */
    override fun check(done: (Purchases?) -> Unit) {
        try {
            client.getPurchaseInteractor()
                .getPurchases(ProductType.SUBSCRIPTION)
                .addOnSuccessListener { subscriptions ->
                    val until = subscriptions.filterIsInstance<SubscriptionPurchase>()
                        .filter { it.productId.value == Product.VIP_MONTH.id && it.status == SubscriptionPurchaseStatus.ACTIVE }
                        .maxOfOrNull { lastDay(it.expirationDate) }
                    owned { bought -> main.post { done(if (bought == null) null else Purchases(until, bought)) } }
                }
                .addOnFailureListener { main.post { done(null) } }
        } catch (e: RuntimeException) {
            main.post { done(null) }
        }
    }

    private fun owned(done: (Set<Product>?) -> Unit) {
        try {
            client.getPurchaseInteractor()
                .getPurchases(ProductType.NON_CONSUMABLE_PRODUCT)
                .addOnSuccessListener { purchases ->
                    done(
                        purchases.filterIsInstance<ProductPurchase>()
                            .filter { it.status == ProductPurchaseStatus.CONFIRMED }
                            .mapNotNull { Product.byId(it.productId.value) }
                            .toSet(),
                    )
                }
                .addOnFailureListener { done(null) }
        } catch (e: RuntimeException) {
            done(null)
        }
    }

    override fun handleIntent(intent: Intent) {
        try {
            client.getIntentInteractor().proceedIntent(intent, SdkTheme.DARK)
        } catch (e: RuntimeException) {
            // Not a payment's return: nothing to do.
        }
    }

    private fun today(): Int = LocalDate.now().toEpochDay().toInt()

    /** The day [date] falls on here, or a month from now when the store does not say. */
    private fun lastDay(date: Date?): Int =
        date?.toInstant()?.atZone(ZoneId.systemDefault())?.toLocalDate()?.toEpochDay()?.toInt() ?: (today() + Vip.DAYS)
}
