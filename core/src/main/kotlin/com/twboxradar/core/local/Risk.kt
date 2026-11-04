package com.twboxradar.core.local

import org.json.JSONArray
import org.json.JSONObject

/**
 * 注意股與處置股。規則與 pipeline/twbox/risk.py 相同。
 *
 * - 上市注意（twse_notice）可指定日期。
 * - 上市處置（twse_punish）、上櫃注意（tpex_warning）、上櫃處置（tpex_disposal）是當下快照，
 *   只能用最新一份。上櫃注意的日期對不上資料日時不採用。
 * 已結束的處置略過；已公告、下個交易日才開始的保留，App 顯示起迄日。
 */
data class PunishItem(val start: Int?, val end: Int, val measure: String)

/** 注意／處置清單。sources 每個來源是 ok／error／stale（沿用同一天上次成功的結果）。 */
data class RiskSnapshot(
    val asOf: Int,
    val notice: Set<String>,
    val punish: Map<String, PunishItem>,
    val sources: Map<String, String>,
)

private val ROC_DATE = Regex("(\\d{2,3})\\s*[/.\\-年]?\\s*(\\d{1,2})\\s*[/.\\-月]?\\s*(\\d{1,2})")

/** 民國日期轉 YYYYMMDD。接受 1151030、115/10/30、115.10.30、115年10月30日。 */
fun parseRoc(raw: String): Int? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    val (year, month, day) = if (value.all { it.isDigit() } && value.length in 6..7) {
        Triple(value.dropLast(4).toInt(), value.takeLast(4).take(2).toInt(), value.takeLast(2).toInt())
    } else {
        val match = ROC_DATE.find(value) ?: return null
        Triple(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
    }
    if (month !in 1..12 || day !in 1..31 || year <= 0) return null
    return (year + 1911) * 10000 + month * 100 + day
}

/** 處置期間「115/10/28～115/11/03」或「1151030~1151105」。 */
fun parsePeriod(raw: String): Pair<Int?, Int?> {
    val value = raw.replace("～", "~").replace("至", "~").replace("－", "~")
    val parts = value.split("~").filter { it.isNotBlank() }
    if (parts.size < 2) return parseRoc(value) to null
    return parseRoc(parts[0]) to parseRoc(parts[1])
}

/** rwd 版上市注意股。stat 不是 OK（當天沒有注意股）時回空集合。 */
fun parseTwseNotice(payload: JSONObject): Set<String> {
    if (text(payload.opt("stat")).uppercase() != "OK") return emptySet()
    val fields = fieldList(payload.optJSONArray("fields"))
    val codeI = fields.indexOf("證券代號").takeIf { it >= 0 } ?: 1
    val data = payload.optJSONArray("data") ?: return emptySet()
    val codes = HashSet<String>()
    for (i in 0 until data.length()) {
        val row = data.optJSONArray(i) ?: continue
        if (row.length() > codeI) {
            val code = text(row.opt(codeI)).trim()
            if (isCommonStock(code)) codes += code
        }
    }
    return codes
}

fun parseTwsePunish(payload: JSONArray, asOf: Int): Map<String, PunishItem> {
    val result = HashMap<String, PunishItem>()
    for (i in 0 until payload.length()) {
        val item = payload.optJSONObject(i) ?: continue
        val code = text(item.opt("Code")).trim()
        if (!isCommonStock(code)) continue
        val (start, end) = parsePeriod(text(item.opt("DispositionPeriod")))
        putPunish(result, code, start, end, text(item.opt("DispositionMeasures")).trim(), asOf)
    }
    return result
}

/** 上櫃注意只有最新一天。最新日期不是 asOf 時回 null（這份不能用）。 */
fun parseTpexWarning(payload: JSONArray, asOf: Int): Set<String>? {
    val codes = HashSet<String>()
    var latest: Int? = null
    for (i in 0 until payload.length()) {
        val item = payload.optJSONObject(i) ?: continue
        val day = parseRoc(text(item.opt("Date")))
        if (day != null && (latest == null || day > latest)) latest = day
        val code = text(item.opt("SecuritiesCompanyCode")).trim()
        if (isCommonStock(code) && day == asOf) codes += code
    }
    if (latest != null && latest != asOf) return null
    return codes
}

fun parseTpexDisposal(payload: JSONArray, asOf: Int): Map<String, PunishItem> {
    val result = HashMap<String, PunishItem>()
    val measure = Regex("第[一二三四五六七八九十]次處置")
    for (i in 0 until payload.length()) {
        val item = payload.optJSONObject(i) ?: continue
        val code = text(item.opt("SecuritiesCompanyCode")).trim()
        if (!isCommonStock(code)) continue
        val (start, end) = parsePeriod(text(item.opt("DispositionPeriod")))
        val label = measure.find(text(item.opt("DisposalCondition")))?.value ?: "處置"
        putPunish(result, code, start, end, label, asOf)
    }
    return result
}

private fun putPunish(result: MutableMap<String, PunishItem>, code: String, start: Int?, end: Int?, measure: String, asOf: Int) {
    if (end == null || end < asOf) return
    val previous = result[code]
    if (previous != null && previous.end >= end) return
    result[code] = PunishItem(start, end, measure.ifEmpty { "處置" })
}

/**
 * 四個來源各自解析，失敗的來源標 error。before 是同一個 asOf 上次的結果時，
 * 這次失敗的來源沿用上次成功的內容並標 stale（risk.py 的 _keep_previous）。
 */
fun buildRiskSnapshot(
    asOf: Int,
    twseNotice: JSONObject?,
    twsePunish: JSONArray?,
    tpexWarning: JSONArray?,
    tpexDisposal: JSONArray?,
    before: RiskSnapshot?,
): RiskSnapshot {
    val sources = linkedMapOf<String, String>()
    val notice = HashSet<String>()
    val punish = HashMap<String, PunishItem>()
    val twseN = twseNotice?.let { runCatching { parseTwseNotice(it) }.getOrNull() }
    sources["twseNotice"] = if (twseN != null) "ok" else "error"
    twseN?.let { notice += it }
    val twseP = twsePunish?.let { runCatching { parseTwsePunish(it, asOf) }.getOrNull() }
    sources["twsePunish"] = if (twseP != null) "ok" else "error"
    twseP?.let { punish.putAll(it) }
    val tpexN = tpexWarning?.let { runCatching { parseTpexWarning(it, asOf) }.getOrNull() }
    sources["tpexNotice"] = if (tpexN != null) "ok" else "error"
    tpexN?.let { notice += it }
    val tpexP = tpexDisposal?.let { runCatching { parseTpexDisposal(it, asOf) }.getOrNull() }
    sources["tpexPunish"] = if (tpexP != null) "ok" else "error"
    tpexP?.let { punish.putAll(it) }
    if (before != null && before.asOf == asOf) {
        for (key in listOf("twseNotice", "tpexNotice")) {
            if (sources[key] != "ok" && before.sources[key] == "ok") {
                // 注意股不分市場存，失敗時整份沿用舊的，寧可多標也不漏標。
                notice += before.notice
                sources[key] = "stale"
            }
        }
        for (key in listOf("twsePunish", "tpexPunish")) {
            if (sources[key] != "ok" && before.sources[key] == "ok") {
                before.punish.forEach { (code, item) -> punish.putIfAbsent(code, item) }
                sources[key] = "stale"
            }
        }
    }
    return RiskSnapshot(asOf, notice, punish, sources)
}
