package com.windinput.ime.smoke

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.ApplicationInfo
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
import com.windinput.ime.settings.TestPadActivity
import org.json.JSONArray
import org.json.JSONObject
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
    private val pendingTrace = StringBuilder()

    @Test(timeout = 240_000)
    fun installedImeTypesPinyinEnglishPasswordAndNumbers() {
        val startupHold = StartupProbe.hold()
        try {
            File(requireNotNull(context.getExternalFilesDir("smoke")), "press-trace.txt")
                .writeText("Smoke started at uptime ${SystemClock.uptimeMillis()}\n")
            exerciseInstalledIme(startupHold)
            flushTrace()
        } catch (failure: Throwable) {
            // AndroidJUnitRunner closes the activity after the test returns.
            // Capture the failing IME now, before shell cleanup sees Launcher.
            captureFailure(failure)
            throw failure
        } finally {
            startupHold?.close()
        }
    }

    private fun exerciseInstalledIme(startupHold: StartupHandoff?) {
        assertEquals("Smoke test must target KunKunIME", appId, context.packageName)
        assertEquals("The release smoke must install the non-debuggable app", startupHold != null,
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
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
        val expectedPinyinText = if (startupHold != null) {
            // Debug only: hold delivery of the real core. Release has no such API.
            assertTrue("Real Rust startup reached its handoff", startupHold.awaitBlocked(90_000))
            awaitKey("n")
            typeKeys("ni")
            assertEquals("Early Pinyin must stay buffered, not commit Latin letters", "", editorText())

            selectScene("密码")
            assertEquals("Changing input type must not commit the abandoned Pinyin", "", editorText())
            typeKeys("abc")
            awaitText("abc")
            selectScene("普通")
            // Keep the text so manually clearing it cannot hide stale queued input.
            assertEquals("Password text survives the scene change", "abc", editorText())
            typeKeys("nihaox", trailingKey = "删除")
            assertEquals("Queued Pinyin must not edit the existing text", "abc", editorText())
            awaitImeNode("seven startup keys buffered after abandoning the old input") {
                it.text?.toString()?.contains("已暂存 7 次输入") == true
            }
            capture("cold-start-queued.png")
            startupHold.close()
            "abc你好"
        } else {
            // Actual release bytecode: wait for normal startup, then drive the
            // real keyboard. This does not claim deterministic queue coverage.
            awaitCoreReady()
            awaitKey("n")
            typeKeys("nihaox", trailingKey = "删除")
            "你好"
        }
        awaitCoreReady()
        eventually("all startup keys replayed before candidate selection") {
            findImeNode { node ->
                val label = "${node.text?.toString().orEmpty()} ${node.contentDescription?.toString().orEmpty()}"
                "已暂存" in label || "词库准备中" in label
            } == null
        }

        // A Latin-only fallback cannot provide and commit this Hanzi candidate.
        // Debug replays queued keys; release processes normal real key presses.
        awaitImeNode("real Pinyin preedit ni'hao after backspace") {
            it.className?.toString() == "android.widget.TextView" && it.text?.toString() == "ni'hao"
        }
        val hello = awaitImeNode("Pinyin candidate 你好") { node ->
            val description = node.contentDescription?.toString().orEmpty()
            node.text?.toString() == "你好" || description == "你好" ||
                description.startsWith("你好，编码 ")
        }
        recordInputState(if (startupHold != null) "AFTER STARTUP HANDOFF" else "AFTER RELEASE INPUT")
        assertKeysAvoidSystemNavigation("pinyin", keyboardAnchor = "中英切换")
        capture("pinyin-candidates.png")
        tap(hello)
        awaitText(expectedPinyinText)
        Log.i(TAG, if (startupHold != null) {
            "PASS: debug queued Pinyin and backspace, old input discarded across password scene"
        } else {
            "PASS: non-debuggable release Pinyin and backspace through the real IME"
        })

        deviceShell("input keyevent KEYCODE_BACK")
        eventually("keyboard hides") { imeWindows().isEmpty() }
        tapEditor()
        awaitKey("n")
        assertEquals("Committed text survives closing the keyboard", expectedPinyinText, editorText())
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

        exerciseQuickPhrasesAndUpdateScreen()

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
        assertKeysAvoidSystemNavigation("numbers", keyboardAnchor = "0")
        capture("number-pad.png")
        assertEquals("KunKunIME remains the active IME", imeId,
            deviceShell("settings get secure default_input_method").trim())
        Log.i(TAG, "PASS: numeric editor receives digits from the number pad")
    }

    private fun awaitCoreReady() {
        eventually("real Rust session becomes ready", timeoutMs = 90_000) {
            val logs = deviceShell("logcat -d -v brief -s DeferredSession:I RustSession:I")
            "Rust core ready:" in logs && "核心就绪" in logs
        }
    }

    /** The actual panel, editor Activity and persisted store, through public UI actions. */
    private fun exerciseQuickPhrasesAndUpdateScreen() {
        clearEditor()
        fun openPhrases() {
            tapEditor()
            awaitKey("a")
            tapImeKey("剪贴板")
            tapImeKey("切换到常用语")
        }
        fun anyText(text: String): AccessibilityNodeInfo {
            var result: AccessibilityNodeInfo? = null
            eventually("visible action $text") {
                val match: (AccessibilityNodeInfo) -> Boolean = { it.text?.toString() == text }
                result = findAppNode(match) ?: findImeNode(match)
                result != null
            }
            return requireNotNull(result)
        }
        fun saveEditor(value: String) {
            val field = awaitNode("phrase editor", imeOnly = false) {
                it.isEditable && it.className?.toString() == "android.widget.EditText" &&
                    it.contentDescription?.toString() != TestPadActivity.TEST_PAD_DESC
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
            eventually("phrase save enabled") { anyText("保存").isEnabled }
            tap(anyText("保存"))
            awaitEditor()
        }
        openPhrases()
        tapImeKey("新增常用语")
        saveEditor("常用语测试123")
        openPhrases()
        val added = awaitImeNode("saved phrase") { it.text?.toString() == "常用语测试123" }
        assertTrue(added.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
        tap(anyText("编辑"))
        saveEditor("已修改常用语456")
        openPhrases()
        val edited = awaitImeNode("edited phrase") { it.text?.toString() == "已修改常用语456" }
        tap(edited)
        awaitText("已修改常用语456")
        assertEquals("Selecting a phrase only commits text, never sends or adds a newline", "已修改常用语456", editorText())
        openPhrases()
        val remove = awaitImeNode("phrase to remove") { it.text?.toString() == "已修改常用语456" }
        assertTrue(remove.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
        tap(anyText("删除"))
        eventually("deleted phrase removed from panel") {
            findImeNode { it.text?.toString() == "已修改常用语456" } == null
        }
        capture("quick-phrases.png")
        tap(awaitImeNode("sample phrase") { it.text?.toString() == "好的谢谢。" })
        awaitText("已修改常用语456好的谢谢。")
        Log.i(TAG, "PASS: quick phrases add, edit, persist, commit without sending, and delete")

        instrumentation.startActivitySync(Intent(context, com.windinput.ime.update.UpdateActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        anyText("检查更新")
        capture("app-update.png")
        tap(anyText("返回"))
        awaitEditor()
        tapEditor()
        awaitKey("a")
        Log.i(TAG, "PASS: in-app update screen exposes manual update check")
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

    private fun typeKeys(text: String, trailingKey: String? = null) {
        text.forEach { tapImeKey(it.toString()) }
        trailingKey?.let(::tapImeKey)
        // Inspect the resulting state only after the whole sequence. Reading
        // every accessibility node between keys changes the input pace.
        recordInputState("AFTER TEXT <$text>${trailingKey?.let { " then <$it>" }.orEmpty()}")
    }

    private fun recordInputState(label: String) {
        pendingTrace.append("${SystemClock.uptimeMillis()} $label: ")
            .append(inputStateSummary()).append('\n')
        flushTrace()
    }

    private fun tapImeKey(label: String) {
        val key = awaitKey(label)
        tap(key, traceLabel = label)
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
    private fun tap(node: AccessibilityNodeInfo, traceLabel: String? = null) {
        val bounds = Rect().also(node::getBoundsInScreen)
        assertTrue("Visible control has nonempty screen bounds: $bounds", !bounds.isEmpty)
        val down = SystemClock.uptimeMillis()
        // Reuse the coordinates already needed for touch injection. Do not
        // retrieve parents, dump trees, or perform I/O between consecutive keys.
        if (traceLabel != null) {
            pendingTrace.append("$down PRESS <$traceLabel>: bounds=${bounds.toShortString()}\n")
        }
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

    private data class HitRegion(val label: String, val bounds: Rect)

    /** Check live touch regions only at screenshot checkpoints, never between typed keys. */
    private fun assertKeysAvoidSystemNavigation(layout: String, keyboardAnchor: String) {
        instrumentation.waitForIdleSync()
        // Virtual keyboard buttons share this host; candidate buttons belong
        // to a different host and must not be mistaken for keyboard keys.
        val keyboard = requireNotNull(awaitKey(keyboardAnchor).parent) { "Keyboard host unavailable" }
        val keys = collectHitRegions(keyboard) {
            it.packageName?.toString() == appId && it.className?.toString() == "android.widget.Button"
        }
        assertTrue("$layout keyboard exposes its key touch regions", keys.isNotEmpty())

        // These are the actual framework navigation controls observed on the
        // dedicated English-language emulator. Their window can span the IME,
        // so compare the controls themselves, not an enclosing window's top.
        val navigationLabels = setOf("Back", "Switch input method")
        val navigation = imeWindows().flatMap { window ->
            collectHitRegions(window.root) {
                it.className?.toString() == "android.widget.ImageView" &&
                    it.contentDescription?.toString().orEmpty() in navigationLabels
            }
        }
        assertEquals("Both system navigation touch regions must be visible for $layout",
            navigationLabels, navigation.map { it.label }.toSet())

        val lastRowBottom = keys.maxOf { it.bounds.bottom }
        val lastRow = keys.filter { it.bounds.bottom == lastRowBottom }
        fun regionsText(regions: List<HitRegion>) = regions.joinToString(" | ") {
            "<${it.label}> ${it.bounds.toShortString()}"
        }
        pendingTrace.append("${SystemClock.uptimeMillis()} GEOMETRY <$layout>: ")
            .append("checkedKeys=${keys.size}; lastRow=[${regionsText(lastRow)}]; ")
            .append("systemNavigation=[${regionsText(navigation)}]\n")
        flushTrace()
        for (key in keys) {
            for (systemControl in navigation) {
                assertTrue("$layout key <${key.label}> ${key.bounds.toShortString()} overlaps " +
                    "system <${systemControl.label}> ${systemControl.bounds.toShortString()}",
                    !Rect.intersects(key.bounds, systemControl.bounds))
            }
        }
    }

    private fun collectHitRegions(
        root: AccessibilityNodeInfo?,
        matches: (AccessibilityNodeInfo) -> Boolean,
    ): List<HitRegion> {
        val regions = mutableListOf<HitRegion>()
        fun collect(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40) return
            if (node.isVisibleToUser && matches(node)) {
                val bounds = Rect().also(node::getBoundsInScreen)
                assertTrue("Visible touch region has nonempty bounds", !bounds.isEmpty)
                val label = node.contentDescription?.toString() ?: node.text?.toString().orEmpty()
                regions += HitRegion(label, bounds)
            }
            for (index in 0 until node.childCount) collect(node.getChild(index), depth + 1)
        }
        collect(root, 0)
        return regions
    }

    private fun captureFailure(failure: Throwable) {
        runCatching { flushTrace() }
            .onFailure { Log.e(TAG, "Could not save buffered touch trace", it) }
        runCatching { capture("failure.png") }
            .onFailure { Log.e(TAG, "Could not capture failure screenshot", it) }
        runCatching {
            val windows = JSONArray()
            for (window in automation.windows) {
                val bounds = Rect().also(window::getBoundsInScreen)
                windows.put(JSONObject().apply {
                    put("id", window.id)
                    put("type", window.type)
                    put("title", window.title?.toString().orEmpty())
                    put("bounds", bounds.toShortString())
                    put("root", window.root?.let { nodeJson(it, 0) } ?: JSONObject.NULL)
                })
            }
            val snapshot = JSONObject().apply {
                put("failure", failure.toString())
                put("uptimeMs", SystemClock.uptimeMillis())
                put("expectedIme", imeId)
                put("inputState", inputStateSummary())
                put("windows", windows)
            }
            File(requireNotNull(context.getExternalFilesDir("smoke")), "failure-ui.json")
                .writeText(snapshot.toString(2))
        }.onFailure { Log.e(TAG, "Could not capture failure accessibility tree", it) }
        runCatching {
            File(requireNotNull(context.getExternalFilesDir("smoke")), "failure-input-method.txt")
                .writeText(deviceShell("dumpsys input_method"))
        }.onFailure { Log.e(TAG, "Could not capture failure input connection", it) }
    }

    private fun nodeJson(node: AccessibilityNodeInfo, depth: Int): JSONObject = JSONObject().apply {
        val bounds = Rect().also(node::getBoundsInScreen)
        put("class", node.className?.toString().orEmpty())
        put("package", node.packageName?.toString().orEmpty())
        put("resourceId", node.viewIdResourceName.orEmpty())
        put("text", node.text?.toString().orEmpty())
        put("description", node.contentDescription?.toString().orEmpty())
        put("bounds", bounds.toShortString())
        put("visible", node.isVisibleToUser)
        put("focused", node.isFocused)
        put("editable", node.isEditable)
        put("clickable", node.isClickable)
        put("childCount", node.childCount)
        if (depth < 40) {
            val children = JSONArray()
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { children.put(nodeJson(it, depth + 1)) }
            }
            put("children", children)
        }
    }

    private fun describeNode(node: AccessibilityNodeInfo): String {
        val bounds = Rect().also(node::getBoundsInScreen)
        return "class=${node.className} text=<${node.text}> desc=<${node.contentDescription}> " +
            "bounds=${bounds.toShortString()} visible=${node.isVisibleToUser}"
    }

    private fun flushTrace() {
        if (pendingTrace.isEmpty()) return
        // The complete node list can exceed logcat's per-message length limit.
        File(requireNotNull(context.getExternalFilesDir("smoke")), "press-trace.txt")
            .appendText(pendingTrace.toString())
        pendingTrace.clear()
    }

    private fun inputStateSummary(): String {
        val editor = findAppNode {
            it.contentDescription?.toString() == TestPadActivity.TEST_PAD_DESC &&
                it.className?.toString() == "android.widget.EditText"
        }
        val nodes = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40 || nodes.size >= 100) return
            if (!node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty()) {
                nodes += describeNode(node)
            }
            for (index in 0 until node.childCount) collect(node.getChild(index), depth + 1)
        }
        imeWindows().forEach { collect(it.root, 0) }
        return "editor=${editor?.let(::describeNode)}; ime=[${nodes.joinToString(" | ")}]"
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
