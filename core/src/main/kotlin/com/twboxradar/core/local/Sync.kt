package com.twboxradar.core.local

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime

/** 資料來源設定。 */
enum class DataSourceMode { REMOTE, DIRECT }

const val WORKER_BASE_URL = "https://tw-box-radar-collector.apsmkimo.workers.dev"
val TAIPEI: ZoneId = ZoneId.of("Asia/Taipei")

/** 沒有初始資料時，最多先補幾天（遠端每天約 0.6 MB；直連每天約 2 個請求、6 秒以上）。 */
const val COLD_START_REMOTE_DAYS = 90
const val COLD_START_DIRECT_DAYS = 10
/** 直連時只補最近幾天的三大法人（畫面只用到 20 日）。 */
const val CHIP_DAYS = 20
/** Worker 最後一次排程是 22:00，之後還沒有今天的行情就改直連。 */
private const val WORKER_LAST_RUN_MINUTES = 22 * 60 + 30
/** 收盤資料最早大約 14:30 才有，之前不打交易所。 */
private const val QUOTES_READY_MINUTES = 14 * 60 + 30
/** 注意／處置清單收盤後還會更新，同一資料日每 2 小時最多重抓一次。 */
private const val RISK_REFRESH_MS = 2 * 60 * 60 * 1000L
/** 每次同步最多查幾筆上市配股明細。 */
private const val DETAIL_PER_SYNC = 5

const val REMOTE_LABEL = "遠端（Cloudflare）"
const val DIRECT_LABEL = "直連證交所／櫃買"

/**
 * 一次同步的結果。usedRemote／usedDirect 表示實際有用到哪一邊；
 * fallbackReason 是遠端改直連的原因；error 是中途停下來的原因（已下載的日子仍會保存）。
 */
data class SyncReport(
    val mode: DataSourceMode,
    val usedRemote: Boolean,
    val usedDirect: Boolean,
    val fallbackReason: String?,
    val newDays: List<Int>,
    val asOf: Int?,
    val error: String?,
    val directRequests: Int = 0,
    val remoteDays: Int = 0,
    val directDays: Int = 0,
) {
    /** 這次新資料實際來自哪裡；沒有新資料時是這次查詢的來源。 */
    val sourceLabel: String
        get() = when {
            remoteDays > 0 && directDays > 0 -> "$REMOTE_LABEL＋$DIRECT_LABEL"
            directDays > 0 -> DIRECT_LABEL
            remoteDays > 0 -> REMOTE_LABEL
            mode == DataSourceMode.REMOTE && fallbackReason == null -> REMOTE_LABEL
            else -> DIRECT_LABEL
        }
}

/**
 * 增量同步：只下載比本機最新一天更新的交易日。
 *
 * 遠端模式：讀 Worker /manifest，有完整行情（上市＋上櫃）的日子用 /day/YYYYMMDD 一次拿整天。
 * Worker 連不上、回非 2xx、或 manifest 少了需要的日子時，這次同步自動改直連補那些日子。
 * - Worker 有記錄但沒有行情、且比 Worker 最新一天早的平日：休市。
 * - 比 Worker 最新一天晚、今天以前的平日：Worker 落後，直連補。
 * - 今天：22:30 以前等 Worker；之後 Worker 還沒有，就直連。
 *
 * 直連模式：從本機最新一天的下一個平日往後走到今天（daily.py）。
 * 某平日兩邊都沒有行情＝休市；今天沒有行情＝還沒發佈，不記休市。只有一邊有＝失敗，下次再試。
 */
class Syncer(
    private val store: LocalStore,
    private val remoteHttp: HttpGet,
    private val direct: ThrottledHttp,
    private val workerBase: String = WORKER_BASE_URL,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(TAIPEI) },
    private val progress: (String) -> Unit = {},
) {
    private var manifest: JSONObject? = null

    fun sync(mode: DataSourceMode, forceSnapshots: Boolean = false): SyncReport {
        val clock = now()
        val today = TradingCalendar.toInt(clock.toLocalDate())
        val minutes = clock.hour * 60 + clock.minute
        var state = store.state()
        val latest = store.latestDay()
        val newDays = mutableListOf<Int>()
        var usedRemote = false
        var fallback: String? = null
        var error: String? = null
        var needDirect: List<Int> = emptyList()
        manifest = null

        if (mode == DataSourceMode.REMOTE) {
            val days = try {
                readManifest()
            } catch (failure: Exception) {
                fallback = "遠端無法使用（${reason(failure)}）"
                null
            }
            if (days != null) {
                usedRemote = true
                val complete = days.filterValues { it.contains(OfficialSources.TWSE_QUOTES) && it.contains(OfficialSources.TPEX_QUOTES) }
                    .keys.sorted()
                val workerLatest = complete.lastOrNull()
                val candidates = if (latest == null) {
                    complete.takeLast(COLD_START_REMOTE_DAYS)
                } else {
                    pendingWeekdays(latest, today, state.closed)
                }
                val missing = mutableListOf<Int>()
                if (latest == null && complete.isEmpty()) {
                    missing += TradingCalendar.recentWeekdays(today, COLD_START_DIRECT_DAYS)
                }
                val closed = state.closed.toMutableSet()
                var broken = false
                for (day in candidates) {
                    if (broken) {
                        missing += day
                        continue
                    }
                    when {
                        day in complete -> {
                            progress("下載 ${pretty(day)}（$REMOTE_LABEL）")
                            try {
                                if (ingestRemoteDay(day)) newDays += day else missing += day
                            } catch (failure: Exception) {
                                fallback = "遠端 ${pretty(day)} 失敗（${reason(failure)}）"
                                missing += day
                                broken = true
                            }
                        }
                        workerLatest != null && day < workerLatest && days.containsKey(day) -> closed += day
                        day == today -> if (minutes >= WORKER_LAST_RUN_MINUTES || workerLatest == null) missing += day
                        else -> missing += day
                    }
                }
                if (closed != state.closed) {
                    state = state.copy(closed = closed)
                    store.saveState(state)
                }
                if (missing.isNotEmpty() && fallback == null) {
                    fallback = if (workerLatest == null) "遠端還沒有資料" else "遠端缺 ${missing.joinToString("、") { pretty(it) }}"
                }
                needDirect = missing
            } else {
                needDirect = if (latest == null) TradingCalendar.recentWeekdays(today, COLD_START_DIRECT_DAYS)
                else pendingWeekdays(latest, today, state.closed)
            }
        } else {
            needDirect = if (latest == null) TradingCalendar.recentWeekdays(today, COLD_START_DIRECT_DAYS)
            else pendingWeekdays(latest, today, state.closed)
        }

        val before = direct.requests
        val remoteCount = newDays.size
        if (needDirect.isNotEmpty()) {
            val result = syncDirect(needDirect.sorted(), today, minutes)
            newDays += result.first
            error = result.second
        }
        val directCount = newDays.size - remoteCount
        state = store.state()

        // 除權息：直連下載的日子與遠端缺表的日子，用區間表補（上市一季一次、上櫃一月一次）。
        val newest = store.latestDay()
        if (newest != null) {
            if (state.exCheckedDay == 0 || TradingCalendar.shift(state.exCheckedDay, 7) <= today) {
                // 每週用直連區間表核對一次最近 5 週，補 Worker 沒存到的上櫃事件。
                val from = TradingCalendar.shift(newest, -35)
                state = state.copy(exPendingFrom = if (state.exPendingFrom == 0) from else minOf(state.exPendingFrom, from))
                store.saveState(state)
            }
            if (state.exPendingFrom > 0) state = refreshExRightsRange(state, state.exPendingFrom, newest, today)
            fillDetails()
            refreshSnapshots(newest, today, newDays.isNotEmpty() || forceSnapshots)
        }
        store.prune()
        val directRequests = direct.requests - before
        val finalState = store.state()
        store.saveState(finalState.copy(lastSyncAt = System.currentTimeMillis()))
        return SyncReport(
            mode = mode,
            usedRemote = usedRemote,
            usedDirect = directRequests > 0,
            fallbackReason = if (needDirect.isNotEmpty()) fallback else null,
            newDays = newDays.sorted(),
            asOf = store.latestDay(),
            error = error,
            directRequests = directRequests,
            remoteDays = remoteCount,
            directDays = directCount,
        )
    }

    /** 本機最新一天之後、到今天為止還沒確認的平日。 */
    private fun pendingWeekdays(latest: Int, today: Int, closed: Set<Int>): List<Int> {
        if (latest >= today) return emptyList()
        return TradingCalendar.weekdaysBetween(TradingCalendar.shift(latest, 1), today).filter { it !in closed }
    }

    private fun readManifest(): Map<Int, Set<String>> {
        val response = remoteHttp.get("$workerBase/manifest", mapOf("User-Agent" to APP_USER_AGENT))
        if (!response.ok) throw IOException("HTTP ${response.code}")
        val root = JSONObject(response.body)
        manifest = root
        val days = root.optJSONObject("days") ?: JSONObject()
        val out = HashMap<Int, Set<String>>()
        val keys = days.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val day = key.toIntOrNull() ?: continue
            val list = days.optJSONArray(key) ?: continue
            out[day] = List(list.length()) { list.optString(it) }.toSet()
        }
        return out
    }

    /** 一天的原文一次下載。行情不完整回 false，交給直連。 */
    private fun ingestRemoteDay(day: Int): Boolean {
        val response = remoteHttp.get("$workerBase/day/%08d".format(day), mapOf("User-Agent" to APP_USER_AGENT))
        if (!response.ok) throw IOException("HTTP ${response.code}")
        val bundle = JSONObject(response.body)
        val twse = bundle.optJSONObject(OfficialSources.TWSE_QUOTES) ?: return false
        val tpex = bundle.optJSONObject(OfficialSources.TPEX_QUOTES) ?: return false
        if (ingestQuotes(day, twse, tpex) != FetchStatus.OK) return false
        ingestChips(day, bundle.optJSONObject(OfficialSources.TWSE_T86), bundle.optJSONObject(OfficialSources.TPEX_INSTI))
        val twseEx = bundle.optJSONObject(OfficialSources.TWSE_EXRIGHTS)
        val tpexEx = bundle.optJSONObject(OfficialSources.TPEX_EXRIGHTS)
        val events = mutableListOf<ExEvent>()
        twseEx?.let { runCatching { parseTwseExSummary(it) }.getOrNull() }?.let { events += it }
        // 櫃買當天沒有除權息時 totalCount 是 0，Worker 不會存；缺表視為沒有事件，每週直連核對一次。
        tpexEx?.let { runCatching { parseTpexExRights(it) }.getOrNull() }?.let { events += it }
        if (events.isNotEmpty()) store.saveEvents(mergeEvents(store.events(), events))
        if (twseEx == null) {
            // 上市當天沒有事件也會回 stat OK，Worker 應該有存；缺表表示抓失敗，改用直連區間表補。
            val state = store.state()
            store.saveState(state.copy(exPendingFrom = if (state.exPendingFrom == 0) day else minOf(state.exPendingFrom, day)))
        }
        return true
    }

    private fun ingestQuotes(day: Int, twse: JSONObject, tpex: JSONObject): FetchStatus {
        val combined = combineDay(parseTwseMiIndex(twse), parseTpexDaily(tpex))
        if (combined.first == FetchStatus.OK) store.writeDay(day, combined.second)
        return combined.first
    }

    private fun ingestChips(day: Int, t86: JSONObject?, insti: JSONObject?): Boolean {
        val twse = t86?.let { runCatching { parseTwseT86(it) }.getOrNull() }
        val tpex = insti?.let { runCatching { parseTpexInsti(it) }.getOrNull() }
        val rows = combineChips(twse, tpex) ?: return false
        store.writeChips(day, rows)
        return true
    }

    /** 回傳（新入庫的日子, 停下來的原因）。 */
    private fun syncDirect(days: List<Int>, today: Int, minutes: Int): Pair<List<Int>, String?> {
        val ingested = mutableListOf<Int>()
        var error: String? = null
        val closed = store.state().closed.toMutableSet()
        for (day in days) {
            if (day == today && minutes < QUOTES_READY_MINUTES) break
            progress("下載 ${pretty(day)}（$DIRECT_LABEL）")
            val status = try {
                val twse = directJson(OfficialSources.url(OfficialSources.TWSE_QUOTES, day)) as JSONObject
                val tpex = directJson(OfficialSources.url(OfficialSources.TPEX_QUOTES, day)) as JSONObject
                ingestQuotes(day, twse, tpex)
            } catch (failure: Exception) {
                error = "直連 ${pretty(day)} 失敗（${reason(failure)}）"
                break
            }
            when (status) {
                FetchStatus.OK -> ingested += day
                FetchStatus.CLOSED -> if (day != today) closed += day else break
                FetchStatus.ERROR -> {
                    error = "直連 ${pretty(day)} 行情不完整，稍後再試"
                    break
                }
            }
        }
        val state = store.state()
        val firstNew = ingested.minOrNull()
        store.saveState(
            state.copy(
                closed = closed,
                exPendingFrom = when {
                    firstNew == null -> state.exPendingFrom
                    state.exPendingFrom == 0 -> firstNew
                    else -> minOf(state.exPendingFrom, firstNew)
                },
            ),
        )
        for (day in ingested.takeLast(CHIP_DAYS)) {
            progress("三大法人 ${pretty(day)}")
            runCatching {
                val t86 = directJson(OfficialSources.url(OfficialSources.TWSE_T86, day)) as? JSONObject
                val insti = directJson(OfficialSources.url(OfficialSources.TPEX_INSTI, day)) as? JSONObject
                ingestChips(day, t86, insti)
            }
        }
        return ingested to error
    }

    /** 直連區間表補除權息。成功才清掉待補起日；失敗留到下次同步。 */
    private fun refreshExRightsRange(state: StoreState, from: Int, to: Int, today: Int): StoreState {
        progress("除權息")
        val events = mutableListOf<ExEvent>()
        return try {
            for ((start, end) in TradingCalendar.quarters(from, to)) {
                val body = directJson(OfficialSources.twseExRightsUrl(start, end), mapOf("Referer" to OfficialSources.TWSE_EX_REFERER)) as JSONObject
                if (!statOk(body)) throw IOException("證交所除權息表：${text(body.opt("stat"))}")
                events += parseTwseExSummary(body)
            }
            for ((start, end) in TradingCalendar.months(from, to)) {
                val body = directJson(OfficialSources.tpexExRightsUrl(start, end)) as JSONObject
                if (!statOk(body)) throw IOException("櫃買除權息表：${text(body.opt("stat"))}")
                events += parseTpexExRights(body)
            }
            if (events.isNotEmpty()) store.saveEvents(mergeEvents(store.events(), events))
            val next = store.state().copy(exPendingFrom = 0, exCheckedDay = today)
            store.saveState(next)
            next
        } catch (failure: Exception) {
            store.state()
        }
    }

    /** 上市含「權」的事件查 TWT49UDetail，補上成交量因子。每次最多幾筆，失敗留到下次。 */
    private fun fillDetails() {
        val events = store.events()
        val pending = events.filter { it.needsDetail }.sortedByDescending { it.date }.take(DETAIL_PER_SYNC)
        if (pending.isEmpty()) return
        val updates = mutableListOf<ExEvent>()
        for (event in pending) {
            runCatching {
                val body = directJson(OfficialSources.twseExDetailUrl(event.code, event.date), mapOf("Referer" to OfficialSources.TWSE_EX_REFERER)) as JSONObject
                if (statOk(body)) {
                    val (stock, cash) = parseTwseExDetail(body)
                    updates += event.copy(volumeFactor = 1.0 + stock + cash, needsDetail = false)
                }
            }
        }
        if (updates.isNotEmpty()) store.saveEvents(mergeEvents(events, updates))
    }

    /**
     * 注意／處置清單與月營收，只留最新一份。遠端可用時讀 Worker 最新一天有存的快照，否則直連。
     */
    private fun refreshSnapshots(asOf: Int, today: Int, changed: Boolean) {
        val state = store.state()
        val before = store.risk()
        val stale = before == null || before.asOf != asOf ||
            System.currentTimeMillis() - state.riskFetchedAt > RISK_REFRESH_MS
        val remote = manifest?.optJSONObject("days")
        if (changed || stale) {
            progress("注意／處置清單")
            val notice = snapshot(remote, OfficialSources.TWSE_NOTICE, asOf, exactDay = true) as? JSONObject
            val punish = snapshot(remote, OfficialSources.TWSE_PUNISH, asOf) as? JSONArray
            val warning = snapshot(remote, OfficialSources.TPEX_WARNING, asOf) as? JSONArray
            val disposal = snapshot(remote, OfficialSources.TPEX_DISPOSAL, asOf) as? JSONArray
            store.saveRisk(buildRiskSnapshot(asOf, notice, punish, warning, disposal, before))
            store.saveState(store.state().copy(riskFetchedAt = System.currentTimeMillis()))
        }
        if (state.revenueFetchedDay != today) {
            progress("月營收")
            var merged = store.revenue()
            var ok = false
            for (source in listOf(OfficialSources.TWSE_REVENUE, OfficialSources.TPEX_REVENUE)) {
                val payload = snapshot(remote, source, asOf) as? JSONArray ?: continue
                runCatching { parseRevenueOpenApi(payload) }.getOrNull()?.let {
                    merged = mergeRevenue(merged, it)
                    ok = true
                }
            }
            if (ok) {
                store.saveRevenue(merged)
                store.saveState(store.state().copy(revenueFetchedDay = today))
            }
        }
    }

    /** 快照：遠端最新一天有存就讀遠端，否則直連。exactDay 的來源（當日注意股）只接受 asOf 那天。 */
    private fun snapshot(remoteDays: JSONObject?, source: String, asOf: Int, exactDay: Boolean = false): Any? {
        if (remoteDays != null) {
            val candidates = mutableListOf<Int>()
            val keys = remoteDays.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val list = remoteDays.optJSONArray(key) ?: continue
                val has = (0 until list.length()).any { list.optString(it) == source }
                val day = key.toIntOrNull() ?: continue
                if (has && (!exactDay || day == asOf)) candidates += day
            }
            val day = candidates.maxOrNull()
            if (day != null) {
                val value = runCatching {
                    val response = remoteHttp.get("$workerBase/raw/%08d/$source".format(day), mapOf("User-Agent" to APP_USER_AGENT))
                    if (response.ok) parseJsonValue(response.body) else null
                }.getOrNull()
                if (value != null) return value
            }
        }
        return runCatching { directJson(OfficialSources.url(source, asOf)) }.getOrNull()
    }

    private fun directJson(url: String, headers: Map<String, String> = emptyMap()): Any {
        val response = direct.get(url, headers)
        if (!response.ok) throw IOException("HTTP ${response.code}")
        return parseJsonValue(response.body)
    }

    private fun statOk(payload: JSONObject): Boolean =
        (text(payload.opt("stat")).ifEmpty { text(payload.opt("status")) }).lowercase() == "ok"

    private fun reason(error: Throwable): String = error.message?.take(80) ?: error.javaClass.simpleName
}

fun pretty(day: Int): String {
    val d = "%08d".format(day)
    return "${d.substring(0, 4)}/${d.substring(4, 6)}/${d.substring(6, 8)}"
}
