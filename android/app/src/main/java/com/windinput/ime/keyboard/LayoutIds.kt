package com.windinput.ime.keyboard

/**
 * 键位稳定 id：用户键位覆盖（`keymap.toml`，见 docs/key-mapping-skin-plan.md §3）靠它定位到某个键。
 *
 * **规则（一经发布不改名）**——同一份布局内唯一，按下面顺序定：
 * 1. 布局 TOML 里写了显式 `id = "…"` 的键用它（布局作者想钉住某个键时用，不受键面改动影响）；
 * 2. 否则由键的内容派生 [derive]：
 *    - 字母 / 数字字符键：字符本身（`a`、`z`、`7`），所以覆盖写作 `layer.letters.key.z`；
 *    - 逗号 `comma`、句号 `period`（底行同一字符常有多处，靠下条去重）；
 *    - 其它标点：`x` + 十六进制码点（`\` → `x5c`），避免 id 里出现 `.` 之类会被 TOML 当路径的字符；
 *    - 原样上屏键（[KeyAction.Commit]）：`c_` + 同上写法（数字键盘的 `c_1`，123 层的半角句点 `c_period`）；
 *    - 功能键：动作名（`shift` / `backspace` / `enter` / `space` / `switch_symbols` / `switch_lang` /
 *      `emoji` / `symbol_page` / `number_pad`）；
 *    - 占位空白：`spacer`；
 * 3. 同一布局内重复（含显式 id 与别的键撞了）：**先到先得**，后到的在派生 id 后加 `-2`、`-3`…（按出现顺序），
 *    并经 [warn] 记一条日志。显式 id 撞了就丢弃显式值改用派生值。缺失 / 空白同样走派生。
 *
 * 纯函数、确定性：同一份布局任何时候都得到同一组 id（单测按出厂三份布局钉死了集合）。
 */
object LayoutIds {

    /** 给 [rows] 里的每个键补齐 / 去重 id，返回新列表（原键的其它字段不变） */
    fun assign(rows: List<List<KeyDef>>, warn: (String) -> Unit = {}): List<List<KeyDef>> {
        val taken = HashSet<String>()
        // 第一遍：显式 id 先占位（先到先得），撞了的丢弃显式值
        val explicit = rows.map { row ->
            row.map { key ->
                val want = key.id.trim()
                when {
                    want.isEmpty() -> ""
                    taken.add(want) -> want
                    else -> {
                        warn("键位 id 重复：$want，改用派生值")
                        ""
                    }
                }
            }
        }
        // 第二遍：其余键按内容派生，撞了加序号
        return rows.mapIndexed { r, row ->
            row.mapIndexed { c, key ->
                val fixed = explicit[r][c]
                if (fixed.isNotEmpty()) {
                    key.copy(id = fixed)
                } else {
                    val base = derive(key)
                    var id = base
                    var n = 2
                    while (!taken.add(id)) id = "$base-${n++}"
                    key.copy(id = id)
                }
            }
        }
    }

    /** 由键内容派生的基础 id（不含去重序号） */
    fun derive(key: KeyDef): String = when (val a = key.action) {
        is KeyAction.Text -> charName(a.char)
        is KeyAction.Commit -> "c_" + a.text.map(::charName).joinToString("_")
        KeyAction.Backspace -> "backspace"
        KeyAction.Enter -> "enter"
        KeyAction.Space -> "space"
        KeyAction.Shift -> "shift"
        KeyAction.SwitchSymbols -> "switch_symbols"
        KeyAction.SwitchLang -> "switch_lang"
        KeyAction.Emoji -> "emoji"
        KeyAction.SymbolPage -> "symbol_page"
        KeyAction.NumberPad -> "number_pad"
        KeyAction.Spacer -> "spacer"
    }

    private fun charName(c: Char): String = when {
        c == ',' -> "comma"
        c == '.' -> "period"
        c in 'a'..'z' || c in '0'..'9' -> c.toString()
        c in 'A'..'Z' -> "u" + c.lowercaseChar()
        else -> "x" + c.code.toString(16)
    }
}
