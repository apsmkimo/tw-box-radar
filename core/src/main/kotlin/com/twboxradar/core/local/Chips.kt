package com.twboxradar.core.local

import com.twboxradar.core.ChipSummary
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * 三大法人每日買賣超。規則與 pipeline/twbox/chips.py 相同。
 *
 * 每檔每天存 [外資, 投信, 自營商]，單位是股、買賣超淨額。
 * - 上市 T86：外資＝「外陸資買賣超股數(不含外資自營商)」＋「外資自營商買賣超股數」；
 *   投信＝「投信買賣超股數」；自營商＝「自營商買賣超股數」。
 * - 上櫃 dailyTrade：欄名重複，只看位置：第 10 欄外資合計、第 13 欄投信、第 22 欄自營商合計、
 *   第 23 欄三大法人合計，並以「三者相加＝合計」檢查欄位沒有位移。
 */

private fun chipInt(value: Any?): Long? {
    val raw = text(value).trim().replace(",", "")
    if (raw in setOf("", "--", "-")) return null
    return raw.toDoubleOrNull()?.toLong()
}

/** 回傳 {代號: [外資, 投信, 自營商]}。stat 不是 OK 時回 null。 */
fun parseTwseT86(payload: JSONObject): Map<String, LongArray>? {
    if (text(payload.opt("stat")).uppercase() != "OK") return null
    val fields = fieldList(payload.optJSONArray("fields")).map { it.replace(" ", "") }
    fun index(name: String): Int? = fields.indexOf(name).takeIf { it >= 0 }
    val codeI = index("證券代號")
    val foreignI = index("外陸資買賣超股數(不含外資自營商)")
    val foreignDealerI = index("外資自營商買賣超股數")
    val trustI = index("投信買賣超股數")
    val dealerI = index("自營商買賣超股數")
    if (codeI == null || foreignI == null || trustI == null || dealerI == null) {
        throw IllegalArgumentException("T86 欄位無法辨識：$fields")
    }
    val result = HashMap<String, LongArray>()
    val data = payload.optJSONArray("data") ?: return result
    for (i in 0 until data.length()) {
        val row = data.optJSONArray(i) ?: continue
        val code = text(row.opt(codeI)).trim()
        if (!isCommonStock(code)) continue
        var foreign = chipInt(row.opt(foreignI)) ?: 0L
        if (foreignDealerI != null) foreign += chipInt(row.opt(foreignDealerI)) ?: 0L
        result[code] = longArrayOf(foreign, chipInt(row.opt(trustI)) ?: 0L, chipInt(row.opt(dealerI)) ?: 0L)
    }
    return result
}

/** 櫃買三大法人。沒有資料回 null；欄位位移丟出例外。 */
fun parseTpexInsti(payload: JSONObject): Map<String, LongArray>? {
    val tables = payload.optJSONArray("tables") ?: return null
    if (tables.length() == 0) return null
    val table = tables.optJSONObject(0) ?: return null
    val data = table.optJSONArray("data") ?: return null
    if (data.length() == 0) return null
    val result = HashMap<String, LongArray>()
    for (i in 0 until data.length()) {
        val row = data.optJSONArray(i) ?: continue
        if (row.length() < 24) continue
        val code = text(row.opt(0)).trim()
        if (!isCommonStock(code)) continue
        val foreign = chipInt(row.opt(10)) ?: continue
        val trust = chipInt(row.opt(13)) ?: continue
        val dealer = chipInt(row.opt(22)) ?: continue
        val total = chipInt(row.opt(23)) ?: continue
        if (foreign + trust + dealer != total) throw IllegalArgumentException("櫃買三大法人欄位位移：$code")
        result[code] = longArrayOf(foreign, trust, dealer)
    }
    return result
}

/** 上市與上櫃都拿到才算這天完整（chips.py 的 fetch_chips）。 */
fun combineChips(twse: Map<String, LongArray>?, tpex: Map<String, LongArray>?): Map<String, LongArray>? {
    if (twse.isNullOrEmpty() || tpex.isNullOrEmpty()) return null
    return twse + tpex
}

/**
 * 近 5／20 日買賣超（張）與連買天數。days 依日期由舊到新。
 * 張＝股 ÷ 1000 四捨五入；當天沒出現的股票視為 0；連買是從最新一天往回數淨買超（> 0）的天數。
 */
fun summarizeChips(days: List<Pair<Int, Map<String, LongArray>>>): Map<String, ChipSummary> {
    if (days.isEmpty()) return emptyMap()
    val codes = sortedSetOf<String>()
    days.forEach { codes.addAll(it.second.keys) }
    val last5 = days.takeLast(5)
    val last20 = days.takeLast(20)
    val zero = LongArray(3)
    fun sum(window: List<Pair<Int, Map<String, LongArray>>>, code: String, column: Int): Int =
        lots(window.sumOf { (it.second[code] ?: zero)[column] })
    fun streak(code: String, column: Int): Int {
        var count = 0
        for (index in days.indices.reversed()) {
            if ((days[index].second[code] ?: zero)[column] > 0) count++ else break
        }
        return count
    }
    return codes.associateWith { code ->
        ChipSummary(
            foreign5 = sum(last5, code, 0),
            foreign20 = sum(last20, code, 0),
            trust5 = sum(last5, code, 1),
            trust20 = sum(last20, code, 1),
            dealer5 = sum(last5, code, 2),
            dealer20 = sum(last20, code, 2),
            trustBuyStreak = streak(code, 1),
            foreignBuyStreak = streak(code, 0),
        )
    }
}

/** Python round() 是銀行家捨入；這裡只在剛好 .5 張時可能差 1，對畫面沒有影響。 */
private fun lots(shares: Long): Int = (shares / 1000.0).roundToLong().toInt()
