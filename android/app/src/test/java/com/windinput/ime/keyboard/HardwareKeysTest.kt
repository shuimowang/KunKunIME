package com.windinput.ime.keyboard

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardwareKeysTest {

    @Test
    fun lowercaseLetterIsPlainText() {
        assertEquals(
            HardwareKeys.Mapped(KeyAction.Text('a'), false),
            HardwareKeys.map(KeyEvent.KEYCODE_A, 'a'.code, 0),
        )
    }

    @Test
    fun shiftedLetterBecomesLowercaseWithShift() {
        assertEquals(
            HardwareKeys.Mapped(KeyAction.Text('a'), true),
            HardwareKeys.map(KeyEvent.KEYCODE_A, 'A'.code, KeyEvent.META_SHIFT_ON),
        )
    }

    @Test
    fun shiftedSymbolKeepsTheResolvedChar() {
        assertEquals(
            HardwareKeys.Mapped(KeyAction.Text('!'), false),
            HardwareKeys.map(KeyEvent.KEYCODE_1, '!'.code, KeyEvent.META_SHIFT_ON),
        )
    }

    @Test
    fun editingKeysMapToTheirActions() {
        assertEquals(KeyAction.Space, HardwareKeys.map(KeyEvent.KEYCODE_SPACE, ' '.code, 0)?.action)
        assertEquals(KeyAction.Enter, HardwareKeys.map(KeyEvent.KEYCODE_ENTER, 0, 0)?.action)
        assertEquals(KeyAction.Enter, HardwareKeys.map(KeyEvent.KEYCODE_NUMPAD_ENTER, 0, 0)?.action)
        assertEquals(KeyAction.Backspace, HardwareKeys.map(KeyEvent.KEYCODE_DEL, 0, 0)?.action)
    }

    @Test
    fun shortcutsAreLeftToTheApp() {
        assertNull(HardwareKeys.map(KeyEvent.KEYCODE_C, 'c'.code, KeyEvent.META_CTRL_ON))
        assertNull(HardwareKeys.map(KeyEvent.KEYCODE_TAB, '\t'.code, KeyEvent.META_ALT_ON))
        assertNull(HardwareKeys.map(KeyEvent.KEYCODE_SPACE, ' '.code, KeyEvent.META_META_ON))
    }

    @Test
    fun nonCharacterAndNonAsciiKeysAreIgnored() {
        assertNull(HardwareKeys.map(KeyEvent.KEYCODE_DPAD_LEFT, 0, 0))
        assertNull(HardwareKeys.map(KeyEvent.KEYCODE_TAB, '\t'.code, 0))
        assertNull(HardwareKeys.map(KeyEvent.KEYCODE_A, 'é'.code, 0))
    }
}
