package com.windinput.ime.settings

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme
import uniffi.wind_android.ConfigException
import uniffi.wind_android.EngineCommand

/** 「快捷输入」二级页里代码画的详情页（路由的 detail 值；方案 id 不会是中文，不会撞） */
internal const val DETAIL_QUICK_SOURCES = "快捷候选来源"

/** 写配置的锁与作用域：离开本页后排着的写入照样做完（同「数字键盘符号」） */
private val writeLock = Mutex()
private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * 「历史条数上限」：只在启用了「快捷输入历史」来源时出现，所以不进设置清单（清单项按开关键
 * 收起，这里的开关是 members 里有没有一个成员），在本页按核心注册表的键画。标题与说明照电脑版。
 */
private val HISTORY_MAX = SettingItem(
    page = "input",
    section = "快捷输入",
    key = "schema.quick_input.history_max",
    source = SettingSource.ENGINE,
    type = "int",
    title = "历史条数上限",
    summary = "快捷输入历史最多保留的条数，超出后按「用得少、用得旧」自动清理。0 = 不限",
    defaultValue = null,
    min = 0,
    max = 10000,
    step = 50,
    unit = "条",
)

/** 读 quick_mix 的成员：(当前, 出厂)，当前为 null = 核心未就绪或没有快捷输入实例 */
private suspend fun loadMembers(context: android.content.Context): Pair<List<String>?, List<String>> =
    withContext(Dispatchers.IO) {
        val info = runCatching { CoreAccess.obtainCore(context)?.quickMixMembers() }.getOrNull()
        if (info == null || !info.found) null to info?.defaults.orEmpty() else info.current to info.defaults
    }

/** 「快捷输入」二级页底下的入口：摘要按优先级列出开着的来源 */
@Composable
internal fun QuickSourcesEntry(onOpen: () -> Unit) {
    val context = LocalContext.current
    var current by remember { mutableStateOf<List<String>?>(null) }
    // 每次重组进来（从详情页返回）都重读一次，摘要跟着刚改的列表走
    LaunchedEffect(Unit) { current = loadMembers(context).first }
    val list = current ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow(DETAIL_QUICK_SOURCES, QuickSources.summary(list), null, onClick = onOpen)
    }
}

/**
 * 输入 → 快捷输入 →「快捷候选来源」：快捷输入模式下出哪些候选。
 *
 * 每行一个来源：开关决定有无，右侧手柄按住上下拖动排序（越过相邻行的一半即换位，抬手保存），
 * 列表顺序即候选优先级。底下恢复默认（点两下确认）。写的是核心 `schema.mix_modes` 里 quick_mix 的
 * members（电脑版设置「快捷候选来源」同一份），写完 ReloadConfig 即时生效。
 */
@Composable
internal fun QuickSourcesSection(values: SettingsValues) {
    val context = LocalContext.current
    var current by remember { mutableStateOf<List<String>?>(null) }
    var defaults by remember { mutableStateOf<List<String>>(emptyList()) }
    var rows by remember { mutableStateOf<List<QuickSources.Row>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Float>() }
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        val (c, d) = loadMembers(context)
        current = c
        defaults = d
        rows = c?.let(QuickSources::rows).orEmpty()
        loaded = true
    }

    /** 整列写回（界面行已由调用方更新），写完重载；失败时提示并按核心回读校正 */
    fun save(next: List<String>) {
        if (current == null) return
        current = next
        writeScope.launch {
            val error = writeLock.withLock {
                val core = CoreAccess.obtainCore(context) ?: return@withLock "输入法核心未就绪"
                try {
                    core.setQuickMixMembers(next)
                    core.runCommand(EngineCommand.ReloadConfig)
                    null
                } catch (e: ConfigException) {
                    (e as? ConfigException.Rejected)?.reason ?: e.toString()
                } catch (e: Exception) {
                    "操作失败：${e.message ?: e}"
                }
            }
            if (error != null) {
                withContext(Dispatchers.Main) { Toast.makeText(context, error, Toast.LENGTH_SHORT).show() }
                val (c, _) = loadMembers(context)
                withContext(Dispatchers.Main) {
                    current = c
                    rows = c?.let(QuickSources::rows).orEmpty()
                }
            }
        }
    }

    fun saveRows(next: List<QuickSources.Row>) {
        val base = current ?: return
        rows = next
        save(QuickSources.members(base, next))
    }

    if (!loaded) return
    if (current == null) {
        Card(modifier = Modifier.fillMaxWidth()) {
            NavRow("没有快捷输入", "配置里找不到快捷输入（quick_mix）实例，或输入法核心未就绪", null)
        }
        return
    }

    SectionTitle("关掉的来源不再出候选 · 顺序即优先级")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            for (row in rows) key(row.source.id) {
                val id = row.source.id
                val isDragged = dragging == id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .heightIn(min = 56.dp)
                        .onSizeChanged { heights[id] = it.height.toFloat() }
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                        .background(
                            if (isDragged) MiuixTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        ToggleLine(row.source.title, row.source.hint, row.enabled) { on ->
                            saveRows(QuickSources.toggle(rows, rows.indexOfFirst { it.source.id == id }, on))
                        }
                    }
                    DragHandle(
                        description = "拖动排序 ${row.source.title}",
                        modifier = Modifier.pointerInput(id) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragging = id
                                    dragOffset = 0f
                                },
                                onVerticalDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount
                                    val from = rows.indexOfFirst { it.source.id == id }
                                    val (to, rest) = QuickSources.dragTarget(
                                        from,
                                        dragOffset,
                                        rows.map { heights[it.source.id] ?: 0f },
                                    )
                                    if (to != from) {
                                        rows = QuickSources.move(rows, from, to)
                                        dragOffset = rest
                                    }
                                },
                                onDragEnd = {
                                    dragging = null
                                    dragOffset = 0f
                                    saveRows(rows)
                                },
                                onDragCancel = {
                                    dragging = null
                                    dragOffset = 0f
                                    saveRows(rows)
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    // 历史来源开着才有条数上限可调
    if (rows.any { it.source.id == QuickSources.HISTORY && it.enabled } && values.available(HISTORY_MAX)) {
        SectionTitle("快捷输入历史")
        Card(modifier = Modifier.fillMaxWidth()) { ItemRow(HISTORY_MAX, values) }
    }

    if (confirmRestore) {
        LaunchedEffect(confirmAt) {
            delay(CONFIRM_RESET_MS)
            confirmRestore = false
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow(
            if (confirmRestore) "再点一次确认恢复" else "恢复默认",
            if (confirmRestore) {
                "候选来源与顺序回到出厂设置"
            } else {
                QuickSources.summary(defaults)
            },
            icon = SettingsIcons.clear,
            highlight = confirmRestore,
        ) {
            val now = SystemClock.uptimeMillis()
            if (!confirmRestore) {
                confirmRestore = true
                confirmAt = now
            } else if (now - confirmAt >= CONFIRM_MIN_GAP_MS) {
                confirmRestore = false
                rows = QuickSources.rows(defaults)
                save(defaults)
            }
        }
    }
}
