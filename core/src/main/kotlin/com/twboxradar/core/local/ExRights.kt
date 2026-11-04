package com.twboxradar.core.local

import com.twboxradar.core.Bar
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * 除權息事件與向後還原。規則與 pipeline/twbox/adjust.py、exrights.py 相同。
 *
 * 價格因子用官方「除權息參考價／除權息前收盤價」，不自行重算。
 * 成交量因子＝1＋每千股無償配股／1000＋按持股比例每千股認購／1000；純除息為 1。
 * 上櫃的配股與認購欄就在計算表裡；上市含「權」時要再查 TWT49UDetail 明細（needsDetail）。
 *
 * 還原：最新一根維持成交價；更早的開高低收乘上「之後每一次」的價格因子，
 * 除權息當天已是新價格，不乘該次。晚於最後一根的事件先不套。
 */
data class ExEvent(
    val date: Int,
    val code: String,
    val priceFactor: Double,
    val volumeFactor: Double = 1.0,
    /** 上市含「權」且還沒查到明細。成交量因子暫時是 1，之後同步再補。 */
    val needsDetail: Boolean = false,
)

fun adjustBars(bars: List<Bar>, events: List<ExEvent>): List<Bar> {
    if (bars.isEmpty()) return bars
    val lastDate = bars.last().date
    val usable = events
        .filter {
            it.priceFactor > 0.0 && it.volumeFactor > 0.0 &&
                it.priceFactor.isFinite() && it.volumeFactor.isFinite() && it.date <= lastDate
        }
        .sortedBy { it.date }
    if (usable.isEmpty()) return bars
    var priceFactor = 1.0
    var volumeFactor = 1.0
    var index = usable.size - 1
    val out = arrayOfNulls<Bar>(bars.size)
    for (i in bars.indices.reversed()) {
        val bar = bars[i]
        while (index >= 0 && usable[index].date > bar.date) {
            priceFactor *= usable[index].priceFactor
            volumeFactor *= usable[index].volumeFactor
            index--
        }
        out[i] = if (priceFactor == 1.0 && volumeFactor == 1.0) {
            bar
        } else {
            Bar(
                date = bar.date,
                open = round4(bar.open * priceFactor),
                high = round4(bar.high * priceFactor),
                low = round4(bar.low * priceFactor),
                close = round4(bar.close * priceFactor),
                volume = (bar.volume * volumeFactor).roundToLong(),
            )
        }
    }
    @Suppress("UNCHECKED_CAST")
    return (out as Array<Bar>).asList()
}

/** 民國或西元日期（113年06月03日、113/06/04）轉 YYYYMMDD。 */
fun parseRocDate(text: String): Int? {
    val digits = Regex("\\d+").findAll(text).map { it.value.toInt() }.toList()
    if (digits.size < 3) return null
    var year = digits[0]
    val month = digits[1]
    val day = digits[2]
    if (year < 1911) year += 1911
    if (month !in 1..12 || day !in 1..31) return null
    return year * 10000 + month * 100 + day
}

/** 「140 股」→ 0.14（每股比率）。空白或 -- 是 0。 */
fun perThousand(value: Any?): Double {
    val raw = text(value).replace(",", "").replace("股", "").replace("元／股", "").replace("元/股", "").trim()
    if (raw in setOf("", "-", "--", "---", "----")) return 0.0
    return (raw.toDoubleOrNull() ?: return 0.0) / 1000.0
}

private fun tablesOf(payload: JSONObject): List<JSONObject> {
    val tables = payload.optJSONArray("tables")
    if (tables != null && tables.length() > 0) {
        return List(tables.length()) { tables.optJSONObject(it) }.filterNotNull()
    }
    return listOf(payload)
}

private fun cell(row: JSONArray, index: Int?): Any? = if (index == null || index >= row.length()) null else row.opt(index)

/** 證交所 TWT49U 計算結果表。含「權」的事件標 needsDetail。 */
fun parseTwseExSummary(payload: JSONObject): List<ExEvent> {
    val events = mutableListOf<ExEvent>()
    for (table in tablesOf(payload)) {
        val fields = fieldList(table.optJSONArray("fields"))
        val dateI = fieldIndexContains(fields, "資料日期")
        val codeI = fieldIndexContains(fields, "股票代號")
        val prevI = fieldIndexContains(fields, "除權息前收盤價")
        val refI = fieldIndexContains(fields, "除權息參考價")
        val kindI = fieldIndexContains(fields, "權/息")
        if (dateI == null || codeI == null || prevI == null || refI == null || kindI == null) continue
        val data = table.optJSONArray("data") ?: continue
        for (i in 0 until data.length()) {
            val row = data.optJSONArray(i) ?: continue
            val code = text(cell(row, codeI)).trim()
            if (!isCommonStock(code)) continue
            val date = parseRocDate(text(cell(row, dateI))) ?: continue
            val prev = parseNumber(cell(row, prevI)) ?: continue
            val ref = parseNumber(cell(row, refI)) ?: continue
            val needsDetail = text(cell(row, kindI)).contains("權")
            events += ExEvent(date, code, ref / prev, 1.0, needsDetail)
        }
    }
    return events
}

/** TWT49UDetail：回傳（無償配股率, 現金增資認購率），都是每股比率。 */
fun parseTwseExDetail(payload: JSONObject): Pair<Double, Double> {
    for (table in tablesOf(payload)) {
        val fields = fieldList(table.optJSONArray("fields"))
        val data = table.optJSONArray("data") ?: continue
        val row = data.optJSONArray(0) ?: continue
        val stockI = fieldIndexContains(fields, "每千股無償配股", "每仟股無償配股")
        val cashI = fieldIndexContains(fields, "按股東持股比例每千股認購", "按持股比例每千股認購", "按持股比例仟股認購")
        val stock = if (stockI != null) perThousand(cell(row, stockI)) else 0.0
        val cash = if (cashI != null) perThousand(cell(row, cashI)) else 0.0
        return maxOf(stock, 0.0) to maxOf(cash, 0.0)
    }
    return 0.0 to 0.0
}

/** 櫃買除權息計算表。配股與認購欄就在表裡，不必查明細。 */
fun parseTpexExRights(payload: JSONObject): List<ExEvent> {
    val events = mutableListOf<ExEvent>()
    for (table in tablesOf(payload)) {
        val fields = fieldList(table.optJSONArray("fields"))
        val dateI = fieldIndexContains(fields, "除權息日期")
        val codeI = fieldIndexContains(fields, "代號", "股票代號")
        val prevI = fieldIndexContains(fields, "除權息前收盤價")
        val refI = fieldIndexContains(fields, "除權息參考價")
        val stockI = fieldIndexContains(fields, "每仟股無償配股", "每千股無償配股")
        val cashI = fieldIndexContains(fields, "按持股比例仟股認購", "按持股比例每千股認購")
        if (dateI == null || codeI == null || prevI == null || refI == null) continue
        val data = table.optJSONArray("data") ?: continue
        for (i in 0 until data.length()) {
            val row = data.optJSONArray(i) ?: continue
            val code = text(cell(row, codeI)).trim()
            if (!isCommonStock(code)) continue
            val date = parseRocDate(text(cell(row, dateI))) ?: continue
            val prev = parseNumber(cell(row, prevI)) ?: continue
            val ref = parseNumber(cell(row, refI)) ?: continue
            val stock = if (stockI != null) maxOf(perThousand(cell(row, stockI)), 0.0) else 0.0
            val cash = if (cashI != null) maxOf(perThousand(cell(row, cashI)), 0.0) else 0.0
            events += ExEvent(date, code, ref / prev, 1.0 + stock + cash, false)
        }
    }
    return events
}

/**
 * 合併事件，鍵是（代號, 日期）。新資料還沒有明細、舊的已有明細時，保留舊的成交量因子。
 */
fun mergeEvents(existing: Collection<ExEvent>, incoming: Collection<ExEvent>): List<ExEvent> {
    val store = LinkedHashMap<Pair<String, Int>, ExEvent>()
    for (event in existing) store[event.code to event.date] = event
    for (event in incoming) {
        val key = event.code to event.date
        val old = store[key]
        store[key] = if (old != null && !old.needsDetail && event.needsDetail) {
            event.copy(volumeFactor = old.volumeFactor, needsDetail = false)
        } else {
            event
        }
    }
    return store.values.sortedWith(compareBy<ExEvent> { it.code }.thenBy { it.date })
}
