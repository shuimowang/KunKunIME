package com.windinput.ime.panels

import com.windinput.ime.keyboard.setHapticClick
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.windinput.ime.core.RecentStore
import com.windinput.ime.keyboard.BackspaceGesture
import com.windinput.ime.keyboard.InputWindow
import com.windinput.ime.keyboard.GlyphDrawable
import com.windinput.ime.keyboard.Palette
import com.windinput.ime.keyboard.PanelIcon

/**
 * 表情面板：分类 Tab（最近使用置首）+ 多行网格 + 退格。
 * 数据为精简集（EmojiData）；肤色变体长按/可渲染过滤为后续能力。
 */
class EmojiWindow(
    private val recentStore: RecentStore,
    private val listener: Listener,
) : InputWindow() {

    interface Listener {
        fun onEmojiSelected(emoji: String)
        fun onEmojiBackspace()

        /** 退格键的手势回调（单击 / 连删 / 扩选 / 上滑删光标前或清空） */
        fun emojiBackspaceGesture(): BackspaceGesture.Listener
        fun onBackToKeyboard()
    }

    private companion object {
        const val COLUMNS = 8
        const val RECENT_LABEL = "🕐"
    }

    private var grid: RecyclerView? = null
    private var tabsRow: LinearLayout? = null
    private var currentTab = 0
    private val adapter = EmojiAdapter { emoji ->
        recentStore.record(emoji)
        listener.onEmojiSelected(emoji)
    }

    override fun onCreateView(context: Context): View {
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val density = context.resources.displayMetrics.density

        val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        tabsRow = tabs
        val labels = listOf(RECENT_LABEL) + EmojiData.categories.map { it.label }
        labels.forEachIndexed { index, label ->
            tabs.addView(
                TextView(context).apply {
                    text = label
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                    setHapticClick { selectTab(index) }
                    // 「最近」不是表情，用矢量图标（同快捷面板那一套），不拿 🕐 冒充分类
                    if (index == 0) {
                        minWidth = (44 * density).toInt()
                        GlyphDrawable.apply(this, PanelIcon.Glyph.CLOCK, Palette.current.text, 20f)
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (36 * density).toInt(),
                ),
            )
        }
        content.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(tabs)
            },
        )

        grid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, COLUMNS)
            adapter = this@EmojiWindow.adapter
            setBackgroundColor(Palette.current.surface)
        }
        content.addView(
            grid,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        return PanelScaffold.build(
            context = context,
            title = "表情",
            onBack = listener::onBackToKeyboard,
            content = content,
            backspace = listener.emojiBackspaceGesture(),
        )
    }

    override fun onAttached() {
        // 有最近使用则默认展示"最近"，否则第一个分类
        selectTab(if (recentStore.list().isEmpty()) 1 else 0)
    }

    private fun selectTab(index: Int) {
        currentTab = index
        adapter.submit(
            if (index == 0) recentStore.list()
            else EmojiData.categories[index - 1].items,
        )
        grid?.scrollToPosition(0)
        tabsRow?.let { tabs ->
            for (i in 0 until tabs.childCount) {
                (tabs.getChildAt(i) as TextView).setTypeface(
                    null,
                    if (i == index) Typeface.BOLD else Typeface.NORMAL,
                )
                tabs.getChildAt(i).setBackgroundColor(
                    if (i == index) Palette.current.accentSoft else Color.TRANSPARENT,
                )
            }
        }
    }

    private class EmojiAdapter(
        private val onClick: (String) -> Unit,
    ) : RecyclerView.Adapter<EmojiAdapter.Holder>() {

        private var items: List<String> = emptyList()

        @Suppress("NotifyDataSetChanged")
        fun submit(newItems: List<String>) {
            items = newItems
            notifyDataSetChanged()
        }

        class Holder(val view: TextView) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val density = parent.resources.displayMetrics.density
            val tv = TextView(parent.context).apply {
                textSize = 24f
                gravity = Gravity.CENTER
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    (44 * density).toInt(),
                )
            }
            return Holder(tv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val emoji = items[position]
            holder.view.text = emoji
            holder.view.setHapticClick { onClick(emoji) }
        }

        override fun getItemCount() = items.size
    }
}
