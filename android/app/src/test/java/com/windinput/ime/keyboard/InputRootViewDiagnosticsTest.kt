package com.windinput.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 避让与布局诊断文本的纯函数测试：荣耀 / 小米真机数据下，分支与钳制标注必须说对。 */
class InputRootViewDiagnosticsTest {

    @Test
    fun `手势导航且超过上限时标注钳制生效`() {
        // 荣耀：gesture 140px，16dp@3.5 = 56px
        val text = InputRootView.describeAvoidance(nav = 0, gesture = 140, density = 3.5f, result = 56)
        assertTrue(text, "分支=纯手势" in text)
        assertTrue(text, "钳制=生效" in text)
        assertTrue(text, "cap=56px" in text)
        assertTrue(text, "结果=56" in text)
    }

    @Test
    fun `手势导航未超上限时标注未触发`() {
        val text = InputRootView.describeAvoidance(nav = 0, gesture = 40, density = 3.5f, result = 40)
        assertTrue(text, "钳制=未触发" in text)
    }

    @Test
    fun `三键导航标注走nav分支`() {
        val text = InputRootView.describeAvoidance(nav = 140, gesture = 56, density = 3.5f, result = 140)
        assertTrue(text, "分支=导航栏(取nav)" in text)
        assertTrue(text, "钳制" !in text)
    }

    @Test
    fun `布局诊断带出底边到屏幕底的距离`() {
        val text = InputRootView.describeLayout(
            rootH = 900, spacerH = 56, contentH = 844, topOnScreen = 1800, screenH = 2800, extraBottom = 0,
        )
        assertTrue(text, "根视图高=900" in text)
        assertTrue(text, "底部占位=56(含用户留空0)" in text)
        // 1800 + 900 = 2700，离屏幕底 100px：说明窗口下方还有系统留出的空白
        assertTrue(text, "底边屏幕y=2700" in text)
        assertTrue(text, "底边距屏幕底=100" in text)
        assertEquals(1, Regex("底边距屏幕底=").findAll(text).count())
    }
}
