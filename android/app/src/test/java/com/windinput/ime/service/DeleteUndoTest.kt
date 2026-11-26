package com.windinput.ime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteUndoTest {

    private val u = DeleteUndo()

    // ── 删掉的是哪段 ─────────────────────────────────────────────

    @Test fun 单删取两次光标之间的那段() {
        assertEquals("c", DeleteUndo.deletedBefore("abc", "", oldStart = 3, newStart = 2, newEnd = 2))
    }

    @Test fun 单删emoji按两次光标的差取整个代理对() {
        val text = "a😀"   // a😀
        assertEquals("😀", DeleteUndo.deletedBefore(text, "", oldStart = 3, newStart = 1, newEnd = 1))
    }

    @Test fun 有选区时删的是选区() {
        assertEquals("bc", DeleteUndo.deletedBefore("a", "bc", oldStart = 1, newStart = 1, newEnd = 1))
    }

    @Test fun 光标没动就是没删() {
        assertEquals("", DeleteUndo.deletedBefore("abc", "", oldStart = 3, newStart = 3, newEnd = 3))
    }

    @Test fun 删后还有选区或光标右移或超出读到的范围都不认() {
        assertNull(DeleteUndo.deletedBefore("abc", "", oldStart = 3, newStart = 1, newEnd = 2))
        assertNull(DeleteUndo.deletedBefore("abc", "", oldStart = 3, newStart = 4, newEnd = 4))
        assertNull(DeleteUndo.deletedBefore("abc", "", oldStart = 9, newStart = 2, newEnd = 2))
    }

    // ── 记录与合并 ───────────────────────────────────────────────

    @Test fun 没删过不能恢复() {
        assertFalse(u.canRestore)
        assertNull(u.take(0, 0))
    }

    @Test fun 连续单删合成一条且恢复点跟着左移() {
        u.recordBackward("c", anchor = 2)
        u.recordBackward("b", anchor = 1)
        u.recordBackward("a", anchor = 0)
        val e = u.take(0, 0)!!
        assertEquals("abc", e.before)
        assertEquals("", e.after)
        assertEquals(0, e.anchor)
    }

    @Test fun 两次单删不相邻时只留后一次() {
        u.recordBackward("c", anchor = 5)
        u.recordBackward("x", anchor = 1)
        assertEquals("x", u.take(1, 1)!!.before)
    }

    @Test fun 扩选删与删光标前各记一条不和单删合并() {
        u.recordBackward("c", anchor = 2)
        u.record("ab", "", anchor = 0)
        assertEquals("ab", u.take(0, 0)!!.before)
    }

    @Test fun 扩选删之后的单删另起一条() {
        u.record("bc", "", anchor = 1)
        u.recordBackward("a", anchor = 0)
        assertEquals("a", u.take(0, 0)!!.before)
    }

    @Test fun 清空记前后两段() {
        u.record("ab", "cd", anchor = 0)
        val e = u.take(0, 0)!!
        assertEquals("ab", e.before)
        assertEquals("cd", e.after)
    }

    @Test fun 什么都没删不记() {
        u.recordBackward("", anchor = 3)
        u.record("", "", anchor = 0)
        assertFalse(u.canRestore)
    }

    @Test fun 恢复后清掉只能恢复一次() {
        u.recordBackward("c", anchor = 2)
        u.take(2, 2)
        assertFalse(u.canRestore)
        assertNull(u.take(2, 2))
    }

    @Test fun 恢复时光标不在记录点不恢复并作废() {
        u.recordBackward("c", anchor = 2)
        assertNull(u.take(5, 5))
        assertFalse(u.canRestore)
    }

    @Test fun 恢复时有选区不恢复() {
        u.recordBackward("c", anchor = 2)
        assertNull(u.take(0, 2))
    }

    // ── 作废 ─────────────────────────────────────────────────────

    @Test fun 主动作废() {
        u.recordBackward("c", anchor = 2)
        u.invalidate()
        assertFalse(u.canRestore)
    }

    @Test fun 删除自己引起的光标回报不作废() {
        u.recordBackward("c", anchor = 2)
        u.onSelectionUpdate(3, 3, 2, 2, 0)
        assertTrue(u.canRestore)
    }

    @Test fun 删除之前的旧回报晚到也不作废() {
        // 连删：上一下的回报（光标在 3）在这一下删完（光标到 2）之后才到
        u.recordBackward("d", anchor = 3)
        u.recordBackward("c", anchor = 2)
        u.onSelectionUpdate(4, 4, 3, 3, 0)
        u.onSelectionUpdate(3, 3, 2, 2, 0)
        assertTrue(u.canRestore)
        assertEquals("cd", u.take(2, 2)!!.before)
    }

    @Test fun 回报到了记录点之后光标再动就作废() {
        u.recordBackward("c", anchor = 2)
        u.onSelectionUpdate(3, 3, 2, 2, 0)
        u.onSelectionUpdate(2, 2, 0, 0, 0)
        assertFalse(u.canRestore)
    }

    @Test fun 回报到了记录点之后出现选区也作废() {
        u.recordBackward("c", anchor = 2)
        u.onSelectionUpdate(3, 3, 2, 2, 0)
        u.onSelectionUpdate(2, 2, 1, 2, 0)
        assertFalse(u.canRestore)
    }

    @Test fun 回到记录点的回报重复到达不作废() {
        u.recordBackward("c", anchor = 2)
        u.onSelectionUpdate(3, 3, 2, 2, 0)
        u.onSelectionUpdate(2, 2, 2, 2, 0)
        assertTrue(u.canRestore)
    }

    @Test fun 打字让光标右移回到旧位置也作废() {
        u.recordBackward("c", anchor = 2)
        u.onSelectionUpdate(3, 3, 2, 2, 0)
        u.onSelectionUpdate(2, 2, 3, 3, 0)
        assertFalse(u.canRestore)
    }

    // ── 删除结果晚到（KEYCODE_DEL 由应用再投递一次才删） ───────────────

    @Test fun 删完立刻读光标已动就当场记() {
        u.onBackwardDelete("abc", "", 3, 3, 2, 2, nowMs = 0)
        assertEquals("c", u.take(2, 2)!!.before)
    }

    @Test fun 删完光标还没动先挂起等回报再记() {
        u.onBackwardDelete("abc", "", 3, 3, 3, 3, nowMs = 0)
        assertFalse(u.canRestore)
        u.onSelectionUpdate(3, 3, 2, 2, nowMs = 50)
        assertTrue(u.canRestore)
        assertEquals("c", u.take(2, 2)!!.before)
    }

    @Test fun 挂起的回报就是删除自己的回报之后光标再动就作废() {
        u.onBackwardDelete("abc", "", 3, 3, 3, 3, nowMs = 0)
        u.onSelectionUpdate(3, 3, 2, 2, nowMs = 50)
        u.onSelectionUpdate(2, 2, 0, 0, nowMs = 90)
        assertFalse(u.canRestore)
    }

    @Test fun 连删两下都挂起按回报顺序合并() {
        u.onBackwardDelete("abc", "", 3, 3, 3, 3, nowMs = 0)
        u.onBackwardDelete("ab", "", 2, 2, 2, 2, nowMs = 50)
        u.onSelectionUpdate(3, 3, 2, 2, nowMs = 60)
        u.onSelectionUpdate(2, 2, 1, 1, nowMs = 110)
        assertEquals("bc", u.take(1, 1)!!.before)
    }

    @Test fun 有选区的单删挂起后按选区记一条() {
        u.onBackwardDelete("a", "bc", 1, 3, 1, 3, nowMs = 0)
        u.onSelectionUpdate(1, 3, 1, 1, nowMs = 40)
        assertEquals("bc", u.take(1, 1)!!.before)
    }

    @Test fun 挂起太久没等到回报就丢掉() {
        u.onBackwardDelete("abc", "", 3, 3, 3, 3, nowMs = 0)
        u.onSelectionUpdate(3, 3, 1, 1, nowMs = DeleteUndo.PENDING_TTL_MS + 1)
        assertFalse(u.canRestore)
    }

    @Test fun 作废时连挂起的一起丢() {
        u.onBackwardDelete("abc", "", 3, 3, 3, 3, nowMs = 0)
        u.invalidate()
        u.onSelectionUpdate(3, 3, 2, 2, nowMs = 50)
        assertFalse(u.canRestore)
    }
}
