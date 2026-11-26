package com.windinput.ime.keyboard

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 多指快打（rollover）：上一指未抬、下一指已落。adb 的 `input tap` 只能单指，
 * 模拟器脚本测不了，这里在进程里直接给 KeyboardView 喂多指 MotionEvent。
 */
@RunWith(AndroidJUnit4::class)
class MultiTouchTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val downTime = SystemClock.uptimeMillis()

    private fun newKeyboard(actions: MutableList<KeyAction>): KeyboardView {
        val view = KeyboardView(inst.targetContext)
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
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    /** 按无障碍节点的描述取键中心（节点区域即命中区域） */
    private fun center(view: View, desc: String): Pair<Float, Float> {
        val provider = view.accessibilityNodeProvider!!
        for (id in 0 until 200) {
            val node = provider.createAccessibilityNodeInfo(id) ?: continue
            if (node.contentDescription?.toString() == desc) {
                val r = Rect()
                @Suppress("DEPRECATION")
                node.getBoundsInParent(r)
                return r.exactCenterX() to r.exactCenterY()
            }
        }
        error("找不到键「$desc」")
    }

    /** 一根手指：指针 id + 位置 */
    private class Finger(val id: Int, val at: Pair<Float, Float>)

    /** [action] 已含 actionIndex（即 [fingers] 里的下标） */
    private fun event(action: Int, vararg fingers: Finger): MotionEvent {
        val points = fingers.map { it.at }
        val props = Array(fingers.size) { i ->
            MotionEvent.PointerProperties().apply { id = fingers[i].id; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(points.size) { i ->
            MotionEvent.PointerCoords().apply { x = points[i].first; y = points[i].second; pressure = 1f; size = 1f }
        }
        return MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, points.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun pointer(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    @Test
    fun secondFingerDownCommitsFirstKeyAndTypesBoth() {
        inst.runOnMainSync {
            val actions = mutableListOf<KeyAction>()
            val view = newKeyboard(actions)
            val q = center(view, "Q")
            val w = center(view, "W")

            view.onTouchEvent(event(MotionEvent.ACTION_DOWN, Finger(0, q)))
            view.onTouchEvent(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), Finger(0, q), Finger(1, w)))
            view.onTouchEvent(event(pointer(MotionEvent.ACTION_POINTER_UP, 0), Finger(0, q), Finger(1, w)))
            view.onTouchEvent(event(MotionEvent.ACTION_UP, Finger(1, w)))

            assertEquals(listOf(KeyAction.Text('q'), KeyAction.Text('w')), actions)
        }
    }

    @Test
    fun laterFingerLiftingFirstStillTypesEachKeyOnce() {
        inst.runOnMainSync {
            val actions = mutableListOf<KeyAction>()
            val view = newKeyboard(actions)
            val q = center(view, "Q")
            val w = center(view, "W")

            view.onTouchEvent(event(MotionEvent.ACTION_DOWN, Finger(0, q)))
            view.onTouchEvent(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), Finger(0, q), Finger(1, w)))
            // 后落的 W 先抬，先落的 Q（已在 W 落下时收尾）后抬
            view.onTouchEvent(event(pointer(MotionEvent.ACTION_POINTER_UP, 1), Finger(0, q), Finger(1, w)))
            view.onTouchEvent(event(MotionEvent.ACTION_UP, Finger(0, q)))

            assertEquals(listOf(KeyAction.Text('q'), KeyAction.Text('w')), actions)
        }
    }

    @Test
    fun secondFingerOnNumberPadSideColumnDoesNotTypeANeighbourKey() {
        inst.runOnMainSync {
            val actions = mutableListOf<KeyAction>()
            val view = newKeyboard(actions)
            view.setLayer(KeyboardLayer.NUMBER)
            view.sideSymbols = listOf("+", "-")
            view.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val five = center(view, "5")
            // 左列（侧列符号区）与 5 同一行：侧列不是键，以前会命中同行最近的「4」
            val side = 20f to five.second

            view.onTouchEvent(event(MotionEvent.ACTION_DOWN, Finger(0, five)))
            view.onTouchEvent(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), Finger(0, five), Finger(1, side)))
            view.onTouchEvent(event(pointer(MotionEvent.ACTION_POINTER_UP, 1), Finger(0, five), Finger(1, side)))
            view.onTouchEvent(event(MotionEvent.ACTION_UP, Finger(0, five)))

            assertEquals(1, actions.size)
        }
    }

    @Test
    fun movesOfAFinishedFingerDoNotCancelTheActiveKey() {
        inst.runOnMainSync {
            val actions = mutableListOf<KeyAction>()
            val view = newKeyboard(actions)
            val q = center(view, "Q")
            val w = center(view, "W")
            val p = center(view, "P")

            view.onTouchEvent(event(MotionEvent.ACTION_DOWN, Finger(0, q)))
            view.onTouchEvent(event(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), Finger(0, q), Finger(1, w)))
            // 已收尾的第一指滑到 P：只跟第二指，不该把 W 判成「滑出键位」取消
            view.onTouchEvent(event(MotionEvent.ACTION_MOVE, Finger(0, p), Finger(1, w)))
            view.onTouchEvent(event(pointer(MotionEvent.ACTION_POINTER_UP, 1), Finger(0, p), Finger(1, w)))
            view.onTouchEvent(event(MotionEvent.ACTION_UP, Finger(0, p)))

            assertEquals(listOf(KeyAction.Text('q'), KeyAction.Text('w')), actions)
        }
    }
}
