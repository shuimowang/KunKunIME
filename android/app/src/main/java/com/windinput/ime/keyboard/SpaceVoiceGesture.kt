package com.windinput.ime.keyboard

/**
 * One space press can become either cursor movement or voice input, never both.
 * A rejected/interrupted voice start still consumes that press, so lifting the
 * finger cannot insert a stray space or turn into a cursor gesture.
 *
 * No Android dependency: timing and pointer selection belong to KeyboardView.
 */
internal class SpaceVoiceGesture(
    private val cancelThresholdPx: Float,
    private val onStart: () -> Boolean,
    private val onCancelChanged: (Boolean) -> Unit,
    private val onEnd: (Boolean) -> Unit,
) {
    private enum class State { IDLE, PENDING, CURSOR, STARTING, ACTIVE, DECLINED, ABORTED }

    private var state = State.IDLE
    private var anchorY = 0f
    private var cancelArmed = false

    val consumed: Boolean
        get() = state == State.STARTING || state == State.ACTIVE ||
            state == State.DECLINED || state == State.ABORTED

    fun prepare() {
        reset()
        state = State.PENDING
    }

    /** A cursor swipe wins only before voice has claimed this press. */
    fun beginCursor(): Boolean {
        if (state != State.PENDING) return false
        state = State.CURSOR
        return true
    }

    /** Called once the long-press timer expires, with the current finger Y. */
    fun start(y: Float) {
        if (state != State.PENDING) return
        state = State.STARTING
        anchorY = y
        cancelArmed = false
        val accepted = onStart()
        if (state == State.STARTING) {
            state = if (accepted) State.ACTIVE else State.DECLINED
        } else if (accepted) {
            // The start callback may synchronously hide/detach the input view.
            // Do not leave an accepted recording alive after that cancellation.
            onEnd(true)
        }
    }

    fun move(y: Float) {
        if (state != State.ACTIVE) return
        // 8dp hysteresis at the default 64dp threshold avoids flickering when
        // a held finger jitters around the cancel boundary.
        val threshold = if (cancelArmed) cancelThresholdPx * 0.875f else cancelThresholdPx
        val cancel = anchorY - y >= threshold
        if (cancel != cancelArmed) {
            cancelArmed = cancel
            onCancelChanged(cancel)
        }
    }

    /** Normal finger release: finish recognition unless cancellation is armed. */
    fun release() {
        val active = state == State.ACTIVE
        val cancel = cancelArmed
        state = State.IDLE
        cancelArmed = false
        if (active) onEnd(cancel)
    }

    /** Service interruption: keep consuming the held press until its final UP. */
    fun abort() {
        val active = state == State.ACTIVE
        // A focus change can arrive before the long-press timer. Consume that
        // pending press too: its old timer/UP must not record or type in the new editor.
        if (state == State.PENDING || state == State.STARTING || active) state = State.ABORTED
        cancelArmed = false
        if (active) onEnd(true)
    }

    /** Touch cancellation/detach or a new press starts a completely fresh cycle. */
    fun reset() {
        val active = state == State.ACTIVE
        state = State.IDLE
        cancelArmed = false
        if (active) onEnd(true)
    }
}
