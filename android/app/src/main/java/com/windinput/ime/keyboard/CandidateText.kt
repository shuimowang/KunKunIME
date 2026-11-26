package com.windinput.ime.keyboard

import com.windinput.ime.core.TextSegment

/**
 * 候选正文的分段排法：候选栏与展开面板共用，纯逻辑（量宽靠传入的函数），可在 JVM 上单测。
 *
 * 某条候选的分段为空 = 按原文整段画；非空时各段首尾相接，提示段（[TextSegment.ROLE_PINYIN_HINT]）
 * 只换颜色、字号与正文相同。
 */
object CandidateText {

    /** 这条候选实际画出来的文字：有分段取分段拼接（「苹(ping)果」），否则原文 */
    fun display(item: String, segments: List<TextSegment>?): String =
        if (segments.isNullOrEmpty()) item else segments.joinToString("") { it.text }

    fun isHint(segment: TextSegment): Boolean = segment.role == TextSegment.ROLE_PINYIN_HINT

    /**
     * 提示段的颜色：强调色的文字形态。不用 [Palette.hint]——那是注释灰，提示要与正文、注释
     * 在色调上都分得开，一眼看出「这段是读音提示」。
     */
    fun hintColor(palette: Palette): Int = palette.accentText

    /**
     * 正文宽度：逐段按画它的那支笔量（`measure(text, isHint)`），与 [CandidateBarView] 逐段绘制的
     * 落笔位置一致；没有分段按原文整段量。
     */
    fun width(item: String, segments: List<TextSegment>?, measure: (String, Boolean) -> Float): Float =
        if (segments.isNullOrEmpty()) {
            measure(item, false)
        } else {
            segments.fold(0f) { w, s -> w + measure(s.text, isHint(s)) }
        }

    /**
     * 把按下标对应的附加数据对齐到 [size] 条：短了补 [filler]（前一段没带，中途开了开关），
     * 长了截掉（核心两次返回的条数对不上时不该崩）。
     */
    fun <T> align(list: List<T>, size: Int, filler: T): List<T> =
        if (list.size >= size) list.take(size) else list + List(size - list.size) { filler }
}
