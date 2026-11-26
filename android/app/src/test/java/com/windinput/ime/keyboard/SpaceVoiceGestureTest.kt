package com.windinput.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceVoiceGestureTest {
    private val events = mutableListOf<String>()
    private var accepted = true
    private val gesture = SpaceVoiceGesture(
        cancelThresholdPx = 64f,
        onStart = { events += "start"; accepted },
        onCancelChanged = { events += "cancel:$it" },
        onEnd = { events += "end:$it" },
    )

    @Test fun cursorSwipeBeforeLongPressPreventsVoiceStart() {
        gesture.prepare()
        assertTrue(gesture.beginCursor())
        gesture.start(200f)
        gesture.release()
        assertTrue(events.isEmpty())
    }

    @Test fun voiceBeforeCursorSwipeOwnsPressAndEndsOnlyOnce() {
        gesture.prepare()
        gesture.start(200f)
        assertFalse(gesture.beginCursor())
        gesture.start(200f)
        gesture.release()
        gesture.release()
        gesture.reset()
        assertEquals(listOf("start", "end:false"), events)
    }

    @Test fun rejectedStartConsumesPressWithoutSendingEnd() {
        accepted = false
        gesture.prepare()
        gesture.start(200f)
        assertTrue(gesture.consumed)
        assertFalse(gesture.beginCursor())
        gesture.move(0f)
        gesture.abort()
        assertTrue(gesture.consumed)
        gesture.release()
        assertFalse(gesture.consumed)
        assertEquals(listOf("start"), events)
    }

    @Test fun upwardCancelUsesHysteresisAndCanBeReversed() {
        gesture.prepare()
        gesture.start(200f)
        gesture.move(136f)
        gesture.move(138f)
        gesture.move(135f)
        gesture.move(145f)
        gesture.release()
        assertEquals(listOf("start", "cancel:true", "cancel:false", "end:false"), events)
    }

    @Test fun releaseInCancelRegionDiscardsInsteadOfFinishingRecognition() {
        gesture.prepare()
        gesture.start(200f)
        gesture.move(100f)
        gesture.release()
        assertEquals(listOf("start", "cancel:true", "end:true"), events)
    }

    @Test fun serviceAbortConsumesRemainingPressAndNotifiesOnlyOnce() {
        gesture.prepare()
        gesture.start(200f)
        gesture.abort()
        gesture.abort()
        assertTrue(gesture.consumed)
        assertFalse(gesture.beginCursor())
        gesture.move(0f)
        gesture.release()
        assertEquals(listOf("start", "end:true"), events)
    }

    @Test fun serviceAbortBeforeLongPressConsumesTheOldTimerAndRelease() {
        gesture.prepare()
        gesture.abort()
        assertTrue(gesture.consumed)
        gesture.start(200f)
        assertFalse(gesture.beginCursor())
        gesture.release()
        assertTrue(events.isEmpty())
        assertFalse(gesture.consumed)

        gesture.prepare()
        gesture.start(200f)
        gesture.release()
        assertEquals(listOf("start", "end:false"), events)
    }

    @Test fun touchCancellationDoesNotLeakIntoNextPress() {
        gesture.prepare()
        gesture.start(200f)
        gesture.reset()
        gesture.release()
        gesture.prepare()
        gesture.start(200f)
        gesture.release()
        assertEquals(listOf("start", "end:true", "start", "end:false"), events)
    }

    @Test fun synchronousViewDetachDuringStartCancelsAcceptedRecording() {
        lateinit var reentrant: SpaceVoiceGesture
        reentrant = SpaceVoiceGesture(
            cancelThresholdPx = 64f,
            onStart = { events += "start"; reentrant.reset(); true },
            onCancelChanged = { events += "cancel:$it" },
            onEnd = { events += "end:$it"; reentrant.abort() },
        )
        reentrant.prepare()
        reentrant.start(200f)
        reentrant.release()
        assertEquals(listOf("start", "end:true"), events)
    }
}
