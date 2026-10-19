package com.makc.basedefense

import android.app.Activity

/** Builds outside RuStore show no adverts. */
object Advertising {
    @Suppress("UNUSED_PARAMETER")
    fun create(activity: Activity): Adverts = object : Adverts {}
}
