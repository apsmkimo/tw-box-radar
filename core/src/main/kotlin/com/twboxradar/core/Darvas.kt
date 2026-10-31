package com.twboxradar.core

/**
 * 達瓦斯箱型。規則與 pipeline/twbox/darvas.py 相同，修改時請讓兩邊的手算測試一起通過。
 *
 * 評估今天時只用今天以前的 K 線。箱頂／箱底在回看視窗內以狀態機重建。
 * 突破與跌破一律看收盤價，影線不單獨觸發。
 */

fun upper(bar: Bar, useWicks: Boolean): Double {
    if (useWicks) return bar.high
    return if (bar.open >= bar.close) bar.open else bar.close
}

fun lower(bar: Bar, useWicks: Boolean): Double {
    if (useWicks) return bar.low
    return if (bar.open <= bar.close) bar.open else bar.close
}

fun activeBox(bars: List<Bar>, confirmation: Int, useWicks: Boolean): Box? {
    if (confirmation < 1 || bars.size < confirmation + 2) return null
    var top: Double? = null
    var bottom: Double? = null
    var topI: Int? = null
    var bottomI: Int? = null
    var pendingHigh: Double? = null
    var pendingHighI: Int? = null
    var pendingLow: Double? = null
    var pendingLowI: Int? = null

    for (i in bars.indices) {
        val bar = bars[i]
        val high = upper(bar, useWicks)
        val low = lower(bar, useWicks)
        val close = bar.close
        val topNow = top
        val bottomNow = bottom
        if (topNow != null && bottomNow != null) {
            if (close > topNow || close < bottomNow) {
                top = null
                bottom = null
                topI = null
                bottomI = null
                pendingHigh = high
                pendingHighI = i
                pendingLow = null
                pendingLowI = null
            }
            continue
        }
        if (topNow == null) {
            val pendingHighNow = pendingHigh
            val pendingHighIndex = pendingHighI
            if (pendingHighNow == null || high > pendingHighNow) {
                pendingHigh = high
                pendingHighI = i
            } else if (pendingHighIndex != null && i - pendingHighIndex >= confirmation) {
                top = pendingHighNow
                topI = pendingHighIndex
                val start = pendingHighIndex + 1
                var lowValue = lower(bars[start], useWicks)
                var lowIndex = start
                for (k in (start + 1)..i) {
                    val value = lower(bars[k], useWicks)
                    if (value < lowValue) {
                        lowValue = value
                        lowIndex = k
                    }
                }
                pendingLow = lowValue
                pendingLowI = lowIndex
                if (i - lowIndex >= confirmation && lowValue < pendingHighNow) {
                    bottom = lowValue
                    bottomI = lowIndex
                }
            }
            continue
        }
        if (high > topNow) {
            top = null
            topI = null
            bottom = null
            bottomI = null
            pendingHigh = high
            pendingHighI = i
            pendingLow = null
            pendingLowI = null
            continue
        }
        val pendingLowNow = pendingLow
        val pendingLowIndex = pendingLowI
        if (pendingLowNow == null || low < pendingLowNow) {
            pendingLow = low
            pendingLowI = i
        } else if (pendingLowIndex != null && i - pendingLowIndex >= confirmation && pendingLowNow < topNow) {
            bottom = pendingLowNow
            bottomI = pendingLowIndex
        }
    }
    val finalTop = top
    val finalBottom = bottom
    val finalTopI = topI
    val finalBottomI = bottomI
    if (finalTop != null && finalBottom != null && finalTopI != null && finalBottomI != null && finalBottom < finalTop) {
        return Box(finalTop, finalBottom, bars[finalTopI].date, bars[finalBottomI].date)
    }
    return null
}

fun volumeRatio(prior: List<Bar>, today: Bar, days: Int): Double? {
    if (days < 1 || prior.size < days) return null
    val sample = prior.takeLast(days)
    val average = sample.sumOf { it.volume.toDouble() } / days
    if (average <= 0.0) return null
    return today.volume / average
}

fun evaluate(bars: List<Bar>, params: BoxParams = BoxParams()): Snapshot {
    if (bars.isEmpty()) {
        return Snapshot(BoxStatus.NONE, 0.0, null, null, null, null, false)
    }
    val today = bars.last()
    val minimum = params.confirmationDays * 2 + 2
    if (bars.size < minimum) {
        return Snapshot(BoxStatus.NONE, today.close, null, null, null, null, false)
    }
    val prior = bars.dropLast(1)
    val window = prior.takeLast(params.lookbackDays)
    val box = activeBox(window, params.confirmationDays, params.useWicks)
    val ratio = volumeRatio(prior, today, params.volumeAverageDays)
    val confirmed = ratio != null && ratio >= params.volumeMultiple
    if (box == null || today.close <= 0.0) {
        return Snapshot(BoxStatus.NONE, today.close, box, null, null, ratio, confirmed)
    }
    val distanceTop = (box.top - today.close) / today.close
    val distanceBottom = (today.close - box.bottom) / today.close
    val status = when {
        today.close > box.top -> BoxStatus.BREAKOUT
        today.close < box.bottom -> BoxStatus.BREAKDOWN
        else -> {
            val span = box.top - box.bottom
            val position = if (span > 0) (today.close - box.bottom) / span else 0.0
            if (position <= params.nearRangeFraction) BoxStatus.NEAR_BOTTOM else BoxStatus.INSIDE
        }
    }
    return Snapshot(status, today.close, box, distanceTop, distanceBottom, ratio, confirmed)
}

fun backtest(bars: List<Bar>, params: BoxParams = BoxParams(), rawBars: List<Bar> = emptyList()): BacktestResult {
    val start = params.confirmationDays * 2 + 2
    val limits = if (params.skipLimitUpEntry) limitMoves(bars, rawBars) else emptyList()
    if (bars.size <= start) {
        return BacktestResult(emptyList(), 0.0, null, 0.0, 0.0, 0)
    }
    var equity = 1.0
    var peak = 1.0
    var maxDd = 0.0
    var inPosition = false
    var entryPrice = 0.0
    var entryDate = 0
    var equityAtEntry = 1.0
    var stop = 0.0
    val trades = mutableListOf<Trade>()

    for (i in start until bars.size) {
        val snap = evaluate(bars.subList(0, i + 1), params)
        val close = bars[i].close
        if (!inPosition) {
            val volumeOk = !params.requireVolumeForEntry || snap.volumeConfirmed
            val limitOk = !params.skipLimitUpEntry || limits.getOrNull(i) != LimitMove.UP
            if (snap.status == BoxStatus.BREAKOUT && volumeOk && limitOk && snap.box != null) {
                inPosition = true
                entryPrice = close
                entryDate = bars[i].date
                equityAtEntry = equity
                stop = snap.box.bottom
            }
        } else {
            if (snap.box != null && snap.box.bottom > stop) {
                stop = snap.box.bottom
            }
            if (close < stop) {
                val ret = close / entryPrice - 1.0
                equity = equityAtEntry * (close / entryPrice)
                trades += Trade(entryDate, entryPrice, bars[i].date, close, ret)
                inPosition = false
            }
        }
        val marked = if (inPosition) equityAtEntry * (close / entryPrice) else equity
        if (marked > peak) peak = marked
        if (peak > 0) {
            val drawdown = (peak - marked) / peak
            if (drawdown > maxDd) maxDd = drawdown
        }
    }
    if (inPosition) {
        val close = bars.last().close
        trades += Trade(entryDate, entryPrice, null, close, close / entryPrice - 1.0)
        equity = equityAtEntry * (close / entryPrice)
    }
    val closed = trades.filter { it.exitDate != null }
    val wins = closed.count { (it.returnFraction ?: 0.0) > 0.0 }
    val winRate = if (closed.isEmpty()) null else wins.toDouble() / closed.size
    val buyHold = bars.last().close / bars[start].close - 1.0
    return BacktestResult(trades, equity - 1.0, winRate, maxDd, buyHold, closed.size)
}

fun analyze(symbol: SymbolHistory, params: BoxParams = BoxParams()): ScreenRow {
    val snap = evaluate(symbol.bars, params)
    val box = snap.box
    val span = if (box == null) 0.0 else box.top - box.bottom
    val position = if (box == null || span == 0.0) null else (snap.close - box.bottom) / span
    val quality = boxQuality(symbol.bars, box)
    return ScreenRow(
        code = symbol.code,
        name = symbol.name,
        market = symbol.market,
        status = snap.status,
        close = snap.close,
        top = box?.top,
        bottom = box?.bottom,
        topDate = box?.topDate,
        bottomDate = box?.bottomDate,
        distanceToTop = snap.distanceToTop,
        distanceToBottom = snap.distanceToBottom,
        volumeRatio = snap.volumeRatio,
        volumeConfirmed = snap.volumeConfirmed,
        rangePosition = position,
        boxDays = quality?.days,
        boxWidth = quality?.width,
        volumeContraction = quality?.volumeContraction,
    )
}
