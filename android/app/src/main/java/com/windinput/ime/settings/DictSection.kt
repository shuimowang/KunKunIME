package com.windinput.ime.settings

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.windinput.ime.core.ImeSession
import com.windinput.ime.core.SchemaEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import uniffi.wind_android.ConfigException
import uniffi.wind_android.UserWordInfo

/** 每次多取的条数 */
private const val PAGE_SIZE = 50u

/** 搜索防抖 */
private const val SEARCH_DEBOUNCE_MS = 250L

/** 清空确认：第二下至少隔这么久（挡误触双击） */
internal const val CONFIRM_MIN_GAP_MS = 600L

/** 清空确认：这么久没点第二下就复位 */
internal const val CONFIRM_RESET_MS = 5000L

/**
 * 词库页：当前方案的个人词（自己加的、打字时学到的）。
 *
 * 分层而不平铺：一级页只有概况与两个入口（我的词条 / 添加词条），列表、搜索、删除、清空
 * 在「我的词条」里，加词表单在「添加词条」里——此前三块摊在一页，列表一长加词框就沉到底。
 *
 * 实现与电脑版「词库」页同一份（核心的用户词模块）：拼音族共用一个库、拼音码的
 * ü 归一与音节切分都在核心里做，这里只管显示。
 * 只管**当前方案**：手机上换方案在「方案」页，词库跟着当前方案走，省掉一个方案选择器。
 */
private enum class DictMode { OVERVIEW, WORDS, ADD }

@Composable
internal fun DictOverview(session: ImeSession?, onOpen: (String) -> Unit) = DictSection(session, DictMode.OVERVIEW, onOpen)

@Composable
internal fun DictWords(session: ImeSession?) = DictSection(session, DictMode.WORDS) {}

@Composable
internal fun DictAdd(session: ImeSession?) = DictSection(session, DictMode.ADD) {}

@Composable
private fun DictSection(session: ImeSession?, mode: DictMode, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 当前方案走 JNI，不放在重组路径上（搜索框每敲一字就重组一次）
    val schema by produceState<SchemaEntry?>(null, session) {
        value = withContext(Dispatchers.IO) { session?.schemas()?.firstOrNull { it.active } }
    }
    val current = schema
    if (current == null) {
        SectionTitle("正在启动输入法核心…")
        return
    }
    DictContent(current, context, scope, mode, onOpen)
}

@Composable
private fun DictContent(
    schema: SchemaEntry,
    context: Context,
    scope: CoroutineScope,
    mode: DictMode,
    onOpen: (String) -> Unit,
) {

    var query by remember { mutableStateOf("") }
    var words by remember { mutableStateOf<List<UserWordInfo>>(emptyList()) }
    var total by remember { mutableStateOf(0uL) }
    var limit by remember { mutableStateOf(PAGE_SIZE) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<UserWordInfo?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmAt by remember { mutableLongStateOf(0L) }
    var newCode by remember { mutableStateOf("") }
    var newText by remember { mutableStateOf("") }

    LaunchedEffect(schema.id, query, limit, reload) {
        // 防抖：每次查询都扫满整张个人词表，连敲几个字就叠出几次全表扫描（JNI 调用取消不了）。
        // 输入变了这个协程会被取消，delay 处直接短路
        if (mode == DictMode.ADD) return@LaunchedEffect // 加词页用不到列表，不扫整张表
        if (query.isNotEmpty()) delay(SEARCH_DEBOUNCE_MS)
        val page = withContext(Dispatchers.IO) {
            runCatching { CoreAccess.obtainCore(context)?.userWords(schema.id, query.trim(), 0u, limit) }.getOrNull()
        }
        words = page?.items.orEmpty()
        total = page?.total ?: 0uL
    }

    /**
     * 写操作放后台，结果用 Toast 报（删列表顶部的词时，页面里的提示行早滚出屏幕了）；
     * 失败显示核心给的原因（只读模式等），成功后重读列表并回调 [onSuccess]。
     */
    fun write(done: String, onSuccess: () -> Unit = {}, op: (uniffi.wind_android.WindCore) -> Unit) {
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                val core = CoreAccess.obtainCore(context) ?: return@withContext "输入法核心未就绪"
                try {
                    op(core)
                    null
                } catch (e: ConfigException) {
                    (e as? ConfigException.Rejected)?.reason ?: e.toString()
                } catch (e: Exception) {
                    // 核心 panic 时 UniFFI 抛的是 InternalException，不兜住设置页就整个崩了
                    "操作失败：${e.message ?: e}"
                }
            }
            Toast.makeText(context, error ?: done, Toast.LENGTH_SHORT).show()
            if (error == null) onSuccess()
            selected = null
            reload++
        }
    }

    val schemaName = schema.name.ifEmpty { schema.id }
    if (mode == DictMode.OVERVIEW) {
        SectionTitle("当前方案：$schemaName")
        Card(modifier = Modifier.fillMaxWidth()) {
            NavRow("我的词条", "共 $total 条 · 搜索、删除、清空", icon = SettingsIcons.words) { onOpen(DICT_WORDS) }
            NavRow("添加词条", "手动加一个词和它的编码", icon = SettingsIcons.addWord) { onOpen(DICT_ADD) }
            NavRow(DICT_STATS, "系统词库的条目、码长、重码与字集覆盖", icon = SettingsIcons.dictStats) { onOpen(DICT_STATS) }
        }
        // 短语不分方案（全局一份），单独一张卡，不放在「当前方案」名下
        Card(modifier = Modifier.fillMaxWidth()) {
            NavRow(DICT_PHRASES, "打编码出固定文字：地址、常用回复、日期时间…", icon = SettingsIcons.phrases) { onOpen(DICT_PHRASES) }
        }
        BackupCard(schema, context, scope) { reload++ }
        return
    }

    if (mode == DictMode.WORDS) {
    SectionTitle("$schemaName · 共 $total 条")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    limit = PAGE_SIZE
                },
                label = "按编码或词搜索",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        Column {
            if (words.isEmpty()) {
                NavRow(if (query.isBlank()) "还没有个人词" else "没有找到", null, null)
            }
            for (w in words) {
                WordRow(w, selected == w, onClick = { selected = if (selected == w) null else w }) {
                    write("已删除「${w.text}」") { it.userWordRemove(schema.id, w.code, w.text) }
                }
            }
            if (words.size.toULong() < total) {
                NavRow("显示更多", "已显示 ${words.size} / $total 条") { limit += PAGE_SIZE }
            }
        }
    }

    }

    if (mode == DictMode.ADD) {
    SectionTitle("加到「$schemaName」")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = newText,
                onValueChange = { newText = it },
                label = "词",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            TextField(
                value = newCode,
                onValueChange = { newCode = it },
                label = "编码（拼音可用空格分音节，如 ni hao）",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            TextButton(
                text = "添加",
                onClick = {
                    val code = newCode.trim()
                    val text = newText.trim()
                    if (code.isEmpty() || text.isEmpty()) {
                        Toast.makeText(context, "词和编码都要填", Toast.LENGTH_SHORT).show()
                    } else {
                        // 成功才清空：被核心拒了（编码不合法、只读模式）不用重填
                        write("已添加「$text」", onSuccess = {
                            newCode = ""
                            newText = ""
                        }) { it.userWordAdd(schema.id, code, text) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    }

    if (mode != DictMode.WORDS) return
    // 二次确认：第二下要隔开一点（误触双击不算），过一阵没点就复位（几分钟后再点不能直接清）
    if (confirmClear) {
        LaunchedEffect(confirmAt) {
            delay(CONFIRM_RESET_MS)
            confirmClear = false
        }
    }
    SectionTitle("数据")
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow(
            if (confirmClear) "再点一次确认清空" else "清空个人词",
            if (confirmClear) "「${schema.name}」的个人词全部删除，不能恢复" else "删除当前方案的全部个人词",
            icon = SettingsIcons.clear,
            highlight = confirmClear,
        ) {
            val now = SystemClock.uptimeMillis()
            if (!confirmClear) {
                confirmClear = true
                confirmAt = now
            } else if (now - confirmAt >= CONFIRM_MIN_GAP_MS) {
                confirmClear = false
                write("已清空") { it.userWordsClear(schema.id) }
            }
        }
    }
}

/**
 * 备份：导出 / 导入当前方案的词库数据（个人词、临时词、词频、候选调整）。
 *
 * 文件经系统的文件选择器存取（SAF），不要存储权限，存到网盘、发到电脑都由系统那边接手。
 * 导入只做合并：手机上没有电脑版那样的预览对话框，「覆盖」一点错就是丢数据，不给这个口。
 */
@Composable
private fun BackupCard(schema: SchemaEntry, context: Context, scope: CoroutineScope, onImported: () -> Unit) {
    var busy by remember { mutableStateOf(false) }

    fun run(op: suspend () -> String) {
        busy = true
        scope.launch {
            val message = try {
                withContext(Dispatchers.IO) { op() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 离开页面时协程被取消：IO 线程上的 JNI 调用停不下来、照常做完，这里不能把它
                // 报成「操作失败」（用户会以为没导进去、再导一遍）。照常往上抛
                throw e
            } catch (e: ConfigException) {
                (e as? ConfigException.Rejected)?.reason ?: e.toString()
            } catch (e: Exception) {
                // 文件读写失败、核心 panic（UniFFI 的 InternalException）都落这里，不让设置页崩
                "操作失败：${e.message ?: e}"
            }
            busy = false
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-yaml")) { uri ->
        if (uri != null) run {
            // 文件在用户选好位置那一刻就建了：之后任何一步失败都删掉它，不在网盘里留个空文件
            fun discard(reason: String): String {
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
                return reason
            }
            val core = CoreAccess.obtainCore(context) ?: return@run discard("输入法核心未就绪")
            val content = try {
                core.dictExport(schema.id)
            } catch (e: Exception) {
                discard("")
                throw e
            }
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) }
                ?: return@run discard("无法写入所选位置")
            "已导出"
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) run {
            val bytes = context.contentResolver.openInputStream(uri)?.use { DictBackup.readLimited(it) }
                ?: return@run "无法读取所选文件，或文件超过 32MB（不像是词库备份）"
            val content = bytes.decodeToString()
            val core = CoreAccess.obtainCore(context) ?: return@run "输入法核心未就绪"
            val report = core.dictImport(schema.id, content, false)
            withContext(Dispatchers.Main) { onImported() }
            DictBackup.describe(
                report.sections.map {
                    // 只有个人词段分「新增 / 更新 / 未变」，其余段（词频、候选调整…）只给导入条数
                    val words = it.key == "userWords"
                    DictBackup.Section(
                        it.key,
                        added = (if (words) it.added else it.imported).toLong(),
                        updated = if (words) it.updated.toLong() else 0,
                        unchanged = if (words) it.unchanged.toLong() else 0,
                        skipped = it.skipped.toLong(),
                    )
                },
            )
        }
    }

    SectionTitle("备份")
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow(
            "导出词库",
            if (busy) "正在处理…" else "存成文件，电脑版「词库 → 导入」也认",
            icon = SettingsIcons.backup,
        ) { if (!busy) exporter.launch(DictBackup.fileName(schema.id)) }
        NavRow(
            "导入词库",
            if (busy) "正在处理…" else "合并电脑版或本机导出的词库文件，也认 Rime / 文本词表",
            icon = SettingsIcons.import,
        ) { if (!busy) importer.launch(arrayOf("*/*")) }
    }
}

/** 一条词：词在左、编码在右；点一下露出「删除」，再点别处收起 */
@Composable
private fun WordRow(word: UserWordInfo, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = word.text, modifier = Modifier.weight(1f))
        Text(text = word.code, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (selected) {
            Text(
                text = "删除",
                modifier = Modifier
                    .clickable(onClick = onDelete)
                    .padding(start = 16.dp),
                color = MiuixTheme.colorScheme.primary,
            )
        }
    }
}
