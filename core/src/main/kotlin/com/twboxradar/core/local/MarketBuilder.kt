package com.twboxradar.core.local

import com.twboxradar.core.Bar
import com.twboxradar.core.Fundamentals
import com.twboxradar.core.LimitMove
import com.twboxradar.core.RiskFlags
import com.twboxradar.core.SymbolHistory
import com.twboxradar.core.limitMove
import kotlin.math.roundToLong

/** 一檔的成交原價歷史。 */
class RawSeries(val code: String, var name: String, var market: String) {
    val bars = ArrayList<Bar>(KEEP_TRADING_DAYS)
}

/**
 * 從本機資料組出 App 用的結構，等同以前 docs/window.json、flags.json、chips.json 的內容。
 *
 * asOf 語意不變：所有股票最後一根 K 線的最大日期（YYYYMMDD 字串）。
 * flags 的漲跌停用成交原價與當天的除權息參考價（publish.py 的 _limit_today），
 * 停牌、最後一根不是 asOf 的股票不標。
 */
class BuiltMarket(
    val asOf: String,
    val series: Map<String, RawSeries>,
    val events: Map<String, List<ExEvent>>,
    val flags: Map<String, RiskFlags>,
    val riskAsOf: String,
    val fundamentals: Map<String, Fundamentals>,
    val chipDays: Int,
    val chipStart: String,
    val chipEnd: String,
    val tradingDays: Int,
) {
    /** 還原價歷史。includeRaw 時另外帶成交原價（只在兩者不同時）。 */
    fun symbol(code: String, includeRaw: Boolean): SymbolHistory? {
        val raw = series[code] ?: return null
        return toHistory(raw, includeRaw)
    }

    /**
     * 全市場清單。為了省記憶體預設不帶 rawBars（和以前的 window.json 一樣），
     * 個股頁再用 symbol(code, includeRaw = true) 取完整資料。
     */
    fun symbols(includeRaw: Boolean = false): List<SymbolHistory> =
        series.keys.sorted().mapNotNull { code -> series[code]?.let { toHistory(it, includeRaw) } }

    private fun toHistory(raw: RawSeries, includeRaw: Boolean): SymbolHistory? {
        if (raw.bars.isEmpty()) return null
        val adjusted = adjustBars(raw.bars, events[raw.code].orEmpty())
        val differs = adjusted !== raw.bars && adjusted != raw.bars
        return SymbolHistory(
            code = raw.code,
            name = raw.name,
            market = raw.market,
            bars = adjusted,
            rawBars = if (includeRaw && differs) raw.bars else emptyList(),
        )
    }
}

/** 由本機資料組裝。沒有任何交易日時回 null。 */
fun buildMarket(store: LocalStore): BuiltMarket? {
    val days = store.tradingDays().takeLast(KEEP_TRADING_DAYS)
    if (days.isEmpty()) return null
    val names = store.names()
    val series = HashMap<String, RawSeries>(2400)
    for (day in days) {
        for (q in store.readDay(day)) {
            val item = series.getOrPut(q.code) {
                val known = names[q.code]
                RawSeries(q.code, known?.first ?: q.code, known?.second ?: q.market)
            }
            item.bars += Bar(day, q.open, q.high, q.low, q.close, q.volume)
        }
    }
    val asOf = series.values.maxOf { it.bars.last().date }
    val events = store.events().groupBy { it.code }

    val limits = HashMap<String, LimitMove>()
    for ((code, item) in series) {
        val move = limitToday(item.bars, events[code].orEmpty())
        if (move != LimitMove.NONE && item.bars.last().date == asOf) limits[code] = move
    }
    val risk = store.risk()
    val flags = buildFlags(risk, limits)

    val chipDays = store.chipDays().filter { it <= asOf }.takeLast(20)
    val chipData = chipDays.mapNotNull { day -> store.readChips(day)?.let { day to it } }
    val chips = summarizeChips(chipData)
    val revenue = summarizeRevenue(store.revenue())
    val fundamentals = HashMap<String, Fundamentals>()
    for (code in series.keys) {
        val c = chips[code]
        val r = revenue[code]
        if (c != null || r != null) fundamentals[code] = Fundamentals(c, r, chipData.size)
    }
    return BuiltMarket(
        asOf = "%08d".format(asOf),
        series = series,
        events = events,
        flags = flags,
        riskAsOf = risk?.asOf?.takeIf { it > 0 }?.let { "%08d".format(it) } ?: "",
        fundamentals = fundamentals,
        chipDays = chipData.size,
        chipStart = chipData.firstOrNull()?.first?.let { "%08d".format(it) } ?: "",
        chipEnd = chipData.lastOrNull()?.first?.let { "%08d".format(it) } ?: "",
        tradingDays = days.size,
    )
}

/** 最後一根是否漲停／跌停。參考價＝前收盤 × 當天生效的除權息價格因子（limits.py 的 reference_price）。 */
fun limitToday(raw: List<Bar>, events: List<ExEvent>): LimitMove {
    if (raw.size < 2) return LimitMove.NONE
    val prev = raw[raw.size - 2]
    val today = raw.last()
    var reference = prev.close
    for (event in events) {
        if (event.date == today.date && event.priceFactor > 0.0) reference *= event.priceFactor
    }
    reference = (reference * 100.0).roundToLong() / 100.0
    return limitMove(reference, today.close, today.high, today.low)
}

/** 等同 publish.py 的 build_flags，只列出有標籤的代號。 */
fun buildFlags(risk: RiskSnapshot?, limits: Map<String, LimitMove>): Map<String, RiskFlags> {
    val flags = HashMap<String, RiskFlags>()
    risk?.notice?.forEach { code -> flags[code] = (flags[code] ?: RiskFlags()).copy(notice = true) }
    risk?.punish?.forEach { (code, item) ->
        flags[code] = (flags[code] ?: RiskFlags()).copy(punishStart = item.start, punishEnd = item.end, punishMeasure = item.measure)
    }
    limits.forEach { (code, move) -> flags[code] = (flags[code] ?: RiskFlags()).copy(limit = move) }
    return flags
}
