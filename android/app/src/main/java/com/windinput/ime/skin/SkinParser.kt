package com.windinput.ime.skin

import com.windinput.ime.keyboard.Palette
import org.tomlj.Toml
import org.tomlj.TomlTable

/**
 * `skin.toml` 读取器骨架（docs/mobile-skin-design.md §3.2、§3.3、§4）。
 *
 * 原则：**任何输入都不崩、不抛**。解析不了的整份回退 [SkinRuntime.DEFAULT]；能解析的部分里，
 * 坏的单项回退该项缺省并写进 [Result.warnings]（调用方决定记日志还是给设置页提示），不让一个拼错的
 * 槽位拖垮整份皮肤。
 *
 * - 版本号高于 [SUPPORTED_VERSION]：整份忽略（[Result.tooNew]），不覆盖、不删除——皮肤来自更新版本。
 *   缺失按 1。
 * - 颜色：`"#RRGGBB"`、`"#RRGGBBAA"`（**AA 在末尾**，与 Android ARGB 相反，这里统一转成 ARGB Int）、
 *   `{ light = "…", dark = "…" }`（缺哪边，那边用缺省）、`"${slot}"` 引用（按当前深浅色解）。
 *   引用成环 / 指向不存在的槽位 / 颜色写法不对 → 该槽位（及成环链上的槽位）回退缺省。
 * - 未知槽位、未知段、未知几何键：忽略，不告警（旧版本读新皮肤要能用）。
 * - 几何：越界值**钳制并告警**，不拒绝整份皮肤。阶段 0 只读不用，视图仍读 `KeyboardDimens`。
 */
object SkinParser {
    const val SUPPORTED_VERSION = 1L

    class Result(
        val skin: SkinRuntime,
        val warnings: List<String>,
        /** 皮肤版本高于本版本支持的，整份被忽略 */
        val tooNew: Boolean = false,
    )

    /** 阶段 0 认的 15 个槽位（与 [Palette] 一一对应）；新增槽位随后续阶段加入，写了也先忽略 */
    private val SLOTS = listOf(
        "background", "surface", "key", "keySpecial", "keyPressed", "text", "hint", "accent",
        "onAccent", "accentSoft", "accentText", "preedit", "separator", "icon", "border",
    )

    private val HEX = Regex("^#([0-9a-fA-F]{6})([0-9a-fA-F]{2})?$")
    private val REF = Regex("^\\$\\{([^}]*)}$")

    fun parse(text: String): Result {
        val default = SkinRuntime.DEFAULT
        val toml = try {
            Toml.parse(text)
        } catch (e: Exception) {
            return Result(default, listOf("skin.toml 无法解析：${e.message}"))
        }
        if (toml.hasErrors()) {
            return Result(default, listOf("skin.toml 无法解析：${toml.errors().firstOrNull()?.message}"))
        }
        val warnings = mutableListOf<String>()

        val version = when (val v = toml.get("version")) {
            null -> 1L
            is Long -> v
            else -> {
                warnings += "version 不是整数，按 1 处理"
                1L
            }
        }
        if (version > SUPPORTED_VERSION) {
            return Result(default, listOf("皮肤版本 $version 高于本版本支持的 $SUPPORTED_VERSION，已忽略"), tooNew = true)
        }

        val colors = (toml.get("colors") as? TomlTable)
        val light = palette(colors, night = false, base = default.light, warnings)
        val dark = palette(colors, night = true, base = default.dark, warnings)
        val geometry = geometry(toml.get("geometry") as? TomlTable, default.geometry, warnings)

        val unchanged = light == default.light && dark == default.dark && geometry == default.geometry
        return Result(if (unchanged) default else SkinRuntime(light, dark, geometry), warnings)
    }

    // ── 颜色 ─────────────────────────────────────────────────────

    private fun palette(colors: TomlTable?, night: Boolean, base: Palette, warnings: MutableList<String>): Palette {
        if (colors == null) return base
        val baseBySlot = slotsOf(base)
        val out = HashMap<String, Int>()
        for (slot in SLOTS) {
            out[slot] = resolve(slot, colors, night, mutableSetOf(), warnings) ?: baseBySlot.getValue(slot)
        }
        val p = fromSlots(out)
        // 与默认逐项相同就复用默认实例：Service 靠 `!==` 判断配色有没有变，别平白造出新实例
        return if (p == base) base else p
    }

    /** @return null = 这个槽位没写 / 写得不对 / 在环上，调用方取缺省 */
    private fun resolve(
        slot: String,
        colors: TomlTable,
        night: Boolean,
        visiting: MutableSet<String>,
        warnings: MutableList<String>,
    ): Int? {
        val raw = colors.get(slot) ?: return null
        if (!visiting.add(slot)) {
            warnings += "颜色引用成环：${visiting.joinToString(" → ")} → $slot"
            return null
        }
        try {
            val s: String = when (raw) {
                is String -> raw
                is TomlTable -> raw.get(if (night) "dark" else "light") as? String ?: return null
                else -> {
                    warnings += "colors.$slot 不是颜色写法，已忽略"
                    return null
                }
            }
            REF.matchEntire(s)?.let { m ->
                val ref = m.groupValues[1]
                if (ref !in SLOTS) {
                    warnings += "colors.$slot 引用了不存在的槽位 \${$ref}"
                    return null
                }
                // 被引用的槽位没写时取它的缺省——resolve 返回 null 在这里也得区分「没写」与「成环」
                return if (colors.get(ref) == null) defaultOf(ref, night) else resolve(ref, colors, night, visiting, warnings)
            }
            val c = parseColor(s)
            if (c == null) warnings += "colors.$slot 的值 \"$s\" 不是 #RRGGBB / #RRGGBBAA"
            return c
        } finally {
            visiting.remove(slot)
        }
    }

    private fun defaultOf(slot: String, night: Boolean): Int =
        slotsOf(SkinRuntime.DEFAULT.palette(night)).getValue(slot)

    /** `#RRGGBB` → 不透明；`#RRGGBBAA` → AA 在末尾，转成 Android 的 ARGB */
    internal fun parseColor(s: String): Int? {
        val m = HEX.matchEntire(s.trim()) ?: return null
        val rgb = m.groupValues[1].toLong(16).toInt()
        val a = m.groupValues[2].takeIf { it.isNotEmpty() }?.toInt(16) ?: 0xFF
        return (a shl 24) or rgb
    }

    private fun slotsOf(p: Palette): Map<String, Int> = mapOf(
        "background" to p.background, "surface" to p.surface, "key" to p.key,
        "keySpecial" to p.keySpecial, "keyPressed" to p.keyPressed, "text" to p.text,
        "hint" to p.hint, "accent" to p.accent, "onAccent" to p.onAccent,
        "accentSoft" to p.accentSoft, "accentText" to p.accentText, "preedit" to p.preedit,
        "separator" to p.separator, "icon" to p.icon, "border" to p.border,
    )

    private fun fromSlots(m: Map<String, Int>) = Palette(
        background = m.getValue("background"), surface = m.getValue("surface"), key = m.getValue("key"),
        keySpecial = m.getValue("keySpecial"), keyPressed = m.getValue("keyPressed"), text = m.getValue("text"),
        hint = m.getValue("hint"), accent = m.getValue("accent"), onAccent = m.getValue("onAccent"),
        accentSoft = m.getValue("accentSoft"), accentText = m.getValue("accentText"), preedit = m.getValue("preedit"),
        separator = m.getValue("separator"), icon = m.getValue("icon"), border = m.getValue("border"),
    )

    // ── 几何 ─────────────────────────────────────────────────────

    private const val MIN_KEY_HEIGHT_DP = 40f
    private const val MIN_GAP_DP = 2f

    private fun geometry(t: TomlTable?, base: SkinGeometry, warnings: MutableList<String>): SkinGeometry {
        if (t == null) return base
        fun num(key: String): Float? = when (val v = t.get(key)) {
            null -> null
            is Long -> v.toFloat()
            is Double -> v.toFloat()
            else -> {
                warnings += "geometry.$key 不是数字，已忽略"
                null
            }
        }

        fun clamp(key: String, v: Float, lo: Float, hi: Float): Float {
            val c = v.coerceIn(lo, hi)
            if (c != v) warnings += "geometry.$key = $v 越界，已钳制为 $c"
            return c
        }

        val keyHeight = num("key_height")?.let { clamp("key_height", it, MIN_KEY_HEIGHT_DP, Float.MAX_VALUE) }
            ?: base.keyHeightDp
        val radius = num("key_radius")?.let { clamp("key_radius", it, 0f, keyHeight / 2) } ?: base.keyRadiusDp
        val gapH = num("key_gap_h")?.let { clamp("key_gap_h", it, MIN_GAP_DP, Float.MAX_VALUE) / 2 } ?: base.keyGapHalfDp
        val rowGap = num("row_gap")?.let { clamp("row_gap", it, MIN_GAP_DP, Float.MAX_VALUE) } ?: base.rowGapDp
        val edge = num("edge_pad")?.let { clamp("edge_pad", it, 0f, Float.MAX_VALUE) } ?: base.edgePadDp
        return base.copy(
            keyHeightDp = keyHeight,
            keyRadiusDp = radius,
            keyGapHalfDp = gapH,
            rowGapDp = rowGap,
            edgePadDp = edge,
        )
    }
}
