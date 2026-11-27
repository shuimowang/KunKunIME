package com.windinput.ime.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.windinput.ime.settings.VoiceEngine
import com.windinput.ime.settings.VoicePreferences
import com.windinput.ime.voice.model.OfflineModelStore

/**
 * The selected local model owns an in-memory recorder, or SpeechRecognizer owns
 * capture for an explicitly selected system service. Neither path stores audio
 * or receives surrounding editor text.
 *
 * The local model never falls back to a system service. A system service may
 * send audio off-device, so fallback must also have explicit consent.
 */
class VoiceInputController(context: Context, listener: Listener) {
    interface Listener : VoiceInputListener

    private val applicationContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val factory = SelectedVoiceBackendFactory(applicationContext)
    private val session = VoiceInputSession(
        factory,
        VoiceScheduler { delay, action ->
            val runnable = Runnable(action)
            handler.postDelayed(runnable, delay)
            VoiceTimer { handler.removeCallbacks(runnable) }
        },
        listener,
    )

    val isActive: Boolean get() = session.isActive

    fun start(languageTag: String, allowSystemFallback: Boolean = false): Boolean {
        requireMainThread()
        if (session.isActive) return false
        val engine = VoicePreferences.engine(applicationContext)
        factory.engine = engine
        return session.start(languageTag, allowSystemFallback && engine == VoiceEngine.SYSTEM_ALLOWED)
    }

    fun stop() {
        requireMainThread()
        session.stop()
    }

    fun cancel() {
        requireMainThread()
        session.cancel()
    }

    fun destroy() {
        requireMainThread()
        session.destroy()
        OfflineRecognizerWorker.releaseWhenIdle()
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Voice input must run on the main thread" }
    }

    companion object {
        fun availability(context: Context): VoiceAvailability = VoiceAvailability(
            onDevice = runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false),
            system = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false),
        )

        fun hasAnyRecognition(context: Context): Boolean = when (VoicePreferences.engine(context)) {
            VoiceEngine.LOCAL_MODEL -> OfflineModelStore.get(context).isReady()
            VoiceEngine.SYSTEM_ON_DEVICE -> availability(context).onDevice
            VoiceEngine.SYSTEM_ALLOWED -> availability(context).let { it.onDevice || it.system }
        }
    }
}

private class SelectedVoiceBackendFactory(private val context: Context) : VoiceBackendFactory {
    var engine: VoiceEngine = VoiceEngine.LOCAL_MODEL

    override fun availability(): VoiceAvailability = when (engine) {
        VoiceEngine.LOCAL_MODEL -> VoiceAvailability(OfflineModelStore.get(context).isReady(), system = false)
        VoiceEngine.SYSTEM_ON_DEVICE -> VoiceInputController.availability(context).copy(system = false)
        VoiceEngine.SYSTEM_ALLOWED -> VoiceInputController.availability(context)
    }

    override fun create(onDevice: Boolean, events: VoiceBackend.Events): VoiceBackend = when (engine) {
        VoiceEngine.LOCAL_MODEL -> {
            check(onDevice) { "The local model cannot use a network recognizer" }
            OfflineVoiceBackend(context, events)
        }
        else -> AndroidVoiceBackend(context, onDevice, events)
    }
}

private class AndroidVoiceBackend(
    private val context: Context,
    private val onDevice: Boolean,
    private val events: VoiceBackend.Events,
) : VoiceBackend {
    private val recognizer = if (onDevice) {
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    } else SpeechRecognizer.createSpeechRecognizer(context)

    init {
        try {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = events.onReady()
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = events.onLevel((rmsdB + 2f) / 12f)
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = events.onSpeechEnded()
                override fun onError(error: Int) = events.onError(recognitionErrorMessage(error))
                override fun onResults(results: Bundle?) = events.onResult(bestResult(results))
                override fun onPartialResults(partialResults: Bundle?) = events.onPartial(bestResult(partialResults))
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        } catch (error: RuntimeException) {
            runCatching { recognizer.destroy() }
            throw error
        }
    }

    override fun checkLocalSupport(language: String, result: (Boolean, String?) -> Unit) {
        recognizer.checkRecognitionSupport(intent(language), context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                val installed = recognitionSupport.installedOnDeviceLanguages.any {
                    matchesVoiceLanguage(language, it)
                }
                result(installed, if (installed) null else "当前语言的离线语音模型未安装，可在语音设置中选择系统语音服务")
            }

            override fun onError(error: Int) {
                result(false, if (error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT) {
                    "此设备无法确认离线语音支持，可在语音设置中选择系统语音服务"
                } else recognitionErrorMessage(error))
            }
        })
    }

    override fun start(language: String) = recognizer.startListening(intent(language))
    override fun stop() = recognizer.stopListening()
    override fun cancel() = recognizer.cancel()
    override fun destroy() = recognizer.destroy()

    private fun intent(language: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // This extra is advisory only. The separate on-device factory is what
        // prevents fallback to a network recognizer in the default mode.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, onDevice)
    }

    private fun bestResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull { it.isNotBlank() }.orEmpty()
}

private fun recognitionErrorMessage(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麦克风权限，请在语音设置中允许录音"
    SpeechRecognizer.ERROR_AUDIO -> "无法使用麦克风，请检查录音权限和系统麦克风开关"
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "系统语音服务连接失败，请检查网络后重试"
    SpeechRecognizer.ERROR_NO_MATCH -> "没有识别到文字，请重新长按空格说话"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听到声音，请重新长按空格说话"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "语音服务正忙，请稍后重试"
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "语音服务不支持当前语言，请检查系统语音设置"
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "当前语言的语音模型未安装，请检查系统语音设置"
    SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "语音服务已断开，请稍后重试"
    else -> "语音识别失败，请松手后重试"
}
