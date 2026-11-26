package com.windinput.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import com.windinput.ime.core.KeyDropStats

/**
 * 软键盘主视图：自定义 View + Canvas 绘制（渲染路线见 docs/architecture.md §3.6）。
 *
 * 手势体系（对齐 fcitx5 KeyDef.Behavior 声明式模型）：
 * 点按 / 长按气泡（横移选择）/ 长按重复（退格）/ 上滑副字符 / 按键预览。
 * 占位阶段：内置配色常量；正式版配色来自 wind-theme 求值后的调色板（键盘域节点）。
 */
class KeyboardView(context: Context) : View(context) {

    private companion object {
        const val TAG = "WindKeyboard"

        /** 按下高亮的最小可见时长；约 4 帧，短于此人眼捕捉不到 */
        const val MIN_HIGHLIGHT_MS = 70L

        /** 左列各格的无障碍节点 id 起点（键的 id 是键位下标，远小于它） */
        const val SIDE_A11Y_BASE = 10_000

    }

    interface Listener {
        fun onKeyAction(action: KeyAction, shifted: Boolean)

        /** 空格滑动移光标：delta 为有符号步数（右正左负） */
        fun onCursorMove(delta: Int)

        /** 退格左滑扩选：选中光标前 count 个字符（实时更新） */
        fun onBackspaceSelect(count: Int)

        /** 退格滑动抬手：删除当前选区 */
        fun onBackspaceSwipeDelete()

        /** 退格上滑过阈值、正上方抬手：删光标前的全部（有编码清编码；光标后的内容保留） */
        fun onBackspaceDeleteBeforeCursor()

        /** 退格上滑后左移过阈值抬手：清空（有编码清编码，否则清空整个输入框） */
        fun onBackspaceClearAll()

        /** 退格下滑过阈值抬手：恢复刚删的内容 */
        fun onBackspaceRestore() = Unit

        /** 有没有可恢复的删除；没有时退格下滑不进恢复（照旧是单击 / 连删） */
        fun canRestoreDeleted(): Boolean = false

        /** 功能键长按（空格 → 按设置切输入法 / 语音；中英键弹不出菜单时退到这里） */
        fun onKeyLongPress(action: KeyAction)

        /**
         * 功能键长按的竖排菜单：手指不抬、上移选、抬手定（中英键：各方案 + 切换输入法）。
         * null = 没有菜单，长按走 [onKeyLongPress]。
         */
        fun longPressOptions(action: KeyAction): KeyOptions? = null

        /** 菜单抬手：[index] 是手指停住的那项；停在初始项或没滑进菜单为 null（作罢） */
        fun onLongPressOption(action: KeyAction, index: Int?) = Unit

        /** 点了九宫格左列最底下的「自定义」格（见 [sideCustomize]） */
        fun onSideCustomize() = Unit
    }

    /** 长按菜单的内容：[selected] 为初始选中（通常是当前状态，如当前方案） */
    data class KeyOptions(val labels: List<String>, val selected: Int)

    var listener: Listener? = null

    /** 布局来源，缺省内置；Service 注入 LayoutRepository 后走 TOML + 覆盖解析 */
    var layoutProvider: (KeyboardLayer) -> List<List<KeyDef>> = KeyboardLayouts::rows
        set(value) {
            field = value
            reloadLayout()
        }


    /** 按键音开关（设置项 ui.android.sound）；音量随系统 */
    var soundEnabled = false

    /** 按键预览气泡开关（设置项 ui.android.key_preview） */
    var previewEnabled = false

    /**
     * 长按判定时长（设置项 ui.android.long_press_delay）。退格连删的起始延迟与它
     * **共用**：两者在用户感知里是同一件事——「按住多久算按住」，分开配只会互相打架。
     */
    var longPressDelayMs = 300

    /** 空格横滑移光标 / 退格左滑扩选 / 上滑副字符 三个手势的开关（设置项 ui.android.gesture_*） */
    var spaceSwipeEnabled = true

    /** 长按空格有没有动作（设置项 ui.android.space_long_press ≠ none）；做什么由 Service 决定 */
    var spaceLongPressEnabled = false
    var backspaceSwipeEnabled = true
    var swipeSymbolsEnabled = true

    /**
     * 中文标点形态开关：键面、角标、长按气泡里的标点按它显示成「，。？」还是「,.?」。
     * 真值来自引擎推送（chineseMode && chinesePunct），本地不自记——见 [Punct] 说明。
     * 初值取中文：与 [englishMode] 的初值一致；核心起来后不主动推初始状态，
     * 初值为 false 时，刚装好的键盘上逗号键显示成半角「,」。
     */
    var chinesePunct = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

    /**
     * 当前方案短称（「五」/「拼」/「英」），**只用于显示**。短称是用户可配的
     * （`ui.labels`），不能拿它判断是不是英文——英文与否看 [latinInput]。
     */
    var modeLabel: String = "中"

    /**
     * 引擎当前是否直接输入英文（核心给的语义字段，由引擎推送，本地不自记），
     * 决定 [englishMode]。用它而不是 `chineseMode`：英文在本端是一个方案，
     * 切过去后 `chineseMode` 仍为 true（见 Service 的说明）。
     */
    var latinInput: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            englishMode = value
        }

    /** 回车键动作标签（imeOptions 驱动：发送/搜索/下一项…），null = 缺省 label */
    var enterLabel: String? = null
        set(value) {
            field = value
            invalidate()
        }

    /**
     * 英文模式（中英键显示「英」时）。决定字母键面大小写（中文大写、英文小写，大众布局）、
     * 中英键哪个字高亮，以及逗号句号合一键输出哪一个。随 [latinInput] 自动更新。
     */
    var englishMode = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * 引擎里有未上屏的编码。有编码时回车写「确定」（上屏编码原文），
     * 可分词的方案（见 [splitChar]）⇧ 写「分词」。
     */
    var composing = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * 当前方案的手动音节分隔符（`'` 或 `` ` ``），由引擎按键位占用情况定：全拼是反引号，
     * 双拼、五笔整句是 `'`（主仓 `manual_separator_vk_of`）。null = 没有，⇧ 不变「分词」。
     * 「分词」键发的就是它——写死哪个都会和引擎对不上：发 `'` 给全拼，实测是「选第三候选上屏」。
     */
    var splitChar: Char? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * 字母键顶上是否画上滑字符角标（设置项 ui.android.show_swipe_hints，默认开：
     * 豆包、微信的默认布局都画，首行一排 1–0 让数字一眼可得）。关掉只是不画，上滑功能
     * 本身不受影响；非字母键的角标始终画。
     */
    var showSwipeHints = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /**
     * 九宫格数字键盘左列的符号（数字框和电话框不同，由 Service 按输入框类型给）。
     * 画在布局左列的占位空白里，可上下滚动，点选原样上屏（见 [sideRect]）。
     */
    var sideSymbols: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            resetSideScroll()
        }

    /** 左列最底下是否带一格「自定义」（齿轮，点开设置页；数字框带，电话框不带） */
    var sideCustomize = false
        set(value) {
            if (field == value) return
            field = value
            resetSideScroll()
        }

    /** 左列回到顶上、停住滑行（换列表时自动调；Service 在换输入框时也调，不留上一个框的滚动位置） */
    fun resetSideScroll() {
        sideScroller.forceFinished(true)
        sideScroll = 0f
        sideHighlight = -1
        if (a11yAttached) a11y.invalidateRoot()
        invalidate()
    }

    /** 空格上的灰色小字：当前方案名。空串 = 不画 */
    var spaceLabel = ""
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var layer = KeyboardLayer.LETTERS

    /** 数字键盘「←」回到哪一层：从 123 层进来回 123 层，数字框直接调起的回字母层 */
    private var numberReturnLayer = KeyboardLayer.LETTERS
    private var rows = layoutProvider(layer)
    private var shifted = false

    /** 大写锁定（Shift 双击） */
    private var shiftLocked = false

    /** 当前的单次 Shift 是自动大写给的（不是用户按的）：条件消失时要自己撤回 */
    private var autoShifted = false
    private var lastShiftTapTime = 0L

    // ── 配色取自键盘域调色板（Service 按系统深浅色注入，见 Palette） ──
    private val palette = Palette.current
    private val bgColor = palette.background
    private val keyColor = palette.key
    private val keySpecialColor = palette.keySpecial
    private val keyPressedColor = palette.keyPressed
    private val textColor = palette.text
    private val hintColor = palette.hint

    private val density = resources.displayMetrics.density

    /** 行高动态取自 KeyboardDimens（横竖屏配置由 Service 注入） */
    private val rowHeightPx get() = KeyboardDimens.rowHeightPx(density)

    /** 键与键的间隙是两侧各留一半：横向 14px、纵向 24px（@420dpi），与主流输入法同量级 */
    private val keyGapPx = KeyboardDimens.KEY_GAP_DP * density
    private val keyGapVPx get() = KeyboardDimens.keyGapVPx(density)

    /** 键区离视图左右边缘的留白：加上半个键间隙，首末键离屏幕边约 16px，不贴边、少误点 */
    private val edgePadPx = KeyboardDimens.EDGE_PAD_DP * density
    private val padTopPx = KeyboardDimens.PAD_TOP_DP * density
    private val keyRadiusPx = KeyboardDimens.KEY_RADIUS_DP * density
    private val swipeThresholdPx = KeyboardDimens.SWIPE_THRESHOLD_DP * density
    private val swipeActivatePx = KeyboardDimens.SWIPE_ACTIVATE_DP * density
    private val cursorStepPx = KeyboardDimens.CURSOR_STEP_DP * density

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textAlign = Paint.Align.LEFT
        textSize = 20 * density
    }

    /** 复用的字形包围盒（onDraw 每键一次，避免分配） */
    private val glyphBounds = android.graphics.Rect()
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hintColor
        textAlign = Paint.Align.RIGHT
        textSize = 11 * density
    }

    /** 每次 layout 重算的键位命中区域；[row] 用于落在空白处时找同一行最近的键 */
    private data class KeyBounds(val key: KeyDef, val rect: RectF, val row: Int)

    private val keyBounds = mutableListOf<KeyBounds>()

    /**
     * 左列符号的区域：布局从第 1 行起、每行打头的等宽占位空白连成的一列（数字键盘的左列）。
     * 没有这样的列或没给 [sideSymbols] 时为 null，视图就是普通键盘。
     */
    private var sideRect: RectF? = null
    private var sideScroll = 0f
    private val sideItemHeightPx get() = rowHeightPx * 0.75f
    private var sideTouch = false
    private var sideDragging = false
    private var sideLastY = 0f
    private var sidePressed = -1

    /** 左列的格数：符号 + 末尾的「自定义」格 */
    private val sideCount get() = sideSymbols.size + if (sideCustomize) 1 else 0

    /** 左列的惯性滑行：抬手按速度 fling，[computeScroll] 逐帧推进；不越界、不回弹 */
    private val sideScroller = OverScroller(context)
    private var sideVelocity: VelocityTracker? = null
    private val viewConfig = android.view.ViewConfiguration.get(context)
    private val minFlingPx = viewConfig.scaledMinimumFlingVelocity
    private val maxFlingPx = viewConfig.scaledMaximumFlingVelocity

    /** 左列**绘制用**的按下格，同按键的 [highlightKey]：抬手后至少亮 [MIN_HIGHLIGHT_MS] */
    private var sideHighlight = -1
    private var sideHighlightAt = 0L
    private val clearSideHighlight = Runnable {
        sideHighlight = -1
        invalidate()
    }
    private val glyphPainter = GlyphPainter()
    private val touchSlopPx = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var pressedKey: KeyDef? = null
    private var pressedRect: RectF? = null

    /**
     * **绘制用**的按下高亮，与手势状态 [pressedKey] 分开。
     *
     * 快速点击时 DOWN 与 UP 常落在同一帧内（间隔 < 16ms）：`invalidate()` 只是标脏，
     * 真正重绘要等下一次 VSYNC，届时 [pressedKey] 已被抬手清空，于是**按下态一帧都没画过**
     * ——表现就是「点得快就没有按下效果」。故抬手时不立刻清高亮，而是保证它至少可见
     * [MIN_HIGHLIGHT_MS]（约 4 帧）。
     */
    private var highlightKey: KeyDef? = null
    private var highlightAt = 0L

    /** 高亮实际经历的帧数：自检用，验证按下态确实被绘制过（见 [onDraw]） */
    private var highlightFrames = 0

    private val clearHighlight = Runnable {
        finishHighlight()
        invalidate()
    }

    /**
     * 结束高亮并自检。
     *
     * 记录**实际绘制帧数**而不是时长：这个 bug 的本质是「标脏了但没轮到重绘」，
     * 时长达标而帧数为 0 依然是没有按下效果。帧数 ≥1 才算真的画过。
     */
    private fun finishHighlight() {
        if (highlightKey != null && highlightFrames == 0) {
            Log.w(TAG, "按下高亮一帧未绘制（${android.os.SystemClock.uptimeMillis() - highlightAt}ms）")
        }
        highlightKey = null
        highlightFrames = 0
    }

    private val popupManager by lazy { KeyPopupManager(this) }

    // 手势状态
    private var downX = 0f
    private var downY = 0f

    /** 上滑的一体式气泡已长出（本次按压改由气泡决定上屏什么） */
    private var swipeShown = false

    /** 上滑已过阈值：抬手上屏副字符 */
    private var swipeArmed = false

    /** 正弹着长按菜单的功能键（中英键）；抬手时按菜单上的选中项回调 */
    private var optionsAction: KeyAction? = null
    private var optionsInitial = 0

    /**
     * 菜单弹出时手指所在的 y；手指真正上移过触摸阈值之前不改选中项。按住不动时系统照样会发
     * MOVE（坐标有抖动），不拦的话选中项会跟着跳，抬手就误切了方案
     */
    private var optionsAnchorY = 0f
    private var optionsMoved = false

    /** 本次按压最近一次的手指位置（长按弹菜单时记作锚点） */
    private var lastY = 0f

    /** 空格滑动移光标进行中 */
    private var spaceSwipeActive = false
    private var lastStepX = 0f

    /** 退格键的全部手势（点按 / 连删 / 左滑扩选 / 上滑双选项 / 下滑恢复）见 [BackspaceGesture] */
    private val backspaceGesture = BackspaceGesture(
        object : BackspaceGesture.Listener {
            override fun onDelete() {
                listener?.onKeyAction(KeyAction.Backspace, shifted || shiftLocked)
            }

            override fun onSelect(count: Int) {
                listener?.onBackspaceSelect(count)
            }

            override fun onSelectionDelete() {
                listener?.onBackspaceSwipeDelete()
            }

            override fun onDeleteBeforeCursor() {
                listener?.onBackspaceDeleteBeforeCursor()
            }

            override fun onClearAll() {
                listener?.onBackspaceClearAll()
            }

            override fun onRestoreDeleted() {
                listener?.onBackspaceRestore()
            }

            override fun canRestoreDeleted() = listener?.canRestoreDeleted() == true

            override fun swipeEnabled() = backspaceSwipeEnabled
        },
        longPressDelayMs = { longPressDelayMs.toLong() },
        feedback = { pressedKey?.let { feedback(it) } },
    )

    private val handler = Handler(Looper.getMainLooper())

    /** 长按已触发（抬手时不再补发单击） */
    private var longPressFired = false

    private val longPressRunnable = Runnable {
        val key = pressedKey ?: return@Runnable
        val rect = pressedRect ?: return@Runnable
        val popup = popupChars(key)
        if (popup.isNotEmpty()) {
            popupManager.dismissPreview()
            // 气泡里显示中英形态，抬手时按**下标**映回布局里的 ASCII 字符再喂引擎
            popupManager.showPopup(
                rect,
                popup.map { Punct.display(it, chinesePunct) },
                key.popupInitialIndex(englishMode),
            )
            repeatHaptic()
        } else if (hasLongPressAction(key.action)) {
            // 功能键长按走独立回调（没有字符气泡可弹）。置位后抬手不补单击，
            // 否则「长按开面板」会连带触发一次「点击切模式」。
            longPressFired = true
            repeatHaptic()
            val options = listener?.longPressOptions(key.action)
            if (options != null) {
                optionsAction = key.action
                optionsInitial = options.selected
                optionsAnchorY = lastY
                optionsMoved = false
                popupManager.dismissPreview()
                popupManager.showMenu(rect, options.labels, options.selected)
            } else {
                listener?.onKeyLongPress(key.action)
            }
        }
    }

    private fun hasLongPressAction(action: KeyAction): Boolean =
        action == KeyAction.SwitchLang || (action == KeyAction.Space && spaceLongPressEnabled)

    /** 长按气泡的字符集，字母键是「大写 | 符号 | 小写」，见 [KeyDef.popupChars] */
    private fun popupChars(key: KeyDef): String = key.popupChars(englishMode)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, KeyboardDimens.keyboardHeightPx(density, rows.size))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildBounds(w)
    }

    override fun onDetachedFromWindow() {
        backspaceGesture.cancel()
        popupManager.dismissAll()
        handler.removeCallbacks(clearHighlight)
        handler.removeCallbacks(clearSideHighlight)
        sideScroller.forceFinished(true)
        sideVelocity?.recycle()
        sideVelocity = null
        sideTouch = false
        sideHighlight = -1
        highlightKey = null
        highlightFrames = 0
        super.onDetachedFromWindow()
    }

    private fun rebuildBounds(width: Int) {
        keyBounds.clear()
        rows.forEachIndexed { rowIndex, row ->
            val totalWeight = row.sumOf { it.widthWeight.toDouble() }.toFloat()
            val unit = (width - 2 * edgePadPx) / totalWeight
            var x = edgePadPx
            val top = padTopPx + rowIndex * rowHeightPx
            for (key in row) {
                val keyWidth = unit * key.widthWeight
                // 占位空白只占宽度，不进命中表也不绘制
                if (key.action != KeyAction.Spacer) {
                    keyBounds += KeyBounds(
                        key,
                        RectF(
                            x + keyGapPx, top + keyGapVPx,
                            x + keyWidth - keyGapPx, top + rowHeightPx * key.rowSpan - keyGapVPx,
                        ),
                        rowIndex,
                    )
                }
                x += keyWidth
            }
        }
        sideRect = computeSideRect(width)
        // 键位变了（换层、改尺寸）：旧的虚拟节点作废，让无障碍服务重取
        if (a11yAttached) a11y.invalidateRoot()
    }

    /**
     * 无障碍代理已挂上。属性按声明顺序初始化，[rebuildBounds] 可能在 [a11y] 建好之前被调用
     * （构造期换层），那时它还是 null——用这个标记挡住（JVM 默认 false）。
     */
    private var a11yAttached = false

    private fun computeSideRect(width: Int): RectF? {
        val first = rows.firstOrNull()?.firstOrNull() ?: return null
        if (first.action != KeyAction.Spacer) return null
        val count = rows.takeWhile { row ->
            row.firstOrNull()?.let { it.action == KeyAction.Spacer && it.widthWeight == first.widthWeight } == true
        }.size
        val unit = (width - 2 * edgePadPx) / rows[0].sumOf { it.widthWeight.toDouble() }.toFloat()
        return RectF(
            edgePadPx + keyGapPx, padTopPx + keyGapVPx,
            edgePadPx + unit * first.widthWeight - keyGapPx, padTopPx + rowHeightPx * count - keyGapVPx,
        )
    }

    private val maxSideScroll: Float
        get() {
            val rect = sideRect ?: return 0f
            return (sideCount * sideItemHeightPx - rect.height()).coerceAtLeast(0f)
        }

    private fun sideIndexAt(y: Float): Int {
        val rect = sideRect ?: return -1
        val i = ((y - rect.top + sideScroll) / sideItemHeightPx).toInt()
        return if (i in 0 until sideCount) i else -1
    }

    /** 第 [i] 格（滚动后）的位置 */
    private fun sideCell(rect: RectF, i: Int): RectF {
        val top = rect.top + i * sideItemHeightPx - sideScroll
        return RectF(rect.left, top, rect.right, top + sideItemHeightPx)
    }

    /**
     * 左列符号：一整条功能键底色，里面一格一个符号、格间细分隔线，超出部分上下滚动；
     * 最底下可带一格「自定义」（齿轮）。按下的格画成与按键同圆角的浮起块（按键按下色）。
     */
    private fun drawSide(canvas: Canvas) {
        val rect = sideRect ?: return
        if (sideCount == 0) return
        keyPaint.color = keySpecialColor
        canvas.drawRoundRect(rect, keyRadiusPx, keyRadiusPx, keyPaint)
        canvas.save()
        canvas.clipRect(rect)
        textPaint.textSize = 18 * density
        textPaint.color = textColor
        val lineInset = rect.width() * 0.2f
        val inset = 3 * density
        for (i in 0 until sideCount) {
            val cell = sideCell(rect, i)
            if (cell.bottom < rect.top || cell.top > rect.bottom) continue
            if (i > 0 && i != sideHighlight && i - 1 != sideHighlight) {
                keyPaint.color = palette.separator
                canvas.drawRect(cell.left + lineInset, cell.top - density / 2, cell.right - lineInset, cell.top + density / 2, keyPaint)
            }
            if (i == sideHighlight) {
                keyPaint.color = keyPressedColor
                canvas.drawRoundRect(
                    cell.left + inset, cell.top + inset, cell.right - inset, cell.bottom - inset,
                    keyRadiusPx, keyRadiusPx, keyPaint,
                )
            }
            val sym = sideSymbols.getOrNull(i)
            if (sym != null) {
                drawCentered(canvas, sym, cell, textPaint)
            } else {
                glyphPainter.draw(canvas, PanelIcon.Glyph.SETTINGS, cell.centerX(), cell.centerY(), 20 * density, palette.icon)
            }
        }
        canvas.restore()
    }

    /** 左列按下：先停住滑行（这一下只是「停」，不算点选），否则记下按的是哪格 */
    private fun startSideTouch(event: MotionEvent) {
        val stoppedFling = !sideScroller.isFinished
        sideScroller.forceFinished(true)
        sideTouch = true
        sideDragging = false
        downY = event.y
        sideLastY = event.y
        sideVelocity?.recycle()
        sideVelocity = VelocityTracker.obtain().also { it.addMovement(event) }
        sidePressed = if (stoppedFling) -1 else sideIndexAt(event.y)
        handler.removeCallbacks(clearSideHighlight)
        sideHighlight = sidePressed
        sideHighlightAt = android.os.SystemClock.uptimeMillis()
        KeyHaptics.tap(this)
        invalidate()
    }

    /** 左列的触摸：竖向拖动是滚动（抬手按速度惯性滑行），没拖动就抬手是点选 */
    private fun handleSideTouch(event: MotionEvent) {
        sideVelocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!sideDragging && kotlin.math.abs(event.y - downY) > touchSlopPx) {
                    sideDragging = true
                    sidePressed = -1
                    sideHighlight = -1
                    sideLastY = event.y
                }
                if (sideDragging) {
                    sideScroll = (sideScroll - (event.y - sideLastY)).coerceIn(0f, maxSideScroll)
                    sideLastY = event.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val index = sidePressed
                if (sideDragging) fling()
                endSideTouch()
                if (index >= 0) clickSide(index)
            }
            MotionEvent.ACTION_CANCEL -> endSideTouch()
        }
    }

    private fun fling() {
        val tracker = sideVelocity ?: return
        tracker.computeCurrentVelocity(1000, maxFlingPx.toFloat())
        val vy = tracker.yVelocity
        if (kotlin.math.abs(vy) < minFlingPx) return
        sideScroller.fling(0, sideScroll.toInt(), 0, -vy.toInt(), 0, 0, 0, maxSideScroll.toInt())
        postInvalidateOnAnimation()
    }

    /** 收尾按压；按下格的高亮补足最短可见时长再清 */
    private fun endSideTouch() {
        sideTouch = false
        sidePressed = -1
        sideVelocity?.recycle()
        sideVelocity = null
        if (sideHighlight >= 0) {
            val left = MIN_HIGHLIGHT_MS - (android.os.SystemClock.uptimeMillis() - sideHighlightAt)
            if (left > 0) handler.postDelayed(clearSideHighlight, left) else sideHighlight = -1
        }
        if (sideScroller.isFinished && a11yAttached) a11y.invalidateRoot()
        invalidate()
    }

    /** 左列第 [index] 格被点（手指或读屏）：符号原样上屏，「自定义」格开设置页 */
    private fun clickSide(index: Int) {
        val sym = sideSymbols.getOrNull(index)
        if (sym != null) {
            listener?.onKeyAction(KeyAction.Commit(sym), shifted = false)
        } else if (sideCustomize && index == sideSymbols.size) {
            listener?.onSideCustomize()
        }
    }

    override fun computeScroll() {
        if (sideScroller.computeScrollOffset()) {
            sideScroll = sideScroller.currY.toFloat().coerceIn(0f, maxSideScroll)
            postInvalidateOnAnimation()
            if (sideScroller.isFinished && a11yAttached) a11y.invalidateRoot()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        drawSide(canvas)
        if (highlightKey != null) highlightFrames++
        for ((key, rect) in keyBounds) {
            keyPaint.color = when {
                key === highlightKey -> keyPressedColor
                key.isAccent -> palette.accent
                key.action == KeyAction.Shift && (shifted || shiftLocked) -> keyPressedColor
                key.isSpecial -> keySpecialColor
                else -> keyColor
            }
            canvas.drawRoundRect(rect, keyRadiusPx, keyRadiusPx, keyPaint)
            textPaint.color = if (key.isAccent) palette.onAccent else textColor
            if (key.action == KeyAction.SwitchLang) {
                drawLangKey(canvas, rect)
                continue
            }
            // 数字键盘的空格只是一个小功能键，写「空格」；方案名留给字母层和 123 层的长空格
            if (key.action == KeyAction.Space && spaceLabel.isNotEmpty() && layer != KeyboardLayer.NUMBER) {
                drawCentered(canvas, spaceLabel, rect, smallPaint)
                continue
            }
            // ⇧ / ⌫ 画矢量：字体里这两个符号笔画细、各家字体字形不一，和字母键面放一起很突兀
            if (key.action == KeyAction.Shift && !splitMode) {
                drawShiftIcon(canvas, rect, textPaint.color)
                continue
            }
            if (key.action == KeyAction.Backspace) {
                drawBackspaceIcon(canvas, rect, textPaint.color)
                continue
            }
            val label = displayLabel(key)
            textPaint.textSize = labelSizePx(label)
            val isLetter = (key.action as? KeyAction.Text)?.char?.isLetter() == true
            val hint = if (isLetter && !showSwipeHints) null else key.swipeFor(englishMode)
            // 有角标的字母键：主字下移一点，给顶上居中的角标让出位置（豆包的排法）
            val shiftDown = if (hint != null && isLetter) 4 * density else 0f
            val textY = rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2 + shiftDown
            // 水平按**字形包围盒**居中，而不是按字符前进宽度居中：全角标点「，」「。」的
            // 字形挤在字模左下角，按前进宽度居中会明显偏左（真机反馈的就是这个）。
            // 竖向仍按 ascent/descent 居中——按包围盒居竖向会让 q 与 a 的基线上下乱跳。
            textPaint.getTextBounds(label, 0, label.length, glyphBounds)
            canvas.drawText(label, rect.centerX() - glyphBounds.exactCenterX(), textY, textPaint)
            // 副字符角标（按当前模式显示中英形态）：字母键在顶上居中（豆包、微信都这么排，
            // 一眼扫过去数字行就是一排 1–0），其余键仍在右上角；字母键的角标受开关控制
            if (hint != null) {
                val text = Punct.display(hint, chinesePunct)
                if (isLetter) {
                    hintPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText(text, rect.centerX(), rect.top + 12 * density, hintPaint)
                    hintPaint.textAlign = Paint.Align.RIGHT
                } else {
                    canvas.drawText(text, rect.right - 5 * density, rect.top + 13 * density, hintPaint)
                }
            }
        }
    }

    private val iconPaint = KeyIcons.strokePaint(density)
    private val iconPath = android.graphics.Path()

    private fun drawShiftIcon(canvas: Canvas, rect: RectF, color: Int) {
        iconPaint.color = color
        KeyIcons.drawShift(canvas, rect.centerX(), rect.centerY(), density, shifted, shiftLocked, iconPaint, iconPath)
    }

    private fun drawBackspaceIcon(canvas: Canvas, rect: RectF, color: Int) {
        iconPaint.color = color
        KeyIcons.drawBackspace(canvas, rect.centerX(), rect.centerY(), density, iconPaint, iconPath)
    }

    /** 有编码且方案支持分词：⇧ 这一格临时当「分词」用（发 [splitChar]） */
    private val splitMode get() = composing && splitChar != null

    private fun displayLabel(key: KeyDef): String {
        if (key.action == KeyAction.Enter) {
            // 有编码时回车上屏编码原文，键面写「确定」（两家相同）
            if (composing) return "确定"
            enterLabel?.let { return it }
        }
        if (key.action == KeyAction.Shift) {
            if (splitMode) return "分词"
            if (shiftLocked) return "⇪"
        }
        val action = key.action
        val ch = key.textFor(englishMode)
        // 标点键：键面随模式显示中英形态（布局里写的是 ASCII）
        if (ch != null && !ch.isLetterOrDigit()) {
            return Punct.display(ch, chinesePunct)
        }
        // 字母键面：中文模式大写、英文模式小写（键面本身就是模式提示）；英文下按 ⇧ 变大写
        val upper = !englishMode || shifted || shiftLocked
        return if (upper && action is KeyAction.Text && action.char.isLetter()) {
            key.label.uppercase()
        } else {
            key.label
        }
    }

    /** 两个字以上的功能键（换行 / 发送 / 分词 / 123）字号收小，免得挤出键帽 */
    private fun labelSizePx(label: String): Float =
        (if (label.length >= 2) 16 else 20) * density

    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = hintColor
        textSize = 12 * density
    }

    private fun drawCentered(canvas: Canvas, text: String, rect: RectF, paint: Paint) {
        paint.getTextBounds(text, 0, text.length, glyphBounds)
        val y = rect.centerY() - (paint.ascent() + paint.descent()) / 2
        canvas.drawText(text, rect.centerX() - glyphBounds.exactCenterX(), y, paint)
    }

    /**
     * 中英键：键面同时写「中」「英」，左上大字、右下小字，**当前模式那个字**在前面用正文色，
     * 另一个用提示色（两家的做法）。用户一眼看出现在在哪，也知道点了会去哪。
     */
    private fun drawLangKey(canvas: Canvas, rect: RectF) {
        val (major, minor) = if (englishMode) "英" to "中" else "中" to "英"
        textPaint.textSize = 17 * density
        canvas.drawText(
            major,
            rect.left + rect.width() * 0.18f,
            rect.top + rect.height() * 0.50f,
            textPaint,
        )
        smallPaint.textSize = 11 * density
        canvas.drawText(
            minor,
            rect.left + rect.width() * 0.52f,
            rect.top + rect.height() * 0.80f,
            smallPaint,
        )
        smallPaint.textSize = 12 * density
    }

    /**
     * 当前跟踪的手指。快打时常是「上一指未抬、下一指已落」（rollover）：只认第一指的话，
     * 第二指整次按键都丢了。做法同 AOSP LatinIME——新手指落下时把上一指的按压按单击收尾，
     * 改跟新手指；之后只看这根手指的事件，已收尾的那根抬起时不再处理。
     */
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (sideTouch) {
            // 侧栏滚动只跟第一指，其余手指的落下/抬起忽略
            if (event.actionMasked != MotionEvent.ACTION_POINTER_DOWN &&
                event.actionMasked != MotionEvent.ACTION_POINTER_UP
            ) {
                handleSideTouch(event)
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val side = sideRect
                if (side != null && sideCount > 0 && side.contains(event.x, event.y)) {
                    startSideTouch(event)
                    return true
                }
                startPress(event.getPointerId(0), event.x, event.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 上一指正处在手势里（气泡选字、空格移光标、退格扩选/连删、长按）时不打断它
                if (pressedKey != null && gestureInProgress()) {
                    KeyDropStats.global.record(KeyDropStats.Reason.SECOND_FINGER_IGNORED)
                    return true
                }
                val i = event.actionIndex
                if (pressedKey != null) finishPress()
                // 落在左侧符号列：那里不是键（占位空白），按键位找会命中同行最近的数字键、打错字。
                // 侧列滚动只认单指，这根手指忽略
                val side = sideRect
                if (side != null && sideCount > 0 && side.contains(event.getX(i), event.getY(i))) {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    return true
                }
                startPress(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(activePointerId)
                if (i >= 0) movePress(event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) finishPress()
            }
            MotionEvent.ACTION_UP -> {
                if (event.getPointerId(0) == activePointerId) finishPress()
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
            MotionEvent.ACTION_CANCEL -> {
                if (pressedKey != null) KeyDropStats.global.record(KeyDropStats.Reason.CANCEL_ACTION)
                cancelPress()
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return true
    }

    private fun gestureInProgress(): Boolean =
        popupManager.isPopupShown || swipeShown || spaceSwipeActive || backspaceGesture.isEngaged ||
            longPressFired

    private fun startPress(pointerId: Int, x: Float, y: Float) {
        activePointerId = pointerId
        lastY = y
        downX = x
        downY = y
        swipeShown = false
        swipeArmed = false
        optionsAction = null
        longPressFired = false
        spaceSwipeActive = false
        lastStepX = x
        val bounds = findBounds(x, y)
        pressedKey = bounds?.key
        pressedRect = bounds?.rect
        val key = bounds?.key
        // 高亮立刻改指新键（连击时上一键的延迟清除不会误伤：clearHighlight 已撤销）
        handler.removeCallbacks(clearHighlight)
        highlightKey = key
        highlightFrames = 0
        highlightAt = android.os.SystemClock.uptimeMillis()
        if (key != null) {
            feedback(key)
            if (previewEnabled && !key.isSpecial && key.action is KeyAction.Text) {
                popupManager.showPreview(bounds.rect, displayLabel(key))
            }
            if (key.action == KeyAction.Backspace) {
                backspaceGesture.down(this, bounds.rect, x, y)
            }
            if (popupChars(key).isNotEmpty() || hasLongPressAction(key.action)) {
                handler.postDelayed(longPressRunnable, longPressDelayMs.toLong())
            }
        }
        invalidate()
    }

    private fun movePress(x: Float, y: Float) {
        val key = pressedKey ?: return
        lastY = y
        if (popupManager.isPopupShown) {
            if (optionsAction != null && !optionsMoved) {
                if (kotlin.math.abs(y - optionsAnchorY) < touchSlopPx) return
                optionsMoved = true
            }
            popupManager.updatePopupSelection(x, y)
            return
        }
        val dy = downY - y
        val dx = x - downX

        // 空格横向滑动：移动光标（步进制，进入后不再出空格）
        if (key.action == KeyAction.Space && spaceSwipeEnabled) {
            if (!spaceSwipeActive && kotlin.math.abs(dx) > swipeActivatePx) {
                spaceSwipeActive = true
                cancelTimers()
                popupManager.dismissAll()
                lastStepX = x
            }
            if (spaceSwipeActive) {
                val steps = ((x - lastStepX) / cursorStepPx).toInt()
                if (steps != 0) {
                    listener?.onCursorMove(steps)
                    lastStepX += steps * cursorStepPx
                }
                return
            }
        }

        // 退格：点按 / 连删 / 左滑扩选 / 上滑双选项都在 BackspaceGesture 里
        if (key.action == KeyAction.Backspace) {
            backspaceGesture.move(x, y)
            if (backspaceGesture.isEngaged) return
        }

        // 上滑副字符：往上一动就从键位长出一体式气泡（上半副字符、下半主字符），过阈值副字符
        // 变强调色，抬手才上屏——上滑途中看得见要出什么，滑过头了退回来还能反悔
        val swipeChar = key.swipeFor(englishMode)
        if (swipeSymbolsEnabled && swipeChar != null) {
            if (!swipeShown && dy > swipeActivatePx && dy > kotlin.math.abs(dx)) {
                val rect = pressedRect ?: return
                swipeShown = true
                cancelTimers()
                popupManager.showSwipeBubble(rect, displayLabel(key), Punct.display(swipeChar, chinesePunct), y)
            }
            if (swipeShown) {
                popupManager.moveSwipeBubble(y)
                val armed = dy > swipeThresholdPx
                if (armed != swipeArmed) {
                    swipeArmed = armed
                    popupManager.setSwipeArmed(armed)
                    if (armed) KeyHaptics.tap(this)
                }
                return
            }
        }
        // 滑出键位即取消（滑动输入为二期能力）。判据以**按下的键**为准、带键缝一半 + touchSlop 的
        // 容差：此前一出矩形就按「同行最近键」改判，快打时拇指蹭几像素整次点按就丢了（B4）
        val r = pressedRect ?: return
        if (TapHit.shouldCancelOnMove(
                TapHit.Rect(r.left, r.top, r.right, r.bottom),
                x,
                y,
                TapHit.margin(keyGapPx, touchSlopPx.toFloat()),
                dyUp = dy,
                swipeThresholdPx = swipeThresholdPx,
            )
        ) {
            KeyDropStats.global.record(KeyDropStats.Reason.DRIFT_CANCEL)
            cancelPress()
        }
    }

    /** 当前手指抬起（或被下一指顶替）：按本次按压的状态决定发什么 */
    private fun finishPress() {
        optionsAction?.let { action ->
            val index = popupManager.popupSelectedIndex()?.takeIf { it != optionsInitial }
            optionsAction = null
            cancelPress()
            listener?.onLongPressOption(action, index)
            return
        }
        val key = pressedKey
        val swipeChar = if (swipeArmed) key?.swipeFor(englishMode) else null
        val popupChoice = popupManager.popupSelectedIndex()
            ?.let { i -> key?.let { popupChars(it).getOrNull(i) } }
        // 长按触发过、或字符气泡弹出过（含下滑取消）都不补单击
        val skipTap = longPressFired || popupManager.isPopupShown
        // 字符气泡弹着、手指却滑出了气泡（选中项为空）：抬手既不上屏也不补单击——用户可能是
        // 慢点按，记一笔供诊断
        if (key != null && popupManager.isPopupShown && popupChoice == null && !longPressFired) {
            KeyDropStats.global.record(KeyDropStats.Reason.POPUP_CANCEL)
        }
        val wasSpaceSwipe = spaceSwipeActive
        if (key?.action == KeyAction.Backspace) {
            backspaceGesture.up()
            cancelPress()
            return
        }
        cancelPress()
        when {
            swipeChar != null -> {
                listener?.onKeyAction(symbolAction(swipeChar), shifted = false)
                resetSingleShift(key)
            }
            wasSpaceSwipe -> Unit
            // 大写字母按「Shift + 小写」发：与 ⇧ 单次态走同一条路（直接上屏大写）
            popupChoice != null && popupChoice.isUpperCase() -> {
                listener?.onKeyAction(KeyAction.Text(popupChoice.lowercaseChar()), shifted = true)
                resetSingleShift(key)
            }
            popupChoice != null -> {
                listener?.onKeyAction(symbolAction(popupChoice), shifted = false)
                resetSingleShift(key)
            }
            key != null && !skipTap -> handleKey(key)
        }
    }

    /**
     * 上滑 / 长按气泡选中的符号转动作：ASCII 走引擎（标点转换、配对）；非 ASCII 字面字符
     * （中文模式 j 的右引号 ”）引擎翻不出 VK，用 [KeyAction.Commit] 直接上屏（有编码时先顶首选）。
     */
    private fun symbolAction(c: Char): KeyAction = symbolActionFor(c, chinesePunct)

    /** 按下反馈：震动 + 按键音，各自受开关控制 */
    private fun feedback(key: KeyDef) {
        KeyHaptics.tap(this)
        if (soundEnabled) {
            // 系统自带四种键音按键型分发（空格/退格/回车有各自的音色），与系统键盘一致
            val fx = when (key.action) {
                KeyAction.Space -> android.media.AudioManager.FX_KEYPRESS_SPACEBAR
                KeyAction.Backspace -> android.media.AudioManager.FX_KEYPRESS_DELETE
                KeyAction.Enter -> android.media.AudioManager.FX_KEYPRESS_RETURN
                else -> android.media.AudioManager.FX_KEYPRESS_STANDARD
            }
            // -1 = 跟随系统触摸音量
            audioManager.playSoundEffect(fx, -1f)
        }
    }

    private fun repeatHaptic() {
        pressedKey?.let { feedback(it) }
    }

    private fun cancelTimers() {
        handler.removeCallbacks(longPressRunnable)
    }

    /**
     * 清手势状态；高亮另按最小可见时长延迟清除（见 [highlightKey]）。
     * 已满时长的直接清，未满的补足剩余毫秒——快点慢点都恰好亮一下。
     */
    private fun clearPressed() {
        pressedKey = null
        pressedRect = null
        if (highlightKey != null) {
            val shown = android.os.SystemClock.uptimeMillis() - highlightAt
            if (shown >= MIN_HIGHLIGHT_MS) {
                finishHighlight()
            } else {
                handler.postDelayed(clearHighlight, MIN_HIGHLIGHT_MS - shown)
            }
        }
        invalidate()
    }

    private fun cancelPress() {
        backspaceGesture.cancel()
        cancelTimers()
        popupManager.dismissAll()
        clearPressed()
    }

    /**
     * 命中的键。落在键缝或占位空白里时，归同一行水平距离最近的键——第 2 行缩进的
     * 半个键位、键与键之间的 3dp 缝都不该是「点空」。
     */
    private fun findBounds(x: Float, y: Float): KeyBounds? {
        keyBounds.firstOrNull { it.rect.contains(x, y) }?.let { return it }
        val row = ((y - padTopPx) / rowHeightPx).toInt().coerceIn(0, rows.size - 1)
        return keyBounds
            .filter { it.row == row }
            .minByOrNull { kotlin.math.abs(it.rect.centerX() - x) }
    }

    private fun findKey(x: Float, y: Float): KeyDef? = findBounds(x, y)?.key

    /**
     * 自动首字母大写（英文模式句首 / 输入框开头，由 Service 按 `getCursorCapsMode` 判定）。
     * 给的是**单次** Shift：键面变大写，打一个字母后复位，和用户按一次 ⇧ 一样。
     * 条件消失（删回了句中、挪了光标）时只撤回自己给的那次，用户手按的 ⇧ 不动。
     */
    fun setAutoCaps(on: Boolean) {
        if (shiftLocked || layer != KeyboardLayer.LETTERS) return
        when {
            on && !shifted -> {
                shifted = true
                autoShifted = true
            }
            !on && autoShifted -> {
                shifted = false
                autoShifted = false
            }
            else -> return
        }
        invalidate()
    }

    /**
     * 切换布局层（EditorInfo 场景感知 / 符号键共用入口）。
     * [returnTo]：进数字键盘时记下「←」回哪一层。
     */
    fun setLayer(newLayer: KeyboardLayer, returnTo: KeyboardLayer = KeyboardLayer.LETTERS) {
        if (newLayer == KeyboardLayer.NUMBER) numberReturnLayer = returnTo
        if (layer == newLayer) return
        layer = newLayer
        shifted = false
        shiftLocked = false
        autoShifted = false
        reloadLayout()
    }

    /** 字母层的 123 键先进九宫数字页（设置项 ui.android.number_page，默认 pad9；设成 full 先进全键盘数字） */
    var numberPad9 = true

    /** 设置里改了布局相关项（底行排法、数字页默认）：按当前设置重取键位 */
    fun refreshLayout() = reloadLayout()

    private fun reloadLayout() {
        rows = layoutProvider(layer)
        requestLayout()
        if (width > 0) {
            rebuildBounds(width)
        }
        invalidate()
    }

    // ── 无障碍 ──────────────────────────────────────────────────────
    // 键是 Canvas 画的，系统看不见：每个键报成一个虚拟节点（读屏念键名、双击即按），
    // uiautomator 也因此能按文字找到键。按下走 [handleKey]，与手指点按同一条路。

    // 左列各格也报成节点（id 从 [SIDE_A11Y_BASE] 起，只报露在列里的格）：符号念「左列·符号」，
    // 齿轮格念「自定义」
    private val a11y = object : androidx.customview.widget.ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val side = sideRect
            if (side != null && side.contains(x, y)) {
                return sideIndexAt(y).takeIf { it >= 0 }?.let { SIDE_A11Y_BASE + it } ?: INVALID_ID
            }
            return keyBounds.indexOfFirst { it.rect.contains(x, y) }.takeIf { it >= 0 } ?: INVALID_ID
        }

        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            keyBounds.indices.forEach(ids::add)
            val side = sideRect ?: return
            for (i in 0 until sideCount) {
                val cell = sideCell(side, i)
                if (cell.bottom > side.top && cell.top < side.bottom) ids += SIDE_A11Y_BASE + i
            }
        }

        override fun onPopulateNodeForVirtualView(id: Int, node: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
            if (id >= SIDE_A11Y_BASE) {
                populateSideNode(id - SIDE_A11Y_BASE, node)
                return
            }
            val kb = keyBounds.getOrNull(id)
            if (kb == null) {
                // 键位刚重排、旧 id 失效：给个空壳，ExploreByTouchHelper 要求边界非空
                node.contentDescription = ""
                node.setBoundsInParent(android.graphics.Rect(0, 0, 1, 1))
                return
            }
            node.className = android.widget.Button::class.java.name
            node.contentDescription = a11yLabel(kb.key)
            node.text = a11yLabel(kb.key)
            val r = android.graphics.Rect()
            kb.rect.roundOut(r)
            node.setBoundsInParent(r)
            node.addAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_CLICK)
        }

        override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: android.os.Bundle?): Boolean {
            if (action != androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            if (id >= SIDE_A11Y_BASE) {
                if (id - SIDE_A11Y_BASE !in 0 until sideCount) return false
                clickSide(id - SIDE_A11Y_BASE)
                return true
            }
            val key = keyBounds.getOrNull(id)?.key ?: return false
            handleKey(key)
            invalidate()
            invalidateVirtualView(id)
            return true
        }
    }

    init {
        androidx.core.view.ViewCompat.setAccessibilityDelegate(this, a11y)
        a11yAttached = true
    }

    private fun populateSideNode(i: Int, node: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
        val side = sideRect
        val label = when {
            i in sideSymbols.indices -> "左列·${sideSymbols[i]}"
            sideCustomize && i == sideSymbols.size -> "自定义"
            else -> null
        }
        if (side == null || label == null) {
            node.contentDescription = ""
            node.setBoundsInParent(android.graphics.Rect(0, 0, 1, 1))
            return
        }
        // 滚到一半露出的格按露出的部分报，不越出左列
        val cell = sideCell(side, i)
        cell.intersect(side)
        node.className = android.widget.Button::class.java.name
        node.contentDescription = label
        val r = android.graphics.Rect()
        cell.roundOut(r)
        node.setBoundsInParent(r)
        node.addAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_CLICK)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        a11y.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    /** 读屏念的键名：功能键说它做什么，字符键念键面上的那个字 */
    private fun a11yLabel(key: KeyDef): String = when (key.action) {
        KeyAction.Shift -> when {
            splitMode -> "分词"
            shiftLocked -> "大写锁定"
            shifted -> "上档，已开"
            else -> "上档"
        }
        KeyAction.Backspace -> "删除"
        KeyAction.Space -> "空格"
        KeyAction.SwitchLang -> "中英切换"
        KeyAction.Emoji -> "表情"
        KeyAction.SymbolPage -> "符号"
        KeyAction.NumberPad -> "数字键盘"
        else -> displayLabel(key)
    }

    private fun handleKey(key: KeyDef) {
        when (key.action) {
            KeyAction.Shift -> {
                if (splitMode) {
                    splitChar?.let { listener?.onKeyAction(KeyAction.Text(it), shifted = false) }
                    return
                }
                // 中文有编码、方案又没有分隔键（双拼、码表）：⇧ 没有用处，按下去只会把下一个字母
                // 顶成大写直出、打断编码，所以忽略
                if (composing && !englishMode) return
                val now = android.os.SystemClock.uptimeMillis()
                autoShifted = false
                when {
                    shiftLocked -> {
                        shiftLocked = false
                        shifted = false
                    }
                    // 双击（300ms 内二连击）→ 大写锁定
                    shifted && now - lastShiftTapTime < 300 -> shiftLocked = true
                    else -> shifted = !shifted
                }
                lastShiftTapTime = now
                invalidate()
            }
            KeyAction.SwitchSymbols -> {
                if (layer == KeyboardLayer.LETTERS && numberPad9) {
                    setLayer(KeyboardLayer.NUMBER, returnTo = KeyboardLayer.LETTERS)
                    return
                }
                setLayer(
                    when (layer) {
                        KeyboardLayer.LETTERS -> KeyboardLayer.SYMBOLS
                        KeyboardLayer.SYMBOLS -> KeyboardLayer.LETTERS
                        KeyboardLayer.NUMBER -> numberReturnLayer
                    },
                )
            }
            // 九宫页上的「123」：去 123 全键盘数字页（九宫作默认时的互切键，见 LayoutTweaks）
            KeyAction.NumberPad ->
                if (layer == KeyboardLayer.NUMBER) {
                    setLayer(KeyboardLayer.SYMBOLS)
                } else {
                    setLayer(KeyboardLayer.NUMBER, returnTo = layer)
                }
            else -> {
                // 逗号句号合一键：英文模式点按改出句点
                val action = key.textFor(englishMode)
                    ?.takeIf { it != (key.action as? KeyAction.Text)?.char }
                    ?.let { KeyAction.Text(it) }
                    ?: key.action
                listener?.onKeyAction(action, shifted || shiftLocked)
                resetSingleShift(key)
            }
        }
    }

    /** 单次 Shift：出字后自动复位（锁定态保持）；长按气泡选字与点按同一规则 */
    private fun resetSingleShift(key: KeyDef?) {
        if (shifted && !shiftLocked && key?.action is KeyAction.Text) {
            shifted = false
            autoShifted = false
            invalidate()
        }
    }
}

/**
 * 上滑 / 长按气泡符号 → 动作。ASCII 走引擎；非 ASCII 字面字符引擎翻不出 VK，直接上屏。
 * 中文标点关闭时，字面的全角引号退回半角 `"`（与同一排 h 键经引擎出半角保持对称）。
 */
internal fun symbolActionFor(c: Char, chinesePunct: Boolean): KeyAction = when {
    c.code < 0x80 -> KeyAction.Text(c)
    c == '”' || c == '“' -> if (chinesePunct) KeyAction.Commit(c.toString()) else KeyAction.Text('"')
    else -> KeyAction.Commit(c.toString())
}
