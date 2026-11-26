package com.windinput.ime.settings

import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable

/**
 * 引擎配置快照（核心 `config_snapshot` 给的 TOML 全文）摊平成「点分键 → 值」。
 *
 * 设置页按注册表里的点分键（`input.association.max_count`）取值，不关心 TOML 的分段写法。
 * 值的类型：Boolean / Long / Double / String / List<Any?>（数组）；内联表、表数组继续往下摊。
 * 纯函数、不依赖 Context，可在 JVM 单测里直接跑。
 */
object ConfigSnapshot {

    fun flatten(toml: String): Map<String, Any> {
        val result = Toml.parse(toml)
        if (result.hasErrors()) {
            throw IllegalArgumentException(
                "配置快照解析失败: " + result.errors().joinToString { it.toString() },
            )
        }
        return buildMap { walk(result, "", this) }
    }

    private fun walk(table: TomlTable, prefix: String, out: MutableMap<String, Any>) {
        for (key in table.keySet()) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            when (val v = table.get(listOf(key))) {
                is TomlTable -> walk(v, path, out)
                is TomlArray -> out[path] = v.toList()
                null -> Unit
                else -> out[path] = v
            }
        }
    }
}
