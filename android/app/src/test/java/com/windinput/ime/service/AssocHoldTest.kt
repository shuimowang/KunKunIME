package com.windinput.ime.service

import com.windinput.ime.service.AssocHold.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

class AssocHoldTest {
    private val sent = mutableListOf<Boolean>()
    private val hold = AssocHold { sent += it }

    @Test
    fun `按下暂停、松手恢复`() {
        hold.set(Reason.TOUCH, true)
        hold.set(Reason.TOUCH, false)
        assertEquals(listOf(true, false), sent)
    }

    @Test
    fun `来源交叠时全部结束才恢复`() {
        hold.set(Reason.TOUCH, true)
        hold.set(Reason.POPUP, true) // 长按弹出提示卡
        hold.set(Reason.TOUCH, false) // 松手，卡片还开着
        assertEquals(listOf(true), sent)
        hold.set(Reason.POPUP, false)
        assertEquals(listOf(true, false), sent)
    }

    @Test
    fun `重复报同一状态不重复通知`() {
        hold.set(Reason.EXPANDED, true)
        hold.set(Reason.EXPANDED, true)
        hold.set(Reason.TOUCH, false)
        hold.set(Reason.EXPANDED, false)
        hold.set(Reason.EXPANDED, false)
        assertEquals(listOf(true, false), sent)
    }
}
