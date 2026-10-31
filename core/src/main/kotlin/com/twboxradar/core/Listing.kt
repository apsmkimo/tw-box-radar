package com.twboxradar.core

const val DEFAULT_DATA_BRANCH = "main"
const val PREVIEW_DATA_BRANCH = "cursor/tw-box-radar-mvp-ba55"

private val BRANCH_PATTERN = Regex("^[A-Za-z0-9._/-]{1,120}$")

/** 空白或無效的分支名稱回到 main，避免網址被拼壞。 */
fun normalizeDataBranch(branch: String): String {
    val trimmed = branch.trim()
    if (trimmed.isEmpty()) return DEFAULT_DATA_BRANCH
    if (!BRANCH_PATTERN.matches(trimmed) || trimmed.contains("..")) return DEFAULT_DATA_BRANCH
    return trimmed
}

fun marketDocsUrl(branch: String): String {
    val ref = normalizeDataBranch(branch)
    return "https://raw.githubusercontent.com/apsmkimo/tw-box-radar/$ref/docs"
}

fun membershipLabel(status: BoxStatus): String = when (status) {
    BoxStatus.BREAKOUT -> "在「今日突破箱頂」"
    BoxStatus.INSIDE -> "在「箱型內整理中」"
    BoxStatus.NEAR_BOTTOM -> "在「接近箱底」"
    BoxStatus.BREAKDOWN -> "不在這三個分頁（跌破箱底）"
    BoxStatus.NONE -> "不在這三個分頁（尚無有效箱型）"
}

/**
 * 沒有搜尋字時只留目前分頁。有搜尋字時改查全部股票，分頁不再把結果濾掉。
 * 市場篩選兩種情況都保留。
 */
fun visibleRows(
    rows: List<ScreenRow>,
    tab: BoxStatus,
    market: String,
    query: String,
    sort: RowSort = RowSort.CODE,
): List<ScreenRow> {
    val text = query.trim()
    val searching = text.isNotEmpty()
    return rows
        .filter { row ->
            val marketOk = market == "all" || row.market == market
            val textOk = !searching ||
                row.code.contains(text, ignoreCase = true) ||
                row.name.contains(text, ignoreCase = true)
            val tabOk = searching || row.status == tab
            marketOk && textOk && tabOk
        }
        .let { filtered ->
            // 搜尋時以符合程度排序；沒有搜尋字時用使用者選的排序。
            if (searching) {
                filtered.sortedWith(compareBy<ScreenRow> { searchRank(it, text) }.thenBy { it.code })
            } else {
                sortRows(filtered, sort)
            }
        }
}

private fun searchRank(row: ScreenRow, query: String): Int = when {
    row.code.equals(query, ignoreCase = true) -> 0
    row.code.startsWith(query) -> 1
    row.name.startsWith(query) -> 2
    else -> 3
}
