package com.windinput.ime.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.windinput.ime.keyboard.KeyAction
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 延迟构造的会话：**核心在后台线程构造，构造期照常收键**。
 *
 * # 为什么必须异步
 *
 * `WindCore` 的构造不是轻活：三层配置加载、打开 redb、把词库 yaml 编译成 wdat
 * （首装时 wubi86 + 英文库合计数百毫秒到数秒）。此前它跑在 `onCreate` 的主线程上，
 * 方案数量一多就突破了系统的输入分发超时——真机/模拟器实测直接 ANR
 * （`Input dispatching timed out … Waited 5001ms for FocusEvent`）。
 *
 * 光把词库准备挪到后台是不够的（那是核心的 `prepare`），**构造本身**也在主线程上。
 *
 * # 未就绪期间的行为
 *
 * 按键有界暂存，就绪后在主线程逐键重放，避免一串拼音的前半段直接上屏、后半段才进引擎。
 * 换输入框、失焦或取消组合会作废暂存；密码和数字框由宿主直输，根本不进入此队列。
 * 队列满时明确提示等待，不把新键绕过旧键写进正文。初始化失败则明确报错并按原顺序直输。
 *
 * # 切方案同样异步
 *
 * 第一次切到某个方案时核心要现编它的词库：白霜拼音约 60 万条，模拟器上主线程卡 4.75 秒
 * （283 帧），真机再慢一点就是 ANR。所以可能现编词库的命令（切方案、切英文、重载配置、
 * 换双拼布局）一律丢到 [worker] 上跑；跑的期间按键同样暂存、候选给空、提示「词库准备中」。
 * 暂存按键之间的方案命令也保序，不能把切换前的字母用切换后的方案解释。
 * 其余不取返回值的调用（焦点、复位、无痕…）在忙时也排进
 * 同一条线程：顺序不乱，主线程也不会去抢那把被现编词库占着的锁。
 */
class DeferredSession(
    private val dataDir: File,
    private val userRoot: File,
    private val localRoot: File,
    /**
     * 构造前的准备工作（出厂数据拷出），**同样在后台线程执行**。
     *
     * 别把它留在 `onCreate` 里：assets 有数 MB（五笔词库 5MB + 英文库 385KB），
     * 逐文件流式拷贝在模拟器上要好几秒，而且版本锚点含 `lastUpdateTime`，
     * 每次重装都会全量重拷——这是压垮输入分发超时的最后一根稻草。
     */
    private val prepareData: () -> Unit,
    /** 就绪后回调（主线程），供 UI 撤掉「准备中」提示 */
    private val onReady: (ImeSession) -> Unit,
    /** 切方案等后台命令开始 / 全部做完（主线程）。做完时 UI 要重读方案与状态 */
    private val onBusyChanged: (Boolean) -> Unit = {},
    /** 初始化失败（主线程）；宿主展示明确错误，不能回退演示候选。 */
    private val onFailure: (String) -> Unit = {},
    /** 暂存键重放后核心仍不消费，交回宿主的普通按键默认行为（主线程）。 */
    private val onUnhandledKey: (KeyAction, Boolean, KeySource) -> Unit,
    /** 暂存按键数变化（主线程），供准备提示显示已收下的输入。 */
    private val onBufferedInputChanged: (Int) -> Unit = {},
) : ImeSession {

    private companion object {
        const val TAG = "DeferredSession"
        const val REPLAY_INTERVAL_MS = 16L
    }

    override var listener: ((UiEvent) -> Unit)? = null
        set(value) {
            field = value
            delegate?.listener = value
        }

    @Volatile
    private var delegate: ImeSession? = null

    private val started = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var closed = false
    private val inputBuffer = DeferredInputBuffer()
    private var replayPosted = false
    private var deliveringBufferedInput = false
    private var overflowReported = false
    private val replayInput = Runnable { replayOneInput() }

    val bufferedInputCount: Int get() = inputBuffer.keyCount

    /** 是否已就绪（UI 据此显示准备中提示） */
    val ready: Boolean get() = delegate != null

    /** null 表示尚未失败；失败后原顺序直输，候选栏显示这条错误。 */
    @Volatile
    var failureMessage: String? = null
        private set

    /** 跑切方案这类可能现编词库的命令；单线程 = 排进来的调用按提交顺序执行 */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "wind-core-switch") }

    /** 排在 [worker] 上还没做完的调用数。只在主线程读写 */
    private var pending = 0

    /** 后台命令进行中：此时核心可能正占着锁现编词库，主线程不碰它 */
    val busy: Boolean get() = pending > 0

    /** 忙时 [schemas] 给最近一次读到的列表，不去等锁 */
    @Volatile
    private var lastSchemas: List<SchemaEntry> = emptyList()

    private fun enqueue(what: String, work: (ImeSession) -> Unit) {
        if (closed) return
        val d = delegate ?: return
        if (pending++ == 0) onBusyChanged(true)
        worker.execute {
            try {
                work(d)
            } catch (e: Throwable) {
                Log.e(TAG, "后台执行 $what 失败", e)
            } finally {
                mainHandler.post {
                    if (closed) return@post
                    if (--pending == 0) {
                        onBusyChanged(false)
                        scheduleReplay()
                    }
                }
            }
        }
    }

    /** 不取返回值的调用：忙时排队（保序），不忙直接做 */
    private fun call(what: String, work: (ImeSession) -> Unit) {
        if (closed) return
        val d = delegate ?: return
        if (busy) enqueue(what, work) else work(d)
    }

    private fun mayBuildDict(cmd: EngineCmd): Boolean = when (cmd) {
        is EngineCmd.SelectSchema, EngineCmd.English, EngineCmd.ReloadConfig,
        is EngineCmd.SetShuangpinLayout -> true
        EngineCmd.TogglePunct, EngineCmd.ToggleWidth, EngineCmd.ToggleS2t -> false
    }

    /** 启动后台构造（幂等） */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            val t0 = System.currentTimeMillis()
            try {
                prepareData()
            } catch (e: Throwable) {
                Log.e(TAG, "出厂数据或核心库准备失败，停止初始化", e)
                fail("中文输入核心或词库未能加载，请重新安装完整安装包。")
                return@Thread
            }
            val installed = System.currentTimeMillis() - t0
            val session = RustSession.getOrCreate(dataDir, userRoot, localRoot)
            val cost = System.currentTimeMillis() - t0
            Log.i(TAG, "数据准备 ${installed}ms，核心构造 ${cost - installed}ms")
            if (session == null) {
                Log.e(TAG, "核心构造失败（${cost}ms），保持直输")
                fail("中文输入核心初始化失败，请查看诊断信息。")
                return@Thread
            }
            try {
                CoreStartupGate.awaitHandoff()
            } catch (e: Exception) {
                Log.e(TAG, "核心交接失败", e)
                fail("中文输入核心初始化未完成，请重新打开输入法。")
                return@Thread
            }
            mainHandler.post {
                if (closed) return@post
                session.listener = listener
                session.setLearningSuspended(learningSuspended)
                session.setAutoSpace(autoSpace)
                session.setTouchQuickTrigger(touchQuickTrigger)
                session.candidateComments = candidateComments
                delegate = session
                Log.i(TAG, "核心就绪，耗时 ${cost}ms")
                onReady(session)
                scheduleReplay()
            }
        }, "wind-core-init").start()
    }

    private fun fail(message: String) {
        mainHandler.post {
            if (closed) return@post
            failureMessage = message
            onFailure(message)
            scheduleReplay()
        }
    }

    override fun focusGained() {
        cancelBufferedInput()
        call("focusGained") { it.focusGained() }
    }

    override fun focusLost() {
        cancelBufferedInput()
        call("focusLost") { it.focusLost() }
    }

    /** Prevent a late constructor/callback from reviving a destroyed service or its old editor. */
    fun close() {
        closed = true
        cancelBufferedInput()
        listener = null
        worker.shutdown()
    }

    private fun cancelBufferedInput() {
        mainHandler.removeCallbacks(replayInput)
        replayPosted = false
        overflowReported = false
        inputBuffer.clear()
        onBufferedInputChanged(0)
    }

    private fun buffer(input: DeferredInput) {
        if (inputBuffer.offer(input)) {
            onBufferedInputChanged(inputBuffer.keyCount)
            scheduleReplay()
        } else if (!overflowReported) {
            overflowReported = true
            listener?.invoke(UiEvent.Toast("输入暂存已满，请等词库准备完毕后继续输入。"))
        }
    }

    private fun scheduleReplay() {
        if (closed || busy || replayPosted || inputBuffer.isEmpty) return
        if (delegate == null && failureMessage == null) return
        replayPosted = true
        // One key per turn lets drawing, editor changes and cancellation run between native calls.
        mainHandler.postDelayed(replayInput, REPLAY_INTERVAL_MS)
    }

    private fun replayOneInput() {
        replayPosted = false
        if (closed || busy) return
        val d = delegate
        if (d == null && failureMessage == null) return
        val entry = inputBuffer.take() ?: return
        deliveringBufferedInput = true
        try {
            when (val input = entry.input) {
                is DeferredInput.Key -> {
                    val consumed = d?.keyPressed(input.action, input.shifted, input.source) ?: false
                    if (!consumed && !closed && inputBuffer.isCurrent(entry)) {
                        onUnhandledKey(input.action, input.shifted, input.source)
                    }
                }
                is DeferredInput.Command -> if (d != null) dispatchCommand(input.command)
            }
        } finally {
            deliveringBufferedInput = false
        }
        onBufferedInputChanged(inputBuffer.keyCount)
        if (inputBuffer.isEmpty) overflowReported = false
        scheduleReplay()
    }

    /**
     * 核心没起来时也要记下：首次弹键盘往往就落在无痕框里（核心构造要一两秒），
     * 就绪后若不补设，这一框剩下的输入就被学进去了。
     */
    @Volatile
    private var learningSuspended = false

    override fun setLearningSuspended(on: Boolean) {
        learningSuspended = on
        call("setLearningSuspended") { it.setLearningSuspended(on) }
    }

    override fun setPrecedingText(text: String) = call("setPrecedingText") { it.setPrecedingText(text) }

    override fun assocHold(hold: Boolean) = call("assocHold") { it.assocHold(hold) }

    /** 暂存期间的新键也排到队尾，不能超过还没重放的旧键。 */
    override fun keyPressed(action: KeyAction, shifted: Boolean, source: KeySource): Boolean {
        if (closed) return false
        if (action !is KeyAction.Text && action !is KeyAction.Commit &&
            action != KeyAction.Space && action != KeyAction.Backspace && action != KeyAction.Enter
        ) return false
        val d = delegate
        if (d == null && failureMessage != null && inputBuffer.isEmpty) return false
        if (busy || d == null || (!inputBuffer.isEmpty && !deliveringBufferedInput)) {
            buffer(DeferredInput.Key(action, shifted, source))
            return true
        }
        return d.keyPressed(action, shifted, source)
    }

    override fun candidateSelected(index: Int, autoSpace: Boolean): Boolean =
        if (busy || (!inputBuffer.isEmpty && !deliveringBufferedInput)) false
        else delegate?.candidateSelected(index, autoSpace) ?: false

    /** 未就绪时先记下，核心就绪后补设（同 [learningSuspended]） */
    override var candidateComments: Boolean = false
        set(value) {
            field = value
            delegate?.candidateComments = value
        }

    override fun candidateWindow(offset: Int, limit: Int): CandidatePage =
        (if (busy) null else delegate?.candidateWindow(offset, limit))
            ?: CandidatePage(emptyList(), offset, 0, 0)

    override fun leadingReadings(): List<LeadingReading> =
        if (busy) emptyList() else delegate?.leadingReadings().orEmpty()

    override fun setLeadingReading(reading: LeadingReading): Boolean =
        if (busy || !inputBuffer.isEmpty) false else delegate?.setLeadingReading(reading) ?: false

    override fun candidateMenu(index: Int): List<CandidateMenuEntry> =
        if (busy) emptyList() else delegate?.candidateMenu(index).orEmpty()

    override fun candidateTooltip(index: Int): List<TooltipSection> =
        if (busy) emptyList() else delegate?.candidateTooltip(index).orEmpty()

    override fun candidateMenuRun(index: Int, action: String): Boolean =
        if (busy || !inputBuffer.isEmpty) false else delegate?.candidateMenuRun(index, action) ?: false

    override fun reset() {
        cancelBufferedInput()
        call("reset") { it.reset() }
    }

    override fun schemas(): List<SchemaEntry> {
        if (busy) return lastSchemas
        return delegate?.schemas().orEmpty().also { lastSchemas = it }
    }

    override fun separatorKey(): Char? = if (busy) null else delegate?.separatorKey()

    override fun shuangpinLayouts(): List<LayoutEntry> =
        if (busy) emptyList() else delegate?.shuangpinLayouts().orEmpty()

    override fun runCommand(cmd: EngineCmd) {
        if (closed || failureMessage != null) return
        if (delegate == null || !inputBuffer.isEmpty) {
            buffer(DeferredInput.Command(cmd))
        } else {
            dispatchCommand(cmd)
        }
    }

    private fun dispatchCommand(cmd: EngineCmd) {
        if (mayBuildDict(cmd)) enqueue(cmd.toString()) { it.runCommand(cmd) } else call(cmd.toString()) { it.runCommand(cmd) }
    }

    /** 核心没起来时先记下，就绪后补设（同 [learningSuspended]） */
    @Volatile
    private var autoSpace = true

    override fun setAutoSpace(on: Boolean) {
        autoSpace = on
        call("setAutoSpace") { it.setAutoSpace(on) }
    }

    @Volatile
    private var touchQuickTrigger = false

    override fun setTouchQuickTrigger(on: Boolean) {
        touchQuickTrigger = on
        call("setTouchQuickTrigger") { it.setTouchQuickTrigger(on) }
    }
}
