package com.twboxradar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitsTest {
    @Test
    fun limitPricesFollowTickSizes() {
        // 與 pipeline/tests/test_risk.py 相同的手算案例
        assertEquals(51.7, limitUpPrice(47.0), 1e-9)
        assertEquals(42.3, limitDownPrice(47.0), 1e-9)
        assertEquals(501.0, limitUpPrice(455.5), 1e-9)
        assertEquals(50.0, limitUpPrice(45.5), 1e-9)
        assertEquals(40.95, limitDownPrice(45.5), 1e-9)
        assertEquals(2670.0, limitUpPrice(2430.0), 1e-9)
        assertEquals(2190.0, limitDownPrice(2430.0), 1e-9)
        assertEquals(10.45, limitUpPrice(9.5), 1e-9)
    }

    @Test
    fun detectsLimitMoves() {
        assertEquals(LimitMove.UP, limitMove(47.0, 51.7, 51.7, 48.0))
        assertEquals(LimitMove.DOWN, limitMove(47.0, 42.3, 45.0, 42.3))
        assertEquals(LimitMove.NONE, limitMove(47.0, 51.6, 51.7, 48.0))
        assertEquals(LimitMove.UP, limitMove(100.0, 109.5, 109.5, 100.0))
        assertEquals(LimitMove.NONE, limitMove(100.0, 109.5, 110.0, 100.0))
    }

    @Test
    fun exRightsDayUsesAdjustedPreviousCloseAsReference() {
        // 前收 100，除息參考價 97。還原後前一根是 97，今天原價收 106.5（97 的漲停）。
        val adjusted = listOf(bar(1, 97.0), bar(2, 106.5))
        val raw = listOf(bar(1, 100.0), bar(2, 106.5))
        assertEquals(listOf(LimitMove.NONE, LimitMove.UP), limitMoves(adjusted, raw))
        // 若誤用原價前收 100，漲停是 110，就不會標。
        assertEquals(LimitMove.NONE, limitMove(100.0, 106.5, 106.5, 97.0))
    }

    @Test
    fun backtestCanSkipLimitUpBreakouts() {
        // 箱頂 20、箱底 15，第 10 根收 22（20 的漲停）突破。
        val rows = listOf(
            Triple(12.0, 10.0, 11.0),
            Triple(15.0, 11.0, 14.0),
            Triple(20.0, 14.0, 19.0),
            Triple(19.0, 16.0, 17.0),
            Triple(18.0, 15.0, 16.0),
            Triple(18.0, 16.0, 17.0),
            Triple(17.0, 16.0, 16.5),
            Triple(18.0, 16.0, 17.0),
            Triple(19.0, 17.0, 18.0),
            Triple(20.0, 18.0, 20.0),
            Triple(22.0, 20.0, 22.0),
            Triple(23.0, 21.0, 22.5),
        )
        val bars = rows.mapIndexed { i, (high, low, close) -> Bar(20240101 + i, close, high, low, close, 1000) }
        val normal = backtest(bars, BoxParams())
        assertTrue(normal.trades.any { it.entryDate == 20240111 })
        val skipped = backtest(bars, BoxParams(skipLimitUpEntry = true))
        assertFalse(skipped.trades.any { it.entryDate == 20240111 })
    }

    @Test
    fun chipsAreObjectiveLabels() {
        val chips = riskChips(RiskFlags(notice = true, punishStart = 20261030, punishEnd = 20261105, limit = LimitMove.UP))
        assertEquals(listOf("注意", "處置至 11/05", "漲停"), chips)
        assertEquals(listOf("跌停"), riskChips(null, LimitMove.DOWN))
        assertTrue(riskChips(null).isEmpty())
        chips.forEach { label ->
            assertFalse(label.contains("建議買"))
            assertFalse(label.contains("建議賣"))
        }
    }

    private fun bar(day: Int, close: Double) = Bar(day, close, close, close, close, 1000)
}
