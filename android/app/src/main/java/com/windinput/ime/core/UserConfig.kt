package com.windinput.ime.core

import android.util.Log
import org.tomlj.Toml
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * TOML 配置的最小读写与进程内缓存（点分键 → TOML 节）。
 * 手机键盘偏好使用独立的 mobile/keyboard.toml；本类也可读取其他配置文件的标量键。
 * Rust 核心的配置合并与校验仍由 wind-config 负责。
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
        private val cache = ConcurrentHashMap<String, Snapshot>()
        // 同一文件的多实例仍串行读改写；后台解析引擎配置不能挡住主线程读取键盘配置。
        private val fileLocks = ConcurrentHashMap<String, Any>()
    }

    // 只做路径归一化，不用会读盘的 canonicalPath；相对路径/./ 的多实例也共享一份状态。
    private val fileKey = file.absoluteFile.toPath().normalize().toString()
    private val fileLock = fileLocks.computeIfAbsent(fileKey) { Any() }

    private class Snapshot(val modified: Long, val length: Long, val values: Map<String, Any>)

    /** 一次应用设置所用的只读快照；各 getter 不再检查文件，也不会随之后的写入改变。 */
    class Values internal constructor(private val values: Map<String, Any>) {
        fun getBoolean(key: String, default: Boolean): Boolean =
            values[key] as? Boolean ?: default

        fun getInt(key: String, default: Int): Int =
            (values[key] as? Long)?.toInt() ?: default

        fun getString(key: String, default: String): String =
            values[key] as? String ?: default
    }

    /** 每次取新快照仍检查外部修改；同一次弹键盘只需取一次，避免逐项重复 stat。 */
    fun snapshot(): Values = Values(readAll())

    /** 点分键 → 值（String/Boolean/Long/Double），读取失败返回空表 */
    fun readAll(): Map<String, Any> = synchronized(fileLock) {
        if (!file.isFile) return emptyMap()
        val modified = file.lastModified()
        val length = file.length()
        cache[fileKey]?.takeIf { it.modified == modified && it.length == length }?.let { return it.values }
        parse().also { cache[fileKey] = Snapshot(modified, length, it) }
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
    fun put(key: String, value: Any) = synchronized(fileLock) {
        val all = readAll().toMutableMap()
        // 与 TOML 解析出的类型对齐（整数解析出来是 Long）：缓存里放的就是下次读到的样子
        all[key] = if (value is Int) value.toLong() else value
        write(all)
        // 直接换上刚写的表：修改时间精度可能只到秒，同一秒内同长度的两次写（30 → 31）
        // 只凭时间 + 大小分不出来
        cache[fileKey] = Snapshot(file.lastModified(), file.length(), all)
    }

    /**
     * 删掉满足 [predicate] 的键（「恢复键盘设置」用），返回删了几个；一个都没删就不动文件。
     */
    fun removeIf(predicate: (String) -> Boolean): Int = synchronized(fileLock) {
        val all = readAll()
        val kept = all.filterKeys { !predicate(it) }
        val removed = all.size - kept.size
        if (removed > 0) {
            write(kept)
            cache[fileKey] = Snapshot(file.lastModified(), file.length(), kept)
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
