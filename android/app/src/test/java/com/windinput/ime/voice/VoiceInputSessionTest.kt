package com.windinput.ime.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputSessionTest {
    private class Clock : VoiceScheduler {
        private data class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private var now = 0L
        private val tasks = mutableListOf<Task>()

        override fun after(delayMillis: Long, action: () -> Unit): VoiceTimer {
            val task = Task(now + delayMillis, action)
            tasks += task
            return VoiceTimer { task.cancelled = true }
        }

        fun advance(millis: Long) {
            val end = now + millis
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                next.cancelled = true
                now = next.at
                next.action()
            }
            now = end
        }
    }

    private class Backend(val local: Boolean, val events: VoiceBackend.Events) : VoiceBackend {
        var stopBeforeReady = false
        override val canStopBeforeReady: Boolean get() = stopBeforeReady
        var prepareDeadline = 5_000L
        override val supportTimeoutMillis: Long get() = prepareDeadline
        var resultDeadline = 10_000L
        override val resultTimeoutMillis: Long get() = resultDeadline
        var support: ((Boolean, String?) -> Unit)? = null
        var starts = 0
        var stops = 0
        var cancels = 0
        var destroys = 0
        var language: String? = null
        var duringStart: (() -> Unit)? = null
        var duringCancel: (() -> Unit)? = null
        var duringDestroy: (() -> Unit)? = null

        override fun checkLocalSupport(language: String, result: (Boolean, String?) -> Unit) {
            this.language = language
            support = result
        }

        override fun start(language: String) {
            this.language = language
            starts++
            duringStart?.invoke()
        }

        override fun stop() { stops++ }
        override fun cancel() { cancels++; duringCancel?.invoke() }
        override fun destroy() { destroys++; duringDestroy?.invoke() }
    }

    private class Rig : VoiceBackendFactory, VoiceInputListener {
        val clock = Clock()
        val session = VoiceInputSession(this, clock, this)
        var available = VoiceAvailability(onDevice = true, system = true)
        val backends = mutableListOf<Backend>()
        val states = mutableListOf<VoiceState>()
        val results = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var finished = 0
        var afterCreate: ((Backend) -> Unit)? = null
        var onErrorAction: (() -> Unit)? = null
        var onStateAction: ((VoiceState) -> Unit)? = null

        override fun availability() = available
        override fun create(onDevice: Boolean, events: VoiceBackend.Events) = Backend(onDevice, events).also {
            backends += it
            afterCreate?.invoke(it)
        }
        override fun onState(state: VoiceState) { states += state; onStateAction?.invoke(state) }
        override fun onResult(text: String) { results += text }
        override fun onError(message: String) { errors += message; onErrorAction?.invoke() }
        override fun onFinished() { finished++ }

        fun listening(): Backend {
            assertTrue(session.start("zh-CN", false))
            return backends.last().also {
                it.support!!(true, null)
                it.events.onReady()
            }
        }
    }

    @Test
    fun onlyAnInstalledLocalLanguageStartsCaptureAndOnlyFinalTextCommits() {
        val rig = Rig()
        assertTrue(rig.session.start("zh-CN", false))
        val backend = rig.backends.single()
        assertEquals(0, backend.starts)
        assertEquals(VoicePhase.CHECKING, rig.states.last().phase)
        backend.support!!(true, null)
        assertEquals("zh-CN", backend.language)
        backend.events.onReady()
        backend.events.onPartial("你好")
        assertTrue(rig.results.isEmpty())
        assertEquals("你好", rig.states.last().partialText)
        rig.session.stop()
        assertEquals(1, backend.stops)
        backend.events.onResult("  你好世界。  ")
        assertEquals(listOf("你好世界。"), rig.results)
        assertFalse(rig.session.isActive)
        assertEquals(1, backend.destroys)
        assertEquals(1, rig.finished)
    }

    @Test
    fun missingLocalModelNeverUsesSystemWithoutConsent() {
        val rig = Rig()
        rig.session.start("zh-CN", false)
        rig.backends.single().support!!(false, "未安装中文模型")
        assertEquals(1, rig.backends.size)
        assertEquals(listOf("未安装中文模型"), rig.errors)
        assertEquals(0, rig.backends.single().starts)
        assertEquals(1, rig.finished)
    }

    @Test
    fun explicitSystemConsentStillPrefersLocalAndInvalidatesItsFallbackCallbacks() {
        val rig = Rig()
        rig.session.start("en-US", true)
        val local = rig.backends.single()
        local.duringCancel = { local.events.onError("old cancel") }
        local.support!!(false, "no model")
        val system = rig.backends.last()
        assertEquals(2, rig.backends.size)
        assertFalse(system.local)
        assertEquals(1, system.starts)
        assertEquals("en-US", system.language)
        local.support!!(true, null)
        local.events.onResult("stale")
        assertTrue(rig.errors.isEmpty())
        assertEquals(0, local.starts)
        system.events.onReady()
        rig.session.stop()
        system.events.onResult("hello")
        assertEquals(listOf("hello"), rig.results)
        assertEquals(1, rig.finished)
    }

    @Test
    fun aSystemOnlyDeviceRequiresExplicitConsentAndNoServiceFailsClearly() {
        val noConsent = Rig().apply { available = VoiceAvailability(false, true) }
        assertFalse(noConsent.session.start("zh-CN", false))
        assertTrue(noConsent.backends.isEmpty())
        assertTrue(noConsent.errors.single().contains("离线"))
        val consent = Rig().apply { available = VoiceAvailability(false, true) }
        assertTrue(consent.session.start("zh-CN", true))
        assertEquals(1, consent.backends.single().starts)
        val missing = Rig().apply { available = VoiceAvailability(false, false) }
        assertFalse(missing.session.start("zh-CN", true))
        assertTrue(missing.errors.single().contains("没有可用"))
        assertEquals(1, missing.finished)
    }

    @Test
    fun releasingDuringSupportCheckCannotStartRecordingLater() {
        val rig = Rig()
        rig.session.start("zh-CN", true)
        val backend = rig.backends.single()
        rig.session.stop()
        backend.support!!(false, "late fallback")
        backend.support!!(true, null)
        rig.clock.advance(70_000)
        assertEquals(0, backend.starts)
        assertEquals(1, rig.backends.size)
        assertEquals(1, rig.errors.size)
        assertEquals(1, rig.finished)
    }

    @Test
    fun releasingBeforeReadyDefersExactlyOneStopUntilReady() {
        val rig = Rig()
        rig.session.start("zh-CN", false)
        val backend = rig.backends.single()
        backend.support!!(true, null)
        rig.session.stop()
        rig.session.stop()
        assertEquals(0, backend.stops)
        backend.events.onReady()
        backend.events.onReady()
        assertEquals(1, backend.stops)
        backend.events.onResult("早到的录音")
        assertEquals(listOf("早到的录音"), rig.results)
        assertEquals(1, rig.finished)
    }

    @Test
    fun appOwnedRecorderIsStoppedBeforeItsAsynchronousReadyCallback() {
        val rig = Rig()
        rig.afterCreate = { it.stopBeforeReady = true }
        rig.session.start("zh-CN", false)
        val backend = rig.backends.single()
        backend.support!!(true, null)
        rig.session.stop()
        assertEquals("The recorder receives stop before it could start later", 1, backend.stops)
        backend.events.onReady()
        backend.events.onReady()
        assertEquals(1, backend.stops)
        assertEquals(VoicePhase.PROCESSING, rig.states.last().phase)
        backend.events.onError("没有听到声音")
        assertEquals(1, rig.finished)
        assertTrue(rig.results.isEmpty())
    }

    @Test
    fun localModelDeadlinesAllowColdLoadAndDecodeButStillCancelLateResults() {
        val rig = Rig()
        rig.afterCreate = {
            it.prepareDeadline = 45_000L
            it.resultDeadline = 60_000L
            it.stopBeforeReady = true
        }
        rig.session.start("zh-CN", false)
        val backend = rig.backends.single()
        rig.clock.advance(6_000)
        assertTrue(rig.session.isActive)
        backend.support!!(true, null)
        backend.events.onReady()
        rig.session.stop()
        rig.clock.advance(11_000)
        assertTrue(rig.session.isActive)
        rig.clock.advance(49_000)
        assertFalse(rig.session.isActive)
        backend.events.onResult("迟到的离线文字")
        assertTrue(rig.results.isEmpty())
        assertEquals(1, backend.cancels)
        assertEquals(1, backend.destroys)
        assertEquals(1, rig.finished)
    }

    @Test
    fun earlyFinalResultWaitsForReleaseAndRemainsCancellable() {
        val cancelled = Rig()
        val first = cancelled.listening()
        first.events.onSpeechEnded()
        first.events.onResult("不要输入")
        assertEquals(VoicePhase.RESULT_READY, cancelled.states.last().phase)
        assertTrue(cancelled.results.isEmpty())
        assertEquals(1, first.destroys)
        cancelled.session.cancel()
        cancelled.session.stop()
        assertTrue(cancelled.results.isEmpty())
        assertEquals(1, cancelled.finished)

        val accepted = Rig()
        val second = accepted.listening()
        second.events.onResult("确认输入")
        second.events.onResult("duplicate")
        second.events.onError("late error")
        accepted.session.stop()
        accepted.session.stop()
        assertEquals(listOf("确认输入"), accepted.results)
        assertTrue(accepted.errors.isEmpty())
        assertEquals(1, accepted.finished)
    }

    @Test
    fun oldEditorCallbacksCannotCommitIntoANewSession() {
        val rig = Rig()
        val old = rig.listening()
        rig.session.cancel()
        val current = rig.listening()
        old.events.onPartial("old partial")
        old.events.onResult("old final")
        old.events.onError("old error")
        assertEquals(VoicePhase.LISTENING, rig.states.last().phase)
        assertTrue(rig.results.isEmpty())
        assertTrue(rig.errors.isEmpty())
        current.events.onResult("new final")
        rig.session.stop()
        assertEquals(listOf("new final"), rig.results)
        assertEquals(2, rig.finished)
    }

    @Test
    fun startupAndSupportDeadlinesCloseTheMicrophoneWithoutLateResults() {
        val checking = Rig()
        checking.session.start("zh-CN", false)
        checking.clock.advance(5_000)
        checking.backends.single().support!!(true, null)
        assertEquals(0, checking.backends.single().starts)
        assertEquals(1, checking.finished)

        val starting = Rig()
        starting.session.start("zh-CN", false)
        val backend = starting.backends.single()
        backend.support!!(true, null)
        starting.session.stop()
        starting.clock.advance(8_000)
        backend.events.onReady()
        backend.events.onResult("too late")
        assertTrue(starting.results.isEmpty())
        assertEquals(1, backend.cancels)
        assertEquals(1, backend.destroys)
        assertEquals(1, starting.finished)
    }

    @Test
    fun automaticEndpointHasABoundedWaitEvenIfReadyAndEndCallbacksRepeat() {
        val rig = Rig()
        val backend = rig.listening()
        backend.events.onSpeechEnded()
        rig.clock.advance(9_000)
        backend.events.onSpeechEnded()
        backend.events.onReady()
        rig.session.stop()
        rig.clock.advance(1_000)
        assertFalse(rig.session.isActive)
        assertEquals(1, rig.errors.size)
        assertEquals(1, rig.finished)
        backend.events.onResult("too late")
        assertTrue(rig.results.isEmpty())
    }

    @Test
    fun aSixtySecondHoldCancelsEvenAnAlreadyRecognizedResult() {
        val rig = Rig()
        val backend = rig.listening()
        backend.events.onResult("never released")
        rig.clock.advance(60_000)
        rig.session.stop()
        assertTrue(rig.results.isEmpty())
        assertTrue(rig.errors.single().contains("60 秒"))
        assertEquals(1, rig.finished)
    }

    @Test
    fun synchronousCallbacksAndTeardownErrorsStillFinishExactlyOnce() {
        val rig = Rig()
        rig.afterCreate = { backend ->
            backend.duringStart = {
                backend.events.onReady()
                backend.events.onResult("synchronous")
            }
            backend.duringDestroy = { backend.events.onError("destroy callback") }
        }
        rig.session.start("zh-CN", false)
        rig.backends.single().support!!(true, null)
        assertTrue(rig.results.isEmpty())
        rig.session.stop()
        assertEquals(listOf("synchronous"), rig.results)
        assertTrue(rig.errors.isEmpty())
        assertEquals(1, rig.finished)
    }

    @Test
    fun startExceptionsAndReentrantCancellationReleaseResourcesOnce() {
        val rig = Rig()
        rig.afterCreate = { backend -> backend.duringStart = { throw SecurityException("denied") } }
        rig.onErrorAction = { rig.session.cancel() }
        rig.session.start("zh-CN", false)
        rig.backends.single().support!!(true, null)
        assertTrue(rig.errors.single().contains("麦克风权限"))
        assertEquals(1, rig.finished)
        assertEquals(1, rig.backends.single().destroys)
        rig.session.destroy()
        assertFalse(rig.session.start("zh-CN", true))
        assertEquals(1, rig.finished)
    }

    @Test
    fun aReentrantReleaseInTheStartingUiNeverStartsCaptureAfterTheFingerLeaves() {
        val rig = Rig()
        rig.onStateAction = { if (it.phase == VoicePhase.STARTING) rig.session.stop() }
        rig.session.start("zh-CN", false)
        val backend = rig.backends.single()
        backend.support!!(true, null)
        assertEquals(0, backend.starts)
        assertEquals(1, backend.destroys)
        assertEquals(1, rig.finished)
    }

    @Test
    fun terminalUiCallbacksCannotStartANewSessionBeforeTheOldFinishCallback() {
        val rig = Rig()
        var reentrantStart = true
        rig.onErrorAction = { reentrantStart = rig.session.start("zh-CN", true) }
        val backend = rig.listening()
        backend.events.onError("failed")
        assertFalse(reentrantStart)
        assertEquals(1, rig.backends.size)
        assertEquals(1, rig.finished)
        assertTrue(rig.session.start("zh-CN", false))
        assertEquals(2, rig.backends.size)
    }

    @Test
    fun installedLanguageMatchingDoesNotSubstituteAnotherRegionOrDialect() {
        assertTrue(matchesVoiceLanguage("zh-CN", "zh-CN"))
        assertTrue(matchesVoiceLanguage("zh-CN", "zh_Hans_CN"))
        assertTrue(matchesVoiceLanguage("zh-CN", "cmn-Hans-CN"))
        assertTrue(matchesVoiceLanguage("en-US", "en"))
        assertFalse(matchesVoiceLanguage("zh-CN", "zh-TW"))
        assertFalse(matchesVoiceLanguage("zh-CN", "zh-Hant"))
        assertFalse(matchesVoiceLanguage("zh-CN", "yue-HK"))
        assertFalse(matchesVoiceLanguage("en-US", "en-GB"))
        assertFalse(matchesVoiceLanguage("zh-CN", ""))
    }
}
