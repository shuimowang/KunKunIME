package com.windinput.ime.keyboard

/**
 * 在 TOML 布局之上按设置做的两处改写（纯函数，JVM 单测直接跑）：
 * 字母层底行的逗号 / 句号 / 符号键怎么排，和九宫数字页作默认时两页之间的切换键。
 *
 * 不另写三份 TOML：布局文件允许用户覆盖，改写按**动作**认键，认不出（用户自定义过底行）就原样放过。
 */
object LayoutTweaks {

    /** 设置项 ui.android.bottom_row 的取值 */
    const val BOTTOM_SPLIT = "split"
    const val BOTTOM_SYM = "sym"
    const val BOTTOM_MERGE = "merge"

    /** 设置项 ui.android.number_page 的取值 */
    const val NUMBER_FULL = "full"
    const val NUMBER_PAD9 = "pad9"

    /** 逗句号合一键：中文点按是逗号、英文是句点，上滑出另一个，长按给常用标点 */
    private fun mergedPunct(weight: Float) = KeyDef(
        ",",
        KeyAction.Text(','),
        widthWeight = weight,
        swipeUp = '.',
        enText = '.',
        enSwipeUp = ',',
        longPress = ",.;:\"'\\?!^",
        id = "merged_punct",
    )

    fun apply(rows: List<List<KeyDef>>, layer: KeyboardLayer, bottomRow: String, numberPage: String): List<List<KeyDef>> =
        when (layer) {
            KeyboardLayer.LETTERS -> rows.mapIndexed { i, row ->
                if (i == rows.lastIndex) bottomRow(row, bottomRow) else row
            }
            KeyboardLayer.NUMBER -> if (numberPage == NUMBER_PAD9) {
                rows.map { row -> numberToggle(row) }
            } else {
                rows
            }
            else -> rows
        }

    /**
     * 底行：
     * - split（默认）：符号｜123｜，｜空格｜。｜中英｜换行 —— 原样
     * - sym：符号｜123｜，。｜空格｜中英｜换行 —— 逗句号并成一键
     * - merge：123｜，。｜空格｜中英｜换行 —— 符号键也并进 123（符号页从快捷面板 / 123 页进）
     */
    fun bottomRow(row: List<KeyDef>, mode: String): List<KeyDef> {
        if (mode != BOTTOM_SYM && mode != BOTTOM_MERGE) return row
        val space = row.indexOfFirst { it.action == KeyAction.Space }
        val comma = row.indexOfFirst { (it.action as? KeyAction.Text)?.char == ',' }
        val period = row.indexOfFirst { (it.action as? KeyAction.Text)?.char == '.' }
        val sym123 = row.indexOfFirst { it.action == KeyAction.SwitchSymbols }
        if (space < 0 || comma < 0 || period < 0 || sym123 < 0) return row
        if (!(comma < space && space < period)) return row

        val merged = mergedPunct(1f)
        val symbolPage = row.indexOfFirst { it.action == KeyAction.SymbolPage }
        val dropSymbol = mode == BOTTOM_MERGE && symbolPage >= 0
        val total = row.sumOf { it.widthWeight.toDouble() }.toFloat()
        val out = mutableListOf<KeyDef>()
        for ((i, key) in row.withIndex()) {
            when {
                i == symbolPage && dropSymbol -> Unit
                i == comma -> out += merged
                i == period -> Unit
                i == sym123 && dropSymbol ->
                    out += key.copy(widthWeight = key.widthWeight + row[symbolPage].widthWeight - 0.35f)
                else -> out += key
            }
        }
        // 空格吃掉省下的宽度，整行总权重不变（与上面几行对齐）
        val rest = total - out.sumOf { it.widthWeight.toDouble() }.toFloat()
        val si = out.indexOfFirst { it.action == KeyAction.Space }
        out[si] = out[si].copy(widthWeight = out[si].widthWeight + rest)
        return out
    }

    /**
     * 九宫页作默认时：123 页和九宫页要能互相切。九宫页左下的「符号」键换成「123」
     * （符号页仍可从字母层的「符号」键、快捷面板进），点它去 123 全键盘数字页。
     */
    private fun numberToggle(row: List<KeyDef>): List<KeyDef> = row.map {
        if (it.action == KeyAction.SymbolPage) it.copy(label = "123", action = KeyAction.NumberPad) else it
    }
}
