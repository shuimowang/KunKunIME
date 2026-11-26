package com.windinput.ime.panels

import com.windinput.ime.keyboard.setHapticClick
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.windinput.ime.core.ClipboardStore
import com.windinput.ime.keyboard.InputWindow
import com.windinput.ime.keyboard.Palette
import com.windinput.ime.keyboard.PanelIcon

/**
 * 剪贴板历史面板：点击粘贴、置顶、删除、清空（跳过置顶）。
 * 列表条数上限 50，普通 ScrollView 足够；样式为占位形态，正式版接主题。
 */
class ClipboardWindow(
    private val store: ClipboardStore,
    private val listener: Listener,
) : InputWindow() {

    interface Listener {
        fun onPasteText(text: String)
        fun onBackToKeyboard()
    }

    private var listContainer: LinearLayout? = null

    override fun onCreateView(context: Context): View {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        listContainer = list
        val scroll = ScrollView(context).apply { addView(list) }
        return PanelScaffold.build(
            context = context,
            title = "剪贴板",
            onBack = listener::onBackToKeyboard,
            content = scroll,
            rightActions = listOf("清空" to { store.clearUnpinned() }),
        )
    }

    override fun onAttached() {
        store.onChanged = { listContainer?.post { refresh() } }
        refresh()
    }

    override fun onDetached() {
        store.onChanged = null
    }

    private fun refresh() {
        val list = listContainer ?: return
        val context = list.context
        val density = context.resources.displayMetrics.density
        list.removeAllViews()

        val entries = store.list()
        if (entries.isEmpty()) {
            list.addView(
                TextView(context).apply {
                    text = "复制的内容会出现在这里"
                    gravity = Gravity.CENTER
                    setTextColor(Palette.current.hint)
                    setPadding(0, (48 * density).toInt(), 0, 0)
                },
            )
            return
        }

        for (entry in entries) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Palette.current.key)
            }
            row.addView(
                TextView(context).apply {
                    text = entry.text
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Palette.current.text)
                    val p = (10 * density).toInt()
                    setPadding(p, p, p, p)
                    setHapticClick { listener.onPasteText(entry.text) }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(
                actionButton(context, if (entry.pinned) PanelIcon.Glyph.PIN else PanelIcon.Glyph.PIN_OUTLINE) {
                    store.setPinned(entry.text, !entry.pinned)
                },
                LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt()),
            )
            row.addView(
                actionButton(context, PanelIcon.Glyph.CLOSE) { store.remove(entry.text) },
                LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt()),
            )

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            val m = (3 * density).toInt()
            lp.setMargins(m, m, m, 0)
            list.addView(row, lp)
        }
    }

    /** 条目右侧的小按钮（置顶 / 删除）：矢量图标，与键盘其他功能图标同一套 */
    private fun actionButton(context: Context, glyph: PanelIcon.Glyph, onClick: () -> Unit): PanelIcon =
        PanelIcon(context).apply {
            this.glyph = glyph
            tint = if (glyph == PanelIcon.Glyph.PIN) Palette.current.accentText else Palette.current.hint
            scale = 0.5f
            setHapticClick { onClick() }
        }
}
