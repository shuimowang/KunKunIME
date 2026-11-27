package com.windinput.ime.smoke

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.media.MediaRecorder
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
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.windinput.ime.settings.TestPadActivity
import com.windinput.ime.settings.VoiceSetupActivity
import com.windinput.ime.voice.model.OfflineModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Real sherpa CPU/JNI inference over public upstream WAV fixtures, followed by
 * the real IME's local preparation, AudioRecord start and swipe cancellation.
 * Fixtures belong only to the test APK. No fake text or production test hook is
 * involved; emulator capture checks lifecycle, not a physical microphone's ASR.
 */
@RunWith(AndroidJUnit4::class)
class OfflineVoiceSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val testAssets = instrumentation.context.assets
    private val automation = instrumentation.uiAutomation
    private val appId = "io.github.shuimowang.kunkunime"
    private val testId = "$appId.test"
    private val imeId = "$appId/com.windinput.ime.service.WindInputImeService"
    private var held: Press? = null

    private data class Press(val down: Long, val x: Float, val y: Float)

    @Test(timeout = 360_000)
    fun nativeChineseAndEnglishDecodeThenLocalImeRecordingCancelsWithoutSystemService() {
        assertEquals("Use only the dedicated emulator", "1", shell("getprop ro.kernel.qemu").trim())
        assertEquals("Keep the controlled system provider installed to detect unwanted fallback",
            "$testId/com.windinput.ime.smoke.ControlledRecognitionService",
            shell("settings get secure voice_recognition_service").trim())
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        assertTrue("Model and WAV fixtures must not be packaged in the production APK",
            context.assets.list("offline-voice").orEmpty().isEmpty())
        assertFalse("The test must start with a clear provider log", "START:" in probeLog())
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val preferences = context.getSharedPreferences("voice_input", 0)
        val previousBooleans = listOf("configured", "system_fallback").associateWith { key ->
            if (preferences.contains(key)) preferences.getBoolean(key, false) else null
        }
        val previousEngine = preferences.getString("engine", null)
        val onboarding = context.getSharedPreferences("ime_onboarding", 0)
        val previousOnboarding = if (onboarding.contains("first_pick_shown")) {
            onboarding.getBoolean("first_pick_shown", false)
        } else null
        val modelDirectory = File(context.noBackupFilesDir, "offline-voice/${OfflineModelCatalog.ID}")
        assertFalse("A clean test must not overwrite an installed model", modelDirectory.exists())
        try {
            assertTrue("The test model directory must be created", modelDirectory.mkdirs())
            // Install before the first settings/IME access creates OfflineModelStore.
            // The normal store then independently validates these same files.
            copyVerifiedFixture(modelDirectory, "model.int8.onnx", 239_233_841L,
                "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51")
            copyVerifiedFixture(modelDirectory, "tokens.txt", 315_894L,
                "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc")
            checkNativeRecognition(modelDirectory)

            assertTrue(preferences.edit().putBoolean("configured", true)
                .putString("engine", "local_model").putBoolean("system_fallback", false).commit())
            assertTrue(onboarding.edit().putBoolean("first_pick_shown", true).commit())
            instrumentation.startActivitySync(
                Intent(context, VoiceSetupActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                ),
            )
            awaitNode(ime = false, timeoutMs = 60_000) {
                // Compose/Miuix may merge a card's Text children into one node.
                // Require the complete READY + selected phrase within that text.
                it.text?.toString()?.contains("语音包已安装 · 当前使用此方式") == true
            }
            capture("offline-model-ready.png")
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
            val audioManager = requireNotNull(context.getSystemService(AudioManager::class.java))
            assertFalse("No speech recorder may already be running", recordingActive(audioManager))
            val before = bounds(awaitKey("空格"))
            holdSpace()
            awaitNode(ime = true, timeoutMs = 50_000) {
                it.text?.toString() == "正在聆听 · 松手输入"
            }
            eventually("Android reports a real VOICE_RECOGNITION recording") {
                recordingActive(audioManager)
            }
            assertEquals("The voice strip must not move the held space key", before, bounds(awaitKey("空格")))
            assertEquals("Recording must not insert text before release", "", editorText())
            capture("offline-voice-listening.png")
            cancelSpaceBySwipe()
            eventually("cancellation stops AudioRecord and closes the voice strip") {
                !recordingActive(audioManager) &&
                    findNode(ime = true) { it.contentDescription?.toString() == "语音取消" } == null
            }
            val until = SystemClock.uptimeMillis() + 1_000
            while (SystemClock.uptimeMillis() < until) {
                assertEquals("Cancelled local speech must not reach the editor", "", editorText())
                assertFalse("Cancelled capture must not restart", recordingActive(audioManager))
                SystemClock.sleep(100)
            }
            capture("offline-voice-cancelled.png")
            val providerLog = probeLog()
            assertFalse("Local recognition must never start the system provider", "START:" in providerLog)
            assertFalse("Local recognition must never receive system-provider readiness", "READY:" in providerLog)
            assertEquals(imeId, shell("settings get secure default_input_method").trim())
            Log.i(TAG, "PASS: real CPU/JNI Chinese and English samples; real IME local AudioRecord start/cancel; no system recognition call. Physical microphone accuracy not measured.")
        } catch (failure: Throwable) {
            runCatching { captureAccessibilityTree("offline-voice-failure-a11y.txt") }
            runCatching { capture("offline-voice-failure.png") }
            throw failure
        } finally {
            runCatching { held?.let { press -> send(press, MotionEvent.ACTION_CANCEL, press.x, press.y) } }
            held = null
            preferences.edit().apply {
                for ((key, value) in previousBooleans) {
                    if (value == null) remove(key) else putBoolean(key, value)
                }
                if (previousEngine == null) remove("engine") else putString("engine", previousEngine)
            }.commit()
            onboarding.edit().apply {
                if (previousOnboarding == null) remove("first_pick_shown")
                else putBoolean("first_pick_shown", previousOnboarding)
            }.commit()
            modelDirectory.deleteRecursively()
        }
    }

    private fun copyVerifiedFixture(directory: File, name: String, bytes: Long, sha256: String) {
        val destination = File(directory, name)
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(testAssets.open("offline-voice/$name"), digest).use { source ->
            destination.outputStream().use { target -> source.copyTo(target, 1024 * 1024) }
        }
        assertEquals("Fixture byte count: $name", bytes, destination.length())
        assertEquals("Fixture SHA-256: $name", sha256,
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

    private fun checkNativeRecognition(directory: File) {
        val engine = OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = File(directory, "model.int8.onnx").absolutePath,
                    language = "auto",
                    useInverseTextNormalization = true,
                ),
                tokens = File(directory, "tokens.txt").absolutePath,
                numThreads = 2,
                provider = "cpu",
                debug = false,
            )),
        )
        try {
            val results = linkedMapOf<String, String>()
            for (language in listOf("zh", "en")) {
                val waveform = readFixtureWaveform("offline-voice/test_wavs/$language.wav")
                val stream = engine.createStream()
                try {
                    stream.acceptWaveform(waveform, 16_000)
                    waveform.fill(0f)
                    engine.decode(stream)
                    results[language] = engine.getResult(stream).text
                } finally {
                    waveform.fill(0f)
                    stream.release()
                }
            }
            // Public fixture transcripts are safe to retain, unlike user recordings.
            val report = results.entries.joinToString("\n", postfix = "\n") { "${it.key}: ${it.value}" }
            File(requireNotNull(context.getExternalFilesDir("smoke")), "offline-native-results.txt").writeText(report)
            Log.i(TAG, "Native fixture results: $report")
            val chinese = requireNotNull(results["zh"])
            assertTrue("Chinese fixture must be recognized, actual: $chinese",
                "早上" in chinese && "下午" in chinese)
            val english = requireNotNull(results["en"]).lowercase(Locale.ROOT)
            assertTrue("English fixture must be recognized, actual: $english",
                listOf("tribal", "chieftain", "boy").all { it in english })
        } finally {
            engine.release()
        }
    }

    /** Accept only the checked-in upstream fixture format, not arbitrary audio. */
    private fun readFixtureWaveform(path: String): FloatArray {
        val bytes = testAssets.open(path).use { it.readBytes() }
        fun word(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
        fun int(offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
        fun short(offset: Int) = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        check(bytes.size >= 12 && word(0) == "RIFF" && word(8) == "WAVE") { "Invalid WAV fixture: $path" }
        var offset = 12
        var formatValid = false
        var dataOffset = -1
        var dataSize = 0
        while (offset <= bytes.size - 8) {
            val size = int(offset + 4)
            val start = offset + 8
            check(size >= 0 && size <= bytes.size - start) { "Invalid WAV chunk: $path" }
            when (word(offset)) {
                "fmt " -> {
                    check(size >= 16 && short(start) == 1 && short(start + 2) == 1 &&
                        int(start + 4) == 16_000 && short(start + 14) == 16) {
                        "Expected 16 kHz mono PCM16 fixture: $path"
                    }
                    formatValid = true
                }
                "data" -> { dataOffset = start; dataSize = size }
            }
            offset = start + size + (size and 1)
        }
        check(formatValid && dataOffset >= 0 && dataSize > 0 && dataSize % 2 == 0) { "Missing PCM fixture data: $path" }
        val pcm = ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dataSize / 2) { pcm.short / 32768f }
    }

    private fun recordingActive(manager: AudioManager) = manager.activeRecordingConfigurations.any {
        it.clientAudioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private fun holdSpace() {
        val box = bounds(awaitKey("空格"))
        val press = Press(SystemClock.uptimeMillis(), box.exactCenterX(), box.exactCenterY())
        held = press
        send(press, MotionEvent.ACTION_DOWN, press.x, press.y)
    }

    private fun cancelSpaceBySwipe() {
        val press = requireNotNull(held)
        val y = press.y - 96f * context.resources.displayMetrics.density
        send(press, MotionEvent.ACTION_MOVE, press.x, y)
        awaitNode(ime = true) { it.text?.toString() == "松开取消" }
        send(press, MotionEvent.ACTION_UP, press.x, y)
        held = null
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

    private fun awaitNode(ime: Boolean, timeoutMs: Long = 15_000, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        eventually(if (ime) "the expected IME control" else "the expected activity control", timeoutMs) {
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

    private fun captureAccessibilityTree(name: String) {
        val tree = buildString {
            var remaining = 2_000
            fun appendNode(node: AccessibilityNodeInfo?, depth: Int) {
                if (node == null || depth > 64 || remaining-- <= 0) return
                // Retain package, bounds, merged text and descriptions for diagnosis.
                append("  ".repeat(depth)).append("visible=${node.isVisibleToUser} ").appendLine(node.toString())
                for (index in 0 until node.childCount) appendNode(node.getChild(index), depth + 1)
            }
            for (window in automation.windows) {
                appendLine("window id=${window.id} type=${window.type} active=${window.isActive}")
                appendNode(window.root, 0)
            }
        }
        File(requireNotNull(context.getExternalFilesDir("smoke")), name).writeText(tree)
    }

    private fun eventually(description: String, timeoutMs: Long = 12_000, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (check()) return
            SystemClock.sleep(150)
        }
        throw AssertionError("Timed out waiting for $description; inspect offline-voice logs and screenshots")
    }

    private companion object { const val TAG = "KunKunOfflineSmoke" }
}
