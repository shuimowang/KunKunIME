package com.windinput.ime.core

import android.content.Context
import java.io.File

/**
 * KunKunIME 0.132 共享核心的私有数据目录。
 *
 * | Android                                   | 桌面对应                          | 内容 |
 * |-------------------------------------------|-----------------------------------|------|
 * | filesDir/data                             | 安装目录 data/（出厂层，L2）       | 出厂配置/布局/主题/方案，App 升级整体重放 |
 * | filesDir/rust（[userRoot]）               | 核心的数据根                       | 用户词库、统计与核心派生目录，升级保留 |
 * | [userRoot]/config/KunKunIME               | %APPDATA%/KunKunIME（用户层，L3）   | config.toml、schemas/、schema_overrides/ |
 * | filesDir/mobile（[mobileDir]）             | —（移动端独有）                    | keyboard.toml、布局、图标与 keymap.toml |
 * | filesDir/rust/data/KunKunIME（[localRoot]）| %LOCALAPPDATA%/KunKunIME           | 核心的词库编译缓存与日志（可再生，不进用户视野） |
 * | cacheDir/dict                             | —                                 | 词库 wdat 编译缓存（可再生） |
 *
 * 0.132 的 wind-mobile::mount_dirs 从一个根派生 XDG_CONFIG_HOME / XDG_DATA_HOME。
 * 最新上游 Android 壳的“用户根就是配置目录”契约不适用于此版本；这里集中映射真实路径，
 * 让设置重置、日志级别与核心落盘指向同一个文件。此独立应用没有上游版本的迁移历史。
 *
 * 覆盖解析与桌面 resolve_data_file 同语义：用户层同名文件整体替代 data 下那份，
 * 失败静默回退。
 */
class DataDirs internal constructor(filesDir: File, cacheDir: File) {

    constructor(context: Context) : this(context.filesDir, context.cacheDir)

    /** 出厂数据层（assets 拷出，App 升级时整体重放） */
    val dataDir: File = File(filesDir, "data")

    /** 传给 0.132 MobileCore 的数据根；与配置目录并非同一个目录。 */
    val userRoot: File = File(filesDir, "rust")

    /** 核心由 XDG_CONFIG_HOME 派生出的真实用户配置目录。 */
    val engineConfigDir: File = File(userRoot, "config/KunKunIME")

    /** 移动端独有的用户层目录：keyboard.toml、layouts/、icons/（引擎不读） */
    val mobileDir: File = File(filesDir, "mobile")

    /** 用户键位覆盖（`keymap.toml`，只存差异，见 docs/key-mapping-skin-plan.md）；移动端独有文件名 */
    val keymapFile: File = File(mobileDir, "keymap.toml")

    /** 键盘自己的配置（`ui.android.*`）；叫 keyboard.toml 而不是 config.toml，是为了不与 PC / 引擎的 config.toml 同名 */
    val keyboardConfigFile: File = File(mobileDir, "keyboard.toml")

    /** 核心的本地目录：词库编译缓存与日志（私有、可再生） */
    val localRoot: File = File(userRoot, "data/KunKunIME")

    /** 核心用户层的 config.toml（设置页的引擎项只能经核心读写，这里仅供重置与日志级别读盘） */
    val engineConfigFile: File = File(engineConfigDir, "config.toml")

    /** 词库编译缓存（可再生，允许系统清理） */
    val dictCacheDir: File = File(cacheDir, "dict")

    fun ensureCreated() {
        dataDir.mkdirs()
        engineConfigDir.mkdirs()
        mobileDir.mkdirs()
        localRoot.mkdirs()
        dictCacheDir.mkdirs()
    }

    /**
     * 覆盖解析：user 优先、data 兜底；都不存在返回 null（调用方自行回退内置）。
     * @param relPath 相对路径，如 "layouts/qwerty.toml"
     */
    fun resolveDataFile(relPath: String): File? {
        val user = File(mobileDir, relPath)
        if (user.isFile) return user
        val factory = File(dataDir, relPath)
        if (factory.isFile) return factory
        return null
    }
}
