package com.windinput.ime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTraceTest {

    @Test
    fun `有类别时写进方括号，无类别保持原样`() {
        assertEquals("01:02:03.004 [insets] x", UiTrace.formatLine("01:02:03.004", UiTrace.INSETS, "x"))
        assertEquals("01:02:03.004 x", UiTrace.formatLine("01:02:03.004", null, "x"))
        assertEquals("01:02:03.004 x", UiTrace.formatLine("01:02:03.004", "", "x"))
    }

    @Test
    fun `缓冲上限放宽到150条且只留最新的`() {
        assertEquals(150, UiTrace.MAX)
        repeat(UiTrace.MAX + 20) { UiTrace.add(UiTrace.LAYOUT, "n$it") }
        val snap = UiTrace.snapshot()
        assertEquals(UiTrace.MAX, snap.size)
        assertTrue(snap.last().endsWith("n${UiTrace.MAX + 19}"))
        assertFalse(snap.any { it.endsWith(" n0") })
    }

    @Test
    fun `edge-to-edge 仅在 API35 及 target35 以上强制`() {
        assertFalse(UiTrace.edgeToEdgeForced(sdk = 34, targetSdk = 36))
        assertFalse(UiTrace.edgeToEdgeForced(sdk = 36, targetSdk = 34))
        assertTrue(UiTrace.edgeToEdgeForced(sdk = 35, targetSdk = 35))
        assertTrue(UiTrace.edgeToEdgeForced(sdk = 36, targetSdk = 36))
    }

    /** 隐私：只记包名与输入类型位，缺失时写 ?，绝不带输入框文字 */
    @Test
    fun `输入记录只含包名与类型位`() {
        val text = UiTrace.describeInput("com.tencent.docs", 0x20001, 0x4000006, restarting = false, cutoutMode = 3)
        assertEquals(
            "开始输入 pkg=com.tencent.docs inputType=0x20001 imeOptions=0x4000006 restarting=false cutoutMode=3",
            text,
        )
        assertTrue("pkg=?" in UiTrace.describeInput(null, 0, 0, true, null))
        assertTrue("cutoutMode=?" in UiTrace.describeInput(null, 0, 0, true, null))
    }
}
