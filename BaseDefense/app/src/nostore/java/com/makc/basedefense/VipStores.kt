package com.makc.basedefense

import com.makc.basedefense.engine.VipShop

/** Builds without RuStore's Pay SDK: VIP is shown or hidden, never sold here. */
object VipStores {
    fun create(): VipStore = object : VipStore {
        override val shop = VipShop.valueOf(BuildConfig.VIP_SHOP)
    }
}
