package com.windinput.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberSideSymbolsTest {

    private val defaults = NumberSideSymbols.DEFAULTS

    @Test
    fun missingKeyMeansFactoryList() {
        assertEquals(defaults, NumberSideSymbols.parse(null))
    }

    @Test
    fun emptyStringMeansEmptyList() {
        assertEquals(emptyList<String>(), NumberSideSymbols.parse(""))
    }

    @Test
    fun parseSplitsOnNewlineAndKeepsComma() {
        assertEquals(listOf(",", "+", "（）"), NumberSideSymbols.parse(",\n+\n（）"))
    }

    @Test
    fun parseDropsBlankTooLongAndDuplicateItems() {
        assertEquals(listOf("+", "abcd"), NumberSideSymbols.parse("+\n \n\nabcde\nabcd\n+"))
    }

    @Test
    fun parseTrimsSpacesAroundItems() {
        assertEquals(listOf("+", "-"), NumberSideSymbols.parse(" + \n\t-"))
    }

    @Test
    fun formatRoundTrips() {
        val list = listOf(",", "+", "@#")
        assertEquals(list, NumberSideSymbols.parse(NumberSideSymbols.format(list)))
    }

    @Test
    fun formatOfEmptyListIsEmptyString() {
        assertEquals("", NumberSideSymbols.format(emptyList()))
    }

    @Test
    fun addAppendsCleanedInput() {
        assertEquals(
            NumberSideSymbols.AddResult.Added(listOf("+", "≈")),
            NumberSideSymbols.add(listOf("+"), " ≈ "),
        )
    }

    @Test
    fun addStripsInnerWhitespace() {
        assertEquals(
            NumberSideSymbols.AddResult.Added(listOf("()")),
            NumberSideSymbols.add(emptyList(), "( )"),
        )
    }

    @Test
    fun addRejectsBlank() {
        assertTrue(NumberSideSymbols.add(listOf("+"), "  ") is NumberSideSymbols.AddResult.Rejected)
    }

    @Test
    fun addRejectsDuplicate() {
        val r = NumberSideSymbols.add(listOf("+", "-"), "-")
        assertEquals(NumberSideSymbols.AddResult.Rejected("已有「-」"), r)
    }

    @Test
    fun addRejectsMoreThanFourChars() {
        assertTrue(NumberSideSymbols.add(emptyList(), "12345") is NumberSideSymbols.AddResult.Rejected)
    }

    @Test
    fun addCountsSupplementaryCharAsOne() {
        // 😂 是一个代码点、两个 UTF-16 单元：四个 😂 仍算 4 个字符
        assertEquals(
            NumberSideSymbols.AddResult.Added(listOf("😂😂😂😂")),
            NumberSideSymbols.add(emptyList(), "😂😂😂😂"),
        )
    }

    @Test
    fun removeDropsThatIndex() {
        assertEquals(listOf("+", "*"), NumberSideSymbols.remove(listOf("+", "-", "*"), 1))
    }

    @Test
    fun moveForwardAndBackward() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), NumberSideSymbols.move(list, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), NumberSideSymbols.move(list, 3, 0))
        assertEquals(list, NumberSideSymbols.move(list, 1, 1))
    }

    @Test
    fun dragTargetSwapsPastHalfARow() {
        assertEquals(0, NumberSideSymbols.dragTarget(0, 40f, 100f, 4))
        assertEquals(1, NumberSideSymbols.dragTarget(0, 60f, 100f, 4))
        assertEquals(0, NumberSideSymbols.dragTarget(2, -200f, 100f, 4))
    }

    @Test
    fun dragTargetStaysInsideList() {
        assertEquals(3, NumberSideSymbols.dragTarget(2, 900f, 100f, 4))
        assertEquals(0, NumberSideSymbols.dragTarget(1, -900f, 100f, 4))
    }

    @Test
    fun factoryListIsValidUnderOwnRules() {
        assertEquals(defaults, NumberSideSymbols.parse(NumberSideSymbols.format(defaults)))
    }
}
