package com.windinput.ime.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

data class QuickPhrase(val id: String, val text: String)

/** Persistent user-selected phrases, separate from clipboard history and engine command phrases. */
internal object QuickPhrases {
    const val MAX_COUNT = 100
    const val MAX_LENGTH = 2_000
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    val defaults = listOf(
        QuickPhrase("thanks", "好的谢谢。"),
        QuickPhrase("call-later", "抱歉，我现在不方便接电话，稍后联系。"),
        QuickPhrase("meeting", "我正在开会，有急事请发短信。"),
        QuickPhrase("on-my-way", "我很快就到，请稍微等一会儿。"),
    )

    fun upsert(entries: List<QuickPhrase>, id: String?, text: String): List<QuickPhrase> {
        val value = text.trim()
        require(value.isNotEmpty()) { "请填写常用语内容" }
        require(value.length <= MAX_LENGTH) { "每条常用语最多 $MAX_LENGTH 字" }
        require(entries.none { it.id != id && it.text == value }) { "这条常用语已经存在" }
        if (id == null) {
            require(entries.size < MAX_COUNT) { "最多保存 $MAX_COUNT 条常用语，请先删除不需要的内容" }
            return listOf(QuickPhrase(UUID.randomUUID().toString(), value)) + entries
        }
        require(entries.any { it.id == id }) { "这条常用语已被删除，请重新添加" }
        return entries.map { if (it.id == id) it.copy(text = value) else it }
    }

    fun read(file: File): List<QuickPhrase> {
        if (!file.exists()) return defaults
        require(file.length() <= MAX_FILE_BYTES) { "常用语文件过大" }
        val array = JSONArray(file.readText())
        require(array.length() <= MAX_COUNT) { "常用语条数超过限制" }
        val entries = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            QuickPhrase(item.getString("id"), item.getString("text"))
        }
        validate(entries)
        return entries
    }

    fun write(file: File, entries: List<QuickPhrase>) {
        validate(entries)
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text)) }
        val bytes = array.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "常用语文件过大" }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            if (!temporary.renameTo(file)) throw IOException("Cannot replace phrase file")
        } finally {
            temporary.delete()
        }
    }

    private fun validate(entries: List<QuickPhrase>) {
        require(entries.size <= MAX_COUNT)
        require(entries.map { it.id }.distinct().size == entries.size)
        require(entries.all {
            it.id.matches(Regex("[A-Za-z0-9_-]{1,64}")) && it.text.isNotBlank() && it.text.length <= MAX_LENGTH
        })
    }
}
