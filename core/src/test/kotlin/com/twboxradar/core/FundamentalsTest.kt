package com.twboxradar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FundamentalsTest {
    private val symbol = SymbolHistory(
        "2330",
        "台積電",
        "twse",
        (0 until 30).map { Bar(20240101 + it, 100.0, 101.0, 99.0, 100.0, 1000) },
    )

    private fun data(
        trustStreak: Int = 0,
        foreign5: Int = 0,
        chipDays: Int = 20,
        yoy: Double? = null,
        high: Boolean? = null,
    ) = Fundamentals(
        chips = ChipSummary(foreign5, foreign5, 0, 0, 0, 0, trustStreak, 0),
        revenue = RevenueSummary(202609, 1000, yoy, null, high),
        chipDays = chipDays,
    )

    @Test
    fun trustStreakCondition() {
        val condition = Condition.TrustBuyStreak(3)
        assertTrue(matches(symbol, condition, BoxParams(), data(trustStreak = 3)))
        assertFalse(matches(symbol, condition, BoxParams(), data(trustStreak = 2)))
        assertFalse(matches(symbol, condition, BoxParams(), null))
    }

    @Test
    fun foreignFiveDayNeedsFiveDaysOfHistory() {
        assertTrue(matches(symbol, Condition.ForeignFiveDayNetBuy, BoxParams(), data(foreign5 = 10)))
        assertFalse(matches(symbol, Condition.ForeignFiveDayNetBuy, BoxParams(), data(foreign5 = -10)))
        assertFalse(matches(symbol, Condition.ForeignFiveDayNetBuy, BoxParams(), data(foreign5 = 10, chipDays = 3)))
    }

    @Test
    fun revenueConditions() {
        assertTrue(matches(symbol, Condition.RevenueYoyAbove(0.2), BoxParams(), data(yoy = 0.25)))
        assertFalse(matches(symbol, Condition.RevenueYoyAbove(0.2), BoxParams(), data(yoy = 0.2)))
        assertFalse(matches(symbol, Condition.RevenueYoyAbove(0.2), BoxParams(), data(yoy = null)))
        assertTrue(matches(symbol, Condition.RevenueTwelveMonthHigh, BoxParams(), data(high = true)))
        assertFalse(matches(symbol, Condition.RevenueTwelveMonthHigh, BoxParams(), data(high = null)))
    }

    @Test
    fun conditionsCombineWithAnd() {
        val both = listOf(Condition.TrustBuyStreak(3), Condition.RevenueYoyAbove(0.2))
        assertTrue(matchesAll(symbol, both, BoxParams(), data(trustStreak = 4, yoy = 0.3)))
        assertFalse(matchesAll(symbol, both, BoxParams(), data(trustStreak = 4, yoy = 0.1)))
    }

    @Test
    fun descriptionsAreObjective() {
        assertEquals("外資 5 日 +1,234 張、20 日 -56 張", describeNet("外資", 1234, -56, 25))
        assertEquals("投信 3 日 0 張、3 日 +2 張", describeNet("投信", 0, 2, 3))
        assertEquals(
            "2026/09 營收 年增 +54.7%、月增 -0.6%，創 12 個月新高",
            describeRevenue(RevenueSummary(202609, 511857399, 0.5465, -0.0057, true)),
        )
        val labels = conditionCatalog().map { it.label } +
            describeRevenue(RevenueSummary(202609, 1, null, null, null))
        labels.forEach {
            assertFalse(it.contains("建議買"))
            assertFalse(it.contains("建議賣"))
        }
    }
}
