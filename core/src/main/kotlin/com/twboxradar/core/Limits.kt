package com.twboxradar.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * 漲跌停與注意／處置標籤。規則與 pipeline/twbox/limits.py 相同。
 *
 * 漲停價＝參考價 × 1.1 依升降單位無條件捨去；跌停價＝參考價 × 0.9 無條件進位。
 * 收盤價達到漲停價（跌停價）記為漲停（跌停）。若規則與交易所算法有出入，
 * 再以「收盤＝最高（最低）且漲跌幅達 9.5%」備援。
 *
 * window.json 是向後還原價。除權息日以外，相鄰兩根還原價的比值等於成交價的比值；
 * 除權息當天，還原後的前收盤剛好等於官方參考價。所以有成交原價時，
 * 參考價＝今天原價收盤 × 前一根還原收盤 ／ 今天還原收盤，平日就是前一根原價收盤。
 * 沒有原價時（視窗檔），直接用還原價計算，升降單位可能對不上，靠 9.5% 備援。
 */

enum class LimitMove { NONE, UP, DOWN }

private const val EPS = 1e-6
private const val FALLBACK_FRACTION = 0.095

fun tickSize(price: Double): Double = when {
    price < 10.0 -> 0.01
    price < 50.0 -> 0.05
    price < 100.0 -> 0.1
    price < 500.0 -> 0.5
    price < 1000.0 -> 1.0
    else -> 5.0
}

private fun round2(value: Double): Double = (value * 100.0).roundToLong() / 100.0

private fun round6(value: Double): Double = (value * 1_000_000.0).roundToLong() / 1_000_000.0

fun limitUpPrice(reference: Double): Double {
    val raw = reference * 1.1
    val tick = tickSize(raw)
    return round2(floor(round6(raw / tick)) * tick)
}

fun limitDownPrice(reference: Double): Double {
    val raw = reference * 0.9
    val tick = tickSize(raw)
    return round2(ceil(round6(raw / tick)) * tick)
}

fun limitMove(reference: Double, close: Double, high: Double, low: Double): LimitMove {
    if (reference <= 0.0 || close <= 0.0) return LimitMove.NONE
    val change = close / reference - 1.0
    return when {
        close >= limitUpPrice(reference) - EPS -> LimitMove.UP
        close <= limitDownPrice(reference) + EPS -> LimitMove.DOWN
        abs(close - high) < EPS && change >= FALLBACK_FRACTION - EPS -> LimitMove.UP
        abs(close - low) < EPS && change <= -FALLBACK_FRACTION + EPS -> LimitMove.DOWN
        else -> LimitMove.NONE
    }
}

/**
 * 每一根 K 線的漲跌停。adjusted 是還原價；raw 是同日期的成交原價（可為空）。
 * 回傳與 adjusted 等長的清單，第一根一定是 NONE。
 */
fun limitMoves(adjusted: List<Bar>, raw: List<Bar> = emptyList()): List<LimitMove> {
    if (adjusted.isEmpty()) return emptyList()
    val rawByDate = if (raw.isEmpty()) emptyMap() else raw.associateBy { it.date }
    val out = ArrayList<LimitMove>(adjusted.size)
    out += LimitMove.NONE
    for (i in 1 until adjusted.size) {
        val prev = adjusted[i - 1]
        val today = adjusted[i]
        val rawToday = rawByDate[today.date] ?: today
        if (today.close <= 0.0) {
            out += LimitMove.NONE
            continue
        }
        val reference = round2(rawToday.close * prev.close / today.close)
        out += limitMove(reference, rawToday.close, rawToday.high, rawToday.low)
    }
    return out
}

fun lastLimitMove(symbol: SymbolHistory): LimitMove = limitMoves(symbol.bars, symbol.rawBars).lastOrNull() ?: LimitMove.NONE

/** docs/flags.json 的一筆。punishStart／punishEnd 為 YYYYMMDD。 */
data class RiskFlags(
    val notice: Boolean = false,
    val punishStart: Int? = null,
    val punishEnd: Int? = null,
    val punishMeasure: String? = null,
    val limit: LimitMove = LimitMove.NONE,
)

/**
 * 列表與個股頁的小標籤。全部是客觀狀態，不含買賣用語。
 * 處置附上迄日（例如「處置至 11/05」）。limitFallback 是 App 用 K 線自己算的漲跌停，
 * 只在 flags.json 沒有給漲跌停時採用。
 */
fun riskChips(flags: RiskFlags?, limitFallback: LimitMove = LimitMove.NONE): List<String> {
    val chips = mutableListOf<String>()
    if (flags?.notice == true) chips += "注意"
    val end = flags?.punishEnd
    if (end != null) {
        val text = end.toString().padStart(8, '0')
        chips += "處置至 ${text.substring(4, 6)}/${text.substring(6, 8)}"
    }
    val limit = flags?.limit?.takeIf { it != LimitMove.NONE } ?: limitFallback
    when (limit) {
        LimitMove.UP -> chips += "漲停"
        LimitMove.DOWN -> chips += "跌停"
        LimitMove.NONE -> Unit
    }
    return chips
}
