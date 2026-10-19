package com.twboxradar.core

import kotlin.math.sqrt

data class MacdSeries(
    val line: List<Double?>,
    val signal: List<Double?>,
    val histogram: List<Double?>,
)

data class BollingerSeries(
    val mid: List<Double?>,
    val upper: List<Double?>,
    val lower: List<Double?>,
)

data class StochasticSeries(
    val k: List<Double?>,
    val d: List<Double?>,
)

fun sma(values: List<Double>, period: Int): List<Double?> {
    if (period < 1) return List(values.size) { null }
    val out = MutableList<Double?>(values.size) { null }
    var sum = 0.0
    for (i in values.indices) {
        sum += values[i]
        if (i >= period) sum -= values[i - period]
        if (i >= period - 1) out[i] = sum / period
    }
    return out
}

fun ema(values: List<Double>, period: Int): List<Double?> {
    if (period < 1 || values.size < period) return List(values.size) { null }
    val out = MutableList<Double?>(values.size) { null }
    val seed = values.take(period).sum() / period
    var previous = seed
    out[period - 1] = seed
    val alpha = 2.0 / (period + 1)
    for (i in period until values.size) {
        previous = values[i] * alpha + previous * (1 - alpha)
        out[i] = previous
    }
    return out
}

fun rsi(closes: List<Double>, period: Int = 14): List<Double?> {
    val out = MutableList<Double?>(closes.size) { null }
    if (period < 1 || closes.size <= period) return out
    var gain = 0.0
    var loss = 0.0
    for (i in 1..period) {
        val change = closes[i] - closes[i - 1]
        if (change >= 0) gain += change else loss -= change
    }
    var avgGain = gain / period
    var avgLoss = loss / period
    out[period] = rsiValue(avgGain, avgLoss)
    for (i in (period + 1) until closes.size) {
        val change = closes[i] - closes[i - 1]
        val up = if (change > 0) change else 0.0
        val down = if (change < 0) -change else 0.0
        avgGain = (avgGain * (period - 1) + up) / period
        avgLoss = (avgLoss * (period - 1) + down) / period
        out[i] = rsiValue(avgGain, avgLoss)
    }
    return out
}

private fun rsiValue(avgGain: Double, avgLoss: Double): Double {
    if (avgLoss == 0.0) return 100.0
    val rs = avgGain / avgLoss
    return 100.0 - 100.0 / (1.0 + rs)
}

/** 台灣慣用 KD：RSV 週期 9，K、D 以 1/3 平滑，初值 50。 */
fun stochastic(bars: List<Bar>, period: Int = 9): StochasticSeries {
    val kOut = MutableList<Double?>(bars.size) { null }
    val dOut = MutableList<Double?>(bars.size) { null }
    if (period < 1) return StochasticSeries(kOut, dOut)
    var k = 50.0
    var d = 50.0
    for (i in bars.indices) {
        if (i < period - 1) continue
        val window = bars.subList(i - period + 1, i + 1)
        val highest = window.maxOf { it.high }
        val lowest = window.minOf { it.low }
        val rsv = if (highest == lowest) 50.0 else (bars[i].close - lowest) / (highest - lowest) * 100.0
        k = (2.0 * k + rsv) / 3.0
        d = (2.0 * d + k) / 3.0
        kOut[i] = k
        dOut[i] = d
    }
    return StochasticSeries(kOut, dOut)
}

fun macd(closes: List<Double>, fast: Int = 12, slow: Int = 26, signalPeriod: Int = 9): MacdSeries {
    val fastEma = ema(closes, fast)
    val slowEma = ema(closes, slow)
    val line = MutableList<Double?>(closes.size) { null }
    val signal = MutableList<Double?>(closes.size) { null }
    val histogram = MutableList<Double?>(closes.size) { null }
    val macdValues = mutableListOf<Double>()
    val macdIndex = mutableListOf<Int>()
    for (i in closes.indices) {
        val f = fastEma[i]
        val s = slowEma[i]
        if (f != null && s != null) {
            val value = f - s
            line[i] = value
            macdValues += value
            macdIndex += i
        }
    }
    val signalEma = ema(macdValues, signalPeriod)
    for (j in signalEma.indices) {
        val value = signalEma[j] ?: continue
        val index = macdIndex[j]
        signal[index] = value
        val macdLine = line[index]
        if (macdLine != null) histogram[index] = macdLine - value
    }
    return MacdSeries(line, signal, histogram)
}

/** 布林通道。標準差用母體標準差（除以 n）。 */
fun bollinger(closes: List<Double>, period: Int = 20, width: Double = 2.0): BollingerSeries {
    val mid = sma(closes, period)
    val upper = MutableList<Double?>(closes.size) { null }
    val lower = MutableList<Double?>(closes.size) { null }
    for (i in closes.indices) {
        val mean = mid[i] ?: continue
        val start = i - period + 1
        var variance = 0.0
        for (k in start..i) {
            val diff = closes[k] - mean
            variance += diff * diff
        }
        val sd = sqrt(variance / period)
        upper[i] = mean + width * sd
        lower[i] = mean - width * sd
    }
    return BollingerSeries(mid, upper, lower)
}

fun crossedAbove(fast: List<Double?>, slow: List<Double?>): Boolean {
    if (fast.size < 2 || slow.size < 2) return false
    val f0 = fast[fast.lastIndex - 1] ?: return false
    val s0 = slow[slow.lastIndex - 1] ?: return false
    val f1 = fast.last() ?: return false
    val s1 = slow.last() ?: return false
    return f0 <= s0 && f1 > s1
}

fun crossedBelow(fast: List<Double?>, slow: List<Double?>): Boolean {
    if (fast.size < 2 || slow.size < 2) return false
    val f0 = fast[fast.lastIndex - 1] ?: return false
    val s0 = slow[slow.lastIndex - 1] ?: return false
    val f1 = fast.last() ?: return false
    val s1 = slow.last() ?: return false
    return f0 >= s0 && f1 < s1
}
