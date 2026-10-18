package com.makc.basedefense

import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.makc.basedefense.engine.Payment
import com.makc.basedefense.engine.Vip
import com.makc.basedefense.engine.VipShop
import ru.rustore.sdk.pay.RuStorePayClient
import ru.rustore.sdk.pay.model.PreferredPurchaseType
import ru.rustore.sdk.pay.model.ProductId
import ru.rustore.sdk.pay.model.ProductPurchaseParams
import ru.rustore.sdk.pay.model.ProductType
import ru.rustore.sdk.pay.model.RuStorePaymentException
import ru.rustore.sdk.pay.model.SdkTheme
import ru.rustore.sdk.pay.model.SubscriptionPurchase
import ru.rustore.sdk.pay.model.SubscriptionPurchaseStatus
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/** The RuStore build: VIP is the monthly subscription [Vip.PRODUCT], paid through RuStore's Pay SDK. */
object VipStores {
    fun create(): VipStore = RuStoreVip()
}

private class RuStoreVip : VipStore {
    private val main = Handler(Looper.getMainLooper())
    private val client: RuStorePayClient get() = RuStorePayClient.instance

    override val shop = VipShop.HERE

    override fun buy(done: (Payment, Int) -> Unit) {
        try {
            client.getPurchaseInteractor()
                .purchase(ProductPurchaseParams(ProductId(Vip.PRODUCT)), PreferredPurchaseType.ONE_STEP, SdkTheme.DARK)
                .addOnSuccessListener {
                    // The purchase result has no end date; the subscription itself does.
                    check { until -> done(Payment.PAID, until ?: today() + Vip.DAYS) }
                }
                .addOnFailureListener { e ->
                    main.post { done(if (e is RuStorePaymentException.ProductPurchaseCancelled) Payment.CANCELLED else Payment.FAILED, 0) }
                }
        } catch (e: RuntimeException) {
            main.post { done(Payment.FAILED, 0) }
        }
    }

    override fun check(done: (Int?) -> Unit) {
        try {
            client.getPurchaseInteractor()
                .getPurchases(ProductType.SUBSCRIPTION)
                .addOnSuccessListener { purchases ->
                    val until = purchases.filterIsInstance<SubscriptionPurchase>()
                        .filter { it.productId.value == Vip.PRODUCT && it.status == SubscriptionPurchaseStatus.ACTIVE }
                        .maxOfOrNull { lastDay(it.expirationDate) }
                    main.post { done(until) }
                }
                .addOnFailureListener { main.post { done(null) } }
        } catch (e: RuntimeException) {
            main.post { done(null) }
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
