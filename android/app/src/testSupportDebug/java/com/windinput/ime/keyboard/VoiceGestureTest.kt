package com.windinput.ime.keyboard

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real MotionEvents/timers; recognizer callbacks are recorded without opening a microphone. */
@RunWith(AndroidJUnit4::class)
class VoiceGestureTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private data class Finger(val id: Int, val x: Float, val y: Float)

    private fun pointer(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    private inner class Fixture(private val accepted: Boolean = true) {
        val actions = mutableListOf<KeyAction>()
        val voice = mutableListOf<String>()
        val legacy = mutableListOf<KeyAction>()
        val started = CountDownLatch(1)
        var interactions = 0
        var cursorSteps = 0
        lateinit var view: KeyboardView
        lateinit var space: Finger
        lateinit var q: Finger
        var spaceNode = -1
        var shiftNode = -1
        val density = inst.targetContext.resources.displayMetrics.density
        private val downTime = SystemClock.uptimeMillis()

        init {
            inst.runOnMainSync {
                view = KeyboardView(inst.targetContext).apply {
                    spaceVoiceEnabled = true
                    longPressDelayMs = 50
                    listener = object : KeyboardView.Listener {
                        override fun onKeyboardInteraction() {
                            interactions++
                            // Mirror Service's cancellation hook. This must run
                            // before the new press prepares its own Space state.
                            view.cancelSpaceVoiceGesture()
                        }
                        override fun onKeyAction(action: KeyAction, shifted: Boolean) { actions += action }
                        override fun onCursorMove(delta: Int) { cursorSteps += delta }
                        override fun onBackspaceSelect(count: Int) = Unit
                        override fun onBackspaceSwipeDelete() = Unit
                        override fun onBackspaceDeleteBeforeCursor() = Unit
                        override fun onBackspaceClearAll() = Unit
                        override fun onKeyLongPress(action: KeyAction) { legacy += action }
                        override fun onSpaceVoiceStart(): Boolean {
                            voice += "start"
                            started.countDown()
                            return accepted
                        }
                        override fun onSpaceVoiceCancelChanged(cancel: Boolean) { voice += "cancel:$cancel" }
                        override fun onSpaceVoiceEnd(cancel: Boolean) { voice += "end:$cancel" }
                    }
                    measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
                    )
                    layout(0, 0, measuredWidth, measuredHeight)
                }
                val provider = view.accessibilityNodeProvider!!
                for (id in 0 until 200) {
                    val node = provider.createAccessibilityNodeInfo(id) ?: continue
                    val rect = Rect()
                    @Suppress("DEPRECATION")
                    node.getBoundsInParent(rect)
                    when (node.contentDescription?.toString()) {
                        "空格" -> {
                            spaceNode = id
                            space = Finger(0, rect.exactCenterX(), rect.exactCenterY())
                        }
                        "Q" -> q = Finger(1, rect.exactCenterX(), rect.exactCenterY())
                        "上档" -> shiftNode = id
                    }
                }
                check(spaceNode >= 0 && ::q.isInitialized) { "Keyboard test keys are missing" }
            }
        }

        fun touch(action: Int, vararg fingers: Finger) {
            inst.runOnMainSync { dispatch(action, fingers) }
        }

        private fun dispatch(action: Int, fingers: Array<out Finger>) {
            val properties = Array(fingers.size) { i ->
                MotionEvent.PointerProperties().apply {
                    id = fingers[i].id
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coordinates = Array(fingers.size) { i ->
                MotionEvent.PointerCoords().apply {
                    x = fingers[i].x
                    y = fingers[i].y
                    pressure = 1f
                    size = 1f
                }
            }
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action, fingers.size,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0,
            )
            try { view.onTouchEvent(event) } finally { event.recycle() }
        }

        fun tap() {
            // One main-loop turn: host scheduling cannot accidentally stretch a
            // short tap beyond the deliberately short test long-press timer.
            inst.runOnMainSync {
                dispatch(MotionEvent.ACTION_DOWN, arrayOf(space))
                dispatch(MotionEvent.ACTION_UP, arrayOf(space))
            }
        }

        fun swipeBeforeLongPress() {
            inst.runOnMainSync {
                dispatch(MotionEvent.ACTION_DOWN, arrayOf(space))
                dispatch(MotionEvent.ACTION_MOVE, arrayOf(space.copy(x = space.x + 24f * density)))
                dispatch(MotionEvent.ACTION_MOVE, arrayOf(space.copy(x = space.x + 80f * density)))
            }
        }

        fun cancelBeforeLongPress() {
            inst.runOnMainSync {
                dispatch(MotionEvent.ACTION_DOWN, arrayOf(space))
                view.cancelSpaceVoiceGesture()
            }
        }

        fun hold() {
            touch(MotionEvent.ACTION_DOWN, space)
            assertTrue("Space long press did not start", started.await(3, TimeUnit.SECONDS))
        }

        fun afterLongPressDeadline() {
            val elapsed = CountDownLatch(1)
            Handler(Looper.getMainLooper()).postDelayed({ elapsed.countDown() }, 100)
            assertTrue(elapsed.await(3, TimeUnit.SECONDS))
        }

        fun assertNoTypedKey() = inst.runOnMainSync { assertTrue(actions.isEmpty()) }
    }

    @Test fun shortPressStillTypesOneSpace() {
        val f = Fixture()
        f.tap()
        f.afterLongPressDeadline()
        inst.runOnMainSync {
            assertEquals(listOf(KeyAction.Space), f.actions)
            assertTrue(f.voice.isEmpty())
        }
    }

    @Test fun holdAndReleaseFinishWithoutTypingSpace() {
        val f = Fixture()
        f.hold()
        f.touch(MotionEvent.ACTION_UP, f.space)
        inst.runOnMainSync { assertEquals(listOf("start", "end:false"), f.voice) }
        f.assertNoTypedKey()
    }

    @Test fun finalUpCoordinatesCanCancelWithoutAnIntermediateMove() {
        val f = Fixture()
        f.hold()
        f.touch(MotionEvent.ACTION_UP, f.space.copy(y = f.space.y - 80f * f.density))
        inst.runOnMainSync { assertEquals(listOf("start", "cancel:true", "end:true"), f.voice) }
        f.assertNoTypedKey()
    }

    @Test fun secondFingerCannotInterruptVoiceOrTypeAfterTheHeldFingerReleases() {
        val f = Fixture()
        f.hold()
        f.touch(pointer(MotionEvent.ACTION_POINTER_DOWN, 1), f.space, f.q)
        f.touch(pointer(MotionEvent.ACTION_POINTER_UP, 0), f.space, f.q)
        f.touch(MotionEvent.ACTION_UP, f.q)
        inst.runOnMainSync {
            assertEquals(listOf("start", "end:false"), f.voice)
            assertEquals(1, f.interactions)
        }
        f.assertNoTypedKey()
    }

    @Test fun cursorSwipeBeforeLongPressKeepsCursorMovementAndNeverStartsVoice() {
        val f = Fixture()
        f.swipeBeforeLongPress()
        f.afterLongPressDeadline()
        f.touch(MotionEvent.ACTION_UP, f.space.copy(x = f.space.x + 80f * f.density))
        inst.runOnMainSync {
            assertTrue(f.cursorSteps > 0)
            assertTrue(f.voice.isEmpty())
        }
        f.assertNoTypedKey()
    }

    @Test fun rejectedStartDoesNotBecomeSpaceOrCursorMovement() {
        val f = Fixture(accepted = false)
        f.hold()
        f.touch(MotionEvent.ACTION_MOVE, f.space.copy(x = f.space.x + 90f * f.density))
        f.touch(MotionEvent.ACTION_UP, f.space)
        inst.runOnMainSync {
            assertEquals(listOf("start"), f.voice)
            assertEquals(0, f.cursorSteps)
        }
        f.assertNoTypedKey()
    }

    @Test fun serviceCancellationConsumesTheRemainingHeldPress() {
        val f = Fixture()
        f.hold()
        inst.runOnMainSync {
            f.view.cancelSpaceVoiceGesture()
            f.view.cancelSpaceVoiceGesture()
        }
        f.touch(MotionEvent.ACTION_MOVE, f.space.copy(x = f.space.x + 90f * f.density))
        f.touch(MotionEvent.ACTION_UP, f.space)
        inst.runOnMainSync {
            assertEquals(listOf("start", "end:true"), f.voice)
            assertEquals(0, f.cursorSteps)
        }
        f.assertNoTypedKey()
    }

    @Test fun serviceCancellationBeforeLongPressCannotRecordOrTypeInTheNextEditor() {
        val f = Fixture()
        f.cancelBeforeLongPress()
        f.afterLongPressDeadline()
        f.touch(MotionEvent.ACTION_UP, f.space)
        inst.runOnMainSync { assertTrue(f.voice.isEmpty()) }
        f.assertNoTypedKey()
    }

    @Test fun systemCancelDiscardsVoiceAndTheNextTapStillWorks() {
        val f = Fixture()
        f.hold()
        f.touch(MotionEvent.ACTION_CANCEL, f.space)
        f.tap()
        inst.runOnMainSync {
            assertEquals(listOf("start", "end:true"), f.voice)
            assertEquals(listOf(KeyAction.Space), f.actions)
        }
    }

    @Test fun accessibilityLongClickUsesToggleInsteadOfWaitingForFingerUp() {
        val f = Fixture()
        inst.runOnMainSync {
            val provider = f.view.accessibilityNodeProvider!!
            assertTrue(provider.createAccessibilityNodeInfo(f.spaceNode)!!.isLongClickable)
            assertTrue(provider.performAction(f.spaceNode, AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
            assertTrue(provider.performAction(f.spaceNode, AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
            assertEquals(listOf(KeyAction.Space, KeyAction.Space), f.legacy)
            assertEquals(0, f.interactions)
            assertTrue(f.voice.isEmpty())
            assertTrue(f.actions.isEmpty())
        }
    }

    @Test fun aNewLetterNotifiesTheServiceOnDownBeforeTypingOnUp() {
        val f = Fixture()
        val letter = f.q.copy(id = 0)
        f.touch(MotionEvent.ACTION_DOWN, letter)
        inst.runOnMainSync {
            assertEquals(1, f.interactions)
            assertTrue(f.actions.isEmpty())
        }
        f.touch(MotionEvent.ACTION_UP, letter)
        inst.runOnMainSync { assertEquals(listOf(KeyAction.Text('q')), f.actions) }
    }

    @Test fun accessibilityShiftNotifiesTheServiceAlthoughItDoesNotTypeAKey() {
        val f = Fixture()
        inst.runOnMainSync {
            assertTrue(f.shiftNode >= 0)
            assertTrue(f.view.accessibilityNodeProvider!!.performAction(f.shiftNode, AccessibilityNodeInfo.ACTION_CLICK, null))
            assertEquals(1, f.interactions)
            assertTrue(f.actions.isEmpty())
            assertTrue(f.voice.isEmpty())
        }
    }
}
