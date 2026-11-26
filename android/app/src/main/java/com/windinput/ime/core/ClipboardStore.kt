package com.windinput.ime.core

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 剪贴板历史存储：监听系统剪贴板，JSON 持久化（设备本地，不进 user 同步树）。
 *
 * 能力对齐 fcitx5 ClipboardManager 的核心子集：置顶、删除、清空(跳过置顶)、
 * 条数上限、敏感内容跳过（[ClipDescription.EXTRA_IS_SENSITIVE] 标记）。
 * 过渡实现：数据量小（上限 50 条），全量读写 JSON 文件；后续量大再换 SQLite。
 *
 * 读写盘都在单独的 IO 线程：单条最长 10 万字、50 条就是几 MB，此前在输入法服务 onCreate
 * 和每次复制时都在主线程上整份读写（StrictMode 记到）。列表本身只在主线程上改。
 */
class ClipboardStore(context: Context) : ClipboardManager.OnPrimaryClipChangedListener {

    data class Entry(val text: String, val time: Long, val pinned: Boolean)

    private companion object {
        const val TAG = "ClipboardStore"
        const val MAX_UNPINNED = 50
        const val MAX_TEXT_LEN = 100_000
    }

    private val file = File(context.filesDir, "clipboard.json")
    private val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val entries = mutableListOf<Entry>()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "clipboard-io") }
    private val main = Handler(Looper.getMainLooper())

    /** 盘上的历史读进来之前不写盘：那时写下去的只有新复制的几条，会盖掉旧历史 */
    private var loaded = false
    private var dirtyBeforeLoad = false

    /** 历史变化通知（面板刷新用） */
    var onChanged: (() -> Unit)? = null

    /** 新复制内容通知（候选条"粘贴建议"用，与 onChanged 独立） */
    var onNewClip: ((String) -> Unit)? = null

    init {
        io.execute {
            val fromDisk = readFile()
            main.post { merge(fromDisk) }
        }
    }

    fun start() = clipboardManager.addPrimaryClipChangedListener(this)

    /** 服务销毁时调：排队中的写盘做完再退出 IO 线程 */
    fun stop() {
        clipboardManager.removePrimaryClipChangedListener(this)
        io.shutdown()
    }

    /** 最新在前，置顶优先 */
    fun list(): List<Entry> =
        entries.sortedWith(compareByDescending<Entry> { it.pinned }.thenByDescending { it.time })

    override fun onPrimaryClipChanged() {
        val clip = clipboardManager.primaryClip ?: return
        if (clip.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) {
            return // 敏感内容（密码管理器等）不入历史
        }
        val text = clip.getItemAt(0)?.coerceToText(null)?.toString() ?: return
        if (text.isBlank() || text.length > MAX_TEXT_LEN) return
        add(text)
        onNewClip?.invoke(text)
    }

    fun add(text: String) {
        // 同文重复：仅刷新时间（保留置顶态）
        val existing = entries.indexOfFirst { it.text == text }
        if (existing >= 0) {
            entries[existing] = entries[existing].copy(time = System.currentTimeMillis())
        } else {
            entries += Entry(text, System.currentTimeMillis(), pinned = false)
            trim()
        }
        persistAndNotify()
    }

    fun setPinned(text: String, pinned: Boolean) {
        val i = entries.indexOfFirst { it.text == text }
        if (i < 0) return
        entries[i] = entries[i].copy(pinned = pinned)
        persistAndNotify()
    }

    fun remove(text: String) {
        if (entries.removeAll { it.text == text }) persistAndNotify()
    }

    /** 清空（保留置顶项） */
    fun clearUnpinned() {
        if (entries.removeAll { !it.pinned }) persistAndNotify()
    }

    private fun trim() {
        val unpinned = entries.filter { !it.pinned }.sortedByDescending { it.time }
        if (unpinned.size <= MAX_UNPINNED) return
        val evict = unpinned.drop(MAX_UNPINNED).toSet()
        entries.removeAll { it in evict }
    }

    private fun persistAndNotify() {
        if (loaded) {
            val snapshot = entries.toList()
            if (!io.isShutdown) io.execute { writeFile(snapshot) }
        } else {
            dirtyBeforeLoad = true
        }
        onChanged?.invoke()
    }

    /** 读盘结果并进内存（主线程）：读完之前已复制的条目保留，同文以内存里的为准 */
    private fun merge(fromDisk: List<Entry>) {
        val known = entries.mapTo(HashSet()) { it.text }
        entries += fromDisk.filter { it.text !in known }
        trim()
        loaded = true
        if (dirtyBeforeLoad) persistAndNotify() else onChanged?.invoke()
    }

    private fun writeFile(snapshot: List<Entry>) {
        try {
            val arr = JSONArray()
            for (e in snapshot) {
                arr.put(
                    JSONObject()
                        .put("text", e.text)
                        .put("time", e.time)
                        .put("pinned", e.pinned),
                )
            }
            // 先写临时文件再改名：写到一半被杀不会留下半截 JSON（那样整份历史都读不回来）
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) Log.w(TAG, "replace clipboard.json failed")
        } catch (e: Exception) {
            Log.w(TAG, "persist clipboard failed", e)
        }
    }

    private fun readFile(): List<Entry> {
        if (!file.isFile) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(o.getString("text"), o.getLong("time"), o.getBoolean("pinned"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "load clipboard failed", e)
            emptyList()
        }
    }
}
