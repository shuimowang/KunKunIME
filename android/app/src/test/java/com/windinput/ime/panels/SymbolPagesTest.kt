package com.windinput.ime.panels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SymbolPagesTest {

    @Test
    fun itemsSplitOnAnyWhitespaceAndKeepMultiCharSymbols() {
        val cats = SymbolPages.parse(
            """
            [[category]]
            name = "中"
            symbols = ""${'"'}
            ， 。  ……
            —— ·
            ""${'"'}
            """.trimIndent(),
        )
        assertEquals(listOf(SymbolCategory("中", listOf("，", "。", "……", "——", "·"))), cats)
    }

    @Test
    fun missingCategoryTableMeansNoCategories() {
        assertEquals(emptyList<SymbolCategory>(), SymbolPages.parse("x = 1"))
    }

    /** 随包数据：分类顺序按两家的底部 tab，名字不重，每类都有内容、类内无重复 */
    @Test
    fun bundledDataIsWellFormed() {
        val cats = SymbolPages.parse(File("src/main/assets/data/symbol_pages.toml").readText())
        assertEquals(listOf("中", "英", "括号", "货币", "数学", "序号", "拼音", "日文"), cats.take(8).map { it.name })
        assertEquals(cats.size, cats.map { it.name }.distinct().size)
        for (c in cats) {
            assertTrue("${c.name} 为空", c.items.isNotEmpty())
            assertEquals("${c.name} 有重复", c.items.size, c.items.distinct().size)
        }
    }
}
