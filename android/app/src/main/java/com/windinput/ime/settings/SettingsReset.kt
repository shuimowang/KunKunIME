package com.windinput.ime.settings

import com.windinput.ime.core.UserConfig
import org.tomlj.Toml
import org.tomlj.TomlArray
import java.io.File

/**
 * 「恢复默认设置」的两档（设置页「高级」）。词库、方案覆盖（schema_overrides/）、扩展词库开关
 * （也在方案覆盖里）、导入的方案都不在这两个文件里，天然不动。
 *
 * - 键盘设置：K 侧 `<用户根>/mobile/keyboard.toml` 里的 `ui.android.*`（键盘高度、振动、手势、
 *   工具栏…），下次弹出键盘时 Service 重读。
 * - 输入设置：核心用户层 `<用户根>/config.toml` 里的引擎配置覆盖，
 *   只留 [ENGINE_KEEP]；写完发 ReloadConfig。
 */
object SettingsReset {

    /**
     * 恢复输入设置时留下的引擎键：
     * - `version`：用户文件的迁移状态锚点（出厂 config.toml 头部注释），删了会被当成老文件再迁移一遍
     * - `schema.active` / `schema.available`：当前方案与启用了哪些方案，是「方案」不是「输入设置」
     */
    val ENGINE_KEEP = setOf("version", "schema.active", "schema.available")

    fun isKeyboardKey(key: String): Boolean = key.startsWith("ui.android.")

    fun keepsEngineKey(key: String): Boolean = key in ENGINE_KEEP

    /** 恢复键盘设置，返回清掉了几项 */
    fun resetKeyboard(userConfig: UserConfig): Int = userConfig.removeIf(::isKeyboardKey)

    /**
     * 恢复输入设置：引擎用户层只留 [ENGINE_KEEP]，返回清掉了几项。文件解析不了时不动它
     * （核心自己会备份坏文件），抛异常给调用方提示。生效要另发 ReloadConfig。
     */
    fun resetEngine(file: File): Int {
        if (!file.isFile) return 0
        val parsed = Toml.parse(file.readText())
        require(!parsed.hasErrors()) { "输入设置文件有语法错误，未改动：${parsed.errors().first()}" }
        val keys = parsed.dottedKeySet()
        val kept = keys.filter(::keepsEngineKey).associateWith { k ->
            when (val v = parsed.get(k)) {
                is TomlArray -> v.toList()
                else -> v!!
            }
        }
        if (kept.size == keys.size) return 0
        file.writeText(renderToml(kept))
        return keys.size - kept.size
    }

    /** 点分键 → TOML 文本（根键在前，其余按节分组）。值：字符串 / 整数 / 小数 / 布尔 / 标量列表 */
    fun renderToml(values: Map<String, Any>): String {
        val sections = sortedMapOf<String, MutableList<Pair<String, Any>>>()
        for ((key, value) in values) {
            val dot = key.lastIndexOf('.')
            sections.getOrPut(if (dot < 0) "" else key.substring(0, dot)) { mutableListOf() }
                .add((if (dot < 0) key else key.substring(dot + 1)) to value)
        }
        val sb = StringBuilder()
        for ((section, entries) in sections) {
            if (section.isNotEmpty()) sb.append(if (sb.isEmpty()) "" else "\n").append('[').append(section).append("]\n")
            for ((name, value) in entries.sortedBy { it.first }) {
                sb.append(name).append(" = ").append(literal(value)).append('\n')
            }
        }
        return sb.toString()
    }

    private fun literal(v: Any?): String = when (v) {
        is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        is List<*> -> v.joinToString(", ", "[", "]") { literal(it) }
        is Boolean, is Long, is Int, is Double -> v.toString()
        else -> error("恢复设置不支持的值类型：${v?.javaClass}")
    }
}
