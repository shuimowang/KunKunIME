package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickSourcesTest {

    /** 核心 default_mix_modes() 的出厂成员序（手抄真值，与 CATALOG 声明序对账） */
    private val factory = listOf(
        "quick_input.calc", "quick_input.number", "quick_input.date", "quick_input.repeat",
        "\$primary_pinyin", "english",
    )

    private fun ids(rows: List<QuickSources.Row>) = rows.map { it.source.id }
    private fun on(rows: List<QuickSources.Row>) = rows.filter { it.enabled }.map { it.source.id }

    /** 声明序前六个就是出厂成员序：补位次序与核心一致 */
    @Test
    fun catalogStartsWithFactoryOrder() {
        assertEquals(factory, QuickSources.CATALOG.take(6).map { it.id })
        assertEquals(8, QuickSources.CATALOG.size)
    }

    /** 出厂：前六个开，历史与生僻字关、排在末尾 */
    @Test
    fun factoryRows() {
        val rows = QuickSources.rows(factory)
        assertEquals(QuickSources.CATALOG.map { it.id }, ids(rows))
        assertEquals(factory, on(rows))
    }

    /** 行序以配置为准；缺席的按声明序补位到第一个声明序更大的已有来源之前 */
    @Test
    fun rowsFollowConfigOrderAndSlotMissing() {
        val current = listOf("english", "quick_input.calc", "\$rare_char")
        val rows = QuickSources.rows(current)
        assertEquals(current, on(rows))
        assertEquals(
            // 同电脑版 managed_order：数字插在第一个声明序比它大的已有来源（英文）之前，依此类推
            listOf(
                "quick_input.number", "quick_input.date", "quick_input.repeat", "\$primary_pinyin",
                "english", "quick_input.calc", "quick_input.history", "\$rare_char",
            ),
            ids(rows),
        )
    }

    /** 没动任何东西时写回是恒等变换（含界面不认识的成员） */
    @Test
    fun untouchedWriteBackIsIdentity() {
        val current = listOf("wubi86", "quick_input.calc", "english", "my_schema")
        assertEquals(current, QuickSources.members(current, QuickSources.rows(current)))
    }

    /** 关掉一个来源：从 members 里去掉，别的不动 */
    @Test
    fun toggleOffRemovesMember() {
        val rows = QuickSources.rows(factory)
        val i = ids(rows).indexOf("english")
        assertEquals(factory - "english", QuickSources.members(factory, QuickSources.toggle(rows, i, false)))
    }

    /** 打开出厂不启用的来源：按行序插进去 */
    @Test
    fun toggleOnAddsAtRowPosition() {
        val rows = QuickSources.rows(factory)
        val i = ids(rows).indexOf("\$rare_char")
        assertEquals(factory + "\$rare_char", QuickSources.members(factory, QuickSources.toggle(rows, i, true)))
    }

    /** 拖动换序：members 跟着换；关着的行挪动不影响 members */
    @Test
    fun moveReorders() {
        val rows = QuickSources.rows(factory)
        val moved = QuickSources.move(rows, ids(rows).indexOf("english"), 0)
        assertEquals(listOf("english") + (factory - "english"), QuickSources.members(factory, moved))
        val offMoved = QuickSources.move(rows, ids(rows).indexOf("\$rare_char"), 0)
        assertEquals(factory, QuickSources.members(factory, offMoved))
    }

    /** 不认识的成员按锚点留在原处，不冲到末尾 */
    @Test
    fun foreignMembersKeepAnchor() {
        val current = listOf("quick_input.calc", "wubi86", "english")
        val rows = QuickSources.rows(current)
        val moved = QuickSources.move(rows, ids(rows).indexOf("english"), 0)
        assertEquals(listOf("english", "wubi86", "quick_input.calc"), QuickSources.members(current, moved))
        // 受管来源全关：外来成员仍在
        val allOff = rows.indices.fold(rows) { r, i -> QuickSources.toggle(r, i, false) }
        assertEquals(listOf("wubi86"), QuickSources.members(current, allOff))
    }

    @Test
    fun summaryListsEnabledByPriority() {
        assertEquals("全部关闭", QuickSources.summary(emptyList()))
        assertEquals("英文 · 计算", QuickSources.summary(listOf("english", "quick_input.calc")))
        assertEquals("计算 · 数字 / 金额 · 日期 / 年月 · 重复上次上屏 等 6 项", QuickSources.summary(factory))
    }

    /** 行高不一：越过相邻行的一半才换，偏移扣掉换过的行高 */
    @Test
    fun dragTargetUsesNeighbourHeights() {
        val h = listOf(100f, 200f, 100f, 100f)
        assertEquals(0 to 90f, QuickSources.dragTarget(0, 90f, h))
        assertEquals(1 to -99f, QuickSources.dragTarget(0, 101f, h))
        assertEquals(2 to 0f, QuickSources.dragTarget(0, 300f, h))
        assertEquals(3 to 50f, QuickSources.dragTarget(0, 1000f - 550f, h))
        assertEquals(0 to 20f, QuickSources.dragTarget(2, -280f, h))
        assertEquals(3 to 500f, QuickSources.dragTarget(3, 500f, h))
    }
}
