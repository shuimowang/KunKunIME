package com.windinput.ime.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.windinput.ime.WindInputApp
import com.windinput.ime.core.EngineCmd
import com.windinput.ime.core.ImeSession
import com.windinput.ime.core.LayoutEntry
import com.windinput.ime.core.SchemaEntry
import com.windinput.ime.core.UserConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.io.File

/**
 * 设置页：由设置清单（assets/data/settings_manifest.toml）驱动生成，结构见
 * docs/mobile-ux-reference.md §6。
 *
 * 层级：首页四组（开始使用 / 输入引擎 / 手机键盘 / 其他），每行带图标；点进去是一页。
 * 项多的页（方案、输入，清单里 `subpages = true`）再分一层：页内每个 section 一个二级入口，
 * 点进去才是那组开关——首页和一级页都在一屏内看完，不用在一条长列表里找。
 * 方案列表、统计、词库、关于是代码画的专属区块，其余全部来自清单。
 *
 * 使用 edge-to-edge（内容画到系统栏下，标题栏与底部留出 insets）、
 * 预测性返回（侧滑返回时当前页跟手右移，松手才真正返回）、莫奈取色（跟随壁纸主色）。
 */
class SettingsActivity : ComponentActivity() {

    companion object {
        /** 直接打开某一页（输入法里的入口用，如九宫格左列的「自定义」）：一级页 id */
        const val EXTRA_PAGE = "page"

        /** 同上：一级页下的二级页名（可缺） */
        const val EXTRA_SUB = "sub"

        /** 「键盘布局」页的 id（设置清单） */
        const val PAGE_KEYBOARD = "keyboard"
    }

    /**
     * 输入法启用 / 当前状态。回到本页时重查：去系统设置启用后按返回是 onResume，
     * 而「切换输入法」弹的是系统对话框，本页不 pause、只失焦——两处都要查。
     */
    private val imeSetup = mutableStateOf(ImeSetup.ENABLE)

    private fun refreshImeSetup() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        val enabled = imm?.enabledInputMethodList.orEmpty().any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        imeSetup.value = ImeSetup.of(enabled, current)
    }

    override fun onResume() {
        super.onResume()
        refreshImeSetup()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshImeSetup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 方案导入的缓存文件：上次进程被杀可能留下，只清早于十分钟的（不碰别处正在进行的导入）
        Thread { SchemeImportFiles.clearStale(applicationContext) }.start()
        val dirs = (application as WindInputApp).dataDirs
        val userConfig = UserConfig(dirs.keyboardConfigFile)
        val manifest = SettingsManifest.load(this)
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        // miuix 推荐的默认配色，深浅色跟系统。不用莫奈取色（MonetSystem）：系统壁纸取出来的
        // 主色在不少机型上与 miuix 组件不协调（维护者要求用 miuix 默认风格）
        val theme = ThemeController(ColorSchemeMode.System)

        setContent {
            MiuixTheme(controller = theme) {
                SettingsApp(
                    setup = imeSetup.value,
                    manifest = manifest,
                    userConfig = userConfig,
                    versionName = versionName,
                    initialRoute = Route(intent.getStringExtra(EXTRA_PAGE), intent.getStringExtra(EXTRA_SUB)),
                    actions = SettingsActions(
                        enableIme = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                        // 国内系统把「切换输入法」藏得很深（导航栏没有键盘图标，要进好几层设置），
                        // 系统选择框本身却人人可调，这里直接弹
                        switchIme = {
                            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                                ?.showInputMethodPicker()
                        },
                        openTestPad = { startActivity(Intent(this, TestPadActivity::class.java)) },
                    ),
                )
            }
        }
    }
}

/** 首页「开始使用」的三个动作 */
private class SettingsActions(
    val enableIme: () -> Unit,
    val switchIme: () -> Unit,
    val openTestPad: () -> Unit,
)

/** 首页「开始使用」处在哪一步（docs/mobile-ux-reference.md §6.3） */
enum class ImeSetup {
    /** 第 1 步：去系统设置里启用 */
    ENABLE,

    /** 第 2 步：已启用，还没切成当前输入法 */
    SWITCH,

    /** 已在使用 */
    READY;

    companion object {
        fun of(enabled: Boolean, current: Boolean): ImeSetup = when {
            current -> READY // 能是当前输入法就一定已启用（个别系统的启用列表查询会漏）
            enabled -> SWITCH
            else -> ENABLE
        }
    }
}

/**
 * 导航位置：首页（[page] 为 null）→ 一级页 → 二级页（[sub]）→ 详情页（[detail]，目前只有
 * 「启用的方案」里某个方案的设置页，值是方案 id）。最多三层，用三个字符串存住即可（转屏、
 * 进程回收后 rememberSaveable 能恢复），不必上导航库。
 */
private data class Route(val page: String? = null, val sub: String? = null, val detail: String? = null) {
    val depth: Int get() = when {
        page == null -> 0
        sub == null -> 1
        detail == null -> 2
        else -> 3
    }

    fun up(): Route = when {
        detail != null -> copy(detail = null)
        sub != null -> copy(sub = null)
        else -> Route()
    }

    /** 存页面状态用的键（SaveableStateHolder 要求可放进 Bundle） */
    val key: String get() = "$page/$sub/$detail"
}

/** 词库页的二级页（不来自清单，代码画）；名字即路由，DictSection 的入口用同一份 */
internal const val DICT_WORDS = "我的词条"
internal const val DICT_ADD = "添加词条"
internal const val DICT_PHRASES = "命令短语"
internal const val DICT_STATS = "词库统计"

/** 方案页里代码画的二级页 */
internal const val SUB_ENABLED_SCHEMAS = "启用的方案"

/** 键盘布局页里代码画的二级页：数字框九宫格左列的符号 */
internal const val SUB_NUMPAD_SYMBOLS = "数字键盘符号"

/** 关于页里代码画的二级页 */
internal const val SUB_PRIVACY = "隐私政策"

/** 输入页的「快捷输入」组：清单项之后，代码画「快捷候选来源」入口 */
private const val SECTION_QUICK_INPUT = "快捷输入"

/** 方案设置里的「双拼」组：清单项之外，代码在组页顶上画布局列表 */
private const val SECTION_SHUANGPIN = "双拼"

/** 代码画的二级页名：清单里的 section 不能叫这些，否则会被遮住（SettingsManifestTest 核对） */
internal val RESERVED_SUBPAGES =
    setOf(DICT_WORDS, DICT_ADD, DICT_PHRASES, DICT_STATS, SUB_ENABLED_SCHEMAS, SUB_NUMPAD_SYMBOLS, SUB_PRIVACY)

/**
 * 方案与双拼布局的显示态，挂在设置页顶层（不随某一页的组合进出而丢）。
 *
 * **只在后台线程碰核心**：第一次切到某方案时核心要现编词库（白霜拼音模拟器上近 5 秒），
 * 这期间主线程若去取方案列表，可能等在核心的锁上——切完方案马上进出别的页、回首页看摘要，
 * 都会走到这里。[switching] 也放在这一层：页面重进后照样知道「正在切」，不会再排一次切换。
 */
private class SchemaState(private val core: ImeSession, private val scope: kotlinx.coroutines.CoroutineScope) {
    var schemas by mutableStateOf<List<SchemaEntry>>(emptyList())
    var layouts by mutableStateOf<List<LayoutEntry>>(emptyList())
    var switching by mutableStateOf<String?>(null)

    /**
     * 切换已超过 [SLOW_SWITCH_MS] 还没完（首次要编词库）：此时才在那一行写「正在准备词库…」。
     * 快的切换（几十毫秒）一闪而过的提示只会让页面跳一下（真机反馈「界面一抖一抖的」）。
     */
    var slowSwitching by mutableStateOf<String?>(null)

    suspend fun refresh() {
        val (s, l) = withContext(Dispatchers.IO) { core.schemas() to core.shuangpinLayouts() }
        schemas = s
        layouts = l
    }

    /**
     * 切方案 / 换双拼布局，做完回读真值（切换可能被引擎拒绝，本地不先行打勾）。
     * 在设置页顶层的作用域里跑：发起切换的那一页离开了，切换照样做完、状态照样复位。
     */
    fun switchTo(label: String, cmd: EngineCmd) {
        if (switching != null) return
        switching = label
        val slow = scope.launch {
            kotlinx.coroutines.delay(SLOW_SWITCH_MS)
            if (switching == label) slowSwitching = label
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.runCommand(cmd) }
                refresh()
            } finally {
                slow.cancel()
                switching = null
                slowSwitching = null
            }
        }
    }

    val activeName: String? get() = schemas.firstOrNull { it.active }?.let { it.name.ifEmpty { it.id } }

    private companion object {
        const val SLOW_SWITCH_MS = 300L
    }
}

@Composable
private fun SettingsApp(
    setup: ImeSetup,
    manifest: SettingsManifestData,
    userConfig: UserConfig,
    versionName: String,
    initialRoute: Route,
    actions: SettingsActions,
) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<ImeSession?>(null) }
    var values by remember { mutableStateOf(SettingsValues(userConfig, null)) }
    // 起始路由来自 intent（输入法里直达某页）；只在首次创建时用，转屏 / 重建后按保存的走
    var routePage by rememberSaveable { mutableStateOf(initialRoute.page) }
    var routeSub by rememberSaveable { mutableStateOf(initialRoute.sub) }
    var routeDetail by rememberSaveable { mutableStateOf<String?>(null) }
    val route = Route(routePage, routeSub, routeDetail)
    // 详情页标题（方案名）：进页前由入口填上，页内读到核心的方案名后再校正；转屏丢了就先显示 id
    val detailTitles = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    // 每一层的可保存状态（滚动位置、标题栏收起、搜索词…）按路由存住：进下一级再返回，
    // 上一级停在离开时的位置，而不是回到顶上
    val stateHolder = rememberSaveableStateHolder()
    fun go(r: Route) {
        // 退出的各层不再留状态：下次进来从顶上开始，只有「还在栈里」的上级保持原位
        var popped = route
        while (popped.depth > r.depth) {
            stateHolder.removeState(popped.key)
            popped = popped.up()
        }
        routePage = r.page
        routeSub = r.sub
        routeDetail = r.detail
    }

    // 核心构造在后台线程（首次约 1 秒）。未就绪时键盘项照常可改，引擎项先不显示
    LaunchedEffect(Unit) {
        val (s, v) = withContext(Dispatchers.IO) {
            val core = CoreAccess.obtainCore(context)
            CoreAccess.obtain(context) to SettingsValues(userConfig, core).also { it.load() }
        }
        session = s
        values = v
    }

    val appScope = rememberCoroutineScope()
    val schemaState = remember(session) { session?.let { SchemaState(it, appScope) } }
    LaunchedEffect(schemaState) { schemaState?.refresh() }

    // 页面切换走可拖动的过渡：平时 animateTo 正常播放；预测性返回时按手势进度 seekTo 上一层，
    // 侧滑过程中上一层就画在下面、跟手滑出来（系统返回预览的样子），松手才真正退一层，
    // 中途放弃就弹回当前页。
    //
    // # 曲线只加在「进度」这一层
    //
    // 过渡里的子动画一律线性、不要尺寸动画，缓动由 animateTo 的进度曲线给。不这样做的两个坑
    // （都是真机录屏逐帧看出来的）：
    // - 子动画自带缓动时，侧滑进度经 seekTo 映射到动画时间，手指拖到一半页面才挪开一成，
    //   松手剩下一大截猛甩出去；
    // - 不给进度曲线时，animateTo 按「过渡总时长」推进；页面内容中途变化（码表页下半截异步
    //   加载出来）总时长被重算，进度随之重排，快到位的页面又往回跳一截再滑进来
    //   （「点进下一级会再弹一下」）。进度单调了，位置就只会往前走。
    val transitionState = remember { SeekableTransitionState(route) }
    // 放弃手势后的收尾协程：新手势、路由变化时先取消它，免得两边抢同一个 transitionState
    val cancelBackJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    LaunchedEffect(route) {
        cancelBackJob[0]?.cancel()
        transitionState.animateTo(route, tween(300, easing = FastOutSlowInEasing))
    }
    PredictiveBackHandler(enabled = route.depth > 0) { events ->
        cancelBackJob[0]?.cancel()
        val target = route.up()
        try {
            events.collect { transitionState.seekTo(it.progress, target) }
        } catch (e: CancellationException) {
            // 手势放弃：本协程已取消，回弹另起一个。不能 animateTo(route)：那是把过渡反过来
            // （上一级 → 当前页），被放弃的那次 seek 会作为「初始值动画」沿原方向接着跑完一整段
            // 线性时长，当前页先被带出去一截再拉回来（「回弹到位后又往回弹一点」）。
            // 系统在 onBackCancelled 前已把进度弹回 0，这里通常只剩 snapTo；
            // 仍有残余进度时沿同一方向 seek 回 0。途中路由被点走（targetState 变了）就不再插手
            val from = transitionState.fraction
            cancelBackJob[0] = appScope.launch {
                if (from < 0.001f) {
                    // 系统已把进度弹回 0：页面就在原位，直接落定，不空跑 200ms
                    if (transitionState.targetState == target) transitionState.snapTo(route)
                    return@launch
                }
                val back = TargetBasedAnimation(
                    tween(200, easing = LinearOutSlowInEasing), Float.VectorConverter, from, 0f,
                )
                val start = withFrameNanos { it }
                var t = 0L
                while (!back.isFinishedFromNanos(t)) {
                    t = withFrameNanos { it } - start
                    if (transitionState.targetState != target) return@launch
                    transitionState.seekTo(back.getValueFromNanos(t), target)
                }
                if (transitionState.targetState == target) transitionState.snapTo(route)
            }
            throw e
        }
        // 先在这里把过渡放完、再改路由（官方示例的顺序）：反过来的话路由一变，过渡按新的
        // 一对起止重算一次，已经拖开的页从另一侧再滑回来一截。
        // 放在 try 外：收尾这 220ms 里点了露出来的上一级页面，新路由的动画会打断这里——
        // 那是用户换了去处，不是放弃手势，不能回弹到旧页（会盖掉新路由）
        transitionState.animateTo(target, tween(220, easing = LinearOutSlowInEasing))
        go(target)
    }
    val transition = rememberTransition(transitionState, label = "settings-route")

    transition.AnimatedContent(
        transitionSpec = {
            // 层叠式（HyperOS / 系统设置同款）：深的一层总压在上面、整页从右边进出，
            // 浅的一层在下面只带一点视差。侧滑返回时就是当前页被手指拖开、露出下面的上一级
            val linear = tween<androidx.compose.ui.unit.IntOffset>(300, easing = LinearEasing)
            val forward = targetState.depth > initialState.depth
            val slide = if (forward) {
                (slideInHorizontally(linear) { it } togetherWith slideOutHorizontally(linear) { -it / 4 })
                    .apply { targetContentZIndex = 1f }
            } else {
                (slideInHorizontally(linear) { -it / 4 } togetherWith slideOutHorizontally(linear) { it })
                    .apply { targetContentZIndex = -1f }
            }
            slide using null // 整页同尺寸，不要尺寸动画（默认弹簧，时长不定，会改过渡总时长）
        },
    ) { r ->
        stateHolder.SaveableStateProvider(r.key) {
        Screen(
            route = r,
            title = r.detail?.let { detailTitles[it] ?: it } ?: screenTitle(manifest, r),
            onBack = if (r.depth > 0) ({ go(r.up()) }) else null,
        ) {
            val page = manifest.pages.firstOrNull { it.id == r.page }
            when {
                page == null -> HomeScreen(setup, manifest, values, schemaState, versionName, actions) { go(Route(it)) }
                r.sub == null -> PageScreen(manifest, page, values, session, schemaState, versionName) { go(r.copy(sub = it)) }
                r.detail == DETAIL_QUICK_SOURCES -> QuickSourcesSection(values)
                r.detail != null -> SchemaDetailSection(r.detail, manifest) { detailTitles[r.detail] = it }
                else -> SubScreen(manifest, page, r.sub, values, session, schemaState) { id, name ->
                    detailTitles[id] = name
                    go(r.copy(detail = id))
                }
            }
        }
        }
    }
}

private fun screenTitle(manifest: SettingsManifestData, r: Route): String =
    r.sub ?: manifest.pages.firstOrNull { it.id == r.page }?.title ?: "困困输入法"

/**
 * 一屏：miuix 大标题栏（随滚动收成小标题）+ 可滚动内容。内容画到导航栏下（edge-to-edge），
 * 末尾垫一段导航栏高度，最后一行不会被手势条挡住。
 */
@Composable
private fun Screen(
    route: Route,
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = title,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack, modifier = Modifier.padding(start = 12.dp)) {
                            Icon(MiuixIcons.Back, contentDescription = "返回")
                        }
                    }
                },
            )
        },
    ) { padding ->
        key(route) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(top = padding.calculateTopPadding())
                    .verticalScroll(rememberScrollState())
                    // 「添加词条」等有输入框的页：软键盘弹起时内容让出来，按钮不被挡
                    .imePadding()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

// ── 首页 ─────────────────────────────────────────────────────────

private val SEGMENT_TITLES = listOf(
    "shared" to "输入引擎",
    "android" to "手机键盘",
    "tail" to "其他",
)

@Composable
private fun HomeScreen(
    setup: ImeSetup,
    manifest: SettingsManifestData,
    values: SettingsValues,
    schemaState: SchemaState?,
    versionName: String,
    actions: SettingsActions,
    onOpen: (String) -> Unit,
) {
    // 「统计」一行写今日字数：比列卡片名（只有一张「统计设置」）有用。统计走 JNI，后台取
    val context = LocalContext.current
    val todayChars by androidx.compose.runtime.produceState<UInt?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { CoreAccess.obtainCore(context)?.statsSummary()?.todayChars }.getOrNull()
        }
    }
    // 三行常在，首次按「启用 → 切换 → 试打」逐步开放。
    // 没选中困困就打开试打页会弹出其它输入法，让新用户误以为安装没有生效。
    SmallTitle("开始使用")
    Card(modifier = Modifier.fillMaxWidth()) {
        val enabled = setup != ImeSetup.ENABLE
        NavRow(
            title = "启用输入法",
            summary = if (enabled) "已启用 ✓" else "下一步：在系统的输入法列表里打开「困困输入法」",
            icon = if (enabled) SettingsIcons.enabled else SettingsIcons.enable,
            highlight = !enabled,
            onClick = actions.enableIme,
        )
        NavRow(
            title = "切换输入法",
            summary = when (setup) {
                ImeSetup.READY -> "当前正在使用困困输入法 ✓"
                ImeSetup.SWITCH -> "下一步：在弹出的选择框里选「困困输入法」"
                ImeSetup.ENABLE -> "先完成上一步，再选择困困输入法"
            },
            icon = SettingsIcons.switchIme,
            highlight = setup == ImeSetup.SWITCH,
            onClick = if (enabled) actions.switchIme else null,
        )
        NavRow(
            title = "试一试",
            summary = if (setup == ImeSetup.READY) {
                "设置完成，可以试打拼音、英语和数字"
            } else {
                "完成上面两步后，在这里试打拼音和英语"
            },
            icon = SettingsIcons.tryIt,
            highlight = setup == ImeSetup.READY,
            onClick = if (setup == ImeSetup.READY) actions.openTestPad else null,
        )
    }
    for ((segment, title) in SEGMENT_TITLES) {
        val pages = manifest.pages.filter { it.segment == segment }
        if (pages.isEmpty()) continue
        SmallTitle(title)
        Card(modifier = Modifier.fillMaxWidth()) {
            for (p in pages) {
                NavRow(
                    title = p.title,
                    summary = if (p.id == "stats" && statsOff(manifest, values)) {
                        "统计已关闭"
                    } else if (p.id == "stats" && todayChars != null) {
                        "今日已输入 $todayChars 字"
                    } else {
                        pageSummary(manifest, p, values, schemaState, versionName)
                    },
                    icon = SettingsIcons.page(p.icon),
                ) { onOpen(p.id) }
            }
        }
    }
}

/**
 * 首页每一行的摘要：几个页面给出当前状态（方案名、版本），
 * 其余列出前几张卡片的名字，让人知道点进去有什么。
 */
private fun pageSummary(
    manifest: SettingsManifestData,
    page: SettingPage,
    values: SettingsValues,
    schemaState: SchemaState?,
    versionName: String,
): String {
    @Suppress("UNUSED_EXPRESSION") values.version // 值变了摘要跟着变
    return when (page.id) {
        "schema" -> schemaState?.slowSwitching?.let { "正在切换到「$it」…" }
            ?: schemaState?.activeName?.let { "当前：$it" }
            ?: sectionList(manifest, page.id)
        "dict" -> "我的词条 · 添加词条 · 命令短语 · 词库统计"
        "about" -> "版本 $versionName"
        else -> sectionList(manifest, page.id)
    }
}

/** 输入统计关着时首页不写「今日 0 字」——那读起来像「今天没打字」 */
private fun statsOff(manifest: SettingsManifestData, values: SettingsValues): Boolean =
    manifest.items.firstOrNull { it.key == "stats.enabled" }
        ?.takeIf { values.available(it) }
        ?.let { !values.bool(it) } ?: false

private fun sectionList(manifest: SettingsManifestData, pageId: String): String =
    manifest.sectionsOf(pageId).map { it.first }.filter { it.isNotEmpty() }.take(4).joinToString(" · ")

// ── 一级页面 ─────────────────────────────────────────────────────

@Composable
private fun PageScreen(
    manifest: SettingsManifestData,
    page: SettingPage,
    values: SettingsValues,
    session: ImeSession?,
    schemaState: SchemaState?,
    versionName: String,
    onOpenSub: (String) -> Unit,
) {
    when (page.id) {
        "schema" -> SchemaSection(schemaState, onOpenSub)
        "stats" -> StatsSection()
        "dict" -> DictOverview(session, onOpen = onOpenSub)
        "about" -> AboutSection(versionName, onOpenSub)
    }
    if (page.id == SettingsActivity.PAGE_KEYBOARD) VoiceSettingsEntry()
    @Suppress("UNUSED_EXPRESSION") values.version // 开关变了，依赖它的项跟着显示 / 隐藏
    val sections = visibleSections(manifest, page.id, values)
    if (sections.isEmpty() && !values.engineReady && manifest.itemsOf(page.id).isNotEmpty()) {
        Text(text = "正在启动输入法核心…", modifier = Modifier.padding(start = 4.dp))
    }
    if (page.subpages) {
        // 每组一个入口，副标题列出组里前几项，点进去才是开关
        if (sections.isNotEmpty()) {
            SmallTitle(if (page.id == "schema") "方案设置" else "分类")
            Card(modifier = Modifier.fillMaxWidth()) {
                for ((name, items) in sections) {
                    NavRow(
                        title = name,
                        summary = sectionSummary(name, items, values, schemaState),
                        icon = SettingsIcons.section(name),
                    ) { onOpenSub(name) }
                }
            }
        }
    } else {
        for ((name, items) in sections) SettingsCard(name, items, values)
        // 长按候选的提示卡显示哪几段：与候选显示相关，接在外观页「候选字体」之后
        if (page.id == "ui") TooltipSectionsSection()
        if (page.id == SettingsActivity.PAGE_KEYBOARD) NumpadSymbolsEntry { onOpenSub(SUB_NUMPAD_SYMBOLS) }
        if (page.id == "advanced") ResetSection(values)
    }
}

/**
 * 二级入口的摘要。组里打头是与组同名的总开关（如「模糊音」）时写**现在的状态**：关着写
 * 「未开启」，开着列出打开了的子项——只列子项标题（「zh ↔ z · ch ↔ c」）读起来像这几项已经开了。
 */
private fun sectionSummary(
    name: String,
    items: List<SettingItem>,
    values: SettingsValues,
    schemaState: SchemaState?,
): String {
    val master = items.firstOrNull()?.takeIf { it.type == "toggle" && it.title == name }
    if (master != null) {
        if (!values.bool(master)) return "未开启"
        val on = items.drop(1).filter { it.type == "toggle" && values.bool(it) }
        if (on.isEmpty()) return "已开启"
        val head = on.take(3).joinToString(" · ") { it.title }
        return if (on.size > 3) "$head 等 ${on.size} 项" else head
    }
    if (name == SECTION_SHUANGPIN) {
        schemaState?.layouts?.firstOrNull { it.active }?.let { return "布局：${it.name.ifEmpty { it.id }}" }
    }
    return items.take(3).joinToString(" · ") { it.title }
}

private fun visibleSections(
    manifest: SettingsManifestData,
    pageId: String,
    values: SettingsValues,
): List<Pair<String, List<SettingItem>>> =
    manifest.sectionsOf(pageId)
        .map { (name, items) -> name to items.filter { values.available(it) && values.visible(it, manifest.items) } }
        .filter { it.second.isNotEmpty() }

// ── 二级页面 ─────────────────────────────────────────────────────

@Composable
private fun SubScreen(
    manifest: SettingsManifestData,
    page: SettingPage,
    sub: String,
    values: SettingsValues,
    session: ImeSession?,
    schemaState: SchemaState?,
    onOpenSchema: (id: String, name: String) -> Unit,
) {
    when {
        page.id == "schema" && sub == SUB_ENABLED_SCHEMAS -> schemaState?.let { EnabledSchemasSection(it, onOpenSchema) }
        page.id == "dict" && sub == DICT_WORDS -> DictWords(session)
        page.id == "dict" && sub == DICT_ADD -> DictAdd(session)
        page.id == "dict" && sub == DICT_PHRASES -> PhrasesSection()
        page.id == "dict" && sub == DICT_STATS -> DictStatsSection(session)
        page.id == SettingsActivity.PAGE_KEYBOARD && sub == SUB_NUMPAD_SYMBOLS -> NumpadSymbolsSection()
        page.id == "about" && sub == SUB_PRIVACY -> PrivacySection()
        else -> {
            @Suppress("UNUSED_EXPRESSION") values.version
            val items = visibleSections(manifest, page.id, values).firstOrNull { it.first == sub }?.second.orEmpty()
            if (page.id == "schema" && sub == SECTION_SHUANGPIN) ShuangpinLayouts(schemaState)
            // 组内再按 card 分卡（模糊音：总开关 / 声母 / 韵母；纠错与提示：总开关 / 纠错规则 / 正确拼音提示）
            for ((card, cardItems) in items.groupBy { it.card }) {
                val title = card ?: if (sub == SECTION_SHUANGPIN) "选项" else ""
                if (cardItems.all { it.grid }) ToggleGridCard(title, cardItems, values)
                else SettingsCard(title, cardItems, values)
            }
            // 方案的附加词库挂在对应的二级页底下：拼音细胞词库在「拼音」，五笔附加库在「码表」
            if (page.id == "schema" && sub == "拼音") SchemaDictsSection("细胞词库", listOf("pinyin", "shuangpin"))
            if (page.id == "schema" && sub == "码表") SchemaDictsSection("五笔附加词库", listOf("wubi86"))
            if (sub == "联想输入") AssocLearningSection()
            if (page.id == "input" && sub == SECTION_QUICK_INPUT) {
                QuickSourcesEntry { onOpenSchema(DETAIL_QUICK_SOURCES, DETAIL_QUICK_SOURCES) }
            }
            // Emoji 开着才列附加模块（同电脑版的 enabled_when）
            if (sub == EMOJI_SECTION &&
                manifest.items.firstOrNull { it.key == EMOJI_ENABLED_KEY }?.let(values::bool) == true
            ) {
                EmojiModulesSection()
            }
        }
    }
}

/** 一张卡片：常用项直接列出，`advanced` 项收进底部的「进阶」折叠区 */
@Composable
private fun SettingsCard(title: String, items: List<SettingItem>, values: SettingsValues) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val (advanced, normal) = items.partition { it.advanced }
    if (title.isNotEmpty()) SmallTitle(title)
    Card(modifier = Modifier.fillMaxWidth()) {
        // 按键名定身份：enabled_when 让中间某行出现 / 消失时，各行记住的状态（滑块草稿等）
        // 不能按位置串到别的行上
        for (item in normal) key(item.key) { ItemRow(item, values) }
        if (advanced.isNotEmpty()) {
            NavRow(if (expanded) "进阶 ▴" else "进阶 ▾", "工程参数，一般不用改") { expanded = !expanded }
            if (expanded) for (item in advanced) key(item.key) { ItemRow(item, values) }
        }
    }
}

/** 两列勾选网格：短标题的一组开关（模糊音的拼音对），一行一个开关太稀、一屏看不全 */
@Composable
private fun ToggleGridCard(title: String, items: List<SettingItem>, values: SettingsValues) {
    val scope = rememberCoroutineScope()
    @Suppress("UNUSED_EXPRESSION") values.version
    if (title.isNotEmpty()) SmallTitle(title)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (row in items.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (item in row) key(item.key) {
                        val on = values.bool(item)
                        GridChip(item.title, on, Modifier.weight(1f)) {
                            scope.launch { withContext(Dispatchers.IO) { values.set(item, !on) } }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun GridChip(text: String, checked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(shape)
            .background(if (checked) colors.primary.copy(alpha = 0.12f) else colors.secondaryContainer)
            .border(1.dp, if (checked) colors.primary else Color.Transparent, shape)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (checked) colors.primary else colors.onSurface,
            fontSize = 15.sp,
        )
    }
}

@Composable
internal fun ItemRow(item: SettingItem, values: SettingsValues) {
    val scope = rememberCoroutineScope()
    @Suppress("UNUSED_EXPRESSION") values.version // 写入后重组
    fun write(v: Any) {
        scope.launch { withContext(Dispatchers.IO) { values.set(item, v) } }
    }
    when (item.type) {
        "toggle" -> ToggleLine(item.title, item.summary, values.bool(item)) { write(it) }
        "int" -> IntRow(item, values.int(item)) { write(it) }
        "select" -> SelectRow(item, values.choice(item)) { write(it) }
        "text" -> TextRow(item, values.text(item)) { v ->
            withContext(Dispatchers.IO) { values.set(item, v) }
        }
    }
}

// ── 方案页的专属区块：方案列表与双拼布局 ─────────────────────────

/**
 * 方案与双拼布局：**数据全部来自核心**，本页不留一份自己的清单。
 *
 * 装了什么方案由文件系统说了算（核心扫 `data/schemas` 下的 `.schema.toml`），双拼有哪些布局
 * 同理（扫 `schemas/shuangpin` 下的 `.toml`）。K 侧写死清单的后果是用户往目录里放的东西看不见，
 * 以及删掉某个方案后设置页还列着一个选不动的死项。
 */
@Composable
private fun SchemaSection(state: SchemaState?, onOpenSub: (String) -> Unit) {
    val st = state ?: return
    if (st.schemas.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // ViewModel：转屏不丢导入状态与缓存文件
    val owner = remember(context) { context.findComponentActivity() } ?: return
    val importer = remember(owner) { ViewModelProvider(owner)[SchemeImportController::class.java] }
    DisposableEffect(importer) {
        importer.onChanged = { scope.launch { st.refresh() } }
        onDispose { importer.onChanged = {} }
    }
    // .wpkg 没有标准 MIME，和 .zip 一起放行：application/octet-stream 兜底
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importer.begin(uri)) {
            android.widget.Toast.makeText(context, "正在导入上一个方案包，请稍候", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    SchemeImportDialogs(importer)

    // 标题固定：切换中的提示只写在那一行里，且慢了才写（见 SchemaState.slowSwitching）
    SmallTitle("输入方案")
    Card(modifier = Modifier.fillMaxWidth()) {
        for (s in st.schemas) {
            val label = s.name.ifEmpty { s.id }
            ChoiceRow(
                title = label,
                selected = s.active,
                summary = if (st.slowSwitching == label) "正在准备词库，首次使用要稍候…" else null,
                onClick = { st.switchTo(label, EngineCmd.SelectSchema(s.index)) },
            )
        }
        // 启用哪些方案设一次就不大动：收进二级页，入口挂在方案列表末尾
        NavRow(
            title = SUB_ENABLED_SCHEMAS,
            summary = "已启用 ${st.schemas.size} 个方案",
            icon = SettingsIcons.section(SUB_ENABLED_SCHEMAS),
        ) { onOpenSub(SUB_ENABLED_SCHEMAS) }
        NavRow(
            title = "导入方案",
            summary = "安装电脑版或文档站分发的方案包（.wpkg / .zip）",
            icon = SettingsIcons.import,
        ) { picker.launch(arrayOf("application/zip", "application/octet-stream")) }
    }
}

/** 沿 ContextWrapper 链找宿主 Activity；Compose 的 LocalContext 在某些包装下不是 Activity 本身 */
private fun android.content.Context.findComponentActivity(): ComponentActivity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    return null
}

/**
 * 启用的方案：装了的方案里哪些出现在「输入方案」列表、快捷面板与中英键卡片里。
 *
 * 移动端默认装了的全部可用；这里关掉的写进用户层（核心每次重载都按它重算，此前重载会读回
 * 出厂的「五笔 + 五笔拼音」，拼音、双拼就从列表里没了）。英文是中英键切换的目标，关不掉；
 * 最后一个中文方案也关不掉——原因由核心给，Toast 报出来。
 *
 * 每行开关左边一个设置图标，点进该方案的详情页（[SchemaDetailSection]）；停用的方案也能设置。
 */
@Composable
private fun EnabledSchemasSection(st: SchemaState, onOpenSchema: (id: String, name: String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf<List<uniffi.wind_android.InstalledSchemaInfo>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        installed = withContext(Dispatchers.IO) {
            runCatching { CoreAccess.obtainCore(context)?.installedSchemas() }.getOrNull().orEmpty()
        }
    }
    if (installed.isEmpty()) return
    UserSchemasCard(installed, onChanged = { reload++; scope.launch { st.refresh() } })
    SectionTitle("启用")
    Card(modifier = Modifier.fillMaxWidth()) {
        for (s in installed) {
            val label = s.name.ifEmpty { s.id }
            SwitchPreference(
                checked = s.enabled,
                title = label,
                summary = if (s.locked) "中英键切换要用，不能关" else null,
                endActions = {
                    IconButton(onClick = { onOpenSchema(s.id, label) }) {
                        Icon(SettingsIcons.schemaSettings, contentDescription = "「$label」的设置")
                    }
                },
                onCheckedChange = { on ->
                    scope.launch {
                        val error = withContext(Dispatchers.IO) {
                            val core = CoreAccess.obtainCore(context) ?: return@withContext "输入法核心未就绪"
                            try {
                                core.setSchemaEnabled(s.id, on)
                                core.runCommand(uniffi.wind_android.EngineCommand.ReloadConfig)
                                null
                            } catch (e: uniffi.wind_android.ConfigException) {
                                (e as? uniffi.wind_android.ConfigException.Rejected)?.reason ?: e.toString()
                            } catch (e: Exception) {
                                "操作失败：${e.message ?: e}"
                            }
                        }
                        if (error != null) android.widget.Toast.makeText(context, error, android.widget.Toast.LENGTH_SHORT).show()
                        reload++
                        st.refresh()
                    }
                },
            )
        }
    }
}

@Composable
private fun ShuangpinLayouts(state: SchemaState?) {
    val st = state ?: return
    if (st.layouts.isEmpty()) return
    SmallTitle("布局")
    Card(modifier = Modifier.fillMaxWidth()) {
        for (l in st.layouts) {
            val label = l.name.ifEmpty { l.id }
            ChoiceRow(
                title = label,
                selected = l.active,
                summary = if (st.slowSwitching == label) "正在切换…" else null,
                onClick = { st.switchTo(label, EngineCmd.SetShuangpinLayout(l.id)) },
            )
        }
    }
}

/** Emoji 候选总开关（附加模块卡片跟着它显示）与它所在的二级页 */
private const val EMOJI_ENABLED_KEY = "input.emoji.enabled"
private const val EMOJI_SECTION = "Emoji/OpenCC"

// ── 关于 ─────────────────────────────────────────────────────────

@Composable
private fun AboutSection(versionName: String, onOpenSub: (String) -> Unit) {
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var collecting by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = "困困输入法",
            summary = "版本 $versionName",
            startAction = { KunKunLogo(Modifier.padding(end = 14.dp).size(40.dp)) },
        )
        NavRow(SUB_PRIVACY, "语音、应用更新、本地数据与系统备份", icon = SettingsIcons.privacy) {
            onOpenSub(SUB_PRIVACY)
        }
        NavRow(
            "应用更新",
            "在应用内检查、下载并安装安卓正式版",
            icon = SettingsIcons.website,
        ) {
            context.startActivity(Intent(context, com.windinput.ime.update.UpdateActivity::class.java))
        }
        NavRow("反馈问题", "在 GitHub 上提交问题或建议（浏览器打开）", icon = SettingsIcons.feedback) {
            Diagnostics.openIssues(context)
        }
        NavRow(
            "分享诊断信息",
            if (collecting) "正在收集…" else "版本、系统、机型、最近的日志与崩溃记录，不含你输入的文字；可附在反馈里",
            icon = SettingsIcons.diagnostics,
        ) {
            if (collecting) return@NavRow
            collecting = true
            scope.launch {
                val text = withContext(Dispatchers.IO) { Diagnostics.collect(context, versionName) }
                collecting = false
                Diagnostics.share(context, text)
            }
        }
        NavRow(
            "导出诊断压缩包",
            if (collecting) "正在收集…" else "比上一项多：更长的日志（含崩溃前那次运行）与系统崩溃缓冲，打成 zip；同样不含你输入的文字",
            icon = SettingsIcons.diagnostics,
        ) {
            if (collecting) return@NavRow
            collecting = true
            scope.launch {
                val zip = withContext(Dispatchers.IO) { runCatching { Diagnostics.buildZip(context, versionName) }.getOrNull() }
                collecting = false
                if (zip != null) Diagnostics.shareZip(context, zip)
            }
        }
        NavRow(
            if (showLicenses) "开源许可 ▴" else "开源许可 ▾",
            "随包分发的词库数据与程序库的来源和许可证",
            icon = SettingsIcons.licenses,
        ) { showLicenses = !showLicenses }
    }
    if (showLicenses) LicensesSection()
}

// ── 通用行（miuix-preference）────────────────────────────────────

@Composable
internal fun SectionTitle(text: String) = SmallTitle(text)

/** 行首图标：统一大小与色调 */
@Composable
private fun RowIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.padding(end = 14.dp).size(24.dp),
        tint = MiuixTheme.colorScheme.onBackground,
    )
}

/**
 * 标题 + 摘要的行。[onClick] 为 null 时是纯展示行（无箭头、不可点）；
 * [highlight] 把摘要染成强调色（「开始使用」里没完成的那一步）。
 */
@Composable
internal fun NavRow(
    title: String,
    summary: String?,
    icon: ImageVector? = null,
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val start: (@Composable () -> Unit)? = icon?.let { { RowIcon(it) } }
    val summaryColor = if (highlight) {
        top.yukonga.miuix.kmp.basic.BasicComponentDefaults.summaryColor(color = MiuixTheme.colorScheme.primary)
    } else {
        top.yukonga.miuix.kmp.basic.BasicComponentDefaults.summaryColor()
    }
    if (onClick != null) {
        ArrowPreference(
            title = title,
            summary = summary?.ifEmpty { null },
            summaryColor = summaryColor,
            startAction = start,
            onClick = onClick,
        )
    } else {
        BasicComponent(title = title, summary = summary?.ifEmpty { null }, summaryColor = summaryColor, startAction = start)
    }
}

/** 单选的一行：选中项右侧打勾（方案、双拼布局） */
@Composable
private fun ChoiceRow(title: String, selected: Boolean, onClick: () -> Unit, summary: String? = null) {
    RadioButtonPreference(
        title = title,
        summary = summary,
        selected = selected,
        onClick = onClick,
        radioButtonLocation = RadioButtonLocation.End,
    )
}

/**
 * 单选设置项：一行，右侧显示当前值，点开是下拉菜单。此前把选项全部摊开成多行打勾，
 * 一个三选一就占四行，二级页一屏装不下；下拉保持一项一行。
 */
@Composable
private fun SelectRow(item: SettingItem, value: String, onValueChange: (String) -> Unit) {
    val labels = item.options.map { it.second }
    val index = item.options.indexOfFirst { it.first == value }.coerceAtLeast(0)
    WindowDropdownPreference(
        items = labels,
        selectedIndex = index,
        title = item.title,
        summary = item.summary,
        onSelectedIndexChange = { i -> item.options.getOrNull(i)?.let { onValueChange(it.first) } },
    )
}

/**
 * 数值项：标题与当前值一行，下面一条滑块。拖动时只改本地草稿（预览跟着变），
 * 松手才写入——拖一下就写几十次配置文件没有意义。
 * 带 [SettingItem.preview] 的项在拖动后显示预览：行高画出四行键，字号写一行候选。
 */
@Composable
private fun IntRow(item: SettingItem, value: Int, onValueChange: (Int) -> Unit) {
    val min = item.min ?: 0
    val max = item.max ?: 100
    var draft by remember(value) { mutableIntStateOf(value) }
    var touched by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.title)
                item.summary?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(top = 2.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Text(text = if (item.unit != null) "$draft ${item.unit}" else draft.toString())
        }
        Slider(
            value = draft.toFloat(),
            onValueChange = {
                draft = snapToStep(it, min, max, item.step)
                touched = true
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            valueRange = min.toFloat()..max.toFloat(),
            steps = ((max - min) / item.step - 1).coerceAtLeast(0),
            onValueChangeFinished = { if (draft != value) onValueChange(draft) },
        )
        when (item.preview) {
            "keyboard_rows" -> if (touched) KeyboardRowsPreview(draft)
            "candidate_text" -> CandidateTextPreview(draft)
        }
    }
}

/** 滑块值吸附到步长网格上（min 起算） */
internal fun snapToStep(raw: Float, min: Int, max: Int, step: Int): Int {
    val s = step.coerceAtLeast(1)
    val snapped = min + Math.round((raw - min) / s) * s
    return snapped.coerceIn(min, max)
}

/** 按所选行高（dp，真实尺寸）画四行键：10 / 9 / 9 / 5 个，只为让人看出键盘有多高 */
@Composable
private fun KeyboardRowsPreview(rowHeightDp: Int) {
    val keyColor = MiuixTheme.colorScheme.dividerLine
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height((rowHeightDp * PREVIEW_ROWS.size).dp),
    ) {
        val rowH = size.height / PREVIEW_ROWS.size
        val gap = 3.dp.toPx()
        PREVIEW_ROWS.forEachIndexed { r, keys ->
            val slot = size.width / 10f
            val indent = (10 - keys) * slot / 2
            for (k in 0 until keys) {
                drawRoundRect(
                    color = keyColor,
                    topLeft = Offset(indent + k * slot + gap, r * rowH + gap),
                    size = Size(slot - gap * 2, rowH - gap * 2),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
        }
    }
}

private val PREVIEW_ROWS = listOf(10, 9, 9, 5)

@Composable
private fun CandidateTextPreview(textSp: Int) {
    Text(
        text = "困困 输入法 候选 示例",
        modifier = Modifier.padding(top = 12.dp),
        fontSize = textSp.sp,
    )
}

/** 标题 + 说明 + 开关的一行；整行可点 */
@Composable
internal fun ToggleLine(title: String, summary: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onCheckedChange,
        title = title,
        summary = summary,
    )
}
