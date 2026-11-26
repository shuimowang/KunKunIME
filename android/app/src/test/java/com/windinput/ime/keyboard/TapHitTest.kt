package com.windinput.ime.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点按漂移判定（B4：「手指碰到字母却没上屏」）。
 *
 * 此前手指一出按下键的矩形、落进键缝就按「同行最近键」重判，只要换了键就 cancelPress——
 * 快打时拇指滚动几像素就足以让一次点按整个丢掉。现在以按下键为准，加键缝一半 + touchSlop 的容差。
 */
class TapHitTest {
    // 按下的键：x 100..140、y 200..250；键缝 6px、touchSlop 24px => 容差 27px
    private val rect = TapHit.Rect(100f, 200f, 140f, 250f)
    private val margin = TapHit.margin(gapPx = 6f, slopPx = 24f)
    private val swipeThreshold = 80f

    @Test
    fun margin_is_half_gap_plus_slop() {
        assertTrue(margin == 27f)
    }

    @Test
    fun inside_key_is_within_zone() {
        assertTrue(TapHit.withinTapZone(rect, 120f, 225f, margin))
    }

    @Test
    fun drift_into_gap_toward_neighbour_still_counts_as_same_key() {
        // 出键右沿 10px：旧逻辑会按同行最近键改判成右邻键并取消
        assertTrue(TapHit.withinTapZone(rect, 150f, 225f, margin))
    }

    @Test
    fun drift_beyond_margin_leaves_the_zone() {
        assertFalse(TapHit.withinTapZone(rect, 140f + margin + 1f, 225f, margin))
        assertFalse(TapHit.withinTapZone(rect, 120f, 250f + margin + 1f, margin))
    }

    @Test
    fun small_drift_does_not_cancel_the_tap() {
        assertFalse(TapHit.shouldCancelOnMove(rect, 150f, 225f, margin, dyUp = 0f, swipeThresholdPx = swipeThreshold))
    }

    @Test
    fun clear_slide_to_another_key_cancels() {
        assertTrue(TapHit.shouldCancelOnMove(rect, 200f, 225f, margin, dyUp = 0f, swipeThresholdPx = swipeThreshold))
    }

    @Test
    fun upward_move_toward_swipe_never_cancels_here() {
        // 上滑意图（dy 已过阈值一半）由上滑气泡那条路接管，不在这里取消
        assertFalse(TapHit.shouldCancelOnMove(rect, 200f, 120f, margin, dyUp = 40f, swipeThresholdPx = swipeThreshold))
    }

    @Test
    fun slide_down_out_of_key_cancels() {
        assertTrue(TapHit.shouldCancelOnMove(rect, 120f, 250f + margin + 5f, margin, dyUp = -60f, swipeThresholdPx = swipeThreshold))
    }
}
