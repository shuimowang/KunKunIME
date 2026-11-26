package com.windinput.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 底部避让取值规则的测试。荣耀 BKQ-AN00（Android 17）真机数据：手势导航 nav=0、
 * gesture=140px（40dp@560dpi），旧逻辑 max(nav, gesture) 垫了 40dp 空白——就是
 * 「键盘下方空白」反馈的根因。三键导航垫的是实色导航栏，不该动。
 */
class InputRootViewInsetsTest {

    private fun px(dp: Int, density: Float) = (dp * density).toInt()

    @Test
    fun `三键导航按导航栏高度避让`() {
        // nav=140px 实色导航栏，gesture 更小：取 nav
        assertEquals(140, InputRootView.chooseBottomAvoidance(nav = 140, gesture = 56, density = 3.5f))
    }

    @Test
    fun `两键导航同理取nav`() {
        assertEquals(96, InputRootView.chooseBottomAvoidance(nav = 96, gesture = 56, density = 3.5f))
    }

    @Test
    fun `手势导航垫到手势条上限为止`() {
        // 荣耀 MagicOS：gesture 报 140px（40dp），只垫 16dp 上限
        assertEquals(px(16, 3.5f), InputRootView.chooseBottomAvoidance(nav = 0, gesture = 140, density = 3.5f))
        // 小米 HyperOS：gesture 报 56px（约 20dp@2.75），高于上限同样钳到 16dp
        assertEquals(px(16, 2.75f), InputRootView.chooseBottomAvoidance(nav = 0, gesture = 56, density = 2.75f))
    }

    @Test
    fun `识别区低于上限时原样保留`() {
        // gesture=30px@2.75（约 11dp）：盖得住手势条，不用钳
        assertEquals(30, InputRootView.chooseBottomAvoidance(nav = 0, gesture = 30, density = 2.75f))
    }

    @Test
    fun `手势条被隐藏时垫0`() {
        assertEquals(0, InputRootView.chooseBottomAvoidance(nav = 0, gesture = 0, density = 3.5f))
    }

    @Test
    fun `恰好等于上限时不变`() {
        val cap = px(16, 3.5f)
        assertEquals(cap, InputRootView.chooseBottomAvoidance(nav = 0, gesture = cap, density = 3.5f))
    }
}
