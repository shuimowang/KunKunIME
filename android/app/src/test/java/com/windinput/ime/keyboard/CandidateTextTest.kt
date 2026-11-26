package com.windinput.ime.keyboard

import com.windinput.ime.core.TextSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateTextTest {

    private val pingguo = listOf(
        TextSegment("苹", null),
        TextSegment("(ping)", TextSegment.ROLE_PINYIN_HINT),
        TextSegment("果", null),
    )

    /** 假笔：正文每字 10、提示每字 1——两支笔要分得开，才验得出逐段换笔 */
    private val measure = { text: String, hint: Boolean -> text.length * (if (hint) 1f else 10f) }

    @Test
    fun displayJoinsSegmentsOrFallsBackToItem() {
        assertEquals("苹(ping)果", CandidateText.display("苹果", pingguo))
        assertEquals("苹果", CandidateText.display("苹果", emptyList()))
        assertEquals("苹果", CandidateText.display("苹果", null))
    }

    @Test
    fun widthMeasuresEachSegmentWithItsOwnPaint() {
        assertEquals(10f + 6f + 10f, CandidateText.width("苹果", pingguo, measure), 0f)
        assertEquals(20f, CandidateText.width("苹果", null, measure), 0f)
    }

    @Test
    fun onlyFuzzyHintRoleIsHint() {
        assertTrue(CandidateText.isHint(pingguo[1]))
        assertFalse(CandidateText.isHint(pingguo[0]))
        assertFalse(CandidateText.isHint(TextSegment("x", "comment")))
    }

    /** 提示色取强调色的文字形态，深浅两套都与正文色、注释灰（hint）分得开 */
    @Test
    fun hintColorIsAccentTextDistinctFromBodyAndComment() {
        for (p in listOf(Palette.light, Palette.dark)) {
            assertEquals(p.accentText, CandidateText.hintColor(p))
            assertNotEquals(p.text, CandidateText.hintColor(p))
            assertNotEquals(p.hint, CandidateText.hintColor(p))
        }
    }

    @Test
    fun alignPadsOrTruncates() {
        assertEquals(listOf(pingguo, emptyList(), emptyList()), CandidateText.align(listOf(pingguo), 3, emptyList()))
        assertEquals(listOf(pingguo), CandidateText.align(listOf(pingguo, pingguo), 1, emptyList()))
    }
}
