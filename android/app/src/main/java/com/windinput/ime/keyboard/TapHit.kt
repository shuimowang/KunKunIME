package com.windinput.ime.keyboard

/**
 * 点按期间「手指漂了多远还算按在原键上」的纯判定（不依赖 Android 类，JVM 可测）。
 *
 * 背景（B4「手指碰到字母却没上屏」）：键在**抬手**时才提交，而此前只要手指移出按下键的矩形、
 * 落进键缝，就按「同行水平最近」重判命中键，换了键就 cancelPress——快打时拇指滚动几像素、
 * 或按在键边缘，整次点按就静默丢了。判据改成**以按下的键为准**：手指仍在该键矩形外扩
 * [margin]（键缝一半 + touchSlop）之内就还算这个键，抬手照常提交它。
 */
object TapHit {
    class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** 容差 = 键缝的一半（缝在两键之间，各归一半）+ 系统 touchSlop */
    fun margin(gapPx: Float, slopPx: Float): Float = gapPx / 2f + slopPx

    fun withinTapZone(rect: Rect, x: Float, y: Float, margin: Float): Boolean =
        x >= rect.left - margin && x <= rect.right + margin &&
            y >= rect.top - margin && y <= rect.bottom + margin

    /**
     * 移动中是否该取消这次点按：已经明显离开按下键的扩展命中区，且没有上滑意图
     * （[dyUp] = 按下点 y − 当前 y，向上为正；超过上滑阈值一半归上滑气泡那条路，不在此取消）。
     */
    fun shouldCancelOnMove(
        rect: Rect,
        x: Float,
        y: Float,
        margin: Float,
        dyUp: Float,
        swipeThresholdPx: Float,
    ): Boolean = !withinTapZone(rect, x, y, margin) && dyUp < swipeThresholdPx / 2f
}
