package com.windinput.ime.keyboard.keymap

/** 键位手势。左右滑不做（非主流，与空格移光标冲突），见 docs/key-mapping-skin-plan.md */
enum class Gesture(val toml: String) {
    TAP("tap"),
    SWIPE_UP("swipe_up"),
    SWIPE_DOWN("swipe_down"),
    LONG_PRESS("long_press"),
    ;

    companion object {
        fun fromToml(name: String): Gesture? = entries.firstOrNull { it.toml == name }
    }
}

/** 手势触发后「做什么」。阶段 0 只解析成数据，不执行 */
sealed interface KeyOutput {
    /** 单字符，经引擎（中英形态随模式） */
    data class Char(val c: kotlin.Char) : KeyOutput

    /** 原样上屏，不经引擎 */
    data class Text(val text: String) : KeyOutput

    /** 上屏时现算的模板，如 `{date}` */
    data class Template(val template: String) : KeyOutput

    /** 引用短语库条目 */
    data class Phrase(val id: String) : KeyOutput

    /** 功能动作，名字在 [KeymapParser.FUNC_WHITELIST] 内 */
    data class Func(val name: String) : KeyOutput
}

/** 一个手势的覆盖：[output] 是点按 / 上滑 / 下滑的输出；长按用 [bubble]（气泡项，顺序即位置） */
data class GestureOverride(val output: KeyOutput? = null, val bubble: List<KeyOutput> = emptyList())

/**
 * 解析结果。
 *
 * @property tooNew 文件 `version` 高于本版本支持 → 整份忽略（[layers] 为空），**不覆盖、不删除**文件，
 *   设置页据此提示「配置来自更新版本」
 * @property warnings 被跳过的项（拼错字段、未知手势、未知 func…），不让整份失效
 */
data class KeymapParseResult(
    val version: Int,
    val tooNew: Boolean,
    /** 层 id（letters / symbols / number）→ 键位 id（见 LayoutIds）→ 手势 → 覆盖 */
    val layers: Map<String, Map<String, Map<Gesture, GestureOverride>>>,
    val warnings: List<String>,
) {
    fun overrideFor(layer: String, keyId: String, gesture: Gesture): GestureOverride? =
        layers[layer]?.get(keyId)?.get(gesture)

    companion object {
        val EMPTY = KeymapParseResult(1, false, emptyMap(), emptyList())
    }
}
