package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigSnapshotTest {

    @Test
    fun nestedSectionsBecomeDottedKeys() {
        val flat = ConfigSnapshot.flatten(
            """
            [input.association]
            kind = "smart"
            max_count = 9
            space_commits = true

            [schema.pinyin.fuzzy]
            zh_z = false
            """.trimIndent(),
        )
        assertEquals("smart", flat["input.association.kind"])
        assertEquals(9L, flat["input.association.max_count"])
        assertEquals(true, flat["input.association.space_commits"])
        assertEquals(false, flat["schema.pinyin.fuzzy.zh_z"])
    }

    @Test
    fun inlineTablesAndArraysFlatten() {
        val flat = ConfigSnapshot.flatten(
            """
            [ui]
            theme = { name = "default", style = "system" }
            [input.temp_pinyin]
            trigger_keys = ["backtick", "semicolon"]
            """.trimIndent(),
        )
        assertEquals("system", flat["ui.theme.style"])
        assertEquals(listOf("backtick", "semicolon"), flat["input.temp_pinyin.trigger_keys"])
    }
}
