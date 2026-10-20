package com.twboxradar.ui

import com.twboxradar.core.ScreenRow
import java.util.Locale
import kotlin.math.abs

fun formatPrice(value: Double?): String {
    if (value == null) return "—"
    return if (abs(value - value.toLong()) < 0.0001) {
        String.format(Locale.US, "%,.0f", value)
    } else {
        String.format(Locale.US, "%,.2f", value)
    }
}

fun formatDate(value: Int?): String {
    if (value == null) return "—"
    val text = value.toString().padStart(8, '0')
    if (text.length != 8) return text
    return "${text.substring(0, 4)}-${text.substring(4, 6)}-${text.substring(6, 8)}"
}

fun formatDateText(value: String): String {
    if (value.length != 8) return value
    return "${value.substring(0, 4)}-${value.substring(4, 6)}-${value.substring(6, 8)}"
}

fun formatRatio(value: Double?): String {
    if (value == null) return "量能不足"
    return String.format(Locale.US, "量比 %.2f", value)
}

fun boxLine(row: ScreenRow): String {
    return "箱頂 ${formatPrice(row.top)}　箱底 ${formatPrice(row.bottom)}　收盤 ${formatPrice(row.close)}"
}
