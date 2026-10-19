package com.twboxradar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DarvasTest {
    private fun bar(index: Int, high: Double, low: Double, close: Double, volume: Long = 1000, open: Double? = null): Bar {
        return Bar(20240101 + index, open ?: close, high, low, close, volume)
    }

    private fun seriesBox(): List<Bar> {
        val raw = listOf(
            Triple(12.0, 10.0, 11.0),
            Triple(15.0, 11.0, 14.0),
            Triple(20.0, 14.0, 19.0),
            Triple(19.0, 16.0, 17.0),
            Triple(18.0, 15.0, 16.0),
            Triple(18.0, 14.0, 15.0),
            Triple(17.0, 15.0, 16.0),
            Triple(17.0, 15.0, 16.0),
            Triple(17.0, 16.0, 16.0),
        )
        return raw.mapIndexed { i, (h, l, c) -> bar(i, h, l, c) }
    }

    @Test
    fun insideNearBottomBreakoutAndBreakdown() {
        val params = BoxParams(lookbackDays = 30, confirmationDays = 3, volumeAverageDays = 3, volumeMultiple = 1.5)
        val base = seriesBox()
        val inside = evaluate(base + bar(9, 17.0, 15.0, 16.0), params)
        assertEquals(BoxStatus.INSIDE, inside.status)
        assertEquals(20.0, inside.box!!.top, 0.0)
        assertEquals(14.0, inside.box.bottom, 0.0)

        val near = evaluate(base + bar(9, 16.0, 14.0, 15.0), params)
        assertEquals(BoxStatus.NEAR_BOTTOM, near.status)

        val breakout = evaluate(base + bar(9, 22.0, 18.0, 21.0, volume = 5000), params)
        assertEquals(BoxStatus.BREAKOUT, breakout.status)
        assertTrue(breakout.volumeConfirmed)
        assertEquals((20.0 - 21.0) / 21.0, breakout.distanceToTop!!, 1e-9)

        val quiet = evaluate(base + bar(9, 22.0, 18.0, 21.0, volume = 1000), params)
        assertEquals(BoxStatus.BREAKOUT, quiet.status)
        assertFalse(quiet.volumeConfirmed)

        val broken = evaluate(base + bar(9, 15.0, 12.0, 13.0), params)
        assertEquals(BoxStatus.BREAKDOWN, broken.status)
        assertEquals(14.0, broken.box!!.bottom, 0.0)
    }

    @Test
    fun breakdownDoesNotExpandTheBox() {
        val params = BoxParams(lookbackDays = 30, confirmationDays = 3)
        val snap = evaluate(seriesBox() + bar(9, 15.0, 10.0, 13.0), params)
        assertEquals(BoxStatus.BREAKDOWN, snap.status)
        assertEquals(20.0, snap.box!!.top, 0.0)
        assertEquals(14.0, snap.box.bottom, 0.0)
    }

    @Test
    fun bodyModeIgnoresWicks() {
        val params = BoxParams(lookbackDays = 30, confirmationDays = 3, useWicks = false)
        val specs = listOf(
            listOf(10.0, 11.0, 9.0, 11.0),
            listOf(11.0, 12.0, 10.0, 11.5),
            listOf(12.0, 40.0, 1.0, 11.0),
            listOf(11.0, 13.0, 9.0, 10.5),
            listOf(10.5, 13.0, 9.0, 10.2),
            listOf(10.2, 13.0, 9.0, 10.4),
            listOf(10.4, 13.0, 9.0, 10.6),
            listOf(10.6, 13.0, 9.0, 10.5),
            listOf(10.5, 13.0, 9.0, 10.8),
            listOf(10.8, 13.0, 9.0, 11.0),
            listOf(11.0, 13.0, 9.0, 10.7),
            listOf(10.7, 13.0, 9.0, 11.0),
        )
        val bars = specs.mapIndexed { i, s -> Bar(20240101 + i, s[0], s[1], s[2], s[3], 1000) }
        val snap = evaluate(bars, params)
        assertNotNull(snap.box)
        assertEquals(12.0, snap.box!!.top, 0.0)
        assertTrue(snap.box.bottom > 5.0)
        assertEquals(BoxStatus.INSIDE, snap.status)
    }

    @Test
    fun falseBreakoutBacktestLoses() {
        val params = BoxParams(lookbackDays = 30, confirmationDays = 3, volumeAverageDays = 2)
        val bars = seriesBox() + listOf(
            bar(9, 22.0, 20.0, 21.0),
            bar(10, 22.0, 19.0, 20.0),
            bar(11, 16.0, 12.0, 13.0),
        )
        val result = backtest(bars, params)
        assertEquals(1, result.closedTrades)
        assertEquals(21.0, result.trades[0].entryPrice, 0.0)
        assertEquals(13.0, result.trades[0].exitPrice!!, 0.0)
        assertEquals(13.0 / 21.0 - 1.0, result.totalReturn, 1e-9)
        assertEquals(0.0, result.winRate!!, 0.0)
        assertTrue(result.maxDrawdown > 0.3)
        assertTrue(result.totalReturn < result.buyHoldReturn)
    }

    @Test
    fun stopRatchetLocksAWinner() {
        val params = BoxParams(lookbackDays = 40, confirmationDays = 3, volumeAverageDays = 2)
        val bars = seriesBox() + listOf(
            bar(9, 22.0, 20.0, 21.0),
            bar(10, 30.0, 26.0, 29.0),
            bar(11, 29.0, 25.0, 26.0),
            bar(12, 28.0, 25.0, 26.0),
            bar(13, 28.0, 24.0, 25.0),
            bar(14, 27.0, 25.0, 26.0),
            bar(15, 27.0, 25.0, 26.0),
            bar(16, 27.0, 26.0, 26.0),
            bar(17, 25.0, 23.0, 23.0),
        )
        val result = backtest(bars, params)
        assertEquals(1, result.closedTrades)
        assertEquals(21.0, result.trades[0].entryPrice, 0.0)
        assertEquals(23.0, result.trades[0].exitPrice!!, 0.0)
        assertEquals(23.0 / 21.0 - 1.0, result.totalReturn, 1e-9)
        assertEquals(1.0, result.winRate!!, 0.0)
    }

    @Test
    fun wordingStaysDescriptive() {
        val params = BoxParams(lookbackDays = 30, confirmationDays = 3, volumeAverageDays = 3)
        val row = analyze(
            SymbolHistory("2330", "台積電", "twse", seriesBox() + bar(9, 17.0, 15.0, 16.0)),
            params,
        )
        val text = describePosition(row)
        assertTrue(text.contains("距離箱頂還有"))
        val banned = listOf("建議買", "建議賣", "目標價", "強力買進", "強力賣出")
        val corpus = buildString {
            append(text)
            append(DISCLAIMER)
            append(describePosition(row.copy(status = BoxStatus.BREAKOUT, distanceToTop = -0.03)))
            append(describePosition(row.copy(status = BoxStatus.BREAKDOWN, distanceToBottom = -0.02)))
            conditionCatalog().forEach { append(it.label) }
        }
        banned.forEach { assertFalse(corpus.contains(it)) }
    }
}
