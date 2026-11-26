package com.windinput.ime.service

/**
 * 联想自动隐藏的「暂停」汇总。
 *
 * 核心的联想窗出厂 5 秒后自动收起，计时只认按键；手指在候选条上滑动、长按看提示卡、
 * 打开展开面板挑词都不是按键，于是操作到一半窗就被收掉（真机反馈）。
 *
 * 几种「正在操作联想候选」的来源会交叠（按住候选条时长按弹出提示卡、松手后卡片还开着），
 * 所以按来源分别记，**任一来源在 = 暂停**，全部结束才告诉核心重新计满。
 * 只在「有 ↔ 无」翻转时调一次 [send]，同一来源重复报不会多打 JNI。
 */
class AssocHold(private val send: (Boolean) -> Unit) {
    enum class Reason { TOUCH, EXPANDED, POPUP }

    private val active = mutableSetOf<Reason>()

    fun set(reason: Reason, on: Boolean) {
        val was = active.isNotEmpty()
        if (on) active.add(reason) else active.remove(reason)
        val now = active.isNotEmpty()
        if (was != now) send(now)
    }

    /** 收键盘时清空：按下后视图被拆掉收不到抬起，不清的话之后的按压都不再通知 */
    fun clear() {
        if (active.isEmpty()) return
        active.clear()
        send(false)
    }
}
