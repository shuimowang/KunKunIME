package com.windinput.ime.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.windinput.ime.keyboard.KeyAction
import com.windinput.ime.keyboard.Punct
import java.io.File
import uniffi.wind_android.UiEventListener
import uniffi.wind_android.WindCore

/**
 * Rust 核心会话：经 UniFFI 绑定驱动 headless Coordinator（walking skeleton）。
 *
 * 通道（对应主仓 wind-android crate 文档）：
 * - 正向：软键 → VK 码 → keyDown，同步拿 上屏/组合区 结果
 * - 反向：UiCommand 泵线程回调（候选/隐藏），post 回主线程再发 UiEvent
 * - 候选点选：骨架期合成数字键（页内 1-9），走正向通道拿上屏文本
 */
class RustSession(
    /** 设置页也直接用它读写配置、查统计（见 settings/CoreAccess） */
    internal val core: WindCore,
) : ImeSession {

    companion object {
        private const val TAG = "RustSession"

        /** 一帧的微秒数（60Hz）；JNI 同步调用超过它就会掉帧 */
        private const val FRAME_BUDGET_US = 16_000L

        // wind-keys VK 码（SSOT 在主仓 wind-keys/keymap.rs）
        private const val VK_ESCAPE = 0x1Bu

        /**
         * 日志级别为调试 / 跟踪时才为 true：逐键日志带上屏文字，只在用户主动调高级别时记
         * （见 [LogLevel]）
         */
        @Volatile
        private var verboseLog = false

        /** 进程内唯一核心（见 [getOrCreate]） */
        @Volatile
        private var instance: RustSession? = null

        /**
         * 取进程内**唯一**的会话，没有则构造。
         *
         * # 为什么必须是单例
         *
         * `InputMethodService.onCreate` 不是只调一次：切换输入法、系统配置变化都会
         * 重建服务。每次新建一个 `WindCore` 的后果是两份 77132 条词库常驻、两个
         * 预热线程、两个 prepare 线程，**争抢同一个 redb 文件锁与协调器 state 锁**
         * ——实测每次按键被拖到 140~480ms，触摸事件随之堆积、连点被误判成滑动。
         *
         * 核心是无状态可复用的（焦点/组合都随 focusGained 重置），复用比重建更正确。
         */
        @Synchronized
        fun getOrCreate(dataDir: File, userRoot: File, localRoot: File): RustSession? {
            instance?.let {
                Log.i(TAG, "复用已有核心（IME 服务重建）")
                return it
            }
            return create(dataDir, userRoot, localRoot)?.also { instance = it }
        }

        /**
         * 创建真实会话；.so 加载或核心构造失败返回 null，宿主必须展示初始化错误。
         * @param dataDir 出厂数据目录（filesDir/data，含 schemas/）
         * @param userRoot 0.132 核心数据根（filesDir/rust）
         * @param localRoot 核心派生的本地目录（userRoot/data/KunKunIME）；保留 FFI 签名
         */
        private fun create(dataDir: File, userRoot: File, localRoot: File): RustSession? {
            return try {
                userRoot.mkdirs()
                localRoot.mkdirs()
                // 级别跟随设置项 debug.log_level（出厂 warn：核心 debug 日志里有用户文字）。
                // 订阅器每个进程只装一次，改设置后要等进程重启
                val level = LogLevel.read(File(userRoot, "config/KunKunIME/config.toml"), dataDir)
                verboseLog = LogLevel.verbose(level)
                uniffi.wind_android.initLogging(level)
                // 泵线程在构造期即可能回调（构造期下发），经可空引用避免竞态
                var sessionRef: RustSession? = null
                val core = WindCore(
                    dataDir.absolutePath,
                    userRoot.absolutePath,
                    localRoot.absolutePath,
                    // 首装兜底方案：全拼（手机用户绝大多数打拼音；五笔用户在首次方案卡片里换）
                    "pinyin",
                    object : UiEventListener {
                        override fun onCandidates(update: uniffi.wind_android.CandidateUpdate) {
                            sessionRef?.post(
                                UiEvent.Candidates(
                                    composing = update.composing,
                                    hasCandidates = update.hasCandidates,
                                    preedit = update.preedit,
                                    preeditCaret = update.preeditCaret.toInt(),
                                    modeLabel = update.modeLabel,
                                    selected = update.selected.toInt(),
                                    page = update.page.toInt(),
                                    totalPages = update.totalPages.toInt(),
                                ),
                            )
                        }

                        override fun onHideCandidates() {
                            sessionRef?.post(UiEvent.Candidates(composing = false, hasCandidates = false))
                        }

                        override fun onStatus(status: uniffi.wind_android.InputStatus) {
                            sessionRef?.post(
                                UiEvent.Status(
                                    chineseMode = status.chineseMode,
                                    iconLabel = status.iconLabel,
                                    latinInput = status.latinInput,
                                    fullWidth = status.fullWidth,
                                    chinesePunct = status.chinesePunct,
                                    s2tEnabled = status.s2tEnabled,
                                    s2tShown = status.s2tShown,
                                ),
                            )
                        }

                        override fun onToast(text: String) {
                            sessionRef?.post(UiEvent.Toast(text))
                        }
                    },
                )
                Log.i(TAG, "Rust core ready: data=${dataDir.absolutePath}")
                RustSession(core).also { sessionRef = it }
            } catch (e: Throwable) {
                Log.e(TAG, "Rust core initialization failed; Chinese input is unavailable", e)
                null
            }
        }
    }

    override var listener: ((UiEvent) -> Unit)? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 泵线程在**非主线程**回调，一律 post 回主线程再交给 UI */
    private fun post(event: UiEvent) {
        mainHandler.post { listener?.invoke(event) }
    }

    /**
     * 每个核心调用都经它：核心 panic 时不让整个输入法崩掉（见 [CoreGuard]）。
     * 按键兜底为「没处理」（字母交给宿主直接上屏），列表类兜底为空。
     */
    private val guard = CoreGuard { what, e, first ->
        Log.e(TAG, "核心调用 $what 出错，已兜住", e)
        if (first) post(UiEvent.Toast("输入法核心出错，已恢复；反复出现请反馈"))
    }

    override fun focusGained() = guard.run("focusGained", Unit) { core.focusGained() }

    override fun focusLost() = guard.run("focusLost", Unit) { core.focusLost() }

    override fun setLearningSuspended(on: Boolean) =
        guard.run("setLearningSuspended", Unit) { core.setLearningSuspended(on) }

    override fun assocHold(hold: Boolean) = guard.run("assocHold", Unit) { core.assocHold(hold) }

    override fun setPrecedingText(text: String) =
        guard.run("setPrecedingText", Unit) { core.setPrecedingText(text) }

    override fun separatorKey(): Char? =
        guard.run<String?>("separatorKey", null) { core.separatorKey() }?.firstOrNull()

    override fun schemas(): List<SchemaEntry> =
        guard.run("schemas", emptyList()) { core.schemas() }.mapIndexed { i, s ->
            SchemaEntry(
                index = i,
                id = s.id,
                name = s.name,
                iconLabel = s.iconLabel,
                active = s.active,
            )
        }

    override fun shuangpinLayouts(): List<LayoutEntry> =
        guard.run("shuangpinLayouts", emptyList()) { core.shuangpinLayouts() }.map { LayoutEntry(id = it.id, name = it.name, active = it.active) }

    override fun runCommand(cmd: EngineCmd) {
        val native = when (cmd) {
            EngineCmd.English -> uniffi.wind_android.EngineCommand.English
            is EngineCmd.SelectSchema ->
                uniffi.wind_android.EngineCommand.SelectSchema(cmd.index.toUInt())
            EngineCmd.TogglePunct -> uniffi.wind_android.EngineCommand.TogglePunct
            EngineCmd.ToggleWidth -> uniffi.wind_android.EngineCommand.ToggleWidth
            EngineCmd.ToggleS2t -> uniffi.wind_android.EngineCommand.ToggleS2t
            EngineCmd.ReloadConfig -> uniffi.wind_android.EngineCommand.ReloadConfig
            is EngineCmd.SetShuangpinLayout ->
                uniffi.wind_android.EngineCommand.SetShuangpinLayout(cmd.id)
        }
        guard.run("runCommand($cmd)", Unit) { core.runCommand(native) }
    }

    override fun setAutoSpace(on: Boolean) {
        guard.run("setAutoSpace", Unit) { core.setAutoSpace(on) }
    }

    override fun setTouchQuickTrigger(on: Boolean) {
        guard.run("setTouchQuickTrigger", Unit) { core.setTouchQuickTrigger(on) }
    }

    /**
     * 软键/物理键 → 核心 `press`：翻译 VK、英文自动空格、标点换位都在核心里，这里只执行返回的编辑指令。
     * 前置条件见 [ImeSession.keyPressed]：直出（密码框）时不得调用。
     */
    override fun keyPressed(action: KeyAction, shifted: Boolean, source: KeySource): Boolean {
        if (action is KeyAction.Commit) {
            // 原样上屏键：只有快捷输入等文本缓冲模式下核心才收（进缓冲），其余交还宿主原样上屏
            val t0 = SystemClock.elapsedRealtimeNanos()
            val result = guard.run("pressLiteral", null) { core.pressLiteral(action.text) } ?: return false
            return deliver(result, "pressLiteral", t0)
        }
        val key = when (action) {
            is KeyAction.Text -> uniffi.wind_android.PressKey.Char(action.char.toString(), shifted)
            KeyAction.Space -> uniffi.wind_android.PressKey.Space
            KeyAction.Backspace -> uniffi.wind_android.PressKey.Backspace
            KeyAction.Enter -> uniffi.wind_android.PressKey.Enter
            else -> return false
        }
        val native = when (source) {
            KeySource.TOUCH -> uniffi.wind_android.KeySource.TOUCH
            KeySource.PHYSICAL -> uniffi.wind_android.KeySource.PHYSICAL
        }
        val t0 = SystemClock.elapsedRealtimeNanos()
        val result = guard.run("press", null) { core.press(key, native) } ?: return false
        return deliver(result, "press", t0)
    }

    /**
     * 点选候选：按**绝对下标**下发，核心回编辑指令流。
     *
     * 此前是「合成页内数字键 1-9」——那把点选限死在前 9 个，第 10 个及以后
     * 永远点不到，而候选栏的滚动因此也没有意义。
     */
    override fun candidateSelected(index: Int, autoSpace: Boolean): Boolean {
        if (index < 0) return false
        val result = guard.run("selectCandidate", null) {
            if (autoSpace) core.selectCandidate(index.toUInt()) else core.selectCandidatePlain(index.toUInt())
        } ?: return false
        if (result.ops.isNotEmpty()) {
            listener?.invoke(UiEvent.Edits(result.ops.map(::toEditOp)))
        }
        return result.consumed
    }

    override fun leadingReadings(): List<LeadingReading> =
        guard.run("leadingReadings", emptyList()) { core.leadingReadings() }
            .map { LeadingReading(it.label, it.head, it.origLen.toInt(), it.selected) }

    override fun setLeadingReading(reading: LeadingReading): Boolean {
        val result = guard.run("setLeadingReading", null) {
            core.setLeadingReading(reading.head, reading.origLen.toUInt())
        } ?: return false
        if (result.ops.isNotEmpty()) listener?.invoke(UiEvent.Edits(result.ops.map(::toEditOp)))
        return true
    }

    override fun candidateMenu(index: Int): List<CandidateMenuEntry> {
        if (index < 0) return emptyList()
        return guard.run("candidateMenu", emptyList()) { core.candidateMenu(index.toUInt()) }.map { CandidateMenuEntry(it.label, it.action, it.enabled) }
    }

    override fun candidateTooltip(index: Int): List<TooltipSection> {
        if (index < 0) return emptyList()
        return guard.run("candidateTooltip", emptyList()) { core.candidateTooltip(index.toUInt()) }.map { TooltipSection(it.title, it.inline, it.lines) }
    }

    override fun candidateMenuRun(index: Int, action: String): Boolean =
        index >= 0 && guard.run("candidateMenuRun", false) { core.candidateMenuRun(index.toUInt(), action) }

    override var candidateComments: Boolean = false

    override fun candidateWindow(offset: Int, limit: Int): CandidatePage {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val w = guard.run("candidates", null) {
            if (candidateComments) {
                core.candidatesWithComments(offset.toUInt(), limit.toUInt())
            } else {
                core.candidates(offset.toUInt(), limit.toUInt())
            }
        } ?: return CandidatePage(emptyList(), offset, 0, 0)
        val costUs = (SystemClock.elapsedRealtimeNanos() - t0) / 1000
        // 与 keyDown 同一条标尺：它同样在主线程上同步阻塞，超一帧就得改预取策略
        if (costUs > FRAME_BUDGET_US) {
            Log.w(TAG, "候选拉取阻塞主线程 ${costUs}µs（超一帧）offset=$offset limit=$limit")
        }
        return CandidatePage(
            items = w.items,
            offset = w.offset.toInt(),
            total = w.total.toInt(),
            selected = w.selected.toInt(),
            comments = w.comments,
            segments = w.textSegments.map { segs -> segs.map { TextSegment(it.text, it.role) } },
            rebuilt = w.rebuilt,
        )
    }

    override fun reset() {
        val t0 = SystemClock.elapsedRealtimeNanos()
        // 核心出错时当作「没处理」：输入法不崩
        val result = guard.run("keyDown", null) { core.keyDown(VK_ESCAPE, 0u) } ?: return
        deliver(result, "reset", t0)
    }

    /** 派发核心回的编辑指令流。@return 是否被引擎消费 */
    private fun deliver(result: uniffi.wind_android.KeyResult, label: String, t0: Long): Boolean {
        // JNI 往返耗时单独计：它在**主线程**上同步阻塞，是输入延迟里唯一不可
        // 让位给渲染的一段。超过一帧（16ms）就必须改成异步，故按帧标注。
        val costUs = (SystemClock.elapsedRealtimeNanos() - t0) / 1000
        if (costUs > FRAME_BUDGET_US) {
            Log.w(TAG, "$label 阻塞主线程 ${costUs}µs（超一帧）")
        }
        // ops 里是上屏文字：只在用户把日志调到调试级别时记（Log.d 在正式包里照样输出）
        if (verboseLog) {
            Log.d(
                TAG,
                "$label ${costUs}µs -> consumed=${result.consumed} " +
                    "ops=" + result.ops.joinToString { "k${it.kind}:'${it.text}'" },
            )
        }
        if (result.ops.isNotEmpty()) {
            listener?.invoke(UiEvent.Edits(result.ops.map(::toEditOp)))
        }
        return result.consumed
    }

    /**
     * FFI 的扁平 Record → Kotlin 密封类。
     *
     * `kind` 判别位的取值由核心 `wind-android` 的 `EditOp` 定义，两边必须同步；
     * 未知取值**抛异常而不是忽略**——静默忽略正是「新动作上了核心、移动端悄无声息
     * 地没有」的老路。
     */
    private fun toEditOp(op: uniffi.wind_android.EditOp): EditOp = when (op.kind.toInt()) {
        0 -> EditOp.Commit(op.text)
        1 -> EditOp.SetComposition(op.text, op.count.toInt())
        2 -> EditOp.DeleteBackward(op.count.toInt())
        3 -> EditOp.ReplaceBackward(op.count.toInt(), op.text)
        4 -> EditOp.MoveCursor(op.delta)
        else -> error("未知 EditOp.kind=${op.kind}，核心与 K 侧的判别位定义已漂移")
    }
}
