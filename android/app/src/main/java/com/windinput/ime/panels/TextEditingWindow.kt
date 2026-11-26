package com.windinput.ime.panels

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.windinput.ime.keyboard.BackspaceGesture
import com.windinput.ime.keyboard.InputWindow
import com.windinput.ime.keyboard.KeyHaptics
import com.windinput.ime.keyboard.KeyIcons
import com.windinput.ime.keyboard.KeyboardDimens
import com.windinput.ime.keyboard.GlyphDrawable
import com.windinput.ime.keyboard.Palette
import com.windinput.ime.keyboard.PanelIcon

/**
 * 文本编辑面板：光标移动/选择/剪切复制粘贴（参考 fcitx5 TextEditingWindow）。
 * 编辑操作经 Listener 回调 Service 执行（InputConnection/KeyEvent）。
 */
class TextEditingWindow(private val listener: Listener) : InputWindow() {

    interface Listener {
        fun onEditAction(action: EditAction)

        /** 当前选择模式状态（按住方向键扩选） */
        fun isSelecting(): Boolean

        fun onBackToKeyboard()

        /** 退格键的手势回调（单击 / 连删 / 扩选 / 上滑删光标前或清空） */
        fun editBackspaceGesture(): BackspaceGesture.Listener
    }

    enum class EditAction {
        UP, DOWN, LEFT, RIGHT, HOME, END,
        TOGGLE_SELECT, SELECT_ALL, CUT, COPY, PASTE, BACKSPACE, UNDO, REDO,
    }

    private var selectButton: TextView? = null

    private val ARROWS = mapOf(
        EditAction.UP to PanelIcon.Glyph.ARROW_UP,
        EditAction.DOWN to PanelIcon.Glyph.ARROW_DOWN,
        EditAction.LEFT to PanelIcon.Glyph.ARROW_LEFT,
        EditAction.RIGHT to PanelIcon.Glyph.ARROW_RIGHT,
    )

    override fun onCreateView(context: Context): View {
        val density = context.resources.displayMetrics.density
        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // 左右留白同主键盘；键缝由键帽格子自己的内缩画出（同符号页）
            val edge = (KeyboardDimens.EDGE_PAD_DP * density).toInt()
            setPadding(edge, 0, edge, 0)
        }

        // 4×3 网格 + 底行撤销 / 重做（各占半行）：文本按钮，功能标注见各 label
        val rows = listOf(
            listOf("全选" to EditAction.SELECT_ALL, "↑" to EditAction.UP, "剪切" to EditAction.CUT, "行首" to EditAction.HOME),
            listOf("←" to EditAction.LEFT, "选择" to EditAction.TOGGLE_SELECT, "→" to EditAction.RIGHT, "行尾" to EditAction.END),
            listOf("复制" to EditAction.COPY, "↓" to EditAction.DOWN, "粘贴" to EditAction.PASTE, "⌫" to EditAction.BACKSPACE),
            listOf("撤销" to EditAction.UNDO, "重做" to EditAction.REDO),
        )
        for (row in rows) {
            val rowView = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, action) in row) {
                // 方向键与「选择」是主键（字母键底色），其余操作用功能键底色，同主键盘的分法
                val primary = action in ARROWS || action == EditAction.TOGGLE_SELECT
                val btn = cellButton(context, label, primary) {
                    KeyHaptics.tap(it)
                    listener.onEditAction(action)
                    refreshSelectState()
                }
                if (action == EditAction.TOGGLE_SELECT) selectButton = btn
                if (action == EditAction.BACKSPACE) {
                    KeyIcons.applyBackspace(btn, Palette.current.text)
                    val gesture = listener.editBackspaceGesture()
                    BackspaceGesture(gesture, gesture::longPressDelayMs, feedback = { KeyHaptics.tap(btn) }).attach(btn)
                }
                // 方向键用矢量箭头，与键盘上其他功能图标同一套笔画（字体里的 ↑↓←→ 细且各机型不一）
                ARROWS[action]?.let { GlyphDrawable.apply(btn, it, Palette.current.text) }
                rowView.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
            grid.addView(
                rowView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
            )
        }

        return PanelScaffold.build(
            context = context,
            title = "编辑",
            onBack = listener::onBackToKeyboard,
            content = grid,
        )
    }

    override fun onAttached() = refreshSelectState()

    private fun refreshSelectState() {
        selectButton?.let {
            val selecting = listener.isSelecting()
            it.setTypeface(null, if (selecting) Typeface.BOLD else Typeface.NORMAL)
            it.setTextColor(if (selecting) Palette.current.accentText else Palette.current.text)
        }
    }

    /** 键帽样式的按钮（圆角、键缝、按下色同主键盘与符号页） */
    private fun cellButton(context: Context, label: String, primary: Boolean, onClick: (View) -> Unit): TextView {
        val p = Palette.current
        return keyCell(context, label, if (primary) p.key else p.keySpecial, p.text).apply {
            textSize = 16f
            setOnClickListener(onClick)
        }
    }
}
