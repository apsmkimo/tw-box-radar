package com.twboxradar.data

import android.content.Context
import com.twboxradar.core.Bar
import com.twboxradar.core.ChipSummary
import com.twboxradar.core.Fundamentals
import com.twboxradar.core.LimitMove
import com.twboxradar.core.RevenueSummary
import com.twboxradar.core.RiskFlags
import com.twboxradar.core.SymbolHistory
import com.twboxradar.core.local.BuiltMarket
import com.twboxradar.core.local.DIRECT_LABEL
import com.twboxradar.core.local.DataSourceMode
import com.twboxradar.core.local.GitHubDataSeed
import com.twboxradar.core.local.LocalStore
import com.twboxradar.core.local.REMOTE_LABEL
import com.twboxradar.core.local.SeedSource
import com.twboxradar.core.local.SyncReport
import com.twboxradar.core.local.Syncer
import com.twboxradar.core.local.ThrottledHttp
import com.twboxradar.core.local.UrlConnectionHttp
import com.twboxradar.core.local.buildMarket
import com.twboxradar.core.local.pretty
import com.twboxradar.core.local.runSeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LoadedMarket(
    val symbols: List<SymbolHistory>,
    val asOf: String,
    val sample: Boolean,
    val message: String?,
    /** 「資料來源 …　資料日 …」，畫面標頭顯示。 */
    val sourceNote: String = "",
)

/** 注意／處置／漲跌停標籤。asOf 是行情資料日，riskAsOf 是注意／處置清單的日期。 */
data class LoadedFlags(
    val asOf: String = "",
    val riskAsOf: String = "",
    val flags: Map<String, RiskFlags> = emptyMap(),
)

/** 法人與營收。chipStart～chipEnd 是本機已累積的法人資料區間。 */
data class LoadedChips(
    val asOf: String = "",
    val chipDays: Int = 0,
    val chipStart: String = "",
    val chipEnd: String = "",
    val items: Map<String, Fundamentals> = emptyMap(),
)

/**
 * 行情都從裝置上的本機資料來（core/local/LocalStore）。
 *
 * 同步：第一次先匯入初始歷史（APK 內建 assets/seed/，沒有就從 GitHub data/ 匯入一次），
 * 之後只下載比本機最新一天更新的交易日。來源依設定：遠端（Cloudflare Worker）或直連證交所／櫃買；
 * 遠端失敗時這次自動改直連。解析、還原、標籤與法人統計都在裝置上計算。
 * 全部失敗而且本機沒有資料時，顯示內建範例 assets/sample/window.json。
 */
class MarketRepository(private val context: Context) {
    private val prefs = Prefs(context)
    private val store = LocalStore(File(context.filesDir, "store"))

    private fun seeds(): List<SeedSource> = listOf(
        AssetSeed(context),
        GitHubDataSeed(UrlConnectionHttp),
    )

    /**
     * 同步後組出全市場。force＝使用者按重新下載；否則 10 分鐘內同步過就直接用本機資料。
     */
    suspend fun loadMarket(force: Boolean, onProgress: (String) -> Unit = {}): LoadedMarket = withContext(Dispatchers.IO) {
        lock.withLock {
            cleanupLegacyCache()
            val outcome = syncLocked(force, onProgress)
            val built = runCatching { buildMarket(store) }.getOrNull()
            if (built == null) {
                cache = null
                return@withLock sampleMarket(outcome.message ?: "本機還沒有資料")
            }
            cache = built
            LoadedMarket(
                symbols = built.symbols(includeRaw = false),
                asOf = built.asOf,
                sample = false,
                message = outcome.message,
                sourceNote = sourceNote(outcome.label, built),
            )
        }
    }

    /** 注意／處置／漲跌停，來自最近一次 loadMarket 組好的結果。 */
    fun loadFlags(asOf: String): LoadedFlags {
        val built = cache ?: return LoadedFlags()
        if (built.asOf != asOf) return LoadedFlags()
        return LoadedFlags(built.asOf, built.riskAsOf, built.flags)
    }

    fun loadChips(asOf: String): LoadedChips {
        val built = cache ?: return LoadedChips()
        if (built.asOf != asOf) return LoadedChips()
        return LoadedChips(built.asOf, built.chipDays, built.chipStart, built.chipEnd, built.fundamentals)
    }

    /** 個股頁：帶成交原價的完整本機歷史（約 250 個交易日）。 */
    suspend fun loadHistory(code: String, fallback: SymbolHistory?): SymbolHistory? = withContext(Dispatchers.IO) {
        cache?.symbol(code, includeRaw = true) ?: fallback
    }

    /**
     * 背景提醒用：先同步，再回傳本機最新資料日。
     * 同步中斷而且沒有拿到任何新資料時丟出例外，讓 WorkManager 稍後重試。
     */
    suspend fun fetchAsOf(): String = withContext(Dispatchers.IO) {
        lock.withLock {
            val outcome = syncLocked(force = true, onProgress = {})
            val report = outcome.report
            if (report != null && report.error != null && report.newDays.isEmpty()) {
                throw IllegalStateException(report.error)
            }
            val latest = store.latestDay() ?: throw IllegalStateException(outcome.message ?: "本機沒有資料")
            "%08d".format(latest)
        }
    }

    /** 背景提醒用：自選股的還原歷史（含成交原價）。 */
    suspend fun loadSymbols(codes: Collection<String>): List<SymbolHistory> = withContext(Dispatchers.IO) {
        lock.withLock {
            val built = buildMarket(store) ?: throw IllegalStateException("本機沒有資料")
            cache = built
            codes.sorted().mapNotNull { built.symbol(it, includeRaw = true) }
        }
    }

    /** 本機資料大小，給設定頁顯示。 */
    fun storeSummary(): String {
        val days = store.tradingDays()
        if (days.isEmpty()) return "本機還沒有資料"
        val mb = store.sizeBytes() / 1024.0 / 1024.0
        return "本機 ${days.size} 個交易日（${pretty(days.first())}～${pretty(days.last())}），約 ${"%.1f".format(mb)} MB"
    }

    private class Outcome(val report: SyncReport?, val label: String, val message: String?)

    private fun syncLocked(force: Boolean, onProgress: (String) -> Unit): Outcome {
        val mode = prefs.dataSourceMode
        val state = store.state()
        val recent = System.currentTimeMillis() - state.lastSyncAt < RESYNC_MS
        if (!force && recent && store.latestDay() != null) {
            return Outcome(null, state.lastSource.ifEmpty { label(mode) }, state.lastMessage.ifEmpty { null })
        }
        val messages = mutableListOf<String>()
        val seedError = runSeed(store, seeds(), onProgress)
        if (seedError != null) messages += "$seedError，下次同步再試。"
        val report = try {
            Syncer(
                store = store,
                remoteHttp = UrlConnectionHttp,
                direct = ThrottledHttp(UrlConnectionHttp),
                progress = onProgress,
            ).sync(mode, forceSnapshots = force)
        } catch (error: Exception) {
            messages += "同步失敗（${error.message?.take(80) ?: error.javaClass.simpleName}），先顯示本機已有的資料。"
            null
        }
        if (report != null) {
            if (report.fallbackReason != null && report.usedDirect) {
                messages += "${report.fallbackReason}，這次改用$DIRECT_LABEL。"
            } else if (report.fallbackReason != null) {
                messages += "${report.fallbackReason}。"
            }
            report.error?.let { messages += "$it。先顯示本機已有的資料。" }
        }
        val label = report?.sourceLabel ?: label(mode)
        val message = messages.joinToString("\n").ifEmpty { null }
        store.saveState(store.state().copy(lastSource = label, lastMessage = message ?: ""))
        return Outcome(report, label, message)
    }

    private fun label(mode: DataSourceMode) = if (mode == DataSourceMode.REMOTE) REMOTE_LABEL else DIRECT_LABEL

    private fun sourceNote(label: String, built: BuiltMarket): String {
        val day = built.asOf.toIntOrNull()?.let { pretty(it) } ?: built.asOf
        return "資料來源 $label　資料日 $day　本機 ${built.tradingDays} 個交易日"
    }

    private fun sampleMarket(reason: String): LoadedMarket {
        val text = context.assets.open("sample/window.json").bufferedReader().use { it.readText() }
        return LoadedMarket(
            symbols = parseWindow(text),
            asOf = JSONObject(text).optString("asOf"),
            sample = true,
            message = "$reason\n目前顯示內建範例。請確認網路後按重試。",
            sourceNote = "內建範例",
        )
    }

    /** 0.2.x 的 GitHub docs 快取（window.json 約 16 MB）已不再使用，刪掉。 */
    private fun cleanupLegacyCache() {
        val legacy = File(context.filesDir, "market")
        if (legacy.exists()) legacy.deleteRecursively()
    }

    companion object {
        /** UI 與背景提醒共用同一份本機資料，同一時間只允許一個同步。 */
        private val lock = Mutex()

        @Volatile
        private var cache: BuiltMarket? = null

        private const val RESYNC_MS = 10 * 60 * 1000L
    }
}

/** 內建範例（assets/sample/window.json）的格式，與以前的 docs/window.json 相同。 */
fun parseWindow(text: String): List<SymbolHistory> {
    val root = JSONObject(text)
    val array = root.getJSONArray("symbols")
    return buildList {
        for (i in 0 until array.length()) {
            add(parseSymbol(array.getJSONObject(i)))
        }
    }
}

fun parseSymbol(obj: JSONObject): SymbolHistory {
    val bars = readBars(obj.getJSONArray("bars"))
    val rawBars = if (obj.has("raw") && !obj.isNull("raw")) readBars(obj.getJSONArray("raw")) else emptyList()
    return SymbolHistory(
        code = obj.getString("c"),
        name = obj.getString("n"),
        market = obj.getString("m"),
        bars = bars,
        rawBars = rawBars,
    )
}

private fun readBars(barsJson: JSONArray): List<Bar> {
    return buildList {
        for (i in 0 until barsJson.length()) {
            val bar = barsJson.getJSONArray(i)
            add(
                Bar(
                    date = bar.getInt(0),
                    open = bar.getDouble(1),
                    high = bar.getDouble(2),
                    low = bar.getDouble(3),
                    close = bar.getDouble(4),
                    volume = bar.getLong(5),
                ),
            )
        }
    }
}

/** 舊版 docs/flags.json 格式。現在標籤由本機計算，保留給測試與除錯。 */
fun parseFlags(text: String): LoadedFlags {
    val root = JSONObject(text)
    val items = root.optJSONObject("flags") ?: JSONObject()
    val map = buildMap {
        val keys = items.keys()
        while (keys.hasNext()) {
            val code = keys.next()
            val item = items.optJSONObject(code) ?: continue
            val period = item.optJSONArray("p")
            val limit = when (item.optInt("l", 0)) {
                1 -> LimitMove.UP
                -1 -> LimitMove.DOWN
                else -> LimitMove.NONE
            }
            put(
                code,
                RiskFlags(
                    notice = item.optInt("n", 0) == 1,
                    punishStart = period?.let { if (it.isNull(0)) null else it.optInt(0) },
                    punishEnd = period?.let { if (it.length() < 2 || it.isNull(1)) null else it.optInt(1) },
                    punishMeasure = if (item.has("pm")) item.optString("pm") else null,
                    limit = limit,
                ),
            )
        }
    }
    return LoadedFlags(root.optString("asOf"), root.optString("riskAsOf"), map)
}

/** 舊版 docs/chips.json 格式。現在由本機計算，保留給測試與除錯。 */
fun parseChips(text: String): LoadedChips {
    val root = JSONObject(text)
    val chipDays = root.optInt("chipDays", 0)
    val items = root.optJSONObject("items") ?: JSONObject()
    val map = buildMap {
        val keys = items.keys()
        while (keys.hasNext()) {
            val code = keys.next()
            val item = items.optJSONObject(code) ?: continue
            val insti = item.optJSONArray("i")
            val chips = if (insti != null && insti.length() >= 8) {
                ChipSummary(
                    foreign5 = insti.optInt(0),
                    foreign20 = insti.optInt(1),
                    trust5 = insti.optInt(2),
                    trust20 = insti.optInt(3),
                    dealer5 = insti.optInt(4),
                    dealer20 = insti.optInt(5),
                    trustBuyStreak = insti.optInt(6),
                    foreignBuyStreak = insti.optInt(7),
                )
            } else {
                null
            }
            val rev = item.optJSONArray("r")
            val revenue = if (rev != null && rev.length() >= 5) {
                RevenueSummary(
                    month = rev.optInt(0),
                    revenue = rev.optLong(1),
                    yoy = if (rev.isNull(2)) null else rev.optDouble(2),
                    mom = if (rev.isNull(3)) null else rev.optDouble(3),
                    twelveMonthHigh = if (rev.isNull(4)) null else rev.optInt(4) == 1,
                )
            } else {
                null
            }
            put(code, Fundamentals(chips, revenue, chipDays))
        }
    }
    return LoadedChips(
        asOf = root.optString("asOf"),
        chipDays = chipDays,
        chipStart = root.optString("chipStart"),
        chipEnd = root.optString("chipEnd"),
        items = map,
    )
}
