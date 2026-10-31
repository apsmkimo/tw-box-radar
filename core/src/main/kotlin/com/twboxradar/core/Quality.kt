package com.twboxradar.core

import java.util.Locale

/**
 * 相對強弱（RS）百分位與箱型品質。純計算，規則與 pipeline/twbox/quality.py 相同。
 *
 * RS：每檔 N 日報酬＝最新收盤 ／ N 個交易日前收盤 − 1（還原價）。在全市場排序後換成
 * 0～100 的百分位：比它低的檔數加上同分檔數的一半，除以（檔數 − 1）。最強的是 100。
 * K 線不足 N＋1 根的股票沒有 RS。window.json 有 180 根，所以 60 日與 120 日都算得出來。
 *
 * 箱型品質（只看今天以前的 K 線，與箱型判斷一致）：
 * - 天數：從箱頂那一根到昨天（含）共幾個交易日。
 * - 寬度：（箱頂 − 箱底）／箱底。
 * - 量縮比：箱內（箱頂那根到昨天）平均成交量 ÷ 箱頂前 20 個交易日平均量。小於 1 表示箱內量縮。
 *   箱頂前不足 20 根時不算。
 */

const val RS_SHORT = 60
const val RS_LONG = 120
const val CONTRACTION_BASE_DAYS = 20

data class BoxQuality(
    val days: Int,
    val width: Double,
    val volumeContraction: Double?,
)

fun periodReturn(bars: List<Bar>, period: Int): Double? {
    if (period < 1 || bars.size < period + 1) return null
    val base = bars[bars.size - 1 - period].close
    if (base <= 0.0) return null
    return bars.last().close / base - 1.0
}

/** 代號 → 0～100 的百分位（四捨五入成整數）。沒有報酬的代號不在結果裡。 */
fun percentileRanks(values: Map<String, Double>): Map<String, Int> {
    if (values.isEmpty()) return emptyMap()
    if (values.size == 1) return values.mapValues { 100 }
    val sorted = values.values.sorted()
    val n = sorted.size
    return values.mapValues { (_, value) ->
        val less = lowerBound(sorted, value)
        val equal = upperBound(sorted, value) - less
        val rank = (less + (equal - 1) / 2.0) / (n - 1)
        Math.round(rank * 100.0).toInt()
    }
}

private fun lowerBound(sorted: List<Double>, value: Double): Int {
    var lo = 0
    var hi = sorted.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (sorted[mid] < value) lo = mid + 1 else hi = mid
    }
    return lo
}

private fun upperBound(sorted: List<Double>, value: Double): Int {
    var lo = 0
    var hi = sorted.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (sorted[mid] <= value) lo = mid + 1 else hi = mid
    }
    return lo
}

fun boxQuality(bars: List<Bar>, box: Box?): BoxQuality? {
    if (box == null || box.bottom <= 0.0 || bars.size < 2) return null
    val prior = bars.dropLast(1)
    val start = prior.indexOfFirst { it.date == box.topDate }
    if (start < 0) return null
    val inside = prior.subList(start, prior.size)
    val width = (box.top - box.bottom) / box.bottom
    val contraction = if (start >= CONTRACTION_BASE_DAYS) {
        val before = prior.subList(start - CONTRACTION_BASE_DAYS, start)
        val baseAverage = before.sumOf { it.volume.toDouble() } / before.size
        val insideAverage = inside.sumOf { it.volume.toDouble() } / inside.size
        if (baseAverage > 0.0) insideAverage / baseAverage else null
    } else {
        null
    }
    return BoxQuality(inside.size, width, contraction)
}

/** 把全市場 RS 填回各列。rows 與 symbols 以代號對應。 */
fun withRelativeStrength(rows: List<ScreenRow>, symbols: List<SymbolHistory>): List<ScreenRow> {
    val short = percentileRanks(symbols.mapNotNull { s -> periodReturn(s.bars, RS_SHORT)?.let { s.code to it } }.toMap())
    val long = percentileRanks(symbols.mapNotNull { s -> periodReturn(s.bars, RS_LONG)?.let { s.code to it } }.toMap())
    return rows.map { it.copy(rs60 = short[it.code], rs120 = long[it.code]) }
}

enum class RowSort(val label: String) {
    CODE("代號"),
    RS60("RS 60 日"),
    RS120("RS 120 日"),
    BOX_WIDTH("箱寬（窄到寬）"),
    BOX_DAYS("箱型天數（長到短）"),
}

/** 沒有該欄位的列排在最後，同值再依代號。 */
fun sortRows(rows: List<ScreenRow>, sort: RowSort): List<ScreenRow> = when (sort) {
    RowSort.CODE -> rows.sortedBy { it.code }
    RowSort.RS60 -> rows.sortedWith(compareBy<ScreenRow> { it.rs60 == null }.thenByDescending { it.rs60 ?: 0 }.thenBy { it.code })
    RowSort.RS120 -> rows.sortedWith(compareBy<ScreenRow> { it.rs120 == null }.thenByDescending { it.rs120 ?: 0 }.thenBy { it.code })
    RowSort.BOX_WIDTH -> rows.sortedWith(compareBy<ScreenRow> { it.boxWidth == null }.thenBy { it.boxWidth ?: 0.0 }.thenBy { it.code })
    RowSort.BOX_DAYS -> rows.sortedWith(compareBy<ScreenRow> { it.boxDays == null }.thenByDescending { it.boxDays ?: 0 }.thenBy { it.code })
}

/** 列表與個股頁的一行，例如「RS60 85　RS120 72　箱 23 天　寬 8.5%　量縮 0.62 倍」。 */
fun describeQuality(row: ScreenRow): String = buildList {
    add("RS60 ${row.rs60?.toString() ?: "—"}")
    add("RS120 ${row.rs120?.toString() ?: "—"}")
    row.boxDays?.let { add("箱 $it 天") }
    row.boxWidth?.let { add("寬 ${formatPercent(it)}") }
    row.volumeContraction?.let { add(String.format(Locale.US, "箱內量 %.2f 倍", it)) }
}.joinToString("　")
