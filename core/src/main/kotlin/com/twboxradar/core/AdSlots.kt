package com.twboxradar.core

sealed interface ListSlot<out T> {
    data class Item<T>(val value: T) : ListSlot<T>
    data class Ad(val afterCount: Int) : ListSlot<Nothing>
}

/** 每 `every` 檔股票後面插一個廣告位。未啟用或不足一整組時不插。 */
fun <T> insertAdSlots(items: List<T>, every: Int = 8, enabled: Boolean = true): List<ListSlot<T>> {
    if (!enabled || every <= 0) return items.map { ListSlot.Item(it) }
    val out = ArrayList<ListSlot<T>>(items.size + items.size / every)
    items.forEachIndexed { index, item ->
        out += ListSlot.Item(item)
        val count = index + 1
        if (count % every == 0) out += ListSlot.Ad(count)
    }
    return out
}
