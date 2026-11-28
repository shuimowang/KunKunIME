package com.windinput.ime.service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.windinput.ime.WindInputApp
import com.windinput.ime.core.AssetsInstaller
import com.windinput.ime.core.ClipboardStore
import com.windinput.ime.core.DeferredSession
import com.windinput.ime.core.EditOp
import com.windinput.ime.core.EngineCmd
import com.windinput.ime.core.ImeSession
import com.windinput.ime.core.KeySource
import com.windinput.ime.core.RecentStore
import com.windinput.ime.core.RustCoreLoader
import com.windinput.ime.core.RustSession
import com.windinput.ime.core.SchemaEntry
import com.windinput.ime.core.UiEvent
import com.windinput.ime.core.UiTrace
import com.windinput.ime.core.UserConfig
import com.windinput.ime.core.suspendsLearning
import com.windinput.ime.core.usesDirectInput
import com.windinput.ime.core.allowsVoiceInput
import com.windinput.ime.keyboard.BackspaceGesture
import com.windinput.ime.keyboard.HardwareKeys
import com.windinput.ime.keyboard.ToolbarItem
import com.windinput.ime.keyboard.KeyHaptics
import com.windinput.ime.keyboard.BarView
import com.windinput.ime.keyboard.CandidateBarView
import com.windinput.ime.keyboard.CandidateMenuPopup
import com.windinput.ime.keyboard.InputRootView
import com.windinput.ime.keyboard.OneHandStrip
import com.windinput.ime.keyboard.InputWindowManager
import com.windinput.ime.keyboard.KeyAction
import com.windinput.ime.keyboard.KeyboardDimens
import com.windinput.ime.keyboard.KeyboardSystemBars
import com.windinput.ime.keyboard.LayoutTweaks
import com.windinput.ime.keyboard.KeyboardLayer
import com.windinput.ime.keyboard.IconSet
import com.windinput.ime.keyboard.KeyboardView
import com.windinput.ime.keyboard.KeyboardWindow
import com.windinput.ime.keyboard.LayoutRepository
import com.windinput.ime.keyboard.NumberSideSymbols
import com.windinput.ime.keyboard.Palette
import com.windinput.ime.keyboard.PanelIcon
import com.windinput.ime.keyboard.VoiceStatusView
import com.windinput.ime.panels.ClipboardWindow
import com.windinput.ime.panels.EmojiWindow
import com.windinput.ime.panels.SymbolPages
import com.windinput.ime.panels.SymbolWindow
import com.windinput.ime.core.LeadingReading
import com.windinput.ime.panels.ExpandedCandidatesWindow
import com.windinput.ime.panels.KeyboardSelectWindow
import com.windinput.ime.panels.QuickPanelWindow
import com.windinput.ime.panels.TextEditingWindow
import com.windinput.ime.settings.SettingsActivity
import com.windinput.ime.settings.SUB_ENABLED_SCHEMAS
import com.windinput.ime.settings.SUB_NUMPAD_SYMBOLS
import com.windinput.ime.settings.VoicePreferences
import com.windinput.ime.settings.VoiceSetupActivity
import com.windinput.ime.voice.VoiceInputController
import com.windinput.ime.voice.VoiceState
import java.io.File

/**
 * 输入法服务：Android IME 框架对接层。
 *
 * 结构（docs/android-ime-features.md §10 建议 1 的 InputWindow 模式）：
 *   root = BarView(工具栏↔候选双态) + 面板区(InputWindowManager)
 *   面板区窗口：键盘 / 文本编辑 / 剪贴板 / 表情，可互换
 *
 * 输入链路：键盘 UI → ImeSession → UniFFI → 与电脑版共用的 Rust 核心
 * → UiEvent → 预编辑/候选/上屏。keyPressed 返回 false 时执行默认行为。
 */
class WindInputImeService :
    InputMethodService(),
    KeyboardView.Listener,
    CandidateBarView.Listener,
    BarView.Listener,
    TextEditingWindow.Listener,
    ClipboardWindow.Listener,
    EmojiWindow.Listener,
    SymbolWindow.Listener,
    QuickPanelWindow.Listener,
    KeyboardSelectWindow.Listener,
    ExpandedCandidatesWindow.Listener {

    private lateinit var session: ImeSession

    /** 密码等敏感场景：绕过会话直输 */
    private var directInput = false

    /** 文本编辑面板的选择模式（方向键扩选） */
    private var selecting = false

    /** 选择模式的锚点（绝对偏移）；焦点端随方向键走，选区 = [min, max] */
    private var selAnchor = -1

    /** 引擎侧是否有未上屏的编码（来自候选帧的 preedit 非空） */
    private var composing = false

    /** 设置里的「退格滑动手势」开关：键盘与各面板的退格键共用 */
    private var backspaceSwipeOn = true

    /** 长按起始延迟（设置项 ui.android.long_press_delay）：键盘长按与各退格键连删共用 */
    private var longPressDelayMs = 300

    /** 剪贴板建议条开关（设置项 ui.android.clip_suggestion） */
    private var clipSuggestionEnabled = true

    /**
     * 英文自动大写开关（设置项 ui.android.auto_cap）。自动补空格不在这里：由核心触摸层补
     * （wind-mobile touch.rs），开关 ui.android.auto_space 经 setAutoSpace 下发
     */
    private var autoCapEnabled = true

    private lateinit var layoutRepository: LayoutRepository
    private lateinit var userConfig: UserConfig
    private lateinit var clipboardStore: ClipboardStore
    private lateinit var recentEmojiStore: RecentStore
    private lateinit var recentSymbolStore: RecentStore
    private lateinit var dataDirs: com.windinput.ime.core.DataDirs

    private var barView: BarView? = null
    private var voiceStatusView: VoiceStatusView? = null
    private var voiceController: VoiceInputController? = null
    private var voiceState: VoiceState? = null
    private var voiceHeld = false
    private var voiceCancelOnRelease = false
    private var cancellingVoice = false
    private var voiceInputEpoch = 0L
    private data class VoiceTarget(val epoch: Long, val connection: InputConnection)
    private var voiceTarget: VoiceTarget? = null
    private var windowManager: InputWindowManager? = null
    private var keyboardWindow: KeyboardWindow? = null
    private var textEditingWindow: TextEditingWindow? = null
    private var clipboardWindow: ClipboardWindow? = null
    private var emojiWindow: EmojiWindow? = null
    private var symbolWindow: SymbolWindow? = null
    private var expandedCandidatesWindow: ExpandedCandidatesWindow? = null
    private var quickPanelWindow: QuickPanelWindow? = null
    private var keyboardSelectWindow: KeyboardSelectWindow? = null

    /** 编码是否同时写进宿主组合区（ui.android.inline_preedit，默认关，见 onUiEvent） */
    private var inlinePreedit = false

    /** 当前方案短称（引擎推送的 `iconLabel`，用户可配），**只用于显示**，不做判断。 */
    private var modeLabel = "中"

    /**
     * 引擎当前直接输入英文（引擎推送的语义字段）。
     *
     * ⚠ 中英键**不能**用 `chineseMode` 判据：英文在本端是一个**方案**，而
     * `SelectSchema` 会把 `chinese_mode` 一并设回 true——于是切到英文方案后
     * `chineseMode` 仍是 true，键面显示「中」，再点一次又切去英文，**切不回来**。
     * 也不能用 [modeLabel] 判据：它是用户可配的显示文案。
     */
    private var latinInput = false

    override fun onCreate() {
        super.onCreate()
        val dirs = (application as WindInputApp).dataDirs
        dataDirs = dirs
        layoutRepository = LayoutRepository(dirs)
        userConfig = UserConfig(dirs.keyboardConfigFile)
        // 图标：用户目录 icons/ 下的同名 SVG 覆盖内置图标（日后主题包挂同一机制）
        IconSet.init(assets, listOf(File(dirs.mobileDir, "icons")))
        // 首次弹键盘前把布局与用户配置解析进缓存：tomlj 走 ANTLR，首次解析还要加载一大批类，
        // 放在 onCreateInputView 里就是主线程上一百多毫秒（模拟器实测，键盘弹出掉帧）
        // 配置排在最前：onCreateInputView 头一件事就是按配置定键高，它先进缓存，主线程就不用自己解析
        Thread({
            val started = SystemClock.uptimeMillis()
            userConfig.readAll()
            val configReady = SystemClock.uptimeMillis()
            layoutRepository.prewarm()
            val layoutsReady = SystemClock.uptimeMillis()
            IconSet.prewarm(PanelIcon.Glyph.entries.map { it.iconName })
            val iconsReady = SystemClock.uptimeMillis()
            SymbolPages.load(dirs)
            android.util.Log.i(
                TAG,
                "UI预热：配置 ${configReady - started}ms，布局 ${layoutsReady - configReady}ms，" +
                    "图标 ${iconsReady - layoutsReady}ms，符号 ${SystemClock.uptimeMillis() - iconsReady}ms",
            )
        }, "wind-prewarm-ui").start()
        // 核心**异步构造**：它要加载三层配置、开 redb、把词库 yaml 编译成 wdat，
        // 放在主线程会突破系统的输入分发超时。准备期间按键暂存，就绪后保序重放。
        session = run {
            val installer = AssetsInstaller(this, dirs)
            DeferredSession(
                dataDir = dirs.dataDir,
                userRoot = dirs.userRoot,
                localRoot = dirs.localRoot,
                // 出厂数据拷出也必须在后台：数 MB 逐文件流式拷贝，留在这里会连同
                // 核心构造一起把 onCreate 拖过输入分发超时（实测 ANR）
                prepareData = {
                    check(RustCoreLoader.available) { "安装包缺少中文输入核心，请重新安装困困输入法" }
                    installer.installIfNeeded()
                },
                onFailure = { message ->
                    barView?.setFailure("中文核心未就绪")
                    showToast(message)
                },
                onUnhandledKey = { action, shifted, _ -> performDefaultKeyAction(action, shifted) },
                onBufferedInputChanged = { count ->
                    barView?.setPendingInputCount(count)
                    val deferred = session as? DeferredSession
                    barView?.setPreparing(deferred != null && deferred.failureMessage == null &&
                        (!deferred.ready || deferred.busy || count > 0))
                },
                onReady = { ready ->
                    // 就绪时若已在输入，补一次焦点，否则引擎收不到当前会话
                    if (currentInputConnection != null) {
                        ready.focusGained()
                        reportPrecedingText()
                    }
                    barView?.setFailure(null)
                    barView?.setPreparing(hasBufferedInput)
                    refreshSchemaKeys()
                    // 第一次弹键盘时核心多半还没起来，方案卡片等到这里再弹
                    if (isInputViewShown && (session as? DeferredSession)?.bufferedInputCount == 0) {
                        maybeShowFirstRunPicker()
                    }
                },
                // 切方案时核心可能在现编词库（首次切到拼音模拟器上近 5 秒）：期间与未就绪同样提示，
                // 做完重读方案（空格键上的方案名、中英键的去向）
                // 有缓存的切换只要几毫秒，过 300ms 还没完才提示，免得每切一次闪一下
                onBusyChanged = { busy ->
                    if (busy) {
                        switchHandler.postDelayed(showSwitching, SWITCH_HINT_DELAY_MS)
                    } else {
                        switchHandler.removeCallbacks(showSwitching)
                        barView?.setPreparing(hasBufferedInput)
                        refreshSchemaKeys()
                        // 切方案期间推来的状态读到的是旧方案列表，切完补刷一次
                        quickPanelWindow?.refresh()
                        keyboardSelectWindow?.refresh()
                    }
                },
            ).also { it.start() }
        }
        session.listener = ::onUiEvent
        clipboardStore = ClipboardStore(this).also { it.start() }
        clipboardStore.onNewClip = { text ->
            if (!directInput && clipSuggestionEnabled) barView?.showClipSuggestion(text)
        }
        recentEmojiStore = RecentStore(this, "recent_emoji")
        recentSymbolStore = RecentStore(this, "recent_symbol")
    }

    override fun onDestroy() {
        cancelVoiceInput()
        voiceController?.destroy()
        voiceController = null
        (session as? DeferredSession)?.close()
        switchHandler.removeCallbacks(showSwitching)
        clipboardStore.stop()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        cancelVoiceInput()
        // 输入法窗口默认不进挖孔区：横屏时挖孔在侧边，键盘左边空出一条露出底下的应用。
        // 让窗口铺进去，键盘根视图再按挖孔把键往里收（InputRootView.onApplyWindowInsets）
        window.window?.let { w ->
            w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        // 旋转 / 配色变化重建输入视图时不经 onFinishInputView：挂在旧条区上的卡片要在这里收，
        // 否则按旧宽度和坐标留在新视图上（首次卡片点外面不收，会一直挂着）
        candidateMenu?.dismiss()
        schemaPicker?.dismiss()
        applyKeyboardDimens()
        // 深浅色：uiMode 变化会让系统重跑本方法，下面所有视图都按新调色板构造
        Palette.apply(this, userConfig.getString("ui.android.theme", "system"))
        window.window?.let { KeyboardSystemBars.apply(it, Palette.current) }
        // 根视图自理手势条/导航栏避让（IME 窗口不吃系统自动 padding）
        val root = InputRootView(this)
        inputRoot = root
        applyGaps()

        val bar = BarView(this, candidateListener = this, listener = this)
        barView = bar
        // Preserve BarView's exact configured height while it is INVISIBLE.
        // A MATCH_PARENT meter in a WRAP_CONTENT frame could otherwise expand
        // during its first measurement and move keys under a held finger.
        val barContainer = FrameLayout(this).apply {
            addView(bar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        voiceStatusView = VoiceStatusView(
            this,
            onStop = { finishVoiceInput() },
            onCancel = { cancelVoiceInput() },
        ).apply { visibility = View.GONE }
        barContainer.addView(voiceStatusView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        root.addView(
            barContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                KeyboardDimens.barHeightPx(resources.displayMetrics.density),
            ),
        )

        // 面板区外面再包一层横排：单手模式时在一侧放「换边 / 退出」那一条（见 applyOneHand）。
        // 条区不进这一层——候选在单手时照样满宽，能多看几个
        val panelContainer = FrameLayout(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(panelContainer, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(
            row,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        oneHandRow = row
        applyHardKeyboardOnly()
        oneHandStrip = OneHandStrip(
            this,
            onSwitchSide = { setOneHand(OneHandStrip.otherSide(oneHandMode())) },
            onExit = { setOneHand(OneHandStrip.OFF) },
        )
        applyOneHand()

        keyboardWindow = KeyboardWindow(
            listener = this,
            layoutProvider = { layer ->
                LayoutTweaks.apply(
                    layoutRepository.rows(layer), layer,
                    bottomRow = userConfig.getString("ui.android.bottom_row", LayoutTweaks.BOTTOM_MERGE),
                    numberPage = userConfig.getString("ui.android.number_page", LayoutTweaks.NUMBER_PAD9),
                )
            },
        )
        textEditingWindow = TextEditingWindow(listener = this)
        clipboardWindow = ClipboardWindow(clipboardStore, listener = this)
        emojiWindow = EmojiWindow(recentEmojiStore, listener = this)
        symbolWindow = SymbolWindow({ SymbolPages.load(dataDirs) }, recentSymbolStore, listener = this)
        expandedCandidatesWindow = ExpandedCandidatesWindow(listener = this)
        quickPanelWindow = QuickPanelWindow(listener = this)
        keyboardSelectWindow = KeyboardSelectWindow(listener = this)

        windowManager = InputWindowManager(panelContainer, keyboardWindow!!).also {
            // 面板打开时条区让位：面板自带左上角返回，不再上下两条横栏并排。
            // 面板高度 = 条区 + 键盘，所以让位前后 IME 窗口总高不变、键盘不跳。
            //
            // 例外由窗口自己声明（InputWindow.keepsBar）：展开候选面板没有自己的返回栏，
            // 返回入口就在条区尾格上，把它一并让位掉就等于展开后无路可退。
            it.onWindowChanged = { window ->
                cancelVoiceInput()
                val keyboardMode = window === keyboardWindow
                barContainer.visibility =
                    if (keyboardMode || window.keepsBar) View.VISIBLE else View.GONE
                bar.setExpanded(window === expandedCandidatesWindow)
                assocHold.set(AssocHold.Reason.EXPANDED, window === expandedCandidatesWindow)
            }
            it.attachInitial()
        }
        val deferred = session as? DeferredSession
        bar.setPreparing(deferred != null && (!deferred.ready || deferred.busy || hasBufferedInput) && deferred.failureMessage == null)
        bar.setPendingInputCount(deferred?.bufferedInputCount ?: 0)
        bar.setFailure(deferred?.failureMessage?.let { "中文核心未就绪" })
        root.post {
            android.util.Log.i(
                TAG,
                "inputView: root=${root.width}x${root.height} " +
                    "bar=${barView?.width}x${barView?.height} " +
                    "panel=${panelContainer.width}x${panelContainer.height} " +
                    "kb=${keyboardWindow?.keyboardView?.width}x${keyboardWindow?.keyboardView?.height} " +
                    "rowDp=${KeyboardDimens.rowHeightDp}",
            )
        }
        return root
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // Apply again after the IME window attaches so the system-owned navigation buttons
        // use the same light/dark appearance as the keyboard, including subsequent shows.
        window.window?.let { KeyboardSystemBars.apply(it, Palette.current) }
    }

    override fun onWindowHidden() {
        cancelVoiceInput()
        super.onWindowHidden()
    }

    private companion object {
        /** 单手模式：off / left / right；last 记着上次用的那一侧，快捷面板重新打开时回到它 */
        const val KEY_ONE_HAND = "ui.android.one_hand"
        const val KEY_ONE_HAND_LAST = "ui.android.one_hand_last"

        const val TAG = "WindIme"

        /** 退格上滑清空时向前 / 向后各取多少字：够一般输入框，超长文档清不干净好过一次读几 MB */
        const val CLEAR_ALL_MAX = 100_000

        /** 光标前文报几个字给核心：联想的下一词表只看 4 个字，留些余量 */
        const val PRECEDING_CHARS = 32

        /** 退格单删前先读光标前多少字备恢复：一下最多删一个字素（带 ZWJ 的 emoji 十几个单元），留足余量 */
        const val UNDO_PEEK_CHARS = 64

        /** 与核心 `wind-mobile` 的 AUTO_SPACE_PUNCT 一致：这几个标点会触发空格换位 */
        private const val AUTO_SPACE_PUNCT = ".,?!;:"

        /** 切方案超过这么久还没完才提示「词库准备中」 */
        const val SWITCH_HINT_DELAY_MS = 300L

        /** 英文方案 id（与 assets/data/schemas/english.schema.toml 一致） */
        const val ENGLISH_SCHEMA_ID = "english"

        /** 中英键长按里的「切换输入法」（长按菜单最后一项、卡片最后一格） */
        const val SWITCH_IME_LABEL = "切换输入法"
        const val ACTION_SWITCH_IME = "switch_ime"

        /**
         * 英文切回中文时，找不到切走前的方案（首次安装就在英文里）就回出厂默认方案。
         * 与 RustSession 的首装兜底、出厂 config.toml 的 schema.active 一致：手机用户绝大多数打全拼
         */
        const val DEFAULT_CHINESE_SCHEMA_ID = "pinyin"
        const val KEY_LAST_CHINESE = "last_chinese_schema"

        /** 按系统切换器切方案后最多等引擎报到位多久（首次切到拼音要现编词库，模拟器上近 5 秒） */
        const val AWAIT_SCHEMA_MAX_MS = 15_000L

        /** 首次使用的方案卡片弹过没有（ime_onboarding prefs） */
        const val KEY_FIRST_PICK_SHOWN = "first_pick_shown"

        /** 不是码表的方案：临时拼音对它们没有意义 */
        val NON_CODETABLE_SCHEMAS = setOf("pinyin", "shuangpin", "english")

        /**
         * 电话框九宫格左列的符号（可上下滚动，固定不可配）：拨号会用到的 `* # +` 和分隔用的 `- ( )`，
         * `,` `;` 是拨号里的暂停 / 等待。数字框的那一列用户可配，见 [NumberSideSymbols]。
         */
        val PHONE_SIDE_SYMBOLS = listOf("*", "#", "+", "-", "(", ")", ",", ";")

        /**
         * 每次按键预取的候选条数。
         *
         * **这是个性能参数，别随手调大。** 候选全量可达上千条，每条都要跨 JNI
         * 序列化、再被候选栏 `measureText` 一次，全在主线程上。24 条约够横向候选栏
         * 铺三屏——用户真滑到那儿了再续取，而绝大多数按键根本不会被滑动。
         */
        const val CANDIDATE_PREFETCH = 24

        /** 续取的步长（滚到接近尾部时取下一段） */
        const val CANDIDATE_CHUNK = 60
    }

    /** 已从核心取到的候选（绝对下标即列表下标，选词直接用它） */
    private var loadedCandidates: List<String> = emptyList()

    /** 核心单次拉取的硬上限（`candidate_pull::MAX_WINDOW`），整份重取时按它分段 */
    private val CANDIDATE_WINDOW_MAX = 200

    /** 与 [loadedCandidates] 对应的注释灰字（开了「候选显示编码提示」才有） */
    private var loadedComments: List<String> = emptyList()

    /** 与 [loadedCandidates] 对应的分段正文（正确拼音提示「苹(ping)果」）；某条为空即按原文画 */
    private var loadedSegments: List<List<com.windinput.ime.core.TextSegment>> = emptyList()

    /** 核心侧候选全量总数：`loadedCandidates.size < candidateTotal` 即还有得取 */
    private var candidateTotal = 0

    override fun onEvaluateFullscreenMode(): Boolean = false

    // ── 物理键盘 ──────────────────────────────────────────────────

    /**
     * 接了物理键盘、且系统设置不要软键盘（「使用实体键盘时显示虚拟键盘」关着）。
     * 这时只在打字中露出条区看候选，键盘面板收起——整块软键盘挡住半屏毫无用处。
     */
    private var hardKeyboardOnly = false

    /** 被引擎吃掉的按下键码：对应的抬起也要吃掉，不能只把半个按键漏给应用 */
    private val consumedHardKeys = mutableSetOf<Int>()

    override fun onEvaluateInputViewShown(): Boolean {
        refreshHardKeyboardOnly()
        return !hardKeyboardOnly || composing
    }

    /** 基类的判断（物理键盘 + 系统设置）即真值；窗口没显示过时系统不会来问，故开始输入时也刷一次 */
    private fun refreshHardKeyboardOnly() {
        val wasHardOnly = hardKeyboardOnly
        hardKeyboardOnly = !super.onEvaluateInputViewShown()
        if (wasHardOnly != hardKeyboardOnly) applyHardKeyboardOnly()
    }

    /**
     * 基类在「有物理键盘 + 设置不显示软键盘」时一律拒绝显示请求，输入法窗口根本不出来，
     * 之后再要露条区也叫不出（`requestShowSelf` 会被应用侧当成重复请求取消）。
     * 故这时照常接下显示请求，窗口一直「显示着」，内容由 [onEvaluateInputViewShown]
     * 决定：空闲时输入视图隐藏（屏幕上什么都没有），打字中才露条区。
     */
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        refreshHardKeyboardOnly()
        return hardKeyboardOnly || super.onShowInputRequested(flags, configChange)
    }

    /** 只露条区时收起面板区（键盘、单手条都在里面） */
    private fun applyHardKeyboardOnly() {
        oneHandRow?.visibility = if (hardKeyboardOnly) View.GONE else View.VISIBLE
    }

    /**
     * 物理键盘按键与软键走同一条路进引擎（[HardwareKeys] 映射成 [KeyAction]）。
     * 引擎不接的键（英文直输、空闲时的退格/回车、快捷键）原样交回系统默认处理，
     * 应用收到的就是普通按键。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        cancelVoiceInput()
        // 只露条区的模式下空闲时屏幕上什么都没有，但窗口在系统眼里是「显示着」的：
        // 交给基类会把这一下返回当成「收起输入法」吃掉，用户得按两下才退得出页面
        if (keyCode == KeyEvent.KEYCODE_BACK && hardKeyboardOnly && !composing) return false
        // 一次按压归谁，在首次按下时定；按住产生的重复按下沿用，不能半途换手——
        // 否则按住退格删完编码后，应用收到的是一串没有首次按下、也没有抬起的重复事件
        if (event.repeatCount > 0) {
            if (keyCode !in consumedHardKeys) return super.onKeyDown(keyCode, event)
            hardKeyToEngine(keyCode, event)
            return true
        }
        if (hardKeyToEngine(keyCode, event)) {
            consumedHardKeys += keyCode
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** @return 引擎是否接了这个键 */
    private fun hardKeyToEngine(keyCode: Int, event: KeyEvent): Boolean {
        if (directInput || currentInputConnection == null) return false
        // 输入法完全不可见（软键盘被收起，又不在只露条区的模式）：编码与候选无处显示，
        // 按键原样给应用，好过敲出一串看不见的编码
        if (!hardKeyboardOnly && !isInputViewShown) return false
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (!composing && !hasBufferedInput) return false
            abandonComposition()
            return true
        }
        val mapped = HardwareKeys.map(keyCode, event.getUnicodeChar(event.metaState), event.metaState)
            ?: return false
        return engineKey(mapped.action, mapped.shifted, KeySource.PHYSICAL)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        cancelVoiceInput()
        if (consumedHardKeys.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    /** 横竖屏独立键高（twinInt 模式）：旋转会触发 onCreateInputView 重建，天然生效 */
    private fun applyKeyboardDimens() {
        KeyboardDimens.rowHeightDp = configuredRowHeightDp()
        val (preedit, main) = barDims()
        KeyboardDimens.barPreeditDp = preedit
        KeyboardDimens.barMainRowDp = main
    }

    /** 设置里当前方向对应的键行高（dp） */
    private fun configuredRowHeightDp(): Int {
        val portrait =
            resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        return if (portrait) {
            userConfig.getInt("ui.android.row_height_portrait", 60)
        } else {
            userConfig.getInt("ui.android.row_height_landscape", 44)
        }
    }

    /**
     * 条区两段高度（编码行, 候选/工具栏行）。横竖屏同高：候选字号不随横屏变小，此前横屏收到
     * 14 + 32dp，候选与编码挤得发扁（真机反馈「太矮」）；横屏省高度靠键行高（单独可调）。
     * 编码写在输入框里（ui.android.inline_preedit）时**不要编码行**：编码只出现在一处，
     * 两处都写是重复（真机反馈），条区也因此矮一截。
     */
    private fun barDims(): Pair<Float, Float> {
        val inline = userConfig.getBoolean("ui.android.inline_preedit", false)
        return if (inline) 0f to 42f else 19f to 38f
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        cancelVoiceInput()
        super.onStartInput(attribute, restarting)
        hasInlineComposition = false // 换了输入框，宿主那边没有我们挂的组合区
        deleteUndo.invalidate() // 删除记录只对原来那个输入框有效
        // 先定学习开关再给焦点：焦点一到，引擎就可能开始记东西
        fieldSuspendsLearning = suspendsLearning(attribute?.inputType ?: 0, attribute?.imeOptions ?: 0)
        applyLearningSuspended()
        // 密码框直输在这里就要定：接物理键盘时窗口可能根本不弹，onStartInputView 不来，
        // 只在那里判的话会沿用上一个输入框的结论
        directInput = usesDirectInput(attribute?.inputType ?: 0)
        consumedHardKeys.clear()
        refreshHardKeyboardOnly()
        session.focusGained()
        reportPrecedingText()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        cancelVoiceInput()
        super.onStartInputView(info, restarting)
        // 一次检查文件、批量读内存；下次弹出时重新检查，设置页写入/外部导入仍会生效。
        val settings = userConfig.snapshot()
        // 诊断：只记包名与输入类型位，不含输入框里的文字 / 提示 / 标签
        UiTrace.add(
            UiTrace.INPUT,
            UiTrace.describeInput(
                info?.packageName,
                info?.inputType ?: 0,
                info?.imeOptions ?: 0,
                restarting,
                window?.window?.attributes?.layoutInDisplayCutoutMode,
            ),
        )
        // 配色核对：视图是按构造那一刻的深浅色画的，系统没发配置变更时不会重建。
        // 对不上（刚装好时拿到了旧配置、或设置页改了深浅色）就当场重建整个输入视图。
        // 条区高度、键行高同理：设置页改了就要按新值重建（键行高此前漏判，要改别的 UI 项才生效）
        if (Palette.resolve(this, settings.getString("ui.android.theme", "system")) !== Palette.current ||
            configuredRowHeightDp() != KeyboardDimens.rowHeightDp ||
            barDims() != (KeyboardDimens.barPreeditDp to KeyboardDimens.barMainRowDp)
        ) {
            UiTrace.add(UiTrace.REBUILD, "输入视图按设置/配色变化重建")
            setInputView(onCreateInputView())
        }
        session.candidateComments = settings.getBoolean("ui.android.candidate_comment", false)
        applyOneHand() // 设置页改了单手模式：弹键盘时按新配置摆
        KeyHaptics.init(this)
        KeyHaptics.enabled = settings.getBoolean("ui.android.haptic", true)
        KeyHaptics.strength = settings.getInt("ui.android.haptic_strength", KeyHaptics.DEFAULT_STRENGTH)
        backspaceSwipeOn = settings.getBoolean("ui.android.gesture_backspace_select", true)
        longPressDelayMs = settings.getInt("ui.android.long_press_delay", 300)
        keyboardWindow?.keyboardView?.apply {
            soundEnabled = settings.getBoolean("ui.android.sound", false)
            previewEnabled = settings.getBoolean("ui.android.key_preview", false)
            longPressDelayMs = this@WindInputImeService.longPressDelayMs
            spaceSwipeEnabled = settings.getBoolean("ui.android.gesture_space_cursor", true)
            val spaceLongPress = settings.getString("ui.android.space_long_press", "voice")
            spaceLongPressEnabled = spaceLongPress != "none"
            spaceVoiceEnabled = spaceLongPress == "voice"
            backspaceSwipeEnabled = backspaceSwipeOn
            swipeSymbolsEnabled = settings.getBoolean("ui.android.gesture_swipe_symbols", true)
            showSwipeHints = settings.getBoolean("ui.android.show_swipe_hints", false)
            enterLabel = enterActionLabel(info)
        }
        barView?.candidateBar?.textSizeSp = settings.getInt("ui.android.candidate_text_sp", 21).toFloat()
        barView?.candidateBar?.showDividers = settings.getBoolean("ui.android.candidate_divider", false)
        barView?.showPageNo = settings.getBoolean("ui.android.candidate_page_no", false)
        barView?.iconChip = settings.getBoolean("ui.android.toolbar_icon_chip", true)
        keyboardWindow?.keyboardView?.let {
            it.numberPad9 = settings.getString("ui.android.number_page", LayoutTweaks.NUMBER_PAD9) == LayoutTweaks.NUMBER_PAD9
            it.refreshLayout()
        }
        // 输入视图可能刚重建（配色变了），无痕标记跟着重设
        barView?.setIncognito(manualIncognito || fieldSuspendsLearning)
        barView?.setPinned(visiblePins())
        clipSuggestionEnabled = settings.getBoolean("ui.android.clip_suggestion", true)
        inlinePreedit = settings.getBoolean("ui.android.inline_preedit", false)
        autoCapEnabled = settings.getBoolean("ui.android.auto_cap", true)
        session.setAutoSpace(settings.getBoolean("ui.android.auto_space", true))
        session.setTouchQuickTrigger(settings.getBoolean("ui.android.touch_quick_input", false))
        selecting = false
        applyGaps()
        windowManager?.showKeyboard()
        applyEditorScene(info)
        refreshSchemaKeys()
        maybeShowFirstRunPicker()
    }

    /** 光标动了（上屏、删除、点击挪光标）：重新判断要不要句首大写，光标前文报给核心 */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        if (oldSelStart != newSelStart || oldSelEnd != newSelEnd) cancelVoiceInput()
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateAutoCaps()
        deleteUndo.onSelectionUpdate(oldSelStart, oldSelEnd, newSelStart, newSelEnd, SystemClock.uptimeMillis())
        // 挂着内联编码时光标前是组合区里的码，不是正文；组合开始前报的那份仍然对
        if (candidatesStart < 0) reportPrecedingText()
    }

    /**
     * 光标前文报给核心（联想用它把逐字上屏拆开的词凑回来）。
     *
     * 读的时机是光标变动之后：`getTextBeforeCursor` 与我们发出的编辑同走一条队列，
     * 读到的总是已含此前全部上屏的正文，即使这次回调是为更早的一次上屏来的。
     * 密码框不读。
     */
    private fun reportPrecedingText() {
        val text = if (directInput) null else currentInputConnection?.getTextBeforeCursor(PRECEDING_CHARS, 0)
        session.setPrecedingText(text?.toString().orEmpty())
    }

    /**
     * 句首自动大写：英文模式、没在打字、输入框声明了大写（`CAP_*` 标志，由
     * `getCursorCapsMode` 结合光标前文判定句首）时给键盘一个单次 Shift。
     * 网址、邮箱、密码框不给——这几类本就不带 `CAP_*`，这里再明确排除一次。
     */
    private fun updateAutoCaps() {
        val kv = keyboardWindow?.keyboardView ?: return
        val info = currentInputEditorInfo
        val ic = currentInputConnection
        val on = autoCapEnabled && latinInput && !directInput && !composing &&
            info != null && ic != null && !noAutoCapField(info) &&
            ic.getCursorCapsMode(info.inputType) != 0
        kv.setAutoCaps(on)
    }

    private fun noAutoCapField(info: EditorInfo): Boolean {
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return (info.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT ||
            variation in setOf(
                InputType.TYPE_TEXT_VARIATION_URI,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )
    }

    /**
     * 键盘收起（按返回键、宿主主动隐藏）：**清掉组合态**。
     *
     * 这里与 [onFinishInput] 不是一回事：收起键盘只走这一个回调，输入连接还在。
     * 不清的话，编码栏里打到一半的码会在下次弹出键盘时原样冒出来，而用户早已
     * 忘了它——接着按的第一个键就拼进了那串旧码，出来的字完全对不上。
     */
    override fun onFinishInputView(finishingInput: Boolean) {
        cancelVoiceInput()
        candidateMenu?.dismiss()
        schemaPicker?.dismiss()
        assocHold.clear()
        abandonComposition()
        // 「词库准备中」不在这里撤：它画在条区里随窗口一起收；撤了再弹键盘时词库还没好，提示却没了
        selecting = false
        super.onFinishInputView(finishingInput)
    }

    /**
     * 用户**点了编辑框**（光标被挪到了别处）：组合态跟着作废。
     *
     * 五笔/拼音的编码是相对「光标当时的位置」积累的，光标一挪，再上屏就落在
     * 新位置，用户看到的是一串字凭空插进了别处。fcitx5 与 Gboard 都在这一刻清组合。
     *
     * onViewClicked 已标记 deprecated，但部分编辑器仍会调用；同时接收
     * [onUpdateEditorToolType]，两个回调都清理组合。
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        cancelVoiceInput()
        super.onViewClicked(focusChanged)
        abandonComposition()
    }

    override fun onUpdateEditorToolType(toolType: Int) {
        cancelVoiceInput()
        super.onUpdateEditorToolType(toolType)
        abandonComposition()
    }

    /**
     * 丢弃当前组合（不上屏）。核心走 ESC 语义；宿主侧若开了内联编码，把组合区收掉，
     * 否则那串灰字会留在正文里。空组合时两步都是空操作，随便调。
     */
    private fun abandonComposition() {
        session.reset()
        currentInputConnection?.let(::clearInlineComposition)
    }

    /**
     * 宿主组合区里是否挂着我们写进去的内联编码（只在开了 ui.android.inline_preedit 时会有）。
     *
     * 清组合区要分两种：有内联编码时用 `setComposingText("")` 把那串码**删掉**；没有时什么都
     * 不做。`finishComposingText` 不能用来「清」——它只去掉下划线，码会原样留成正文（开着内联编码
     * 打 a 再退格，正文里剩一个 a）；`setComposingText("")` 也不能无条件调——没有组合区时它
     * 作用在选区上，会把用户选中的文字删掉。
     */
    private var hasInlineComposition = false

    /** 退格下滑恢复用：最近一次输入框里的删除，见 [DeleteUndo] */
    private val deleteUndo = DeleteUndo()

    /** 引擎这一批编辑往输入框里写过字（上屏 / 回退替换）：退格单删期间出现就不记那次删除 */
    private var wroteText = false

    private fun clearInlineComposition(ic: android.view.inputmethod.InputConnection) {
        if (hasInlineComposition) ic.setComposingText("", 1)
        hasInlineComposition = false
    }

    /**
     * imeOptions → 回车键动作标签；`null` = 显示默认换行图标。
     *
     * ⚠ 光看 `IME_MASK_ACTION` 是不够的：多行文本框里系统常给出 `IME_ACTION_NEXT`
     * （因为表单后面还有输入框），但**回车在多行框里恒为换行**，照抄就会出现
     * 「键上写着『下一项』、按下去却是换行」——真机实测到的就是这个。
     *
     * 两条否决优先于 action：
     * - `TYPE_TEXT_FLAG_MULTI_LINE`：多行框回车换行，action 只能由别的方式触发
     * - `IME_FLAG_NO_ENTER_ACTION`：宿主明确声明「回车不要执行 action」
     */
    private fun enterActionLabel(info: EditorInfo?): String? {
        if (info == null) return null
        if (!enterPerformsAction(info)) return null
        if (info.actionLabel != null) return info.actionLabel.toString()
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_SEARCH -> "搜索"
            EditorInfo.IME_ACTION_SEND -> "发送"
            EditorInfo.IME_ACTION_NEXT -> "下一项"
            EditorInfo.IME_ACTION_DONE -> "完成"
            EditorInfo.IME_ACTION_PREVIOUS -> "上一项"
            else -> null
        }
    }

    /**
     * 回车是否应执行编辑器动作（而非换行）。
     *
     * **标签与行为共用这一个判据**——分成两处判必然漂移，那正是「显示下一项、
     * 实际换行」的成因。
     */
    private fun enterPerformsAction(info: EditorInfo?): Boolean {
        if (info == null) return false
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return false
        if (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) return false
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        return action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
    }

    override fun onFinishInput() {
        cancelVoiceInput()
        consumedHardKeys.clear()
        session.focusLost()
        super.onFinishInput()
    }

    /** EditorInfo 场景感知：密码、数字、电话和日期框直输；数字类初始切符号层。 */
    private fun applyEditorScene(info: EditorInfo?) {
        val inputType = info?.inputType ?: 0
        val clazz = inputType and InputType.TYPE_MASK_CLASS

        directInput = usesDirectInput(inputType)
        if (directInput) {
            session.reset()
        }

        val numericScene = clazz == InputType.TYPE_CLASS_NUMBER ||
            clazz == InputType.TYPE_CLASS_PHONE ||
            clazz == InputType.TYPE_CLASS_DATETIME
        keyboardWindow?.keyboardView?.let { kv ->
            kv.setLayer(if (numericScene) KeyboardLayer.NUMBER else KeyboardLayer.LETTERS)
            val phone = clazz == InputType.TYPE_CLASS_PHONE
            kv.sideSymbols = if (phone) {
                PHONE_SIDE_SYMBOLS
            } else {
                NumberSideSymbols.parse(userConfig.readAll()[NumberSideSymbols.CONFIG_KEY] as? String)
            }
            kv.sideCustomize = !phone
            kv.resetSideScroll()
        }
    }

    /**
     * 把模式与当前方案同步到键盘：中英键、空格上的方案名、⇧ 能否当「分词」。
     *
     * 除了 Status 推送，核心就绪和每次弹出键盘时也要调：核心起来后**不会**主动推一次
     * 初始状态（工具栏推送按「有变化才发」去重），只靠推送的话，刚装好、刚重启时
     * 空格上一直是空的。
     */
    private fun refreshSchemaKeys() {
        val schemas = session.schemas()
        val active = schemas.firstOrNull { it.active }
        // 切到英文方案时，方案列表先变、模式短称后到：只看短称的话中间那一下空格上写的是
        // 英文方案自己的名字（「清风英文」），随即又变「英文」——看着就是闪一下
        val english = latinInput || active?.id == ENGLISH_SCHEMA_ID
        syncSubtypes(schemas, if (english && schemas.any { it.id == ENGLISH_SCHEMA_ID }) ENGLISH_SCHEMA_ID else active?.id)
        // ⚠ 别写成 keyboardView.apply { modeLabel = modeLabel }：块内的 modeLabel
        // 解析成视图自己的属性，等于自赋值，英文模式永远进不去
        keyboardWindow?.keyboardView?.let { kv ->
            kv.modeLabel = modeLabel
            kv.latinInput = latinInput
            // 空格上的灰字：英文写「英文」（引擎没装英文方案时退回系统英文模式，
            // 那时 active 仍是切走前的中文方案），否则写当前方案名
            kv.spaceLabel = if (english) "英文" else active?.name.orEmpty()
            kv.splitChar = if (english) null else session.separatorKey()
        }
        updateAutoCaps()
    }

    // ── 会话事件 → 编辑器/候选条 ───────────────────────────────────

    private fun onUiEvent(event: UiEvent) {
        val ic = currentInputConnection
        // 候选一变，菜单记着的下标就指向别的词了——收起，宁可让用户再长按一次
        if (event !is UiEvent.Edits) candidateMenu?.dismiss()
        when (event) {
            is UiEvent.Edits -> applyEdits(ic ?: return, event.ops)
            is UiEvent.Candidates -> {
                // 引擎推来真候选：条区不再是符号页的历史
                symbolHistoryInBar = false
                // 推送帧只用来画编码栏（编码/短称/页码）；候选列表另行**拉取**——
                // 帧里的是当页 6~9 条，装不下一条可滚动的长列表。
                val page = if (!event.hasCandidates) {
                    // 帧说没候选就是没候选，不必再往核心跑一趟
                    com.windinput.ime.core.CandidatePage(emptyList(), 0, 0, 0)
                } else {
                    session.candidateWindow(0, CANDIDATE_PREFETCH)
                }
                loadedCandidates = page.items
                loadedComments = page.comments
                loadedSegments = page.segments
                candidateTotal = page.total
                val composingChanged = composing != event.composing
                composing = event.composing
                // 只露条区的模式下，开始/结束打字时跟着显示/收起输入法窗口
                if (hardKeyboardOnly && composingChanged) updateInputViewShown()
                keyboardWindow?.keyboardView?.let { kv ->
                    kv.composing = composing
                    // 分隔符键随设置（五笔整句开关、分隔符策略）变，开始打字时现问一次
                    if (composingChanged && composing && !latinInput) kv.splitChar = session.separatorKey()
                }
                // 打完一个词（编码清空）后再判一次句首大写：上屏回调可能早于这一帧
                updateAutoCaps()

                barView?.setCandidateFrame(
                    items = loadedCandidates,
                    comments = loadedComments,
                    segments = loadedSegments,
                    preedit = event.preedit,
                    modeLabel = event.modeLabel,
                    page = event.page,
                    totalPages = event.totalPages,
                )
                expandedCandidatesWindow?.setCandidates(loadedCandidates, loadedComments, loadedSegments)
                if (windowManager?.current === expandedCandidatesWindow) refreshReadings()
                // 候选清空时若停留在展开面板，收回键盘
                if (loadedCandidates.isEmpty() &&
                    windowManager?.current === expandedCandidatesWindow
                ) {
                    windowManager?.showKeyboard()
                }
            }
            is UiEvent.Status -> {
                modeLabel = event.iconLabel.ifEmpty { if (event.chineseMode) "中" else "英" }
                latinInput = event.latinInput
                refreshSchemaKeys()
                // 键面标点形态跟引擎的真值走：英文模式或关了中文标点，键面就显示 ASCII
                keyboardWindow?.keyboardView?.chinesePunct = event.chineseMode && event.chinesePunct
                quickPanelWindow?.updateStatus(event)
                keyboardSelectWindow?.updateStatus(event)
            }
            is UiEvent.Toast -> showToast(event.text)
        }
    }

    /**
     * 执行核心下发的编辑指令流。
     *
     * 每条指令都有 `InputConnection` 的直接对应物——这正是把按键结果从「上屏文本 +
     * 组合区」的三元组改成指令流的理由：智能符号配对、回退替换、配对跳出以前在这里
     * 无处落地，只能被丢弃（不报错、不掉键，单纯功能没有）。
     *
     * `beginBatchEdit` 包住整批：一次按键的多条指令（如「上屏 + 开新组合」）在宿主
     * 撤销栈里应该是一步，也避免中间态触发多次 reflow。
     */
    private fun applyEdits(ic: android.view.inputmethod.InputConnection, ops: List<EditOp>) {
        if (ops.isEmpty()) return
        if (ops.any { op ->
            when (op) {
                is EditOp.SetComposition -> if (op.text.isEmpty() || isCompositionPlaceholder(op.text)) {
                    hasInlineComposition
                } else inlinePreedit
                else -> true
            }
        }) cancelVoiceInput()
        ic.beginBatchEdit()
        try {
            for (op in ops) {
                when (op) {
                    is EditOp.Commit -> {
                        // commitText 会顶掉组合区里的内联编码
                        ic.commitText(op.text, 1)
                        hasInlineComposition = false
                        wroteText = true
                        deleteUndo.invalidate()
                    }
                    is EditOp.SetComposition -> {
                        if (op.text.isEmpty() || isCompositionPlaceholder(op.text)) {
                            // 空 = 核心要清组合区（退格清空、ESC…）；占位组合（联想态挂的空格等）
                            // 是桌面 TSF 给候选窗定位光标用的，手机上没有这回事，写进来就是输入框
                            // 里一个带下划线的空格（真机反馈）。两种都只清掉我们的内联编码
                            clearInlineComposition(ic)
                        } else if (inlinePreedit) {
                            // 编码默认只显示在自绘编码栏：五笔码嵌在正文里是噪声，且与
                            // 编码栏重复。想要宿主内联的用户开 ui.android.inline_preedit。
                            hasInlineComposition = ic.setInlineComposition(op.text, op.caret)
                        }
                    }
                    // 按**码点**删除：核心按字符计数，deleteSurroundingText 的单位是
                    // UTF-16 code unit，emoji 等增补平面字符会被删半个。
                    is EditOp.DeleteBackward ->
                        ic.deleteSurroundingTextInCodePoints(op.count, 0)
                    is EditOp.ReplaceBackward -> {
                        ic.deleteSurroundingTextInCodePoints(op.count, 0)
                        ic.commitText(op.text, 1)
                        wroteText = true
                        deleteUndo.invalidate()
                    }
                    is EditOp.MoveCursor -> moveCursor(ic, op.delta)
                }
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    /**
     * 核心的占位组合：只由空格 / ZWSP / U+2800 组成（核心按应用兼容规则在三者里挑，见主仓
     * `composition_placeholder`）。移动端原则上不往输入框里放任何占位符。
     */
    private fun isCompositionPlaceholder(text: String): Boolean =
        text.all { it == ' ' || it == '\u200B' || it == '\u2800' }

    /** 相对移动光标：取当前选区绝对位置再 setSelection，比连发方向键可靠 */
    private fun moveCursor(ic: android.view.inputmethod.InputConnection, delta: Int) {
        if (delta == 0) return
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val text = extracted?.text
        val target = if (text != null && extracted.startOffset >= 0) {
            codePointTarget(text, extracted.selectionEnd, delta)?.let { extracted.startOffset + it }
        } else null
        if (target == null) {
            // 拿不到选区时退回方向键（部分宿主不支持 ExtractedText）
            val code = if (delta > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            repeat(kotlin.math.abs(delta)) { sendDownUpKeyEvents(code) }
            return
        }
        ic.setSelection(target, target)
    }

    /**
     * 引擎提示：用系统 Toast 而非自绘浮层。
     *
     * IME 进程可以弹 Toast（不需要窗口焦点），而自绘浮层要么占键盘空间、要么得申请
     * 悬浮窗权限——为一句「已切换到五笔」不值得。
     */
    private fun showToast(text: String) {
        if (text.isBlank()) return
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ── 键盘回调 ─────────────────────────────────────────────────

    /**
     * 文字/空格/退格/回车键进核心。翻译、英文自动空格、标点换位都在核心（`press`），这里只管
     * 直出判断。软键（[onKeyAction]）与物理键（[onKeyDown]）共用，区别只在 [source]。
     * @return true = 已处理；false = 引擎不接，调用方走默认行为
     */
    private fun engineKey(action: KeyAction, shifted: Boolean, source: KeySource = KeySource.TOUCH): Boolean {
        // 前置条件：直出（密码框等）绝不进核心，核心的英文判断含密码框强制英文
        if (directInput) return false
        // 联想态（没有编码、候选条上是联想）按空格：出厂同豆包、微信，收起联想、出空格，不选联想词；
        // 设置里打开「空格选首个联想」则上屏首个联想。都由核心按 `mobile.association.space_commits`
        // 处置，这里不分支。
        //
        // 句末标点可能触发「自动补的空格挪到标点后」：核心靠光标前文判断空格还在不在。
        // onUpdateSelection 的异步报告在挪光标后可能还没到，这里同步读一次，不留窗口。
        if (action is KeyAction.Text && latinInput && !composing && action.char in AUTO_SPACE_PUNCT) reportPrecedingText()
        return session.keyPressed(action, shifted, source)
    }

    private val hasBufferedInput: Boolean
        get() = ((session as? DeferredSession)?.bufferedInputCount ?: 0) > 0

    override fun onKeyAction(action: KeyAction, shifted: Boolean) {
        cancelVoiceInput()
        when (action) {
            KeyAction.Backspace -> recordBackwardDelete { performKeyAction(action, shifted) }
            else -> {
                // 输入任何字符都让「恢复刚删的」作废：之后再恢复会插到新字后面
                if (action is KeyAction.Text || action is KeyAction.Commit ||
                    action == KeyAction.Space || action == KeyAction.Enter
                ) {
                    deleteUndo.invalidate()
                }
                performKeyAction(action, shifted)
            }
        }
    }

    override fun onKeyboardInteraction() {
        cancelVoiceInput()
    }

    private fun performKeyAction(action: KeyAction, shifted: Boolean) {
        // 中英切换 / 表情键是宿主侧动作，不走引擎按键路径
        if (action == KeyAction.SwitchLang) {
            toggleLanguage()
            return
        }
        if (action == KeyAction.Emoji) {
            emojiWindow?.let { windowManager?.show(it) }
            return
        }
        if (action == KeyAction.SymbolPage) {
            symbolWindow?.let { windowManager?.show(it) }
            return
        }
        if (action is KeyAction.Commit) {
            // 快捷输入等文本缓冲模式下数字页的数字、算符要进缓冲（`;12+3`），由核心判定；
            // 其余情况核心不收，照旧原样上屏（中文下句点不变「。」，有编码时数字不选词）
            commitDirect(action.text)
            return
        }
        if (engineKey(action, shifted)) return
        performDefaultKeyAction(action, shifted)
    }

    /** Normal host fallback, also used for a deferred key the engine declines after replay. */
    private fun performDefaultKeyAction(action: KeyAction, shifted: Boolean) {
        cancelVoiceInput()
        if (action is KeyAction.Commit) {
            commitDirectNow(action.text)
            return
        }
        val ic = currentInputConnection
        if (ic == null) {
            android.util.Log.w(TAG, "默认行为无法执行：currentInputConnection 为 null action=$action")
            com.windinput.ime.core.KeyDropStats.global.record(com.windinput.ime.core.KeyDropStats.Reason.NO_INPUT_CONNECTION)
            return
        }
        when (action) {
            is KeyAction.Text -> {
                val ch = if (shifted) action.char.uppercaseChar() else action.char
                ic.commitText(ch.toString(), 1)
            }
            KeyAction.Space -> ic.commitText(" ", 1)
            KeyAction.Backspace -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            // 与 enterActionLabel 共用 enterPerformsAction：键面写什么，按下就做什么
            KeyAction.Enter -> {
                if (enterPerformsAction(currentInputEditorInfo)) {
                    sendDefaultEditorAction(true)
                } else {
                    ic.commitText("\n", 1)
                }
            }
            // Shift/SwitchSymbols 由 KeyboardView 内部消化，不会到达这里
            else -> Unit
        }
    }

    private var candidateMenu: CandidateMenuPopup? = null

    /**
     * 正在操作联想候选（按住条区、展开面板开着、长按卡片 / 菜单弹着）时暂停联想的自动隐藏，
     * 都结束了再重新计时。见 [AssocHold]。
     */
    private val assocHold = AssocHold { session.assocHold(it) }

    override fun onBarTouch(down: Boolean) = assocHold.set(AssocHold.Reason.TOUCH, down)

    /** 候选长按的提示卡 / 菜单：开着时暂停联想自动隐藏，收起（含点外面）时恢复 */
    private fun holdWhileShowing(popup: CandidateMenuPopup): CandidateMenuPopup {
        assocHold.set(AssocHold.Reason.POPUP, true)
        popup.onDismissed = { assocHold.set(AssocHold.Reason.POPUP, false) }
        return popup
    }

    /**
     * 方案选择卡片（长按中英键 / 首次使用）。与 [candidateMenu] 分开：候选一变要收的是候选菜单
     * （它记着的下标会失效），方案卡片跟候选无关——共用一个字段时，键盘刚弹出那一下的状态推送
     * 就把首次使用的卡片收掉了。
     */
    private var schemaPicker: CandidateMenuPopup? = null

    private var inputRoot: InputRootView? = null

    // ── 单手模式（设置项 ui.android.one_hand：off / left / right）──────────────

    private var oneHandRow: LinearLayout? = null
    private var oneHandStrip: OneHandStrip? = null

    private fun oneHandMode(): String = userConfig.getString(KEY_ONE_HAND, OneHandStrip.OFF)

    /** 按配置摆放让出的那一条：右手模式键盘靠右、条在左；左手相反；关着就不放 */
    private fun applyOneHand() {
        val row = oneHandRow ?: return
        val strip = oneHandStrip ?: return
        val mode = oneHandMode()
        row.removeView(strip)
        if (mode != OneHandStrip.LEFT && mode != OneHandStrip.RIGHT) return
        val width = (resources.displayMetrics.widthPixels * OneHandStrip.WIDTH_FRACTION).toInt()
        val lp = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.MATCH_PARENT)
        if (mode == OneHandStrip.RIGHT) row.addView(strip, 0, lp) else row.addView(strip, lp)
    }

    private fun setOneHand(mode: String) {
        cancelVoiceInput()
        userConfig.put(KEY_ONE_HAND, mode)
        if (mode != OneHandStrip.OFF) userConfig.put(KEY_ONE_HAND_LAST, mode)
        applyOneHand()
        quickPanelWindow?.refresh()
    }

    override fun oneHand(): Boolean = oneHandMode() != OneHandStrip.OFF

    override fun onToggleOneHand() =
        setOneHand(OneHandStrip.toggled(oneHandMode(), userConfig.getString(KEY_ONE_HAND_LAST, "")))

    /** 键盘边距（设置页「键盘布局 → 边距」）；每次弹出键盘重读，改完不用重启 */
    private fun applyGaps() {
        val d = resources.displayMetrics.density
        inputRoot?.setGaps(
            bottomPx = (userConfig.getInt("ui.android.keyboard_bottom_gap", 0) * d).toInt(),
            sidePx = (userConfig.getInt("ui.android.keyboard_side_gap", 0) * d).toInt(),
        )
    }

    private val switchHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val showSwitching = Runnable { barView?.setPreparing(true) }

    override fun onCandidateLongPressed(index: Int) = showCandidateMenu(index)

    override fun onExpandedCandidateLongPressed(index: Int) = showCandidateMenu(index)

    /**
     * 候选长按：先弹提示卡（编码 / 读音 / 释义…，与电脑版悬停气泡同一份模板），卡上「更多操作」
     * 再换成菜单。一段提示都没有的候选（标点、符号）直接给菜单，不弹一张空卡片。
     */
    private fun showCandidateMenu(index: Int) {
        cancelVoiceInput()
        val bar = barView ?: return
        // 标题取已加载的那份（长按的必然是看得见的候选），不为一个字再跨一次 JNI、再算一遍注释
        val title = loadedCandidates.getOrNull(index)
            ?: session.candidateWindow(index, 1).items.firstOrNull().orEmpty()
        val sections = session.candidateTooltip(index)
        candidateMenu?.dismiss()
        if (sections.isEmpty()) {
            showCandidateActions(index, title)
            return
        }
        // 没有可做的操作（核心给的菜单为空）就不放「更多操作」：点了只会让卡片消失
        val hasActions = session.candidateMenu(index).isNotEmpty()
        candidateMenu = holdWhileShowing(CandidateMenuPopup(bar)).also { card ->
            card.showTooltip(
                title,
                sections,
                onCopy = { copyCandidate(title) },
                onMore = if (hasActions) ({ showCandidateActions(index, title) }) else null,
            )
        }
    }

    /**
     * 候选操作菜单。菜单项由核心按候选类型给（普通词：置顶 / 前移 / 删除 / 恢复默认 …；
     * 上屏历史：删除此历史 …），与电脑版右键菜单同一份。执行后核心会推送新的候选，
     * 不用这里刷新。「复制」核心不管，在这里写剪贴板。
     */
    private fun showCandidateActions(index: Int, title: String) {
        val bar = barView ?: return
        val items = session.candidateMenu(index)
        candidateMenu?.dismiss()
        if (items.isEmpty()) return
        candidateMenu = holdWhileShowing(CandidateMenuPopup(bar)).also { menu ->
            menu.show(title, items) { entry ->
                if (entry.action == "copy") {
                    copyCandidate(title)
                } else {
                    session.candidateMenuRun(index, entry.action)
                }
            }
        }
    }

    private fun copyCandidate(text: String) {
        getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("candidate", text))
    }

    override fun onCandidateSelected(index: Int) {
        cancelVoiceInput()
        if (symbolHistoryInBar) {
            loadedCandidates.getOrNull(index)?.let {
                recentSymbolStore.record(it)
                commitDirect(it)
                // 与点符号格子一致：没锁定就退回键盘，锁定则停在本页继续点
                if (!isSymbolLocked()) onSymbolBack()
            }
            return
        }
        session.candidateSelected(index)
    }

    /**
     * 候选栏/展开面板滚到接近尾部：向核心续取一段。
     *
     * 两个视图共用同一份 [loadedCandidates]（绝对下标即列表下标），所以续取一次
     * 两边都受益，不会各拉各的。取满即不再请求——视图那边只管发信号，
     * 「还有没有」的真值在核心，视图自己记一份必然漂移。
     */
    override fun onNeedMoreCandidates() {
        if (loadedCandidates.size >= candidateTotal) return
        val page = session.candidateWindow(loadedCandidates.size, CANDIDATE_CHUNK)
        if (page.rebuilt) {
            // 核心扩容重建了：已显示的前段可能换了顺序，整份重取到同样的长度再加一段，
            // 原地替换（保留滚动位置）——只往后接的话，按下标选词会选到别的词
            // 分段取：核心单次最多给 MAX_WINDOW（200）条，已显示的可能比这还多
            val target = loadedCandidates.size + CANDIDATE_CHUNK
            val items = ArrayList<String>(target)
            val comments = ArrayList<String>(target)
            val segments = ArrayList<List<com.windinput.ime.core.TextSegment>>(target)
            var total = page.total
            while (items.size < target) {
                val w = session.candidateWindow(items.size, minOf(target - items.size, CANDIDATE_WINDOW_MAX))
                if (w.items.isEmpty()) break
                // 注释可能比候选短（开关中途切换）：按下标补齐后再接，保证两边一一对应
                comments += com.windinput.ime.keyboard.CandidateBarView.alignComments(w.comments, w.items.size)
                    .takeIf { w.comments.isNotEmpty() } ?: List(w.items.size) { "" }
                segments += com.windinput.ime.keyboard.CandidateText.align(w.segments, w.items.size, emptyList())
                items += w.items
                total = w.total
            }
            loadedCandidates = items
            loadedComments = comments
            loadedSegments = segments
            candidateTotal = total
            barView?.replaceCandidates(items, comments, segments)
            expandedCandidatesWindow?.replaceCandidates(items, comments, segments)
            return
        }
        if (page.items.isEmpty()) return
        loadedCandidates = loadedCandidates + page.items
        // 与视图同一种对齐：注释一律按候选下标对得上，谁按下标取都不错位
        if (page.comments.isNotEmpty()) {
            loadedComments = com.windinput.ime.keyboard.CandidateBarView.alignComments(
                loadedComments,
                loadedCandidates.size - page.items.size,
            ) + page.comments
        }
        if (page.segments.isNotEmpty()) {
            loadedSegments = com.windinput.ime.keyboard.CandidateText.align(
                loadedSegments,
                loadedCandidates.size - page.items.size,
                emptyList(),
            ) + page.segments
        }
        candidateTotal = page.total
        barView?.appendCandidates(page.items, page.comments, page.segments)
        expandedCandidatesWindow?.appendCandidates(page.items, page.comments, page.segments)
    }

    /**
     * 中英键：中文方案 ↔ **英文方案**（带词库补全：打 `beau` 出 `beautiful`）。
     *
     * 曾经只能切「系统英文模式」（字母直出、无候选）：切到英文方案后候选来自英文引擎、
     * 上屏却走中文码表。主仓已修（`rust/wind-android/tests/key_flow.rs`
     * `english_schema_commits_latin_not_hanzi` 转绿），现在走方案。引擎里没装英文方案时
     * 仍退回系统英文模式。
     */
    private fun toggleLanguage() {
        // 有编码时先把字母原文上屏再切（两家相同）：用户在中文里打了半截想改打英文，
        // 码不该被吞掉。有编码时回车就是「上屏原文」（见回车键「确定」），直接复用。
        if ((composing || hasBufferedInput) && !directInput) {
            session.keyPressed(KeyAction.Enter, false)
        }
        val schemas = session.schemas()
        val active = schemas.firstOrNull { it.active }
        if (latinInput) {
            // 回到切走前的中文方案。系统英文模式下引擎报的 active 仍是那个中文方案；
            // 英文方案下 active 是英文，只能靠切走时记下的 id（持久化：输入法进程在
            // 英文里被杀掉后也能原路回去）。
            //
            // ⚠ 绝不要退化成「列表里第一个非英文方案」：方案按文件名排序，
            // 装上拼音后第一个非英文是 pinyin——五笔用户切一次英文再切回来就掉进全拼。
            val lastId = statePrefs.getString(KEY_LAST_CHINESE, null)
            val target = active?.takeIf { it.id != ENGLISH_SCHEMA_ID }
                ?: schemas.firstOrNull { it.id == lastId }
                ?: schemas.firstOrNull { it.id == DEFAULT_CHINESE_SCHEMA_ID }
                ?: return
            session.runCommand(EngineCmd.SelectSchema(target.index))
            return
        }
        active?.let { statePrefs.edit().putString(KEY_LAST_CHINESE, it.id).apply() }
        val english = schemas.firstOrNull { it.id == ENGLISH_SCHEMA_ID }
        session.runCommand(english?.let { EngineCmd.SelectSchema(it.index) } ?: EngineCmd.English)
    }

    // ── 系统输入法列表里的当前方案（子类型）────────────────────────

    private val schemaSubtypes by lazy { SchemaSubtypes(this) }

    /** 用户在系统切换器里选了方案、但核心还没就绪：就绪后再切 */
    private var pendingSystemSchema: String? = null

    /**
     * 已按系统切换器里的选择发了切方案、引擎还没报到位：期间推来的状态还是旧方案，
     * 不能拿它去同步系统（会把用户刚选的子类型又改回去）
     */
    private var awaitingSchema: String? = null
    private var awaitingSince = 0L

    /**
     * 方案变了就同步系统当前子类型（[SchemaSubtypes]）。系统那边先被用户改过、还没切过来的，
     * 先按用户的选择切方案，切完的状态推送会再走到这里同步。
     */
    private fun syncSubtypes(schemas: List<SchemaEntry>, currentId: String?) {
        if (schemas.isEmpty()) return
        pendingSystemSchema?.let { wanted ->
            pendingSystemSchema = null
            if (applySystemSchema(schemas, wanted)) return
        }
        awaitingSchema?.let { wanted ->
            // 引擎拒绝了这次切换（或首次编词库太久）也不能一直不同步：等够了就放弃
            val stale = SystemClock.uptimeMillis() - awaitingSince > AWAIT_SCHEMA_MAX_MS
            if (currentId != wanted && !stale) return
            awaitingSchema = null
        }
        schemaSubtypes.sync(schemas, currentId)
    }

    /** 切到系统子类型对应的方案；已是当前方案或没这个方案时不动。@return 是否发了切换 */
    private fun applySystemSchema(schemas: List<SchemaEntry>, id: String): Boolean {
        val current = schemas.getOrNull(currentSchemaIndex(schemas))
        if (current?.id == id) return false
        val target = schemas.firstOrNull { it.id == id } ?: return false
        awaitingSchema = id
        awaitingSince = SystemClock.uptimeMillis()
        selectSchemaFromKey(schemas, target)
        return true
    }

    // 进程冷启动时**不**按系统当前子类型改方案，以引擎记住的为准、再同步给系统：
    // 系统在切回本输入法（`ime set`、别的输入法切过来）时按它自己的子类型历史挑一个，
    // 不一定是用户刚选的——实测按它来会把五笔无端切成英文。只认会话里的回调。

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        // 自己同步过去的那次回调：方案本来就是它，不再切（防回环）
        if (schemaSubtypes.consumeSelfSwitch(newSubtype)) return
        cancelVoiceInput()
        val id = SchemaSubtypes.schemaOf(newSubtype.extraValue) ?: return
        val schemas = session.schemas()
        if (schemas.isEmpty()) {
            pendingSystemSchema = id
            return
        }
        applySystemSchema(schemas, id)
    }

    /** 键盘自己的小状态（不属于引擎配置的那种），如中英切换前的中文方案 */
    private val statePrefs by lazy { getSharedPreferences("ime_state", MODE_PRIVATE) }

    /**
     * 长按中英键：直接弹方案选择卡片（五笔 / 全拼 / 双拼 / 英文…），点一下就切。
     *
     * 此前是打开快捷面板——方案只占面板顶上一行，还得再点一下，而快捷面板本来就有工具栏
     * 入口。卡片与候选长按菜单同一个组件，点外面只收卡片、不按到底下的键。
     * 切方案走 [DeferredSession] 的后台线程（首次切到拼音要现编词库），不卡主线程。
     */
    override fun onKeyLongPress(action: KeyAction) {
        if (action == KeyAction.Space) {
            // Accessibility long-clicks have no held pointer. Use the same
            // recognition session with explicit 完成 / 取消 controls instead.
            when (userConfig.getString("ui.android.space_long_press", "voice")) {
                "voice" -> onVoiceInput()
                "switch_ime" -> {
                    abandonComposition()
                    onSwitchInputMethod()
                }
            }
            return
        }
        cancelVoiceInput()
        if (action != KeyAction.SwitchLang) return
        if (!showSchemaPicker("切换方案")) {
            quickPanelWindow?.let { windowManager?.show(it) } // 核心没起来：退回快捷面板
        }
    }

    /**
     * 首次使用：第一次在普通文本框里弹出键盘时，先弹方案卡片让用户选（豆包、微信首次都先问
     * 键盘类型）。出厂默认是全拼，五笔、双拼用户在这里一眼就能换。
     *
     * 只弹一次（真弹出来了才记下，不管选没选）：关掉就当默认够用，之后随时长按中英键再换。
     * 标记单独一个 prefs 文件——ime_state 在测试脚本里会被整个删掉重置方案，不能连带它。
     * 核心还没起来时不弹（拿不到方案列表），就绪回调里会再试一次。
     *
     * 此前「记过上次中文方案 = 升级上来的老用户」也直接记成已弹：新用户在密码框 / 网址框里
     * 先按了一下中英键就会记下上次方案，之后卡片永远不出——没看到就算弹过。去掉了。
     */
    private fun maybeShowFirstRunPicker() {
        if (voiceController?.isActive == true) return
        if (onboardingPrefs.getBoolean(KEY_FIRST_PICK_SHOWN, false)) return
        val info = currentInputEditorInfo ?: return
        val cls = info.inputType and android.text.InputType.TYPE_MASK_CLASS
        if (cls != android.text.InputType.TYPE_CLASS_TEXT || suspendsLearning(info.inputType, 0)) return
        val bar = barView ?: return
        // 条区要先量好才能定位卡片
        bar.post {
            if (voiceController?.isActive == true) return@post
            if (onboardingPrefs.getBoolean(KEY_FIRST_PICK_SHOWN, false)) return@post
            // 排进来到执行之间键盘可能已经收起：此时弹出用户看不到，标记却记上了
            if (!isInputViewShown || currentInputEditorInfo == null) return@post
            if (showSchemaPicker("选一个输入方案（以后长按中英键随时换）", dismissOnOutside = false)) {
                onboardingPrefs.edit().putBoolean(KEY_FIRST_PICK_SHOWN, true).apply()
            }
        }
    }

    private val onboardingPrefs by lazy { getSharedPreferences("ime_onboarding", MODE_PRIVATE) }

    /** 方案选择卡片（长按中英键、首次使用共用）。@return 是否弹出（核心未就绪 / 条区未就位时为 false） */
    private fun showSchemaPicker(title: String, dismissOnOutside: Boolean = true): Boolean {
        cancelVoiceInput()
        val bar = barView ?: return false
        if (bar.width <= 0) return false
        val schemas = session.schemas()
        if (schemas.isEmpty()) return false
        val current = currentSchemaIndex(schemas)
        val entries = schemas.mapIndexed { i, s ->
            com.windinput.ime.core.CandidateMenuEntry(
                label = s.name.ifEmpty { s.id },
                action = s.index.toString(),
                enabled = i != current,
                current = i == current,
            )
        } + com.windinput.ime.core.CandidateMenuEntry(SWITCH_IME_LABEL, ACTION_SWITCH_IME, enabled = true)
        candidateMenu?.dismiss()
        schemaPicker?.dismiss()
        schemaPicker = CandidateMenuPopup(bar).also { menu ->
            menu.show(title, entries, dismissOnOutside) { entry ->
                if (entry.action == ACTION_SWITCH_IME) {
                    onSwitchInputMethod()
                    return@show
                }
                schemas.firstOrNull { it.index.toString() == entry.action }?.let { selectSchemaFromKey(schemas, it) }
            }
        }
        return schemaPicker?.isShowing == true
    }

    /** 方案列表里「当前」那项的下标。英文模式下引擎报的 active 仍是切走前的中文方案，当前要算英文 */
    private fun currentSchemaIndex(schemas: List<SchemaEntry>): Int {
        val english = latinInput
        return schemas.indexOfFirst {
            if (english) it.id == ENGLISH_SCHEMA_ID else it.active && it.id != ENGLISH_SCHEMA_ID
        }
    }

    /** 从中英键（卡片或长按菜单）或键盘选择面板选中一个方案 */
    private fun selectSchemaFromKey(schemas: List<SchemaEntry>, target: SchemaEntry) {
        cancelVoiceInput()
        if (target.id != ENGLISH_SCHEMA_ID) {
            statePrefs.edit().putString(KEY_LAST_CHINESE, target.id).apply()
        } else {
            schemas.firstOrNull { it.active && it.id != ENGLISH_SCHEMA_ID }
                ?.let { statePrefs.edit().putString(KEY_LAST_CHINESE, it.id).apply() }
        }
        // 与单击中英键一致：有编码先上屏字母原文再切，打了半截的码不吞掉
        if ((composing || hasBufferedInput) && !directInput) {
            session.keyPressed(KeyAction.Enter, false)
        }
        session.runCommand(EngineCmd.SelectSchema(target.index))
    }

    /**
     * 长按中英键、手指不抬：键上方弹竖排菜单（各方案 + 切换输入法），上移选、抬手定。
     * 「切换输入法」原来在长按空格上，归到这里——它和切方案是同一类事（换一种打字方式）。
     */
    override fun longPressOptions(action: KeyAction): KeyboardView.KeyOptions? {
        if (action != KeyAction.SwitchLang) return null
        cancelVoiceInput()
        val schemas = session.schemas()
        if (schemas.isEmpty()) return null
        return KeyboardView.KeyOptions(
            labels = schemas.map { it.name.ifEmpty { it.id } } + SWITCH_IME_LABEL,
            selected = currentSchemaIndex(schemas).coerceAtLeast(0),
        )
    }

    /**
     * 菜单抬手：滑到哪项就是哪项；没滑进菜单（或停回当前方案）就作罢。
     * 以前这里会改弹可点的方案卡片，按久一点没滑就弹出一张卡挡住键盘，真机反馈像误触
     */
    override fun onLongPressOption(action: KeyAction, index: Int?) {
        cancelVoiceInput()
        if (action != KeyAction.SwitchLang || index == null) return
        val schemas = session.schemas()
        when {
            index >= schemas.size -> {
                abandonComposition()
                onSwitchInputMethod()
            }
            else -> selectSchemaFromKey(schemas, schemas[index])
        }
    }

    /** 空格滑动移光标 */
    override fun onCursorMove(delta: Int) {
        cancelVoiceInput()
        if (delta == 0) return
        if (hasBufferedInput) abandonComposition()
        val keyCode =
            if (delta > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(kotlin.math.abs(delta)) { sendDownUpKeyEvents(keyCode) }
    }

    /**
     * 退格左滑扩选：实时选中光标前 count 个字符。
     *
     * **有编码时不碰宿主文本**：用户正在打字，滑退格的意图是「这串码不要了」，
     * 不是删正文——此前会把编码留着、把输入框里的字选中删掉，与意图完全相反。
     * 有编码时整个手势等价于清空编码（抬手时做，见 [onBackspaceSwipeDelete]）。
     */
    override fun onBackspaceSelect(count: Int) {
        cancelVoiceInput()
        if (composing || hasBufferedInput) return
        val ic = currentInputConnection ?: return
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
        val text = extracted.text ?: return
        if (extracted.startOffset < 0) return
        val start = backspaceSelectionStart(text, extracted.selectionEnd, count) ?: return
        ic.setSelection(extracted.startOffset + start, extracted.startOffset + extracted.selectionEnd)
    }

    /** 退格滑动抬手：有编码 → 清空编码；否则删除选区 */
    override fun onBackspaceSwipeDelete() {
        cancelVoiceInput()
        if (composing || hasBufferedInput) {
            abandonComposition()
            return
        }
        val ic = currentInputConnection ?: return
        if (!canRememberDeletes) {
            ic.commitText("", 1)
            return
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            recordDeleted(ic, selected.toString(), "")
        }
    }

    /**
     * 退格上滑（正上方）抬手：有编码 → 放弃编码；否则删光标前的全部，光标后的内容保留。
     * 有选区先删选区。同样包在批处理里，文本框撤销栈可整体找回
     */
    override fun onBackspaceDeleteBeforeCursor() {
        cancelVoiceInput()
        if (composing || hasBufferedInput) {
            abandonComposition()
            return
        }
        val ic = currentInputConnection ?: return
        if (!canRememberDeletes) {
            ic.deleteWithoutUndo(CLEAR_ALL_MAX, 0)
            return
        }
        ic.beginBatchEdit()
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotEmpty()) ic.commitText("", 1)
        val before = ic.getTextBeforeCursor(CLEAR_ALL_MAX, 0)?.toString().orEmpty()
        if (before.isNotEmpty()) ic.deleteSurroundingText(before.length, 0)
        ic.endBatchEdit()
        recordDeleted(ic, before + selected, "")
    }

    /** 面板里的退格键：单次删除沿用各面板原来的处理，滑动手势与键盘退格同一套宿主实现 */
    private fun panelBackspace(delete: () -> Unit) = object : BackspaceGesture.Listener {
        // 有编码（符号页保留条区，编码仍可见）时先交引擎退格，和键盘退格键一致；引擎不接再走面板自己的删除
        override fun onDelete() {
            cancelVoiceInput()
            if ((composing || hasBufferedInput) && engineKey(KeyAction.Backspace, false)) return
            recordBackwardDelete(delete)
        }
        override fun longPressDelayMs() = longPressDelayMs.toLong()
        override fun onSelect(count: Int) = onBackspaceSelect(count)
        override fun onSelectionDelete() = onBackspaceSwipeDelete()
        override fun onDeleteBeforeCursor() = onBackspaceDeleteBeforeCursor()
        override fun onClearAll() = onBackspaceClearAll()
        override fun onRestoreDeleted() = onBackspaceRestore()
        override fun canRestoreDeleted() = this@WindInputImeService.canRestoreDeleted()
        override fun swipeEnabled() = backspaceSwipeOn
    }

    override fun symbolBackspaceGesture() = panelBackspace { onSymbolBackspace() }

    override fun emojiBackspaceGesture() = panelBackspace { onEmojiBackspace() }

    override fun editBackspaceGesture() = panelBackspace { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }

    /**
     * 退格上滑后左移抬手：有编码 → 清空编码；否则清空整个输入框。走宿主的文本删除，
     * 误清了可以在编辑面板「撤销」找回（文本框自带的撤销栈）
     */
    override fun onBackspaceClearAll() {
        cancelVoiceInput()
        if (composing || hasBufferedInput) {
            abandonComposition()
            return
        }
        val ic = currentInputConnection ?: return
        if (!canRememberDeletes) {
            ic.deleteWithoutUndo(CLEAR_ALL_MAX, CLEAR_ALL_MAX)
            return
        }
        ic.beginBatchEdit()
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotEmpty()) ic.commitText("", 1)
        val before = ic.getTextBeforeCursor(CLEAR_ALL_MAX, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(CLEAR_ALL_MAX, 0)?.toString().orEmpty()
        if (before.length + after.length > 0) ic.deleteSurroundingText(before.length, after.length)
        ic.endBatchEdit()
        // 恢复后光标回原位：选区（若有）连同光标前的放回前段，光标后的放回后段
        recordDeleted(ic, before + selected, after)
    }

    /**
     * 退格单删（单击、连删的每一下）：删前删后各取光标，两次之间那段就是删掉的，记进 [deleteUndo]。
     * 有编码时退格删的是编码，不记；密码框不记（不在内存里留密码片段）；
     * 引擎这一下顺带往输入框写了字（回退替换等）也不记——那不是单纯的删除。
     */
    private fun recordBackwardDelete(delete: () -> Unit) {
        val ic = currentInputConnection
        val old = if (composing || hasBufferedInput || !canRememberDeletes || ic == null) null else selectionOf(ic)
        if (ic == null || old == null) {
            delete()
            return
        }
        val before = ic.getTextBeforeCursor(UNDO_PEEK_CHARS, 0)?.toString().orEmpty()
        val selected = if (old[0] != old[1]) ic.getSelectedText(0)?.toString().orEmpty() else ""
        wroteText = false
        delete()
        if (wroteText) return
        // KEYCODE_DEL 由应用再投递一次才删，这里读到的可能还是删前的光标：那样 DeleteUndo 会等回报
        val now = selectionOf(ic) ?: return
        deleteUndo.onBackwardDelete(before, selected, old[0], old[1], now[0], now[1], SystemClock.uptimeMillis())
    }

    /** 扩选删 / 删光标前 / 清空之后：记一条，恢复点 = 删完后的光标 */
    private fun recordDeleted(ic: android.view.inputmethod.InputConnection, before: String, after: String) {
        if (!canRememberDeletes) return
        val now = selectionOf(ic) ?: return
        if (now[0] == now[1]) deleteUndo.record(before, after, now[0])
    }

    /** 选区的绝对位置 [起点, 终点]（与 onUpdateSelection 同口径）；拿不到返回 null */
    private fun selectionOf(ic: android.view.inputmethod.InputConnection): IntArray? {
        ic.getSurroundingText(0, 0, 0)?.let {
            if (it.offset >= 0) return intArrayOf(it.offset + it.selectionStart, it.offset + it.selectionEnd)
        }
        // 编辑器可能不提供 surroundingText 或绝对 offset，继续用提取文本获取选区。
        val ext = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        return intArrayOf(ext.startOffset + ext.selectionStart, ext.startOffset + ext.selectionEnd)
    }

    /**
     * 退格下滑抬手：把最近一次删除放回原处（清空则先放光标前段、再放后段，光标停在中间）。
     * 恢复前同步核对光标仍在记录点，不在就作废不放——防止把旧内容插到别处
     */
    override fun onBackspaceRestore() {
        cancelVoiceInput()
        if (!canRestoreDeleted()) return
        val ic = currentInputConnection ?: return
        val sel = selectionOf(ic) ?: return
        val e = deleteUndo.take(sel[0], sel[1]) ?: return
        ic.beginBatchEdit()
        if (e.before.isNotEmpty()) ic.commitText(e.before, 1)
        // newCursorPosition = 0：光标停在后段开头，即两段之间
        if (e.after.isNotEmpty()) ic.commitText(e.after, 0)
        ic.endBatchEdit()
    }

    private val canRememberDeletes get() = !directInput && !deleteUndo.suspended

    override fun canRestoreDeleted() = !composing && canRememberDeletes && deleteUndo.canRestore

    /**
     * 撤销 / 重做：发 Ctrl+Z / Ctrl+Shift+Z。这是 Android 文本框的标准快捷键
     * （TextView 自带撤销栈，浏览器网页输入框也认），不用输入法自己记历史。
     * 有编码时先放弃编码：撤销的对象是已经写进输入框的字
     */
    private fun sendUndoRedo(redo: Boolean) {
        if (composing) abandonComposition()
        val ic = currentInputConnection ?: return
        var meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (redo) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, meta))
    }

    // ── 工具栏 ───────────────────────────────────────────────────

    override fun onToolbarAction(action: BarView.ToolbarAction) {
        cancelVoiceInput()
        val wm = windowManager ?: return
        when (action) {
            BarView.ToolbarAction.QUICK_PANEL -> quickPanelWindow?.let { wm.show(it) }
            BarView.ToolbarAction.HIDE_IME -> requestHideSelf(0)
        }
    }

    // ── 快捷功能面板 ─────────────────────────────────────────────

    override fun onQuickCommand(cmd: EngineCmd) {
        cancelVoiceInput()
        session.runCommand(cmd)
    }

    override fun quickSchemas(): List<SchemaEntry> = session.schemas()

    override fun onOpenPanel(target: QuickPanelWindow.Target) {
        cancelVoiceInput()
        val wm = windowManager ?: return
        when (target) {
            QuickPanelWindow.Target.EMOJI -> emojiWindow?.let { wm.show(it) }
            QuickPanelWindow.Target.CLIPBOARD -> clipboardWindow?.let { wm.show(it) }
            QuickPanelWindow.Target.TEXT_EDIT -> textEditingWindow?.let { wm.show(it) }
            QuickPanelWindow.Target.KEYBOARD_SELECT -> keyboardSelectWindow?.let { wm.show(it) }
        }
    }

    // ── 键盘选择面板 ─────────────────────────────────────────────

    override fun onKeyboardSelectSchema(index: Int) {
        cancelVoiceInput()
        val schemas = session.schemas()
        schemas.firstOrNull { it.index == index }?.let { selectSchemaFromKey(schemas, it) }
    }

    override fun onKeyboardManage() = openSettings("schema", SUB_ENABLED_SCHEMAS)

    override fun onOpenSettings() = openSettings()

    override fun onSideCustomize() =
        openSettings(SettingsActivity.PAGE_KEYBOARD, SUB_NUMPAD_SYMBOLS)

    // ── 工具栏常驻按钮（快捷面板「编辑工具栏」里挑）──────────────────

    override fun toolbarPins(): List<ToolbarItem> =
        ToolbarItem.parse(userConfig.getString(ToolbarItem.CONFIG_KEY, ToolbarItem.DEFAULT_PINS))

    override fun onToolbarPinsChanged(pins: List<ToolbarItem>) {
        cancelVoiceInput()
        userConfig.put(ToolbarItem.CONFIG_KEY, ToolbarItem.format(pins))
        barView?.setPinned(visiblePins())
    }

    /** 实际摆上工具栏的：语音只用于普通文本框，当前方案进不了临时拼音时不摆它 */
    private fun visiblePins(): List<ToolbarItem> = toolbarPins().filter {
        when (it) {
            ToolbarItem.VOICE -> voiceInputAvailable()
            ToolbarItem.TEMP_PINYIN -> tempPinyinAvailable()
            else -> true
        }
    }

    /** 临时拼音是码表方案（五笔）里临时打一个拼音；拼音、双拼、英文方案用不上 */
    override fun tempPinyinAvailable(): Boolean {
        val active = session.schemas().firstOrNull { it.active } ?: return false
        return !latinInput && active.id !in NON_CODETABLE_SCHEMAS
    }

    override fun onToolbarItemFromPanel(item: ToolbarItem) {
        windowManager?.showKeyboard()
        onToolbarItem(item)
    }

    override fun onToolbarItem(item: ToolbarItem) {
        if (item != ToolbarItem.VOICE) cancelVoiceInput()
        when (item) {
            ToolbarItem.KEYBOARD -> onOpenPanel(QuickPanelWindow.Target.KEYBOARD_SELECT)
            ToolbarItem.CLIPBOARD -> onOpenPanel(QuickPanelWindow.Target.CLIPBOARD)
            ToolbarItem.TEXT_EDIT -> onOpenPanel(QuickPanelWindow.Target.TEXT_EDIT)
            ToolbarItem.EMOJI -> onOpenPanel(QuickPanelWindow.Target.EMOJI)
            ToolbarItem.VOICE -> onVoiceInput()
            ToolbarItem.SWITCH_IME -> onSwitchInputMethod()
            ToolbarItem.SETTINGS -> onOpenSettings()
            ToolbarItem.ONE_HAND -> onToggleOneHand()
            ToolbarItem.INCOGNITO -> onToggleIncognito()
            // 替用户按一下引导键（电脑上空码时按 ` / ; 进这两个模式），引擎侧行为一致。
            // 有编码先上屏首选：引导键只在空码时生效
            ToolbarItem.TEMP_PINYIN -> sendLeadKey('`')
            ToolbarItem.QUICK_INPUT -> sendLeadKey(';')
        }
    }

    private fun sendLeadKey(key: Char) {
        if (directInput) return
        if (composing) commitDirect("")
        // 工具栏按钮必须进模式：用 PHYSICAL 绕开「触摸 `;` 不是引导键」的开关
        engineKey(KeyAction.Text(key), shifted = false, source = KeySource.PHYSICAL)
    }

    /**
     * 系统输入法选择器。`switchToNextInputMethod` 是盲切，用户看不到切去了哪个；
     * 选择器列出全部已启用输入法，与 Gboard 地球键长按的行为一致。
     */
    override fun onSwitchInputMethod() {
        cancelVoiceInput()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun voiceInputAvailable(): Boolean =
        allowsVoiceInput(currentInputEditorInfo?.inputType ?: 0)

    /** 输入框要求的无痕（应用置了 NO_PERSONALIZED_LEARNING、密码框），每个框重算 */
    private var fieldSuspendsLearning = false

    /**
     * 用户在快捷面板手动开的无痕。只活在本次键盘服务里、不落盘：
     * 忘了关的无痕会让输入法一直「学不会」，进程重启时自动回到正常更安全。
     */
    private var manualIncognito = false

    private fun applyLearningSuspended() {
        val suspended = manualIncognito || fieldSuspendsLearning
        deleteUndo.suspended = suspended
        session.setLearningSuspended(suspended)
        recentEmojiStore.paused = suspended
        recentSymbolStore.paused = suspended
        barView?.setIncognito(suspended)
    }

    override fun incognito(): Boolean = manualIncognito

    override fun incognitoForced(): Boolean = fieldSuspendsLearning

    override fun onToggleIncognito() {
        cancelVoiceInput()
        manualIncognito = !manualIncognito
        applyLearningSuspended()
    }

    /** Toolbar and accessibility entry: tap 完成 to accept, 取消 to discard. */
    override fun onVoiceInput() {
        if (voiceController?.isActive == true) finishVoiceInput()
        else beginVoiceInput(held = false)
    }

    override fun onSpaceVoiceStart(): Boolean = beginVoiceInput(held = true)

    override fun onSpaceVoiceCancelChanged(cancel: Boolean) {
        voiceCancelOnRelease = cancel
        renderVoiceStatus()
    }

    override fun onSpaceVoiceEnd(cancel: Boolean) {
        if (cancel) cancelVoiceInput()
        else finishVoiceInput()
    }

    private fun beginVoiceInput(held: Boolean): Boolean {
        if (voiceController?.isActive == true) return false
        val connection = currentInputConnection ?: return false
        val info = currentInputEditorInfo
        if (!isInputViewShown || !allowsVoiceInput(info?.inputType ?: 0)) {
            showToast("请在普通文本框中使用语音输入")
            return false
        }
        if (composing || hasBufferedInput) {
            showToast("请先选字，再长按空格说话")
            return false
        }
        if (!VoicePreferences.configured(this) ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            !VoiceInputController.hasAnyRecognition(this)
        ) {
            openVoiceSetup()
            return false
        }
        // A toolbar action can originate in the quick panel. Return first so
        // the panel-change cancellation cannot invalidate this new request.
        windowManager?.showKeyboard()
        candidateMenu?.dismiss()
        schemaPicker?.dismiss()
        if (currentInputConnection !== connection || !isInputViewShown ||
            !allowsVoiceInput(currentInputEditorInfo?.inputType ?: 0)
        ) return false
        voiceTarget = VoiceTarget(voiceInputEpoch, connection)
        voiceHeld = held
        voiceCancelOnRelease = false
        val controller = voiceController ?: VoiceInputController(this, object : VoiceInputController.Listener {
            override fun onState(state: VoiceState) {
                voiceState = state
                renderVoiceStatus()
            }

            override fun onResult(text: String) {
                val target = voiceTarget ?: return
                val current = currentInputConnection
                // The recognizer may finish after a focus or cursor change.
                // Never resolve the target lazily to whichever app is current.
                if (target.epoch != voiceInputEpoch || current !== target.connection ||
                    !isInputViewShown || !allowsVoiceInput(currentInputEditorInfo?.inputType ?: 0) ||
                    composing || hasBufferedInput ||
                    (voiceState?.onDevice == false && (manualIncognito || fieldSuspendsLearning))
                ) return
                voiceTarget = null
                deleteUndo.invalidate()
                // One final commit only: do not route speech through the Pinyin
                // engine, add a space, or execute an editor's Send action.
                if (!target.connection.commitText(text, 1)) showToast("文字未能输入，请重新长按空格")
                reportPrecedingText()
            }

            override fun onError(message: String) = showToast(message)

            override fun onFinished() {
                voiceTarget = null
                clearVoiceStatus()
                // A held Space gesture still consumes its eventual UP. Do not
                // call cancelSpaceVoiceGesture from this terminal callback.
            }
        }).also { voiceController = it }
        val started = controller.start(
            languageTag = if (latinInput) "en-US" else "zh-CN",
            allowSystemFallback = VoicePreferences.allowSystemFallback(this) &&
                !manualIncognito && !fieldSuspendsLearning,
        )
        if (!started) {
            voiceTarget = null
            clearVoiceStatus()
        }
        return started
    }

    private fun finishVoiceInput() {
        voiceHeld = false
        voiceCancelOnRelease = false
        renderVoiceStatus()
        voiceController?.stop()
    }

    private fun cancelVoiceInput() {
        if (cancellingVoice) return
        cancellingVoice = true
        try {
            ++voiceInputEpoch
            voiceTarget = null
            voiceController?.cancel()
            // Also abort a pending long-press timer when recording has not
            // started yet; otherwise it could start in a different editor.
            keyboardWindow?.keyboardView?.cancelSpaceVoiceGesture()
            clearVoiceStatus()
        } finally {
            cancellingVoice = false
        }
    }

    private fun renderVoiceStatus() {
        val state = voiceState ?: return
        if (voiceTarget == null) return
        barView?.visibility = View.INVISIBLE
        voiceStatusView?.apply {
            visibility = View.VISIBLE
            render(state, cancelOnRelease = voiceCancelOnRelease, held = voiceHeld)
        }
    }

    private fun clearVoiceStatus() {
        voiceState = null
        voiceHeld = false
        voiceCancelOnRelease = false
        voiceStatusView?.visibility = View.GONE
        barView?.visibility = View.VISIBLE
    }

    private fun openVoiceSetup() {
        cancelVoiceInput()
        requestHideSelf(0)
        try {
            startActivity(Intent(this, VoiceSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: RuntimeException) {
            showToast("无法打开语音设置，请从困困输入法设置中开启语音输入")
        }
    }

    /**
     * 打开设置页。
     *
     * `NEW_TASK` 是必须的：输入法服务不是 Activity，没有任务栈可挂；
     * 同时收起键盘，否则设置页起来后键盘悬在上面挡住内容。
     * 给了 [page] / [sub] 就直接进那一页：另加 `CLEAR_TASK` 新开一个设置页，已开着的设置页
     * 停在别处时也落到这一页（路由只在创建时从 intent 读）。
     */
    private fun openSettings(page: String? = null, sub: String? = null) {
        cancelVoiceInput()
        requestHideSelf(0)
        val intent = android.content.Intent(this, SettingsActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (page != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(SettingsActivity.EXTRA_PAGE, page)
                .putExtra(SettingsActivity.EXTRA_SUB, sub)
        }
        startActivity(intent)
    }

    override fun onToggleExpanded() {
        cancelVoiceInput()
        val wm = windowManager ?: return
        if (wm.current === expandedCandidatesWindow) {
            wm.showKeyboard()
        } else {
            expandedCandidatesWindow?.let {
                wm.show(it)
                refreshReadings()
            }
        }
    }

    override fun onPasteSuggestion(text: String) {
        cancelVoiceInput()
        currentInputConnection?.commitText(text, 1)
    }

    /** 展开页的读法栏：拉一次核心的首单元读法（没得选 / 非全拼为空，栏自动收起） */
    private fun refreshReadings() {
        expandedCandidatesWindow?.setReadings(session.leadingReadings())
    }

    override fun onReadingTapped(reading: LeadingReading) {
        cancelVoiceInput()
        session.setLeadingReading(reading)
    }

    override fun onExpandedCandidateSelected(index: Int) {
        cancelVoiceInput()
        session.candidateSelected(index)
        // 选中即原位返回：展开候选的唯一去向就是选一个词
        windowManager?.showKeyboard()
    }

    override fun onBackToKeyboard() {
        cancelVoiceInput()
        selecting = false
        windowManager?.showKeyboard()
    }

    // ── 文本编辑面板 ─────────────────────────────────────────────

    override fun isSelecting(): Boolean = selecting

    /**
     * 编辑动作全部基于 `getExtractedText` + `setSelection` 实现，不发带 Shift 的方向键事件。
     *
     * 此前选择模式靠 `sendKeyEvent(DPAD_*, META_SHIFT_ON)`：**多数编辑器不理会输入法
     * 合成的按键 meta**（EditText 只在物理键盘事件里看 Shift），于是「选择」按下去光标
     * 照常移动、选区纹丝不动——真机反馈「选择无效」就是它。选区直接 set 是所有实现
     * `InputConnection` 的控件都支持的最小公分母。
     *
     * 拿不到 ExtractedText 的宿主（极少数自绘编辑器）退回方向键，能移不能选。
     */
    override fun onEditAction(action: TextEditingWindow.EditAction) {
        cancelVoiceInput()
        val ic = currentInputConnection ?: return
        if (hasBufferedInput && action != TextEditingWindow.EditAction.COPY &&
            action != TextEditingWindow.EditAction.TOGGLE_SELECT
        ) abandonComposition()
        when (action) {
            TextEditingWindow.EditAction.TOGGLE_SELECT -> {
                selecting = !selecting
                selAnchor = if (selecting) currentSelection(ic)?.second ?: -1 else -1
            }
            TextEditingWindow.EditAction.SELECT_ALL -> {
                ic.performContextMenuAction(android.R.id.selectAll)
                selecting = false
            }
            TextEditingWindow.EditAction.CUT -> {
                ic.performContextMenuAction(android.R.id.cut)
                selecting = false
            }
            TextEditingWindow.EditAction.COPY -> {
                ic.performContextMenuAction(android.R.id.copy)
                selecting = false
            }
            TextEditingWindow.EditAction.PASTE -> ic.performContextMenuAction(android.R.id.paste)
            TextEditingWindow.EditAction.BACKSPACE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            TextEditingWindow.EditAction.UNDO -> sendUndoRedo(redo = false)
            TextEditingWindow.EditAction.REDO -> sendUndoRedo(redo = true)
            else -> moveCaret(ic, action)
        }
    }

    /** 当前选区的绝对偏移 (start, end)；宿主不支持 ExtractedText 时为 null */
    private fun currentSelection(ic: android.view.inputmethod.InputConnection): Pair<Int, Int>? {
        val ext = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        return (ext.startOffset + ext.selectionStart) to (ext.startOffset + ext.selectionEnd)
    }

    /** 方向 / 行首行尾：算出新焦点，选择模式下与锚点合成选区 */
    private fun moveCaret(
        ic: android.view.inputmethod.InputConnection,
        action: TextEditingWindow.EditAction,
    ) {
        val ext = ic.getExtractedText(ExtractedTextRequest(), 0)
        val text = ext?.text
        if (ext == null || text == null || ext.startOffset < 0 ||
            ext.selectionStart !in 0..text.length || ext.selectionEnd !in 0..text.length
        ) {
            // 退回方向键：能移动，选不了
            val code = when (action) {
                TextEditingWindow.EditAction.UP -> KeyEvent.KEYCODE_DPAD_UP
                TextEditingWindow.EditAction.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
                TextEditingWindow.EditAction.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                TextEditingWindow.EditAction.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                TextEditingWindow.EditAction.HOME -> KeyEvent.KEYCODE_MOVE_HOME
                else -> KeyEvent.KEYCODE_MOVE_END
            }
            sendDownUpKeyEvents(code)
            return
        }
        val base = ext.startOffset
        val selStart = base + ext.selectionStart
        val selEnd = base + ext.selectionEnd
        // 焦点端：选择模式下是锚点的对侧；非选择模式选区已合并，取 end
        val anchor = if (selecting && (selAnchor == selStart || selAnchor == selEnd)) selAnchor else selEnd
        val focus = if (selecting && selStart != anchor) selStart else selEnd
        val newFocus = editorCaretTarget(text, base, focus, action) ?: return
        if (selecting) {
            selAnchor = anchor
            ic.setSelection(minOf(anchor, newFocus), maxOf(anchor, newFocus))
        } else {
            ic.setSelection(newFocus, newFocus)
        }
    }

    // ── 剪贴板面板 ───────────────────────────────────────────────

    override fun onPasteText(text: String) {
        cancelVoiceInput()
        currentInputConnection?.commitText(text, 1)
        windowManager?.showKeyboard()
    }

    // ── 表情面板 ─────────────────────────────────────────────────

    override fun onEmojiSelected(emoji: String) {
        commitDirect(emoji)
    }

    // ── 分类符号页 ───────────────────────────────────────────────

    override fun onSymbolSelected(symbol: String) {
        commitDirect(symbol)
    }

    /** 符号页开着时条区候选行放最近用过的符号（点了直接上屏）；为 false 时条区照常 */
    private var symbolHistoryInBar = false

    /**
     * 符号页打开：没在打字时，把最近用过的符号摆进条区的候选行，一眼就看得到、不用翻页
     * （「常用」格子里不再重复放它们）。收起符号页时还原。
     */
    override fun onSymbolPageShown(shown: Boolean) {
        if (shown) {
            if (composing) return
            val recent = recentSymbolStore.list()
            if (recent.isEmpty()) return
            symbolHistoryInBar = true
            loadedCandidates = recent
            loadedComments = emptyList()
            loadedSegments = emptyList()
            candidateTotal = recent.size
            barView?.setCandidateFrame(recent, preedit = "", modeLabel = "", page = 1, totalPages = 1)
        } else if (symbolHistoryInBar) {
            symbolHistoryInBar = false
            loadedCandidates = emptyList()
            candidateTotal = 0
            barView?.setCandidateFrame(emptyList(), preedit = "", modeLabel = "", page = 1, totalPages = 1)
        }
    }

    override fun onSymbolBackspace() {
        cancelVoiceInput()
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    /** 回到进来前的那一层：键盘窗口的层没动过，123 层或数字键盘原样还在 */
    override fun onSymbolBack() {
        cancelVoiceInput()
        windowManager?.showKeyboard()
    }

    override fun isSymbolLocked(): Boolean = userConfig.getBoolean("ui.android.symbol_lock", false)

    override fun onSymbolLockChanged(locked: Boolean) = userConfig.put("ui.android.symbol_lock", locked)

    /**
     * 不经引擎、原样上屏（数字键盘、符号页、表情）。
     *
     * 有编码时先把首选上屏，再接这个字符——与在编码后打一个标点的效果一样（引擎打标点也是
     * 先顶首选）。没有候选的编码（五笔打了空码）上屏原文。不先处理的话，编码还挂在引擎里，
     * 字符却已插到了它前面。选词是同步回调，上屏顺序有保证。
     */
    private fun commitDirect(text: String) {
        cancelVoiceInput()
        if (!engineKey(KeyAction.Commit(text), shifted = false)) commitDirectNow(text)
    }

    private fun commitDirectNow(text: String) {
        deleteUndo.invalidate()
        if (composing && !directInput) {
            if (loadedCandidates.isNotEmpty()) {
                session.candidateSelected(0, autoSpace = false)
            } else {
                session.keyPressed(KeyAction.Enter, false)
            }
        }
        currentInputConnection?.commitText(text, 1)
    }

    override fun onEmojiBackspace() {
        cancelVoiceInput()
        // KEYCODE_DEL 由编辑器按字素删除，代理链正确处理 emoji 代理对
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }
}
