package com.windinput.ime.panels

import android.util.Log
import com.windinput.ime.core.DataDirs
import org.tomlj.Toml

/** 分类符号页的一个分类（底部 tab 一格） */
data class SymbolCategory(val name: String, val items: List<String>)

/**
 * 分类符号数据：`data/symbol_pages.toml`，格式见该文件头部注释。
 * 解析是纯函数，不依赖 Context，可在 JVM 单测里直接跑。
 */
object SymbolPages {

    private const val TAG = "SymbolPages"
    const val FILE = "symbol_pages.toml"

    fun parse(toml: String): List<SymbolCategory> {
        val result = Toml.parse(toml)
        if (result.hasErrors()) {
            throw IllegalArgumentException(
                "TOML errors: " + result.errors().joinToString { it.toString() },
            )
        }
        val array = result.getArray("category") ?: return emptyList()
        return (0 until array.size()).map { i ->
            val t = array.getTable(i)
            val name = t.getString("name")
                ?: throw IllegalArgumentException("category[$i] 缺少 name")
            val items = t.getString("symbols").orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }
            SymbolCategory(name, items)
        }
    }

    /** 上次的解析结果：文件 + 修改时间 + 长度对得上才用 */
    private var cached: Triple<String, Long, Long>? = null
    private var cachedPages: List<SymbolCategory> = emptyList()

    /**
     * 用户层 > 出厂层；都读不出来时返回空表（页面只剩「最近」，不至于崩）。
     * 结果按文件缓存：每次弹键盘都会新建符号页，此前头一回点「符号」就在主线程上读盘 + 解析
     * （StrictMode 记到过百毫秒的消息）；服务启动时在后台先 [load] 一次
     */
    @Synchronized
    fun load(dirs: DataDirs): List<SymbolCategory> {
        val file = dirs.resolveDataFile(FILE) ?: return emptyList()
        val key = Triple(file.path, file.lastModified(), file.length())
        if (key == cached) return cachedPages
        return try {
            parse(file.readText()).also { cached = key; cachedPages = it }
        } catch (e: Exception) {
            Log.w(TAG, "$FILE parse failed", e)
            emptyList()
        }
    }
}
