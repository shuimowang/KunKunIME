package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsManifestTest {

    private val bundled by lazy {
        SettingsManifest.parse(File("src/main/assets/data/settings_manifest.toml").readText())
    }

    @Test
    fun englishSpacingHasOnePhoneControl() {
        val keys = bundled.items.map { it.key }
        assertTrue("手机自动空格开关必须可用", "ui.android.auto_space" in keys)
        assertTrue("不能同时暴露桌面补空格开关，否则一次确认会出现两个空格",
            "schema.english.commit_space" !in keys)
    }

    @Test
    fun homeHasThreeSegmentsInDesignOrder() {
        assertEquals(
            listOf("schema", "input", "ui", "stats", "dict", "keyboard", "keys", "toolbar", "advanced", "about"),
            bundled.pages.map { it.id },
        )
        assertEquals(listOf("shared", "android", "tail"), bundled.pages.map { it.segment }.distinct())
    }

    /** 键盘项的值只在 K 侧，没有默认值就显示成空 */
    @Test
    fun androidItemsHaveDefaults() {
        val missing = bundled.items.filter { it.source == SettingSource.ANDROID && it.defaultValue == null }
        assertTrue("键盘项缺默认值：${missing.map { it.key }}", missing.isEmpty())
    }

    /** 键的命名空间决定来源：ui.android.* 只有键盘读，其余都是核心注册表里的键 */
    @Test
    fun sourceMatchesKeyNamespace() {
        val wrong = bundled.items.filter {
            (it.source == SettingSource.ANDROID) != it.key.startsWith("ui.android.")
        }
        assertTrue("来源与键名不符：${wrong.map { it.key }}", wrong.isEmpty())
    }

    @Test
    fun selectItemsHaveOptionsAndKeysAreUnique() {
        val noOptions = bundled.items.filter { it.type == "select" && it.options.isEmpty() }
        assertTrue("select 没有选项：${noOptions.map { it.key }}", noOptions.isEmpty())
        val dup = bundled.items.groupBy { it.key }.filter { it.value.size > 1 }.keys
        assertTrue("键重复：$dup", dup.isEmpty())
    }

    @Test
    fun sectionsKeepFirstAppearanceOrder() {
        assertEquals(
            listOf("码表", "拼音", "模糊音", "双拼", "混输", "英文"),
            bundled.sectionsOf("schema").map { it.first },
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun itemOnUnknownPageIsRejected() {
        SettingsManifest.parse(
            """
            [[page]]
            id = "a"
            [[item]]
            page = "b"
            key = "x"
            source = "engine"
            """.trimIndent(),
        )
    }

    @Test
    fun heightAndFontSizeItemsCarryPreview() {
        val previews = bundled.items.filter { it.preview != null }.associate { it.key to it.preview }
        assertEquals(
            mapOf(
                "ui.android.candidate_text_sp" to "candidate_text",
                "ui.android.row_height_portrait" to "keyboard_rows",
                "ui.android.row_height_landscape" to "keyboard_rows",
            ),
            previews,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownPreviewIsRejected() {
        SettingsManifest.parse(
            """
            [[page]]
            id = "a"
            [[item]]
            page = "a"
            key = "ui.android.x"
            source = "android"
            type = "int"
            preview = "nope"
            """.trimIndent(),
        )
    }

    /** 滑块的浮点值吸附到 min 起算的步长网格，且不出范围 */
    @Test
    fun sliderSnapsToStepGrid() {
        assertEquals(56, snapToStep(56.9f, 40, 72, 2))
        assertEquals(58, snapToStep(57.1f, 40, 72, 2))
        assertEquals(40, snapToStep(30f, 40, 72, 2))
        assertEquals(72, snapToStep(80f, 40, 72, 2))
        assertEquals(19, snapToStep(19.4f, 15, 24, 1))
    }

    /** 简繁二选一要互相指向，否则只有一个方向会自动关掉另一个 */
    @Test
    fun exclusivePairsPointAtEachOther() {
        val pairs = bundled.items.filter { it.exclusiveWith != null }.associate { it.key to it.exclusiveWith }
        assertTrue("清单里应有二选一项", pairs.isNotEmpty())
        for ((a, b) in pairs) assertEquals("$a ↔ $b 应双向", a, pairs[b])
    }

    @Test(expected = IllegalArgumentException::class)
    fun enabledWhenMustReferToAToggle() {
        SettingsManifest.parse(
            """
            [[page]]
            id = "a"
            [[item]]
            page = "a"
            key = "input.x"
            source = "engine"
            type = "select"
            options = [{ value = "1" }]
            enabled_when = "input.no_such"
            """.trimIndent(),
        )
    }

    /** 每个页面都有图标，且名字在 SettingsIcons 里登记过（写错就是首页一行落回通用图标） */
    @Test
    fun everyPageIconResolves() {
        val m = bundled
        for (p in m.pages) {
            assertTrue("${p.id} 缺 icon", p.icon != null)
            assertTrue("${p.id} 的 icon「${p.icon}」没登记", p.icon in SettingsIcons.PAGE_ICON_NAMES)
        }
    }

    /** 实体键盘专属项靠「显示实体键盘设置」放出来：开关本身得在清单里、是键盘项、出厂关，且开关自己不能被藏起来 */
    @Test
    fun hardwareItemsAreGatedByAnAndroidToggle() {
        val m = bundled
        val hw = m.items.filter { it.hardware }
        assertTrue("至少有临时英文 / 临时拼音的触发键", hw.map { it.key }.containsAll(
            listOf("input.temp_english.trigger_keys", "input.temp_pinyin.trigger_keys"),
        ))
        val gate = m.items.firstOrNull { it.key == SettingsValues.HARDWARE_GATE }
        assertTrue("清单里缺开关 ${SettingsValues.HARDWARE_GATE}", gate != null)
        assertEquals(SettingSource.ANDROID, gate!!.source)
        assertEquals("toggle", gate.type)
        assertEquals(false, gate.defaultValue)
        assertTrue("开关自己不能是实体键盘项", !gate.hardware)
    }

    /** 分二级页的页：每组都有专属图标，且至少一项（空组会是一个点进去什么都没有的入口） */
    @Test
    fun subpageSectionsHaveIconsAndItems() {
        val m = bundled
        val subPages = m.pages.filter { it.subpages }
        assertTrue("至少方案、输入两页分二级", subPages.map { it.id }.containsAll(listOf("schema", "input")))
        for (p in subPages) {
            for ((name, items) in m.sectionsOf(p.id)) {
                assertTrue("${p.id} 有无名 section", name.isNotEmpty())
                assertTrue("${p.id}/$name 没有专属图标", name in SettingsIcons.SECTION_ICON_NAMES)
                assertTrue("${p.id}/$name 是空组", items.isNotEmpty())
                assertTrue("${p.id}/$name 与代码画的二级页撞名，会被遮住", name !in RESERVED_SUBPAGES)
            }
        }
    }

    /** 模糊音页：总开关一行，拼音对分声母 / 韵母两张网格卡；总开关关着全收起 */
    @Test
    fun fuzzyPageSplitsIntoCards() {
        val items = bundled.sectionsOf("schema").first { it.first == "模糊音" }.second
        assertEquals(listOf(null, "声母", "韵母"), items.map { it.card }.distinct())
        assertEquals("schema.pinyin.fuzzy.enabled", items.first().key)
        assertEquals(13, items.drop(1).size)
        assertTrue("拼音对都画成网格", items.drop(1).all { it.grid })
        assertTrue(
            "总开关关着时拼音对都收起",
            items.drop(1).all { it.enabledWhen == "schema.pinyin.fuzzy.enabled" },
        )
    }

    /**
     * 纠错与提示页：纠错总开关一行，规则随它收起；提示来源与带声调还管注释变量 `${correct_pinyin}`，
     * 不随「显示正确拼音」收起，只有提示范围跟它走
     */
    @Test
    fun correctionPageSplitsIntoCards() {
        val items = bundled.sectionsOf("input").first { it.first == "纠错与提示" }.second
        assertEquals(listOf(null, "纠错规则", "正确拼音提示"), items.map { it.card }.distinct())
        assertEquals("input.correction.enabled", items.first().key)
        val rules = items.filter { it.card == "纠错规则" }
        assertEquals(8, rules.size)
        assertTrue("规则随纠错总开关收起", rules.all { it.enabledWhen == "input.correction.enabled" })
        val hints = items.filter { it.card == "正确拼音提示" }.associateBy { it.key }
        assertEquals("input.correction.hint.enabled", hints.getValue("input.correction.hint.scope").enabledWhen)
        val free = hints.keys - "input.correction.hint.scope"
        assertTrue("其余提示项不收起", free.all { hints.getValue(it).enabledWhen == null })
    }

    @Test(expected = IllegalArgumentException::class)
    fun gridOnlyOnToggles() {
        SettingsManifest.parse(
            """
            [[page]]
            id = "a"
            [[item]]
            page = "a"
            key = "input.x"
            source = "engine"
            type = "select"
            grid = true
            options = [{ value = "1" }]
            """.trimIndent(),
        )
    }
}
