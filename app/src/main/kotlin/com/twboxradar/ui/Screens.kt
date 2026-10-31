package com.twboxradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.twboxradar.core.BacktestResult
import com.twboxradar.core.BoxParams
import com.twboxradar.core.BoxStatus
import com.twboxradar.core.DISCLAIMER
import com.twboxradar.core.PREVIEW_DATA_BRANCH
import com.twboxradar.core.ScreenRow
import com.twboxradar.core.SymbolHistory
import com.twboxradar.core.Trade
import com.twboxradar.core.conditionCatalog
import com.twboxradar.core.describeNet
import com.twboxradar.core.describePosition
import com.twboxradar.core.describeQuality
import com.twboxradar.core.RowSort
import com.twboxradar.core.describeRevenue
import com.twboxradar.core.marketDocsUrl
import com.twboxradar.core.marketLabel
import com.twboxradar.core.membershipLabel
import com.twboxradar.core.normalizeDataBranch
import com.twboxradar.core.visibleRows
import java.util.Locale

@Composable
fun DisclaimerScreen(onAccept: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("使用前請先閱讀", style = MaterialTheme.typography.headlineSmall)
        Text("台股箱型雷達只整理公開行情與客觀計算結果，例如「突破箱頂」「跌破箱底」「距離箱頂還有多少」。")
        Text("本程式沒有投資顧問資格，不提供買賣建議、目標價或個股推薦。")
        Text(DISCLAIMER, fontWeight = FontWeight.Bold)
        // SMCPKG_SUPPORT>>>CursorXXX
        // Text("行情來自臺灣證券交易所與證券櫃檯買賣中心的公開資料，價格未還原除權息。跨越除權息日的箱型與報酬可能失真。")
        Text("行情來自臺灣證券交易所與證券櫃檯買賣中心。清單、箱型、提醒與回測使用向後還原價，拿掉除權息造成的缺口。K 線預設也是還原價，可以改看成交原價。")
        // SMCPKG_SUPPORT<<<CursorYYY
        Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
            Text("我了解了，資料僅供參考")
        }
    }
}

@Composable
fun ListScreen(state: UiState, onOpen: (String) -> Unit, onRetry: () -> Unit) {
    // SMCPKG_SUPPORT>>>CursorXXX
    // fun ListScreen(state: UiState, onOpen: (String) -> Unit)
    // 搜尋以前只過濾目前分頁，所以在「今日突破箱頂」輸入 2330 會得到 0 檔。
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var market by rememberSaveable { mutableStateOf("all") }
    var sortName by rememberSaveable { mutableStateOf(RowSort.CODE.name) }
    val sort = RowSort.entries.firstOrNull { it.name == sortName } ?: RowSort.CODE
    val focus = LocalFocusManager.current
    val titles = listOf("今日突破箱頂", "箱型內整理中", "接近箱底")
    val statuses = listOf(BoxStatus.BREAKOUT, BoxStatus.INSIDE, BoxStatus.NEAR_BOTTOM)
    val shown = visibleRows(state.rows, statuses[tab], market, query, sort)
    val searching = query.isNotBlank()
    Column(Modifier.fillMaxSize()) {
        Header(state, onRetry)
        TabRow(selectedTabIndex = tab) {
            titles.forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            label = { Text("搜尋代號或名稱") },
            placeholder = { Text("例如 2330 或 台積電") },
            supportingText = {
                Text(if (searching) "正在查全部股票，不受上方分頁限制。點一下結果即可開啟。" else "輸入後會改查全部股票，不限目前這個分頁。")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    if (shown.size == 1) onOpen(shown.single().code)
                    focus.clearFocus()
                },
            ),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "全部", "twse" to "上市", "tpex" to "上櫃").forEach { (id, label) ->
                FilterChip(selected = market == id, onClick = { market = id }, label = { Text(label) })
            }
        }
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("排序", style = MaterialTheme.typography.labelMedium)
            RowSort.entries.forEach { option ->
                FilterChip(selected = sort == option, onClick = { sortName = option.name }, label = { Text(option.label) })
            }
        }
        if (searching && shown.size == 1) {
            Button(
                onClick = { onOpen(shown.single().code) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) { Text("開啟 ${shown.single().name} ${shown.single().code}") }
        }
        if (state.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    if (searching) "從全部股票找到 ${shown.size} 檔" else "「${titles[tab]}」共 ${shown.size} 檔",
                    style = MaterialTheme.typography.labelLarge,
                )
                if (!searching) {
                    Text(
                        "這一頁只列出符合分頁的股票。想找特定代號，直接輸入即可，鍵盤上的搜尋鍵也會開啟唯一結果。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (shown.isEmpty()) {
                item { EmptyListMessage(state, searching, market, query.trim(), titles[tab]) }
            }
            items(shown, key = { it.code }) { row ->
                StockCard(
                    row,
                    onClick = { onOpen(row.code) },
                    membership = if (searching) membershipLabel(row.status) else null,
                    chips = state.chips[row.code].orEmpty(),
                )
            }
        }
    }
    // SMCPKG_SUPPORT<<<CursorYYY
}

@Composable
private fun EmptyListMessage(state: UiState, searching: Boolean, market: String, query: String, tabTitle: String) {
    val marketName = when (market) {
        "twse" -> "上市"
        "tpex" -> "上櫃"
        else -> "全部市場"
    }
    val text = when {
        searching && market != "all" ->
            "在「$marketName」裡找不到「$query」。市場篩選仍然有效，可改選「全部」再查一次。"
        searching ->
            "已載入的股票裡找不到「$query」。"
        else ->
            "「$tabTitle」目前沒有股票。這不代表市場裡沒有你要的代號，輸入搜尋即可查全部股票。"
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text)
        if (state.sample) {
            Text(
                "目前是內建範例，只有少數股票。到「設定」把資料分支改成 $PREVIEW_DATA_BRANCH 並重新下載，才能查全市場。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun StockCard(row: ScreenRow, onClick: () -> Unit, membership: String? = null, chips: List<String> = emptyList()) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${row.name} ${row.code}", fontWeight = FontWeight.SemiBold)
                    RiskChipRow(chips)
                }
                Text(marketLabel(row.market), style = MaterialTheme.typography.labelMedium)
            }
            membership?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
            Text(describePosition(row))
            Text(boxLine(row), style = MaterialTheme.typography.bodySmall)
            Text(describeQuality(row), style = MaterialTheme.typography.bodySmall)
            Text(
                buildString {
                    append(formatRatio(row.volumeRatio))
                    if (row.status == BoxStatus.BREAKOUT) {
                        append(if (row.volumeConfirmed) "　帶量" else "　未帶量")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 注意／處置／漲停／跌停小標籤。紅漲綠跌，注意與處置用橘色。 */
@Composable
fun RiskChipRow(chips: List<String>) {
    if (chips.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        chips.forEach { label ->
            val color = when {
                label == "漲停" -> Color(0xFFC62828)
                label == "跌停" -> Color(0xFF2E7D32)
                else -> Color(0xFFE65100)
            }
            Text(
                label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(color)
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
fun DetailScreen(state: UiState, code: String, onWatch: () -> Unit, onBacktest: () -> Unit) {
    val symbol = state.symbols.firstOrNull { it.code == code }
    val row = state.rows.firstOrNull { it.code == code }
    if (symbol == null || row == null) {
        Text("找不到這檔股票", modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${symbol.name} ${symbol.code}", style = MaterialTheme.typography.headlineSmall)
                Text("${marketLabel(symbol.market)}　資料日 ${formatDateText(state.asOf)}")
                RiskChipRow(state.chips[code].orEmpty())
                riskDetail(state, code)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                FundamentalsBlock(state, code)
                Text(describePosition(row), fontWeight = FontWeight.Medium)
                Text(boxLine(row))
                Text("箱頂日 ${formatDate(row.topDate)}　箱底日 ${formatDate(row.bottomDate)}")
                Text(describeQuality(row))
                Text(
                    "RS 是 60／120 日報酬在全市場的百分位（100 最強）。箱內量是箱頂以來均量除以箱頂前 20 日均量，小於 1 表示量縮。",
                    style = MaterialTheme.typography.bodySmall,
                )
                // SMCPKG_SUPPORT>>>CursorXXX
                // Text("價格未還原除權息。${DISCLAIMER}", style = MaterialTheme.typography.bodySmall)
                Text("箱型依還原價計算。${DISCLAIMER}", style = MaterialTheme.typography.bodySmall)
                // SMCPKG_SUPPORT<<<CursorYYY
                if (state.detailLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp))
                        Text("正在補齊個股歷史")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onWatch) {
                        Text(if (code in state.watchlist) "移出自選" else "加入自選")
                    }
                    TextButton(onClick = onBacktest) { Text("看這檔的回測") }
                }
            }
        }
        // SMCPKG_SUPPORT>>>CursorXXX
        // item { CandleChart(symbol.bars, row.top, row.bottom) }
        item { AdjustedChart(symbol, row.top, row.bottom) }
        // SMCPKG_SUPPORT<<<CursorYYY
    }
}

@Composable
private fun AdjustedChart(symbol: SymbolHistory, top: Double?, bottom: Double?) {
    val hasRaw = symbol.rawBars.isNotEmpty()
    var showRaw by rememberSaveable(symbol.code) { mutableStateOf(false) }
    Column {
        if (hasRaw) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = !showRaw, onClick = { showRaw = false }, label = { Text("還原價") })
                FilterChip(selected = showRaw, onClick = { showRaw = true }, label = { Text("原始價") })
            }
            Text(
                if (showRaw) {
                    "目前是成交原價。箱頂與箱底是用還原價算出來的，所以這張圖不畫箱線。"
                } else {
                    "目前是向後還原價。除權息日前的價格已按官方參考價調過，成交量只在配股或現金增資時調整。"
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val useRaw = showRaw && hasRaw
        CandleChart(
            if (useRaw) symbol.rawBars else symbol.bars,
            if (useRaw) null else top,
            if (useRaw) null else bottom,
        )
    }
}

@Composable
fun WatchScreen(state: UiState, onOpen: (String) -> Unit, onRetry: () -> Unit) {
    val rows = state.rows.filter { it.code in state.watchlist }
    Column(Modifier.fillMaxSize()) {
        Header(state, onRetry)
        if (rows.isEmpty()) {
            Text(
                "尚未加入自選股。可從每日清單點進個股後加入。加入後，背景工作會在資料更新時檢查突破與跌破。",
                modifier = Modifier.padding(16.dp),
            )
            return
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.code }) { row ->
                StockCard(row, onClick = { onOpen(row.code) }, chips = state.chips[row.code].orEmpty())
            }
        }
    }
}

@Composable
fun ConditionScreen(
    state: UiState,
    onToggle: (String) -> Unit,
    onRun: () -> Unit,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Header(state, onRetry)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("勾選的條件全部同時成立（而且）才會列入結果。")
                Text("例如：突破箱頂，而且收盤站上 20 日均線，而且 KD 黃金交叉。")
                Text(
                    "法人與營收條件需要下載 chips.json；法人資料從 ${formatDateText(state.fundamentals.chipStart).ifEmpty { "開始累積那天" }} 起算，沒有資料的股票不列入。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    conditionCatalog().forEach { choice ->
                        FilterChip(
                            selected = choice.id in state.selectedConditions,
                            onClick = { onToggle(choice.id) },
                            label = { Text(choice.label) },
                        )
                    }
                }
            }
            item {
                Button(onClick = onRun, enabled = state.selectedConditions.isNotEmpty()) {
                    Text("套用條件")
                }
            }
            val hits = state.conditionHits
            if (hits != null) {
                item { Text("符合 ${hits.size} 檔", fontWeight = FontWeight.Medium) }
                items(hits, key = { it.code }) { row ->
                    StockCard(row, onClick = { onOpen(row.code) }, chips = state.chips[row.code].orEmpty())
                }
            }
        }
    }
}

@Composable
fun BacktestScreen(state: UiState, code: String) {
    val symbol = state.symbols.firstOrNull { it.code == code }
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("${symbol?.name ?: code} $code 回測", style = MaterialTheme.typography.headlineSmall)
        Text("買進：收盤突破箱頂。賣出：收盤跌破停損（先是該箱箱底，之後只上移到新的箱底）。")
        // SMCPKG_SUPPORT>>>CursorXXX
        // Text("以收盤價模擬成交，不計手續費與稅。價格未還原除權息，區間盤的假突破會出現在交易清單。")
        Text("以還原後的收盤價模擬成交，不計手續費與稅。區間盤的假突破會出現在交易清單。")
        // SMCPKG_SUPPORT<<<CursorYYY
        if (state.params.skipLimitUpEntry) {
            Text("已略過收盤漲停的突破，這幾天不進場。", style = MaterialTheme.typography.bodySmall)
        }
        Text(DISCLAIMER, style = MaterialTheme.typography.bodySmall)
        if (state.backtestLoading || state.backtest == null) {
            CircularProgressIndicator()
        } else {
            ResultBlock(state.backtest, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ResultBlock(result: BacktestResult, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Metric("策略總報酬", pct(result.totalReturn)) }
        item { Metric("買進持有", pct(result.buyHoldReturn)) }
        item {
            val winRate = result.winRate
            Metric("勝率", if (winRate == null) "沒有已平倉交易" else pct(winRate))
        }
        item { Metric("最大回撤", pct(result.maxDrawdown)) }
        item { Metric("已平倉次數", result.closedTrades.toString()) }
        items(result.trades) { trade -> TradeLine(trade) }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TradeLine(trade: Trade) {
    val exit = if (trade.exitDate == null) "未平倉 ${formatPrice(trade.exitPrice)}" else "${formatDate(trade.exitDate)} ${formatPrice(trade.exitPrice)}"
    Text(
        "${formatDate(trade.entryDate)} 買進 ${formatPrice(trade.entryPrice)} → $exit　${pct(trade.returnFraction ?: 0.0)}",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
fun SettingsScreen(
    state: UiState,
    onParams: (BoxParams) -> Unit,
    onAlerts: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRequestNotifications: () -> Unit,
    onBranch: (String) -> Unit,
) {
    val params = state.params
    var draft by rememberSaveable { mutableStateOf(state.dataBranch) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Header(state, onRefresh) }
        item { Text("資料來源", style = MaterialTheme.typography.titleMedium) }
        item {
            Text("合併到 main 之後，空白分支會自動讀全市場。合併前 main 沒有檔案，可先改成這次更新的分支。")
        }
        item {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("資料分支，空白表示 main") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onBranch(draft) }),
            )
        }
        item {
            Text(
                "目前使用 ${normalizeDataBranch(state.dataBranch)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(marketDocsUrl(state.dataBranch), style = MaterialTheme.typography.bodySmall)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    draft = ""
                    onBranch("")
                }) { Text("改回 main") }
                TextButton(onClick = {
                    draft = PREVIEW_DATA_BRANCH
                    onBranch(PREVIEW_DATA_BRANCH)
                }) { Text("這次更新的分支") }
            }
        }
        item {
            Button(onClick = { onBranch(draft) }, enabled = !state.loading) {
                Text(if (state.loading) "更新中" else "用這個分支並重新下載")
            }
        }
        item { Text("箱型參數", style = MaterialTheme.typography.titleMedium) }
        item {
            SliderField("回看天數 ${params.lookbackDays}", params.lookbackDays.toFloat(), 20f, 120f) {
                onParams(params.copy(lookbackDays = it.toInt()))
            }
        }
        item {
            SliderField("確認天數 ${params.confirmationDays}", params.confirmationDays.toFloat(), 2f, 8f) {
                onParams(params.copy(confirmationDays = it.toInt()))
            }
        }
        item {
            SliderField(
                "接近箱底：位於箱型區間下緣 ${(params.nearRangeFraction * 100).toInt()}%",
                params.nearRangeFraction.toFloat(),
                0.1f,
                0.4f,
            ) { onParams(params.copy(nearRangeFraction = (it * 20).toInt() / 20.0)) }
        }
        item {
            SliderField(
                "帶量倍數 ${String.format(Locale.US, "%.1f", params.volumeMultiple)} 倍 20 日均量",
                params.volumeMultiple.toFloat(),
                1f,
                5f,
            ) { onParams(params.copy(volumeMultiple = (it * 2).toInt() / 2.0)) }
        }
        item {
            SwitchRow("用最高最低（含影線）畫箱。關閉則只用開盤與收盤的實體", params.useWicks) {
                onParams(params.copy(useWicks = it))
            }
        }
        item {
            SwitchRow("回測的突破必須帶量才進場", params.requireVolumeForEntry) {
                onParams(params.copy(requireVolumeForEntry = it))
            }
        }
        item {
            SwitchRow("回測略過收盤漲停的突破（漲停價常無法成交）", params.skipLimitUpEntry) {
                onParams(params.copy(skipLimitUpEntry = it))
            }
        }
        item {
            SwitchRow("自選股突破／跌破提醒", state.alertsEnabled) {
                onAlerts(it)
                if (it) onRequestNotifications()
            }
        }
        item {
            Text("提醒在資料發佈後，由系統背景工作檢查。突破要成交量大於上面的均量倍數，才會出現「可能進入新箱型」。跌破箱底則顯示「箱型被破壞」。")
        }
        item { Button(onClick = onRefresh) { Text(if (state.loading) "更新中" else "重新下載資料") } }
        item { Text(DISCLAIMER) }
        item { Text("預設回看 60 日、確認 3 日、使用最高最低，對應經典達瓦斯箱型的三日確認。", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SliderField(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    var local by androidx.compose.runtime.remember(value) { mutableFloatStateOf(value) }
    Column {
        Text(label)
        Slider(
            value = local.coerceIn(min, max),
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = min..max,
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Header(state: UiState, onRetry: (() -> Unit)? = null) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("資料日 ${formatDateText(state.asOf)}　${state.symbols.size} 檔", style = MaterialTheme.typography.labelLarge)
        if (state.sample) Text("目前是內建範例", color = MaterialTheme.colorScheme.primary)
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (onRetry != null && state.message != null) {
            Button(onClick = onRetry, enabled = !state.loading) {
                Text(if (state.loading) "更新中" else "重試")
            }
        }
    }
}

/** 個股頁的三大法人與月營收。沒有資料就整段不顯示。 */
@Composable
private fun FundamentalsBlock(state: UiState, code: String) {
    val data = state.fundamentals.items[code] ?: return
    val chips = data.chips
    val revenue = data.revenue
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (chips != null) {
            Text("三大法人買賣超", style = MaterialTheme.typography.titleSmall)
            Text(describeNet("外資", chips.foreign5, chips.foreign20, data.chipDays))
            Text(describeNet("投信", chips.trust5, chips.trust20, data.chipDays))
            Text(describeNet("自營", chips.dealer5, chips.dealer20, data.chipDays))
            if (chips.trustBuyStreak > 0) Text("投信連續買超 ${chips.trustBuyStreak} 天", style = MaterialTheme.typography.bodySmall)
            if (chips.foreignBuyStreak > 0) Text("外資連續買超 ${chips.foreignBuyStreak} 天", style = MaterialTheme.typography.bodySmall)
            val range = state.fundamentals
            Text(
                "法人資料自 ${formatDateText(range.chipStart)} 起累積，目前 ${range.chipDays} 個交易日。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (revenue != null) {
            Text("月營收", style = MaterialTheme.typography.titleSmall)
            Text(describeRevenue(revenue))
        }
    }
}

/** 個股頁的注意／處置說明。清單日期和行情不同時寫出清單日期。 */
private fun riskDetail(state: UiState, code: String): String? {
    val item = state.flags.flags[code] ?: return null
    val parts = mutableListOf<String>()
    if (item.notice) parts += "當日列入公布注意交易資訊"
    val end = item.punishEnd
    if (end != null) {
        val measure = item.punishMeasure?.takeIf { it.isNotBlank() } ?: "處置"
        parts += "處置期間 ${formatDate(item.punishStart)} 至 ${formatDate(end)}（$measure）"
    }
    if (parts.isEmpty()) return null
    val dated = if (state.flags.riskAsOf.isNotEmpty()) "清單日期 ${formatDateText(state.flags.riskAsOf)}" else ""
    return (parts + listOfNotNull(dated.ifEmpty { null })).joinToString("。")
}

private fun pct(value: Double): String = String.format(Locale.US, "%+.1f%%", value * 100.0)
