package com.windinput.ime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable
import java.io.File

/** A clean APK must contain this checkout's shared defaults plus explicit phone overrides. */
class FactoryConfigSyncTest {
    private fun plain(value: Any?): Any? = when (value) {
        is TomlArray -> value.toList().map(::plain)
        is TomlTable -> value.toMap().mapValues { plain(it.value) }
        else -> value
    }

    private fun values(path: String): Map<String, Any?> {
        val file = File(path)
        assertTrue("Missing build input: $path; run scripts/prepare-assets.py", file.isFile)
        val parsed = Toml.parse(file.readText())
        assertTrue("Invalid TOML in $path: ${parsed.errors()}", !parsed.hasErrors())
        return parsed.dottedKeySet().associateWith { plain(parsed.get(it)) }
    }

    @Test
    fun bundledConfigurationMatchesSharedProductAndPhoneDefaults() {
        val expected = values("../../WindInput/data/config.toml").toMutableMap()
        expected.putAll(values("../../WindInput/data_custom/config.toml"))
        expected.putAll(values("../assets/engine-overrides.toml"))
        assertEquals(expected, values("src/main/assets/data/config.toml"))
    }

    @Test
    fun phoneStartsInRealBundledPinyin() {
        val config = values("src/main/assets/data/config.toml")
        assertEquals("pinyin", config["schema.active"])
        val schemas = config["schema.available"] as List<*>
        assertTrue(schemas.containsAll(listOf("pinyin", "shuangpin", "english")))
        for (schema in schemas) {
            assertTrue("Unavailable phone schema $schema",
                File("src/main/assets/data/schemas/$schema.schema.toml").isFile)
        }
    }
}
