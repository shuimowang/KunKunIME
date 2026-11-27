package com.windinput.ime.voice

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.windinput.ime.voice.model.OfflineModelFiles
import com.windinput.ime.voice.model.OfflineModelStore
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A single native lane and at most one recognizer per process. Sherpa's decode
 * is synchronous and has no cancellation API: cancellation is checked between
 * bounded chunks, and release is never concurrent with decode. The idle cache
 * avoids a model load for each short sentence and drops its memory after 30 s.
 */
internal object OfflineRecognizerWorker {
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "kunkun-offline-recognizer").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private var recognizer: OfflineRecognizer? = null
    private var fingerprint: String? = null
    private var verifiedStore: OfflineModelStore? = null
    private val owners = mutableSetOf<Any>()
    private var idleRelease: ScheduledFuture<*>? = null

    fun prepare(
        owner: Any,
        store: OfflineModelStore,
        cancelled: () -> Boolean,
        done: (Boolean, String?) -> Unit,
    ) {
        executor.execute {
            if (cancelled()) return@execute
            idleRelease?.cancel(false)
            idleRelease = null
            try {
                // The store holds its installation lock while the native
                // constructor opens the verified files. Deletion cannot race it.
                // An already-loaded model is the same verified immutable model;
                // it needs no disk access for another sentence. isReady prevents
                // starting from this cache while the user deletes/replaces it.
                val ready = if (recognizer != null && verifiedStore === store && store.isReady()) {
                    true
                } else {
                    runBlocking {
                        store.withVerifiedFiles { files ->
                            if (cancelled()) false
                            else {
                                ensureRecognizer(files)
                                verifiedStore = store
                                true
                            }
                        }
                    } == true
                }
                if (cancelled()) return@execute
                if (ready) {
                    owners += owner
                    done(true, null)
                } else done(false, "离线语音包未就绪，请到语音设置下载或重新下载")
            } catch (_: LinkageError) {
                dropRecognizer()
                if (!cancelled()) done(false, "离线语音引擎无法加载，请更新困困输入法后重试")
            } catch (_: OutOfMemoryError) {
                dropRecognizer()
                if (!cancelled()) done(false, "内存不足，无法加载离线语音；请关闭其他大型应用后重试")
            } catch (_: Exception) {
                dropRecognizer()
                if (!cancelled()) done(false, "离线语音包无法加载，请到语音设置重新下载")
            } finally {
                scheduleIdleRelease()
            }
        }
    }

    fun decode(
        owner: Any,
        samples: ShortArray,
        cancelled: () -> Boolean,
        done: (String?, String?) -> Unit,
    ) {
        executor.execute {
            try {
                if (cancelled()) return@execute
                val engine = recognizer
                if (owner !in owners || engine == null) {
                    done(null, "离线语音引擎已释放，请重新长按空格")
                    return@execute
                }
                if (!hasAudibleInput(samples)) {
                    done(null, "没有听到声音，请重新长按空格说话")
                    return@execute
                }
                val parts = ArrayList<String>()
                for (range in offlineDecodeRanges(samples)) {
                    if (cancelled()) return@execute
                    val waveform = FloatArray(range.last - range.first + 1) { index ->
                        samples[range.first + index] / 32768f
                    }
                    val stream = engine.createStream()
                    try {
                        stream.acceptWaveform(waveform, OfflinePcmBuffer.SAMPLE_RATE)
                        waveform.fill(0f)
                        if (cancelled()) return@execute
                        engine.decode(stream)
                        if (cancelled()) return@execute
                        parts += cleanOfflineTranscription(engine.getResult(stream).text)
                    } finally {
                        waveform.fill(0f)
                        stream.release()
                    }
                }
                if (!cancelled()) {
                    val text = joinOfflineTranscriptions(parts)
                    if (text.isBlank()) done(null, "没有识别到文字，请重新长按空格说话")
                    else done(text, null)
                }
            } catch (_: LinkageError) {
                dropRecognizer()
                if (!cancelled()) done(null, "离线语音引擎运行失败，请更新应用后重试")
            } catch (_: OutOfMemoryError) {
                dropRecognizer()
                if (!cancelled()) done(null, "可用内存不足，请缩短语音后重试")
            } catch (_: Exception) {
                dropRecognizer()
                if (!cancelled()) done(null, "离线识别失败，请松手后重新说话")
            } finally {
                samples.fill(0)
                scheduleIdleRelease()
            }
        }
    }

    fun release(owner: Any) {
        executor.execute {
            owners -= owner
            scheduleIdleRelease()
        }
    }

    fun releaseWhenIdle() {
        executor.execute { if (owners.isEmpty()) dropRecognizer() }
    }

    private fun ensureRecognizer(files: OfflineModelFiles) {
        val identity = listOf(files.model, files.tokens).joinToString("|") {
            "${it.absolutePath}:${it.length()}:${it.lastModified()}"
        }
        if (recognizer != null && fingerprint == identity) return
        check(owners.isEmpty()) { "Cannot replace a model used by an active request" }
        dropRecognizer()
        recognizer = OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = files.model.absolutePath,
                        // The same model supports Chinese, English and mixing.
                        language = "auto",
                        useInverseTextNormalization = true,
                    ),
                    tokens = files.tokens.absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                    debug = false,
                ),
            ),
        )
        fingerprint = identity
    }

    private fun scheduleIdleRelease() {
        if (owners.isNotEmpty() || recognizer == null) return
        idleRelease?.cancel(false)
        idleRelease = executor.schedule({
            if (owners.isEmpty()) dropRecognizer()
        }, 30, TimeUnit.SECONDS)
    }

    private fun dropRecognizer() {
        idleRelease?.cancel(false)
        idleRelease = null
        val old = recognizer
        recognizer = null
        fingerprint = null
        verifiedStore = null
        // This is only called on the native lane, after decode has returned.
        if (old != null) runCatching { old.release() }
    }
}
