package com.windinput.ime.settings

import com.windinput.ime.core.UserConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tomlj.Toml

class SettingsResetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun keyboardTierIsUiAndroidOnly() {
        assertTrue(SettingsReset.isKeyboardKey("ui.android.row_height_portrait"))
        assertTrue(SettingsReset.isKeyboardKey("ui.android.toolbar_pins"))
        assertFalse(SettingsReset.isKeyboardKey("ui.candidate.font_size"))
        assertFalse(SettingsReset.isKeyboardKey("ui.androidx"))
    }

    /** 方案选择与迁移锚点留下；其余引擎覆盖都清掉 */
    @Test
    fun engineTierKeepsSchemaChoiceAndVersion() {
        for (k in listOf("version", "schema.active", "schema.available")) assertTrue(k, SettingsReset.keepsEngineKey(k))
        for (k in listOf("schema.pinyin.fuzzy.enabled", "input.emoji.enabled", "mobile.grammar.enabled", "debug.log_level")) {
            assertFalse(k, SettingsReset.keepsEngineKey(k))
        }
    }

    @Test
    fun resetEngineRewritesFileWithKeptKeysOnly() {
        val f = tmp.newFile("config.toml")
        f.writeText(
            """
            version = 7
            [schema]
            active = "wubi86"
            available = ["wubi86", "english"]
            [schema.pinyin.fuzzy]
            enabled = true
            [input.emoji]
            enabled = true
            """.trimIndent(),
        )
        assertEquals(2, SettingsReset.resetEngine(f))
        val after = Toml.parse(f.readText())
        assertFalse(after.errors().toString(), after.hasErrors())
        assertEquals(setOf("version", "schema.active", "schema.available"), after.dottedKeySet())
        assertEquals(7L, after.getLong("version"))
        assertEquals("wubi86", after.getString("schema.active"))
        assertEquals(listOf("wubi86", "english"), after.getArray("schema.available")!!.toList())
    }

    @Test
    fun resetEngineLeavesCleanOrMissingFileAlone() {
        val f = tmp.newFile("clean.toml")
        f.writeText("[schema]\nactive = \"pinyin\"\n")
        val before = f.readText()
        assertEquals(0, SettingsReset.resetEngine(f))
        assertEquals(before, f.readText())
        assertEquals(0, SettingsReset.resetEngine(tmp.root.resolve("none.toml")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun resetEngineRefusesBrokenFile() {
        val f = tmp.newFile("broken.toml")
        f.writeText("[schema\nactive = ")
        SettingsReset.resetEngine(f)
    }

    @Test
    fun resetKeyboardDropsOnlyUiAndroid() {
        val f = tmp.newFile("user.toml")
        val cfg = UserConfig(f)
        cfg.put("ui.android.vibrate", false)
        cfg.put("ui.android.row_height_portrait", 50)
        cfg.put("other.flag", true)
        assertEquals(2, SettingsReset.resetKeyboard(cfg))
        assertEquals(mapOf<String, Any>("other.flag" to true), UserConfig(f).readAll())
    }

    @Test
    fun renderTomlPutsRootKeysFirst() {
        val text = SettingsReset.renderToml(mapOf("schema.active" to "a\"b", "version" to 3L))
        assertEquals("version = 3\n\n[schema]\nactive = \"a\\\"b\"\n", text)
    }
}
