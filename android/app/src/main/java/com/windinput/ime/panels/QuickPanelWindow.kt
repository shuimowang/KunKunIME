package com.windinput.ime.panels

import com.windinput.ime.keyboard.setHapticClick
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.windinput.ime.core.EngineCmd
import com.windinput.ime.core.UiEvent
import com.windinput.ime.keyboard.BarView
import com.windinput.ime.keyboard.InputWindow
import com.windinput.ime.keyboard.Palette
import com.windinput.ime.keyboard.PanelIcon
import com.windinput.ime.keyboard.ToolbarItem
import com.windinput.ime.keyboard.ToolbarPinEdit

/**
 * 快捷功能面板：主流输入法「更多」页的形态——**4 列图标格子**。切方案在「键盘」格打开的
 * 键盘选择面板（[KeyboardSelectWindow]）里，此前顶上那行方案分段选择器已由它取代。
 *
 * 格子统一：上图标、下文字、等宽等高，开关类格子用强调色表示"开着"。搜狗/百度/Gboard
 * 的功能页都是这一套，用户不用学。此前是三段标签 + 大小不一的文字 chip，看着像设置页
 * 而不是键盘面板。
 *
 * **界面完全由本端定义，不镜像桌面菜单树。** Rust 侧只提供 [EngineCmd]（能调到什么）与
 * [UiEvent.Status]（当前是什么），排布与命名归这里。开关的**显示态一律来自引擎推送**
 * （[updateStatus]），本地不记一份：方案切换会改写模式短称，密码框会强制英文，
 * 都发生在引擎内部，本地自记必然漂移。
 */
class QuickPanelWindow(
    private val listener: Listener,
) : InputWindow() {

    interface Listener {
        fun onQuickCommand(cmd: EngineCmd)

        /** 打开某个面板（表情/剪贴板/文本编辑/键盘选择） */
        fun onOpenPanel(target: Target)

        fun onOpenSettings()

        fun onBackToKeyboard()

        /** 弹系统输入法选择器 */
        fun onSwitchInputMethod()

        /** 系统里有没有可转发的语音输入法（没有就不摆入口，免得点不通） */
        fun voiceInputAvailable(): Boolean

        /** 切到系统语音输入法 */
        fun onVoiceInput()

        /** 用户手动开的无痕（本次键盘服务内有效，不落盘） */
        fun incognito(): Boolean

        /** 当前输入框自己要求无痕（密码框、应用置了 NO_PERSONALIZED_LEARNING），手动关不掉 */
        fun incognitoForced(): Boolean

        fun onToggleIncognito()

        /** 单手模式开着（键盘收窄靠向一侧） */
        fun oneHand(): Boolean

        /** 单手模式开关：打开到上次那一侧，再点关掉 */
        fun onToggleOneHand()

        /** 工具栏上常驻的按钮（顺序即排列） */
        fun toolbarPins(): List<ToolbarItem>

        /** 编辑工具栏后保存 */
        fun onToolbarPinsChanged(pins: List<ToolbarItem>)

        /** 当前方案能进临时拼音（码表方案） */
        fun tempPinyinAvailable(): Boolean

        /** 面板里点了一个也能常驻工具栏的入口（临时拼音 / 快捷输入），与工具栏上点它同一处理 */
        fun onToolbarItemFromPanel(item: ToolbarItem)
    }

    enum class Target { EMOJI, CLIPBOARD, TEXT_EDIT, KEYBOARD_SELECT }

    private companion object {
        const val COLUMNS = 4
        const val TILE_HEIGHT_DP = 64
        const val MIN_TILE_HEIGHT_DP = 40
        const val EDIT_MIN_TILE_HEIGHT_DP = 54

        /** 编辑工具栏时顶部「工具栏预览」行的高度（dp） */
        const val PREVIEW_ROW_DP = 50

        const val EDIT_HINT = "按住拖动调顺序，点 ⊖ 移除，点 ＋ 加入"
    }

    /**
     * 编辑工具栏中：顶部出工具栏预览（拖动换位、⊖ 移除）；能常驻的格子右上角出 ＋（未常驻）
     * / ✓（已常驻），点格子改为加入 / 移除，不能常驻的格子（中文标点等开关）变淡、点了无效。
     * 顶栏右侧「编辑工具栏 / 完成」切换。
     */
    private var editingPins = false
    private var editButton: TextView? = null
    private var editHint: TextView? = null
    private var tileGrid: GridLayout? = null

    /** 编辑工具栏时的工具栏预览：常驻项按工具栏顺序排成 [ToolbarItem.MAX_PINS] 个等宽槽 */
    private var previewRow: LinearLayout? = null
    private var status: UiEvent.Status? = null

    override fun onCreateView(context: Context): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(4), dp(8))
        }

        val hint = TextView(context).apply {
            text = EDIT_HINT
            textSize = 12f
            setTextColor(Palette.current.hint)
            visibility = View.GONE
        }
        editHint = hint
        column.addView(
            hint,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            },
        )

        val preview = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            // 被拖的项放大浮起，别被行边裁掉
            clipChildren = false
            clipToPadding = false
        }
        previewRow = preview
        column.clipChildren = false
        column.addView(
            preview,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(PREVIEW_ROW_DP)).apply {
                bottomMargin = dp(8)
            },
        )

        val grid = GridLayout(context).apply { columnCount = COLUMNS }
        tileGrid = grid
        column.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        rebuildTiles(context)

        // 面板高度固定（= 条区 + 键盘），格子多了要能滚
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(column)
        }
        val edit = PanelScaffold.headerButton(context, "编辑工具栏") { setEditing(context, !editingPins) }
        editButton = edit
        return PanelScaffold.build(
            context = context,
            title = "快捷功能",
            onBack = listener::onBackToKeyboard,
            content = scroll,
            rightView = edit,
        )
    }

    private fun setEditing(context: Context, on: Boolean) {
        editingPins = on
        editButton?.text = if (on) "完成" else "编辑工具栏"
        editHint?.visibility = if (on) View.VISIBLE else View.GONE
        resetHint()
        rebuildTiles(context)
    }

    private fun resetHint() {
        editHint?.apply {
            text = EDIT_HINT
            setTextColor(Palette.current.hint)
        }
    }

    /** 离开面板就退出编辑：下次进来是正常的快捷面板 */
    override fun onDetached() {
        val ctx = tileGrid?.context ?: return
        if (editingPins) setEditing(ctx, false)
    }

    /** 网格里点能常驻的格子：已常驻就移除，否则加入；满了不加，提示行改说原因 */
    private fun togglePin(context: Context, item: ToolbarItem) {
        val pins = listener.toolbarPins()
        val next = if (item in pins) ToolbarPinEdit.remove(pins, item) else ToolbarPinEdit.add(pins, item)
        if (next == null) {
            editHint?.apply {
                text = "工具栏最多 ${ToolbarItem.MAX_PINS} 个，先移除一个"
                setTextColor(Palette.current.accentText)
            }
            return
        }
        applyPins(context, next)
    }

    private fun applyPins(context: Context, next: List<ToolbarItem>) {
        listener.onToolbarPinsChanged(next)
        resetHint()
        rebuildTiles(context)
    }

    /**
     * 引擎推送的模式真值到达：刷新开关选中态。
     *
     * 视图未创建时只存值——面板是懒创建的，首次打开时 [onCreateView] 会用这份最新值构建。
     */
    fun updateStatus(newStatus: UiEvent.Status) {
        status = newStatus
        val ctx = tileGrid?.context ?: return
        rebuildTiles(ctx)
    }

    /** 每次进入面板刷新一次：方案可能在别处（键盘快捷键/设置页）被切过 */
    override fun onAttached() = refresh()

    /**
     * 重画功能格子（临时拼音格随当前方案出没）。切方案在后台做，引擎推状态时后台任务还没收尾、
     * 方案列表还是切之前的缓存——所以后台切完（忙状态解除）时 Service 再叫一次这里。
     */
    fun refresh() {
        val ctx = tileGrid?.context ?: return
        rebuildTiles(ctx)
    }

    /** 功能格子：开关在前（带选中态），入口在后 */
    private fun rebuildTiles(context: Context) {
        val grid = tileGrid ?: return
        grid.removeAllViews()
        rebuildPreview(context)
        val st = status
        val tiles = mutableListOf<View>()
        val pins = listener.toolbarPins()
        val full = pins.size >= ToolbarItem.MAX_PINS

        /**
         * [icon]：字（「，」「Ａ」这类本身就是字符的）或矢量图标 [PanelIcon.Glyph]。
         * [pin]：这一格能常驻工具栏时对应的项（编辑工具栏时据此出 ＋ / ✓）
         */
        fun add(icon: Any, label: String, selected: Boolean? = null, pin: ToolbarItem? = null, onClick: () -> Unit) {
            tiles += when {
                !editingPins -> tile(context, icon, label, selected, onClick)
                pin == null -> tile(context, icon, label, selected) {}.apply { alpha = 0.35f }
                else -> pinBadged(context, tile(context, icon, label, selected) { togglePin(context, pin) }, pin, pin in pins, full)
            }
        }
        add("，", "中文标点", selected = st?.chinesePunct ?: true) {
            listener.onQuickCommand(EngineCmd.TogglePunct)
        }
        add("Ａ", "全角", selected = st?.fullWidth ?: false) {
            listener.onQuickCommand(EngineCmd.ToggleWidth)
        }
        // 简繁格仅在用户启用简繁功能后出现（与桌面工具栏同一判据）
        if (st?.s2tShown != false) {
            add("繁", "简入繁出", selected = st?.s2tEnabled ?: false) {
                listener.onQuickCommand(EngineCmd.ToggleS2t)
            }
        }
        // 无痕是键盘自己的状态（引擎只管「这次学不学」），选中态取本端，不等引擎推送。
        // 选中态显示**实际**是否无痕：密码框里条区亮着「🕶 无痕」，这格却灭着，就对不上了
        val forced = listener.incognitoForced()
        add(PanelIcon.Glyph.INCOGNITO, if (forced) "无痕·本框" else "无痕", selected = listener.incognito() || forced, pin = ToolbarItem.INCOGNITO) {
            listener.onToggleIncognito()
            rebuildTiles(context)
        }
        // 临时拼音只对码表方案（五笔）有意义：拼音方案本来就在打拼音
        if (listener.tempPinyinAvailable()) {
            add("拼", "临时拼音", pin = ToolbarItem.TEMP_PINYIN) { listener.onToolbarItemFromPanel(ToolbarItem.TEMP_PINYIN) }
        }
        add(PanelIcon.Glyph.QUICK_INPUT, "快捷输入", pin = ToolbarItem.QUICK_INPUT) { listener.onToolbarItemFromPanel(ToolbarItem.QUICK_INPUT) }
        add(PanelIcon.Glyph.KEYBOARD, "键盘", pin = ToolbarItem.KEYBOARD) { listener.onOpenPanel(Target.KEYBOARD_SELECT) }
        add(PanelIcon.Glyph.EMOJI, "表情", pin = ToolbarItem.EMOJI) { listener.onOpenPanel(Target.EMOJI) }
        add(PanelIcon.Glyph.CLIPBOARD, "剪贴板", pin = ToolbarItem.CLIPBOARD) { listener.onOpenPanel(Target.CLIPBOARD) }
        add(PanelIcon.Glyph.TEXT_EDIT, "文本编辑", pin = ToolbarItem.TEXT_EDIT) { listener.onOpenPanel(Target.TEXT_EDIT) }
        if (listener.voiceInputAvailable()) {
            add(PanelIcon.Glyph.MIC, "语音输入", pin = ToolbarItem.VOICE) { listener.onVoiceInput() }
        }
        add(PanelIcon.Glyph.GLOBE, "切换输入法", pin = ToolbarItem.SWITCH_IME) { listener.onSwitchInputMethod() }
        add(PanelIcon.Glyph.ONE_HAND, "单手", selected = listener.oneHand(), pin = ToolbarItem.ONE_HAND) {
            listener.onToggleOneHand() // 选中态由 Service 改完配置后经 refresh 重画
        }
        // 「设置」固定在最后一格：主流输入法都把它放末尾，新加的格子插在它前面，它的位置不随之变
        add(PanelIcon.Glyph.SETTINGS, "设置", pin = ToolbarItem.SETTINGS) { listener.onOpenSettings() }
        val heightPx = tileHeightPx(context, rows = (tiles.size + COLUMNS - 1) / COLUMNS)
        for (t in tiles) grid.addView(t, cell(context, heightPx))
    }

    /**
     * 工具栏预览：只在编辑工具栏时出现。常驻项按工具栏上的顺序、用与工具栏同样的图标排成一行，
     * 右上角 ⊖ 移除，按住左右拖动换位；不足 [ToolbarItem.MAX_PINS] 个补虚线空位，让每格宽度稳定。
     */
    private fun rebuildPreview(context: Context) {
        val row = previewRow ?: return
        row.removeAllViews()
        row.visibility = if (editingPins) View.VISIBLE else View.GONE
        if (!editingPins) return
        val density = context.resources.displayMetrics.density
        val p = Palette.current
        val pins = listener.toolbarPins()
        fun slot() = LinearLayout.LayoutParams(0, MATCH, 1f).apply { marginEnd = (6 * density).toInt() }
        if (pins.isEmpty()) {
            row.addView(
                TextView(context).apply {
                    text = "点下面带 ＋ 的功能加到工具栏"
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(p.hint)
                    background = dashedBox(density, p.hint)
                },
                slot(),
            )
            return
        }
        pins.forEachIndexed { i, item -> row.addView(previewCell(context, row, pins, i), slot()) }
        repeat(ToolbarItem.MAX_PINS - pins.size) {
            row.addView(View(context).apply { background = dashedBox(density, p.hint) }, slot())
        }
    }

    private fun dashedBox(density: Float, color: Int) = GradientDrawable().apply {
        cornerRadius = 10 * density
        setColor(android.graphics.Color.TRANSPARENT)
        setStroke((1.2f * density).toInt(), color, 4 * density, 3 * density)
    }

    /** 预览里的一个常驻项：图标与真工具栏（BarView.setPinned）一致，右上角 ⊖ */
    @SuppressLint("ClickableViewAccessibility")
    private fun previewCell(context: Context, row: LinearLayout, pins: List<ToolbarItem>, index: Int): View {
        val density = context.resources.displayMetrics.density
        val p = Palette.current
        val item = pins[index]
        val icon: View = item.glyph?.let { g ->
            PanelIcon(context).apply {
                glyph = g
                tint = p.icon
                boxDp = BarView.TOOLBAR_ICON_BOX_DP
                decorative = true
            }
        } ?: TextView(context).apply {
            text = item.text
            textSize = 17f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(p.icon)
        }
        val cell = FrameLayout(context).apply {
            contentDescription = "工具栏·${item.label}"
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(p.key)
            }
            addView(icon, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(
                badge(context, "−", p.hint, p.surface, "移除 ${item.label}") {
                    applyPins(context, ToolbarPinEdit.remove(listener.toolbarPins(), item))
                },
                badgeParams(density),
            )
        }
        // 按下即拖：被拖项浮起跟手，其余项让位；抬手落位才保存。⊖ 自己可点、先吃掉触摸，不会触发拖动
        var downX = 0f
        var target = index
        fun settle() {
            for (j in 0 until row.childCount) {
                row.getChildAt(j).animate().translationX(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
            }
            cell.translationZ = 0f
        }
        cell.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    target = index
                    // 外层 ScrollView 会把横竖拖动当成滚动抢走
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    v.translationZ = 8 * density
                    v.animate().scaleX(1.08f).scaleY(1.08f).alpha(0.92f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    // 槽位间距：预览行恒有 MAX_PINS 个子视图（常驻 + 空位），取相邻两个的左边距差
                    val pitch = (row.getChildAt(1)?.left ?: v.width).toFloat() - row.getChildAt(0).left
                    val dx = ev.rawX - downX
                    v.translationX = dx
                    val next = ToolbarPinEdit.dropIndex(v.left + v.width / 2f + dx, pitch, pins.size)
                    if (next != target) {
                        target = next
                        for (j in pins.indices) {
                            if (j == index) continue
                            row.getChildAt(j).animate()
                                .translationX(ToolbarPinEdit.shiftOf(j, index, target) * pitch)
                                .setDuration(120).start()
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    if (target != index) {
                        // 推迟到本次触摸分发之后再重建：当场 removeAllViews 会给正在分发的这一格补发 CANCEL
                        val next = ToolbarPinEdit.move(pins, index, target)
                        row.post { applyPins(context, next) }
                    } else {
                        settle()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                    settle()
                }
            }
            true
        }
        return cell
    }

    /** 格子 / 预览项右上角的小圆钮（⊖ ＋ ✓）：自己可点，读屏念 [desc]（e2e 也按它找） */
    private fun badge(context: Context, mark: String, bg: Int, fg: Int, desc: String, onClick: () -> Unit) =
        TextView(context).apply {
            text = mark
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(fg)
            contentDescription = desc
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bg)
            }
            setHapticClick { onClick() }
        }

    private fun badgeParams(density: Float) =
        FrameLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt(), Gravity.TOP or Gravity.END).apply {
            topMargin = (3 * density).toInt()
            marginEnd = (3 * density).toInt()
        }

    /**
     * 编辑工具栏时网格里能常驻的格子：右上角 ＋（未常驻）/ ✓（已常驻，点了移除）。
     * 已满时 ＋ 变灰，点了仍走 [togglePin]，由它提示为什么加不进去。
     */
    private fun pinBadged(context: Context, body: View, item: ToolbarItem, pinned: Boolean, full: Boolean): View {
        val density = context.resources.displayMetrics.density
        val p = Palette.current
        val mark = when {
            pinned -> badge(context, "✓", p.accent, p.onAccent, "已加入 ${item.label}") { togglePin(context, item) }
            else -> badge(context, "+", if (full) p.hint else p.accent, if (full) p.surface else p.onAccent, "加入 ${item.label}") {
                togglePin(context, item)
            }.apply { if (full) alpha = 0.5f }
        }
        return FrameLayout(context).apply {
            addView(body, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(mark, badgeParams(density))
        }
    }

    /**
     * 格子高度：所有行在键盘区里一屏放下（此前固定 64dp，第三行被底边裁掉一大半，「切换输入法」
     * 「设置」只露出图标顶，而滚动条是隐藏的，用户不知道还能滑）。上限是原来的 64dp，下限 40dp
     * ——再矮图标与字就叠了，那时宁可滚动。
     */
    private fun tileHeightPx(context: Context, rows: Int): Int {
        val density = context.resources.displayMetrics.density
        // 上下内边距；编辑工具栏时再多出提示行和预览行
        val reserved = (8 + 8 + if (editingPins) PREVIEW_ROW_DP + 8 + 28 else 0) * density
        val perRow = (PanelScaffold.keyboardHeightPx(context) - reserved) / rows.coerceAtLeast(1) - 6 * density
        // 编辑工具栏时多了两行，一屏放不下就滚，不再往下压：压到 40dp 标签会被裁
        val min = if (editingPins) EDIT_MIN_TILE_HEIGHT_DP else MIN_TILE_HEIGHT_DP
        return perRow.coerceIn(min * density, TILE_HEIGHT_DP * density).toInt()
    }

    private fun cell(context: Context, heightPx: Int) = GridLayout.LayoutParams().apply {
        val density = context.resources.displayMetrics.density
        width = 0
        height = heightPx
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(0, 0, (6 * density).toInt(), (6 * density).toInt())
    }

    /**
     * 一个格子：上图标下文字。[selected] 为 null = 纯入口（无选中态）；
     * true/false = 开关，开着用强调色实底。
     */
    private fun tile(
        context: Context,
        icon: Any,
        label: String,
        selected: Boolean?,
        onClick: () -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        val p = Palette.current
        val on = selected == true
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(if (on) p.accent else p.key)
            }
            val fg = if (on) p.onAccent else p.text
            // 矢量图标而不是 emoji：emoji 各机型长相不一、颜色写死，跟着选中态变不了色
            val size = (26 * density).toInt()
            addView(glyphOrBadge(context, icon, fg, size), LinearLayout.LayoutParams(size, size))
            addView(
                TextView(context).apply {
                    text = label
                    textSize = 11f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    setTextColor(if (on) p.onAccent else p.hint)
                    setPadding(0, (2 * density).toInt(), 0, 0)
                },
            )
            setHapticClick { onClick() }
        }
    }

    private val MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT
}

/**
 * 统一的图标视图：矢量图标直接画；本身是字符的（「，」「Ａ」「繁」…）画成同线宽的圆角方框
 * 里放字，与线条风格的矢量图标并排时看着是一套。
 */
internal fun glyphOrBadge(context: Context, icon: Any, color: Int, sizePx: Int): View {
    if (icon is PanelIcon.Glyph) {
        return PanelIcon(context).apply {
            glyph = icon
            tint = color
            scale = 0.86f
            decorative = true
        }
    }
    val density = context.resources.displayMetrics.density
    return TextView(context).apply {
        text = icon.toString()
        textSize = (sizePx / density) * 0.5f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(color)
        background = GradientDrawable().apply {
            cornerRadius = 4 * density
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke((1.6f * density).toInt(), color)
        }
    }
}
