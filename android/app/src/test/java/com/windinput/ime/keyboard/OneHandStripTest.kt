package com.windinput.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class OneHandStripTest {

    /** 开关：关着 → 上次那一侧（没用过是右手）；开着 → 关 */
    @Test
    fun toggleRemembersLastSide() {
        assertEquals(OneHandStrip.RIGHT, OneHandStrip.toggled(OneHandStrip.OFF, ""))
        assertEquals(OneHandStrip.LEFT, OneHandStrip.toggled(OneHandStrip.OFF, OneHandStrip.LEFT))
        assertEquals(OneHandStrip.OFF, OneHandStrip.toggled(OneHandStrip.RIGHT, OneHandStrip.RIGHT))
        assertEquals(OneHandStrip.OFF, OneHandStrip.toggled(OneHandStrip.LEFT, OneHandStrip.RIGHT))
    }

    @Test
    fun switchSideFlips() {
        assertEquals(OneHandStrip.LEFT, OneHandStrip.otherSide(OneHandStrip.RIGHT))
        assertEquals(OneHandStrip.RIGHT, OneHandStrip.otherSide(OneHandStrip.LEFT))
    }
}
