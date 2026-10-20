package com.twboxradar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListingTest {
    @Test
    fun blankQueryStaysOnTheCurrentTab() {
        val rows = listOf(
            row("2330", "台積電", "twse", BoxStatus.INSIDE),
            row("2317", "鴻海", "twse", BoxStatus.BREAKOUT),
        )
        val shown = visibleRows(rows, BoxStatus.BREAKOUT, "twse", "")
        assertEquals(listOf("2317"), shown.map { it.code })
    }

    @Test
    fun queryFindsStockOutsideTheCurrentTab() {
        val rows = listOf(
            row("2330", "台積電", "twse", BoxStatus.INSIDE),
            row("2317", "鴻海", "twse", BoxStatus.BREAKOUT),
            row("6488", "環球晶", "tpex", BoxStatus.INSIDE),
        )
        val shown = visibleRows(rows, BoxStatus.BREAKOUT, "twse", "2330")
        assertEquals(listOf("2330"), shown.map { it.code })
        assertEquals("在「箱型內整理中」", membershipLabel(shown.single().status))
    }

    @Test
    fun marketFilterStillAppliesWhileSearching() {
        val rows = listOf(row("6488", "環球晶", "tpex", BoxStatus.INSIDE))
        assertTrue(visibleRows(rows, BoxStatus.BREAKOUT, "twse", "6488").isEmpty())
        assertEquals("6488", visibleRows(rows, BoxStatus.BREAKOUT, "all", "環球").single().code)
    }

    @Test
    fun exactCodeRanksBeforePartialName() {
        val rows = listOf(
            row("2337", "旺宏", "twse", BoxStatus.NONE),
            row("2330", "台積電", "twse", BoxStatus.NEAR_BOTTOM),
        )
        assertEquals(listOf("2330", "2337"), visibleRows(rows, BoxStatus.BREAKOUT, "all", "233").map { it.code })
    }

    @Test
    fun docsUrlUsesMainOrTheGivenBranch() {
        assertEquals(
            "https://raw.githubusercontent.com/apsmkimo/tw-box-radar/main/docs",
            marketDocsUrl(""),
        )
        assertEquals(
            "https://raw.githubusercontent.com/apsmkimo/tw-box-radar/cursor/tw-box-radar-mvp-ba55/docs",
            marketDocsUrl(PREVIEW_DATA_BRANCH),
        )
        assertEquals(
            "https://raw.githubusercontent.com/apsmkimo/tw-box-radar/main/docs",
            marketDocsUrl("../etc"),
        )
    }

    private fun row(code: String, name: String, market: String, status: BoxStatus) = ScreenRow(
        code = code,
        name = name,
        market = market,
        status = status,
        close = 1.0,
        top = null,
        bottom = null,
        topDate = null,
        bottomDate = null,
        distanceToTop = null,
        distanceToBottom = null,
        volumeRatio = null,
        volumeConfirmed = false,
        rangePosition = null,
    )
}
