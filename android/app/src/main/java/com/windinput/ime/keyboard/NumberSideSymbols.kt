package com.windinput.ime.keyboard

/**
 * 九宫格数字键盘（数字框）左列的符号表：配置串的解析 / 格式化与设置页的增删挪（纯逻辑）。
 *
 * 存在 `ui.android.number_side_symbols`：每项之间用 `\n` 分隔（单项里允许 `,`，所以不用逗号分隔）。
 * 键不存在 = 出厂列表；空串 = 用户清空了（左列只剩「自定义」格）。
 * 电话框用固定的另一套，不走这里。
 */
object NumberSideSymbols {

    const val CONFIG_KEY = "ui.android.number_side_symbols"

    /** 单项最多几个字符（按代码点数，😂 算一个） */
    const val MAX_CHARS = 4

    /** 出厂列表：算式和常见分隔符 */
    val DEFAULTS = listOf("+", "-", "*", "/", "%", "=", ",", ":", "@", "#", "(", ")")

    /** 配置值 → 列表。手改配置写坏的项（空白、超长、重复）跳过，不让整列失效 */
    fun parse(raw: String?): List<String> {
        if (raw == null) return DEFAULTS
        val out = mutableListOf<String>()
        for (part in raw.split('\n')) {
            val item = clean(part)
            if (valid(item) && item !in out) out += item
        }
        return out
    }

    fun format(list: List<String>): String = list.joinToString("\n")

    sealed interface AddResult {
        data class Added(val list: List<String>) : AddResult
        data class Rejected(val reason: String) : AddResult
    }

    /** 设置页「添加」：去掉空白后校验长度与重复，通过则排到末尾 */
    fun add(list: List<String>, input: String): AddResult {
        val item = clean(input)
        return when {
            item.isEmpty() -> AddResult.Rejected("请输入符号")
            !valid(item) -> AddResult.Rejected("每项最多 $MAX_CHARS 个字符")
            item in list -> AddResult.Rejected("已有「$item」")
            else -> AddResult.Added(list + item)
        }
    }

    fun remove(list: List<String>, index: Int): List<String> =
        list.filterIndexed { i, _ -> i != index }

    fun move(list: List<String>, from: Int, to: Int): List<String> {
        if (from == to) return list
        val m = list.toMutableList()
        m.add(to, m.removeAt(from))
        return m
    }

    /**
     * 拖动排序：第 [from] 行被拖动了 [offsetPx]（下正上负），行高 [rowPx]，越过半行即换到相邻位置。
     * 调用方换位后把偏移减去换过的行数 × 行高，手指下的行就接着跟手。
     */
    fun dragTarget(from: Int, offsetPx: Float, rowPx: Float, size: Int): Int =
        (from + Math.round(offsetPx / rowPx)).coerceIn(0, size - 1)

    /** 去掉所有空白：符号里夹空格没有意义，换行更会把一项拆成两项 */
    private fun clean(s: String): String = s.filterNot { it.isWhitespace() }

    private fun valid(item: String): Boolean =
        item.isNotEmpty() && item.codePointCount(0, item.length) <= MAX_CHARS
}
