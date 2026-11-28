package com.windinput.ime.smoke

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionService
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.windinput.ime.settings.TestPadActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Real IME touch -> SpeechRecognizer IPC -> test-only provider -> real editor.
 * Fixed provider text proves integration/cancellation, never recognition accuracy.
 */
@RunWith(AndroidJUnit4::class)
class VoiceImeSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val appId = "io.github.shuimowang.kunkunime"
    private val testId = "$appId.test"
    private val imeId = "$appId/com.windinput.ime.service.WindInputImeService"
    private var held: Press? = null

    private data class Press(val down: Long, val x: Float, val y: Float)

    @Test(timeout = 180_000)
    fun heldSpaceCommitsFinalOnlyAndCancelledIpcCannotReachPasswordEditor() {
        assertEquals("Use only the dedicated emulator", "1", shell("getprop ro.kernel.qemu").trim())
        assertEquals("Only the test APK may provide fixed recognition", "$testId/com.windinput.ime.smoke.ControlledRecognitionService",
            shell("settings get secure voice_recognition_service").trim())
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        assertTrue("The production APK must not contain a controlled recognition service",
            context.packageManager.queryIntentServices(
                Intent(RecognitionService.SERVICE_INTERFACE).setPackage(appId),
                PackageManager.ResolveInfoFlags.of(0),
            ).isEmpty())
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val preferences = context.getSharedPreferences("voice_input", 0)
        val keys = listOf("configured", "system_fallback")
        val previous = keys.associateWith { key ->
            if (preferences.contains(key)) preferences.getBoolean(key, false) else null
        }
        val previousEngine = preferences.getString("engine", null)
        val onboarding = context.getSharedPreferences("ime_onboarding", 0)
        val previousOnboarding = if (onboarding.contains("first_pick_shown")) {
            onboarding.getBoolean("first_pick_shown", false)
        } else null
        try {
            preferences.edit().putBoolean("configured", true).putBoolean("system_fallback", true)
                .putString("engine", "system_allowed").commit()
            onboarding.edit().putBoolean("first_pick_shown", true).commit()
            shell("settings put secure show_ime_with_hard_keyboard 1")
            shell("ime enable $imeId")
            shell("ime set $imeId")
            instrumentation.startActivitySync(
                Intent(context, TestPadActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                ),
            )
            tap(awaitEditor())
            eventually("the real Rust core is ready", 90_000) {
                val logs = shell("logcat -d -v brief -s DeferredSession:I RustSession:I")
                "Rust core ready:" in logs && "核心就绪" in logs
            }
            clearEditor()

            val before = bounds(awaitKey("空格"))
            holdSpace()
            awaitPartial()
            val completedRequest = newestReadyRequest()
            assertEquals("The voice strip must not move the held space key", before, bounds(awaitKey("空格")))
            assertEquals("Partial speech must not enter the host", "", editorText())
            capture("voice-ipc-listening.png")
            releaseSpace(cancel = false)
            eventually("final speech text reaches the real editor") { editorText() == ControlledRecognitionService.FINAL }
            assertTrue("Stopping must cross real Android recognizer IPC", "STOP: request=$completedRequest " in probeLog())
            assertTrue("A final response must come from the controlled test provider", "FINAL: request=$completedRequest " in probeLog())
            capture("voice-ipc-final.png")

            clearEditor()
            holdSpace()
            awaitPartial()
            val cancelledRequest = newestReadyRequest()
            assertTrue("The cancellation probe must use a new recognizer request", cancelledRequest > completedRequest)
            assertEquals("Second partial speech must also stay outside the host", "", editorText())
            releaseSpace(cancel = true)
            eventually("the current provider request terminates and the voice strip closes") {
                val log = probeLog()
                val terminal = "CANCEL: request=$cancelledRequest " in log ||
                    "DISCONNECTED: request=$cancelledRequest " in log
                terminal && findNode(ime = true) { it.contentDescription?.toString() == "语音取消" } == null &&
                    editorText().isEmpty()
            }
            val password = awaitNode(ime = false) {
                it.text?.toString() == "密码" && it.className?.toString() != "android.widget.EditText"
            }
            tap(password)
            tap(awaitEditor())
            listOf("a", "b", "c").forEach { tap(awaitKey(it)) }
            eventually("the new password editor receives only typed keys") { passwordFixtureEquals("abc") }
            // Deliver the cancelled callback only after the real editor changed.
            shell("am broadcast -n $testId/com.windinput.ime.smoke.ControlledRecognitionReleaseReceiver --ei request_id $cancelledRequest")
            eventually("the provider attempts this request's late cancelled callback") {
                "LATE_ATTEMPT: request=$cancelledRequest " in probeLog()
            }
            val until = SystemClock.uptimeMillis() + 800
            while (SystemClock.uptimeMillis() < until) {
                assertTrue("Cancelled speech must not reach the new password editor", passwordFixtureEquals("abc"))
                SystemClock.sleep(100)
            }
            capture("voice-ipc-cancelled.png")
            assertEquals(imeId, shell("settings get secure default_input_method").trim())
            Log.i(TAG, "PASS: real IME long-press/stop, controlled speech IPC final, cancelled late result discarded; no audio accuracy claim")
        } catch (failure: Throwable) {
            runCatching { capture("voice-ipc-failure.png") }
            throw failure
        } finally {
            runCatching { held?.let { press -> send(press, MotionEvent.ACTION_CANCEL, press.x, press.y) } }
            held = null
            preferences.edit().apply {
                for ((key, value) in previous) {
                    if (value == null) remove(key) else putBoolean(key, value)
                }
                if (previousEngine == null) remove("engine") else putString("engine", previousEngine)
            }.commit()
            onboarding.edit().apply {
                if (previousOnboarding == null) remove("first_pick_shown")
                else putBoolean("first_pick_shown", previousOnboarding)
            }.commit()
        }
    }

    private fun holdSpace() {
        val box = bounds(awaitKey("空格"))
        val press = Press(SystemClock.uptimeMillis(), box.exactCenterX(), box.exactCenterY())
        held = press
        send(press, MotionEvent.ACTION_DOWN, press.x, press.y)
    }

    private fun releaseSpace(cancel: Boolean) {
        val press = requireNotNull(held)
        val y = if (cancel) press.y - 96f * context.resources.displayMetrics.density else press.y
        if (cancel) {
            send(press, MotionEvent.ACTION_MOVE, press.x, y)
            awaitNode(ime = true) { it.text?.toString() == "松开取消" }
        }
        send(press, MotionEvent.ACTION_UP, press.x, y)
        held = null
    }

    private fun awaitPartial() = awaitNode(ime = true) {
        it.text?.toString() == ControlledRecognitionService.PARTIAL
    }

    private fun clearEditor() {
        assertTrue(awaitEditor().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }))
        eventually("the editor is empty") { editorText().isEmpty() }
        instrumentation.waitForIdleSync()
    }

    private fun awaitKey(label: String) = awaitNode(ime = true) {
        it.contentDescription?.toString().equals(label, ignoreCase = true) ||
            it.text?.toString().equals(label, ignoreCase = true)
    }

    private fun awaitEditor() = awaitNode(ime = false) {
        it.contentDescription?.toString() == TestPadActivity.TEST_PAD_DESC &&
            it.className?.toString() == "android.widget.EditText"
    }

    private fun editorText() = awaitEditor().let { if (it.isShowingHintText) "" else it.text?.toString().orEmpty() }

    private fun awaitNode(ime: Boolean, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        eventually(if (ime) "the expected IME control" else "the expected editor control", 15_000) {
            found = findNode(ime, match)
            found != null
        }
        return requireNotNull(found)
    }

    private fun findNode(ime: Boolean, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        automation.windows.filter {
            it.type == if (ime) AccessibilityWindowInfo.TYPE_INPUT_METHOD else AccessibilityWindowInfo.TYPE_APPLICATION
        }.firstNotNullOfOrNull { find(it.root, match) }

    private fun find(node: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.packageName?.toString() == appId && node.isVisibleToUser && match(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), match)?.let { return it }
        return null
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen).also {
        assertTrue("Control must have visible screen bounds", !it.isEmpty)
    }

    private fun tap(node: AccessibilityNodeInfo) {
        val box = bounds(node)
        val press = Press(SystemClock.uptimeMillis(), box.exactCenterX(), box.exactCenterY())
        send(press, MotionEvent.ACTION_DOWN, press.x, press.y)
        SystemClock.sleep(40)
        send(press, MotionEvent.ACTION_UP, press.x, press.y)
        instrumentation.waitForIdleSync()
    }

    private fun send(press: Press, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(press.down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue("Real touch injection must succeed", automation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private fun probeLog() = shell("logcat -d -v brief -s KunKunVoiceProbe:I")

    private fun newestReadyRequest(): Int =
        Regex("""READY: request=(\d+)\b""").findAll(probeLog()).lastOrNull()?.groupValues?.get(1)?.toInt()
            ?: throw AssertionError("The controlled provider must identify the active request")

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun capture(name: String) {
        val bitmap = requireNotNull(automation.takeScreenshot())
        try {
            File(requireNotNull(context.getExternalFilesDir("smoke")), name).outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private fun eventually(description: String, timeoutMs: Long = 12_000, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (check()) return
            SystemClock.sleep(150)
        }
        throw AssertionError("Timed out waiting for $description; inspect voice-ipc logs and screenshots")
    }

    private companion object { const val TAG = "KunKunVoiceImeSmoke" }
}
