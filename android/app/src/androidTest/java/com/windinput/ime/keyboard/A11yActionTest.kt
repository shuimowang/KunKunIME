package com.windinput.ime.keyboard

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 读屏用户「双击」一个键 / 候选时，系统调的是无障碍节点的 ACTION_CLICK。这里直接调它，
 * 验证真的走到了按键 / 选词回调——adb 注入的触摸会绕过 TalkBack 的触摸探索，模拟器上
 * 用 adb 验不了这条路，只能这样在进程里验。
 */
@RunWith(AndroidJUnit4::class)
class A11yActionTest {

    private val inst = InstrumentationRegistry.getInstrumentation()

    /** 量好、排好，让控件算出命中区域（无障碍节点由它而来） */
    private fun layOut(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    /** 在虚拟节点里按 content-desc 找 id（ExploreByTouchHelper 的 id 是 0 起的下标） */
    private fun findVirtualId(view: View, desc: String): Int {
        val provider = view.accessibilityNodeProvider ?: error("没有无障碍节点提供者")
        for (id in 0 until 200) {
            val node: AccessibilityNodeInfo = provider.createAccessibilityNodeInfo(id) ?: continue
            if (node.contentDescription?.toString() == desc) return id
        }
        error("找不到描述为「$desc」的虚拟节点")
    }

    @Test
    fun clickingKeyNodeTypesTheKey() {
        inst.runOnMainSync {
            val view = KeyboardView(inst.targetContext)
            val actions = mutableListOf<KeyAction>()
            view.listener = object : KeyboardView.Listener {
                override fun onKeyAction(action: KeyAction, shifted: Boolean) {
                    actions += action
                }
                override fun onCursorMove(delta: Int) = Unit
                override fun onBackspaceSelect(count: Int) = Unit
                override fun onBackspaceSwipeDelete() = Unit
                override fun onBackspaceDeleteBeforeCursor() = Unit
                override fun onBackspaceClearAll() = Unit
                override fun onKeyLongPress(action: KeyAction) = Unit
            }
            layOut(view, 1080, 2000)

            val q = findVirtualId(view, "Q")
            assertTrue(view.accessibilityNodeProvider!!.performAction(q, AccessibilityNodeInfo.ACTION_CLICK, null))
            val del = findVirtualId(view, "删除")
            assertTrue(view.accessibilityNodeProvider!!.performAction(del, AccessibilityNodeInfo.ACTION_CLICK, null))

            assertEquals(listOf(KeyAction.Text('q'), KeyAction.Backspace), actions)
        }
    }

    @Test
    fun candidateNodeReadsCommentAndSelectsOnClick() {
        inst.runOnMainSync {
            val view = CandidateBarView(inst.targetContext)
            val picked = mutableListOf<Int>()
            val longPressed = mutableListOf<Int>()
            view.listener = object : CandidateBarView.Listener {
                override fun onCandidateSelected(index: Int) {
                    picked += index
                }
                override fun onCandidateLongPressed(index: Int) {
                    longPressed += index
                }
            }
            layOut(view, 1080, 400)
            view.setCandidates(listOf("工", "式", "节"), listOf("", "a", "b"))

            val shi = findVirtualId(view, "式，编码 a")
            val provider = view.accessibilityNodeProvider!!
            assertTrue(provider.performAction(shi, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertTrue(provider.performAction(findVirtualId(view, "工"), AccessibilityNodeInfo.ACTION_LONG_CLICK, null))

            assertEquals(listOf(1), picked)
            assertEquals(listOf(0), longPressed)
        }
    }
}
