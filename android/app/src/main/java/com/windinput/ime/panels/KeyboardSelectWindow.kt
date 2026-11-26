package com.windinput.ime.panels

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.windinput.ime.core.EngineCmd
import com.windinput.ime.core.SchemaEntry
import com.windinput.ime.core.UiEvent
import com.windinput.ime.keyboard.InputWindow
import com.windinput.ime.keyboard.KeyHaptics
import com.windinput.ime.keyboard.Palette
import com.windinput.ime.keyboard.PanelIcon

/**
 * 键盘选择面板（照微信「键盘选择」）：每个启用的方案一格（含英文方案）、「英文直输」一格，
 * 最后是「键盘管理」（设置里的「启用的方案」页）。点方案格切过去并回到键盘。
 *
 * 格子列表与标签由 [KeyboardChoices] 生成；选中态（当前方案）来自引擎推送的 [UiEvent.Status]，
 * 与快捷面板同一做法，本地不记一份。
 */
class KeyboardSelectWindow(
    private val listener: Listener,
) : InputWindow() {

    interface Listener {
        fun onQuickCommand(cmd: EngineCmd)

        /** 当前可选方案（启用的） */
        fun quickSchemas(): List<SchemaEntry>

        fun onBackToKeyboard()

        /** 切到第 [index] 个方案（与中英键选方案同一路径：记下切走前的中文方案，中英键才切得回来） */
        fun onKeyboardSelectSchema(index: Int)

        /** 打开设置里的「启用的方案」页 */
        fun onKeyboardManage()
    }

    private companion object {
        const val COLUMNS = 4
        const val TILE_HEIGHT_DP = 76
    }

    private var grid: GridLayout? = null
    private var status: UiEvent.Status? = null

    override fun onCreateView(context: Context): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val g = GridLayout(context).apply {
            columnCount = COLUMNS
            setPadding(dp(10), dp(10), dp(4), dp(8))
        }
        grid = g
        rebuild(context)
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(g)
        }
        return PanelScaffold.build(
            context = context,
            title = "键盘选择",
            onBack = listener::onBackToKeyboard,
            content = scroll,
        )
    }

    /** 每次进入面板刷新：方案可能在别处（中英键卡片、设置页）被切过或增删 */
    override fun onAttached() = refresh()

    fun updateStatus(newStatus: UiEvent.Status) {
        status = newStatus
        refresh()
    }

    fun refresh() {
        val ctx = grid?.context ?: return
        rebuild(ctx)
    }

    private fun rebuild(context: Context) {
        val g = grid ?: return
        g.removeAllViews()
        val density = context.resources.displayMetrics.density
        val choices = KeyboardChoices.build(listener.quickSchemas(), status?.chineseMode ?: true)
        for (c in choices) {
            g.addView(
                tile(context, c) { onChoice(c.action) },
                GridLayout.LayoutParams().apply {
                    width = 0
                    height = (TILE_HEIGHT_DP * density).toInt()
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(0, 0, (6 * density).toInt(), (6 * density).toInt())
                },
            )
        }
    }

    private fun onChoice(action: KeyboardChoices.Action) {
        when (action) {
            is KeyboardChoices.Action.Schema -> {
                listener.onKeyboardSelectSchema(action.index)
                listener.onBackToKeyboard()
            }
            KeyboardChoices.Action.English -> {
                listener.onQuickCommand(EngineCmd.English)
                listener.onBackToKeyboard()
            }
            KeyboardChoices.Action.Manage -> listener.onKeyboardManage()
        }
    }

    /** 一格：上方框字徽章（「键盘管理」是矢量图标），下标签；当前方案浅强调底 + 强调色文字 */
    private fun tile(context: Context, choice: KeyboardChoices.Choice, onClick: () -> Unit): View {
        val density = context.resources.displayMetrics.density
        val p = Palette.current
        val fg = if (choice.selected) p.accentText else p.text
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(if (choice.selected) p.accentSoft else p.key)
            }
            val size = (28 * density).toInt()
            addView(
                glyphOrBadge(context, choice.badge ?: PanelIcon.Glyph.SETTINGS, fg, size),
                LinearLayout.LayoutParams(size, size),
            )
            addView(
                TextView(context).apply {
                    text = choice.label
                    textSize = 12f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    setTextColor(if (choice.selected) p.accentText else p.hint)
                    setPadding(0, (4 * density).toInt(), 0, 0)
                },
            )
            // 读屏念标签，选中的带上「当前」（e2e 也据此认出当前方案）
            contentDescription = if (choice.selected) "${choice.label}·当前" else choice.label
            setOnClickListener {
                KeyHaptics.tap(it)
                onClick()
            }
        }
    }
}
