package com.windinput.ime.smoke

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.windinput.ime.core.RustCoreLoader
import com.windinput.ime.settings.TestPadActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises the installed InputMethodService, its real native engine, the
 * actual on-screen keys and an Android EditText/InputConnection. No fake
 * session, constructed keyboard view or direct native key injection is used.
 * Run with scripts/smoke-test.sh on a dedicated emulator.
 */
@RunWith(AndroidJUnit4::class)
class RealImeSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val appId = "io.github.shuimowang.kunkunime"
    private val imeId = "$appId/com.windinput.ime.service.WindInputImeService"

    @Test(timeout = 180_000)
    fun installedImeTypesPinyinEnglishPasswordAndNumbers() {
        assertEquals("Smoke test must target KunKunIME", appId, context.packageName)
        assertTrue("The packaged Rust engine must load", RustCoreLoader.available)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        // Dismiss only the one-time scheme picker. Engine defaults and all
        // keystrokes still follow the production path from the packaged assets.
        context.getSharedPreferences("ime_onboarding", 0).edit()
            .putBoolean("first_pick_shown", true).commit()
        deviceShell("settings put secure show_ime_with_hard_keyboard 1")
        deviceShell("ime enable $imeId")
        deviceShell("ime set $imeId")
        assertEquals(imeId, deviceShell("settings get secure default_input_method").trim())

        instrumentation.startActivitySync(
            Intent(context, TestPadActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
            ),
        )
        instrumentation.waitForIdleSync()
        tapEditor()
        eventually("real Rust session becomes ready", timeoutMs = 90_000) {
            val logs = deviceShell("logcat -d -v brief -s DeferredSession:I RustSession:I")
            "Rust core ready:" in logs && "核心就绪" in logs
        }
        awaitKey("n")

        // A Latin-only fallback can never provide and commit this Hanzi
        // candidate, even if it draws a keyboard and passes an APK build.
        typeKeys("nihao")
        val hello = awaitImeNode("Pinyin candidate 你好") { node ->
            val description = node.contentDescription?.toString().orEmpty()
            node.text?.toString() == "你好" || description == "你好" ||
                description.startsWith("你好，编码 ")
        }
        capture("pinyin-candidates.png")
        tap(hello)
        awaitText("你好")
        Log.i(TAG, "PASS: real Pinyin candidate selected and committed")

        deviceShell("input keyevent KEYCODE_BACK")
        eventually("keyboard hides") { imeWindows().isEmpty() }
        tapEditor()
        awaitKey("n")
        assertEquals("Committed text survives closing the keyboard", "你好", editorText())
        Log.i(TAG, "PASS: keyboard closes and reopens")

        clearEditor()
        tapImeKey("中英切换")
        // The English dictionary is small but switching it runs asynchronously.
        // This smoke test checks resulting text, without depending on a ranking.
        SystemClock.sleep(1_000)
        typeKeys("hello")
        tapImeKey("空格")
        awaitText("hello ")
        Log.i(TAG, "PASS: English text and space")

        selectScene("密码")
        clearEditor()
        typeKeys("abc")
        awaitText("abc")
        assertTrue("Password input must not produce Hanzi candidates", findImeNode { node ->
            node.contentDescription?.toString()?.startsWith("你好") == true
        } == null)
        Log.i(TAG, "PASS: visible password receives direct ASCII")

        selectScene("数字")
        clearEditor()
        typeKeys("123")
        awaitText("123")
        capture("number-pad.png")
        assertEquals("KunKunIME remains the active IME", imeId,
            deviceShell("settings get secure default_input_method").trim())
        Log.i(TAG, "PASS: numeric editor receives digits from the number pad")
    }

    private fun selectScene(label: String) {
        val tab = awaitNode("test-pad scene $label", imeOnly = false) {
            it.text?.toString() == label && it.className?.toString() != "android.widget.EditText"
        }
        tap(tab)
        tapEditor()
        awaitKey(if (label == "数字") "1" else "a")
    }

    private fun clearEditor() {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        val editor = awaitEditor()
        assertTrue("Test pad supports clearing its text", editor.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT, args,
        ))
        awaitText("")
        instrumentation.waitForIdleSync()
    }

    private fun typeKeys(text: String) = text.forEach { tapImeKey(it.toString()) }

    private fun tapImeKey(label: String) {
        tap(awaitKey(label))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(80)
    }

    private fun awaitKey(label: String) = awaitImeNode("IME key $label") {
        it.contentDescription?.toString().equals(label, ignoreCase = true) ||
            it.text?.toString().equals(label, ignoreCase = true)
    }

    private fun tapEditor() = tap(awaitEditor())

    private fun awaitEditor() = awaitNode("test-pad editor", imeOnly = false) {
        it.contentDescription?.toString() == TestPadActivity.TEST_PAD_DESC &&
            it.className?.toString() == "android.widget.EditText"
    }

    private fun editorText(): String {
        val editor = awaitEditor()
        return if (editor.isShowingHintText) "" else editor.text?.toString().orEmpty()
    }

    private fun awaitText(expected: String) {
        eventually("editor text equals <$expected>") { editorText() == expected }
    }

    private fun awaitImeNode(description: String, match: (AccessibilityNodeInfo) -> Boolean) =
        awaitNode(description, imeOnly = true, match = match)

    private fun awaitNode(
        description: String,
        imeOnly: Boolean,
        match: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        eventually(description) {
            found = if (imeOnly) findImeNode(match) else findAppNode(match)
            found != null
        }
        return requireNotNull(found)
    }

    private fun imeWindows() = automation.windows.filter {
        it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
            it.root?.packageName?.toString() == appId
    }

    private fun findImeNode(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        imeWindows().firstNotNullOfOrNull { find(it.root, match) }

    private fun findAppNode(match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .firstNotNullOfOrNull { find(it.root, match) }

    private fun find(
        node: AccessibilityNodeInfo?,
        match: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.packageName?.toString() == appId && node.isVisibleToUser && match(node)) return node
        for (index in 0 until node.childCount) {
            find(node.getChild(index), match)?.let { return it }
        }
        return null
    }

    /** Touch real screen coordinates obtained from fresh accessibility nodes. */
    private fun tap(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        assertTrue("Visible control has nonempty screen bounds: $bounds", !bounds.isEmpty)
        val down = SystemClock.uptimeMillis()
        for ((action, time) in listOf(MotionEvent.ACTION_DOWN to down, MotionEvent.ACTION_UP to down + 40)) {
            val event = MotionEvent.obtain(down, time, action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue("Touch event was injected", automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(40)
        }
    }

    private fun deviceShell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun capture(name: String) {
        val screenshot = requireNotNull(automation.takeScreenshot()) { "Device screenshot unavailable" }
        val output = File(requireNotNull(context.getExternalFilesDir("smoke")), name)
        try {
            output.outputStream().use {
                assertTrue("Screenshot was saved", screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            screenshot.recycle()
        }
    }

    private fun eventually(description: String, timeoutMs: Long = 12_000, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (check()) return
            SystemClock.sleep(150)
        }
        throw AssertionError("Timed out waiting for $description; see smoke logcat and screenshot")
    }

    private companion object {
        const val TAG = "KunKunSmoke"
    }
}
