package com.windinput.ime.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePcmTest {
    @Test
    fun recordingMemoryIsBoundedAndTakingAudioClearsTheReusableBuffer() {
        val buffer = OfflinePcmBuffer(sampleRate = 100, maxSeconds = 1)
        buffer.append(ShortArray(70) { 7 }, 70)
        buffer.append(ShortArray(70) { 9 }, 70)
        assertEquals(0, buffer.remaining)
        val captured = buffer.take()
        assertEquals(100, captured.size)
        assertArrayEquals(ShortArray(70) { 7 } + ShortArray(30) { 9 }, captured)
        assertEquals(0, buffer.size)
        assertEquals(100, buffer.remaining)
        assertTrue(buffer.take().isEmpty())
    }

    @Test
    fun chunkingNeverDropsOrDuplicatesSamplesEvenWithoutPauses() {
        val sampleRate = 1000
        for (length in listOf(0, 1, 200, 14_999, 15_000, 15_001, 30_001, 60_000)) {
            val samples = ShortArray(length) { (1000 + it % 2000).toShort() }
            val ranges = offlineDecodeRanges(samples, sampleRate)
            val combined = ranges.flatMap { it.toList() }
            assertEquals((0 until length).toList(), combined)
            assertTrue(ranges.all { it.last - it.first + 1 <= 15_000 })
        }
    }

    @Test
    fun longUtteranceCutsAtAQuietPauseAndKeepsBothSides() {
        val samples = ShortArray(30_500) { 3000 }
        samples.fill(0, 13_000, 13_500)
        val ranges = offlineDecodeRanges(samples, sampleRate = 1000)
        assertTrue(ranges.first().last in 13_000..13_500)
        assertEquals(ranges.first().last + 1, ranges[1].first)
        assertEquals(30_499, ranges.last().last)
        assertEquals(samples.size, ranges.sumOf { it.last - it.first + 1 })
    }

    @Test
    fun emptySilentAndVeryShortInputAreNotPassedToTheLanguageModel() {
        assertFalse(hasAudibleInput(shortArrayOf()))
        assertFalse(hasAudibleInput(ShortArray(16_000)))
        assertFalse(hasAudibleInput(ShortArray(16_000) { 1 }))
        assertFalse(hasAudibleInput(ShortArray(1000) { 8000 }))
        assertTrue(hasAudibleInput(ShortArray(16_000) { if (it % 2 == 0) 3000 else -3000 }))
    }

    @Test
    fun metadataAndNonSpeechEventsCannotEnterTheEditor() {
        assertEquals("你好，2026年。", cleanOfflineTranscription("<|zh|><|NEUTRAL|><|Speech|><|withitn|>你好，2026年。"))
        assertEquals("", cleanOfflineTranscription("<|auto|><|nospeech|><|Event_UNK|>"))
        assertEquals("hello world", cleanOfflineTranscription("<|en|> hello\u0000\n  world "))
        assertEquals("<3", cleanOfflineTranscription("<3"))
    }

    @Test
    fun joiningKeepsLegitimateRepetitionAndSeparatesEnglishWords() {
        assertEquals("我们我们继续。", joinOfflineTranscriptions(listOf("我们", "我们继续。")))
        assertEquals("Hello world. Again", joinOfflineTranscriptions(listOf("Hello", "world.", "Again")))
        assertEquals("现在使用GitHub仓库。", joinOfflineTranscriptions(listOf("现在使用", "GitHub", "仓库。")))
        assertEquals("123年", joinOfflineTranscriptions(listOf("123", "年")))
    }
}
