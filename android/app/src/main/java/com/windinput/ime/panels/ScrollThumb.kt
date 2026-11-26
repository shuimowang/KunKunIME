package com.windinput.ime.panels

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.recyclerview.widget.RecyclerView
import com.windinput.ime.keyboard.Palette

/**
 * 竖向滚动指示（右缘细条）：符号页、剪贴板这类「默认只露几行、其余要上下滑」的列表用，
 * 告诉用户这里还有内容。
 *
 * 打开 / 换类别时先亮 [FIRST_HOLD_MS]，滑动时亮着、停手后再留 [HOLD_MS]，随后淡出。
 * 内容不超出可视区（不可滚）时什么都不画。颜色取调色板 [Palette.hint]。
 */
class ScrollThumb(private val recycler: RecyclerView) : RecyclerView.ItemDecoration() {
    private val density = recycler.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var alpha = 0f
    private var fade: ValueAnimator? = null
    private val startFade = Runnable { fadeTo(0f, FADE_MS) }

    init {
        recycler.addItemDecoration(this)
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) flash(HOLD_MS) else show()
            }
        })
    }

    /** 亮 [holdMs] 后淡出；打开面板 / 换类别时调 */
    fun flash(holdMs: Long = FIRST_HOLD_MS) {
        show()
        recycler.postDelayed(startFade, holdMs)
    }

    private fun show() {
        recycler.removeCallbacks(startFade)
        fade?.cancel()
        alpha = 1f
        recycler.invalidate()
    }

    private fun fadeTo(target: Float, durationMs: Long) {
        fade?.cancel()
        fade = ValueAnimator.ofFloat(alpha, target).apply {
            duration = durationMs
            addUpdateListener {
                alpha = it.animatedValue as Float
                recycler.invalidate()
            }
            start()
        }
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (alpha <= 0f) return
        val range = parent.computeVerticalScrollRange()
        val extent = parent.computeVerticalScrollExtent()
        if (range <= extent || extent <= 0) return
        val offset = parent.computeVerticalScrollOffset()
        val trackTop = TRACK_INSET_DP * density
        val track = extent - 2 * trackTop
        val thumb = maxOf(track * extent / range, MIN_THUMB_DP * density)
        val top = trackTop + (track - thumb) * offset / (range - extent).toFloat()
        val w = THUMB_DP * density
        val right = parent.width - EDGE_DP * density
        rect.set(right - w, top, right, top + thumb)
        paint.color = Palette.current.hint
        paint.alpha = (alpha * THUMB_ALPHA).toInt()
        c.drawRoundRect(rect, w / 2, w / 2, paint)
    }

    private companion object {
        const val FIRST_HOLD_MS = 1500L
        const val HOLD_MS = 800L
        const val FADE_MS = 300L
        const val THUMB_DP = 3f
        const val EDGE_DP = 2f
        const val TRACK_INSET_DP = 4f
        const val MIN_THUMB_DP = 24f
        const val THUMB_ALPHA = 200
    }
}
