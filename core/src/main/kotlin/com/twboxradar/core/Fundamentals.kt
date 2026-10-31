package com.twboxradar.core

import java.util.Locale
import kotlin.math.abs

/**
 * docs/chips.json 的一檔。三大法人單位是張（買賣超淨額），營收單位是千元。
 * 法人資料從管線開始累積那天起算，chipDays 不足 20 時，20 日欄位只是已累積天數的合計。
 */
data class ChipSummary(
    val foreign5: Int,
    val foreign20: Int,
    val trust5: Int,
    val trust20: Int,
    val dealer5: Int,
    val dealer20: Int,
    val trustBuyStreak: Int,
    val foreignBuyStreak: Int,
)

data class RevenueSummary(
    val month: Int,
    val revenue: Long,
    val yoy: Double?,
    val mom: Double?,
    /** null 表示前 11 個月資料不足，無法判斷。 */
    val twelveMonthHigh: Boolean?,
)

data class Fundamentals(
    val chips: ChipSummary? = null,
    val revenue: RevenueSummary? = null,
    val chipDays: Int = 0,
)

/** 法人買賣超一行，例如「外資 5 日 +1,234 張、20 日 -56 張」。 */
fun describeNet(label: String, five: Int, twenty: Int, chipDays: Int): String {
    val twentyLabel = if (chipDays in 1 until 20) "${chipDays} 日" else "20 日"
    val fiveLabel = if (chipDays in 1 until 5) "${chipDays} 日" else "5 日"
    return "$label $fiveLabel ${signedLots(five)}、$twentyLabel ${signedLots(twenty)}"
}

fun signedLots(value: Int): String {
    val sign = if (value > 0) "+" else if (value < 0) "-" else ""
    return "$sign${String.format(Locale.US, "%,d", abs(value))} 張"
}

fun describeRevenue(revenue: RevenueSummary): String {
    val month = revenue.month.toString()
    val label = if (month.length == 6) "${month.substring(0, 4)}/${month.substring(4)}" else month
    val yoy = revenue.yoy?.let { signedPercent(it) } ?: "—"
    val mom = revenue.mom?.let { signedPercent(it) } ?: "—"
    val high = when (revenue.twelveMonthHigh) {
        true -> "，創 12 個月新高"
        false -> ""
        null -> "，近 12 個月資料不足"
    }
    return "$label 營收 年增 $yoy、月增 $mom$high"
}

fun signedPercent(fraction: Double): String = String.format(Locale.US, "%+.1f%%", fraction * 100.0)
