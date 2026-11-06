package com.twboxradar.core.local

import com.twboxradar.core.Bar
import com.twboxradar.core.BoxParams
import com.twboxradar.core.BoxStatus
import com.twboxradar.core.evaluate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 讀 src/test/resources/raw 的官方原文（2026-11 實際下載後裁短）。 */
internal fun fixture(name: String): String =
    requireNotNull(ParseTest::class.java.getResource("/raw/$name")) { name }.readText(Charsets.UTF_8)

internal fun fixtureObject(name: String) = JSONObject(fixture(name))
internal fun fixtureArray(name: String) = JSONArray(fixture(name))

/** 對應 pipeline/tests/test_parse.py、test_exrights.py、test_chips_revenue.py、test_risk.py。 */
class ParseTest {
    @Test
    fun parseNumberStripsCommasAndRejectsHalts() {
        assertEquals(2430.0, parseNumber("2,430.00")!!, 1e-9)
        assertNull(parseNumber("--"))
        assertNull(parseNumber(""))
        assertNull(parseNumber("0"))
        assertNull(parseNumber(" ---"))
    }

    @Test
    fun twseFiltersCommonStocksAndDetectsHoliday() {
        val payload = JSONObject(
            """{"stat":"OK","tables":[{"fields":["證券代號","證券名稱","成交股數","成交筆數","成交金額","開盤價","最高價","最低價","收盤價"],
            "data":[["2330","台積電","43,046,982","1","1","2,430.00","2,445.00","2,420.00","2,420.00"],
            ["0050","元大台灣50","1,000","1","1","100","101","99","100"],
            ["1101B","台泥乙","1,000","1","1","10","11","9","10"],
            ["9999","暫停","0","0","0","--","--","--","--"]]}]}""",
        )
        val (status, rows) = parseTwseMiIndex(payload)
        assertEquals(FetchStatus.OK, status)
        assertEquals(1, rows.size)
        assertEquals("2330", rows[0].code)
        assertEquals(2430.0, rows[0].open, 1e-9)
        assertEquals(43046982L, rows[0].volume)
        assertEquals(FetchStatus.CLOSED, parseTwseMiIndex(JSONObject("""{"stat":"很抱歉，沒有符合條件的資料!"}""")).first)
    }

    @Test
    fun realTwseAndTpexQuotes() {
        val (twseStatus, twse) = parseTwseMiIndex(fixtureObject("twse_quotes_20261103.json"))
        assertEquals(FetchStatus.OK, twseStatus)
        assertEquals(listOf("1101", "2330", "2466", "9958"), twse.map { it.code })
        val tsmc = twse.first { it.code == "2330" }
        assertEquals(2295.0, tsmc.close, 1e-9)
        assertEquals(46137336L, tsmc.volume)
        assertEquals("twse", tsmc.market)
        val (tpexStatus, tpex) = parseTpexDaily(fixtureObject("tpex_quotes_20261103.json"))
        assertEquals(FetchStatus.OK, tpexStatus)
        // 權證、ETF、沒有成交的列都排除
        assertEquals(listOf("3324", "6488", "8291"), tpex.map { it.code })
        assertEquals(1380.0, tpex[0].open, 1e-9)
        assertEquals(6892374L, tpex[0].volume)
        assertEquals("tpex", tpex[0].market)
    }

    @Test
    fun holidaysAndPartialDays() {
        val twse = parseTwseMiIndex(fixtureObject("twse_quotes_holiday.json"))
        val tpex = parseTpexDaily(fixtureObject("tpex_quotes_holiday.json"))
        assertEquals(FetchStatus.CLOSED, twse.first)
        assertEquals(FetchStatus.CLOSED, tpex.first)
        assertEquals(FetchStatus.CLOSED, combineDay(twse, tpex).first)
        val open = parseTwseMiIndex(fixtureObject("twse_quotes_20261103.json"))
        // 只有一邊有資料不算休市
        assertEquals(FetchStatus.ERROR, combineDay(open, tpex).first)
        val both = combineDay(open, parseTpexDaily(fixtureObject("tpex_quotes_20261103.json")))
        assertEquals(FetchStatus.OK, both.first)
        assertEquals(7, both.second.size)
    }

    @Test
    fun rocDatesAndPerThousand() {
        assertEquals(20240603, parseRocDate("113年06月03日"))
        assertEquals(20240604, parseRocDate("113/06/04"))
        assertEquals(0.14, perThousand("140 股"), 1e-12)
        assertEquals(0.20211906001, perThousand("202.11906001 股"), 1e-12)
        assertEquals(0.0, perThousand(""), 0.0)
        assertEquals(0.0, perThousand("--"), 0.0)
    }

    @Test
    fun twseSummaryKeepsCommonStockAndMarksRights() {
        val payload = JSONObject(
            """{"stat":"OK","fields":["資料日期","股票代號","股票名稱","除權息前收盤價","除權息參考價","權/息","詳細資料"],
            "data":[["113年06月03日","2313","華通","73.20","71.70","息","2313,20240603"],
            ["113年05月30日","2543","皇昌","59.50","48.42","權息","2543,20240530"],
            ["113年01月30日","2836A","高雄銀甲特","21.30","20.90","權","2836A,20240130"],
            ["113年10月19日","00694B","債券","42.53","42.13","息","00694B,20241019"]]}""",
        )
        val events = parseTwseExSummary(payload).associateBy { it.code }
        assertEquals(setOf("2313", "2543"), events.keys)
        assertFalse(events.getValue("2313").needsDetail)
        assertEquals(1.0, events.getValue("2313").volumeFactor, 0.0)
        assertEquals(71.70 / 73.20, events.getValue("2313").priceFactor, 1e-12)
        assertTrue(events.getValue("2543").needsDetail)
        assertEquals(20240530, events.getValue("2543").date)
        val real = parseTwseExSummary(fixtureObject("twse_exrights_202607.json"))
        assertEquals(listOf("1101", "1470"), real.map { it.code })
        assertEquals(23.25 / 24.05, real[0].priceFactor, 1e-12)
        assertEquals(20260701, real[0].date)
    }

    @Test
    fun twseDetailRatios() {
        val payload = JSONObject(
            """{"stat":"ok","fields":["股票代號","股票名稱","(每股配發現金股利)除息","A. 按普通股股東持股比例每千股無償配股","按股東持股比例每千股認購"],
            "data":[["2543  ","皇昌","0.4 元／股","140 股","202.11906001 股"]]}""",
        )
        val (stock, cash) = parseTwseExDetail(payload)
        assertEquals(0.14, stock, 1e-12)
        assertEquals(0.20211906001, cash, 1e-12)
    }

    @Test
    fun tpexStockDividendAndCash() {
        val payload = JSONObject(
            """{"stat":"ok","tables":[{"fields":["除權息日期","代號","名稱","除權息前收盤價","除權息參考價","現金股利","每仟股無償配股","按持股比例仟股認購"],
            "data":[["113/06/04","6788","華景電","146.50","140.50","6.00000000","0.00000000","0.00000000"],
            ["113/07/01","1234","測試","110.00","100.00","0","100.00000000","50.00000000"],
            ["113/07/01","00694B","債券","10","9","1","0","0"]]}]}""",
        )
        val events = parseTpexExRights(payload).associateBy { it.code }
        assertEquals(setOf("6788", "1234"), events.keys)
        assertEquals(1.0, events.getValue("6788").volumeFactor, 0.0)
        assertEquals(140.50 / 146.50, events.getValue("6788").priceFactor, 1e-12)
        assertEquals(1.15, events.getValue("1234").volumeFactor, 1e-12)
        val real = parseTpexExRights(fixtureObject("tpex_exrights_202607.json"))
        assertEquals(listOf("2230", "4116", "4305"), real.map { it.code })
    }

    @Test
    fun mergeKeepsKnownVolumeFactor() {
        val old = ExEvent(20240530, "2543", 0.81, 1.342, needsDetail = false)
        val fresh = ExEvent(20240530, "2543", 0.81, 1.0, needsDetail = true)
        val merged = mergeEvents(listOf(old), listOf(fresh)).single()
        assertEquals(1.342, merged.volumeFactor, 1e-12)
        assertFalse(merged.needsDetail)
    }

    @Test
    fun spansCoverPartialEdges() {
        val quarters = TradingCalendar.quarters(20231016, 20240102)
        assertEquals(20231016 to 20231231, quarters[0])
        assertEquals(20240101 to 20240102, quarters[1])
        val months = TradingCalendar.months(20240604, 20240701)
        assertEquals(20240604, months.first().first)
        assertEquals(20240701, months.last().second)
    }

    private fun bar(day: Int, close: Double, volume: Long = 1000) = Bar(day, close, close, close, close, volume)

    @Test
    fun cashDividendScalesEarlierPricesNotVolumeOrExDate() {
        val events = listOf(ExEvent(20240603, "2313", 71.70 / 73.20, 1.0))
        val adjusted = adjustBars(listOf(bar(20240531, 73.20, 2000), bar(20240603, 71.70, 3000)), events)
        assertEquals(71.70, adjusted[0].close, 1e-4)
        assertEquals(2000L, adjusted[0].volume)
        assertEquals(71.70, adjusted[1].close, 1e-9)
        assertEquals(3000L, adjusted[1].volume)
    }

    @Test
    fun stockDividendAndRightsScaleVolume() {
        val events = listOf(ExEvent(20240530, "2543", 48.42 / 59.50, 1 + 0.14 + 0.20211906001))
        val adjusted = adjustBars(listOf(bar(20240529, 59.50, 1000), bar(20240530, 48.42, 800)), events)
        assertEquals(48.42, adjusted[0].close, 1e-4)
        assertEquals(1342L, adjusted[0].volume)
        assertEquals(800L, adjusted[1].volume)
    }

    @Test
    fun eventAfterLastBarIsNotAppliedAndFactorsMultiply() {
        val late = adjustBars(listOf(bar(20250401, 900.0)), listOf(ExEvent(20250612, "2330", 0.5, 2.0)))
        assertEquals(900.0, late[0].close, 0.0)
        val events = listOf(
            ExEvent(20240201, "2330", 0.9, 1.0),
            ExEvent(20240601, "2330", 0.8, 1.1),
            ExEvent(20240701, "2330", 0.0, 1.0),
        )
        val adjusted = adjustBars(listOf(bar(20240101, 100.0), bar(20240301, 100.0), bar(20240702, 100.0)), events)
        assertEquals(72.0, adjusted[0].close, 1e-9)
        assertEquals(1100L, adjusted[0].volume)
        assertEquals(80.0, adjusted[1].close, 1e-9)
        assertEquals(100.0, adjusted[2].close, 1e-9)
        assertEquals(1000L, adjusted[2].volume)
    }

    @Test
    fun exDividendGapIsNotBreakdownAfterAdjustment() {
        val params = BoxParams(lookbackDays = 30, confirmationDays = 3, volumeAverageDays = 3)
        val raw = listOf(
            Triple(12.0, 10.0, 11.0), Triple(15.0, 11.0, 14.0), Triple(20.0, 14.0, 19.0), Triple(19.0, 16.0, 17.0),
            Triple(18.0, 15.0, 16.0), Triple(18.0, 14.0, 15.0), Triple(17.0, 15.0, 16.0), Triple(17.0, 15.0, 16.0),
            Triple(17.0, 16.0, 16.0), Triple(15.0, 12.0, 13.0),
        )
        val bars = raw.mapIndexed { i, (h, l, c) -> Bar(20240101 + i, c, h, l, c, 1000) }
        assertEquals(BoxStatus.BREAKDOWN, evaluate(bars, params).status)
        val adjusted = adjustBars(bars, listOf(ExEvent(20240110, "2330", 13.0 / 16.0, 1.0)))
        val snap = evaluate(adjusted, params)
        assertNotEquals(BoxStatus.BREAKDOWN, snap.status)
        assertEquals(14 * 13.0 / 16.0, snap.box!!.bottom, 1e-4)
        assertEquals(13.0, snap.close, 1e-9)
    }

    @Test
    fun twseT86() {
        val chips = parseTwseT86(fixtureObject("twse_t86_20261103.json"))!!
        assertEquals(setOf("1101", "2330"), chips.keys)
        // 外陸資 -11,703,020 ＋ 外資自營商 0；投信 2,345,645；自營商 2,658,082
        assertEquals(listOf(-11703020L, 2345645L, 2658082L), chips.getValue("2330").toList())
        assertNull(parseTwseT86(JSONObject("""{"stat":"很抱歉，沒有符合條件的資料!"}""")))
    }

    @Test
    fun tpexDailyTradeByPosition() {
        val chips = parseTpexInsti(fixtureObject("tpex_insti_20261103.json"))!!
        assertEquals(setOf("3324", "6488"), chips.keys)
        assertEquals(listOf(-96945L, 153000L, -265923L), chips.getValue("3324").toList())
        val row = JSONArray(
            """["3324","雙鴻","2,377,855","2,142,163","235,692","0","0","0","2,377,855","2,142,163","235,692","136,000","0","136,000","134,000","79,100","54,900","114,461","98,556","15,905","248,461","177,656","70,805","1"]""",
        )
        val broken = JSONObject().put("tables", JSONArray().put(JSONObject().put("data", JSONArray().put(row))))
        assertTrue(runCatching { parseTpexInsti(broken) }.isFailure)
    }

    @Test
    fun chipSummaryLotsAndStreaks() {
        val days = (0 until 6).map { i ->
            (20261001 + i) to mapOf("2330" to longArrayOf(1000L * (i + 1), if (i >= 3) 2000L else -500L, 0L))
        }
        val summary = summarizeChips(days).getValue("2330")
        assertEquals(20, summary.foreign5)
        assertEquals(21, summary.foreign20)
        assertEquals(5, summary.trust5)
        assertEquals(3, summary.trustBuyStreak)
        assertEquals(6, summary.foreignBuyStreak)
        assertNull(combineChips(mapOf("2330" to longArrayOf(1, 2, 3)), null))
    }

    @Test
    fun revenueShiftParseAndSummary() {
        assertEquals(202608, shiftMonth(202609, -1))
        assertEquals(202512, shiftMonth(202601, -1))
        assertEquals(202509, shiftMonth(202609, -12))
        val parsed = parseRevenueOpenApi(fixtureArray("twse_revenue.json"))
        assertEquals(mapOf(202609 to 14321556L, 202608 to 13515534L, 202509 to 13306676L), parsed.getValue("1101"))
        val values = HashMap<Int, Long>()
        for (gap in 1..12) values[shiftMonth(202609, -gap)] = 100L
        values[202609] = 150L
        values[202608] = 120L
        val summary = summarizeRevenue(mapOf("1101" to values, "2330" to mapOf(202609 to 50L, 202608 to 100L)))
        val a = summary.getValue("1101")
        assertEquals(202609, a.month)
        assertEquals(0.5, a.yoy!!, 1e-9)
        assertEquals(0.25, a.mom!!, 1e-9)
        assertEquals(true, a.twelveMonthHigh)
        val b = summary.getValue("2330")
        assertNull(b.yoy)
        assertEquals(-0.5, b.mom!!, 1e-9)
        assertNull(b.twelveMonthHigh)
        val merged = mergeRevenue(mapOf("1101" to (1..20).associate { shiftMonth(202609, -it) to 1L }), parsed)
        assertEquals(REVENUE_KEEP_MONTHS, merged.getValue("1101").size)
        assertEquals(14321556L, merged.getValue("1101")[202609])
    }

    @Test
    fun rocDatesAndPeriods() {
        assertEquals(20261030, parseRoc("1151030"))
        assertEquals(20261028, parseRoc("115/10/28"))
        assertEquals(20261030, parseRoc("115.10.30"))
        assertNull(parseRoc(""))
        assertEquals(20261028 to 20261103, parsePeriod("115/10/28～115/11/03"))
        assertEquals(20261030 to 20261105, parsePeriod("1151030~1151105"))
    }

    @Test
    fun riskSourcesFromRealSnapshots() {
        val notice = parseTwseNotice(fixtureObject("twse_notice_20261103.json"))
        assertTrue("2466" in notice)
        assertFalse("066098" in notice)
        val punish = parseTwsePunish(fixtureArray("twse_punish.json"), 20261103)
        // 同一檔兩筆處置，留迄日較晚的
        assertEquals(PunishItem(20261104, 20261110, "第二次處置"), punish["2466"])
        assertTrue("4526" in punish)
        assertEquals(setOf("1815", "3379", "3624", "3685"), parseTpexWarning(fixtureArray("tpex_warning.json"), 20261103))
        assertNull(parseTpexWarning(fixtureArray("tpex_warning.json"), 20261102))
        val disposal = parseTpexDisposal(fixtureArray("tpex_disposal.json"), 20261104)
        assertFalse("4542" in disposal) // 115/11/03 已結束
        assertFalse("36913" in disposal) // 不是普通股
        // 這筆的處置措施沒有寫「第 N 次處置」，與 risk.py 一樣顯示「處置」
        assertEquals(PunishItem(20261103, 20261109, "處置"), disposal["8291"])
    }

    @Test
    fun riskKeepsPreviousOnSameDayFailure() {
        val before = RiskSnapshot(
            20261030,
            setOf("2426"),
            mapOf("2466" to PunishItem(null, 20261105, "處置")),
            mapOf("twseNotice" to "ok", "twsePunish" to "ok", "tpexNotice" to "ok", "tpexPunish" to "ok"),
        )
        val fresh = buildRiskSnapshot(20261030, null, null, JSONArray(), JSONArray(), before)
        assertTrue("2426" in fresh.notice)
        assertTrue("2466" in fresh.punish)
        assertEquals("stale", fresh.sources["twseNotice"])
        assertEquals("ok", fresh.sources["tpexPunish"])
    }
}
