package com.twboxradar.core.local

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * 證交所、櫃買中心每日行情原文 → 統一列。規則與 pipeline/twbox/parse.py 相同。
 *
 * 只保留普通股：代號為 4 碼數字且不以 0 開頭（排除 ETF、權證、特別股）。
 * 這裡留下官方成交價，除權息還原在組裝歷史時才做（見 ExRights.kt）。
 *
 * 只用 org.json 最基本的 API（Android 內建版本較舊），不用 toList、迭代器等新方法。
 */

private val STOCK_CODE = Regex("^[1-9]\\d{3}$")

enum class FetchStatus { OK, CLOSED, ERROR }

/** 一檔一天的成交價。market 是 twse 或 tpex。 */
data class QuoteRow(
    val code: String,
    val name: String,
    val market: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)

fun isCommonStock(code: String): Boolean = STOCK_CODE.matches(code.trim())

/** JSON 值轉字串。null 與 JSONObject.NULL 都是空字串。 */
internal fun text(value: Any?): String = if (value == null || value == JSONObject.NULL) "" else value.toString()

/** 價格與股數。去千分位；空白、--、X、0 與負數都視為沒有成交。 */
fun parseNumber(value: Any?): Double? {
    val raw = text(value).trim().replace(",", "")
    if (raw in setOf("", "--", "---", "----", "X", "null", "None", "N/A")) return null
    val number = raw.replace(" ", "").toDoubleOrNull() ?: return null
    if (number.isNaN() || number <= 0.0) return null
    return number
}

internal fun round4(value: Double): Double = (value * 10_000.0).roundToLong() / 10_000.0

internal fun fieldList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return List(array.length()) { text(array.opt(it)) }
}

/** 欄名完全相同或以候選字開頭（parse.py 的 _field_index）。 */
internal fun fieldIndexPrefix(fields: List<String>, vararg candidates: String): Int? {
    val normalized = fields.map { it.replace(" ", "").replace("\n", "") }
    for (candidate in candidates) {
        val key = candidate.replace(" ", "")
        val index = normalized.indexOfFirst { it == key || it.startsWith(key) }
        if (index >= 0) return index
    }
    return null
}

/** 欄名包含候選字（exrights.py 的 _field_index）。 */
internal fun fieldIndexContains(fields: List<String>, vararg candidates: String): Int? {
    val normalized = fields.map { it.replace(" ", "").replace("\n", "") }
    for (candidate in candidates) {
        val key = candidate.replace(" ", "")
        val index = normalized.indexOfFirst { it == key || it.contains(key) }
        if (index >= 0) return index
    }
    return null
}

internal fun rowsFromTable(fields: List<String>, data: JSONArray?, market: String): List<QuoteRow> {
    val codeI = fieldIndexPrefix(fields, "證券代號", "代號")
    val nameI = fieldIndexPrefix(fields, "證券名稱", "名稱")
    val openI = fieldIndexPrefix(fields, "開盤價", "開盤")
    val highI = fieldIndexPrefix(fields, "最高價", "最高")
    val lowI = fieldIndexPrefix(fields, "最低價", "最低")
    val closeI = fieldIndexPrefix(fields, "收盤價", "收盤")
    val volumeI = fieldIndexPrefix(fields, "成交股數")
    if (codeI == null || nameI == null || openI == null || highI == null || lowI == null || closeI == null || volumeI == null) {
        throw IllegalArgumentException("無法辨識欄位：$fields")
    }
    val need = maxOf(codeI, nameI, openI, highI, lowI, closeI, volumeI)
    if (data == null) return emptyList()
    val rows = ArrayList<QuoteRow>(data.length())
    for (i in 0 until data.length()) {
        val raw = data.optJSONArray(i) ?: continue
        if (raw.length() <= need) continue
        val code = text(raw.opt(codeI)).trim()
        if (!isCommonStock(code)) continue
        val open = parseNumber(raw.opt(openI)) ?: continue
        val high = parseNumber(raw.opt(highI)) ?: continue
        val low = parseNumber(raw.opt(lowI)) ?: continue
        val close = parseNumber(raw.opt(closeI)) ?: continue
        val volume = parseNumber(raw.opt(volumeI)) ?: continue
        if (high < low) continue
        rows += QuoteRow(
            code = code,
            name = text(raw.opt(nameI)).trim(),
            market = market,
            open = round4(open),
            high = round4(high),
            low = round4(low),
            close = round4(close),
            volume = volume.toLong(),
        )
    }
    return rows
}

/** 證交所 MI_INDEX（ALLBUT0999）。休市回 CLOSED；格式不對回 ERROR。 */
fun parseTwseMiIndex(payload: JSONObject): Pair<FetchStatus, List<QuoteRow>> {
    val stat = text(payload.opt("stat"))
    if (stat != "OK") {
        if (stat.contains("沒有符合條件") || stat.lowercase() in setOf("no data", "nodata")) {
            return FetchStatus.CLOSED to emptyList()
        }
        return FetchStatus.ERROR to emptyList()
    }
    val tables = payload.optJSONArray("tables") ?: return FetchStatus.ERROR to emptyList()
    for (i in 0 until tables.length()) {
        val table = tables.optJSONObject(i) ?: continue
        val fields = fieldList(table.optJSONArray("fields"))
        val joined = fields.joinToString("")
        if (joined.contains("證券代號") && joined.contains("收盤價")) {
            return FetchStatus.OK to rowsFromTable(fields, table.optJSONArray("data"), "twse")
        }
    }
    return FetchStatus.ERROR to emptyList()
}

/** 櫃買 dailyQuotes。totalCount 0 或上櫃家數 0 是休市。 */
fun parseTpexDaily(payload: JSONObject): Pair<FetchStatus, List<QuoteRow>> {
    val tables = payload.optJSONArray("tables")
    if (tables == null || tables.length() == 0) {
        val stat = text(payload.opt("stat"))
        if (stat.isNotEmpty() && stat != "ok" && stat != "OK") return FetchStatus.CLOSED to emptyList()
        return FetchStatus.ERROR to emptyList()
    }
    val table = tables.optJSONObject(0) ?: return FetchStatus.ERROR to emptyList()
    val total = text(table.opt("totalCount"))
    val listed = text(table.opt("listedCompanies"))
    if (total == "0" || listed == "0" || listed == "0家") return FetchStatus.CLOSED to emptyList()
    val rows = rowsFromTable(fieldList(table.optJSONArray("fields")), table.optJSONArray("data"), "tpex")
    if (rows.isEmpty()) return FetchStatus.CLOSED to emptyList()
    return FetchStatus.OK to rows
}

/**
 * 上市與上櫃合成一天（fetch.py 的 fetch_day）。
 * 兩邊都休市才是休市；只有一邊有資料時當成失敗，下次再試，避免漏抓。
 */
fun combineDay(
    twse: Pair<FetchStatus, List<QuoteRow>>,
    tpex: Pair<FetchStatus, List<QuoteRow>>,
): Pair<FetchStatus, List<QuoteRow>> = when {
    twse.first == FetchStatus.ERROR || tpex.first == FetchStatus.ERROR -> FetchStatus.ERROR to emptyList()
    twse.first == FetchStatus.CLOSED && tpex.first == FetchStatus.CLOSED -> FetchStatus.CLOSED to emptyList()
    twse.first != FetchStatus.OK || tpex.first != FetchStatus.OK -> FetchStatus.ERROR to emptyList()
    else -> FetchStatus.OK to (twse.second + tpex.second)
}

/** 原文去 BOM 後解析。不是 JSON（例如被擋的 HTML 頁）時丟出例外。 */
fun parseJsonValue(body: String): Any {
    val trimmed = body.removePrefix("\uFEFF").trimStart()
    return when {
        trimmed.startsWith("{") -> JSONObject(trimmed)
        trimmed.startsWith("[") -> JSONArray(trimmed)
        else -> throw IllegalStateException("不是 JSON：${trimmed.take(60)}")
    }
}
