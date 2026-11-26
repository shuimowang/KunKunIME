package com.windinput.ime.panels

import com.windinput.ime.keyboard.setHapticClick
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.windinput.ime.core.RecentStore
import com.windinput.ime.keyboard.GlyphDrawable
import com.windinput.ime.keyboard.InputWindow
import com.windinput.ime.keyboard.KeyHaptics
import com.windinput.ime.keyboard.KeyIcons
import com.windinput.ime.keyboard.KeyboardDimens
import com.windinput.ime.keyboard.BackspaceGesture
import com.windinput.ime.keyboard.Palette
import com.windinput.ime.keyboard.PanelIcon

/**
 * 分类符号页（docs/android-ime-features.md §14.3）：上面 3 行是和键盘同样样式的符号键，
 * 底部一行是 `←` · 横滑分类 tab · `⌫`。
 *
 * 与两家的出入（§14.7 有记录）：
 * - 两家第 3 行两端是 `123` 和 `⌫`。这里符号区要能上下滚（日文、序号一类一页放不下），
 *   固定在滚动区里的两个键不好处理，于是 `⌫` 挪到底行，`←` 直接回进来前的那一层（123 层或数字键盘），
 *   就不再要单独的 `123`。
 * - 点一个符号后回到进来前的那一层（主流做法）；底行的锁定键锁上后改为**停在本页**，
 *   连着打几个符号时不用每次重进三层。锁定态由宿主持久化（[Listener.isSymbolLocked]）。
 *
 * 符号原样上屏，不经引擎（中文模式下点「,」就是半角逗号，这一页本来就是按形态挑的）。
 * 窗口保留条区（[keepsBar]），与键盘同高，进出时整个输入法不跳。
 */
class SymbolWindow(
    private val categoriesProvider: () -> List<SymbolCategory>,
    private val recentStore: RecentStore,
    private val listener: Listener,
) : InputWindow() {

    interface Listener {
        fun onSymbolSelected(symbol: String)
        fun onSymbolBackspace()

        /** 退格键的手势回调（单击 / 连删 / 扩选 / 上滑删光标前或清空） */
        fun symbolBackspaceGesture(): BackspaceGesture.Listener
        fun onSymbolBack()

        /** 锁定态（点完符号是否停在本页）：由宿主持久化，缺省不锁 */
        fun isSymbolLocked(): Boolean = false
        fun onSymbolLockChanged(locked: Boolean) = Unit

        /** 符号页打开 / 收起：打开时条区的候选行用来放最近用过的符号 */
        fun onSymbolPageShown(shown: Boolean) = Unit
    }

    private companion object {
        const val COLUMNS = 10
        const val GRID_ROWS = 3
        const val COMMON_LABEL = "常用"

        /**
         * 「常用」是固定的一组常见符号，不混入最近用过的：最近用过的在条区候选行里（一眼可见、
         * 点了直接上屏），两处都放就重复了。日后「常用」支持自定义，改的也是这一份。
         */
        val COMMON_DEFAULTS = (
            "， 。 ？ ！ 、 ： ； “ ” ‘ ’ （ ） 《 》 【 】 … — ～ · ￥ " +
                "@ # % & * + - = / _ ~ ^ | \\ < > ( ) [ ] { } ' \" ` $"
            ).split(' ').filter { it.isNotEmpty() }
    }

    override val keepsBar: Boolean get() = true

    private val categories by lazy(categoriesProvider)
    private var tabsRow: LinearLayout? = null
    private var grid: RecyclerView? = null
    private var scrollThumb: ScrollThumb? = null
    private val adapter = SymbolAdapter { symbol ->
        // 只记不刷：正在看「最近」时不让格子在手指底下挪位（微信也是下次进来才更新）
        recentStore.record(symbol)
        listener.onSymbolSelected(symbol)
        if (!locked) listener.onSymbolBack()
    }
    private var locked = false

    override fun onCreateView(context: Context): View {
        val density = context.resources.displayMetrics.density
        val palette = Palette.current
        val rowHeight = (KeyboardDimens.rowHeightDp * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            // 左右留白与主键盘相同：10 列符号格和字母层 10 列键上下对齐
            val edge = (KeyboardDimens.EDGE_PAD_DP * density).toInt()
            setPadding(edge, (KeyboardDimens.PAD_TOP_DP * density).toInt(), edge, 0)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                PanelScaffold.keyboardHeightPx(context),
            )
        }

        grid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, COLUMNS)
            adapter = this@SymbolWindow.adapter
            // 脚本按 content-desc 找到符号区（格子本身是 TextView，文字即符号）
            contentDescription = "symbol-grid"
            scrollThumb = ScrollThumb(this)
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight * GRID_ROWS))

        val bottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        // 底行宽度按主键盘底行的权重（总权重 10）：「返回」占「符号」键的位置和宽度（1.35），
        // ⌫ 与「换行」同宽（1.4），锁定键一个字母键宽。点进来、点回去手指不用挪
        val unit = (context.resources.displayMetrics.widthPixels -
            2 * (KeyboardDimens.EDGE_PAD_DP * density).toInt()) / 10f

        bottom.addView(
            keyCell(context, "返回", palette.keySpecial, palette.text).apply {
                // 功能键底色（与主键盘「符号」「123」同色），矢量返回箭头与面板左上角的返回同一个图标
                GlyphDrawable.apply(this, PanelIcon.Glyph.BACK, palette.text)
                contentDescription = "symbol-back"
                setOnClickListener {
                    KeyHaptics.tap(it)
                    listener.onSymbolBack()
                }
            },
            LinearLayout.LayoutParams((unit * 1.35f).toInt(), rowHeight),
        )

        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabsRow = tabs
        val labels = listOf(COMMON_LABEL) + categories.map { it.name }
        labels.forEachIndexed { index, label ->
            tabs.addView(
                TextView(context).apply {
                    text = label
                    textSize = 16f
                    setTextColor(palette.text)
                    gravity = Gravity.CENTER
                    setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
                    setHapticClick { selectTab(index) }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (rowHeight * 0.7f).toInt()),
            )
        }
        bottom.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(tabs)
            },
            LinearLayout.LayoutParams(0, rowHeight, 1f).apply { gravity = Gravity.CENTER_VERTICAL },
        )

        val lockCell = keyCell(context, "", palette.keySpecial, palette.text)
        lockCell.contentDescription = "symbol-lock"
        fun paintLock() {
            val tint = if (locked) palette.accentText else palette.text
            GlyphDrawable.apply(lockCell, if (locked) PanelIcon.Glyph.LOCK else PanelIcon.Glyph.UNLOCK, tint)
        }
        locked = listener.isSymbolLocked()
        paintLock()
        lockCell.setOnClickListener {
            KeyHaptics.tap(it)
            locked = !locked
            listener.onSymbolLockChanged(locked)
            paintLock()
        }
        bottom.addView(lockCell, LinearLayout.LayoutParams(unit.toInt(), rowHeight))

        bottom.addView(
            keyCell(context, "⌫", palette.keySpecial, palette.text).apply {
                contentDescription = "symbol-backspace"
                KeyIcons.applyBackspace(this, palette.text)
                val gesture = listener.symbolBackspaceGesture()
                BackspaceGesture(gesture, gesture::longPressDelayMs, feedback = { KeyHaptics.tap(this) })
                    .attach(this)
            },
            LinearLayout.LayoutParams((unit * 1.4f).toInt(), rowHeight),
        )
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight))
        return root
    }

    override fun onAttached() {
        selectTab(0)
        listener.onSymbolPageShown(true)
    }

    override fun onDetached() {
        listener.onSymbolPageShown(false)
    }

    private fun commonSymbols(): List<String> = COMMON_DEFAULTS

    private fun selectTab(index: Int) {
        adapter.submit(if (index == 0) commonSymbols() else categories[index - 1].items)
        grid?.scrollToPosition(0)
        grid?.post { scrollThumb?.flash() } // 换类别 / 打开面板时亮一下，提示这里能上下滑
        val palette = Palette.current
        val density = grid?.resources?.displayMetrics?.density ?: 1f
        tabsRow?.let { tabs ->
            for (i in 0 until tabs.childCount) {
                tabs.getChildAt(i).background = if (i == index) {
                    GradientDrawable().apply {
                        cornerRadius = 16 * density
                        setColor(palette.accentSoft)
                    }
                } else {
                    null
                }
            }
        }
    }

    private class SymbolAdapter(
        private val onClick: (String) -> Unit,
    ) : RecyclerView.Adapter<SymbolAdapter.Holder>() {

        private var items: List<String> = emptyList()

        @Suppress("NotifyDataSetChanged")
        fun submit(newItems: List<String>) {
            items = newItems
            notifyDataSetChanged()
        }

        class Holder(val view: TextView) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val density = parent.resources.displayMetrics.density
            val palette = Palette.current
            val tv = keyCell(parent.context, "", palette.key, palette.text).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    (KeyboardDimens.rowHeightDp * density).toInt(),
                )
            }
            return Holder(tv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val symbol = items[position]
            holder.view.text = symbol
            holder.view.setOnClickListener {
                KeyHaptics.tap(it)
                onClick(symbol)
            }
        }

        override fun getItemCount() = items.size
    }
}

/**
 * 键帽样式的格子：圆角键面 + 键缝 + 按下高亮，与 KeyboardView 画出来的键一致
 * （圆角、横纵键缝都取 [KeyboardDimens] 同一组数）。符号页与编辑面板共用。
 */
internal fun keyCell(context: Context, label: String, face: Int, textColor: Int): TextView {
    val density = context.resources.displayMetrics.density
    val gapH = (KeyboardDimens.KEY_GAP_DP * density).toInt()
    val gapV = KeyboardDimens.keyGapVPx(density).toInt()
    fun face(color: Int) = InsetDrawable(
        GradientDrawable().apply {
            cornerRadius = KeyboardDimens.KEY_RADIUS_DP * density
            setColor(color)
        },
        gapH, gapV, gapH, gapV,
    )
    return TextView(context).apply {
        text = label
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(textColor)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), face(Palette.current.keyPressed))
            addState(intArrayOf(), face(face))
        }
    }
}
