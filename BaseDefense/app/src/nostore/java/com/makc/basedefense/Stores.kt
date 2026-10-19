package com.makc.basedefense

import android.content.Context
import com.makc.basedefense.engine.Shop

/** Builds without RuStore's Pay SDK: the shop is shown or hidden, never sold from here. */
object Stores {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): Store = object : Store {
        override val shop = Shop.valueOf(BuildConfig.SHOP)
    }
}
