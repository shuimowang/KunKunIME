package com.windinput.ime.settings

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 词库统计回包（核心 `dict_stats`，同电脑版 `dict.systemStats`）→ 显示用的文字。
 *
 * 措辞与口径逐条照电脑版「词库统计」对话框（`wind-setting/src/pages/dict/stats_dialog.rs`），
 * 两边说的是同一份数；手机屏窄，电脑版的表格在这里摊成「标题 + 一行摘要」。
 * 纯函数，不碰 Compose，单测直接喂 JSON。
 */
internal object DictStatsFormat {

    /** 一行：标题 + 摘要（摘要可空） */
    data class Line(val title: String, val detail: String?)

    /** 千分位，与电脑版 `fmt_n` 一致（3,748） */
    fun n(v: Long): String = String.format(Locale.US, "%,d", v)

    private fun num(o: JSONObject?, key: String): String = n(o?.optLong(key, 0L) ?: 0L)

    private fun engineType(v: JSONObject): String = v.optString("engineType")

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    /** 各列：混输是 `members`（主码表 / 拼音并排），其余就是回包本身 */
    fun columns(v: JSONObject): List<JSONObject> {
        val members = v.optJSONArray("members")
        return if (engineType(v) == "mixed" && members != null) members.objects() else listOf(v)
    }

    /** 概览：(名称, 数字) */
    fun overview(v: JSONObject): List<Pair<String, String>> {
        val t = v.optJSONObject("total")
        val by = t?.optJSONObject("byLen")
        val out = mutableListOf("系统词库词条" to num(t, "entries"))
        if (engineType(v) == "english") {
            out += "单词（去重）" to num(t, "uniqueWords")
            for ((k, label) in listOf("1" to "1 字母", "2" to "2 字母", "3" to "3 字母", "4" to "4 字母", "5+" to "5 字母及以上")) {
                out += label to num(by, k)
            }
        } else {
            out += "单字（去重）" to num(t, "uniqueChars")
            out += "词组（去重）" to num(t, "uniqueWords")
            for ((k, label) in listOf("2" to "二字", "3" to "三字", "4" to "四字", "5+" to "五字及以上")) {
                out += label to num(by, k)
            }
            out += "其他" to num(t, "other")
        }
        v.optJSONObject("user")?.let { u ->
            out += "用户词" to num(u, "userWords")
            out += "临时词" to num(u, "tempWords")
        }
        return out
    }

    /**
     * 概览压成几行摘要（手机上一个数占一行太长）：词条总数 / 去重 / 按字数 / 其他与用户词。
     * 数字与 [overview] 同一份，只是归组。
     */
    fun overviewLines(v: JSONObject): List<Line> {
        val items = overview(v)
        fun pick(vararg labels: String) =
            items.filter { it.first in labels }.joinToString(" · ") { "${it.first} ${it.second}" }
        val english = engineType(v) == "english"
        val total = items.first().second
        return buildList {
            add(Line("系统词库词条 $total", scope(v).removePrefix("范围：")))
            if (english) {
                add(Line("单词（去重）", items.first { it.first == "单词（去重）" }.second))
                add(Line("按字母数", pick("1 字母", "2 字母", "3 字母", "4 字母", "5 字母及以上")))
            } else {
                add(Line("去重", pick("单字（去重）", "词组（去重）").replace("（去重）", "")))
                add(Line("词组按字数", pick("二字", "三字", "四字", "五字及以上")))
            }
            pick("其他", "用户词", "临时词").takeIf { it.isNotEmpty() }?.let { add(Line("其他与个人词", it)) }
        }
    }

    /** 「范围：系统词库（已启用 2 / 3）」 */
    fun scope(v: JSONObject): String {
        val layers = v.optJSONArray("layers").objects()
        return "范围：系统词库（已启用 ${layers.count { it.optBoolean("enabled") }} / ${layers.size}）"
    }

    /** 各词库一行：停用 / 加载失败只给状态 */
    fun layers(v: JSONObject): List<Line> = v.optJSONArray("layers").objects().map { l ->
        val label = l.optString("label")
        val name = when (l.optString("kind")) {
            "main" -> "$label（主）"
            "userLib" -> "用户分库：$label"
            else -> label
        }
        val detail = when {
            !l.optBoolean("enabled") -> "未启用"
            l.optBoolean("failed") || !l.has("entries") -> "加载失败"
            else -> "条目 ${num(l, "entries")} · 单字 ${num(l, "chars")} · 词组 ${num(l, "words")} · 其他 ${num(l, "other")}"
        }
        Line(name, detail)
    }

    /** 码长分布（只码表）：单字 / 词组各一行 */
    fun codeLen(v: JSONObject): List<Line> {
        val cl = v.optJSONObject("codeLen") ?: return emptyList()
        return listOf("单字" to "char", "词组" to "word").map { (name, key) ->
            val m = cl.optJSONObject(key)
            val cells = listOf("1" to "1 码", "2" to "2 码", "3" to "3 码", "4" to "4 码", "5+" to "≥5 码")
                .joinToString(" · ") { (b, label) -> "$label ${num(m, b)}" }
            Line(name, cells)
        }
    }

    private val levelNames = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九")

    private fun levelName(k: String): String =
        k.toIntOrNull()?.takeIf { it in 1..9 }?.let { "${levelNames[it - 1]}级" } ?: "$k 码"

    /** 「简码：一级 50 · 二级 654（有简码的字 704）」；不是码表给 null */
    fun shortCodes(v: JSONObject): String? {
        val sc = v.optJSONObject("shortCodes") ?: return null
        val levels = sc.optJSONObject("levels")
        val parts = levels?.keys()?.asSequence()?.toList().orEmpty()
            .sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
            .map { "${levelName(it)} ${n(levels!!.optLong(it))}" }
        val body = if (parts.isEmpty()) "无" else parts.joinToString(" · ")
        return "简码：$body（有简码的字 ${num(sc, "charsWithShort")}）"
    }

    /** 重码摘要；拼音同音是常态，换个说法 */
    fun dup(v: JSONObject): String {
        val d = v.optJSONObject("dup")
        val maxCode = d?.optString("maxCode").orEmpty()
        if (engineType(v) == "pinyin") {
            return "同码多词的编码 ${num(d, "groups")} 个 · 最多一码 ${num(d, "max")} 个（$maxCode）"
        }
        if ((d?.optLong("groups") ?: 0L) == 0L) return "没有重码"
        return "重码组 ${num(d, "groups")} · 涉及 ${num(d, "entries")} 条 · 最大 ${num(d, "max")} 个（编码 $maxCode）"
    }

    /** 主库这一行：合计含扩展库，单看主库才是方案本身的重码。只码表给 */
    fun mainDup(v: JSONObject): String? {
        if (engineType(v) != "codetable") return null
        val m = v.optJSONObject("dup")?.optJSONObject("main") ?: return null
        return "主库：重码组 ${num(m, "groups")} · 涉及 ${num(m, "entries")} 条 · 最大 ${num(m, "max")} 个（编码 ${m.optString("maxCode")}）"
    }

    /** 「单字全码：重码组 2,889 · 涉及 6,402 字 · 最大 12」；拼音给 null */
    fun charFull(v: JSONObject): String? {
        val c = v.optJSONObject("dup")?.optJSONObject("charFull") ?: return null
        return "单字全码：重码组 ${num(c, "groups")} · 涉及 ${num(c, "chars")} 字 · 最大 ${num(c, "max")}"
    }

    /** 码表多码字 / 拼音多音字 + 音节与零权重 */
    fun extra(v: JSONObject): String? {
        val t = v.optJSONObject("total")
        return when (engineType(v)) {
            "codetable" -> "多码字 ${num(t, "multiCodeChars")}"
            "pinyin" -> {
                val p = v.optJSONObject("pinyin")
                "多音字 ${num(t, "multiCodeChars")} · 音节 ${num(p, "syllables")} · 零权重条目 ${num(p, "zeroWeight")}"
            }
            else -> null
        }
    }

    /** 重码 Top：标题是编码与组大小，摘要是成员（按序位） */
    fun top(v: JSONObject, limit: Int): List<Line> =
        v.optJSONObject("dup")?.optJSONArray("top").objects().take(limit).map { g ->
            val texts = g.optJSONArray("texts")
            val members = if (texts == null) "" else (0 until texts.length()).joinToString(" ") { texts.optString(it) }
            Line("${g.optString("code")} · ${num(g, "size")} 个", members)
        }

    /** 字集覆盖：码表另有静重、动态选重率、加权码长 */
    fun charsets(v: JSONObject): List<Line> {
        val codetable = engineType(v) == "codetable"
        return v.optJSONArray("charsets").objects().map { c ->
            val parts = mutableListOf("字数 ${num(c, "size")}", "未收 ${num(c, "missing")}")
            if (codetable) {
                parts += "静重 " + (if (c.isNull("staticDup")) "—" else num(c, "staticDup"))
                parts += "选重率 " + (c.optDoubleOrNull("dynRate")?.let { String.format(Locale.US, "%.3f%%", it * 100) } ?: "—")
                parts += "加权码长 " + (c.optDoubleOrNull("avgCodeLen")?.let { String.format(Locale.US, "%.2f", it) } ?: "—")
            }
            Line(c.optString("label"), parts.joinToString(" · "))
        }
    }

    /** isNull 对缺键也返回 true */
    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

    /** 字频来源脚注（只码表）：没有字频时说明加权指标为何空着 */
    fun freq(v: JSONObject): String? {
        if (engineType(v) != "codetable") return null
        val s = if (v.isNull("freqSource")) null else v.optString("freqSource")
        return if (s != null) "字频：$s（同字各读音权重之和）" else "没有找到白霜拼音字表，动态选重率与加权码长不显示"
    }

    /** 「统计于 10:21:05 · 用时 0.12 s」，全部命中缓存时注明 */
    fun status(v: JSONObject, locale: Locale = Locale.getDefault()): String {
        val cols = columns(v)
        val at = cols.firstOrNull()?.optLong("computedAtMs", 0L)?.takeIf { it > 0 }
            ?.let { SimpleDateFormat("HH:mm:ss", locale).format(Date(it)) }.orEmpty()
        val ms = cols.sumOf { it.optLong("elapsedMs", 0L) }
        val cached = cols.isNotEmpty() && cols.all { it.optBoolean("cached") }
        return "统计于 $at · 用时 ${String.format(Locale.US, "%.2f", ms / 1000.0)} s" + if (cached) "（缓存）" else ""
    }
}
