package com.windinput.ime.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.windinput.ime.voice.model.OfflineModelStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10

/** One held utterance; audio exists only as a bounded PCM buffer in RAM. */
internal class OfflineVoiceBackend(
    context: Context,
    private val events: VoiceBackend.Events,
) : VoiceBackend {
    override val supportTimeoutMillis = 45_000L
    override val resultTimeoutMillis = 60_000L
    override val canStopBeforeReady = true

    private val store = OfflineModelStore.get(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val cancelled = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val recorderLock = Any()
    private var recorder: AudioRecord? = null

    override fun checkLocalSupport(language: String, result: (Boolean, String?) -> Unit) {
        OfflineRecognizerWorker.prepare(this, store, cancelled::get) { ready, error ->
            post { result(ready, error) }
        }
    }

    override fun start(language: String) {
        if (cancelled.get() || stopRequested.get() || !started.compareAndSet(false, true)) return
        Thread({ capture() }, "kunkun-offline-microphone").apply { isDaemon = true }.start()
    }

    override fun stop() {
        stopCapture(cancel = false)
    }

    override fun cancel() {
        stopCapture(cancel = true)
    }

    override fun destroy() {
        stopCapture(cancel = true)
        OfflineRecognizerWorker.release(this)
    }

    private fun stopCapture(cancel: Boolean) {
        // The lock establishes that startRecording is either before this stop,
        // or is forbidden. An asynchronous startup can never open the mic later.
        synchronized(recorderLock) {
            if (cancel) cancelled.set(true)
            stopRequested.set(true)
            recorder?.let { runCatching { it.stop() } }
        }
    }

    private fun capture() {
        // Include the bounded buffer allocation in the error boundary too.
        try {
            captureWithBuffer()
        } catch (_: OutOfMemoryError) {
            post { events.onError("可用内存不足，请关闭其他大型应用后重试") }
        }
    }

    private fun captureWithBuffer() {
        val pcm = OfflinePcmBuffer()
        val chunk = ShortArray(320)
        var audio: AudioRecord? = null
        var captureError: String? = null
        try {
            if (cancelled.get() || stopRequested.get()) return
            val minimum = AudioRecord.getMinBufferSize(
                OfflinePcmBuffer.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimum > 0) { "Unsupported microphone format" }
            val created = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(OfflinePcmBuffer.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setBufferSizeInBytes(maxOf(minimum, chunk.size * 4))
                .build()
            audio = created
            check(created.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            val began = synchronized(recorderLock) {
                if (cancelled.get() || stopRequested.get()) false
                else {
                    recorder = created
                    created.startRecording()
                    check(created.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
                    true
                }
            }
            if (!began) return
            post { events.onReady() }
            var frame = 0
            while (!cancelled.get() && !stopRequested.get() && pcm.remaining > 0) {
                val read = created.read(chunk, 0, minOf(chunk.size, pcm.remaining), AudioRecord.READ_BLOCKING)
                if (cancelled.get()) break
                if (read > 0) {
                    val volume = pcm.append(chunk, read)
                    if (++frame % 4 == 0) {
                        // Smooth visual scale; no audio samples leave this thread.
                        val level = if (volume <= 0f) 0f else ((20f * log10(volume) + 60f) / 50f).coerceIn(0f, 1f)
                        post { if (!stopRequested.get()) events.onLevel(level) }
                    }
                } else if (read < 0 && !stopRequested.get()) {
                    throw IllegalStateException("Microphone read failed")
                } else if (read == 0 && !stopRequested.get()) {
                    // Some providers return zero even for a blocking read.
                    // Avoid spinning a CPU core while waiting for a sample.
                    Thread.sleep(10)
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            captureError = "录音已中断，请重新长按空格说话"
        } catch (_: SecurityException) {
            captureError = "需要麦克风权限，请在语音设置中允许录音"
        } catch (_: OutOfMemoryError) {
            captureError = "可用内存不足，请关闭其他大型应用后重试"
        } catch (_: RuntimeException) {
            captureError = "无法使用麦克风，请检查录音权限和系统麦克风开关"
        } finally {
            synchronized(recorderLock) {
                recorder = null
                audio?.let {
                    runCatching { it.stop() }
                    runCatching { it.release() }
                }
            }
            chunk.fill(0)
            if (cancelled.get()) {
                pcm.clear()
            } else if (captureError != null) {
                pcm.clear()
                val message = captureError
                post { events.onError(message) }
            } else {
                val samples = pcm.take()
                post { events.onSpeechEnded() }
                OfflineRecognizerWorker.decode(this, samples, cancelled::get) { text, error ->
                    post {
                        if (text != null) events.onResult(text)
                        else events.onError(error ?: "离线识别失败，请重新长按空格")
                    }
                }
            }
        }
    }

    private fun post(action: () -> Unit) {
        main.post { if (!cancelled.get()) action() }
    }
}
