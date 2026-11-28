package com.windinput.ime.service

import android.text.InputType
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Debug instrumentation exercises the host helpers against Android's native EditText. */
@RunWith(AndroidJUnit4::class)
class EditorConnectionTest {
    private fun withEditor(text: String, body: (EditText, InputConnection) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var failure: Throwable? = null
        instrumentation.runOnMainSync {
            try {
                val field = EditText(instrumentation.targetContext)
                field.inputType = InputType.TYPE_CLASS_TEXT
                field.setText(text)
                body(field, requireNotNull(field.onCreateInputConnection(EditorInfo())))
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw it }
    }

    @Test fun inlinePinyinInTheMiddleKeepsHostSuffixAndInternalCaret() = withEditor("甲后面还有正文") { field, ic ->
        field.setSelection(1)
        assertTrue(ic.setInlineComposition("nihao", 5))
        assertEquals("甲nihao后面还有正文", field.text.toString())
        assertEquals(6, field.selectionEnd)
        assertTrue(ic.setInlineComposition("nihao", 2))
        assertEquals(3, field.selectionEnd)
        assertTrue(ic.setInlineComposition("𠮷ab", 2))
        assertEquals(3, field.selectionEnd)
        assertTrue(ic.setInlineComposition("𠮷ab", 0))
        assertEquals(1, field.selectionEnd)
        assertEquals(1, BaseInputConnection.getComposingSpanStart(field.text))
        assertEquals(5, BaseInputConnection.getComposingSpanEnd(field.text))
        assertTrue(ic.commitText("你好", 1))
        assertEquals("甲你好后面还有正文", field.text.toString())
        assertEquals(3, field.selectionEnd)
    }

    @Test fun dragDeletesAWholeEmojiCluster() = withEditor("前👩‍💻后") { field, ic ->
        val text = field.text
        val end = text.length - 1
        val start = requireNotNull(backspaceSelectionStart(text, end, 1))
        assertEquals(1, start)
        assertTrue(ic.setSelection(start, end))
        assertTrue(ic.commitText("", 1))
        assertEquals("前后", field.text.toString())
    }

    @Test fun privateGestureDeletesSelectionAndPrefixWithoutAskingForOriginalText() =
        withEditor("甲乙𠮷后") { field, ic ->
            field.setSelection(1, 4)
            val privateConnection = object : InputConnectionWrapper(ic, false) {
                override fun getSelectedText(flags: Int): CharSequence = error("Read private selection")
                override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence = error("Read private prefix")
                override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = error("Read private suffix")
            }
            privateConnection.deleteWithoutUndo(100_000, 0)
            assertEquals("后", field.text.toString())
            privateConnection.deleteWithoutUndo(100_000, 100_000)
            assertEquals("", field.text.toString())
        }
}
