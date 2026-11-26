package com.windinput.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.PopupWindow
import com.windinput.ime.keyboard.BackspaceGestureMachine.Bubble

/**
 * 退格手势组件：主键盘与各面板的退格键共用一套行为——
 * 单击、长按连删、左滑扩选、上滑弹「删光标前 | 清空」双选项气泡、下滑弹「恢复」气泡。状态机在
 * [BackspaceGestureMachine]（纯 JVM 可测），这里只管 Android 侧：计时、气泡、触摸接线。
 *
 * 坐标一律是 [anchor] 视图内的坐标。键盘（一个 View 里多个键）手动调 [down]/[move]/[up]/[cancel]，
 * 面板里的独立按钮用 [attach]。
 *
 * @param longPressDelayMs 连删起始延迟，每次按下时读取（设置 ui.android.long_press_delay，键盘与面板同一来源）
 * @param feedback 连删每一下之前的反馈（震动 / 键音），由宿主决定
 */
class BackspaceGesture(
    private val listener: Listener,
    private val longPressDelayMs: () -> Long,
    private val style: Style? = null,
    private val feedback: (() -> Unit)? = null,
) {
    interface Listener {
        /** 单次退格（单击一下、连删的每一下） */
        fun onDelete()

        /** 左滑扩选：选中光标前 count 个字符（实时；0 = 选区收回光标） */
        fun onSelect(count: Int)

        /** 扩选后抬手：删选区 */
        fun onSelectionDelete()

        /** 上滑正上方抬手：删光标前的全部（光标后的保留） */
        fun onDeleteBeforeCursor()

        /** 上滑后左移抬手：清空整个输入框 */
        fun onClearAll()

        /** 设置里的「退格滑动手势」开关；关了只剩单击 / 连删 */
        fun swipeEnabled(): Boolean = true

        /** 下滑抬手：恢复刚删的内容 */
        fun onRestoreDeleted() = Unit

        /** 有没有可恢复的删除；没有时下滑不进恢复模式 */
        fun canRestoreDeleted(): Boolean = false

        /** 连删起始延迟（毫秒）；面板宿主取设置里的同一项，键盘直接把值传给 [BackspaceGesture] */
        fun longPressDelayMs(): Long = 300L
    }

    /** 气泡配色；默认取当前主题，面板可传入覆盖 */
    data class Style(
        val icon: Int,
        val accent: Int,
        val accentSoft: Int,
        val bubble: Int,
        val border: Int,
    ) {
        companion object {
            fun fromPalette(): Style = Palette.current.let {
                Style(it.text, it.accentText, it.accentSoft, it.key, it.border)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var anchor: View? = null
    private var bubbleView: BubbleView? = null
    private var popup: PopupWindow? = null
    private var keyRect = RectF()
    private var density = 1f

    private val timer = object : BackspaceGestureMachine.Timer {
        private val fire = Runnable {
            if (!machine.isPressed) return@Runnable
            feedback?.invoke()
            machine.onTimer()
        }

        override fun schedule(delayMs: Long) {
            handler.removeCallbacks(fire)
            handler.postDelayed(fire, delayMs)
        }

        override fun cancel() = handler.removeCallbacksAndMessages(null)
    }

    private lateinit var machine: BackspaceGestureMachine
    private var machineDensity = 0f

    /** 本次按压已连删过 / 进过滑动（宿主据此屏蔽别的手势） */
    val isEngaged: Boolean get() = ::machine.isInitialized && machine.isEngaged

    fun down(anchor: View, keyRect: RectF, x: Float, y: Float) {
        this.anchor = anchor
        this.keyRect = RectF(keyRect)
        density = anchor.resources.displayMetrics.density
        if (!::machine.isInitialized || machineDensity != density) {
            machineDensity = density
            machine = BackspaceGestureMachine(
                BackspaceGestureMachine.Config.forDensity(density), listener, timer, ::onBubble,
            )
        }
        dismissBubble()
        machine.down(x, y, longPressDelayMs())
        lastY = y
    }

    private var lastY = 0f

    fun move(x: Float, y: Float) {
        if (!::machine.isInitialized) return
        lastY = y
        machine.move(x, y)
        if (bubbleShown) moveBubble(y)
    }

    fun up() {
        if (::machine.isInitialized) machine.up()
        dismissBubble()
    }

    fun cancel() {
        if (::machine.isInitialized) machine.cancel()
        dismissBubble()
    }

    /**
     * 面板里的独立退格按钮：按下 / 抬起 / 取消接到本组件，按压态与按下震动在此处理。
     * 只跟踪按下的那一指（pointerId）；该指抬起即结束手势。IDLE 时抬手触点已拖出按钮范围
     * （超过 touchSlop）按取消处理，和普通按钮一致。视图被摘下（面板切走）时取消手势并收起气泡。
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attach(button: View) {
        val slop = ViewConfiguration.get(button.context).scaledTouchSlop
        var pointerId = MotionEvent.INVALID_POINTER_ID

        fun release(v: View, x: Float, y: Float) {
            pointerId = MotionEvent.INVALID_POINTER_ID
            v.isPressed = false
            val outside = x < -slop || y < -slop || x > v.width + slop || y > v.height + slop
            if (outside && !isEngaged) cancel() else up()
        }

        button.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                pointerId = MotionEvent.INVALID_POINTER_ID
                v.isPressed = false
                cancel()
            }
        })
        button.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointerId = e.getPointerId(0)
                    v.isPressed = true
                    KeyHaptics.tap(v)
                    down(v, RectF(0f, 0f, v.width.toFloat(), v.height.toFloat()), e.x, e.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    val i = e.findPointerIndex(pointerId)
                    if (i >= 0) move(e.getX(i), e.getY(i))
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val i = e.actionIndex
                    if (pointerId != MotionEvent.INVALID_POINTER_ID && e.getPointerId(i) == pointerId) {
                        release(v, e.getX(i), e.getY(i))
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (pointerId != MotionEvent.INVALID_POINTER_ID) {
                        val i = e.findPointerIndex(pointerId).coerceAtLeast(0)
                        release(v, e.getX(i), e.getY(i))
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    pointerId = MotionEvent.INVALID_POINTER_ID
                    v.isPressed = false
                    cancel()
                }
            }
            true
        }
    }

    // ── 气泡 ─────────────────────────────────────────────────────

    /** 气泡在手势期间只建一次，状态切换只改显隐 / 选中项；手势结束才 [dismissBubble] */
    private fun onBubble(state: Bubble) {
        val view = anchor ?: return
        if (state == Bubble.HIDDEN) {
            hideBubble()
            return
        }
        val wasShown = bubbleShown
        if (popup == null) {
            showBubble(view, state)
        } else {
            // 已建过（可能是上滑那块收起后又下滑）：换内容，位置按新内容重摆
            bubbleView?.choice = state
            moveBubble(lastY)
        }
        bubbleView?.visibility = View.VISIBLE
        bubbleShown = true
        if (wasShown && state != Bubble.IDLE && state != Bubble.RESTORE) KeyHaptics.tap(view)
    }

    private fun hideBubble() {
        bubbleView?.visibility = View.INVISIBLE
        bubbleShown = false
    }

    private fun showBubble(view: View, state: Bubble) {
        val st = style ?: Style.fromPalette()
        val bv = BubbleView(view.context, st, density)
        bv.choice = state
        bubbleView = bv
        val w = bv.widthPx
        val h = bv.heightPx
        val root = view.rootView
        val rootLoc = IntArray(2)
        root.getLocationInWindow(rootLoc)
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        // 双选项：右格（删光标前）正对键中心，左格（清空）在它左边；恢复：整块居中对键
        val minX = rootLoc[0]
        val maxX = maxOf(rootLoc[0], rootLoc[0] + root.width - w)
        bubbleX = (loc[0] + keyRect.centerX() - bv.rightCellCenterPx).toInt().coerceIn(minX, maxX)
        restoreX = (loc[0] + keyRect.centerX() - w / 2f).toInt().coerceIn(minX, maxX)
        bubbleW = w
        bubbleH = h
        popup = PopupWindow(bv, w, h, false).apply {
            isClippingEnabled = false
            isTouchable = false
            showAtLocation(view, Gravity.NO_GRAVITY, currentX(), bubbleY(lastY))
        }
    }

    private val restoring get() = bubbleView?.choice.let { it == Bubble.RESTORE || it == Bubble.RESTORE_ARMED }

    private fun currentX() = if (restoring) restoreX else bubbleX

    private var bubbleShown = false
    private var bubbleX = 0
    private var restoreX = 0
    private var bubbleW = 0
    private var bubbleH = 0

    /**
     * 气泡下沿高出指尖 [BUBBLE_GAP_DP]：手指高约 40dp，再近就被遮住；顶到屏幕上沿停住。
     * 恢复气泡（手指往下走）不跟手，停在键上方——跟手就压到下面的键上了
     */
    private fun bubbleY(fingerY: Float): Int {
        val loc = IntArray(2)
        anchor?.getLocationInWindow(loc)
        val y = if (restoring) keyRect.centerY() else fingerY
        return (loc[1] + y - bubbleH - BUBBLE_GAP_DP * density).toInt().coerceAtLeast(0)
    }

    private fun moveBubble(fingerY: Float) {
        popup?.update(currentX(), bubbleY(fingerY), bubbleW, bubbleH)
    }

    private fun dismissBubble() {
        popup?.dismiss()
        popup = null
        bubbleView = null
        bubbleShown = false
    }

    /**
     * 双选项气泡：固定宽高、只画矢量图标，选中项放大变强调色也不会换行 / 溢出（没有文字）。
     * 左格 = 清空（垃圾桶），右格 = 删光标前（|← 退格标签）。
     * 下滑恢复时同一块气泡改画「↶ 恢复」整格，过阈值铺强调底色、文字变强调色。
     */
    private class BubbleView(context: Context, private val st: Style, private val density: Float) : View(context) {
        private val inset = INSET_DP * density
        private val cell = CELL_DP * density
        val widthPx = (2 * cell + 2 * inset).toInt()
        val heightPx = (CELL_H_DP * density + 2 * inset).toInt()
        val rightCellCenterPx get() = inset + cell * 1.5f

        var choice: Bubble = Bubble.IDLE
            set(v) {
                if (field != v) {
                    field = v
                    invalidate()
                }
            }

        private val bg = GradientDrawable().apply {
            setColor(st.bubble)
            cornerRadius = RADIUS_DP * density
            setStroke((1 * density).toInt(), st.border)
        }
        private val paint = KeyIcons.strokePaint(density)
        private val path = Path()
        private val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = st.accentSoft }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, RESTORE_TEXT_SP, context.resources.displayMetrics)
        }

        override fun onDraw(canvas: Canvas) {
            bg.setBounds(0, 0, width, height)
            bg.draw(canvas)
            if (choice == Bubble.RESTORE || choice == Bubble.RESTORE_ARMED) {
                drawRestore(canvas)
                return
            }
            val cy = height / 2f
            for (all in booleanArrayOf(true, false)) {
                val selected = choice == if (all) Bubble.ALL else Bubble.BEFORE
                val left = inset + if (all) 0f else cell
                val cx = left + cell / 2
                if (selected) {
                    val r = (RADIUS_DP - INSET_DP) * density
                    canvas.drawRoundRect(left, inset, left + cell, height - inset, r, r, soft)
                }
                paint.color = if (selected) st.accent else st.icon
                val d = density * if (selected) 1.25f else 1f
                if (all) KeyIcons.drawTrash(canvas, cx, cy, d, paint, path)
                else KeyIcons.drawDeleteBefore(canvas, cx, cy, d, paint, path)
            }
        }

        private fun drawRestore(canvas: Canvas) {
            val armed = choice == Bubble.RESTORE_ARMED
            if (armed) {
                val r = (RADIUS_DP - INSET_DP) * density
                canvas.drawRoundRect(inset, inset, width - inset, height - inset, r, r, soft)
            }
            label.color = if (armed) st.accent else st.icon
            label.isFakeBoldText = armed
            val baseline = height / 2f - (label.descent() + label.ascent()) / 2
            canvas.drawText(RESTORE_LABEL, width / 2f, baseline, label)
        }
    }

    private companion object {
        const val BUBBLE_GAP_DP = 40f
        const val INSET_DP = 3f
        const val CELL_DP = 52f
        const val CELL_H_DP = 56f
        const val RADIUS_DP = 14f
        const val RESTORE_TEXT_SP = 18f
        const val RESTORE_LABEL = "↶ 恢复"
    }
}
