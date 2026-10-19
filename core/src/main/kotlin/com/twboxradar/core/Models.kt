package com.twboxradar.core

enum class BoxStatus {
    NONE,
    INSIDE,
    NEAR_BOTTOM,
    BREAKOUT,
    BREAKDOWN,
}

data class Bar(
    val date: Int,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)

data class BoxParams(
    val lookbackDays: Int = 60,
    val confirmationDays: Int = 3,
    val useWicks: Boolean = true,
    val nearRangeFraction: Double = 0.20,
    val volumeMultiple: Double = 1.5,
    val volumeAverageDays: Int = 20,
    val requireVolumeForEntry: Boolean = false,
)

data class Box(
    val top: Double,
    val bottom: Double,
    val topDate: Int,
    val bottomDate: Int,
)

data class Snapshot(
    val status: BoxStatus,
    val close: Double,
    val box: Box?,
    val distanceToTop: Double?,
    val distanceToBottom: Double?,
    val volumeRatio: Double?,
    val volumeConfirmed: Boolean,
)

data class SymbolHistory(
    val code: String,
    val name: String,
    val market: String,
    val bars: List<Bar>,
)

data class ScreenRow(
    val code: String,
    val name: String,
    val market: String,
    val status: BoxStatus,
    val close: Double,
    val top: Double?,
    val bottom: Double?,
    val topDate: Int?,
    val bottomDate: Int?,
    val distanceToTop: Double?,
    val distanceToBottom: Double?,
    val volumeRatio: Double?,
    val volumeConfirmed: Boolean,
    val rangePosition: Double?,
)

data class Trade(
    val entryDate: Int,
    val entryPrice: Double,
    val exitDate: Int?,
    val exitPrice: Double?,
    val returnFraction: Double?,
)

data class BacktestResult(
    val trades: List<Trade>,
    val totalReturn: Double,
    val winRate: Double?,
    val maxDrawdown: Double,
    val buyHoldReturn: Double,
    val closedTrades: Int,
)

const val DISCLAIMER = "資料僅供參考，不構成投資建議"
