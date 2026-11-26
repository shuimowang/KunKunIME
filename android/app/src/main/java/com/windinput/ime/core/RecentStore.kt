package com.windinput.ime.core

import android.content.Context

/**
 * 最近使用（LRU），SharedPreferences 持久化，跨重启保留。表情面板、分类符号页各用一份。
 * 对齐 fcitx5 RecentlyUsed 语义：最近使用分类自动置首。
 *
 * @param prefsName 各自的存储名；表情沿用 `recent_emoji`，升级后不丢已有记录
 */
class RecentStore(context: Context, prefsName: String, private val capacity: Int = 40) {

    private companion object {
        const val KEY = "items"
        const val SEPARATOR = "\u0001"
    }

    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun list(): List<String> =
        prefs.getString(KEY, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() } ?: emptyList()

    /** 无痕时暂停记录：「最近使用」也是一种输入历史，界面上亮着「🕶 无痕」就不该留下 */
    @Volatile
    var paused = false

    fun record(item: String) {
        if (paused) return
        val items = list().toMutableList()
        items.remove(item)
        items.add(0, item)
        while (items.size > capacity) items.removeAt(items.size - 1)
        prefs.edit().putString(KEY, items.joinToString(SEPARATOR)).apply()
    }
}
