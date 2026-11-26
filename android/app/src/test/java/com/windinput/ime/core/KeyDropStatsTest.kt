package com.windinput.ime.core

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyDropStatsTest {
    private var t = 1_000L
    private val stats = KeyDropStats(capacity = 3) { t++ }

    @Test
    fun counts_each_reason_independently() {
        stats.record(KeyDropStats.Reason.DRIFT_CANCEL)
        stats.record(KeyDropStats.Reason.DRIFT_CANCEL)
        stats.record(KeyDropStats.Reason.CANCEL_ACTION)
        assertEquals(2L, stats.count(KeyDropStats.Reason.DRIFT_CANCEL))
        assertEquals(1L, stats.count(KeyDropStats.Reason.CANCEL_ACTION))
        assertEquals(0L, stats.count(KeyDropStats.Reason.POPUP_CANCEL))
    }

    @Test
    fun recent_keeps_only_last_n_in_order_with_timestamps() {
        for (r in listOf("a", "b", "c", "d")) stats.record(KeyDropStats.Reason.SECOND_FINGER_IGNORED, r)
        val recent = stats.recent()
        assertEquals(listOf("b", "c", "d"), recent.map { it.detail })
        assertEquals(listOf(1001L, 1002L, 1003L), recent.map { it.timeMs })
    }

    @Test
    fun counts_survive_ring_overflow() {
        repeat(10) { stats.record(KeyDropStats.Reason.CORE_BUSY_BYPASS) }
        assertEquals(10L, stats.count(KeyDropStats.Reason.CORE_BUSY_BYPASS))
        assertEquals(3, stats.recent().size)
    }

    @Test
    fun summary_lists_nonzero_reasons_only() {
        stats.record(KeyDropStats.Reason.POPUP_CANCEL)
        val s = stats.summary()
        assert("POPUP_CANCEL=1" in s) { s }
        assert("DRIFT_CANCEL" !in s) { s }
    }

    @Test
    fun reset_clears_everything() {
        stats.record(KeyDropStats.Reason.NO_INPUT_CONNECTION)
        stats.reset()
        assertEquals(0L, stats.count(KeyDropStats.Reason.NO_INPUT_CONNECTION))
        assertEquals(0, stats.recent().size)
    }
}
