package com.twboxradar.core.local

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 官方端點。和 worker/src/index.js 的 SOURCES 完全相同，直連模式由 App 自己打。
 * 名稱也一樣，遠端（Cloudflare）模式 /day/YYYYMMDD 回來的鍵就是這些名稱。
 */
object OfficialSources {
    const val TWSE_QUOTES = "twse_quotes"
    const val TPEX_QUOTES = "tpex_quotes"
    const val TWSE_T86 = "twse_t86"
    const val TPEX_INSTI = "tpex_insti"
    const val TWSE_NOTICE = "twse_notice"
    const val TWSE_PUNISH = "twse_punish"
    const val TPEX_WARNING = "tpex_warning"
    const val TPEX_DISPOSAL = "tpex_disposal"
    const val TWSE_EXRIGHTS = "twse_exrights"
    const val TPEX_EXRIGHTS = "tpex_exrights"
    const val TWSE_REVENUE = "twse_revenue"
    const val TPEX_REVENUE = "tpex_revenue"

    /** 只有當下快照、不能指定日期的來源。 */
    val SNAPSHOTS = setOf(TWSE_PUNISH, TPEX_WARNING, TPEX_DISPOSAL, TWSE_REVENUE, TPEX_REVENUE)

    fun url(source: String, day: Int): String {
        val d = "%08d".format(day)
        return when (source) {
            TWSE_QUOTES -> "https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX?date=$d&type=ALLBUT0999&response=json"
            TPEX_QUOTES -> "https://www.tpex.org.tw/www/zh-tw/afterTrading/dailyQuotes?date=${slash(d)}&id=&response=json"
            TWSE_T86 -> "https://www.twse.com.tw/rwd/zh/fund/T86?date=$d&selectType=ALLBUT0999&response=json"
            TPEX_INSTI -> "https://www.tpex.org.tw/www/zh-tw/insti/dailyTrade?type=Daily&sect=EW&date=${slash(d)}&response=json"
            TWSE_NOTICE -> "https://www.twse.com.tw/rwd/zh/announcement/notice?startDate=$d&endDate=$d&response=json"
            TWSE_PUNISH -> "https://openapi.twse.com.tw/v1/announcement/punish"
            TPEX_WARNING -> "https://www.tpex.org.tw/openapi/v1/tpex_trading_warning_information"
            TPEX_DISPOSAL -> "https://www.tpex.org.tw/openapi/v1/tpex_disposal_information"
            TWSE_EXRIGHTS -> twseExRightsUrl(day, day)
            TPEX_EXRIGHTS -> tpexExRightsUrl(day, day)
            TWSE_REVENUE -> "https://openapi.twse.com.tw/v1/opendata/t187ap05_L"
            TPEX_REVENUE -> "https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap05_O"
            else -> throw IllegalArgumentException("未知來源 $source")
        }
    }

    fun twseExRightsUrl(start: Int, end: Int): String =
        "https://www.twse.com.tw/exchangeReport/TWT49U?response=json&startDate=%08d&endDate=%08d".format(start, end)

    fun tpexExRightsUrl(start: Int, end: Int): String =
        "https://www.tpex.org.tw/web/stock/exright/dailyquo/exDailyQ_result.php?l=zh-tw&d=${rocSlash("%08d".format(start))}&ed=${rocSlash("%08d".format(end))}&o=json"

    /** 上市含「權」的事件要查明細才知道配股與認購（exrights.py 的 twse_detail_url）。 */
    fun twseExDetailUrl(code: String, day: Int): String =
        "https://www.twse.com.tw/rwd/zh/exRight/TWT49UDetail?response=json&STK_NO=$code&T1=%08d".format(day)

    const val TWSE_EX_REFERER = "https://www.twse.com.tw/zh/announcement/ex-right/twt49u.html"

    private fun slash(d: String) = "${d.substring(0, 4)}/${d.substring(4, 6)}/${d.substring(6, 8)}"

    private fun rocSlash(d: String) = "${d.substring(0, 4).toInt() - 1911}/${d.substring(4, 6)}/${d.substring(6, 8)}"
}

/**
 * 交易日曆只排除週六日（calendar_util.py）。國定假日由「官方回應沒有行情」判定，不內建假日表。
 */
object TradingCalendar {
    private val BASIC = DateTimeFormatter.BASIC_ISO_DATE

    fun toDate(day: Int): LocalDate = LocalDate.parse("%08d".format(day), BASIC)

    fun toInt(date: LocalDate): Int = date.format(BASIC).toInt()

    fun isWeekday(day: Int): Boolean = toDate(day).dayOfWeek.let { it != DayOfWeek.SATURDAY && it != DayOfWeek.SUNDAY }

    fun shift(day: Int, delta: Long): Int = toInt(toDate(day).plusDays(delta))

    /** start～end（含）之間的平日。 */
    fun weekdaysBetween(start: Int, end: Int): List<Int> {
        val out = mutableListOf<Int>()
        var cursor = toDate(start)
        val last = toDate(end)
        while (!cursor.isAfter(last)) {
            if (cursor.dayOfWeek != DayOfWeek.SATURDAY && cursor.dayOfWeek != DayOfWeek.SUNDAY) out += toInt(cursor)
            cursor = cursor.plusDays(1)
        }
        return out
    }

    /** 到 today（含）為止最近 count 個平日，由舊到新。 */
    fun recentWeekdays(today: Int, count: Int): List<Int> {
        val out = ArrayList<Int>()
        var cursor = toDate(today)
        while (out.size < count) {
            if (cursor.dayOfWeek != DayOfWeek.SATURDAY && cursor.dayOfWeek != DayOfWeek.SUNDAY) out += toInt(cursor)
            cursor = cursor.minusDays(1)
        }
        return out.reversed()
    }

    /** 把 start～end 切成日曆季（上市除權息表一次查一季，exrights.py 的 iter_quarters）。 */
    fun quarters(start: Int, end: Int): List<Pair<Int, Int>> = spans(start, end, 3)

    /** 切成日曆月（櫃買除權息表一次查一個月）。 */
    fun months(start: Int, end: Int): List<Pair<Int, Int>> = spans(start, end, 1)

    private fun spans(start: Int, end: Int, months: Long): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        val first = toDate(start)
        val last = toDate(end)
        val startMonth = if (months == 3L) ((first.monthValue - 1) / 3) * 3 + 1 else first.monthValue
        var cursor = LocalDate.of(first.year, startMonth, 1)
        while (!cursor.isAfter(last)) {
            val next = cursor.plusMonths(months)
            val spanStart = if (cursor.isBefore(first)) first else cursor
            val spanEnd = next.minusDays(1).let { if (it.isAfter(last)) last else it }
            out += toInt(spanStart) to toInt(spanEnd)
            cursor = next
        }
        return out
    }
}
