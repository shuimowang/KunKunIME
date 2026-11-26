package com.windinput.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTweaksTest {

    private val letters = KeyboardLayouts.qwerty

    private fun bottom(mode: String) =
        LayoutTweaks.apply(letters, KeyboardLayer.LETTERS, mode, LayoutTweaks.NUMBER_FULL).last()

    private fun total(row: List<KeyDef>) = row.sumOf { it.widthWeight.toDouble() }

    private fun actions(row: List<KeyDef>) = row.map { it.action }

    @Test
    fun splitIsUntouched() {
        val out = LayoutTweaks.apply(letters, KeyboardLayer.LETTERS, LayoutTweaks.BOTTOM_SPLIT, LayoutTweaks.NUMBER_FULL)
        assertEquals(letters, out)
    }

    @Test
    fun symMergesCommaPeriodIntoOneKey() {
        val row = bottom(LayoutTweaks.BOTTOM_SYM)
        assertEquals(
            listOf(
                KeyAction.SymbolPage, KeyAction.SwitchSymbols, KeyAction.Text(','),
                KeyAction.Space, KeyAction.SwitchLang, KeyAction.Enter,
            ),
            actions(row),
        )
        val merged = row[2]
        assertEquals('.', merged.swipeUp)
        assertEquals('.', merged.textFor(english = true))
        assertEquals(total(letters.last()), total(row), 1e-4)
    }

    @Test
    fun mergeAlsoFoldsSymbolKeyIntoNumberKey() {
        val row = bottom(LayoutTweaks.BOTTOM_MERGE)
        assertEquals(
            listOf(
                KeyAction.SwitchSymbols, KeyAction.Text(','),
                KeyAction.Space, KeyAction.SwitchLang, KeyAction.Enter,
            ),
            actions(row),
        )
        assertEquals(total(letters.last()), total(row), 1e-4)
        // 123 键吃掉符号键的宽度，空格仍是最宽的
        assertTrue(row.maxBy { it.widthWeight }.action == KeyAction.Space)
    }

    @Test
    fun customBottomRowIsLeftAlone() {
        val custom = listOf(
            KeyDef("空格", KeyAction.Space),
            KeyDef("换行", KeyAction.Enter),
        )
        assertSame(custom, LayoutTweaks.bottomRow(custom, LayoutTweaks.BOTTOM_MERGE))
    }

    @Test
    fun otherRowsAndLayersKeepTheirShape() {
        val out = LayoutTweaks.apply(letters, KeyboardLayer.LETTERS, LayoutTweaks.BOTTOM_MERGE, LayoutTweaks.NUMBER_FULL)
        assertEquals(letters.dropLast(1), out.dropLast(1))
        assertEquals(
            KeyboardLayouts.symbols,
            LayoutTweaks.apply(KeyboardLayouts.symbols, KeyboardLayer.SYMBOLS, LayoutTweaks.BOTTOM_MERGE, LayoutTweaks.NUMBER_PAD9),
        )
    }

    @Test
    fun pad9SwapsSymbolKeyForNumberToggleOnNumberPage() {
        val pad = LayoutTweaks.apply(KeyboardLayouts.number, KeyboardLayer.NUMBER, LayoutTweaks.BOTTOM_SPLIT, LayoutTweaks.NUMBER_PAD9)
        val keys = pad.flatten()
        assertFalse(keys.any { it.action == KeyAction.SymbolPage })
        assertTrue(keys.any { it.action == KeyAction.NumberPad && it.label == "123" })
        // 默认（全键盘数字）数字页原样
        assertEquals(
            KeyboardLayouts.number,
            LayoutTweaks.apply(KeyboardLayouts.number, KeyboardLayer.NUMBER, LayoutTweaks.BOTTOM_SPLIT, LayoutTweaks.NUMBER_FULL),
        )
    }
}
