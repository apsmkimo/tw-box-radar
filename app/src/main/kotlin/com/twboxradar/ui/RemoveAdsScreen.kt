package com.twboxradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.twboxradar.BuildConfig
import com.twboxradar.ads.AdsController
import com.twboxradar.core.DISCLAIMER

@Composable
fun RemoveAdsScreen(ads: AdsController, activity: android.app.Activity) {
    val owned by ads.owned.collectAsStateWithLifecycle()
    val debugRemoved by ads.debugRemoved.collectAsStateWithLifecycle()
    val price by ads.price.collectAsStateWithLifecycle()
    val status by ads.status.collectAsStateWithLifecycle()
    val canBuy by ads.canBuy.collectAsStateWithLifecycle()
    val hidden = owned || (BuildConfig.DEBUG && debugRemoved)
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("移除廣告", style = MaterialTheme.typography.headlineSmall)
        Text("一次買斷後，頂端橫幅、股票清單裡的廣告，以及重新開啟 App 時的廣告都會關掉。行情與箱型計算不會因此改變。")
        Text(if (hidden) "目前廣告已關閉。" else "目前仍會顯示測試廣告。", fontWeight = FontWeight.Medium)
        price?.let { Text("價格 $it") }
        if (status.isNotBlank()) Text(status)
        Button(
            onClick = { ads.buy(activity) },
            enabled = canBuy && !owned,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (owned) "已購買" else "購買移除廣告")
        }
        TextButton(onClick = ads::restore, modifier = Modifier.fillMaxWidth()) {
            Text("恢復購買")
        }
        if (BuildConfig.DEBUG) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("測試：當作已購買", modifier = Modifier.weight(1f))
                Switch(checked = debugRemoved, onCheckedChange = ads::setDebugRemoved)
            }
            Text(
                "只有 debug 版有這個開關，用來在 Play Console 建立商品前先看沒有廣告的畫面。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(DISCLAIMER, style = MaterialTheme.typography.bodySmall)
    }
}
