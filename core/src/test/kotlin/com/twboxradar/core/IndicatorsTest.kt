package com.twboxradar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorsTest {
    @Test
    fun smaAndEmaOfFlatSeries() {
        val values = List(10) { 5.0 }
        assertEquals(5.0, sma(values, 4).last()!!, 1e-9)
        assertEquals(5.0, ema(values, 4).last()!!, 1e-9)
        assertNull(sma(values, 4)[2])
    }

    @Test
    fun rsiHandComputedPeriod2() {
        val closes = listOf(1.0, 2.0, 3.0, 2.0, 4.0)
        val values = rsi(closes, 2)
        assertNull(values[1])
        assertEquals(100.0, values[2]!!, 1e-9)
        assertEquals(50.0, values[3]!!, 1e-9)
        assertEquals(100.0 - 100.0 / 6.0, values[4]!!, 1e-9)
    }

    @Test
    fun bollingerOfConstantSeriesCollapses() {
        val closes = List(25) { 10.0 }
        val bands = bollinger(closes, 20, 2.0)
        assertEquals(10.0, bands.mid.last()!!, 1e-9)
        assertEquals(10.0, bands.upper.last()!!, 1e-9)
        assertEquals(10.0, bands.lower.last()!!, 1e-9)
    }

    @Test
    fun goldenCrossUsesPreviousAndCurrent() {
        val fast = listOf<Double?>(1.0, 2.0)
        val slow = listOf<Double?>(2.0, 1.5)
        assertTrue(crossedAbove(fast, slow))
        assertTrue(crossedBelow(listOf(3.0, 1.0), listOf(2.0, 2.0)))
    }

    @Test
    fun stochasticStaysInsideRange() {
        val bars = (0 until 20).map { i ->
            Bar(20240101 + i, 10.0, 12.0 + i, 9.0, 11.0 + (i % 3), 1000)
        }
        val kd = stochastic(bars, 9)
        val lastK = kd.k.last()!!
        val lastD = kd.d.last()!!
        assertTrue(lastK in 0.0..100.0)
        assertTrue(lastD in 0.0..100.0)
    }

    @Test
    fun macdOfFlatSeriesIsZero() {
        val closes = List(40) { 8.0 }
        val series = macd(closes)
        assertEquals(0.0, series.line.last()!!, 1e-9)
        assertEquals(0.0, series.signal.last()!!, 1e-9)
    }

    @Test
    fun conditionsCombineWithAnd() {
        val bars = (0 until 30).map { i ->
            val close = 10.0 + i
            Bar(20240101 + i, close, close + 1, close - 1, close, if (i == 29) 10_000 else 100)
        }
        val symbol = SymbolHistory("1101", "台泥", "twse", bars)
        val params = BoxParams(volumeAverageDays = 5, volumeMultiple = 2.0)
        assertTrue(matches(symbol, Condition.CloseAboveMa(5), params))
        assertTrue(matches(symbol, Condition.VolumeAnomaly, params))
        assertTrue(
            matchesAll(
                symbol,
                listOf(Condition.CloseAboveMa(5), Condition.VolumeAnomaly),
                params,
            ),
        )
        assertTrue(!matchesAll(symbol, listOf(Condition.CloseAboveMa(5), Condition.RsiBelow(30.0)), params))
    }
}
