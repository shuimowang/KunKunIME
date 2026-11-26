package com.windinput.ime.panels

import com.windinput.ime.core.SchemaEntry
import com.windinput.ime.panels.KeyboardChoices.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardChoicesTest {

    private fun entry(index: Int, id: String, name: String, active: Boolean = false) =
        SchemaEntry(index = index, id = id, name = name, iconLabel = "", active = active)

    private val schemas = listOf(
        entry(0, "pinyin", "全拼"),
        entry(1, "shuangpin", "双拼"),
        entry(2, "wubi86", "五笔", active = true),
        entry(3, "english", "清风英文"),
    )

    /** 真实顺序：方案按文件名排序，english 排在中文方案中间 */
    private val sortedByFile = listOf(
        entry(0, "english", "清风英文"),
        entry(1, "pinyin", "全拼"),
        entry(2, "wubi86", "五笔", active = true),
    )

    @Test
    fun knownTypesGetKeyboardLabels() {
        assertEquals("全键盘拼音", KeyboardChoices.labelOf("pinyin", "全拼"))
        assertEquals("双拼键盘", KeyboardChoices.labelOf("shuangpin", "双拼"))
        assertEquals("五笔键盘", KeyboardChoices.labelOf("wubi86", "五笔"))
        assertEquals("五笔拼音键盘", KeyboardChoices.labelOf("wubi86_pinyin", "五笔拼音"))
        assertEquals("清风英文", KeyboardChoices.labelOf("english", "清风英文"))
    }

    @Test
    fun unknownTypeUsesSchemaNameOrIdWhenNameEmpty() {
        assertEquals("我的混输", KeyboardChoices.labelOf("my_mix", "我的混输"))
        assertEquals("my_mix", KeyboardChoices.labelOf("my_mix", ""))
    }

    @Test
    fun badgesAreOneCharacter() {
        assertEquals("拼", KeyboardChoices.badgeOf("pinyin", "全拼"))
        assertEquals("双", KeyboardChoices.badgeOf("shuangpin", "双拼"))
        assertEquals("五", KeyboardChoices.badgeOf("wubi86", "五笔"))
        assertEquals("英", KeyboardChoices.badgeOf("english", "清风英文"))
        assertEquals("我", KeyboardChoices.badgeOf("my_mix", "我的混输"))
    }

    @Test
    fun buildOrdersChineseSchemasThenEnglishSchemaDirectEnglishManage() {
        val labels = KeyboardChoices.build(schemas, chineseMode = true).map { it.label }
        assertEquals(listOf("全键盘拼音", "双拼键盘", "五笔键盘", "清风英文", "英文直输", "键盘管理"), labels)
    }

    @Test
    fun englishSchemaGoesAfterChineseSchemasWhateverItsIndex() {
        val choices = KeyboardChoices.build(sortedByFile, chineseMode = true)
        assertEquals(listOf("全键盘拼音", "五笔键盘", "清风英文", "英文直输", "键盘管理"), choices.map { it.label })
        assertEquals(Action.Schema(0), choices[2].action)
    }

    @Test
    fun englishSchemaAndDirectEnglishHaveDistinctBadges() {
        val badges = KeyboardChoices.build(schemas, chineseMode = true).associate { it.label to it.badge }
        assertEquals("英", badges["清风英文"])
        assertEquals("A", badges["英文直输"])
    }

    @Test
    fun activeEnglishSchemaHighlightsEnglishSchemaNotDirectEnglish() {
        val withEnglishActive = schemas.map { it.copy(active = it.id == "english") }
        val selected = KeyboardChoices.build(withEnglishActive, chineseMode = true).filter { it.selected }.map { it.label }
        assertEquals(listOf("清风英文"), selected)
    }

    @Test
    fun chineseModeHighlightsActiveSchemaOnly() {
        val selected = KeyboardChoices.build(schemas, chineseMode = true).filter { it.selected }.map { it.label }
        assertEquals(listOf("五笔键盘"), selected)
    }

    @Test
    fun englishModeHighlightsDirectEnglishOnly() {
        val selected = KeyboardChoices.build(schemas, chineseMode = false).filter { it.selected }.map { it.label }
        assertEquals(listOf("英文直输"), selected)
    }

    @Test
    fun actionsCarrySchemaIndexAndSpecialEntries() {
        val actions = KeyboardChoices.build(schemas, chineseMode = true).map { it.action }
        assertEquals(
            listOf(Action.Schema(0), Action.Schema(1), Action.Schema(2), Action.Schema(3), Action.English, Action.Manage),
            actions,
        )
    }

    @Test
    fun directEnglishShownEvenWithoutEnglishSchema() {
        val choices = KeyboardChoices.build(listOf(entry(0, "wubi86", "五笔", active = true)), chineseMode = true)
        assertEquals(listOf("五笔键盘", "英文直输", "键盘管理"), choices.map { it.label })
    }

    @Test
    fun manageTileUsesIconNotBadge() {
        assertNull(KeyboardChoices.build(schemas, chineseMode = true).last().badge)
    }
}
