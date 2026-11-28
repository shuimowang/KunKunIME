package com.windinput.ime.service

import android.view.inputmethod.InputConnection

/** Remove the selection and adjacent text without fetching the contents of a private field. */
internal fun InputConnection.deleteWithoutUndo(before: Int, after: Int) {
    beginBatchEdit()
    try {
        if (commitText("", 1)) deleteSurroundingTextInCodePoints(before, after)
    } finally {
        endBatchEdit()
    }
}

/** Set the composing span and place the editor caret at the core's UTF-16 offset in that span. */
internal fun InputConnection.setInlineComposition(text: String, caret: Int): Boolean {
    val offset = caret.coerceIn(0, text.length)
    if (offset == 0) return setComposingText(text, 0)
    // Android interprets positive newCursorPosition relative to the end of the replacement.
    if (!setComposingText(text, 1)) return false
    if (offset == text.length) return true

    // Only an interior caret needs an absolute setSelection. The editor may transform the
    // supplied text or withhold its offsets; in that case leave the caret at the editor's end.
    val context = getSurroundingText(text.length, 0, 0) ?: return true
    val end = context.selectionEnd
    val start = end - text.length
    if (context.offset < 0 || end != context.selectionStart || start < 0 || end > context.text.length ||
        !context.text.regionMatches(start, text, 0, text.length)
    ) return true
    val safe = if (text[offset].isLowSurrogate() && text[offset - 1].isHighSurrogate()) offset - 1 else offset
    val absolute = context.offset + start + safe
    setSelection(absolute, absolute)
    return true
}
