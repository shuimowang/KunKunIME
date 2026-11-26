package com.windinput.ime.settings

import android.util.Log
import android.content.Context
import org.tomlj.Toml

/** 一级页面（首页的一行）。格式见 assets/data/settings_manifest.toml 头部注释 */
data class SettingPage(
    val id: String,
    val title: String,
    /** 首页分段：shared（输入引擎）/ android（手机键盘）/ tail（其他） */
    val segment: String,
    /** 首页图标名，见 [SettingsIcons] */
    val icon: String? = null,
    /** true = 页内每个 section 是一个二级入口（项多的页：方案、输入），点进去才是那组设置 */
    val subpages: Boolean = false,
)

/** 设置项写到哪里 */
enum class SettingSource {
    /** 引擎配置：经核心 `config_set` 写进核心用户层 */
    ENGINE,

    /** 键盘自己的配置：K 侧 `<用户根>/mobile/keyboard.toml`，引擎不读 */
    ANDROID,
}

data class SettingItem(
    val page: String,
    /** 卡片名 */
    val section: String,
    val key: String,
    val source: SettingSource,
    /** toggle / select / int */
    val type: String,
    val title: String,
    val summary: String?,
    /** 仅 android 项；引擎项的值来自核心 */
    val defaultValue: Any?,
    val min: Int? = null,
    val max: Int? = null,
    val step: Int = 1,
    val unit: String? = null,
    /** select 的选项：(value, label)，value 一律是字符串，写入时按键的类型转换 */
    val options: List<Pair<String, String>> = emptyList(),
    /** 收进卡片底部的「进阶」折叠区 */
    val advanced: Boolean = false,
    /** int 项拖动时的预览：keyboard_rows（按所选行高画四行键）/ candidate_text（按所选字号写一行候选） */
    val preview: String? = null,
    /** 二选一的另一个开关键：本项打开时把它关掉（照电脑版清单的 exclusive_with） */
    val exclusiveWith: String? = null,
    /** 另一个开关键：它开着时本项才显示（照电脑版清单的 enabled_when，这里只写键） */
    val enabledWhen: String? = null,
    /** 二级页里的子卡片名：同 section 内按它再分卡，null = 无标题的头一张 */
    val card: String? = null,
    /** 本项所在卡画成两列勾选网格（只用于短标题的开关） */
    val grid: Boolean = false,
    /** 只对实体键盘有意义（触发键、快捷键）：「显示实体键盘设置」打开才显示，免得手机用户翻到一堆用不上的项 */
    val hardware: Boolean = false,
)

data class SettingsManifestData(
    val pages: List<SettingPage>,
    val items: List<SettingItem>,
) {
    fun itemsOf(page: String): List<SettingItem> = items.filter { it.page == page }

    /** 某页的卡片，按清单里首次出现的顺序 */
    fun sectionsOf(page: String): List<Pair<String, List<SettingItem>>> =
        itemsOf(page).groupBy { it.section }.toList()
}

object SettingsManifest {

    private const val TAG = "SettingsManifest"

    /** 设置页认识的预览种类 */
    val PREVIEWS = setOf("keyboard_rows", "candidate_text")

    /**
     * 直接从 APK 的 assets 读清单，**不走**数据目录：数据目录里的出厂数据要等核心启动时
     * 才按版本重拷，设置页比它早——升级后第一次打开读到的是上一版的清单（格式一变就整页空白）。
     * 清单是 App 自己的界面描述，不需要用户覆盖层。
     */
    fun load(context: Context): SettingsManifestData {
        return try {
            parse(context.assets.open("data/settings_manifest.toml").bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w(TAG, "settings manifest parse failed", e)
            SettingsManifestData(emptyList(), emptyList())
        }
    }

    /** 纯函数解析，可在 JVM 单测里直接跑。清单写错（缺字段、页面不存在）直接抛错 */
    fun parse(toml: String): SettingsManifestData {
        val result = Toml.parse(toml)
        if (result.hasErrors()) {
            throw IllegalArgumentException("TOML errors: ${result.errors()}")
        }
        val pageArr = result.getArray("page")
        val pages = (0 until (pageArr?.size() ?: 0)).map { i ->
            val t = pageArr!!.getTable(i)
            SettingPage(
                id = requireNotNull(t.getString("id")) { "page 缺少 id" },
                title = t.getString("title") ?: "",
                segment = t.getString("segment") ?: "shared",
                icon = t.getString("icon"),
                subpages = t.getBoolean("subpages") ?: false,
            )
        }
        val pageIds = pages.map { it.id }.toSet()
        val itemArr = result.getArray("item")
        val items = (0 until (itemArr?.size() ?: 0)).map { i ->
            val t = itemArr!!.getTable(i)
            val key = requireNotNull(t.getString("key")) { "item 缺少 key" }
            val page = requireNotNull(t.getString("page")) { "$key 缺少 page" }
            require(page in pageIds) { "$key 的 page「$page」不存在" }
            val source = when (val s = t.getString("source")) {
                "engine" -> SettingSource.ENGINE
                "android" -> SettingSource.ANDROID
                else -> throw IllegalArgumentException("$key 的 source「$s」不认识")
            }
            SettingItem(
                page = page,
                section = t.getString("section") ?: "",
                key = key,
                source = source,
                type = t.getString("type") ?: "toggle",
                title = t.getString("title") ?: "",
                summary = t.getString("summary"),
                defaultValue = t.get("default"),
                min = t.getLong("min")?.toInt(),
                max = t.getLong("max")?.toInt(),
                step = t.getLong("step")?.toInt() ?: 1,
                unit = t.getString("unit"),
                options = t.getArray("options")?.let { arr ->
                    (0 until arr.size()).map { o ->
                        val opt = arr.getTable(o)
                        val value = requireNotNull(opt.getString("value")) { "$key 的选项缺少 value" }
                        value to (opt.getString("label") ?: value)
                    }
                } ?: emptyList(),
                advanced = t.getBoolean("advanced") ?: false,
                preview = t.getString("preview")?.also {
                    require(it in PREVIEWS) { "$key 的 preview「$it」不认识" }
                },
                exclusiveWith = t.getString("exclusive_with"),
                enabledWhen = t.getString("enabled_when"),
                card = t.getString("card"),
                grid = t.getBoolean("grid") ?: false,
                hardware = t.getBoolean("hardware") ?: false,
            )
        }
        // 引用的键必须是清单里的开关：写错一个字，二选一就变成能同时开、依赖项就永远不显示
        val toggles = items.filter { it.type == "toggle" }.associateBy { it.key }
        for (item in items) {
            for (ref in listOfNotNull(item.exclusiveWith, item.enabledWhen)) {
                require(ref in toggles) { "${item.key} 引用的「$ref」不是清单里的开关" }
            }
            require(!item.grid || item.type == "toggle") { "${item.key}：grid 只能用在开关上" }
            // 二选一只在引擎项上实现（先关对方、一次重载），写在键盘项上会被静默忽略
            item.exclusiveWith?.let { other ->
                require(item.source == SettingSource.ENGINE && toggles.getValue(other).source == SettingSource.ENGINE) {
                    "${item.key} ↔ $other：exclusive_with 两端都必须是引擎开关"
                }
            }
        }
        return SettingsManifestData(pages, items)
    }
}
