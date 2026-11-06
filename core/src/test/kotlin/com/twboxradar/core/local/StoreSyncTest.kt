package com.twboxradar.core.local

import com.twboxradar.core.LimitMove
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZonedDateTime

/** 假的網路：網址 → 回應。沒有登記的網址回 404。 */
private class FakeHttp : HttpGet {
    val routes = LinkedHashMap<String, () -> HttpResponse>()
    val calls = mutableListOf<String>()

    fun json(url: String, body: String) {
        routes[url] = { HttpResponse(200, body) }
    }

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        calls += url
        return routes[url]?.invoke() ?: HttpResponse(404, "not found")
    }
}

class StoreSyncTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun row(code: String, close: Double, market: String = "twse", high: Double = close, low: Double = close) =
        QuoteRow(code, "名$code", market, close, high, low, close, 1000)

    private fun taipei(text: String): ZonedDateTime = ZonedDateTime.of(java.time.LocalDateTime.parse(text), TAIPEI)

    @Test
    fun storeRoundTripAndPrune() {
        val store = LocalStore(temp.newFolder("s"))
        store.writeDay(20261102, listOf(row("2330", 1000.0), row("6488", 700.5, "tpex")))
        store.writeDay(20261103, listOf(row("2330", 1010.0)))
        assertEquals(listOf(20261102, 20261103), store.tradingDays())
        val day = store.readDay(20261102)
        assertEquals(700.5, day.first { it.code == "6488" }.close, 0.0)
        assertEquals("tpex", day.first { it.code == "6488" }.market)
        assertEquals("名2330" to "twse", store.names()["2330"])
        store.writeChips(20261103, mapOf("2330" to longArrayOf(-1, 2, 3)))
        assertEquals(listOf(-1L, 2L, 3L), store.readChips(20261103)!!.getValue("2330").toList())
        store.saveEvents(listOf(ExEvent(20261103, "2330", 0.99, 1.0, true), ExEvent(20261102, "2330", 0.9)))
        store.saveState(StoreState(closed = setOf(20261030, 20261104), seedDone = true))
        store.prune(keep = 1)
        assertEquals(listOf(20261103), store.tradingDays())
        // 落在最舊一天以前的事件、休市紀錄都不再需要
        assertEquals(emptyList<ExEvent>(), store.events())
        assertEquals(setOf(20261104), store.state().closed)
        assertTrue(store.state().seedDone)
    }

    @Test
    fun builderAdjustsAndFlagsLimitWithExRightsReference() {
        val store = LocalStore(temp.newFolder("b"))
        store.writeDay(20261029, listOf(row("1234", 100.0), row("2330", 1000.0)))
        // 除息參考價 97 → 漲停 106.5
        store.writeDay(20261030, listOf(row("1234", 106.5, low = 98.0), row("2330", 1000.0)))
        store.saveEvents(listOf(ExEvent(20261030, "1234", 0.97, 1.0)))
        store.writeChips(20261030, mapOf("2330" to longArrayOf(2000, 1000, -1000)))
        store.saveRisk(RiskSnapshot(20261030, setOf("2330"), mapOf("1234" to PunishItem(20261030, 20261105, "第二次處置")), mapOf()))
        val market = buildMarket(store)!!
        assertEquals("20261030", market.asOf)
        val symbol = market.symbol("1234", includeRaw = true)!!
        assertEquals(97.0, symbol.bars[0].close, 1e-9)
        assertEquals(106.5, symbol.bars[1].close, 1e-9)
        assertEquals(100.0, symbol.rawBars[0].close, 1e-9)
        assertTrue(market.symbol("2330", includeRaw = true)!!.rawBars.isEmpty())
        assertTrue(market.symbols().all { it.rawBars.isEmpty() })
        assertEquals(LimitMove.UP, market.flags.getValue("1234").limit)
        assertEquals(20261105, market.flags.getValue("1234").punishEnd)
        assertTrue(market.flags.getValue("2330").notice)
        assertEquals("20261030", market.riskAsOf)
        assertEquals(1, market.chipDays)
        val chips = market.fundamentals.getValue("2330").chips!!
        assertEquals(2, chips.foreign5)
        assertEquals(-1, chips.dealer20)
        assertEquals(1, chips.trustBuyStreak)
        assertEquals(LimitMove.NONE, limitToday(symbol.rawBars.take(1), emptyList()))
    }

    private fun bundle(day: String): String {
        val obj = JSONObject()
        obj.put("twse_quotes", fixtureObject("twse_quotes_20261103.json"))
        obj.put("tpex_quotes", fixtureObject("tpex_quotes_20261103.json"))
        obj.put("twse_t86", fixtureObject("twse_t86_20261103.json"))
        obj.put("tpex_insti", fixtureObject("tpex_insti_20261103.json"))
        obj.put("twse_exrights", JSONObject("""{"stat":"OK","fields":["資料日期","股票代號","股票名稱","除權息前收盤價","除權息參考價","權/息"],"data":[["$day","2330","台積電","2300","2295","息"]]}"""))
        obj.put("twse_notice", fixtureObject("twse_notice_20261103.json"))
        return obj.toString()
    }

    private fun throttled(http: HttpGet) = ThrottledHttp(http, sleep = {})

    @Test
    fun remoteDownloadsOnlyNewDaysAndMarksWorkerHolidays() {
        val store = LocalStore(temp.newFolder("r"))
        store.writeDay(20261029, listOf(row("2330", 2000.0)))
        store.saveState(StoreState(seedDone = true, exCheckedDay = 20261103))
        val remote = FakeHttp()
        val manifest = """{"days":{"20261030":["twse_notice"],"20261102":["tpex_quotes","twse_quotes"],"20261103":["tpex_quotes","twse_quotes","twse_punish","tpex_warning"]},"latest":"20261103"}"""
        remote.json("$WORKER_BASE_URL/manifest", manifest)
        remote.json("$WORKER_BASE_URL/day/20261102", bundle("115年11月02日"))
        remote.json("$WORKER_BASE_URL/day/20261103", bundle("115年11月03日"))
        remote.json("$WORKER_BASE_URL/raw/20261103/twse_punish", fixture("twse_punish.json"))
        remote.json("$WORKER_BASE_URL/raw/20261103/tpex_warning", fixture("tpex_warning.json"))
        val direct = FakeHttp()
        val syncer = Syncer(store, remote, throttled(direct), now = { taipei("2026-11-04T12:00") })
        val report = syncer.sync(DataSourceMode.REMOTE)
        assertEquals(listOf(20261102, 20261103), report.newDays)
        assertEquals(20261103, report.asOf)
        assertNull(report.fallbackReason)
        assertEquals(REMOTE_LABEL, report.sourceLabel)
        // 20261030 Worker 有跑但沒有行情 → 休市；今天（中午）等 Worker，不直連
        assertTrue(20261030 in store.state().closed)
        assertFalse(direct.calls.any { it.contains("MI_INDEX") })
        assertEquals(setOf("1101", "2330", "2466", "9958", "3324", "6488", "8291"), store.readDay(20261103).map { it.code }.toSet())
        assertNotNull(store.readChips(20261103))
        assertEquals(2, store.events().size)
        val risk = store.risk()!!
        assertEquals(20261103, risk.asOf)
        assertTrue("2466" in risk.punish)
        assertTrue("1815" in risk.notice)
        // 第二次同步沒有新日子，只讀 manifest
        remote.calls.clear()
        val again = Syncer(store, remote, throttled(direct), now = { taipei("2026-11-04T12:05") }).sync(DataSourceMode.REMOTE)
        assertEquals(emptyList<Int>(), again.newDays)
        assertFalse(remote.calls.any { it.contains("/day/") })
    }

    @Test
    fun remoteFailureFallsBackToDirect() {
        val store = LocalStore(temp.newFolder("f"))
        store.writeDay(20261030, listOf(row("2330", 2000.0)))
        store.saveState(StoreState(seedDone = true, exCheckedDay = 20261103))
        val remote = FakeHttp() // manifest 404
        val direct = FakeHttp()
        direct.json(OfficialSources.url(OfficialSources.TWSE_QUOTES, 20261102), fixture("twse_quotes_holiday.json"))
        direct.json(OfficialSources.url(OfficialSources.TPEX_QUOTES, 20261102), fixture("tpex_quotes_holiday.json"))
        direct.json(OfficialSources.url(OfficialSources.TWSE_QUOTES, 20261103), fixture("twse_quotes_20261103.json"))
        direct.json(OfficialSources.url(OfficialSources.TPEX_QUOTES, 20261103), fixture("tpex_quotes_20261103.json"))
        direct.json(OfficialSources.url(OfficialSources.TWSE_QUOTES, 20261104), fixture("twse_quotes_holiday.json"))
        direct.json(OfficialSources.url(OfficialSources.TPEX_QUOTES, 20261104), fixture("tpex_quotes_holiday.json"))
        direct.json(OfficialSources.url(OfficialSources.TWSE_T86, 20261103), fixture("twse_t86_20261103.json"))
        direct.json(OfficialSources.url(OfficialSources.TPEX_INSTI, 20261103), fixture("tpex_insti_20261103.json"))
        val report = Syncer(store, remote, throttled(direct), now = { taipei("2026-11-04T17:00") }).sync(DataSourceMode.REMOTE)
        assertEquals(listOf(20261103), report.newDays)
        assertNotNull(report.fallbackReason)
        assertEquals(DIRECT_LABEL, report.sourceLabel)
        // 過去的平日沒有行情＝休市；今天還沒有＝尚未發佈，不記休市
        assertEquals(setOf(20261102), store.state().closed)
        assertNotNull(store.readChips(20261103))
        // 直連區間表失敗（假網路 404），留待下次補
        assertEquals(20261103, store.state().exPendingFrom)
    }

    @Test
    fun directSkipsTodayBeforeClose() {
        val store = LocalStore(temp.newFolder("d"))
        store.writeDay(20261103, listOf(row("2330", 2000.0)))
        store.saveState(StoreState(seedDone = true, exCheckedDay = 20261103))
        val direct = FakeHttp()
        val report = Syncer(store, FakeHttp(), throttled(direct), now = { taipei("2026-11-04T10:00") }).sync(DataSourceMode.DIRECT)
        assertEquals(emptyList<Int>(), report.newDays)
        assertNull(report.error)
        assertFalse(direct.calls.any { it.contains("MI_INDEX") })
    }

    @Test
    fun partialDirectDayIsAnErrorNotAHoliday() {
        val store = LocalStore(temp.newFolder("p"))
        store.writeDay(20261102, listOf(row("2330", 2000.0)))
        store.saveState(StoreState(seedDone = true, exCheckedDay = 20261103))
        val direct = FakeHttp()
        direct.json(OfficialSources.url(OfficialSources.TWSE_QUOTES, 20261103), fixture("twse_quotes_20261103.json"))
        direct.json(OfficialSources.url(OfficialSources.TPEX_QUOTES, 20261103), fixture("tpex_quotes_holiday.json"))
        val report = Syncer(store, FakeHttp(), throttled(direct), now = { taipei("2026-11-04T10:00") }).sync(DataSourceMode.DIRECT)
        assertNotNull(report.error)
        assertEquals(listOf(20261102), store.tradingDays())
        assertTrue(store.state().closed.isEmpty())
    }

    @Test
    fun throttleWaitsBetweenRequests() {
        var clock = 0L
        val waits = mutableListOf<Long>()
        val http = ThrottledHttp(FakeHttp(), minIntervalMs = 3000, clock = { clock }, sleep = { waits += it; clock += it })
        clock = 10_000
        http.get("https://a")
        clock += 500
        http.get("https://b")
        assertEquals(listOf(2500L), waits)
    }

    @Test
    fun githubSeedImportsRawHistoryAndEvents() {
        val store = LocalStore(temp.newFolder("g"))
        val http = FakeHttp()
        val base = "https://example.test/data"
        http.json("$base/state.json", """{"completed":["20261030","20261102"],"closed":[]}""")
        http.json(
            "$base/monthly/2026-10.jsonl",
            """[20261030,"2330","台積電","twse",100,101,99,100,4000]""" + "\n" + """[20261030,"0050","ETF","twse",1,1,1,1,1]""" + "\n",
        )
        http.json("$base/monthly/2026-11.jsonl", """[20261102,"2330","台積電","twse",95,96,94,95,5000]""" + "\n")
        http.json("$base/exrights.json", """{"events":[{"date":20261102,"code":"2330","priceFactor":0.95,"volumeFactor":1.0,"volumeKnown":true}]}""")
        http.json("$base/revenue.json", """{"months":{"2330":{"202609":10,"202608":9}}}""")
        http.json("$base/chips/20261102.json", """{"2330":[1000,2000,3000]}""")
        val error = runSeed(store, listOf(GitHubDataSeed(http, base)), {})
        assertNull(error)
        assertTrue(store.state().seedDone)
        assertEquals(listOf(20261030, 20261102), store.tradingDays())
        assertEquals(listOf("2330"), store.readDay(20261030).map { it.code })
        val market = buildMarket(store)!!
        val tsmc = market.symbol("2330", includeRaw = true)!!
        assertEquals(95.0, tsmc.bars[0].close, 1e-9)
        assertEquals(100.0, tsmc.rawBars[0].close, 1e-9)
        assertEquals(10L, store.revenue().getValue("2330")[202609])
        assertNotNull(store.readChips(20261102))
        assertEquals("台積電", tsmc.name)
    }

    @Test
    fun seedFailureIsReportedAndRetriedLater() {
        val store = LocalStore(temp.newFolder("x"))
        val error = runSeed(store, listOf(GitHubDataSeed(FakeHttp(), "https://example.test/data")), {})
        assertNotNull(error)
        assertFalse(store.state().seedDone)
    }
}

class ImportTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun importOnlyFillsMissingDaysAndKeepsLocalEvents() {
        val seed = LocalStore(temp.newFolder("seed"))
        val local = LocalStore(temp.newFolder("local"))
        val a = QuoteRow("2330", "台積電", "twse", 1.0, 1.0, 1.0, 1.0, 1)
        seed.writeDay(20261029, listOf(a))
        seed.writeDay(20261030, listOf(a.copy(close = 2.0)))
        seed.saveEvents(listOf(ExEvent(20261030, "2330", 0.9, 1.0, true)))
        local.writeDay(20261030, listOf(a.copy(close = 3.0)))
        local.saveEvents(listOf(ExEvent(20261030, "2330", 0.9, 1.2)))
        assertEquals(1, local.importFrom(seed))
        assertEquals(listOf(20261029, 20261030), local.tradingDays())
        assertEquals(3.0, local.readDay(20261030).single().close, 0.0)
        assertEquals(1.2, local.events().single().volumeFactor, 0.0)
        assertEquals("台積電", local.names().getValue("2330").first)
    }
}
