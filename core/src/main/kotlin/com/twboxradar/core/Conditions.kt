package com.twboxradar.core

import java.util.Locale

sealed class Condition {
    data object Breakout : Condition()
    data object InsideBox : Condition()
    data object NearBottom : Condition()
    data object Breakdown : Condition()
    data class CloseAboveMa(val period: Int) : Condition()
    data class CloseBelowMa(val period: Int) : Condition()
    data class MaGoldenCross(val fast: Int, val slow: Int) : Condition()
    data class MaDeathCross(val fast: Int, val slow: Int) : Condition()
    data object KdGoldenCross : Condition()
    data object KdDeathCross : Condition()
    data object MacdGoldenCross : Condition()
    data object MacdDeathCross : Condition()
    data class RsiAbove(val level: Double) : Condition()
    data class RsiBelow(val level: Double) : Condition()
    data object VolumeAnomaly : Condition()
    data object CloseAboveBollingerMid : Condition()
    data object CloseAboveBollingerUpper : Condition()
    data object CloseBelowBollingerLower : Condition()
    // 以下需要 docs/chips.json。沒有資料的股票一律不符合。
    data class TrustBuyStreak(val days: Int) : Condition()
    data object ForeignFiveDayNetBuy : Condition()
    data class RevenueYoyAbove(val fraction: Double) : Condition()
    data object RevenueTwelveMonthHigh : Condition()
}

data class ConditionChoice(
    val id: String,
    val label: String,
    val condition: Condition,
)

fun conditionCatalog(): List<ConditionChoice> = listOf(
    ConditionChoice("breakout", "突破箱頂", Condition.Breakout),
    ConditionChoice("inside", "箱型內整理", Condition.InsideBox),
    ConditionChoice("near", "接近箱底", Condition.NearBottom),
    ConditionChoice("breakdown", "跌破箱底", Condition.Breakdown),
    ConditionChoice("ma5", "收盤站上 5 日均線", Condition.CloseAboveMa(5)),
    ConditionChoice("ma20", "收盤站上 20 日均線", Condition.CloseAboveMa(20)),
    ConditionChoice("ma60", "收盤站上 60 日均線", Condition.CloseAboveMa(60)),
    ConditionChoice("below20", "收盤跌破 20 日均線", Condition.CloseBelowMa(20)),
    ConditionChoice("ma_gold", "5 日均線黃金交叉 20 日均線", Condition.MaGoldenCross(5, 20)),
    ConditionChoice("ma_death", "5 日均線死亡交叉 20 日均線", Condition.MaDeathCross(5, 20)),
    ConditionChoice("kd_gold", "KD 黃金交叉", Condition.KdGoldenCross),
    ConditionChoice("kd_death", "KD 死亡交叉", Condition.KdDeathCross),
    ConditionChoice("macd_gold", "MACD 黃金交叉", Condition.MacdGoldenCross),
    ConditionChoice("macd_death", "MACD 死亡交叉", Condition.MacdDeathCross),
    ConditionChoice("rsi_high", "RSI 高於 70", Condition.RsiAbove(70.0)),
    ConditionChoice("rsi_low", "RSI 低於 30", Condition.RsiBelow(30.0)),
    ConditionChoice("volume", "成交量大於設定的均量倍數", Condition.VolumeAnomaly),
    ConditionChoice("bb_mid", "收盤站上布林中軌", Condition.CloseAboveBollingerMid),
    ConditionChoice("bb_up", "收盤突破布林上軌", Condition.CloseAboveBollingerUpper),
    ConditionChoice("bb_dn", "收盤跌破布林下軌", Condition.CloseBelowBollingerLower),
    ConditionChoice("trust3", "投信連買 3 天", Condition.TrustBuyStreak(3)),
    ConditionChoice("trust5", "投信連買 5 天", Condition.TrustBuyStreak(5)),
    ConditionChoice("foreign5", "外資 5 日買超", Condition.ForeignFiveDayNetBuy),
    ConditionChoice("rev_yoy20", "營收年增 > 20%", Condition.RevenueYoyAbove(0.20)),
    ConditionChoice("rev_yoy50", "營收年增 > 50%", Condition.RevenueYoyAbove(0.50)),
    ConditionChoice("rev_high", "營收創 12 個月新高", Condition.RevenueTwelveMonthHigh),
)

fun matchesAll(
    symbol: SymbolHistory,
    conditions: List<Condition>,
    params: BoxParams,
    fundamentals: Fundamentals? = null,
): Boolean {
    if (conditions.isEmpty() || symbol.bars.isEmpty()) return false
    return conditions.all { matches(symbol, it, params, fundamentals) }
}

fun matches(symbol: SymbolHistory, condition: Condition, params: BoxParams, fundamentals: Fundamentals? = null): Boolean {
    val bars = symbol.bars
    val closes = bars.map { it.close }
    val row = analyze(symbol, params)
    return when (condition) {
        Condition.Breakout -> row.status == BoxStatus.BREAKOUT
        Condition.InsideBox -> row.status == BoxStatus.INSIDE
        Condition.NearBottom -> row.status == BoxStatus.NEAR_BOTTOM
        Condition.Breakdown -> row.status == BoxStatus.BREAKDOWN
        is Condition.CloseAboveMa -> {
            val value = sma(closes, condition.period).last() ?: return false
            closes.last() > value
        }
        is Condition.CloseBelowMa -> {
            val value = sma(closes, condition.period).last() ?: return false
            closes.last() < value
        }
        is Condition.MaGoldenCross -> crossedAbove(sma(closes, condition.fast), sma(closes, condition.slow))
        is Condition.MaDeathCross -> crossedBelow(sma(closes, condition.fast), sma(closes, condition.slow))
        Condition.KdGoldenCross -> {
            val kd = stochastic(bars)
            crossedAbove(kd.k, kd.d)
        }
        Condition.KdDeathCross -> {
            val kd = stochastic(bars)
            crossedBelow(kd.k, kd.d)
        }
        Condition.MacdGoldenCross -> {
            val series = macd(closes)
            crossedAbove(series.line, series.signal)
        }
        Condition.MacdDeathCross -> {
            val series = macd(closes)
            crossedBelow(series.line, series.signal)
        }
        is Condition.RsiAbove -> {
            val value = rsi(closes).last() ?: return false
            value > condition.level
        }
        is Condition.RsiBelow -> {
            val value = rsi(closes).last() ?: return false
            value < condition.level
        }
        Condition.VolumeAnomaly -> row.volumeConfirmed
        Condition.CloseAboveBollingerMid -> {
            val mid = bollinger(closes).mid.last() ?: return false
            closes.last() > mid
        }
        Condition.CloseAboveBollingerUpper -> {
            val upper = bollinger(closes).upper.last() ?: return false
            closes.last() > upper
        }
        Condition.CloseBelowBollingerLower -> {
            val lower = bollinger(closes).lower.last() ?: return false
            closes.last() < lower
        }
        is Condition.TrustBuyStreak -> (fundamentals?.chips?.trustBuyStreak ?: 0) >= condition.days
        Condition.ForeignFiveDayNetBuy -> {
            val chips = fundamentals?.chips ?: return false
            (fundamentals?.chipDays ?: 0) >= 5 && chips.foreign5 > 0
        }
        is Condition.RevenueYoyAbove -> {
            val yoy = fundamentals?.revenue?.yoy ?: return false
            yoy > condition.fraction
        }
        Condition.RevenueTwelveMonthHigh -> fundamentals?.revenue?.twelveMonthHigh == true
    }
}

fun describePosition(row: ScreenRow): String {
    val topDistance = row.distanceToTop
    return when (row.status) {
        BoxStatus.NONE -> "尚無有效箱型"
        BoxStatus.BREAKOUT -> "已突破箱頂 ${formatPercent(-(topDistance ?: 0.0))}，可能進入新箱型"
        BoxStatus.BREAKDOWN -> "已跌破箱底 ${formatPercent(-(row.distanceToBottom ?: 0.0))}，箱型被破壞"
        BoxStatus.INSIDE, BoxStatus.NEAR_BOTTOM -> when {
            topDistance == null -> "箱型內"
            topDistance >= 0 -> "距離箱頂還有 ${formatPercent(topDistance)}"
            else -> "已高出箱頂 ${formatPercent(-topDistance)}"
        }
    }
}

fun formatPercent(fraction: Double): String = String.format(Locale.US, "%.1f%%", fraction * 100.0)

fun marketLabel(market: String): String = if (market == "tpex") "上櫃" else "上市"
