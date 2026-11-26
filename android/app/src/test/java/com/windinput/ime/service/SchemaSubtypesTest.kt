package com.windinput.ime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SchemaSubtypesTest {

    @Test
    fun extraValueRoundTrips() {
        for (id in listOf("pinyin", "wubi86", "wubi86_pinyin", "english")) {
            assertEquals(id, SchemaSubtypes.schemaOf(SchemaSubtypes.extraValue(id)))
        }
    }

    /** 兜底子类型（method.xml）没有 extraValue；别的键混在一起也认得出 */
    @Test
    fun schemaOfToleratesMissingAndMixedExtras() {
        assertNull(SchemaSubtypes.schemaOf(null))
        assertNull(SchemaSubtypes.schemaOf(""))
        assertNull(SchemaSubtypes.schemaOf("schema="))
        assertEquals("pinyin", SchemaSubtypes.schemaOf("AsciiCapable,schema=pinyin"))
    }

    /** 系统按子类型 id 记住用户的选择：必须跨版本稳定（String.hashCode 由语言规范定死），且各方案互不相同、不为 0 */
    @Test
    fun subtypeIdIsStableAndDistinct() {
        assertEquals("wind-schema:pinyin".hashCode(), SchemaSubtypes.subtypeId("pinyin"))
        val ids = listOf("pinyin", "wubi86", "shuangpin", "english", "wubi86_pinyin").map(SchemaSubtypes::subtypeId)
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertNotEquals(0, it) }
    }
}
