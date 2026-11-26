package com.windinput.ime.core

import android.util.Log
import org.tomlj.Toml
import java.io.File

/**
 * 用户层配置（<用户根>/keyboard/config.toml）的最小读写。
 *
 * ⚠️ 过渡实现：正式配置链路由 Rust wind-config 负责（三层合并 + REGISTRY 校验），
 * 本类只服务于 Rust 核心接入前的 Kotlin 侧设置项（点分键 → TOML 节）。
 * 文件格式与桌面用户层 config.toml 一致，接入后可被 wind-config 直接读取。
 */
class UserConfig(private val file: File) {

    private companion object {
        const val TAG = "UserConfig"

        /**
         * 解析结果按文件路径缓存，进程内共享（输入法服务与设置页各有一个实例，同一进程）。
         *
         * 此前每取一个值都要读盘 + 整份解析：弹一次键盘 onStartInputView 取二十来项，就在主线程
         * 上解析二十来遍；设置页键盘侧的每一项、每次重组也各解析一遍。
         *
         * 失效：本进程写入（[put]）直接换上新表；进程外改了文件（测试脚本 run-as 直接写）按
         * 修改时间 + 大小发现。
         */
        private val cache = HashMap<String, Snapshot>()
    }

    private class Snapshot(val modified: Long, val length: Long, val values: Map<String, Any>)

    /** 点分键 → 值（String/Boolean/Long/Double），读取失败返回空表 */
    fun readAll(): Map<String, Any> = synchronized(cache) {
        if (!file.isFile) return emptyMap()
        val modified = file.lastModified()
        val length = file.length()
        cache[file.path]?.takeIf { it.modified == modified && it.length == length }?.let { return it.values }
        parse().also { cache[file.path] = Snapshot(modified, length, it) }
    }

    private fun parse(): Map<String, Any> = try {
        val result = Toml.parse(file.readText())
        if (result.hasErrors()) {
            Log.w(TAG, "config.toml has errors: ${result.errors()}")
        }
        result.dottedKeySet().associateWith { key -> result.get(key)!! }
    } catch (e: Exception) {
        Log.w(TAG, "read config.toml failed", e)
        emptyMap()
    }

    fun getBoolean(key: String, default: Boolean): Boolean =
        readAll()[key] as? Boolean ?: default

    fun getInt(key: String, default: Int): Int =
        (readAll()[key] as? Long)?.toInt() ?: default

    fun getString(key: String, default: String): String =
        readAll()[key] as? String ?: default

    /**
     * 写回一个键值（读全量 → 改内存 → 重新序列化整文件）。
     * 值仅支持标量（Boolean/Long/Double/String）。
     */
    fun put(key: String, value: Any) = synchronized(cache) {
        val all = readAll().toMutableMap()
        // 与 TOML 解析出的类型对齐（整数解析出来是 Long）：缓存里放的就是下次读到的样子
        all[key] = if (value is Int) value.toLong() else value
        write(all)
        // 直接换上刚写的表：修改时间精度可能只到秒，同一秒内同长度的两次写（30 → 31）
        // 只凭时间 + 大小分不出来
        cache[file.path] = Snapshot(file.lastModified(), file.length(), all)
    }

    /**
     * 删掉满足 [predicate] 的键（「恢复键盘设置」用），返回删了几个；一个都没删就不动文件。
     */
    fun removeIf(predicate: (String) -> Boolean): Int = synchronized(cache) {
        val all = readAll()
        val kept = all.filterKeys { !predicate(it) }
        val removed = all.size - kept.size
        if (removed > 0) {
            write(kept)
            cache[file.path] = Snapshot(file.lastModified(), file.length(), kept)
        }
        removed
    }

    private fun write(values: Map<String, Any>) {
        // 按节分组：a.b.c=v → [a.b] 下的 c = v；无点键归根节
        val sections = sortedMapOf<String, MutableList<Pair<String, Any>>>()
        for ((key, value) in values) {
            val lastDot = key.lastIndexOf('.')
            val section = if (lastDot < 0) "" else key.substring(0, lastDot)
            val name = if (lastDot < 0) key else key.substring(lastDot + 1)
            sections.getOrPut(section) { mutableListOf() }.add(name to value)
        }
        val sb = StringBuilder("# WindInput Android 用户层配置（设置页写入）\n")
        for ((section, entries) in sections) {
            if (section.isNotEmpty()) {
                sb.append("\n[").append(section).append("]\n")
            }
            for ((name, value) in entries.sortedBy { it.first }) {
                sb.append(name).append(" = ").append(serialize(value)).append('\n')
            }
        }
        file.parentFile?.mkdirs()
        file.writeText(sb.toString())
    }

    private fun serialize(value: Any): String = when (value) {
        is Boolean, is Long, is Int, is Double -> value.toString()
        // 基本字符串里不能有裸换行（数字键盘左列符号表按换行分隔），转成 \n
        else -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
    }
}
