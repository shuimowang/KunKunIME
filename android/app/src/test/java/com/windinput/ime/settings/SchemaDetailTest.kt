package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.wind_android.InstalledSchemaInfo
import uniffi.wind_android.SchemaSettingGroup
import uniffi.wind_android.SchemaSettingItem
import uniffi.wind_android.SchemaSettingKind
import uniffi.wind_android.SchemaSettingsInfo
import java.io.File

class SchemaDetailTest {

    private val manifest by lazy {
        SettingsManifest.parse(File("src/main/assets/data/settings_manifest.toml").readText())
    }

    private fun item(
        key: String,
        kind: SchemaSettingKind = SchemaSettingKind.BOOL,
        value: String = "true",
        default: String = "true",
        overridden: Boolean = false,
        group: SchemaSettingGroup = SchemaSettingGroup.CODE_TABLE,
        options: List<String> = emptyList(),
    ) = SchemaSettingItem(key, group, kind, value, default, overridden, options)

    /** 核心给的码表覆盖键（wind-mobile `CODETABLE_KEYS`），与清单「码表」节逐项对上 */
    private val coreCodetableKeys = listOf(
        "auto_commit_at_full", "clear_on_empty_max", "top_code_commit", "punct_commit", "show_code_hint",
        "single_char", "frequency.enabled", "english_merge.enable", "auto_commit_min_len",
        "short_code_yield_level", "frequency.strategy",
        "wildcard", "wildcard_key", "wildcard_single_only", "z_key_repeat", "z_key_action",
    )

    @Test
    fun codetableRowsFollowManifestOrderAndSkipKeysWithoutSchemaLevel() {
        val items = coreCodetableKeys.map { item("engine.codetable.$it") }
        val rows = codetableRows(manifest, items)
        val manifestKeys = manifest.items
            .filter { it.page == "schema" && it.section == "码表" }
            .map { it.key.removePrefix("schema.codetable.") }
        // 清单「码表」节的键，核心要么有方案级覆盖（得逐项对上——漏一项就是清单新增了键而核心没跟上），
        // 要么明确列在 globalOnly 里（只有全局值的进阶参数）。新增清单键又不在两边，这里就红
        val globalOnly = manifestKeys.filter {
            it == "auto_phrase.enabled" || it.startsWith("auto_phrase.") ||
                it.startsWith("frequency.") && it != "frequency.enabled" && it != "frequency.strategy" ||
                it.startsWith("english_merge.") && it != "english_merge.enable" || it.startsWith("single_code_")
        }
        assertEquals(manifestKeys - globalOnly.toSet(), rows.map { it.second.key.removePrefix("engine.codetable.") })
        assertEquals("全码唯一自动上屏", rows.first().first.title)
        assertTrue("核心给了而清单没有的不画", codetableRows(manifest, listOf(item("engine.codetable.sentence_input"))).isEmpty())
    }

    @Test
    fun choosingTheDefaultValueResets() {
        val top = item("engine.codetable.top_code_commit", value = "false", default = "true", overridden = true)
        assertEquals(SchemaWrite.Reset(top.key), writeFor(top, "true"))
        assertEquals(SchemaWrite.Set(top.key, "false"), writeFor(top.copy(value = "true", overridden = false), "false"))
        val punct = item("punct.mode", SchemaSettingKind.CHOICE, "follow", "english", group = SchemaSettingGroup.BEHAVIOR)
        assertEquals("英文方案文件写了 english：选「跟随全局」是改动", SchemaWrite.Set("punct.mode", "follow"), writeFor(punct, "follow"))
    }

    @Test
    fun auxIsTriStateAndPinsExplicitValues() {
        val aux = item("engine.aux_code.enabled", value = "false", default = "false", group = SchemaSettingGroup.AUX_CODE)
        assertEquals(listOf("跟随全局（关）", "开启", "关闭"), auxLabels(aux))
        assertEquals(0, auxIndex(aux))
        assertEquals(2, auxIndex(aux.copy(overridden = true)))
        assertEquals(1, auxIndex(aux.copy(value = "true", overridden = true)))
        assertEquals(SchemaWrite.Reset(aux.key), auxWrite(aux, 0))
        // 与全局同值也写实值：三态里「关」与「跟随（关）」是两回事
        assertEquals(SchemaWrite.Set(aux.key, "false"), auxWrite(aux, 2))
        assertNull(auxWrite(aux, 3))
    }

    @Test
    fun summaryNamesTheDefaultOnlyWhenChanged() {
        val punct = item("punct.mode", SchemaSettingKind.CHOICE, "english", "follow", group = SchemaSettingGroup.BEHAVIOR)
        assertEquals("说明", rowSummary("说明", punct, PUNCT_LABELS))
        assertNull(rowSummary(null, punct, PUNCT_LABELS))
        assertEquals("说明\n已自定义 · 默认：跟随全局", rowSummary("说明", punct.copy(overridden = true), PUNCT_LABELS))
        val bool = item("phrases.enabled", value = "false", default = "true", overridden = true)
        assertEquals("已自定义 · 默认：开", rowSummary(null, bool, emptyMap()))
    }

    @Test
    fun candidateLayoutIsNotShownOnPhones() {
        val layout = item("candidate.layout", SchemaSettingKind.CHOICE, "vertical", "follow", true, SchemaSettingGroup.BEHAVIOR)
        assertTrue("手机候选条没有横竖排", !isShown(layout, manifest))
        assertTrue(isShown(item("punct.mode", group = SchemaSettingGroup.BEHAVIOR), manifest))
        assertTrue(isShown(item("engine.codetable.top_code_commit"), manifest))
        assertTrue(isShown(item(KEY_SENTENCE, group = SchemaSettingGroup.EXPERIMENTAL), manifest))
    }

    @Test
    fun infoSummaryJoinsFactsThenDescription() {
        val s = SchemaSettingsInfo("wubi86", "五笔", "codetable", "1.0", "内置", "五笔86版输入方案", emptyList())
        val builtin = InstalledSchemaInfo("wubi86", "五笔", true, false, false, true)
        assertEquals("码表 · 应用自带 · 版本 1.0 · 作者 内置\n五笔86版输入方案", infoSummary(s, builtin))
        assertEquals("码表", infoSummary(s.copy(version = "", author = "", description = ""), null))
    }

    @Test
    fun labels() {
        assertEquals("双拼", engineTypeLabel("shuangpin"))
        assertEquals("码表", engineTypeLabel("codetable"))
        assertEquals("应用自带", sourceLabel(user = false, builtin = true))
        assertEquals("用户导入（覆盖了自带版本）", sourceLabel(user = true, builtin = true))
        assertEquals("用户导入", sourceLabel(user = true, builtin = false))
    }

    @Test
    fun choiceEntriesAppendsUnknownValueInsteadOfShowingFirst() {
        val labels = linkedMapOf("0" to "关闭", "2" to "二码", "3" to "三码")
        val known = item("engine.codetable.auto_commit_min_len", SchemaSettingKind.INT, value = "2")
        assertEquals(listOf("0", "2", "3") to listOf("关闭", "二码", "三码"), choiceEntries(known, labels))
        val odd = item("engine.codetable.auto_commit_min_len", SchemaSettingKind.INT, value = "6")
        val (values, names) = choiceEntries(odd, labels)
        assertEquals(3, values.indexOf("6"))
        assertEquals("6（自定义）", names[3])
        val gone = item(KEY_SHUANGPIN_LAYOUT, SchemaSettingKind.CHOICE, value = "old", options = listOf("xiaohe", "ziranma"))
        assertEquals(listOf("xiaohe", "ziranma", "old"), choiceEntries(gone, emptyMap()).first)
    }
}
