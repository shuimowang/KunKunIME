package com.windinput.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolbarItemTest {

    @Test
    fun defaultPinsAreKeyboardEmojiClipboardAndTextEdit() {
        assertEquals(
            listOf(ToolbarItem.KEYBOARD, ToolbarItem.EMOJI, ToolbarItem.CLIPBOARD, ToolbarItem.TEXT_EDIT),
            ToolbarItem.parse(ToolbarItem.DEFAULT_PINS),
        )
    }

    @Test
    fun parseSkipsUnknownAndDuplicateIds() {
        assertEquals(
            listOf(ToolbarItem.EMOJI, ToolbarItem.CLIPBOARD),
            ToolbarItem.parse("emoji, nope,clipboard,emoji"),
        )
    }

    @Test
    fun parseCapsAtMaxPins() {
        val all = ToolbarItem.format(ToolbarItem.entries)
        assertEquals(ToolbarItem.MAX_PINS, ToolbarItem.parse(all).size)
    }

    @Test
    fun emptyConfigMeansNoPins() {
        assertEquals(emptyList<ToolbarItem>(), ToolbarItem.parse(""))
    }
}
