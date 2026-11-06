package com.twboxradar.core.local

import com.twboxradar.core.RevenueSummary
import org.json.JSONArray
import kotlin.math.roundToLong

/**
 * 每月營收（千元）。規則與 pipeline/twbox/revenue.py 相同。
 *
 * 上市 t187ap05_L、上櫃 mopsfin_t187ap05_O 只有最新一個月，每筆帶當月、上月、去年當月，
 * 每次都把三個數字合併進本機的 {代號: {YYYYMM: 營收}}，每檔留最近 KEEP_MONTHS 個月。
 * 「創 12 個月新高」需要前 11 個月，所以要靠初始資料或持續累積。
 */
const val REVENUE_KEEP_MONTHS = 15

fun shiftMonth(yyyymm: Int, delta: Int): Int {
    val year = yyyymm / 100
    val month = yyyymm % 100
    val index = year * 12 + (month - 1) + delta
    return Math.floorDiv(index, 12) * 100 + Math.floorMod(index, 12) + 1
}

private fun rocMonth(raw: String): Int? {
    val value = raw.trim()
    if (value.isEmpty() || !value.all { it.isDigit() } || value.length !in 4..5) return null
    val year = value.dropLast(2).toInt() + 1911
    val month = value.takeLast(2).toInt()
    if (month !in 1..12) return null
    return year * 100 + month
}

private fun revenueNumber(value: Any?): Long? {
    val raw = text(value).trim().replace(",", "")
    if (raw.isEmpty() || raw == "-" || raw == "--") return null
    return raw.toDoubleOrNull()?.roundToLong()
}

/** 回傳 {代號: {YYYYMM: 營收}}，含當月、上月、去年當月。 */
fun parseRevenueOpenApi(payload: JSONArray): Map<String, Map<Int, Long>> {
    val result = HashMap<String, Map<Int, Long>>()
    for (i in 0 until payload.length()) {
        val item = payload.optJSONObject(i) ?: continue
        val code = text(item.opt("公司代號")).trim()
        val month = rocMonth(text(item.opt("資料年月"))) ?: continue
        if (!isCommonStock(code)) continue
        val values = linkedMapOf(
            month to revenueNumber(item.opt("營業收入-當月營收")),
            shiftMonth(month, -1) to revenueNumber(item.opt("營業收入-上月營收")),
            shiftMonth(month, -12) to revenueNumber(item.opt("營業收入-去年當月營收")),
        )
        @Suppress("UNCHECKED_CAST")
        result[code] = values.filterValues { it != null } as Map<Int, Long>
    }
    return result
}

/** 合併後每檔只留最近 KEEP_MONTHS 個月。 */
fun mergeRevenue(
    store: Map<String, Map<Int, Long>>,
    parsed: Map<String, Map<Int, Long>>,
): Map<String, Map<Int, Long>> {
    val out = HashMap<String, Map<Int, Long>>(store)
    for ((code, values) in parsed) {
        val merged = HashMap(out[code] ?: emptyMap())
        merged.putAll(values)
        val keep = merged.keys.sorted().takeLast(REVENUE_KEEP_MONTHS)
        out[code] = keep.associateWith { merged.getValue(it) }
    }
    return out
}

/**
 * 每檔最新一個月的摘要。年增率、月增率為小數，基期 ≤ 0 或缺資料時為 null。
 * 創 12 個月新高：當月 ≥ 前 11 個月最大值；前 11 個月缺任何一個月時為 null。
 */
fun summarizeRevenue(store: Map<String, Map<Int, Long>>): Map<String, RevenueSummary> {
    val result = HashMap<String, RevenueSummary>()
    for ((code, values) in store) {
        if (values.isEmpty()) continue
        val month = values.keys.max()
        val current = values[month] ?: continue
        val previous = values[shiftMonth(month, -1)]
        val lastYear = values[shiftMonth(month, -12)]
        val window = (1..11).map { values[shiftMonth(month, -it)] }
        val high = if (window.any { it == null }) null else current >= window.maxOf { it!! }
        result[code] = RevenueSummary(month, current, growth(current, lastYear), growth(current, previous), high)
    }
    return result
}

private fun growth(current: Long, base: Long?): Double? {
    if (base == null || base <= 0) return null
    return (current.toDouble() / base - 1.0).let { (it * 10_000.0).roundToLong() / 10_000.0 }
}
