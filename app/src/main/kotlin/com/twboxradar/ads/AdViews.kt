package com.twboxradar.ads

import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.twboxradar.BuildConfig

@Composable
fun BannerAd(enabled: Boolean) {
    if (!enabled) return
    val context = LocalContext.current
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = {
            val widthDp = (context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density).toInt()
            AdView(context).apply {
                setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp.coerceAtLeast(320)))
                adUnitId = BuildConfig.ADMOB_BANNER_UNIT
                loadAd(AdRequest.Builder().build())
            }
        },
        onRelease = { it.destroy() },
    )
}

@Composable
fun NativeAdCard(enabled: Boolean) {
    if (!enabled) return
    val context = LocalContext.current
    val holder = remember { arrayOfNulls<NativeAd>(1) }
    var nativeAd by remember { mutableStateOf<NativeAd?>(null) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val loader = AdLoader.Builder(context, BuildConfig.ADMOB_NATIVE_UNIT)
            .forNativeAd { ad ->
                holder[0]?.destroy()
                holder[0] = ad
                nativeAd = ad
            }
            .withNativeAdOptions(
                NativeAdOptions.Builder()
                    .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                    .build(),
            )
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    failed = true
                }
            })
            .build()
        loader.loadAd(AdRequest.Builder().build())
        onDispose {
            holder[0]?.destroy()
            holder[0] = null
        }
    }
    if (failed) return
    val ad = nativeAd
    if (ad == null) {
        Card(Modifier.fillMaxWidth()) {
            Text(
                "廣告",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        return
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx -> nativeAdLayout(ctx) },
        update = { view ->
            if (view.tag != ad) {
                bindNativeAd(view, ad)
                view.tag = ad
            }
        },
    )
}

private fun nativeAdLayout(context: android.content.Context): NativeAdView {
    val density = context.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()
    val root = NativeAdView(context)
    val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundColor(0xFFF4F7FB.toInt())
    }
    val badge = TextView(context).apply {
        text = "廣告"
        textSize = 12f
        setTextColor(0xFF1565C0.toInt())
    }
    val headline = TextView(context).apply {
        textSize = 16f
        setTextColor(0xFF102027.toInt())
        setPadding(0, dp(4), 0, 0)
    }
    val body = TextView(context).apply {
        textSize = 13f
        setTextColor(0xFF546E7A.toInt())
        setPadding(0, dp(4), 0, 0)
    }
    val cta = Button(context).apply {
        textSize = 14f
        gravity = Gravity.CENTER
    }
    column.addView(badge)
    column.addView(headline)
    column.addView(body)
    column.addView(cta)
    root.addView(column)
    root.headlineView = headline
    root.bodyView = body
    root.callToActionView = cta
    return root
}

private fun bindNativeAd(view: NativeAdView, ad: NativeAd) {
    (view.headlineView as? TextView)?.text = ad.headline
    val body = view.bodyView as? TextView
    body?.text = ad.body
    body?.visibility = if (ad.body.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
    val cta = view.callToActionView as? Button
    cta?.text = ad.callToAction
    cta?.visibility = if (ad.callToAction.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
    view.setNativeAd(ad)
}
