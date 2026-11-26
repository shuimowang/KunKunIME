package com.windinput.ime.settings

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.windinput.ime.WindInputApp
import com.windinput.ime.core.UserConfig
import com.windinput.ime.keyboard.NumberSideSymbols
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/** 列表每行的高度：拖动换位按它算越过了几行 */
private val ROW_HEIGHT = 56.dp

/**
 * 写配置的锁与作用域：离开本页后排着的写入照样做完（页面的作用域会随页面取消）。
 * 每次写的都是**当时最新**的列表，几次写谁先拿到锁都不影响最终结果。
 */
private val writeLock = Mutex()
private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * 键盘布局 →「数字键盘符号」：数字框九宫格左列的符号（`ui.android.number_side_symbols`）。
 *
 * 每行一个符号：右侧手柄按住上下拖动排序（越过半行即换位，抬手保存）、垃圾桶删除；
 * 底下添加（去空白、重复拒绝、最多 4 个字符）与恢复默认（点两下确认，同「清空个人词」）。
 * 列表可以删空，键盘左列就只剩「自定义」格。输入法每次弹出键盘时重读，改完回去就生效。
 * 键盘左列最底下的「自定义」格直接跳到这一页。
 */
@Composable
internal fun NumpadSymbolsSection() {
    val context = LocalContext.current
    val config = remember {
        UserConfig((context.applicationContext as WindInputApp).dataDirs.keyboardConfigFile)
    }
    var symbols by remember {
        mutableStateOf(NumberSideSymbols.parse(config.readAll()[NumberSideSymbols.CONFIG_KEY] as? String))
    }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmAt by remember { mutableLongStateOf(0L) }
    val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }

    fun save(list: List<String>) {
        symbols = list
        writeScope.launch {
            writeLock.withLock { config.put(NumberSideSymbols.CONFIG_KEY, NumberSideSymbols.format(symbols)) }
        }
    }

    SectionTitle("左列符号 · ${symbols.size} 个")
    Card(modifier = Modifier.fillMaxWidth()) {
        if (symbols.isEmpty()) NavRow("列表是空的", "键盘左列只剩「自定义」格", null)
        Column {
            for (sym in symbols) key(sym) {
                val isDragged = dragging == sym
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ROW_HEIGHT)
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                        .background(
                            if (isDragged) MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)
                            else androidx.compose.ui.graphics.Color.Transparent,
                        )
                        .padding(start = 20.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = sym, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            message = null
                            save(NumberSideSymbols.remove(symbols, symbols.indexOf(sym)))
                        },
                    ) {
                        Icon(
                            SettingsIcons.clear,
                            contentDescription = "删除 $sym",
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    DragHandle(
                        description = "拖动排序 $sym",
                        modifier = Modifier.pointerInput(sym) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragging = sym
                                    dragOffset = 0f
                                },
                                onVerticalDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount
                                    // 越过半行就和相邻行换位，偏移扣掉换过的行高：这一行接着跟手
                                    val from = symbols.indexOf(sym)
                                    val to = NumberSideSymbols.dragTarget(from, dragOffset, rowPx, symbols.size)
                                    if (to != from) {
                                        symbols = NumberSideSymbols.move(symbols, from, to)
                                        dragOffset -= (to - from) * rowPx
                                    }
                                },
                                onDragEnd = {
                                    dragging = null
                                    dragOffset = 0f
                                    save(symbols)
                                },
                                onDragCancel = {
                                    dragging = null
                                    dragOffset = 0f
                                    save(symbols)
                                },
                            )
                        },
                    )
                }
            }
        }
    }

    SectionTitle("添加符号")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = input,
                onValueChange = {
                    input = it
                    message = null
                },
                label = "符号（最多 ${NumberSideSymbols.MAX_CHARS} 个字符）",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            message?.let { Text(text = it, color = MiuixTheme.colorScheme.error) }
            TextButton(
                text = "添加",
                enabled = input.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    when (val r = NumberSideSymbols.add(symbols, input)) {
                        is NumberSideSymbols.AddResult.Added -> {
                            save(r.list)
                            input = ""
                            message = null
                        }
                        is NumberSideSymbols.AddResult.Rejected -> message = r.reason
                    }
                },
            )
        }
    }

    // 二次确认：第二下要隔开一点（误触双击不算），过一阵没点就复位
    if (confirmRestore) {
        LaunchedEffect(confirmAt) {
            delay(CONFIRM_RESET_MS)
            confirmRestore = false
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow(
            if (confirmRestore) "再点一次确认恢复" else "恢复默认",
            if (confirmRestore) "列表回到出厂的 ${NumberSideSymbols.DEFAULTS.size} 个符号" else NumberSideSymbols.DEFAULTS.joinToString(" "),
            icon = SettingsIcons.clear,
            highlight = confirmRestore,
        ) {
            val now = SystemClock.uptimeMillis()
            if (!confirmRestore) {
                confirmRestore = true
                confirmAt = now
            } else if (now - confirmAt >= CONFIRM_MIN_GAP_MS) {
                confirmRestore = false
                message = null
                save(NumberSideSymbols.DEFAULTS)
            }
        }
    }
}

/** 拖动手柄：三道横线；整块（不止线条）都可按 */
@Composable
internal fun DragHandle(description: String, modifier: Modifier) {
    val color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(
        modifier = modifier
            .width(52.dp)
            .fillMaxHeight()
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val stroke = 2.dp.toPx()
            for (k in 0..2) {
                val y = size.height * (0.25f + 0.25f * k)
                drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            }
        }
    }
}

/** 键盘布局页底下的入口：摘要列出当前列表的前几个 */
@Composable
internal fun NumpadSymbolsEntry(onOpen: () -> Unit) {
    val context = LocalContext.current
    val config = remember {
        UserConfig((context.applicationContext as WindInputApp).dataDirs.keyboardConfigFile)
    }
    // 不 remember：从二级页返回时本页重组，摘要按刚改的列表重读（读的是进程内缓存）
    val symbols = NumberSideSymbols.parse(config.readAll()[NumberSideSymbols.CONFIG_KEY] as? String)
    val summary = when {
        symbols.isEmpty() -> "数字框九宫格左列：已清空"
        symbols.size > 6 -> "数字框九宫格左列：${symbols.take(6).joinToString(" ")} …"
        else -> "数字框九宫格左列：${symbols.joinToString(" ")}"
    }
    SectionTitle("数字键盘")
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow(SUB_NUMPAD_SYMBOLS, summary, null, onClick = onOpen)
    }
}
