package com.twboxradar.core.local

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 第一次安裝（本機沒有歷史）時的初始資料來源。逐日直連回補 250 天太慢（每天至少 6 秒、
 * 交易所也可能暫停 IP），所以先一次匯入一份現成的歷史，之後才增量同步。
 *
 * 匯入只補本機還沒有的日子，已有的資料（較新）優先，所以中斷後可以重來。
 * 計畫：之後把初始資料直接打包進 APK（assets/seed/），GitHub 這條只是過渡。
 */
interface SeedSource {
    /** 畫面上顯示的名稱。 */
    val label: String

    /** 這個來源目前能不能用（例如 APK 裡有沒有 assets/seed）。 */
    fun available(): Boolean

    /** 匯入初始資料，回傳匯入的交易日數。失敗丟出例外。 */
    fun seed(store: LocalStore, progress: (String) -> Unit): Int
}

/**
 * 從 GitHub repo 的 data/（管線的原始月檔）匯入一次。
 *
 * 不用 docs/window.json：它只有 180 根「已還原」K 線，沒有成交原價，
 * 本機需要原價加上除權息事件才能在新的除權息日回頭重算還原。
 * data/ 裡正好是需要的原始資料：
 * - data/state.json：已入庫的交易日清單，取最近 250 天。
 * - data/monthly/YYYY-MM.jsonl：每行 [日期, 代號, 名稱, 市場, 開, 高, 低, 收, 股數]，成交原價（約 13 個檔，gzip 後共約 11 MB）。
 * - data/exrights.json：除權息事件（含上市配股明細算好的成交量因子）。
 * - data/revenue.json：每檔最近 15 個月營收，算「創 12 個月新高」需要。
 * - data/chips/YYYYMMDD.json：最近幾天的三大法人（有就匯入）。
 */
class GitHubDataSeed(
    private val http: HttpGet,
    private val base: String = "https://raw.githubusercontent.com/apsmkimo/tw-box-radar/main/data",
) : SeedSource {
    override val label: String = "GitHub 歷史資料（一次性）"

    override fun available(): Boolean = true

    override fun seed(store: LocalStore, progress: (String) -> Unit): Int {
        progress("下載初始歷史清單")
        val state = JSONObject(fetch("$base/state.json"))
        val completed = state.optJSONArray("completed") ?: JSONArray()
        val days = List(completed.length()) { completed.optString(it).toIntOrNull() }
            .filterNotNull()
            .sorted()
            .takeLast(KEEP_TRADING_DAYS)
        if (days.isEmpty()) throw IOException("初始資料沒有交易日")
        val existing = store.tradingDays().toSet()
        val wanted = days.filter { it !in existing }.toSet()
        val months = wanted.map { it / 100 }.distinct().sorted()
        var imported = 0
        for ((index, month) in months.withIndex()) {
            progress("匯入初始歷史 ${index + 1}/${months.size}（${month / 100}/${"%02d".format(month % 100)}）")
            val text = fetch("$base/monthly/${month / 100}-${"%02d".format(month % 100)}.jsonl")
            val byDay = HashMap<Int, MutableList<QuoteRow>>()
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                val row = JSONArray(line)
                val day = row.optInt(0)
                if (day !in wanted) continue
                val code = text(row.opt(1))
                if (!isCommonStock(code)) continue
                byDay.getOrPut(day) { ArrayList(2100) } += QuoteRow(
                    code = code,
                    name = text(row.opt(2)),
                    market = text(row.opt(3)),
                    open = row.optDouble(4),
                    high = row.optDouble(5),
                    low = row.optDouble(6),
                    close = row.optDouble(7),
                    volume = row.optLong(8),
                )
            }
            for (day in byDay.keys.sorted()) {
                // 月檔同一天同一檔若重複，以後面那行為準（store.py 的 SymbolSeries.add）。
                val rows = byDay.getValue(day).associateBy { it.code }.values.toList()
                store.writeDay(day, rows)
                imported++
            }
        }
        val oldest = store.tradingDays().firstOrNull() ?: days.first()

        progress("匯入除權息事件")
        val ex = JSONObject(fetch("$base/exrights.json")).optJSONArray("events") ?: JSONArray()
        val seeded = ArrayList<ExEvent>()
        for (i in 0 until ex.length()) {
            val item = ex.optJSONObject(i) ?: continue
            val date = item.optInt("date")
            val code = item.optString("code")
            val price = item.optDouble("priceFactor", 0.0)
            val volume = item.optDouble("volumeFactor", 0.0)
            if (date <= oldest || code.isEmpty() || price <= 0.0 || volume <= 0.0) continue
            val needs = item.optBoolean("needsDetail", false) && !item.optBoolean("volumeKnown", true)
            seeded += ExEvent(date, code, price, volume, needs)
        }
        // 本機已有的事件較新，覆蓋初始資料。
        store.saveEvents(mergeEvents(seeded, store.events()))

        progress("匯入月營收")
        runCatching {
            val months = JSONObject(fetch("$base/revenue.json")).optJSONObject("months") ?: JSONObject()
            val parsed = HashMap<String, Map<Int, Long>>()
            val codes = months.keys()
            while (codes.hasNext()) {
                val code = codes.next()
                val values = months.optJSONObject(code) ?: continue
                val map = HashMap<Int, Long>()
                val keys = values.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    key.toIntOrNull()?.let { map[it] = values.optLong(key) }
                }
                parsed[code] = map
            }
            store.saveRevenue(mergeRevenue(parsed, store.revenue()))
        }

        progress("匯入三大法人")
        for (day in days.takeLast(CHIP_DAYS)) {
            if (store.readChips(day) != null) continue
            runCatching {
                val response = http.get("$base/chips/%08d.json".format(day), mapOf("User-Agent" to APP_USER_AGENT))
                if (response.ok) {
                    val obj = JSONObject(response.body)
                    val rows = HashMap<String, LongArray>()
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val code = keys.next()
                        val v = obj.optJSONArray(code) ?: continue
                        if (v.length() >= 3) rows[code] = longArrayOf(v.optLong(0), v.optLong(1), v.optLong(2))
                    }
                    if (rows.isNotEmpty()) store.writeChips(day, rows)
                }
            }
        }
        store.prune()
        return imported
    }

    private fun fetch(url: String): String {
        val response = http.get(url, mapOf("User-Agent" to APP_USER_AGENT))
        if (!response.ok) throw IOException("HTTP ${response.code} $url")
        return response.body
    }
}

/**
 * 有可用的初始資料來源就匯入一次。成功後記 seedDone；失敗不丟例外，回傳原因，下次同步再試。
 */
fun runSeed(store: LocalStore, sources: List<SeedSource>, progress: (String) -> Unit): String? {
    val state = store.state()
    if (state.seedDone) return null
    var lastError: String? = null
    for (source in sources) {
        if (!source.available()) continue
        try {
            source.seed(store, progress)
            store.saveState(store.state().copy(seedDone = true, seedSource = source.label))
            return null
        } catch (error: Exception) {
            lastError = "${source.label}匯入失敗（${error.message?.take(80) ?: error.javaClass.simpleName}）"
        }
    }
    return lastError
}
