package com.windinput.ime.settings

import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.ConvertFile
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Help
import top.yukonga.miuix.kmp.icon.extended.HorizontalSplit
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Merge
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Months
import top.yukonga.miuix.kmp.icon.extended.Notes
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Paste
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Replace
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.Stopwatch
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Translate
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Unlock

/**
 * 设置页图标：清单里写名字（页面的 `icon`），二级入口按 section 名取。
 *
 * 只用 miuix-icons 自带的那一套：线条粗细、圆角与 miuix 组件同源，混进别家图标一眼就花；
 * 也不为几十个图标拉 material-icons-extended 这种大依赖。
 */
object SettingsIcons {

    /** 清单 `icon` 可写的名字（与 [byName] 同步；单测用它核对清单，免得在 JVM 上构造矢量图） */
    val PAGE_ICON_NAMES = setOf("Layers", "Tune", "Theme", "Months", "Notes", "GridView", "Tasks", "Paste", "Settings", "Info")

    /** 有专属图标的二级入口名（与 [bySection] 同步） */
    val SECTION_ICON_NAMES = setOf(
        "码表", "拼音", "模糊音", "双拼", "混输", "英文",
        "默认状态", "字符与标点", "标点配对", "快捷输入", "临时拼音", "临时英文", "智能符号", "特殊输入", "短语匹配", "联想输入", "简繁转换", "Emoji/OpenCC", "纠错与提示",
    )

    /** 清单 `icon` 字段可写的名字 */
    val byName: Map<String, ImageVector> by lazy {
        mapOf(
            "Layers" to MiuixIcons.Layers,
            "Tune" to MiuixIcons.Tune,
            "Theme" to MiuixIcons.Theme,
            "Months" to MiuixIcons.Months,
            "Notes" to MiuixIcons.Notes,
            "GridView" to MiuixIcons.GridView,
            "Tasks" to MiuixIcons.Tasks,
            "Paste" to MiuixIcons.Paste,
            "Settings" to MiuixIcons.Settings,
            "Info" to MiuixIcons.Info,
        )
    }

    /** 二级入口（subpages 页里的 section）的图标；没登记的落回通用图标 */
    val bySection: Map<String, ImageVector> by lazy {
        mapOf(
            "码表" to MiuixIcons.ListView,
            "拼音" to MiuixIcons.Messages,
            "模糊音" to MiuixIcons.Filter,
            "双拼" to MiuixIcons.HorizontalSplit,
            "混输" to MiuixIcons.Merge,
            "英文" to MiuixIcons.Translate,
            "默认状态" to MiuixIcons.Refresh,
            "字符与标点" to MiuixIcons.Edit,
            "标点配对" to MiuixIcons.Copy,
            "快捷输入" to MiuixIcons.Stopwatch,
            "临时拼音" to MiuixIcons.Edit,
            "临时英文" to MiuixIcons.Translate,
            "智能符号" to MiuixIcons.Refresh,
            "特殊输入" to MiuixIcons.Link,
            "短语匹配" to MiuixIcons.Copy,
            "联想输入" to MiuixIcons.Link,
            "简繁转换" to MiuixIcons.ConvertFile,
            "Emoji/OpenCC" to MiuixIcons.Favorites,
            "纠错与提示" to MiuixIcons.Help,
        )
    }

    // 首页「开始使用」与词库页用的固定图标
    val enable: ImageVector get() = MiuixIcons.Unlock
    val enabled: ImageVector get() = MiuixIcons.Ok
    val switchIme: ImageVector get() = MiuixIcons.Replace
    val tryIt: ImageVector get() = MiuixIcons.Edit
    val words: ImageVector get() = MiuixIcons.Notes
    val addWord: ImageVector get() = MiuixIcons.Add
    val phrases: ImageVector get() = MiuixIcons.Messages
    val dictStats: ImageVector get() = MiuixIcons.Months
    val clear: ImageVector get() = MiuixIcons.Delete
    val backup: ImageVector get() = MiuixIcons.Backup
    val import: ImageVector get() = MiuixIcons.Import
    val licenses: ImageVector get() = MiuixIcons.Share

    // 「关于」与「高级」里代码画的行
    val privacy: ImageVector get() = MiuixIcons.Lock
    val website: ImageVector get() = MiuixIcons.Link
    val feedback: ImageVector get() = MiuixIcons.Report
    val diagnostics: ImageVector get() = MiuixIcons.Send
    val reset: ImageVector get() = MiuixIcons.Reset
    val fallback: ImageVector get() = MiuixIcons.Settings

    /** 「启用的方案」每行进方案详情页的按钮 */
    val schemaSettings: ImageVector get() = MiuixIcons.Settings

    fun page(name: String?): ImageVector = name?.let(byName::get) ?: fallback

    fun section(name: String): ImageVector = bySection[name] ?: fallback
}
