package com.twboxradar.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AdSlotsTest {
    @Test
    fun insertsOneAdAfterEveryEightStocks() {
        val slots = insertAdSlots((1..16).toList(), every = 8, enabled = true)
        val kinds = slots.map { slot ->
            when (slot) {
                is ListSlot.Item -> "s${slot.value}"
                is ListSlot.Ad -> "ad${slot.afterCount}"
            }
        }
        assertEquals(
            listOf(
                "s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "ad8",
                "s9", "s10", "s11", "s12", "s13", "s14", "s15", "s16", "ad16",
            ),
            kinds,
        )
    }

    @Test
    fun shortListHasNoAd() {
        val slots = insertAdSlots((1..7).toList())
        assertEquals(7, slots.size)
        assertEquals(true, slots.all { it is ListSlot.Item })
    }

    @Test
    fun disabledKeepsTheOriginalOrder() {
        val slots = insertAdSlots(listOf("a", "b"), enabled = false)
        assertEquals(listOf("a", "b"), slots.map { (it as ListSlot.Item).value })
    }
}
