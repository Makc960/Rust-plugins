package com.makc.basedefense

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.common.InitializationListener
import com.yandex.mobile.ads.common.YandexAds
import com.yandex.mobile.ads.rewarded.Reward
import com.yandex.mobile.ads.rewarded.RewardedAd
import com.yandex.mobile.ads.rewarded.RewardedAdEventListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoadListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoader
import kotlin.math.min

/** Rewarded videos from the Yandex Advertising Network, when the build has an ad unit for them. */
object Advertising {
    fun create(activity: Activity): Adverts {
        val unit = BuildConfig.REWARDED_AD_UNIT
        return if (unit.isEmpty()) object : Adverts {} else YandexAdverts(activity, unit)
    }
}

/** Keeps one video loaded ahead, so the game can offer it the moment it is needed. */
private class YandexAdverts(private val activity: Activity, private val unit: String) : Adverts {
    private val main = Handler(Looper.getMainLooper())
    private val loader by lazy { RewardedAdLoader(activity.applicationContext) }
    private var ad: RewardedAd? = null
    private var loading = false
    private var wait = FIRST_RETRY_MS

    override val enabled = true
    override val ready: Boolean get() = ad != null

    init {
        YandexAds.initialize(
            activity.applicationContext,
            object : InitializationListener {
                override fun onInitializationCompleted() {
                    main.post { load() }
                }
            },
        )
    }

    private fun load() {
        if (loading || ad != null) return
        loading = true
        loader.loadAd(
            AdRequest.Builder(unit).build(),
            object : RewardedAdLoadListener {
                override fun onAdLoaded(rewarded: RewardedAd) {
                    loading = false
                    ad = rewarded
                    wait = FIRST_RETRY_MS
                }

                override fun onAdFailedToLoad(error: AdRequestError) {
                    loading = false
                    // Nothing to show now (no network, no video): try again later, waiting longer each time.
                    main.postDelayed({ load() }, wait)
                    wait = min(wait * 2, LAST_RETRY_MS)
                }
            },
        )
    }

    override fun show(done: (Boolean) -> Unit) {
        val shown = ad ?: return done(false)
        ad = null
        var rewarded = false
        var over = false
        shown.setAdEventListener(
            object : RewardedAdEventListener {
                override fun onAdShown() = Unit

                override fun onAdClicked() = Unit

                override fun onAdImpression(impressionData: ImpressionData?) = Unit

                override fun onRewarded(reward: Reward) {
                    rewarded = true
                }

                override fun onAdFailedToShow(adError: AdError) = finish()

                override fun onAdDismissed() = finish()

                private fun finish() {
                    if (over) return
                    over = true
                    shown.setAdEventListener(null)
                    // The reward may be reported just after the video closes: answer a moment later.
                    main.post {
                        done(rewarded)
                        load()
                    }
                }
            },
        )
        shown.show(activity)
    }

    private companion object {
        const val FIRST_RETRY_MS = 15_000L
        const val LAST_RETRY_MS = 300_000L
    }
}
