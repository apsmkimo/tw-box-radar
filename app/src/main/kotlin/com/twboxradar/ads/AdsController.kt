package com.twboxradar.ads

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.twboxradar.BuildConfig
import com.twboxradar.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 測試廣告、UMP 同意，以及一次買斷移除廣告。
 * 開屏廣告：冷啟動與從背景回來時才顯示，至少間隔 30 分鐘，免責聲明同意前不顯示。
 */
class AdsController(private val app: Application) : DefaultLifecycleObserver, Application.ActivityLifecycleCallbacks {
    private val prefs = Prefs(app)
    private val productId = BuildConfig.REMOVE_ADS_PRODUCT_ID

    private val _owned = MutableStateFlow(prefs.removeAdsOwned)
    private val _debugRemoved = MutableStateFlow(prefs.debugRemoveAds && BuildConfig.DEBUG)
    private val _canRequestAds = MutableStateFlow(false)
    private val _price = MutableStateFlow<String?>(null)
    private val _status = MutableStateFlow(if (prefs.removeAdsOwned) "已購買，廣告已關閉。" else "")
    private val _canBuy = MutableStateFlow(false)

    val owned: StateFlow<Boolean> = _owned.asStateFlow()
    val debugRemoved: StateFlow<Boolean> = _debugRemoved.asStateFlow()
    val canRequestAds: StateFlow<Boolean> = _canRequestAds.asStateFlow()
    val price: StateFlow<String?> = _price.asStateFlow()
    val status: StateFlow<String> = _status.asStateFlow()
    val canBuy: StateFlow<Boolean> = _canBuy.asStateFlow()

    fun adsHidden(): Boolean = _owned.value || (BuildConfig.DEBUG && _debugRemoved.value)

    private var currentActivity: Activity? = null
    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null
    private var mobileAdsStarted = false
    private var consentStarted = false
    private var appOpenAd: AppOpenAd? = null
    private var appOpenLoading = false
    private var appOpenLoadedAt = 0L
    private var appOpenShowing = false
    private var foregroundAt = 0L
    private var shownThisForeground = false
    private var suppressAppOpen = false

    fun install() {
        app.registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    fun attach(activity: Activity) {
        currentActivity = activity
        connectBilling()
        if (consentStarted) return
        consentStarted = true
        requestConsent(activity)
    }

    fun onDisclaimerAccepted() {
        val activity = currentActivity ?: return
        if (_canRequestAds.value) {
            startLoadingAds(activity)
        } else {
            requestConsent(activity)
        }
    }

    fun setDebugRemoved(enabled: Boolean) {
        if (!BuildConfig.DEBUG) return
        prefs.debugRemoveAds = enabled
        _debugRemoved.value = enabled
        if (!adsHidden() && prefs.disclaimerAccepted && _canRequestAds.value) {
            currentActivity?.let { startLoadingAds(it) }
        }
    }

    fun buy(activity: Activity) {
        val details = productDetails
        val client = billingClient
        if (details == null || client == null || !client.isReady) {
            _status.value = _status.value.ifBlank { "目前無法開始購買。" }
            return
        }
        suppressAppOpen = true
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build(),
                ),
            )
            .build()
        client.launchBillingFlow(activity, params)
    }

    fun restore() {
        val client = billingClient
        if (client == null || !client.isReady) {
            _status.value = "這台裝置目前無法連到 Google Play 結帳，無法恢復購買。"
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                _status.value = "恢復購買失敗。請稍後再試。"
                return@queryPurchasesAsync
            }
            applyPurchases(purchases)
            if (!_owned.value) {
                _status.value = "沒有找到 remove_ads 的購買紀錄。"
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        foregroundAt = SystemClock.elapsedRealtime()
        shownThisForeground = false
        if (suppressAppOpen) {
            suppressAppOpen = false
            return
        }
        maybeShowAppOpen()
    }

    private fun requestConsent(activity: Activity) {
        val builder = ConsentRequestParameters.Builder()
        if (BuildConfig.DEBUG && BuildConfig.ADMOB_DEBUG_EEA) {
            val debug = ConsentDebugSettings.Builder(activity)
                .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
            if (BuildConfig.ADMOB_TEST_DEVICE.isNotBlank()) {
                debug.addTestDeviceHashedId(BuildConfig.ADMOB_TEST_DEVICE)
            }
            builder.setConsentDebugSettings(debug.build())
        }
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        consent.requestConsentInfoUpdate(
            activity,
            builder.build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    _canRequestAds.value = consent.canRequestAds()
                    if (prefs.disclaimerAccepted) startLoadingAds(activity)
                }
            },
            {
                _canRequestAds.value = consent.canRequestAds()
                if (prefs.disclaimerAccepted) startLoadingAds(activity)
            },
        )
    }

    private fun startLoadingAds(context: android.content.Context) {
        if (adsHidden() || !_canRequestAds.value || !prefs.disclaimerAccepted) return
        if (!mobileAdsStarted) {
            mobileAdsStarted = true
            MobileAds.initialize(context)
        }
        loadAppOpen()
    }

    private fun loadAppOpen() {
        if (appOpenLoading || appOpenAd != null || adsHidden()) return
        appOpenLoading = true
        AppOpenAd.load(
            app,
            BuildConfig.ADMOB_APP_OPEN_UNIT,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenLoading = false
                    appOpenAd = ad
                    appOpenLoadedAt = SystemClock.elapsedRealtime()
                    val freshForeground = SystemClock.elapsedRealtime() - foregroundAt < COLD_START_WINDOW_MS
                    if (freshForeground && !shownThisForeground) maybeShowAppOpen()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    appOpenLoading = false
                    appOpenAd = null
                }
            },
        )
    }

    private fun maybeShowAppOpen() {
        if (shownThisForeground || appOpenShowing || adsHidden()) return
        if (!prefs.disclaimerAccepted || !_canRequestAds.value) return
        val activity = currentActivity ?: return
        if (activity.isFinishing) return
        val now = System.currentTimeMillis()
        if (now - prefs.appOpenShownAt < APP_OPEN_GAP_MS) return
        val ad = appOpenAd
        if (ad == null || SystemClock.elapsedRealtime() - appOpenLoadedAt > AD_MAX_AGE_MS) {
            appOpenAd = null
            loadAppOpen()
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                appOpenShowing = true
            }

            override fun onAdDismissedFullScreenContent() {
                appOpenShowing = false
                appOpenAd = null
                loadAppOpen()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                appOpenShowing = false
                appOpenAd = null
                loadAppOpen()
            }
        }
        shownThisForeground = true
        prefs.appOpenShownAt = now
        ad.show(activity)
    }

    private val purchasesUpdated = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> applyPurchases(purchases.orEmpty())
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> restore()
            else -> _status.value = "購買沒有完成。請稍後再試，或按恢復購買。"
        }
    }

    private fun connectBilling() {
        if (billingClient != null) return
        val client = BillingClient.newBuilder(app)
            .setListener(purchasesUpdated)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .build()
        billingClient = client
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    _canBuy.value = false
                    if (!_owned.value) {
                        _status.value = "這台裝置目前無法使用 Google Play 結帳。已買過可稍後再按恢復購買。"
                    }
                    return
                }
                restoreQuiet()
                queryProduct()
            }

            override fun onBillingServiceDisconnected() {
                _canBuy.value = false
            }
        })
    }

    private fun restoreQuiet() {
        val client = billingClient ?: return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                applyPurchases(purchases)
            }
        }
    }

    private fun queryProduct() {
        val client = billingClient ?: return
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(productId)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            val found = details.firstOrNull()
            if (result.responseCode != BillingClient.BillingResponseCode.OK || found == null) {
                productDetails = null
                _price.value = null
                _canBuy.value = false
                if (!_owned.value) {
                    _status.value = "Play Console 還沒有商品 $productId。請建立一次性、非消耗型的應用程式內商品，商品 ID 用 $productId。"
                }
                return@queryProductDetailsAsync
            }
            productDetails = found
            _price.value = found.oneTimePurchaseOfferDetails?.formattedPrice
            _canBuy.value = !_owned.value
            if (!_owned.value && _status.value.isBlank()) {
                _status.value = "一次買斷後，橫幅、清單廣告與開啟 App 的廣告都會關閉。"
            }
        }
    }

    private fun applyPurchases(purchases: List<Purchase>) {
        val match = purchases.filter {
            it.products.contains(productId) && it.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        val ownedNow = match.isNotEmpty()
        prefs.removeAdsOwned = ownedNow
        _owned.value = ownedNow
        _canBuy.value = productDetails != null && !ownedNow
        if (ownedNow) {
            _status.value = "已購買，廣告已關閉。"
            appOpenAd = null
        }
        val client = billingClient ?: return
        match.filter { !it.isAcknowledged }.forEach { purchase ->
            val ack = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            client.acknowledgePurchase(ack) { }
        }
    }

    override fun onActivityStarted(activity: Activity) {
        currentActivity = activity
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity == activity) currentActivity = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    companion object {
        const val APP_OPEN_GAP_MS = 30 * 60 * 1000L
        private const val COLD_START_WINDOW_MS = 5_000L
        private const val AD_MAX_AGE_MS = 4 * 60 * 60 * 1000L
    }
}
