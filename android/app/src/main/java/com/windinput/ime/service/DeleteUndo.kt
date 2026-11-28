package com.windinput.ime.service

/**
 * 退格下滑「恢复刚删的内容」的记录（纯 Kotlin，可 JVM 单测）。
 *
 * 由输入法自己记最近一次**输入框里的**删除，不发 Ctrl+Z，所有输入框都有效。只留一条，恢复后清掉。
 * - 单删 / 连删（[onBackwardDelete]）：紧挨着上一条单删（上一条的恢复点 = 这次恢复点 + 删掉的长度）就合并，
 *   同一次按压的连删、相邻两次按压之间没有别的输入时都合成一条。
 * - 扩选删、删光标前、清空（[record]）：各记一条，清空记光标前后两段。
 *
 * 位置一律是输入框里的绝对下标（UTF-16 code unit，与 onUpdateSelection 同口径）。
 *
 * **作废**：光标不在恢复点就作废，防止把旧内容插到别处。onUpdateSelection 是异步的，删除前的旧回报
 * 可能在记录之后才到——记录后先等「光标到恢复点」的那条回报（就是这次删除自己引起的），之前到的一律不管；
 * 等到之后光标再离开恢复点（移光标、上屏、出现选区）即作废。换输入框、打字由宿主调 [invalidate]。
 * 恢复时宿主再同步取一次光标交给 [take] 核对，回报漏掉的情况在那里兜住。
 *
 * **单删的结果可能晚到**：退格多是发 KEYCODE_DEL，应用那边把按键再投递一次才删，宿主删完立刻读光标
 * 可能还是删前的位置。这时先挂起（[Pending]），等「旧选区 = 删前选区」的那条光标回报来了再算删掉了什么；
 * 挂起超过 [PENDING_TTL_MS] 还没等到就丢掉（应用吞了这个键），免得之后用户挪光标被误认成删除。
 */
class DeleteUndo {

    /** 恢复：在 [anchor] 处放回 [before]（光标前）与 [after]（光标后），光标停在两段之间 */
    data class Entry(val before: String, val after: String, val anchor: Int)

    private var entry: Entry? = null

    /** 删前读到的光标前文、选区文字与选区位置，删除结果还没回报时挂着 */
    private class Pending(val textBefore: String, val selected: String, val oldStart: Int, val oldEnd: Int, val atMs: Long)

    private val pending = ArrayDeque<Pending>()

    /** 当前这条是单删记的，下一次紧挨着的单删可以并进来 */
    private var mergeable = false

    /** 还没等到这次删除自己引起的那条光标回报 */
    private var awaitingOwn = false

    var suspended = false
        set(value) {
            if (value) invalidate()
            field = value
        }
    val canRestore: Boolean get() = entry != null

    /**
     * 一次单删：删前选区 [oldStart]..[oldEnd]、读到的光标前文 [textBefore] 与选区文字 [selected]，
     * 删完立刻读到的选区 [newStart]..[newEnd]。光标还没动就挂起，等回报。
     */
    fun onBackwardDelete(
        textBefore: String, selected: String, oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int, nowMs: Long,
    ) {
        if (suspended) return
        if (newStart == oldStart && newEnd == oldEnd) {
            if (pending.size == MAX_PENDING) pending.removeFirst()
            pending.addLast(Pending(textBefore, selected, oldStart, oldEnd, nowMs))
            return
        }
        resolve(textBefore, selected, oldStart, newStart, newEnd)
    }

    private fun resolve(textBefore: String, selected: String, oldStart: Int, newStart: Int, newEnd: Int) {
        val deleted = deletedBefore(textBefore, selected, oldStart, newStart, newEnd) ?: return
        if (selected.isEmpty()) recordBackward(deleted, newStart) else record(deleted, "", newStart)
    }

    fun recordBackward(deleted: String, anchor: Int) {
        if (suspended || deleted.isEmpty()) return
        val e = entry
        entry = if (mergeable && e != null && e.anchor == anchor + deleted.length) {
            Entry(deleted + e.before, e.after, anchor)
        } else {
            Entry(deleted, "", anchor)
        }
        mergeable = true
        awaitingOwn = true
    }

    fun record(before: String, after: String, anchor: Int) {
        if (suspended || (before.isEmpty() && after.isEmpty())) return
        entry = Entry(before, after, anchor)
        mergeable = false
        awaitingOwn = true
    }

    fun onSelectionUpdate(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int, nowMs: Long) {
        while (pending.isNotEmpty() && nowMs - pending.first().atMs > PENDING_TTL_MS) pending.removeFirst()
        val i = pending.indexOfFirst { it.oldStart == oldStart && it.oldEnd == oldEnd }
        if (i >= 0) {
            val p = pending[i]
            // 更早挂起的已不会再有回报（回报按顺序到）
            repeat(i + 1) { pending.removeFirst() }
            resolve(p.textBefore, p.selected, p.oldStart, newStart, newEnd)
            // 这条就是删除自己的回报
            if (entry?.anchor == newStart) awaitingOwn = false
            return
        }
        val e = entry ?: return
        val atAnchor = newStart == e.anchor && newEnd == e.anchor
        if (awaitingOwn) {
            if (atAnchor) awaitingOwn = false
            return
        }
        if (!atAnchor) invalidate()
    }

    fun invalidate() {
        entry = null
        pending.clear()
        mergeable = false
        awaitingOwn = false
    }

    /** 取出记录并清掉；光标（[selStart], [selEnd]）不在恢复点返回 null（记录同样作废） */
    fun take(selStart: Int, selEnd: Int): Entry? {
        val e = entry
        invalidate()
        return e?.takeIf { selStart == it.anchor && selEnd == it.anchor }
    }

    companion object {
        const val PENDING_TTL_MS = 1000L
        private const val MAX_PENDING = 8

        /**
         * 一次光标前删除删掉了什么：删前读到的光标前文 [textBefore]（只需覆盖删掉的部分）与选区文字
         * [selected]，删前选区起点 [oldStart]，删后选区 [newStart]..[newEnd]。
         * 按两次光标的差取，emoji 代理对、组合字符删几个单元就取几个。
         * 返回 null = 对不上（删后还有选区、光标往右了、删掉的超出读到的前文），不记。
         */
        fun deletedBefore(textBefore: String, selected: String, oldStart: Int, newStart: Int, newEnd: Int): String? {
            if (newStart != newEnd || newStart > oldStart) return null
            val count = oldStart - newStart
            if (count > textBefore.length) return null
            return textBefore.takeLast(count) + selected
        }
    }
}
