package com.windinput.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.windinput.ime.core.TextSegment

/**
 * 键盘上方的条区，高度恒定 [TOTAL_DP]，三态（候选 / 工具栏 / 剪贴板建议）叠在同一块地方：
 *
 * ```
 * ┌──────────────────────────────┐
 * │ 五 aa                    1/3 │  编码行（KeyboardDimens.barPreeditDp，只在候选态）
 * │ 式  戒  工  ...            ∨ │  候选行
 * ├──────────────────────────────┤
 * │ ⠿          🕶 无痕          ∨ │  或：工具栏，占满整个条区高度、图标居中
 * └──────────────────────────────┘
 * ```
 *
 * **高度为什么必须恒定**：条区高度直接决定 IME 窗口高度。若编码出现时整体变高，
 * 每次起手打字键盘都会向上跳一下、宿主界面跟着 reflow。
 *
 * **编码为什么放在窗口里**：此前编码栏是伸出 IME 窗口顶部的浮层，盖在应用的正文上，
 * 正好挡住正在输入的那一行（真机反馈「特别容易遮挡」）。豆包 / Gboard 都是在条区顶上
 * 留一条细行写编码、候选行紧贴其下，工具栏态则整块条区归图标——条区比单行候选高一点，
 * 换来的是什么都不挡。
 *
 * 主行三态优先级：候选 > 剪贴板建议 > 工具栏；建议条 30s 超时自动消失。
 *
 * **候选展开态**（下方铺开全部候选）主行强制切工具栏：候选已在网格里，条上再画一遍
 * 纯属重复；同时尾格由「收起键盘」变「返回」——展开面板自己没有返回栏，
 * 这一格就是它唯一的退路。
 */
@SuppressLint("ViewConstructor")
class BarView(
    context: Context,
    candidateListener: CandidateBarView.Listener,
    private val listener: Listener,
) : LinearLayout(context) {

    interface Listener {
        fun onToolbarAction(action: ToolbarAction)

        /** 展开/收起候选全览（同一个钮往返） */
        fun onToggleExpanded()

        /** 候选栏滚到接近尾部、且核心那边还有更多：请求续取一段 */
        fun onNeedMoreCandidates()

        fun onPasteSuggestion(text: String)

        /** 工具栏上常驻的功能按钮（见 [setPinned]） */
        fun onToolbarItem(item: ToolbarItem)

        /** 手指按在条区上（[down] = true）/ 离开。联想候选正被滑动、长按时不该被计时收掉 */
        fun onBarTouch(down: Boolean) = Unit

    }

    enum class ToolbarAction { QUICK_PANEL, HIDE_IME }

    companion object {
        /**
         * 条区总高（dp）= 编码行 + 候选行；工具栏态整块归工具栏。恒定不随组合态变化。
         * 两段高度在 [KeyboardDimens]：横屏可用高度只有竖屏的一半不到，条区跟着键高一起收
         * （竖屏 20 + 40，横屏 16 + 34）。旋转会重建输入视图，取值天然跟上。
         */
        val TOTAL_DP: Float get() = KeyboardDimens.barPreeditDp + KeyboardDimens.barMainRowDp

        private const val SUGGESTION_TIMEOUT_MS = 30_000L

        private const val PREPARING_TEXT = "词库准备中…"

        /** 工具栏两端图标格的宽度（dp）：入口在最左、尾格在最右，中间留空 */
        /**
         * 空闲工具栏的图标尺寸，量自小米小爱输入法（1080 宽、2.625 倍屏）：格宽 128px、
         * 宫格 45px / 笑脸 51px，Lucide 的 24 格图标框折合 60px，线宽 5px；
         * 收起箭头 41px 宽，按它自己的比例单独放大。首尾图标中心离屏幕边 91px。
         */
        const val TOOLBAR_ICON_DP = 49
        const val TOOLBAR_ICON_BOX_DP = 21f
        const val TOOLBAR_COLLAPSE_BOX_DP = 27f
        const val TOOLBAR_SIDE_PAD_DP = 10

        /** 圆托直径：比工具栏行（38dp）略小，上下各留 2dp */
        const val TOOLBAR_CHIP_DP = 34f

        /** ViewGroup.LayoutParams.MATCH_PARENT 的短名（类内无法裸用继承来的常量） */
        private const val MATCH = android.view.ViewGroup.LayoutParams.MATCH_PARENT
    }

    private enum class State { TOOLBAR, CANDIDATES, CLIP_SUGGESTION }

    val candidateBar = CandidateBarView(context).apply {
        listener = candidateListener
        onNeedMore = { this@BarView.listener.onNeedMoreCandidates() }
    }

    private val density = resources.displayMetrics.density

    /** 编码行右侧的「页/总页」（设置项 ui.android.candidate_page_no，默认关：主流输入法都不显示） */
    var showPageNo = false

    /** 工具栏图标是否带圆形底托（设置项 ui.android.toolbar_icon_chip，默认关：小米式无底） */
    var iconChip = false
        set(value) {
            field = value
            applyIconChip()
        }

    private fun applyIconChip() {
        val d = if (iconChip) TOOLBAR_CHIP_DP else 0f
        gridIcon.chipDp = d
        trailingIcon.chipDp = d
        for (i in 0 until pinnedRow.childCount) (pinnedRow.getChildAt(i) as? PanelIcon)?.chipDp = d
    }

    // ── 编码行 ───────────────────────────────────────────────────
    // 编码行高度固定（竖屏 20dp、横屏 16dp，见 KeyboardDimens），字号按 dp 而非 sp：
    // 系统字体放大到 1.3 倍以上时 sp 字号会被行高裁掉
    private val modeLabel = TextView(context).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11f)
        includeFontPadding = false
        gravity = Gravity.CENTER
        setTextColor(Palette.current.onAccent)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 3 * density
            setColor(Palette.current.accent)
        }
        setPadding(dp(4), 0, dp(4), 0)
    }
    private val preeditText = TextView(context).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16f)
        includeFontPadding = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.START // 长编码保留末尾（正在打的那几码）
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(Palette.current.preedit)
        setPadding(dp(6), 0, dp(6), 0)
        // 行高由设备字体决定（MiSans 等比 Roboto 高），固定 16dp 在 19dp 的行里会裁掉 g/j/p/q/y 的下伸：
        // 按字体实际的 ascent+descent 收字号，让整行字形（含下伸）落在行内、上下各留 1dp
        val fm = paint.fontMetrics
        val lineEm = (fm.descent - fm.ascent) / paint.textSize
        val fit = (KeyboardDimens.barPreeditDp - 2f) / lineEm
        if (fit < 16f) setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, fit)
    }
    private val pagerText = TextView(context).apply {
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 11f)
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(Palette.current.hint)
        setPadding(dp(6), 0, dp(12), 0)
    }
    private val preeditRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // 横屏编码行只有 16dp：去掉顶部内边距、短称收矮，否则短称被裁、拼音的 g/j/p/q/y 下伸被裁
        val compact = KeyboardDimens.barPreeditDp < 20f
        setPadding(dp(10), 0, 0, 0)
        setBackgroundColor(Palette.current.background)
        addView(modeLabel, LayoutParams(LayoutParams.WRAP_CONTENT, dp(if (compact) 13 else 15)))
        addView(preeditText, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(pagerText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    /** 核心构造期 / 切方案现编词库时的提示（显示在工具栏中间，见 [refreshCenterNote]） */
    private var preparing = false
    private var pendingInputCount = 0
    private var failure: String? = null

    /** 当前编码（展开态下候选行让位给工具栏，编码改在工具栏中间显示） */
    private var currentPreedit = ""

    private var incognito = false

    // ── 主行 ─────────────────────────────────────────────────────
    private val mainRow: FrameLayout
    private val candidateRow: View
    private val toolbar: View
    private val suggestionRow: LinearLayout
    private val suggestionText: TextView

    private var state = State.TOOLBAR
    private var hasCandidates = false

    /**
     * 条区该停在候选态吗：有候选，或者有编码（生僻码、打错码零候选时编码行也要让人看到）。
     * 所有「回候选态还是回工具栏」的判断都用它，别再单看 [hasCandidates]。
     * 准备词库时例外：提示写在工具栏中间，候选态会把它藏住。
     */
    private val wantsCandidateRow: Boolean
        get() = !preparing && failure == null && (hasCandidates || currentPreedit.isNotEmpty())

    /** 候选是否已在下方面板铺开（见 [setExpanded]） */
    private var expanded = false

    /** 候选态尾格：展开全部候选 */
    private val expandButton = PanelIcon(context).apply {
        glyph = PanelIcon.Glyph.EXPAND
        setHapticClick { listener.onToggleExpanded() }
    }

    /**
     * 工具栏尾格：与 [expandButton] 同一个屏幕位置（条区最右）。
     *
     * 常态是「收起键盘」；候选展开时变「返回」——用户看到的就是「原下拉钮的位置
     * 变成了返回」。两个动作共用一格而不是并排两格：这一格的语义恒为
     * 「离开当前这层」，并排反而要用户分辨两个下箭头。
     */
    /**
     * 无痕标记：放在空闲工具栏中间那块空地上。无痕是「看不见的状态」，忘了关就一直学不会，
     * 必须常驻可见；只在工具栏态显示（打字时条区归候选）。
     */
    private val incognitoLabel = TextView(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        textSize = 13f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(Palette.current.hint)
    }

    /** 无痕时写在字前面的图标（与快捷面板「无痕」格同一个矢量图标，不用 emoji） */
    private val incognitoIcon = PanelIcon(context).apply {
        glyph = PanelIcon.Glyph.INCOGNITO
        tint = Palette.current.hint
        scale = 0.9f
        decorative = true
        visibility = GONE
    }

    /**
     * 中间那一格（图标 + 字）。INVISIBLE 不是 GONE：它兼做中间的弹性占位，GONE 掉尾格就挤到左边去了
     */
    private val centerNote = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        addView(incognitoIcon, LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(4) })
        addView(incognitoLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        visibility = INVISIBLE
    }

    /** 工具栏上常驻的功能按钮，排在「快捷功能」之后 */
    private val pinnedRow = LinearLayout(context).apply { orientation = HORIZONTAL }

    /** 设置常驻按钮（顺序即排列顺序）。快捷面板里编辑完、每次弹键盘时由 Service 调 */
    fun setPinned(items: List<ToolbarItem>) {
        pinnedRow.removeAllViews()
        for (item in items) {
            val glyph = item.glyph
            val view: View = if (glyph != null) {
                icon(glyph) { listener.onToolbarItem(item) }.apply {
                    boxDp = TOOLBAR_ICON_BOX_DP
                    chipDp = if (iconChip) TOOLBAR_CHIP_DP else 0f
                }
            } else {
                TextView(context).apply {
                    text = item.text
                    textSize = 17f
                    gravity = Gravity.CENTER
                    setTextColor(Palette.current.icon)
                    setOnClickListener {
                        KeyHaptics.tap(it)
                        listener.onToolbarItem(item)
                    }
                }
            }
            view.contentDescription = item.label
            pinnedRow.addView(view, LayoutParams(dp(TOOLBAR_ICON_DP), LayoutParams.MATCH_PARENT))
        }
    }

    /** 学习是否暂停（手动无痕或输入框要求），见 [incognitoLabel] */
    fun setIncognito(on: Boolean) {
        incognito = on
        refreshCenterNote()
    }

    /**
     * 工具栏中间那一格写什么：准备词库 > 展开态的编码 > 无痕。
     * 三者都只在工具栏态可见，共用一格免得互相挤。
     */
    private fun refreshCenterNote() {
        val text = when {
            failure != null -> failure!!
            preparing && pendingInputCount > 0 -> "准备词库，已暂存 $pendingInputCount 次输入"
            preparing -> PREPARING_TEXT
            expanded && currentPreedit.isNotEmpty() -> currentPreedit
            incognito -> "无痕"
            else -> ""
        }
        incognitoLabel.text = text
        incognitoLabel.setTextColor(if (preparing) Palette.current.preedit else Palette.current.hint)
        incognitoIcon.visibility = if (text == "无痕" && !preparing) VISIBLE else GONE
        centerNote.visibility = if (text.isEmpty()) INVISIBLE else VISIBLE
        // Give the transient setup/error message the center of the bar rather than squeezing
        // it between pinned shortcuts. The quick-panel and dismiss buttons remain available.
        pinnedRow.visibility = if (preparing || failure != null) GONE else VISIBLE
    }

    private val trailingIcon = PanelIcon(context).apply {
        glyph = PanelIcon.Glyph.COLLAPSE
        boxDp = TOOLBAR_COLLAPSE_BOX_DP
        setHapticClick {
            if (expanded) listener.onToggleExpanded() else listener.onToolbarAction(ToolbarAction.HIDE_IME)
        }
    }

    private val gridIcon = icon(PanelIcon.Glyph.GRID) { listener.onToolbarAction(ToolbarAction.QUICK_PANEL) }
        .apply { boxDp = TOOLBAR_ICON_BOX_DP }

    private val dismissSuggestion = Runnable { hideClipSuggestion() }

    init {
        orientation = VERTICAL

        // ── 候选态：编码行 + 候选行（含展开钮） ──
        val candidateLine = LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(
                candidateBar,
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
            )
            addView(
                expandButton,
                LayoutParams(dp(44), LayoutParams.MATCH_PARENT),
            )
        }
        candidateRow = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(Palette.current.background)
            addView(preeditRow, LayoutParams(MATCH, dp(KeyboardDimens.barPreeditDp)))
            addView(candidateLine, LayoutParams(MATCH, 0, 1f))
        }

        // ── 主行：剪贴板建议 ──
        suggestionText = TextView(context).apply {
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Palette.current.text)
        }
        suggestionRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(Palette.current.background)
            addView(
                PanelIcon(context).apply {
                    glyph = PanelIcon.Glyph.CLIPBOARD
                    tint = Palette.current.hint
                    decorative = true
                },
                LayoutParams(dp(40), LayoutParams.MATCH_PARENT),
            )
            addView(suggestionText, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(
                icon(PanelIcon.Glyph.CLOSE) { hideClipSuggestion() },
                LayoutParams(dp(44), LayoutParams.MATCH_PARENT),
            )
        }

        // ── 主行：工具栏 ──
        toolbar = buildToolbar()

        mainRow = FrameLayout(context).apply {
            addView(toolbar, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(candidateRow, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(suggestionRow, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        addView(mainRow, LayoutParams(MATCH, MATCH))
        applyState(State.TOOLBAR)
    }

    /** 条区自身高度写死为 [TOTAL_DP]——它是"总高恒定"这条规矩的落点 */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> listener.onBarTouch(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> listener.onBarTouch(false)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(KeyboardDimens.barHeightPx(density), MeasureSpec.EXACTLY),
        )
    }

    /**
     * 候选帧到达：更新编码栏与候选栏。
     *
     * 编码栏的显隐只看 [preedit] 是否为空，**不看候选是否为空**：有编码但零候选
     * （生僻码、打错码）时编码栏仍要显示，否则用户看不到自己打了什么。
     */
    fun setCandidateFrame(
        items: List<String>,
        preedit: String,
        modeLabel: String,
        page: Int,
        totalPages: Int,
        comments: List<String> = emptyList(),
        segments: List<List<TextSegment>> = emptyList(),
    ) {
        candidateBar.setCandidates(items, comments, segments)
        hasCandidates = items.isNotEmpty()

        currentPreedit = preedit
        if (preedit.isNotEmpty() && pendingInputCount == 0) preparing = false
        preeditText.text = preedit
        this.modeLabel.text = modeLabel
        this.modeLabel.visibility = if (modeLabel.isEmpty() || preedit.isEmpty()) GONE else VISIBLE
        pagerText.text = if (showPageNo && preedit.isNotEmpty() && totalPages > 1) "$page/$totalPages" else ""
        refreshCenterNote()

        // 展开态下主行归工具栏管：候选正在下方网格里铺开，条上不再重复一份
        if (expanded) {
            // 但 hasCandidates 已更新——收起时要据此决定回候选态还是工具栏态
            return
        }
        // 有编码没候选（生僻码、打错码）也停在候选态：编码行要让用户看到自己打了什么
        if (wantsCandidateRow) {
            applyState(State.CANDIDATES)
        } else if (state == State.CANDIDATES) {
            applyState(State.TOOLBAR)
        }
    }

    /** 续取到货：只把新的一段交给候选栏（不重量已有项，见 CandidateBarView 的说明） */
    /** 核心扩容重建后整份替换（保留滚动位置），见 [CandidateBarView.replaceCandidates] */
    fun replaceCandidates(
        items: List<String>,
        comments: List<String>,
        segments: List<List<TextSegment>> = emptyList(),
    ) {
        candidateBar.replaceCandidates(items, comments, segments)
        hasCandidates = items.isNotEmpty()
    }

    fun appendCandidates(
        more: List<String>,
        moreComments: List<String> = emptyList(),
        moreSegments: List<List<TextSegment>> = emptyList(),
    ) {
        if (more.isEmpty()) return
        candidateBar.appendCandidates(more, moreComments, moreSegments)
        hasCandidates = true
    }

    /**
     * 候选全览面板的开合：条区随之在「候选行」与「工具栏（快捷模式）」之间切换，
     * 尾格同步在「收起键盘」与「返回」之间切换。
     */
    fun setExpanded(expanded: Boolean) {
        this.expanded = expanded
        trailingIcon.glyph =
            if (expanded) PanelIcon.Glyph.BACK else PanelIcon.Glyph.COLLAPSE
        refreshCenterNote()
        applyState(
            when {
                expanded -> State.TOOLBAR
                wantsCandidateRow -> State.CANDIDATES
                else -> State.TOOLBAR
            },
        )
    }

    /**
     * 核心尚在后台构造 / 切方案现编词库：工具栏中间写一行提示。
     *
     * 不弹 Toast、不禁用键盘——首次初始化期间会暂存按键，就绪后继续生成中文候选。
     * 已暂存的数量由 [setPendingInputCount] 更新，让用户知道刚才的按键已经接收。
     */
    fun setPreparing(preparing: Boolean) {
        this.preparing = preparing
        if (!preparing) pendingInputCount = 0
        refreshCenterNote()
        // 准备中的提示在工具栏上：联想候选还挂着时也得让出来，否则提示画在被藏住的工具栏里
        if (!expanded && state != State.CLIP_SUGGESTION) {
            applyState(if (wantsCandidateRow) State.CANDIDATES else State.TOOLBAR)
        }
    }

    fun setPendingInputCount(count: Int) {
        pendingInputCount = count.coerceAtLeast(0)
        refreshCenterNote()
    }

    /** A missing or failed native core must never look like a working Chinese IME. */
    fun setFailure(message: String?) {
        failure = message
        if (message != null) {
            preparing = false
            pendingInputCount = 0
        }
        refreshCenterNote()
        if (message != null) applyState(State.TOOLBAR)
    }

    /** 复制新内容后的建议条（候选优先级更高，仅空闲时展示） */
    fun showClipSuggestion(text: String) {
        if (wantsCandidateRow) return
        suggestionText.text = text
        suggestionText.setHapticClick {
            hideClipSuggestion()
            listener.onPasteSuggestion(text)
        }
        applyState(State.CLIP_SUGGESTION)
        removeCallbacks(dismissSuggestion)
        postDelayed(dismissSuggestion, SUGGESTION_TIMEOUT_MS)
    }

    private fun hideClipSuggestion() {
        removeCallbacks(dismissSuggestion)
        if (state == State.CLIP_SUGGESTION) {
            applyState(if (wantsCandidateRow) State.CANDIDATES else State.TOOLBAR)
        }
    }

    private fun applyState(newState: State) {
        state = newState
        toolbar.visibility = if (newState == State.TOOLBAR) VISIBLE else GONE
        candidateRow.visibility = if (newState == State.CANDIDATES) VISIBLE else GONE
        suggestionRow.visibility = if (newState == State.CLIP_SUGGESTION) VISIBLE else GONE
    }

    private fun dp(value: Float): Int = (value * density).toInt()

    private fun dp(value: Int): Int = (value * density).toInt()

    /**
     * 空闲工具栏，按大众布局（豆包 / 微信，docs/android-ime-features.md §14.1）只放两格：
     * 左端是快捷面板入口（表情、剪贴板、文本编辑、设置都在面板里），右端是尾格
     * （见 [trailingIcon]），中间留空。此前常驻 6 个等宽图标，空闲时满屏是按钮。
     */
    private fun buildToolbar(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setBackgroundColor(Palette.current.background)
        setPadding(dp(TOOLBAR_SIDE_PAD_DP), 0, dp(TOOLBAR_SIDE_PAD_DP), 0)
        addView(gridIcon, LayoutParams(dp(TOOLBAR_ICON_DP), LayoutParams.MATCH_PARENT))
        addView(pinnedRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(centerNote, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(trailingIcon, LayoutParams(dp(TOOLBAR_ICON_DP), LayoutParams.MATCH_PARENT))
    }

    private fun icon(glyph: PanelIcon.Glyph, onClick: () -> Unit): PanelIcon =
        PanelIcon(context).apply {
            this.glyph = glyph
            setHapticClick { onClick() }
        }
}
