package com.twboxradar.core.local

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** 本機保留的交易日數。約 12 個月，足夠 120 日 RS 與 60 日箱型。 */
const val KEEP_TRADING_DAYS = 250

/** 一檔一天的成交原價（未還原）。 */
data class DayQuote(
    val code: String,
    val market: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)

/** 同步狀態。closed 是確認過的休市平日；seedDone 表示初始歷史已匯入過。 */
data class StoreState(
    val closed: Set<Int> = emptySet(),
    val seedDone: Boolean = false,
    val seedSource: String = "",
    val revenueFetchedDay: Int = 0,
    val lastSyncAt: Long = 0L,
    val lastSource: String = "",
    val lastMessage: String = "",
    /** 除權息待用直連區間表補的起日（0 表示沒有）。 */
    val exPendingFrom: Int = 0,
    /** 上次用區間表核對除權息的日子。 */
    val exCheckedDay: Int = 0,
    val riskFetchedAt: Long = 0L,
)

/**
 * 裝置上的盤後資料。只存解析過的精簡格式：
 *
 * - q/YYYYMMDD.gz：每行「代號 市場 開 高 低 收 股數」，成交原價、只有普通股。
 * - c/YYYYMMDD.gz：每行「代號 外資 投信 自營商」，單位股。
 * - names.tsv：代號、名稱、市場（取最新一天）。
 * - exrights.tsv：除權息事件。
 * - revenue.tsv：月營收（每檔最近 15 個月）。
 * - risk.json：注意／處置清單，只留最新一份。
 * - state.json：休市日、初始資料狀態、最後同步來源。
 *
 * 每個交易日壓縮後約 40 KB，250 天約 10 MB。寫檔都先寫暫存檔再改名，中斷也不會留下半個檔。
 */
class LocalStore(val root: File) {
    private val quotesDir = File(root, "q")
    private val chipsDir = File(root, "c")

    fun tradingDays(): List<Int> = daysIn(quotesDir)

    fun latestDay(): Int? = tradingDays().lastOrNull()

    fun hasDay(day: Int): Boolean = dayFile(quotesDir, day).exists()

    fun chipDays(): List<Int> = daysIn(chipsDir)

    fun writeDay(day: Int, rows: List<QuoteRow>) {
        val body = buildString(rows.size * 48) {
            for (row in rows.sortedBy { it.code }) {
                append(row.code).append('\t').append(if (row.market == "tpex") 'o' else 't').append('\t')
                append(num(row.open)).append('\t').append(num(row.high)).append('\t')
                append(num(row.low)).append('\t').append(num(row.close)).append('\t')
                append(row.volume).append('\n')
            }
        }
        writeGzip(dayFile(quotesDir, day), body)
        updateNames(day, rows)
    }

    fun readDay(day: Int): List<DayQuote> {
        val file = dayFile(quotesDir, day)
        if (!file.exists()) return emptyList()
        val out = ArrayList<DayQuote>(2100)
        readGzipLines(file) { line ->
            val parts = line.split('\t')
            if (parts.size >= 7) {
                out += DayQuote(
                    code = parts[0],
                    market = if (parts[1] == "o") "tpex" else "twse",
                    open = parts[2].toDouble(),
                    high = parts[3].toDouble(),
                    low = parts[4].toDouble(),
                    close = parts[5].toDouble(),
                    volume = parts[6].toLong(),
                )
            }
        }
        return out
    }

    fun writeChips(day: Int, rows: Map<String, LongArray>) {
        val body = buildString(rows.size * 32) {
            for (code in rows.keys.sorted()) {
                val v = rows.getValue(code)
                append(code).append('\t').append(v[0]).append('\t').append(v[1]).append('\t').append(v[2]).append('\n')
            }
        }
        writeGzip(dayFile(chipsDir, day), body)
    }

    fun readChips(day: Int): Map<String, LongArray>? {
        val file = dayFile(chipsDir, day)
        if (!file.exists()) return null
        val out = HashMap<String, LongArray>(2100)
        readGzipLines(file) { line ->
            val p = line.split('\t')
            if (p.size >= 4) out[p[0]] = longArrayOf(p[1].toLong(), p[2].toLong(), p[3].toLong())
        }
        return out
    }

    /** 代號 → (名稱, 市場)。 */
    fun names(): Map<String, Pair<String, String>> {
        val file = File(root, "names.tsv")
        if (!file.exists()) return emptyMap()
        val out = HashMap<String, Pair<String, String>>()
        file.forEachLine(Charsets.UTF_8) { line ->
            if (line.startsWith("#")) return@forEachLine
            val p = line.split('\t')
            if (p.size >= 3) out[p[0]] = p[1] to p[2]
        }
        return out
    }

    private fun namesDay(): Int {
        val file = File(root, "names.tsv")
        if (!file.exists()) return 0
        return file.bufferedReader(Charsets.UTF_8).use { it.readLine() }?.removePrefix("#")?.toIntOrNull() ?: 0
    }

    /** 名稱以最新一天為準；回補較舊的日子只補還沒有的代號。 */
    private fun updateNames(day: Int, rows: List<QuoteRow>) {
        val current = names().toMutableMap()
        val newer = day >= namesDay()
        for (row in rows) {
            if (newer || row.code !in current) current[row.code] = row.name to row.market
        }
        val stamp = maxOf(day, namesDay())
        val body = buildString {
            append('#').append(stamp).append('\n')
            for (code in current.keys.sorted()) {
                val (name, market) = current.getValue(code)
                append(code).append('\t').append(name.replace('\t', ' ')).append('\t').append(market).append('\n')
            }
        }
        writeText(File(root, "names.tsv"), body)
    }

    fun events(): List<ExEvent> {
        val file = File(root, "exrights.tsv")
        if (!file.exists()) return emptyList()
        val out = ArrayList<ExEvent>()
        file.forEachLine(Charsets.UTF_8) { line ->
            val p = line.split('\t')
            if (p.size >= 5) {
                out += ExEvent(
                    date = p[1].toInt(),
                    code = p[0],
                    priceFactor = p[2].toDouble(),
                    volumeFactor = p[3].toDouble(),
                    needsDetail = p[4] == "1",
                )
            }
        }
        return out
    }

    fun saveEvents(events: List<ExEvent>) {
        val body = buildString {
            for (e in events.sortedWith(compareBy<ExEvent> { it.code }.thenBy { it.date })) {
                append(e.code).append('\t').append(e.date).append('\t').append(e.priceFactor).append('\t')
                append(e.volumeFactor).append('\t').append(if (e.needsDetail) '1' else '0').append('\n')
            }
        }
        writeText(File(root, "exrights.tsv"), body)
    }

    fun revenue(): Map<String, Map<Int, Long>> {
        val file = File(root, "revenue.tsv")
        if (!file.exists()) return emptyMap()
        val out = HashMap<String, HashMap<Int, Long>>()
        file.forEachLine(Charsets.UTF_8) { line ->
            val p = line.split('\t')
            if (p.size >= 3) out.getOrPut(p[0]) { HashMap() }[p[1].toInt()] = p[2].toLong()
        }
        return out
    }

    fun saveRevenue(store: Map<String, Map<Int, Long>>) {
        val body = buildString {
            for (code in store.keys.sorted()) {
                val values = store.getValue(code)
                for (month in values.keys.sorted()) {
                    append(code).append('\t').append(month).append('\t').append(values.getValue(month)).append('\n')
                }
            }
        }
        writeText(File(root, "revenue.tsv"), body)
    }

    fun risk(): RiskSnapshot? {
        val file = File(root, "risk.json")
        if (!file.exists()) return null
        return runCatching {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val notice = obj.optJSONArray("notice") ?: JSONArray()
            val punish = obj.optJSONObject("punish") ?: JSONObject()
            val sources = obj.optJSONObject("sources") ?: JSONObject()
            val punishMap = HashMap<String, PunishItem>()
            val keys = punish.keys()
            while (keys.hasNext()) {
                val code = keys.next()
                val item = punish.optJSONObject(code) ?: continue
                val start = if (item.has("start") && !item.isNull("start")) item.optInt("start") else null
                punishMap[code] = PunishItem(start, item.optInt("end"), item.optString("measure", "處置"))
            }
            val sourceMap = LinkedHashMap<String, String>()
            val sourceKeys = sources.keys()
            while (sourceKeys.hasNext()) {
                val key = sourceKeys.next()
                sourceMap[key] = sources.optString(key)
            }
            RiskSnapshot(
                asOf = obj.optInt("asOf"),
                notice = List(notice.length()) { notice.optString(it) }.toSet(),
                punish = punishMap,
                sources = sourceMap,
            )
        }.getOrNull()
    }

    fun saveRisk(snapshot: RiskSnapshot) {
        val obj = JSONObject()
        obj.put("asOf", snapshot.asOf)
        val notice = JSONArray()
        snapshot.notice.sorted().forEach { notice.put(it) }
        obj.put("notice", notice)
        val punish = JSONObject()
        for (code in snapshot.punish.keys.sorted()) {
            val item = snapshot.punish.getValue(code)
            val entry = JSONObject()
            entry.put("start", item.start ?: JSONObject.NULL)
            entry.put("end", item.end)
            entry.put("measure", item.measure)
            punish.put(code, entry)
        }
        obj.put("punish", punish)
        val sources = JSONObject()
        snapshot.sources.forEach { (key, value) -> sources.put(key, value) }
        obj.put("sources", sources)
        writeText(File(root, "risk.json"), obj.toString())
    }

    fun state(): StoreState {
        val file = File(root, "state.json")
        if (!file.exists()) return StoreState()
        return runCatching {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val closed = obj.optJSONArray("closed") ?: JSONArray()
            StoreState(
                closed = List(closed.length()) { closed.optInt(it) }.toSet(),
                seedDone = obj.optBoolean("seedDone", false),
                seedSource = obj.optString("seedSource", ""),
                revenueFetchedDay = obj.optInt("revenueFetchedDay", 0),
                lastSyncAt = obj.optLong("lastSyncAt", 0L),
                lastSource = obj.optString("lastSource", ""),
                lastMessage = obj.optString("lastMessage", ""),
                exPendingFrom = obj.optInt("exPendingFrom", 0),
                exCheckedDay = obj.optInt("exCheckedDay", 0),
                riskFetchedAt = obj.optLong("riskFetchedAt", 0L),
            )
        }.getOrDefault(StoreState())
    }

    fun saveState(state: StoreState) {
        val obj = JSONObject()
        val closed = JSONArray()
        state.closed.sorted().forEach { closed.put(it) }
        obj.put("closed", closed)
        obj.put("seedDone", state.seedDone)
        obj.put("seedSource", state.seedSource)
        obj.put("revenueFetchedDay", state.revenueFetchedDay)
        obj.put("lastSyncAt", state.lastSyncAt)
        obj.put("lastSource", state.lastSource)
        obj.put("lastMessage", state.lastMessage)
        obj.put("exPendingFrom", state.exPendingFrom)
        obj.put("exCheckedDay", state.exCheckedDay)
        obj.put("riskFetchedAt", state.riskFetchedAt)
        writeText(File(root, "state.json"), obj.toString())
    }

    /**
     * 只留最新 keep 個交易日。更早的行情、法人與除權息事件、休市紀錄一起刪掉。
     */
    fun prune(keep: Int = KEEP_TRADING_DAYS) {
        val days = tradingDays()
        if (days.size > keep) {
            days.dropLast(keep).forEach { dayFile(quotesDir, it).delete() }
        }
        val oldest = tradingDays().firstOrNull() ?: return
        chipDays().filter { it < oldest }.forEach { dayFile(chipsDir, it).delete() }
        val events = events()
        val kept = events.filter { it.date > oldest }
        if (kept.size != events.size) saveEvents(kept)
        val state = state()
        val closed = state.closed.filter { it > oldest }.toSet()
        if (closed.size != state.closed.size) saveState(state.copy(closed = closed))
    }

    /**
     * 匯入另一份同格式的資料（APK 內建的初始資料）。只補本機沒有的日子；
     * 除權息、營收、名稱以本機已有的為準。回傳補進來的交易日數。
     */
    fun importFrom(seed: LocalStore): Int {
        var imported = 0
        for (day in seed.tradingDays()) {
            if (hasDay(day)) continue
            copyFile(seed.dayFile(seed.quotesDir, day), dayFile(quotesDir, day))
            imported++
        }
        for (day in seed.chipDays()) {
            if (!dayFile(chipsDir, day).exists()) copyFile(seed.dayFile(seed.chipsDir, day), dayFile(chipsDir, day))
        }
        saveEvents(mergeEvents(seed.events(), events()))
        saveRevenue(mergeRevenue(seed.revenue(), revenue()))
        val names = seed.names().toMutableMap()
        names.putAll(names())
        val stamp = maxOf(namesDay(), seed.namesDay())
        writeText(
            File(root, "names.tsv"),
            buildString {
                append('#').append(stamp).append('\n')
                for (code in names.keys.sorted()) {
                    val (name, market) = names.getValue(code)
                    append(code).append('\t').append(name).append('\t').append(market).append('\n')
                }
            },
        )
        return imported
    }

    private fun copyFile(from: File, to: File) {
        to.parentFile?.mkdirs()
        val tmp = File(to.parentFile, to.name + ".tmp")
        from.copyTo(tmp, overwrite = true)
        replace(tmp, to)
    }

    /** 本機資料大約佔用的位元組數。 */
    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        root.deleteRecursively()
    }

    private fun dayFile(dir: File, day: Int) = File(dir, "%08d.gz".format(day))

    private fun daysIn(dir: File): List<Int> =
        (dir.list() ?: emptyArray())
            .filter { it.endsWith(".gz") }
            .mapNotNull { it.removeSuffix(".gz").toIntOrNull() }
            .sorted()

    private fun writeGzip(file: File, body: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        GZIPOutputStream(tmp.outputStream().buffered()).use { it.write(body.toByteArray(Charsets.UTF_8)) }
        replace(tmp, file)
    }

    private fun readGzipLines(file: File, action: (String) -> Unit) {
        GZIPInputStream(file.inputStream().buffered()).bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotEmpty()) action(line)
            }
        }
    }

    private fun writeText(file: File, body: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(body, Charsets.UTF_8)
        replace(tmp, file)
    }

    private fun replace(tmp: File, target: File) {
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IllegalStateException("無法寫入 ${target.name}")
        }
    }

    private fun num(value: Double): String {
        val whole = value.toLong()
        return if (whole.toDouble() == value) whole.toString() else value.toString()
    }
}
