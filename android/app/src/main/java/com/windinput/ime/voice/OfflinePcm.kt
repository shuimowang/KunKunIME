package com.windinput.ime.voice

import kotlin.math.sqrt

/** PCM is retained only in memory, with a fixed upper bound independent of hold duration. */
internal class OfflinePcmBuffer(
    val sampleRate: Int = SAMPLE_RATE,
    maxSeconds: Int = MAX_SECONDS,
) {
    private val samples = ShortArray(sampleRate * maxSeconds)
    var size: Int = 0
        private set

    val remaining: Int get() = samples.size - size

    fun append(source: ShortArray, count: Int): Float {
        require(count in 0..source.size)
        val accepted = minOf(count, remaining)
        source.copyInto(samples, size, 0, accepted)
        size += accepted
        return rms(source, 0, accepted).toFloat()
    }

    fun take(): ShortArray = samples.copyOf(size).also { clear() }

    fun clear() {
        samples.fill(0)
        size = 0
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 60
    }
}

/** Only a quiet/silent-input guard, not a claim of full speech/noise classification. */
internal fun hasAudibleInput(samples: ShortArray, sampleRate: Int = OfflinePcmBuffer.SAMPLE_RATE): Boolean {
    val frame = sampleRate / 50 // 20 ms
    if (samples.size < sampleRate / 5) return false
    var audibleFrames = 0
    for (offset in samples.indices step frame) {
        if (rms(samples, offset, minOf(frame, samples.size - offset)) >= 0.001) audibleFrames++
    }
    return audibleFrames >= 5
}

/**
 * A long non-streaming utterance can have a large native memory peak. Decode at
 * most 15 seconds per stream, preferring a quiet 160 ms boundary in its last
 * three seconds. Ranges are adjacent: every captured sample appears exactly
 * once, with neither an overlapping transcription nor a discarded boundary.
 */
internal fun offlineDecodeRanges(
    samples: ShortArray,
    sampleRate: Int = OfflinePcmBuffer.SAMPLE_RATE,
    maxSeconds: Int = 15,
): List<IntRange> {
    require(sampleRate >= 50 && maxSeconds >= 4)
    if (samples.isEmpty()) return emptyList()
    val maximum = sampleRate * maxSeconds
    val frame = sampleRate / 50
    val quietWindow = frame * 8
    val result = ArrayList<IntRange>()
    var start = 0
    while (samples.size - start > maximum) {
        val hardEnd = start + maximum
        var cut = hardEnd
        var quietest = Double.POSITIVE_INFINITY
        val searchStart = hardEnd - sampleRate * 3
        for (offset in searchStart..(hardEnd - quietWindow) step frame) {
            val volume = rms(samples, offset, quietWindow)
            // Later equally quiet pauses avoid needlessly fragmenting silence.
            if (volume <= 0.003 && volume <= quietest) {
                quietest = volume
                cut = offset + quietWindow / 2
            }
        }
        result += start until cut
        start = cut
    }
    result += start until samples.size
    return result
}

internal fun rms(samples: ShortArray, offset: Int, count: Int): Double {
    if (count <= 0) return 0.0
    var power = 0.0
    for (i in offset until offset + count) {
        val value = samples[i].toDouble() / 32768.0
        power += value * value
    }
    return sqrt(power / count)
}

private val senseVoiceTags = Regex("<\\|[^<>]*\\|>")
private val whitespace = Regex("\\s+")

/** SenseVoice language/emotion/event control tags must never become user text. */
internal fun cleanOfflineTranscription(raw: String): String {
    if (raw.contains("<|nospeech|>", ignoreCase = true)) return ""
    return senseVoiceTags.replace(raw, "")
        .filterNot { it.isISOControl() && !it.isWhitespace() }
        .replace(whitespace, " ")
        .trim()
}

/** Preserve repetitions and punctuation; do not guess away overlapping words. */
internal fun joinOfflineTranscriptions(parts: List<String>): String = buildString {
    for (raw in parts) {
        val part = cleanOfflineTranscription(raw)
        if (part.isEmpty()) continue
        if (isNotEmpty()) {
            val previous = last()
            val next = part.first()
            val previousLatin = previous.code < 128 && previous.isLetterOrDigit()
            val nextLatin = next.code < 128 && next.isLetterOrDigit()
            if ((previousLatin && nextLatin) || (previous in ".!?;:" && nextLatin)) append(' ')
        }
        append(part)
    }
}
