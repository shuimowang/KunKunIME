package com.windinput.ime.keyboard

import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * 按键气泡：按下预览（单字符放大）、长按多字符选择（横移换焦点，抬手上屏）、
 * 功能键长按的竖排菜单、上滑副字符的一体式气泡。
 * PopupWindow 关闭裁剪以便伸出 IME 窗口顶部（fcitx5 PopupComponent 同思路）。
 */
class KeyPopupManager(private val anchor: View) {

    private val density = anchor.resources.displayMetrics.density

    private companion object {
        /** [selectedIndex] 的「已取消」哨兵：字符气泡被手指下滑出键位 */
        const val CANCELLED = -1

        /** 上滑气泡下沿高出指尖多少（dp），见 [showSwipeBubble]：手指高约 40dp，再近就被遮住 */
        const val SWIPE_BUBBLE_GAP_DP = 40f

        /** 气泡内容四周的留白（dp）：选中项的圆角底画在留白里面，不顶出气泡的圆角 */
        const val BUBBLE_INSET_DP = 3f

        /** 气泡被屏幕夹偏时，手指移过这么远才改按真实位置选（dp） */
        const val POPUP_MOVE_SLOP_DP = 8f

        /** 气泡外圈圆角（dp）；选中项底的圆角 = 它减去留白，两道圆弧同心 */
        const val BUBBLE_RADIUS_DP = 14f
    }

    // ── 预览气泡 ─────────────────────────────────────────────────
    private var preview: PopupWindow? = null
    private var previewLabel: TextView? = null

    fun showPreview(keyRect: RectF, label: String) {
        dismissPreview()
        val w = (52 * density).toInt()
        val h = (56 * density).toInt()
        val tv = bubbleText(label, 26f)
        previewLabel = tv
        val pos = positionAbove(keyRect, w, h)
        preview = popupWindow(tv, w, h).also {
            it.showAtLocation(anchor, Gravity.NO_GRAVITY, pos[0], pos[1])
        }
    }

    fun dismissPreview() {
        preview?.dismiss()
        preview = null
        previewLabel = null
    }

    // ── 长按选择气泡 ─────────────────────────────────────────────
    private var popup: PopupWindow? = null
    private var popupItems: List<TextView> = emptyList()
    private var popupLeft = 0
    private var itemWidthPx = 0
    private var selectedIndex = 0
    private var popupInitial = 0
    private var popupAnchorX = 0f
    private var popupClamped = false
    private var popupMoved = false

    /** 字符气泡对应键位的下沿（anchor 坐标）；手指滑到它以下即取消本次长按输入 */
    private var popupKeyBottom = 0f

    val isPopupShown: Boolean get() = popup != null

    /** [initial] 非 0 时气泡把该项摆在键中心正上方，选中也按手指相对键中心的横移算（边缘键被挤偏也不乱跳） */
    fun showPopup(keyRect: RectF, chars: List<String>, initial: Int = 0) {
        dismissAll()
        if (chars.isEmpty()) return
        itemWidthPx = (44 * density).toInt()
        val h = (52 * density).toInt()
        val inset = (BUBBLE_INSET_DP * density).toInt()
        val w = itemWidthPx * chars.size + 2 * inset
        val row = LinearLayout(anchor.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(inset, inset, inset, inset)
        }
        popupItems = chars.map { ch ->
            bubbleText(ch, 22f).also {
                it.background = null
                row.addView(it, LinearLayout.LayoutParams(itemWidthPx, h))
            }
        }
        row.background = bubbleBackground()
        val pos = positionAbove(keyRect, w, h + 2 * inset)
        val start = initial.coerceIn(0, chars.size - 1)
        if (start != 0) {
            val loc = IntArray(2)
            anchor.getLocationInWindow(loc)
            val want = (loc[0] + keyRect.centerX() - inset - itemWidthPx * (start + 0.5f)).toInt()
            pos[0] = want.coerceIn(loc[0], loc[0] + anchor.width - w)
            // q / p 一类边缘键：气泡被屏幕夹偏，不再对着键中心，改按各格的真实位置命中，
            // 否则够不着靠边那一侧的选项
            popupClamped = pos[0] != want
        }
        popupLeft = pos[0] + inset
        popupInitial = start
        popupMoved = false
        popupAnchorX = keyRect.centerX()
        popupKeyBottom = keyRect.bottom
        selectedIndex = start
        highlight()
        popup = popupWindow(row, w, h + 2 * inset).also {
            it.showAtLocation(anchor, Gravity.NO_GRAVITY, pos[0], pos[1])
        }
    }

    /**
     * 长按功能键的竖排菜单（如中英键：各方案 + 切换输入法）。从键位上方往上排，左沿对齐键位，
     * 手指上移逐项选中、抬手定；初始高亮 [selected]（当前状态）。
     *
     * 竖排而不是横排：方案名长短不一、个数不定，横排一多就得缩字号挤成一行；中英键又在
     * 底栏靠边，横条大半伸到手指够不着的另一侧。竖排时手指只管上下，离键最近的是最后一项。
     */
    fun showMenu(keyRect: RectF, labels: List<String>, selected: Int) {
        dismissAll()
        if (labels.isEmpty()) return
        val pad = (16 * density).toInt()
        val views = labels.map { label ->
            bubbleText(label, 16f).also {
                it.background = null
                it.maxLines = 1
                it.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                it.setPadding(pad, 0, pad, 0)
            }
        }
        val w = maxOf(
            (120 * density).toInt(),
            views.maxOf { it.paint.measureText(it.text.toString()).toInt() } + 2 * pad,
        ).coerceAtMost(anchor.width)
        // 方案多、上方放不下时行高往下收（不低于 32dp），条整个仍在屏内
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val room = loc[1] + keyRect.top - 40 * density
        val rowH = (room / labels.size).coerceIn(32 * density, 44 * density).toInt()
        val inset = (BUBBLE_INSET_DP * density).toInt()
        val col = LinearLayout(anchor.context).apply {
            orientation = LinearLayout.VERTICAL
            background = bubbleBackground()
            setPadding(inset, inset, inset, inset)
        }
        views.forEach { col.addView(it, LinearLayout.LayoutParams(w, rowH)) }
        popupItems = views
        val h = rowH * labels.size + 2 * inset
        val win = IntArray(2)
        anchor.getLocationInWindow(win)
        val x = (win[0] + keyRect.left).toInt().coerceIn(win[0], win[0] + anchor.width - w - 2 * inset)
        val y = (win[1] + keyRect.top - h - 8 * density).toInt()
        menuTop = y - win[1] + inset
        menuRowH = rowH
        selectedIndex = selected.coerceIn(0, labels.size - 1)
        menuInitial = selectedIndex
        highlight()
        popup = popupWindow(col, w + 2 * inset, h).also {
            it.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
    }

    /** [showMenu] 的竖排菜单：顶边（anchor 坐标）、行高；行高为 0 表示当前是横排字符气泡 */
    private var menuTop = 0
    private var menuRowH = 0
    private var menuInitial = 0

    /** 手指位置（anchor 坐标）更新选中项：字符气泡看横向，竖排菜单看纵向 */
    fun updatePopupSelection(x: Float, y: Float) {
        if (popupItems.isEmpty()) return
        selectedIndex = if (menuRowH == 0 && y > popupKeyBottom) {
            // 字符气泡：手指往下滑出键位即取消（与系统键盘一致），抬手不上屏也不补单击
            CANCELLED
        } else if (menuRowH > 0) {
            // 手指还在菜单下沿以下（键位上）：不算选了哪项，保持初始项——抬手即作罢。
            // 否则一按住往旁边蹭一下，就会落到离键最近的最后一项（切换输入法）上
            val bottom = menuTop + menuRowH * popupItems.size
            if (y >= bottom) menuInitial
            else ((y - menuTop) / menuRowH).toInt().coerceIn(0, popupItems.size - 1)
        } else if (popupInitial != 0 && popupClamped) {
            // 手指先离开按下点一小段才开始按真实位置选，免得刚长按出气泡、指尖微颤就跳项
            if (!popupMoved && kotlin.math.abs(x - popupAnchorX) < POPUP_MOVE_SLOP_DP * density) {
                popupInitial
            } else {
                popupMoved = true
                val loc = IntArray(2)
                anchor.getLocationInWindow(loc)
                ((x + loc[0] - popupLeft) / itemWidthPx).toInt().coerceIn(0, popupItems.size - 1)
            }
        } else if (popupInitial != 0) {
            (popupInitial + Math.floorDiv((x - popupAnchorX + itemWidthPx / 2f).toInt(), itemWidthPx))
                .coerceIn(0, popupItems.size - 1)
        } else {
            val loc = IntArray(2)
            anchor.getLocationInWindow(loc)
            ((x + loc[0] - popupLeft) / itemWidthPx).toInt().coerceIn(0, popupItems.size - 1)
        }
        highlight()
    }

    /** 当前选中项**下标**；未弹出返回 null。调用方按下标映回自己的字符表（气泡里显示的可能是换过形态的） */
    fun popupSelectedIndex(): Int? =
        if (popupItems.isEmpty() || selectedIndex == CANCELLED) null else selectedIndex

    fun dismissPopup() {
        popup?.dismiss()
        popup = null
        popupItems = emptyList()
        menuRowH = 0
        popupInitial = 0
        popupClamped = false
    }

    // ── 上滑一体式气泡 ───────────────────────────────────────────
    private var swipeBubble: PopupWindow? = null
    private var swipeTop: TextView? = null

    /**
     * 上滑副字符的气泡：只放副字符，悬在**手指上方**。手指上滑时指尖会越过键位上沿 20dp 左右，
     * 气泡若贴着键位（此前是罩住整个键、再往上伸一格）就整个被手遮住；这里让它下沿高出键位
     * [SWIPE_BUBBLE_GAP_DP]，滑到阈值时指尖仍在气泡下方。
     * 上滑过阈值时副字符放大变强调色（[setSwipeArmed]），抬手上屏的就是它；退回来恢复。
     * [main] 只给读屏 / 调用方对照用，不画。
     */
    @Suppress("UNUSED_PARAMETER")
    fun showSwipeBubble(keyRect: RectF, main: String, swipe: String, fingerY: Float) {
        dismissAll()
        val w = (maxOf(keyRect.width(), 52 * density)).toInt()
        val h = (58 * density).toInt()
        val top = bubbleText(swipe, 24f)
        swipeTop = top
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        swipeX = (loc[0] + keyRect.centerX() - w / 2f).toInt().coerceIn(loc[0], loc[0] + anchor.width - w)
        swipeW = w
        swipeH = h
        setSwipeArmed(false)
        swipeBubble = popupWindow(top, w, h).also {
            it.showAtLocation(anchor, Gravity.NO_GRAVITY, swipeX, swipeY(fingerY))
        }
    }

    private var swipeX = 0
    private var swipeW = 0
    private var swipeH = 0

    /** 气泡下沿在指尖上方 [SWIPE_BUBBLE_GAP_DP]；顶到屏幕上沿就停住（PopupWindow 不受键盘窗口裁剪） */
    private fun swipeY(fingerY: Float): Int {
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        return (loc[1] + fingerY - swipeH - SWIPE_BUBBLE_GAP_DP * density).toInt().coerceAtLeast(0)
    }

    /** 手指上滑途中气泡跟着指尖走，始终悬在手指上方 */
    fun moveSwipeBubble(fingerY: Float) {
        swipeBubble?.update(swipeX, swipeY(fingerY), swipeW, swipeH)
    }

    fun setSwipeArmed(armed: Boolean) {
        val p = Palette.current
        swipeTop?.apply {
            setTextColor(if (armed) p.accentText else p.text)
            textSize = if (armed) 30f else 24f
            typeface = if (armed) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
    }

    private fun dismissSwipeBubble() {
        swipeBubble?.dismiss()
        swipeBubble = null
        swipeTop = null
    }

    fun dismissAll() {
        dismissPreview()
        dismissPopup()
        dismissSwipeBubble()
    }

    // ── 内部 ─────────────────────────────────────────────────────

    private fun highlight() {
        popupItems.forEachIndexed { i, tv ->
            tv.setTextColor(if (i == selectedIndex) Palette.current.accentText else Palette.current.text)
            tv.background = if (i == selectedIndex) {
                GradientDrawable().apply {
                    setColor(Palette.current.accentSoft)
                    cornerRadius = (BUBBLE_RADIUS_DP - BUBBLE_INSET_DP) * density
                }
            } else {
                null
            }
        }
    }

    /** 气泡置于键位上方居中，水平钳制在锚点视图范围内 */
    private fun positionAbove(keyRect: RectF, w: Int, h: Int): IntArray {
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x = (loc[0] + keyRect.centerX() - w / 2f).toInt()
            .coerceIn(loc[0], loc[0] + anchor.width - w)
        val y = (loc[1] + keyRect.top - h - 8 * density).toInt()
        return intArrayOf(x, y)
    }

    private fun popupWindow(content: View, w: Int, h: Int): PopupWindow =
        PopupWindow(content, w, h, false).apply {
            isClippingEnabled = false
            isTouchable = false
        }

    private fun bubbleText(label: String, size: Float): TextView =
        TextView(anchor.context).apply {
            text = label
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(Palette.current.text)
            background = bubbleBackground()
        }

    private fun bubbleBackground(): GradientDrawable =
        GradientDrawable().apply {
            setColor(Palette.current.key)
            cornerRadius = BUBBLE_RADIUS_DP * density
            setStroke((1 * density).toInt(), Palette.current.border)
        }
}
