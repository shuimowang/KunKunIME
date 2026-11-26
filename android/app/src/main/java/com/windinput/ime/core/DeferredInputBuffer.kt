package com.windinput.ime.core

import com.windinput.ime.keyboard.KeyAction
import java.util.ArrayDeque

/** Work accepted while the engine is starting or changing schemas, in input order. */
internal sealed interface DeferredInput {
    data class Key(val action: KeyAction, val shifted: Boolean, val source: KeySource) : DeferredInput
    data class Command(val command: EngineCmd) : DeferredInput
}

/**
 * A bounded, main-thread-only queue tied to the current editor/composition.
 *
 * Backspace is an ordinary queued key: reducing the queue to a string would be wrong after a
 * space, punctuation or schema change. A generation also invalidates a key already taken for
 * replay if its editor is abandoned before the host fallback runs.
 */
internal class DeferredInputBuffer(private val capacity: Int = 256) {
    init {
        require(capacity > 0)
    }

    data class Entry internal constructor(val generation: Long, val input: DeferredInput)

    private val pending = ArrayDeque<DeferredInput>()
    private var generation = 0L

    var keyCount: Int = 0
        private set

    val isEmpty: Boolean get() = pending.isEmpty()

    /** False means the caller must explicitly report refusal; accepted keys remain intact. */
    fun offer(input: DeferredInput): Boolean {
        if (pending.size >= capacity) return false
        pending.addLast(input)
        if (input is DeferredInput.Key) keyCount++
        return true
    }

    fun take(): Entry? {
        val input = pending.pollFirst() ?: return null
        if (input is DeferredInput.Key) keyCount--
        return Entry(generation, input)
    }

    fun isCurrent(entry: Entry): Boolean = entry.generation == generation

    /** Called on a new editor, focus loss, explicit reset, or service destruction. */
    fun clear() {
        generation++
        pending.clear()
        keyCount = 0
    }
}
