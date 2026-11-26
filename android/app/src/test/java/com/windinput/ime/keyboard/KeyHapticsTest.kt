package com.windinput.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyHapticsTest {

    /** 强度单调：调大一定不会变轻 */
    @Test
    fun amplitudeAndDurationGrowWithStrength() {
        for (s in 5 until 100) {
            assertTrue(KeyHaptics.amplitudeFor(s + 1) >= KeyHaptics.amplitudeFor(s))
            assertTrue(KeyHaptics.durationFor(s + 1) >= KeyHaptics.durationFor(s))
        }
    }

    /** 两端落在振动器接受的范围里：幅度 0 会抛异常，255 是上限 */
    @Test
    fun boundsAreValid() {
        assertEquals(255, KeyHaptics.amplitudeFor(100))
        assertTrue(KeyHaptics.amplitudeFor(0) >= 1)
        assertEquals(20L, KeyHaptics.durationFor(100))
    }
}
