package com.twboxradar.data

import android.content.Context
import com.twboxradar.core.Bar
import com.twboxradar.core.ChipSummary
import com.twboxradar.core.Fundamentals
import com.twboxradar.core.RevenueSummary
import com.twboxradar.core.LimitMove
import com.twboxradar.core.RiskFlags
import com.twboxradar.core.PREVIEW_DATA_BRANCH
import com.twboxradar.core.SymbolHistory
import com.twboxradar.core.marketDocsUrl
import com.twboxradar.core.normalizeDataBranch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class LoadedMarket(
    val symbols: List<SymbolHistory>,
    val asOf: String,
    val sample: Boolean,
    val message: String?,
    val sourceUrl: String = "",
)

/** docs/flags.json。asOf 是行情資料日，riskAsOf 是注意／處置清單的日期。 */
data class LoadedFlags(
    val asOf: String = "",
    val riskAsOf: String = "",
    val flags: Map<String, RiskFlags> = emptyMap(),
)

/** docs/chips.json。chipStart～chipEnd 是已累積的法人資料區間。 */
data class LoadedChips(
    val asOf: String = "",
    val chipDays: Int = 0,
    val chipStart: String = "",
    val chipEnd: String = "",
    val items: Map<String, Fundamentals> = emptyMap(),
)

class MarketRepository(private val context: Context) {
    /** 法人與營收。快取規則同 flags.json，失敗就回空的，畫面只是少這兩段。 */
    suspend fun loadChips(force: Boolean, branch: String, asOf: String): LoadedChips =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val file = cacheFile(branch, "chips.json")
            val cached = if (file.exists()) runCatching { parseChips(file.readText()) }.getOrNull() else null
            if (!force && cached != null && cached.asOf == asOf && asOf.isNotEmpty()) {
                return@withContext cached
            }
            try {
                val text = http("${marketDocsUrl(branch)}/chips.json")
                val parsed = parseChips(text)
                file.parentFile?.mkdirs()
                file.writeText(text)
                parsed
            } catch (error: Exception) {
                cached ?: LoadedChips()
            }
        }

    /**
     * 注意／處置／漲跌停標籤。跟 window.json 一樣先比對 asOf 再決定要不要重抓。
     * 下載失敗用快取；連快取都沒有就回空的，畫面只是不顯示標籤。
     */
    suspend fun loadFlags(force: Boolean, branch: String, asOf: String): LoadedFlags =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val file = cacheFile(branch, "flags.json")
            val cached = if (file.exists()) runCatching { parseFlags(file.readText()) }.getOrNull() else null
            if (!force && cached != null && cached.asOf == asOf && asOf.isNotEmpty()) {
                return@withContext cached
            }
            try {
                val text = http("${marketDocsUrl(branch)}/flags.json")
                val parsed = parseFlags(text)
                file.parentFile?.mkdirs()
                file.writeText(text)
                parsed
            } catch (error: Exception) {
                cached ?: LoadedFlags()
            }
        }


    // SMCPKG_SUPPORT>>>CursorXXX
    // suspend fun loadMarket(force: Boolean): LoadedMarket
    // 網址以前寫死 BuildConfig.DATA_BASE_URL（main）。main 還沒合併時會 404，只能看範例。
    suspend fun loadMarket(force: Boolean, branch: String): LoadedMarket = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val base = marketDocsUrl(branch)
        val cacheManifest = cacheFile(branch, "manifest.json")
        val cacheWindow = cacheFile(branch, "window.json")
        try {
            val manifestText = http("$base/manifest.json")
            val manifest = JSONObject(manifestText)
            val asOf = manifest.optString("asOf")
            val cachedAsOf = if (cacheManifest.exists()) JSONObject(cacheManifest.readText()).optString("asOf") else ""
            val windowText = if (!force && cachedAsOf == asOf && cacheWindow.exists()) {
                cacheWindow.readText()
            } else {
                http("$base/window.json").also { body ->
                    cacheManifest.parentFile?.mkdirs()
                    cacheManifest.writeText(manifestText)
                    cacheWindow.writeText(body)
                }
            }
            LoadedMarket(parseWindow(windowText), asOf, sample = false, message = null, sourceUrl = base)
        } catch (error: Exception) {
            val reason = error.message ?: error.javaClass.simpleName
            if (cacheWindow.exists()) {
                val asOf = if (cacheManifest.exists()) JSONObject(cacheManifest.readText()).optString("asOf") else ""
                LoadedMarket(
                    parseWindow(cacheWindow.readText()),
                    asOf,
                    sample = false,
                    message = "更新失敗（$reason）。顯示這個分支上次下載的資料。",
                    sourceUrl = base,
                )
            } else {
                val text = context.assets.open("sample/window.json").bufferedReader().use { it.readText() }
                val hint = if (normalizeDataBranch(branch) == "main") {
                    "main 要等合併才有檔案。到「設定」把資料分支改成 $PREVIEW_DATA_BRANCH，再按重試，就可以先看全市場。"
                } else {
                    "請確認分支名稱後按重試。"
                }
                LoadedMarket(
                    parseWindow(text),
                    JSONObject(text).optString("asOf"),
                    sample = true,
                    message = "連不到 $base/manifest.json（$reason）。目前顯示內建範例。$hint",
                    sourceUrl = base,
                )
            }
        }
    }

    // suspend fun loadHistory(code: String, fallback: SymbolHistory?): SymbolHistory?
    suspend fun loadHistory(code: String, fallback: SymbolHistory?, branch: String): SymbolHistory? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val file = cacheFile(branch, "bars/$code.json")
            try {
                val text = http("${marketDocsUrl(branch)}/bars/$code.json")
                file.parentFile?.mkdirs()
                file.writeText(text)
                parseSymbol(JSONObject(text))
            } catch (error: Exception) {
                when {
                    file.exists() -> parseSymbol(JSONObject(file.readText()))
                    else -> fallback
                }
            }
        }

    /** 只抓 manifest.json（約 1KB）取得資料日。失敗時丟出例外。 */
    suspend fun fetchAsOf(branch: String): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            JSONObject(http("${marketDocsUrl(branch)}/manifest.json")).optString("asOf")
        }

    /**
     * 只下載自選股的單檔歷史（bars/{代號}.json），不下載 16MB 的 window.json。
     * 任何一檔抓不到就丟出例外，讓呼叫端稍後重試，避免用舊快取發出錯誤通知。
     */
    suspend fun loadSymbols(codes: Collection<String>, branch: String): List<SymbolHistory> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            codes.sorted().map { code ->
                val text = http("${marketDocsUrl(branch)}/bars/$code.json")
                val file = cacheFile(branch, "bars/$code.json")
                file.parentFile?.mkdirs()
                file.writeText(text)
                parseSymbol(JSONObject(text))
            }
        }

    private fun cacheFile(branch: String, name: String): File {
        val folder = normalizeDataBranch(branch).replace('/', '_')
        return File(context.filesDir, "market/$folder/$name")
    }
    // SMCPKG_SUPPORT<<<CursorYYY

    private fun http(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 180_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "tw-box-radar-android/0.1")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                // SMCPKG_SUPPORT>>>CursorXXX
                // throw IllegalStateException("HTTP $code")
                throw IllegalStateException("HTTP $code $url")
                // SMCPKG_SUPPORT<<<CursorYYY
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

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
    // SMCPKG_SUPPORT>>>CursorXXX
    // val barsJson = obj.getJSONArray("bars")
    // val bars = buildList { ... 只讀 bars ... }
    // return SymbolHistory(code = obj.getString("c"), name = obj.getString("n"), market = obj.getString("m"), bars = bars)
    val bars = readBars(obj.getJSONArray("bars"))
    val rawBars = if (obj.has("raw") && !obj.isNull("raw")) readBars(obj.getJSONArray("raw")) else emptyList()
    return SymbolHistory(
        code = obj.getString("c"),
        name = obj.getString("n"),
        market = obj.getString("m"),
        bars = bars,
        rawBars = rawBars,
    )
    // SMCPKG_SUPPORT<<<CursorYYY
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
