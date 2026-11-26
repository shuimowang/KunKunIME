package com.windinput.ime.voice

import java.util.Locale

enum class VoicePhase { CHECKING, STARTING, LISTENING, PROCESSING, RESULT_READY }

/** Partial text is for the keyboard's own status view, never the host editor. */
data class VoiceState(
    val phase: VoicePhase,
    val onDevice: Boolean,
    val partialText: String = "",
    val level: Float = 0f,
)

data class VoiceAvailability(val onDevice: Boolean, val system: Boolean)

interface VoiceInputListener {
    fun onState(state: VoiceState)
    fun onResult(text: String)
    fun onError(message: String)
    fun onFinished()
}

internal fun interface VoiceTimer {
    fun cancel()
}

internal fun interface VoiceScheduler {
    fun after(delayMillis: Long, action: () -> Unit): VoiceTimer
}

/** Small adapter boundary: tests can drive real callback order without a microphone. */
internal interface VoiceBackend {
    interface Events {
        fun onReady()
        fun onSpeechEnded()
        fun onPartial(text: String)
        fun onLevel(level: Float)
        fun onResult(text: String)
        fun onError(message: String)
    }

    fun checkLocalSupport(language: String, result: (Boolean, String?) -> Unit)
    fun start(language: String)
    fun stop()
    fun cancel()
    fun destroy()
}

internal interface VoiceBackendFactory {
    fun availability(): VoiceAvailability
    fun create(onDevice: Boolean, events: VoiceBackend.Events): VoiceBackend
}

/**
 * One press owns one recognition request. Every callback is checked against both
 * the request and its backend attempt, so cancellation, editor changes and an
 * on-device -> system fallback cannot deliver stale text.
 *
 * All entry points and backend callbacks are serialized on the main thread by
 * [VoiceInputController]. No audio or recognition text is persisted here.
 */
internal class VoiceInputSession(
    private val factory: VoiceBackendFactory,
    private val scheduler: VoiceScheduler,
    private val listener: VoiceInputListener,
) {
    private class Request(val language: String, val allowSystem: Boolean) {
        var holding = true
        var attempt = 0
        var backend: VoiceBackend? = null
        var started = false
        var ready = false
        var stopSent = false
        var result: String? = null
        var state = VoiceState(VoicePhase.CHECKING, onDevice = true)
        var stageTimer: VoiceTimer? = null
        var holdTimer: VoiceTimer? = null
    }

    private var active: Request? = null
    private var destroyed = false
    private var finishing = false

    val isActive: Boolean get() = active != null

    fun start(language: String, allowSystemFallback: Boolean): Boolean {
        if (destroyed || finishing || active != null) return false
        val request = Request(language, allowSystemFallback)
        active = request
        request.holdTimer = scheduler.after(60_000) {
            if (active === request) fail(request, "语音输入已到 60 秒，请松手后重新长按空格")
        }
        val available = try {
            factory.availability()
        } catch (_: RuntimeException) {
            fail(request, "无法连接语音服务，请检查系统语音设置")
            return false
        }
        when {
            available.onDevice -> prepare(request, onDevice = true)
            request.allowSystem && available.system -> prepare(request, onDevice = false)
            available.system -> fail(request, "设备未提供离线语音服务，可在语音设置中选择系统语音服务")
            else -> fail(request, "手机没有可用的语音识别服务，请先在系统中安装或启用语音服务")
        }
        return active === request
    }

    /** Releasing before support-check completion must never start a microphone later. */
    fun stop() {
        val request = active ?: return
        if (!request.holding) return
        request.holding = false
        request.holdTimer?.cancel()
        request.holdTimer = null
        request.result?.let {
            complete(request, it)
            return
        }
        if (!request.started) {
            fail(request, "语音还未准备好，请重新长按空格")
            return
        }
        if (request.ready) {
            stopBackend(request)
        } else {
            // onReadyForSpeech may arrive after the finger was released. Keep
            // the startup deadline, then stop immediately when readiness arrives.
            update(request, request.state.copy(phase = VoicePhase.PROCESSING, level = 0f))
        }
    }

    fun cancel() {
        active?.let { finish(it, cancelBackend = true) }
    }

    fun destroy() {
        destroyed = true
        cancel()
    }

    private fun prepare(request: Request, onDevice: Boolean) {
        if (active !== request || !request.holding) return
        val attempt = ++request.attempt
        releaseBackend(request, cancel = true)
        request.started = false
        request.ready = false
        request.stopSent = false
        update(request, VoiceState(if (onDevice) VoicePhase.CHECKING else VoicePhase.STARTING, onDevice))
        if (!current(request, attempt)) return
        val backend = try {
            factory.create(onDevice, events(request, attempt))
        } catch (_: RuntimeException) {
            if (onDevice) fallback(request, "设备端语音服务无法启动，请检查系统语音设置")
            else fail(request, "系统语音服务无法启动，请检查系统语音设置")
            return
        }
        if (!current(request, attempt)) {
            runCatching { backend.destroy() }
            return
        }
        request.backend = backend
        if (!onDevice) {
            startBackend(request, attempt)
            return
        }
        stageTimeout(request, 5_000) {
            fallback(request, "设备端语音服务响应超时，请检查语音设置后重试")
        }
        try {
            backend.checkLocalSupport(request.language) { supported, message ->
                if (!current(request, attempt) || request.started) return@checkLocalSupport
                if (supported) startBackend(request, attempt)
                else fallback(request, message ?: "当前语言的离线语音模型未安装，可在语音设置中选择系统语音服务")
            }
        } catch (_: RuntimeException) {
            fallback(request, "无法确认离线语音支持，可在语音设置中选择系统语音服务")
        }
    }

    private fun fallback(request: Request, localError: String) {
        if (active !== request) return
        val systemAvailable = request.allowSystem && runCatching {
            factory.availability().system
        }.getOrDefault(false)
        if (request.holding && systemAvailable) prepare(request, onDevice = false)
        else fail(request, localError)
    }

    private fun startBackend(request: Request, attempt: Int) {
        if (!current(request, attempt) || !request.holding || request.started) return
        update(request, request.state.copy(phase = VoicePhase.STARTING))
        if (!current(request, attempt) || !request.holding) return
        request.started = true
        stageTimeout(request, 8_000) { fail(request, "麦克风启动超时，请松手后重试") }
        try {
            request.backend?.start(request.language)
        } catch (_: SecurityException) {
            fail(request, "需要麦克风权限，请在语音设置中允许录音")
        } catch (_: RuntimeException) {
            fail(request, "语音识别未能启动，请松手后重试")
        }
    }

    private fun events(request: Request, attempt: Int) = object : VoiceBackend.Events {
        override fun onReady() {
            if (!current(request, attempt) || request.ready || request.stopSent || !request.started) return
            request.ready = true
            request.stageTimer?.cancel()
            request.stageTimer = null
            if (!request.holding) stopBackend(request)
            else update(request, request.state.copy(phase = VoicePhase.LISTENING))
        }

        override fun onSpeechEnded() {
            if (!current(request, attempt) || !request.started || request.result != null || request.stopSent) return
            request.stopSent = true
            update(request, request.state.copy(phase = VoicePhase.PROCESSING, level = 0f))
            stageTimeout(request, 10_000) { fail(request, "识别结果等待超时，请重新长按空格") }
        }

        override fun onPartial(text: String) {
            if (!current(request, attempt) || !request.started || request.result != null) return
            update(request, request.state.copy(partialText = text))
        }

        override fun onLevel(level: Float) {
            if (!current(request, attempt) || request.state.phase != VoicePhase.LISTENING) return
            update(request, request.state.copy(level = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f))
        }

        override fun onResult(text: String) {
            if (!current(request, attempt) || !request.started || request.result != null) return
            val result = text.trim()
            if (result.isEmpty()) {
                fail(request, "没有识别到文字，请重新长按空格说话")
                return
            }
            request.result = result
            request.stageTimer?.cancel()
            request.stageTimer = null
            // Invalidate this backend before destroy: some services report an
            // error or a duplicate final callback during teardown.
            ++request.attempt
            releaseBackend(request, cancel = false)
            if (request.holding) {
                update(request, request.state.copy(phase = VoicePhase.RESULT_READY, partialText = result, level = 0f))
            } else complete(request, result)
        }

        override fun onError(message: String) {
            if (current(request, attempt)) fail(request, message)
        }
    }

    private fun stopBackend(request: Request) {
        if (active !== request || request.result != null) return
        if (request.stopSent) return
        request.stopSent = true
        update(request, request.state.copy(phase = VoicePhase.PROCESSING, level = 0f))
        if (active !== request) return
        stageTimeout(request, 10_000) { fail(request, "识别结果等待超时，请重新长按空格") }
        try {
            request.backend?.stop()
        } catch (_: RuntimeException) {
            fail(request, "结束语音识别失败，请重新长按空格")
        }
    }

    private fun current(request: Request, attempt: Int) = active === request && request.attempt == attempt

    private fun update(request: Request, state: VoiceState) {
        if (active !== request) return
        request.state = state
        listener.onState(state)
    }

    private fun stageTimeout(request: Request, delay: Long, action: () -> Unit) {
        request.stageTimer?.cancel()
        if (active !== request) return
        request.stageTimer = scheduler.after(delay) { if (active === request) action() }
    }

    private fun fail(request: Request, message: String) {
        if (active !== request) return
        // Tear down first, then emit terminal callbacks. Reentrant cancellation
        // from the UI and late provider callbacks cannot duplicate onFinished.
        finish(request, cancelBackend = true, error = message)
    }

    private fun complete(request: Request, text: String) {
        finish(request, cancelBackend = false, result = text)
    }

    private fun finish(request: Request, cancelBackend: Boolean, error: String? = null, result: String? = null) {
        if (active !== request) return
        active = null
        ++request.attempt
        request.stageTimer?.cancel()
        request.holdTimer?.cancel()
        releaseBackend(request, cancelBackend)
        finishing = true
        try {
            if (error != null) listener.onError(error)
            else if (result != null) listener.onResult(result)
        } finally {
            try {
                listener.onFinished()
            } finally {
                finishing = false
            }
        }
    }

    private fun releaseBackend(request: Request, cancel: Boolean) {
        val backend = request.backend ?: return
        request.backend = null
        if (cancel) runCatching { backend.cancel() }
        runCatching { backend.destroy() }
    }
}

/** Accept a provider's broad language tag, but never silently switch dialect/region. */
internal fun matchesVoiceLanguage(requested: String, available: String): Boolean {
    fun locale(tag: String) = Locale.forLanguageTag(tag.replace('_', '-'))
    val wanted = locale(requested)
    val actual = locale(available)
    fun language(value: Locale) = if (value.language == "cmn") "zh" else value.language
    if (language(wanted).isEmpty() || language(wanted) != language(actual)) return false
    if (wanted.country.isNotEmpty() && actual.country.isNotEmpty() && wanted.country != actual.country) return false
    val wantedScript = wanted.script.ifEmpty {
        if (language(wanted) == "zh" && wanted.country in setOf("CN", "SG")) "Hans" else ""
    }
    return wantedScript.isEmpty() || actual.script.isEmpty() || wantedScript == actual.script
}
