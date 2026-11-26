package com.windinput.ime.keyboard

import com.windinput.ime.keyboard.BackspaceGestureMachine.Bubble
import org.junit.Assert.assertEquals
import org.junit.Test

class BackspaceGestureMachineTest {

    // 1px = 1 单位：activate 16、threshold 40、step 12、left 28
    private val cfg = BackspaceGestureMachine.Config.forDensity(1f)
    private val actions = mutableListOf<String>()
    private val delays = mutableListOf<Long>()
    private var timerActive = false

    private val timer = object : BackspaceGestureMachine.Timer {
        override fun schedule(delayMs: Long) {
            delays += delayMs
            timerActive = true
        }

        override fun cancel() {
            timerActive = false
        }
    }

    private val listener = object : BackspaceGesture.Listener {
        override fun onDelete() { actions += "delete" }
        override fun onSelect(count: Int) { actions += "select:$count" }
        override fun onSelectionDelete() { actions += "selDel" }
        override fun onDeleteBeforeCursor() { actions += "delBefore" }
        override fun onClearAll() { actions += "clearAll" }
        override fun onRestoreDeleted() { actions += "restore" }
        override fun canRestoreDeleted() = restorable
    }

    private var restorable = true

    private val bubbles = mutableListOf<Bubble>()
    private val m = BackspaceGestureMachine(cfg, listener, timer) { bubbles += it }

    private fun down() = m.down(100f, 100f, 300L)

    @Test fun 单击抬手删一个() {
        down(); m.up()
        assertEquals(listOf("delete"), actions)
    }

    @Test fun 长按连删节奏为300加50且抬手不再补单击() {
        down()
        m.onTimer(); m.onTimer(); m.onTimer()
        m.up()
        assertEquals(listOf(300L, 50L, 50L, 50L), delays)
        assertEquals(listOf("delete", "delete", "delete"), actions)
        assertEquals(false, timerActive)
    }

    @Test fun 左滑N字符回调select加N() {
        down()
        m.move(100f - 17f, 100f)   // 超过 activate：1 字
        m.move(100f - 50f, 100f)   // 50/12 = 4
        assertEquals(listOf("select:1", "select:4"), actions)
    }

    @Test fun 左滑扩选后抬手删选区() {
        down(); m.move(60f, 100f); m.up()
        assertEquals(listOf("select:3", "selDel"), actions)
    }

    @Test fun 左滑再滑回起点收回选区且抬手不删() {
        down(); m.move(60f, 100f); m.move(95f, 100f); m.up()
        assertEquals(listOf("select:3", "select:0"), actions)
    }

    @Test fun 左滑回起点再上滑过阈值抬手删光标前() {
        down()
        m.move(60f, 100f)
        m.move(100f, 100f)
        m.move(100f, 70f)    // 上滑 30：气泡出现，未过阈值
        m.move(100f, 50f)    // 上滑 50：过阈值
        m.up()
        assertEquals(listOf("select:3", "select:0", "delBefore"), actions)
    }

    @Test fun 上滑正上方抬手删光标前() {
        down(); m.move(100f, 40f); m.up()
        assertEquals(listOf("delBefore"), actions)
        assertEquals(listOf(Bubble.BEFORE, Bubble.HIDDEN), bubbles)
    }

    @Test fun 上滑后左移过阈值抬手清空() {
        down(); m.move(100f, 40f); m.move(60f, 40f); m.up()
        assertEquals(listOf("clearAll"), actions)
        assertEquals(Bubble.ALL, bubbles[bubbles.size - 2])
    }

    @Test fun 上滑后左移再右移回正上方改为删光标前() {
        down(); m.move(100f, 40f); m.move(60f, 40f); m.move(100f, 40f); m.up()
        assertEquals(listOf("delBefore"), actions)
    }

    @Test fun 上滑未过阈值抬手不删() {
        down(); m.move(100f, 70f); m.up()
        assertEquals(emptyList<String>(), actions)
        assertEquals(listOf(Bubble.IDLE, Bubble.HIDDEN), bubbles)
    }

    @Test fun 上滑过阈值再滑回起点抬手不删且不补单击() {
        down(); m.move(100f, 40f); m.move(100f, 100f); m.up()
        assertEquals(emptyList<String>(), actions)
    }

    @Test fun 上滑滑回起点后可直接左滑扩选() {
        down(); m.move(100f, 40f); m.move(100f, 100f); m.move(60f, 100f); m.up()
        assertEquals(listOf("select:3", "selDel"), actions)
    }

    @Test fun 手势开关关闭时只剩单击() {
        val off = object : BackspaceGesture.Listener by listener {
            override fun swipeEnabled() = false
        }
        val machine = BackspaceGestureMachine(cfg, off, timer) { bubbles += it }
        machine.down(100f, 100f, 300L); machine.move(100f, 20f); machine.up()
        assertEquals(listOf("delete"), actions)
    }

    @Test fun 连删起始延迟取按下时传入的值() {
        m.down(100f, 100f, 650L)
        assertEquals(listOf(650L), delays)
    }

    @Test fun 上滑气泡下滑收起后残留左移不进扩选() {
        down()
        m.move(100f, 40f); m.move(60f, 40f); m.move(60f, 95f)
        m.up()
        assertEquals(emptyList<String>(), actions)
    }

    @Test fun 上滑气泡收起后回到水平原点再左滑可扩选() {
        down()
        m.move(100f, 40f); m.move(60f, 40f); m.move(60f, 95f)
        m.move(100f, 95f)     // 回到水平原点：解除限制
        m.move(60f, 95f)
        m.up()
        assertEquals(listOf("select:3", "selDel"), actions)
    }

    @Test fun 激活阈值附近抖动不反复触发扩选() {
        down()
        for (i in 0 until 4) { m.move(100f - 17f, 100f); m.move(100f - 15f, 100f) }
        assertEquals(listOf("select:1"), actions)
    }

    @Test fun 激活阈值附近抖动气泡不反复切换() {
        down()
        for (i in 0 until 4) { m.move(100f, 100f - 17f); m.move(100f, 100f - 15f) }
        assertEquals(listOf(Bubble.IDLE), bubbles)
    }

    @Test fun 选项阈值附近抖动不在IDLE与BEFORE间反复切换() {
        down()
        m.move(100f, 100f - 42f)
        for (i in 0 until 4) { m.move(100f, 100f - 38f); m.move(100f, 100f - 42f) }
        assertEquals(listOf(Bubble.BEFORE), bubbles)
        m.move(100f, 100f - 34f)   // 低于回差下沿才退回 IDLE
        assertEquals(listOf(Bubble.BEFORE, Bubble.IDLE), bubbles)
    }

    @Test fun 清空阈值附近抖动不在BEFORE与ALL间反复切换() {
        down()
        m.move(100f, 40f); m.move(100f - 30f, 40f)
        for (i in 0 until 4) { m.move(100f - 26f, 40f); m.move(100f - 30f, 40f) }
        assertEquals(listOf(Bubble.BEFORE, Bubble.ALL), bubbles)
    }

    @Test fun 滑动一旦开始就停掉连删计时() {
        down(); m.move(60f, 100f)
        assertEquals(false, timerActive)
    }

    @Test fun 扩选途中被取消选区收回光标() {
        down(); m.move(60f, 100f); m.cancel()
        assertEquals(listOf("select:3", "select:0"), actions)
    }

    // ── 下滑恢复 ─────────────────────────────────────────────────

    @Test fun 下滑过阈值抬手恢复() {
        down(); m.move(100f, 160f); m.up()
        assertEquals(listOf("restore"), actions)
        assertEquals(listOf(Bubble.RESTORE_ARMED, Bubble.HIDDEN), bubbles)
    }

    @Test fun 下滑先出未选中气泡再过阈值高亮() {
        down(); m.move(100f, 130f); m.move(100f, 150f)
        assertEquals(listOf(Bubble.RESTORE, Bubble.RESTORE_ARMED), bubbles)
    }

    @Test fun 下滑未过阈值抬手不恢复也不补单击() {
        down(); m.move(100f, 130f); m.up()
        assertEquals(emptyList<String>(), actions)
    }

    @Test fun 下滑过阈值再滑回起点抬手反悔() {
        down(); m.move(100f, 160f); m.move(100f, 100f); m.up()
        assertEquals(emptyList<String>(), actions)
        assertEquals(listOf(Bubble.RESTORE_ARMED, Bubble.HIDDEN), bubbles)
    }

    @Test fun 下滑阈值附近抖动不反复切换() {
        down()
        m.move(100f, 142f)
        for (i in 0 until 4) { m.move(100f, 138f); m.move(100f, 142f) }
        assertEquals(listOf(Bubble.RESTORE_ARMED), bubbles)
        m.move(100f, 134f)   // 低于回差下沿才退回未选中
        assertEquals(listOf(Bubble.RESTORE_ARMED, Bubble.RESTORE), bubbles)
    }

    @Test fun 没有可恢复内容时下滑照旧是单击() {
        restorable = false
        down(); m.move(100f, 160f); m.up()
        assertEquals(listOf("delete"), actions)
        assertEquals(emptyList<Bubble>(), bubbles)
    }

    @Test fun 没有可恢复内容时下滑不打断连删() {
        restorable = false
        down(); m.move(100f, 160f)
        assertEquals(true, timerActive)
    }

    @Test fun 手势开关关闭时下滑也只是单击() {
        val off = object : BackspaceGesture.Listener by listener {
            override fun swipeEnabled() = false
        }
        val machine = BackspaceGestureMachine(cfg, off, timer) { bubbles += it }
        machine.down(100f, 100f, 300L); machine.move(100f, 160f); machine.up()
        assertEquals(listOf("delete"), actions)
    }

    @Test fun 斜下偏横向为主时不进下滑() {
        down(); m.move(60f, 120f); m.up()
        assertEquals(listOf("select:3", "selDel"), actions)
    }

    @Test fun 扩选中往下移不串成恢复() {
        down(); m.move(60f, 100f); m.move(60f, 160f); m.up()
        assertEquals(listOf("select:3", "selDel"), actions)
    }

    @Test fun 下滑中往左移不串成扩选() {
        down(); m.move(100f, 160f); m.move(60f, 160f); m.up()
        assertEquals(listOf("restore"), actions)
    }

    @Test fun 下滑后直接上滑改为删光标前() {
        down(); m.move(100f, 160f); m.move(100f, 40f); m.up()
        assertEquals(listOf("delBefore"), actions)
    }

    @Test fun 上滑后直接下滑改为恢复() {
        down(); m.move(100f, 40f); m.move(100f, 160f); m.up()
        assertEquals(listOf("restore"), actions)
    }

    @Test fun 下滑气泡收起后残留左移不进扩选() {
        down(); m.move(100f, 160f); m.move(60f, 160f); m.move(60f, 105f); m.up()
        assertEquals(emptyList<String>(), actions)
    }
}
