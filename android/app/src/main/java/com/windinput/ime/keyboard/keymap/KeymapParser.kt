package com.windinput.ime.keyboard.keymap

import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable
import java.io.File

/**
 * `<userRoot>/mobile/keymap.toml`（用户键位覆盖，只存差异）的读取器。纯 JVM、不依赖 Context。
 *
 * **阶段 0：只解析成数据结构，没有任何调用方**——不接入 KeyboardView，不改变渲染与手势。
 *
 * 容错原则（与「解析失败回退」同一哲学，但粒度更细）：
 * - 文件不存在 / 空文件：无覆盖；
 * - 缺 `version`：按 1；
 * - `version` 高于 [SUPPORTED_VERSION]：整份忽略并置 [KeymapParseResult.tooNew]（不改文件）；
 * - TOML 语法错误：整份读不出来，无覆盖并给一条警告（无法部分解析）；
 * - 拼错字段 / 未知手势 / 未知 func / 输出写法不对：只跳过那一项，记警告，其余照常生效。
 */
object KeymapParser {
    const val SUPPORTED_VERSION = 1

    /** 层 id 与布局文件名一致 */
    val LAYERS = setOf("letters", "symbols", "number")

    /**
     * 功能动作白名单：不开放任意代码。阶段 0 只是名字表，真正的实现在阶段 1/2 与现有剪贴板、编辑、
     * 快捷面板动作对接；认不出的名字一律跳过。
     */
    val FUNC_WHITELIST = setOf(
        "punct_toggle", "one_hand", "undo", "redo", "symbol_page", "clipboard",
        "select_all", "cut", "copy", "paste", "history", "para_start", "para_end",
    )

    private val OUTPUT_FIELDS = setOf("char", "text", "template", "phrase", "func")

    /** 键位表的小节名；`bottom` 是 `key` 的别名（底行逗号 / 句号 / 空格等也是普通键位 id） */
    private val KEY_SECTIONS = setOf("key", "bottom")

    fun load(file: File): KeymapParseResult {
        if (!file.isFile) return KeymapParseResult.EMPTY
        return try {
            parse(file.readText())
        } catch (e: Exception) {
            KeymapParseResult.EMPTY.copy(warnings = listOf("读取 ${file.name} 失败：$e"))
        }
    }

    fun parse(text: String): KeymapParseResult {
        val warnings = ArrayList<String>()
        val doc = Toml.parse(text)
        if (doc.hasErrors()) {
            warnings += "keymap.toml 语法错误，整份忽略：" + doc.errors().joinToString("；") { it.toString() }
            return KeymapParseResult(1, false, emptyMap(), warnings)
        }

        val version = readVersion(doc, warnings)
        if (version > SUPPORTED_VERSION) {
            return KeymapParseResult(version, true, emptyMap(), listOf("keymap.toml 的 version=$version 高于本版本支持的 $SUPPORTED_VERSION，已忽略"))
        }

        for (key in doc.keySet()) {
            if (key != "version" && key != "layer") warnings += "未知顶层字段 $key，已跳过"
        }

        val layers = LinkedHashMap<String, Map<String, Map<Gesture, GestureOverride>>>()
        val layerTable = doc.getTable("layer")
        if (layerTable != null) {
            for (layerId in layerTable.keySet()) {
                val table = layerTable.getTable(layerId)
                if (layerId !in LAYERS || table == null) {
                    warnings += "未知键盘层 layer.$layerId，已跳过"
                    continue
                }
                parseLayer(layerId, table, warnings).takeIf { it.isNotEmpty() }?.let { layers[layerId] = it }
            }
        }
        return KeymapParseResult(version, false, layers, warnings)
    }

    private fun readVersion(doc: TomlTable, warnings: MutableList<String>): Int {
        if (!doc.contains("version")) return 1
        val v = doc.get("version") as? Long
        if (v == null || v < 1 || v > Int.MAX_VALUE) {
            warnings += "version 不是正整数，按 1 处理"
            return 1
        }
        return v.toInt()
    }

    private fun parseLayer(layerId: String, layer: TomlTable, warnings: MutableList<String>): Map<String, Map<Gesture, GestureOverride>> {
        val out = LinkedHashMap<String, MutableMap<Gesture, GestureOverride>>()
        for (section in layer.keySet()) {
            val keys = layer.getTable(section)
            if (section !in KEY_SECTIONS || keys == null) {
                warnings += "layer.$layerId.$section 不认识（应为 key / bottom），已跳过"
                continue
            }
            for (keyId in keys.keySet()) {
                val gestures = keys.getTable(keyId)
                if (gestures == null) {
                    warnings += "layer.$layerId.$section.$keyId 应是一张表，已跳过"
                    continue
                }
                val parsed = parseGestures("layer.$layerId.$section.$keyId", gestures, warnings)
                if (parsed.isNotEmpty()) out.getOrPut(keyId) { LinkedHashMap() }.putAll(parsed)
            }
        }
        return out
    }

    private fun parseGestures(path: String, t: TomlTable, warnings: MutableList<String>): Map<Gesture, GestureOverride> {
        val out = LinkedHashMap<Gesture, GestureOverride>()
        for (name in t.keySet()) {
            val gesture = Gesture.fromToml(name)
            if (gesture == null) {
                warnings += "$path.$name 不是已知手势（${Gesture.entries.joinToString { it.toml }}），已跳过"
                continue
            }
            val ov = if (gesture == Gesture.LONG_PRESS) parseBubble("$path.$name", t.get(name), warnings)
            else parseOutput("$path.$name", t.get(name), warnings)?.let { GestureOverride(output = it) }
            if (ov != null) out[gesture] = ov
        }
        return out
    }

    /** 长按：输出数组（每项字符串 = [KeyOutput.Text]，或带 char/text/… 的表）；空数组 = 无气泡，也算有效覆盖 */
    private fun parseBubble(path: String, v: Any?, warnings: MutableList<String>): GestureOverride? {
        if (v !is TomlArray) {
            warnings += "$path 应是数组，已跳过"
            return null
        }
        val items = (0 until v.size()).mapNotNull { parseOutput("$path[$it]", v.get(it), warnings) }
        return GestureOverride(bubble = items)
    }

    private fun parseOutput(path: String, v: Any?, warnings: MutableList<String>): KeyOutput? {
        if (v is String) return if (v.isEmpty()) null.also { warnings += "$path 不能是空字符串，已跳过" } else KeyOutput.Text(v)
        if (v !is TomlTable) {
            warnings += "$path 应是 { text = \"…\" } 这样的表，已跳过"
            return null
        }
        val fields = v.keySet()
        val unknown = fields.filter { it !in OUTPUT_FIELDS }
        if (unknown.isNotEmpty()) {
            warnings += "$path 含未知字段 ${unknown.joinToString()}，已跳过"
            return null
        }
        if (fields.size != 1) {
            warnings += "$path 需要且只能有一个输出字段（${OUTPUT_FIELDS.joinToString()}），已跳过"
            return null
        }
        val field = fields.first()
        val s = v.getString(field)
        if (s.isNullOrEmpty()) {
            warnings += "$path.$field 应是非空字符串，已跳过"
            return null
        }
        return when (field) {
            "char" -> if (s.codePointCount(0, s.length) == 1 && s.length == 1) KeyOutput.Char(s[0])
            else null.also { warnings += "$path.char 必须是单个字符，已跳过" }
            "text" -> KeyOutput.Text(s)
            "template" -> KeyOutput.Template(s)
            "phrase" -> KeyOutput.Phrase(s)
            else -> if (s in FUNC_WHITELIST) KeyOutput.Func(s)
            else null.also { warnings += "$path.func 的 \"$s\" 不在白名单内，已跳过" }
        }
    }
}
