package com.windinput.ime.service

import com.windinput.ime.panels.TextEditingWindow.EditAction
import java.text.BreakIterator
import java.util.Locale

/** Engine MoveCursor counts Unicode scalar values, unlike Android's UTF-16 selection offsets. */
internal fun codePointTarget(text: CharSequence, from: Int, delta: Int): Int? {
    if (from !in 0..text.length) return null
    var pos = from
    var steps = delta
    while (steps > 0 && pos < text.length) {
        pos += Character.charCount(Character.codePointAt(text, pos))
        steps--
    }
    while (steps < 0 && pos > 0) {
        pos -= Character.charCount(Character.codePointBefore(text, pos))
        steps++
    }
    return pos
}

/** All indices into [text] are local UTF-16 indices. Only the returned selection is absolute. */
internal fun editorCaretTarget(text: CharSequence, base: Int, focus: Int, action: EditAction): Int? {
    if (base < 0 || focus < base || focus - base > text.length) return null
    val at = focus - base
    val content = text.toString()
    val breaks = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(content) }
    fun lineStart(pos: Int) = content.lastIndexOf('\n', pos - 1) + 1
    fun lineEnd(pos: Int): Int {
        val newline = content.indexOf('\n', pos)
        return when {
            newline < 0 -> content.length
            newline > 0 && content[newline - 1] == '\r' -> newline - 1
            else -> newline
        }
    }

    // Preserve the column in visible characters when crossing lines of emoji or supplementary Hanzi.
    fun sameColumn(targetStart: Int, targetEnd: Int): Int {
        var source = lineStart(at)
        var target = targetStart
        while (source < at && target < targetEnd) {
            source = breaks.following(source)
            target = breaks.following(target)
        }
        return target
    }
    val next = when (action) {
        EditAction.LEFT -> if (at == 0) 0 else breaks.preceding(at)
        EditAction.RIGHT -> if (at == content.length) at else breaks.following(at)
        EditAction.HOME -> lineStart(at)
        EditAction.END -> lineEnd(at)
        EditAction.UP -> {
            val start = lineStart(at)
            if (start == 0) 0 else {
                val previousStart = lineStart(start - 1)
                sameColumn(previousStart, lineEnd(previousStart))
            }
        }
        EditAction.DOWN -> {
            val newline = content.indexOf('\n', at)
            if (newline < 0) content.length else sameColumn(newline + 1, lineEnd(newline + 1))
        }
        else -> return null
    }
    return base + next
}

/** The backspace drag counts visible characters, including multi-code-point emoji. */
internal fun backspaceSelectionStart(text: CharSequence, end: Int, count: Int): Int? {
    if (end !in 0..text.length || count < 0) return null
    if (count == 0) return end
    val breaks = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text.toString()) }
    if (!breaks.isBoundary(end)) return null
    var start = end
    var remaining = count
    while (remaining > 0 && start > 0) {
        start = breaks.preceding(start)
        remaining--
    }
    return start
}
