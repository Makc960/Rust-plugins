package com.makc.basedefense

import com.makc.basedefense.engine.Shop

/** Builds without RuStore's Pay SDK: the shop is shown or hidden, never sold from here. */
object Stores {
    fun create(): Store = object : Store {
        override val shop = Shop.valueOf(BuildConfig.SHOP)
    }
}
