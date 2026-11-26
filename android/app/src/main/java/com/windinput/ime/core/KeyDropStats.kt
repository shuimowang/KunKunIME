package com.windinput.ime.core

import java.util.concurrent.atomic.AtomicLongArray

/**
 * 「按键没出字」的原因计数与最近记录（B4 诊断）。
 *
 * 触屏上丢键是用户最难描述、开发者最难复现的一类问题：手指碰了、屏幕没反应，没有任何
 * 报错。这里把键盘各条**静默丢弃**的路径各记一笔，导出诊断时能直接看出是哪条路径在丢。
 * 只记原因与时间，不记按了什么键、也不含任何输入内容。
 *
 * 线程：计数是原子的，环形记录加锁；主线程与后台线程都可以调。
 */
class KeyDropStats(
    private val capacity: Int = 50,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class Reason {
        /** 系统发来 ACTION_CANCEL（手势导航边缘、父视图拦截等） */
        CANCEL_ACTION,

        /** 点按中手指漂出按下键的容差区，被当作滑走而取消 */
        DRIFT_CANCEL,

        /** 长按气泡弹出后手指滑出气泡，抬手不上屏也不补单击 */
        POPUP_CANCEL,

        /** 前一根手指还在手势里（气泡 / 空格滑动 / 退格手势 / 长按），第二指整次按键被忽略 */
        SECOND_FINGER_IGNORED,

        /** 核心未就绪或在后台切方案，按键交还宿主默认处理（字母没进引擎） */
        CORE_BUSY_BYPASS,

        /** 输入连接已失效，按键丢弃 */
        NO_INPUT_CONNECTION,
    }

    class Entry(val timeMs: Long, val reason: Reason, val detail: String?)

    private val counts = AtomicLongArray(Reason.entries.size)
    private val ring = ArrayDeque<Entry>()

    fun record(reason: Reason, detail: String? = null) {
        counts.incrementAndGet(reason.ordinal)
        synchronized(ring) {
            if (ring.size >= capacity) ring.removeFirst()
            ring.addLast(Entry(now(), reason, detail))
        }
    }

    fun count(reason: Reason): Long = counts.get(reason.ordinal)

    /** 最近的记录，旧→新 */
    fun recent(): List<Entry> = synchronized(ring) { ring.toList() }

    /** 一行摘要，只列非零项，如 `DRIFT_CANCEL=3 POPUP_CANCEL=1`；全零返回「无」 */
    fun summary(): String =
        Reason.entries.filter { count(it) > 0 }.joinToString(" ") { "${it.name}=${count(it)}" }.ifEmpty { "无" }

    fun reset() {
        for (i in 0 until counts.length()) counts.set(i, 0)
        synchronized(ring) { ring.clear() }
    }

    companion object {
        /** 进程内的全局实例，键盘各处直接记；诊断导出读它 */
        val global = KeyDropStats()
    }
}
