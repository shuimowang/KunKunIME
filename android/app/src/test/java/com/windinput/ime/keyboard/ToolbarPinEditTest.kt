package com.windinput.ime.keyboard

import com.windinput.ime.keyboard.ToolbarItem.CLIPBOARD
import com.windinput.ime.keyboard.ToolbarItem.EMOJI
import com.windinput.ime.keyboard.ToolbarItem.SETTINGS
import com.windinput.ime.keyboard.ToolbarItem.TEXT_EDIT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolbarPinEditTest {

    private val three = listOf(EMOJI, CLIPBOARD, TEXT_EDIT)

    @Test
    fun removeDropsOnlyThatItemAndKeepsOrder() {
        assertEquals(listOf(EMOJI, TEXT_EDIT), ToolbarPinEdit.remove(three, CLIPBOARD))
    }

    @Test
    fun removeOfAbsentItemIsNoChange() {
        assertEquals(three, ToolbarPinEdit.remove(three, SETTINGS))
    }

    @Test
    fun addAppendsToTheEnd() {
        assertEquals(listOf(EMOJI, CLIPBOARD, TEXT_EDIT, SETTINGS), ToolbarPinEdit.add(three, SETTINGS))
    }

    @Test
    fun addOfPinnedItemIsNoChange() {
        assertEquals(three, ToolbarPinEdit.add(three, EMOJI))
    }

    @Test
    fun addWhenFullIsRefused() {
        val full = ToolbarItem.entries.take(ToolbarItem.MAX_PINS)
        val extra = ToolbarItem.entries[ToolbarItem.MAX_PINS]
        assertNull(ToolbarPinEdit.add(full, extra))
    }

    @Test
    fun moveForwardShiftsTheOthersBack() {
        assertEquals(listOf(CLIPBOARD, TEXT_EDIT, EMOJI), ToolbarPinEdit.move(three, 0, 2))
    }

    @Test
    fun moveBackwardShiftsTheOthersForward() {
        assertEquals(listOf(TEXT_EDIT, EMOJI, CLIPBOARD), ToolbarPinEdit.move(three, 2, 0))
    }

    @Test
    fun dropIndexIsTheSlotUnderTheCenter() {
        // 槽位间距 100：中心 250 落在第 2 格（0 起）
        assertEquals(2, ToolbarPinEdit.dropIndex(centerX = 250f, slotPitch = 100f, count = 3))
    }

    @Test
    fun dropIndexClampsToTheLastPinNotTheEmptySlots() {
        // 3 个常驻、5 个槽：拖到第 4 格的空位上，也只能落到最后一个常驻的位置
        assertEquals(2, ToolbarPinEdit.dropIndex(centerX = 420f, slotPitch = 100f, count = 3))
    }

    @Test
    fun dropIndexClampsAtTheLeftEdge() {
        assertEquals(0, ToolbarPinEdit.dropIndex(centerX = -30f, slotPitch = 100f, count = 3))
    }

    @Test
    fun othersBetweenFromAndToStepTowardTheGap() {
        // 0 拖到 2：原 1、2 各左移一格，被拖项本身不算
        assertEquals(listOf(0, -1, -1), (0..2).map { ToolbarPinEdit.shiftOf(it, from = 0, to = 2) })
        // 2 拖到 0：原 0、1 各右移一格
        assertEquals(listOf(1, 1, 0), (0..2).map { ToolbarPinEdit.shiftOf(it, from = 2, to = 0) })
    }

    @Test
    fun nothingShiftsWhenDroppedInPlace() {
        assertEquals(listOf(0, 0, 0), (0..2).map { ToolbarPinEdit.shiftOf(it, from = 1, to = 1) })
    }
}
