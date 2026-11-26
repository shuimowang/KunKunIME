package com.windinput.ime.keyboard

import kotlin.math.floor

/**
 * 「编辑工具栏」里对常驻列表的改动：移除、加入、拖动换位。
 *
 * 不碰视图，纯函数，便于 JVM 单测；视图（快捷面板的工具栏预览）只负责把手势翻译成这几个调用。
 */
object ToolbarPinEdit {

    fun remove(pins: List<ToolbarItem>, item: ToolbarItem): List<ToolbarItem> = pins - item

    /** 加到末尾；已常驻则不变；已满 [ToolbarItem.MAX_PINS] 个返回 null（由调用方提示用户） */
    fun add(pins: List<ToolbarItem>, item: ToolbarItem): List<ToolbarItem>? = when {
        item in pins -> pins
        pins.size >= ToolbarItem.MAX_PINS -> null
        else -> pins + item
    }

    /** 把第 [from] 个挪到第 [to] 个的位置，其余依次让位 */
    fun move(pins: List<ToolbarItem>, from: Int, to: Int): List<ToolbarItem> =
        pins.toMutableList().apply { add(to, removeAt(from)) }

    /**
     * 拖动中被拖项该落到第几位：看它的中心落在哪个槽里（预览行等距排 [ToolbarItem.MAX_PINS] 个槽，
     * [slotPitch] 是相邻两槽左边的距离）。只能落在 [count] 个常驻之内——拖到后面的空位上也是排最后。
     */
    fun dropIndex(centerX: Float, slotPitch: Float, count: Int): Int {
        if (count <= 0 || slotPitch <= 0f) return 0
        return floor(centerX / slotPitch).toInt().coerceIn(0, count - 1)
    }

    /**
     * 拖动中第 [index] 个（原位置）该让出几格：被拖项从 [from] 移到 [to] 时，两者之间的项朝空出来的
     * 那头挪一格（-1 左移、+1 右移）。被拖项自己跟手，不在此列，返回 0。
     */
    fun shiftOf(index: Int, from: Int, to: Int): Int = when {
        index == from -> 0
        from < to && index in (from + 1)..to -> -1
        from > to && index in to until from -> 1
        else -> 0
    }
}
