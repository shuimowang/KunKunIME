package com.windinput.ime.settings

/**
 * 快捷输入的候选来源（内置融合实例 quick_mix 的 `members`）：列表解析、排序、写回的纯逻辑。
 *
 * members 是候选来源的单一真相源——**有无即开关、顺序即候选优先级**。设置页两维都管：
 * 每行一个开关决定有无，拖动手柄决定顺序。规则照电脑版设置的 `mix_members.rs`：
 *
 * - 界面行序**以配置里的顺序为准**，界面认识但配置里没有的来源按声明序补位（显示为关）；
 * - 写回时保留**界面不认识的成员**（用户手写进来的别的方案 id），且按原锚点留在原处，
 *   不冲到末尾——没动顺序时写回是恒等变换。
 */
object QuickSources {

    /** 一个候选来源：成员 id、标题、说明（照电脑版设置原文） */
    data class Source(val id: String, val title: String, val hint: String)

    /** 界面里的一行：来源 + 开关 */
    data class Row(val source: Source, val enabled: Boolean)

    /** 快捷输入历史的成员 id：启用时设置页一并显示「历史条数上限」 */
    const val HISTORY = "quick_input.history"

    /**
     * 界面管理的全部来源。声明序 = 新增来源的补位次序，须与核心 `default_mix_modes()` 一致
     * （前六个即出厂成员序）；末两个出厂不启用，用户自己打开才生效。
     */
    val CATALOG: List<Source> = listOf(
        Source("quick_input.calc", "计算", "算式求值，如 1+2*3、5^2、(1+2)/4"),
        Source("quick_input.number", "数字 / 金额", "中文数字、财务大写、千分位；算式结果也会转换"),
        Source(
            "quick_input.date",
            "日期 / 年月",
            "如 2026.3.5 → 2026年3月5日；12.25 → 12月25日。只有一个点时数字候选在前，多打一个点（12.25.）使日期优先",
        ),
        Source("quick_input.repeat", "重复上次上屏", "仅在刚进入、尚未输入时出现，空格即再上屏一次"),
        Source("\$primary_pinyin", "拼音", "跟随主拼音方案（全拼/双拼）"),
        Source("english", "英文", "英文词库候选"),
        Source(
            HISTORY,
            "快捷输入历史",
            "按前缀补全以前在快捷输入里上屏过的文本，如 \${reset_time}、RESET_TIME。所打内容带符号或大写时，历史排在原文之前；回车仍上屏原文。出厂不启用",
        ),
        Source(
            "\$rare_char",
            "生僻字",
            "用当前方案的编码打生僻字，只出单字。排在拼音之后时，同一个字会先被拼音取走；需要优先显示则前移",
        ),
    )

    private val managed: List<String> = CATALOG.map { it.id }

    private fun rank(id: String): Int? = managed.indexOf(id).takeIf { it >= 0 }

    /**
     * 界面行序：配置里已有的受管来源按配置顺序（开），缺席的按声明序补位（关）——
     * 插到第一个声明序更大的已有来源之前，都没有则追加。
     */
    fun rows(current: List<String>): List<Row> {
        val out = current.filter { rank(it) != null }.distinct().toMutableList()
        val enabled = out.toSet()
        for (id in managed.filter { it !in enabled }) {
            val my = rank(id)!!
            val at = out.indexOfFirst { (rank(it) ?: -1) > my }.let { if (it < 0) out.size else it }
            out.add(at, id)
        }
        return out.map { id -> Row(CATALOG[rank(id)!!], id in enabled) }
    }

    /**
     * 按界面行写回 members：开着的受管来源按行序排；界面不认识的成员按锚点（它前面原本有几个
     * 受管成员）插回新序列的同一序号处，锚点超出新序列长度的追加在末尾。
     */
    fun members(current: List<String>, rows: List<Row>): List<String> {
        val ordered = rows.filter { it.enabled }.map { it.source.id }
        val foreign = mutableListOf<Pair<Int, String>>()
        var seen = 0
        for (m in current) {
            if (rank(m) != null) seen++ else foreign.add(seen to m)
        }
        val out = mutableListOf<String>()
        var fi = 0
        for ((i, m) in ordered.withIndex()) {
            while (fi < foreign.size && foreign[fi].first <= i) out.add(foreign[fi++].second)
            out.add(m)
        }
        while (fi < foreign.size) out.add(foreign[fi++].second)
        return out
    }

    /** 开 / 关第 [index] 行 */
    fun toggle(rows: List<Row>, index: Int, on: Boolean): List<Row> =
        rows.mapIndexed { i, r -> if (i == index) r.copy(enabled = on) else r }

    /** 第 [from] 行挪到 [to] */
    fun move(rows: List<Row>, from: Int, to: Int): List<Row> {
        if (from == to) return rows
        val m = rows.toMutableList()
        m.add(to, m.removeAt(from))
        return m
    }

    /**
     * 拖动排序：第 [from] 行被拖动了 [offsetPx]（下正上负），各行高 [heights]（拖动前的行序）。
     * 越过相邻行的一半就换过去；返回 (新位置, 扣掉换过的行高后剩下的偏移)，手指下的行接着跟手。
     * 行高不一（说明有一行有两行）所以逐行算，不能按固定行高除。
     */
    fun dragTarget(from: Int, offsetPx: Float, heights: List<Float>): Pair<Int, Float> {
        var i = from
        var off = offsetPx
        // 只朝起手偏移的方向走：往下依次越过原来的 from+1、from+2…，往上依次越过 from-1、from-2…
        if (offsetPx > 0) {
            while (i + 1 < heights.size && off > heights[i + 1] / 2) {
                off -= heights[i + 1]
                i++
            }
        } else {
            while (i > 0 && off < -heights[i - 1] / 2) {
                off += heights[i - 1]
                i--
            }
        }
        return i to off
    }

    /** 二级入口的摘要：开着的来源标题按优先级列出 */
    fun summary(current: List<String>): String {
        val on = rows(current).filter { it.enabled }.map { it.source.title }
        return when {
            on.isEmpty() -> "全部关闭"
            on.size > 4 -> on.take(4).joinToString(" · ") + " 等 ${on.size} 项"
            else -> on.joinToString(" · ")
        }
    }
}
