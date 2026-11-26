package com.windinput.ime.core

import com.windinput.ime.keyboard.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredInputBufferTest {
    private fun key(action: KeyAction) = DeferredInput.Key(action, false, KeySource.TOUCH)
    private fun letter(char: Char) = key(KeyAction.Text(char))

    @Test
    fun earlyPinyinBackspaceAndSpaceKeepTheirExactOrder() {
        val buffer = DeferredInputBuffer()
        val input = "nihaox".map(::letter) + key(KeyAction.Backspace) + key(KeyAction.Space)
        input.forEach { assertTrue(buffer.offer(it)) }
        assertEquals(8, buffer.keyCount)

        assertEquals(input, generateSequence { buffer.take()?.input }.toList())
        assertEquals(0, buffer.keyCount)
        assertTrue(buffer.isEmpty)
        assertNull(buffer.take())
    }

    @Test
    fun aLaterSchemaChangeCannotOvertakeAcceptedKeys() {
        val buffer = DeferredInputBuffer()
        val before = "ni".map(::letter) + key(KeyAction.Enter)
        val switch = DeferredInput.Command(EngineCmd.English)
        val after = "hello".map(::letter) + key(KeyAction.Space)
        val input = before + switch + after
        input.forEach { assertTrue(buffer.offer(it)) }

        assertEquals(before.size + after.size, buffer.keyCount)
        assertEquals(input, generateSequence { buffer.take()?.input }.toList())
    }

    @Test
    fun typingDuringReplayStaysBehindTheRemainingKeys() {
        val buffer = DeferredInputBuffer()
        "ni".map(::letter).forEach { buffer.offer(it) }
        assertEquals(letter('n'), buffer.take()?.input)
        "hao".map(::letter).forEach { buffer.offer(it) }

        assertEquals("ihao".map(::letter), generateSequence { buffer.take()?.input }.toList())
    }

    @Test
    fun abandoningAnEditorClearsOldKeysAndInvalidatesAnInFlightFallback() {
        val buffer = DeferredInputBuffer()
        "old".map(::letter).forEach { buffer.offer(it) }
        val oldEntry = buffer.take()!!
        assertTrue(buffer.isCurrent(oldEntry))

        // focusGained, focusLost, reset and close all use this same cancellation operation.
        buffer.clear()
        assertFalse(buffer.isCurrent(oldEntry))
        assertEquals(0, buffer.keyCount)
        assertTrue(buffer.isEmpty)
        buffer.offer(letter('n'))
        val newEntry = buffer.take()!!
        assertTrue(buffer.isCurrent(newEntry))
        assertEquals(letter('n'), newEntry.input)
        assertNull(buffer.take())
    }

    @Test
    fun overflowIsExplicitAndDoesNotReplaceOrReorderAcceptedInput() {
        val buffer = DeferredInputBuffer(capacity = 2)
        assertTrue(buffer.offer(letter('n')))
        assertTrue(buffer.offer(key(KeyAction.Backspace)))
        assertFalse(buffer.offer(letter('x')))
        assertFalse(buffer.offer(DeferredInput.Command(EngineCmd.English)))
        assertEquals(2, buffer.keyCount)

        assertEquals(letter('n'), buffer.take()?.input)
        assertTrue(buffer.offer(letter('i')))
        assertEquals(key(KeyAction.Backspace), buffer.take()?.input)
        assertEquals(letter('i'), buffer.take()?.input)
        assertTrue(buffer.isEmpty)
    }

    @Test
    fun literalTextShiftAndPhysicalSourceSurviveBuffering() {
        val buffer = DeferredInputBuffer()
        val shifted = DeferredInput.Key(KeyAction.Text('a'), true, KeySource.PHYSICAL)
        val literal = key(KeyAction.Commit("🙂123"))
        val input = listOf(shifted, literal, key(KeyAction.Backspace))
        input.forEach { buffer.offer(it) }

        assertEquals(input, generateSequence { buffer.take()?.input }.toList())
    }
}
