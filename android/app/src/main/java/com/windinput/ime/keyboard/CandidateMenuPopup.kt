package com.windinput.ime.keyboard

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.windinput.ime.core.CandidateMenuEntry
import com.windinput.ime.core.TooltipSection

/**
 * 候选长按卡片：盖在键盘区顶部的一张小卡片——标题是那个候选。先是提示（[showTooltip]），
 * 「更多操作」再换成一格一个动作的菜单（[show]）；长按中英键的方案卡片也用 [show]。
 *
 * 菜单项与文案全部来自核心（与电脑版候选右键菜单同一份，见 [com.windinput.ime.core.ImeSession.candidateMenu]），
 * 这里只管画。放在**条区之下、键盘区之上**而不是条区上方：IME 窗口以外的区域在多数系统上
 * 收不到触摸，菜单必须落在窗口里才点得到。
 *
 * 点菜单外任何地方即收起，**不执行任何动作**：浮层是一层从条区顶边铺到输入视图底部的透明底，
 * 卡片放在上面。只用 outsideTouchable 做不到——不可聚焦的 PopupWindow 不拦外面的触摸，那一下照样
 * 落到底下的键上，「点空白处关菜单」就会顺手打出一个字母。
 */
class CandidateMenuPopup(private val anchor: View) {

    private val density = anchor.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var popup: PopupWindow? = null

    /**
     * @param dismissOnOutside 点 IME 窗口以外（应用那边）是否收起。首次使用的方案卡片传 false：
     *   第一次打开输入框，用户顺手再点一下文本框，引导卡片还没看清就没了
     */
    fun show(
        title: String,
        items: List<CandidateMenuEntry>,
        dismissOnOutside: Boolean = true,
        onPick: (CandidateMenuEntry) -> Unit,
    ) {
        dismiss()
        if (items.isEmpty() || anchor.windowToken == null) return
        val content = card(title)
        // 条目多时（电脑版的两层菜单在手机上摊成一层，十几项）网格会比可用高度还高，没有滚动就
        // 把最后一行挤出屏幕（真机反馈：「设为生僻字」被截断）。上限留出标题行和上下边距
        val maxBody = ((anchor.rootView.height - anchor.height) - dp(64)).coerceAtLeast(dp(120))
        val scroll = object : android.widget.ScrollView(anchor.context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(
                    widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(maxBody, View.MeasureSpec.AT_MOST),
                )
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            addView(menuGrid(items, onPick))
        }
        content.addView(scroll)
        present(content, dismissOnOutside)
    }

    /**
     * 候选长按的第一步：提示卡——候选、各段提示（编码 / 读音 / 释义…，与电脑版悬停气泡同一份
     * 模板），底部「复制」与「更多操作」。点「更多操作」就地换成 [show] 那张菜单。
     *
     * 手机没有悬停，长按就是「想多知道一点这个候选」：先给信息，操作收进第二步——
     * 置顶、删除这些是低频动作，不该比信息更先跳到眼前。
     */
    fun showTooltip(
        title: String,
        sections: List<TooltipSection>,
        onCopy: () -> Unit,
        onMore: (() -> Unit)?,
    ) {
        dismiss()
        if (sections.isEmpty() || anchor.windowToken == null) return
        val p = Palette.current
        // 操作放在标题行右侧：位置固定，不随下面提示的长短上下跑；提示再长也挤不掉它们
        val content = card(
            title,
            listOfNotNull(
                "复制" to {
                    dismiss()
                    onCopy()
                },
                onMore?.let { "更多操作 ›" to it },
            ),
        )
        val body = LinearLayout(anchor.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        for (sec in sections) {
            val head = sec.title?.takeIf { it.isNotEmpty() }
            if (head != null && !sec.inline) {
                body.addView(
                    TextView(anchor.context).apply {
                        text = head
                        textSize = 12f
                        setTextColor(p.accentText)
                        setPadding(0, dp(6), 0, dp(2))
                    },
                )
            }
            sec.lines.forEachIndexed { i, line ->
                body.addView(
                    TextView(anchor.context).apply {
                        // 拆字段的字根是私用区字符，只给它们套字根字体，其余照常
                        text = ZiGenFont.apply(context, if (head != null && sec.inline && i == 0) "$head：$line" else line)
                        textSize = 15f
                        setTextColor(p.text)
                        setTextIsSelectable(false)
                        setPadding(0, dp(1), 0, dp(1))
                    },
                )
            }
        }
        // 提示可能很长（释义、长短语逐字读音）：可滚动，最高到键盘区的大半，按钮行始终看得到
        val maxBody = ((anchor.rootView.height - anchor.height) * 0.55f).toInt()
        val scroll = object : android.widget.ScrollView(anchor.context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(
                    widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(maxBody.coerceAtLeast(dp(80)), View.MeasureSpec.AT_MOST),
                )
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            addView(body)
        }
        content.addView(scroll)
        present(content, dismissOnOutside = true)
    }

    /** 卡片外壳 + 标题行（那个候选，右侧可带几个操作按钮） */
    private fun card(title: String, actions: List<Pair<String, () -> Unit>> = emptyList()): LinearLayout {
        val p = Palette.current
        val header = LinearLayout(anchor.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
            addView(
                TextView(anchor.context).apply {
                    text = title
                    textSize = 15f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(p.text)
                    setPadding(dp(4), 0, dp(4), 0)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            for ((label, onClick) in actions) {
                addView(
                    actionCell(label, enabled = true, onClick = onClick).apply { setPadding(dp(14), 0, dp(14), 0) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                        marginStart = dp(6)
                    },
                )
            }
        }
        return LinearLayout(anchor.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(p.surface)
                setStroke(dp(1), p.border)
            }
            addView(header)
        }
    }

    /**
     * 卡片里的一格。层次：**当前项**用强调色底 + 强调色粗字（一眼看出「现在是它」），可点的项
     * 用正文色，不可用的灰。此前可点的是强调色、当前项是灰的，主次正好反了（真机反馈）。
     */
    private fun actionCell(label: String, enabled: Boolean, current: Boolean = false, onClick: () -> Unit): TextView {
        val p = Palette.current
        return TextView(anchor.context).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            maxLines = 1
            // 4 列下一格只有 1/4 宽，「固定到第 4」这类六七个字的文案会顶到格子边：超宽时缩字号
            // 而不是截断（下限 11sp，再长才截）。动作按钮等不在网格里的格子同样受益
            setAutoSizeTextTypeUniformWithConfiguration(11, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            setTextColor(
                when {
                    current -> p.accentText
                    enabled -> p.text
                    else -> p.hint
                },
            )
            if (current) typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = 6 * density
                setColor(if (current) p.accentSoft else p.key)
            }
            isEnabled = enabled
            setOnClickListener { onClick() }
        }
    }

    private fun menuGrid(items: List<CandidateMenuEntry>, onPick: (CandidateMenuEntry) -> Unit): GridLayout {
        val grid = GridLayout(anchor.context).apply { columnCount = COLUMNS }
        for (item in items) {
            grid.addView(
                actionCell(item.label, item.enabled, item.current) {
                    dismiss()
                    onPick(item)
                },
                GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(40)
                    // ⚠️ 行必须显式 FILL：GridLayout 行的默认对齐是**文字基线**，同一行里「置顶」与
                    // 「固定到第 2」的基线不同，格子就一个高一个低（真机反馈：排版错乱）
                    rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, GridLayout.FILL)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, GridLayout.FILL, 1f)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                },
            )
        }
        return grid
    }

    /** 把卡片放进铺满条区与键盘的透明底里弹出（见类注释：点外面只收起、不落到键上） */
    private fun present(content: View, dismissOnOutside: Boolean) {
        val root = anchor.rootView
        // 条区还没量好（刚重建）时宽高为 0，算出的尺寸是负数，PopupWindow 不认
        if (anchor.width <= 0 || root.height <= 0) return
        val loc = IntArray(2).also(anchor::getLocationInWindow)
        // 透明底从条区顶边起：以条区为原点定位（以根视图为原点会差出一个状态栏高度）。
        // 高度铺到输入视图底，盖住候选条与整个键盘
        val shadeHeight = root.height - loc[1]
        if (shadeHeight <= anchor.height) return
        content.isClickable = true // 点卡片空白处不算「点外面」
        val shade = FrameLayout(anchor.context).apply {
            setOnClickListener { dismiss() }
            addView(
                content,
                FrameLayout.LayoutParams(anchor.width - dp(16), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(8)
                    topMargin = anchor.height + dp(4)
                },
            )
        }
        popup = PopupWindow(shade, anchor.width, shadeHeight, false).apply {
            // 输入视图比可见区多出底部导航那一截：开着裁剪时 PopupWindow 会把超出屏幕底的部分
            // 往上推，整张卡片随之上移一个状态栏高度、盖住候选条
            isClippingEnabled = false
            isTouchable = true
            // IME 窗口以外（应用那边）被点时也收起（首次使用的卡片除外，见 show 的参数说明）
            isOutsideTouchable = dismissOnOutside
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener { onDismissed?.invoke() }
            showAtLocation(anchor, Gravity.NO_GRAVITY, loc[0], loc[1])
        }
    }

    /** 卡片 / 菜单收起时回调（含点外面、被新卡片顶掉） */
    var onDismissed: (() -> Unit)? = null

    val isShowing: Boolean get() = popup?.isShowing == true

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    private companion object {
        const val COLUMNS = 4
    }
}
