package com.twboxradar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityTest {
    @Test
    fun periodReturnNeedsEnoughBars() {
        val bars = (0..60).map { bar(it, 100.0 + it) }
        assertEquals(160.0 / 100.0 - 1.0, periodReturn(bars, 60)!!, 1e-12)
        assertNull(periodReturn(bars, 61))
    }

    @Test
    fun percentileRanksHandleTies() {
        val ranks = percentileRanks(mapOf("a" to 0.1, "b" to 0.2, "c" to 0.2, "d" to 0.5, "e" to -0.3))
        // 由小到大：e、a、b=c、d → 0、25、62.5（同分取中間）、100
        assertEquals(0, ranks["e"])
        assertEquals(25, ranks["a"])
        assertEquals(63, ranks["b"])
        assertEquals(63, ranks["c"])
        assertEquals(100, ranks["d"])
        assertEquals(mapOf("x" to 100), percentileRanks(mapOf("x" to 0.0)))
    }

    @Test
    fun boxQualityFromHandCase() {
        // 前 20 根量 1000，第 21 根起是箱（箱頂日 20240121），箱內量 500，最後一根是今天。
        val bars = (0 until 20).map { bar(it, 10.0, 1000) } +
            (20 until 30).map { bar(it, 10.0, 500) } +
            listOf(bar(30, 10.0, 3000))
        val box = Box(top = 11.0, bottom = 10.0, topDate = 20240101 + 20, bottomDate = 20240101 + 25)
        val quality = boxQuality(bars, box)!!
        assertEquals(10, quality.days)
        assertEquals(0.1, quality.width, 1e-12)
        assertEquals(0.5, quality.volumeContraction!!, 1e-12)
        // 箱頂前不足 20 根：沒有量縮比
        val short = boxQuality(bars.drop(5), box)!!
        assertNull(short.volumeContraction)
        assertNull(boxQuality(bars, null))
    }

    @Test
    fun analyzeFillsBoxQuality() {
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
        )
        val bars = rows.mapIndexed { i, (high, low, close) -> Bar(20240101 + i, close, high, low, close, 1000) }
        val row = analyze(SymbolHistory("1234", "測試", "twse", bars))
        assertEquals(20.0, row.top!!, 0.0)
        assertEquals(15.0, row.bottom!!, 0.0)
        // 箱頂在第 3 根，昨天是第 8 根 → 6 天
        assertEquals(6, row.boxDays)
        assertEquals(1.0 / 3.0, row.boxWidth!!, 1e-12)
        assertNull(row.volumeContraction)
    }

    @Test
    fun relativeStrengthAndSorting() {
        val symbols = listOf(
            SymbolHistory("1111", "甲", "twse", (0..120).map { bar(it, 100.0 + it) }),
            SymbolHistory("2222", "乙", "twse", (0..120).map { bar(it, 100.0 - it * 0.1) }),
            SymbolHistory("3333", "丙", "twse", (0..30).map { bar(it, 100.0) }),
        )
        val rows = withRelativeStrength(symbols.map { analyze(it) }, symbols)
        val byCode = rows.associateBy { it.code }
        assertEquals(100, byCode["1111"]!!.rs60)
        assertEquals(0, byCode["2222"]!!.rs60)
        assertNull(byCode["3333"]!!.rs60)
        assertEquals(listOf("1111", "2222", "3333"), sortRows(rows, RowSort.RS60).map { it.code })
        val withWidth = listOf(
            rows[0].copy(boxWidth = 0.3, boxDays = 5),
            rows[1].copy(boxWidth = 0.1, boxDays = 40),
            rows[2].copy(boxWidth = null, boxDays = null),
        )
        assertEquals(listOf("2222", "1111", "3333"), sortRows(withWidth, RowSort.BOX_WIDTH).map { it.code })
        assertEquals(listOf("2222", "1111", "3333"), sortRows(withWidth, RowSort.BOX_DAYS).map { it.code })
        val text = describeQuality(withWidth[1])
        assertTrue(text.contains("箱 40 天"))
        assertFalse(text.contains("建議買") || text.contains("建議賣"))
    }

    private fun bar(i: Int, close: Double, volume: Long = 1000) =
        Bar(20240101 + i, close, close, close, close, volume)
}
