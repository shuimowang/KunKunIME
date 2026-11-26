package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ImeSetupTest {

    @Test
    fun stepFollowsEnableThenSwitch() {
        assertEquals(ImeSetup.ENABLE, ImeSetup.of(enabled = false, current = false))
        assertEquals(ImeSetup.SWITCH, ImeSetup.of(enabled = true, current = false))
        assertEquals(ImeSetup.READY, ImeSetup.of(enabled = true, current = true))
    }

    /** 已是当前输入法却查不到启用（个别系统）：照样算装好，不能让用户去启用一个已在用的输入法 */
    @Test
    fun currentWinsOverEnabledQuery() {
        assertEquals(ImeSetup.READY, ImeSetup.of(enabled = false, current = true))
    }
}
