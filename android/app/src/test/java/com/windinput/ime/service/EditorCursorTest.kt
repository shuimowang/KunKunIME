package com.windinput.ime.service

import com.windinput.ime.panels.TextEditingWindow.EditAction.*
import org.junit.Assert.*
import org.junit.Test

class EditorCursorTest {
    @Test fun partialExtractReturnsAbsoluteOffsetsAcrossMultilineSupplementaryText() {
        val text = "A𠮷\nB🙂D\n尾"
        val base = 500
        assertEquals(501, editorCaretTarget(text, base, 503, LEFT))
        assertEquals(503, editorCaretTarget(text, base, 501, RIGHT))
        assertEquals(504, editorCaretTarget(text, base, 507, HOME))
        assertEquals(508, editorCaretTarget(text, base, 507, END))
        assertEquals(507, editorCaretTarget(text, base, 503, DOWN))
        assertEquals(503, editorCaretTarget(text, base, 507, UP))
        assertEquals(510, editorCaretTarget(text, base, 507, DOWN))
        assertNull(editorCaretTarget(text, base, 499, LEFT))
        assertNull(editorCaretTarget(text, base, 511, RIGHT))
    }

    @Test fun cursorAndDeletionRespectCharacterBoundaries() {
        val text = "甲🙂e\u0301𠮷"
        assertEquals(5, backspaceSelectionStart(text, text.length, 1))
        assertEquals(3, backspaceSelectionStart(text, text.length, 2))
        assertEquals(1, backspaceSelectionStart(text, text.length, 3))
        assertEquals(0, backspaceSelectionStart(text, text.length, 20))
        assertEquals(3, backspaceSelectionStart(text, 3, 0))
        assertNull(backspaceSelectionStart(text, 2, 1))
        assertNull(backspaceSelectionStart(text, -1, 1))
        assertEquals(3, codePointTarget(text, 1, 1))
        assertEquals(1, codePointTarget(text, 3, -1))
        assertEquals(text.length, codePointTarget(text, 3, 1000))
        assertEquals(0, codePointTarget(text, 3, -1000))
    }

    @Test fun extendingSelectingAcrossCrlfDoesNotSplitTheLineEnding() {
        val text = "𠮷\r\nYZ"
        assertEquals(102, editorCaretTarget(text, 100, 100, END))
        assertEquals(104, editorCaretTarget(text, 100, 102, RIGHT))
        assertEquals(105, editorCaretTarget(text, 100, 102, DOWN))
        assertEquals(102, editorCaretTarget(text, 100, 105, UP))
    }
}
