package com.windinput.ime.settings

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import uniffi.wind_android.ConfigException
import uniffi.wind_android.EngineCommand
import uniffi.wind_android.InstalledSchemaInfo
import uniffi.wind_android.SchemeImportInfo
import uniffi.wind_android.SchemePreviewInfo
import java.io.File
import java.io.InputStream

/**
 * 方案包（.wpkg / .zip）导入。设置页「导入方案」与文件关联的 [SchemeImportActivity] 共用本文件，
 * 包的格式、解压守卫、限额、版本门禁全在核心（`scheme_preview` / `scheme_import`），这里只管
 * 把用户选的文件放进应用私有目录、把核心的预览说给人听、把结果交回去。
 */
internal object SchemeImportFiles {

    /**
     * 方案包大小上限（压缩后）。核心另有解压后 256MB 的硬限；这里先在复制时就挡住误选的大文件，
     * 不让它把缓存目录写满。真实方案包里最大的是几十 MB 的全量词库。
     */
    const val MAX_PACKAGE_BYTES = 128L * 1024 * 1024

    /** 正在使用的缓存文件名（进程内登记）；[clearStale] 不碰它们 */
    private val inUse: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    fun markInUse(file: File) {
        inUse.add(file.name)
    }

    fun release(file: File) {
        inUse.remove(file.name)
    }

    fun cacheDir(context: Context): File = File(context.cacheDir, "scheme-import").apply { mkdirs() }

    /**
     * 流式复制 [input] 到 [dest]，超过 [max] 字节就停下、删掉半截文件并返回 false。
     * 不信描述符报的长度（网盘一类提供方常报未知），与 [DictBackup.readLimited] 同一思路，
     * 但落盘而不进内存：方案包可达几十 MB。
     */
    fun copyLimited(input: InputStream, dest: File, max: Long = MAX_PACKAGE_BYTES): Boolean {
        var total = 0L
        var ok = true
        dest.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > max) {
                    ok = false
                    break
                }
                out.write(buf, 0, n)
            }
        }
        if (!ok) dest.delete()
        return ok
    }

    /**
     * 上次异常退出（进程被杀）可能留下的缓存文件。只清早于 [olderThanMs] 的：
     * 设置页与文件关联页可能同时有一次导入在进行，不能把对方正在用的文件删了。
     */
    fun clearStale(context: Context, olderThanMs: Long = 10 * 60_000L) {
        val now = System.currentTimeMillis()
        cacheDir(context).listFiles()
            ?.filter { it.name !in inUse && now - it.lastModified() > olderThanMs }
            ?.forEach { it.delete() }
    }
}

/**
 * 方案导入 / 删除的全局互斥（设置页入口、文件关联 Activity、删除共用）。核心里也有一把锁串行化落盘，
 * 这里是界面层的：进行中禁止再开始别的操作，并让列表置灰，而不是让第二个操作悄悄排队或被丢弃。
 * 只在主线程读写。
 */
internal object SchemeBusy {
    var busy by mutableStateOf(false)
        private set

    fun tryAcquire(): Boolean {
        if (busy) return false
        busy = true
        return true
    }

    fun release() {
        busy = false
    }

    const val MESSAGE = "正在进行另一个方案导入或删除，请稍候"
}

/** 导入流程的界面状态 */
internal sealed interface SchemeImportState {
    data object Idle : SchemeImportState

    data class Working(val text: String) : SchemeImportState

    data class Preview(
        val file: File,
        val info: SchemePreviewInfo,
        /** 「覆盖已存在的文件」开关，默认关 */
        val replace: Boolean = false,
        /** 正在二次确认 */
        val confirming: Boolean = false,
    ) : SchemeImportState {
        /**
         * 这次导入实际会遮住自带方案的方案 id / 资源文件：用户层已有同名文件且没开「覆盖」时，
         * 这些文件会被跳过，不算。
         */
        val effectiveOverrideSchemas: List<String>
            get() = info.overridesBuiltin.filter { replace || "$it.schema.toml" !in info.conflicts }
        val effectiveOverrideFiles: List<String>
            get() = info.overridesBuiltinFiles.filter { replace || it !in info.conflicts }

        /** 要不要二次确认：会覆盖用户已有文件，或遮住自带方案 / 资源 */
        val needsConfirm: Boolean
            get() = (replace && info.conflicts.isNotEmpty()) ||
                effectiveOverrideSchemas.isNotEmpty() || effectiveOverrideFiles.isNotEmpty()
    }

    data class Done(val result: SchemeImportInfo, val rows: List<InstalledSchemaInfo>) : SchemeImportState

    data class Failed(val reason: String) : SchemeImportState
}

/**
 * 导入流程：选好的文件 → 预览 → 确认 → 结果。状态机在这里，界面只是读 [state]。
 *
 * 是 ViewModel：转屏 / 配置变更时状态与缓存文件不丢；Activity 真正结束时 [onCleared] 删缓存、放锁。
 * 整个流程（从 [begin] 到回到 Idle）持有 [SchemeBusy]，期间不能再开始别的导入 / 删除。
 * [onChanged] 在方案列表有变（导入成功、立即启用）时回调，由宿主界面设置。
 */
internal class SchemeImportController(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()

    var onChanged: () -> Unit = {}

    var state by mutableStateOf<SchemeImportState>(SchemeImportState.Idle)
        private set

    /** 是否持有 [SchemeBusy]（只有成功 acquire 的实例才能放） */
    private var held = false

    /** 当前缓存文件；协程被取消时 state 来不及赋值，靠它兜底删除 */
    @Volatile
    private var pendingFile: File? = null

    /** ViewModel 已被清理；后台复制与之赛跑时，晚到的缓存文件由复制线程自己删 */
    @Volatile
    private var cleared = false

    /**
     * 开始一次导入：把 [uri]（SAF 文档或 content://）复制进缓存目录再预览。
     * @return false = 没有开始（已有导入在进行），调用方应提示用户而不是静默丢弃
     */
    fun begin(uri: Uri): Boolean {
        if (state !is SchemeImportState.Idle) return false
        if (uri.scheme == "file" && !isDebuggable(context)) {
            state = SchemeImportState.Failed("不支持这种来源（file://），请用「导入方案」按钮选择文件，或从文件管理器打开")
            return true
        }
        if (!SchemeBusy.tryAcquire()) {
            state = SchemeImportState.Failed(SchemeBusy.MESSAGE)
            return true
        }
        held = true
        state = SchemeImportState.Working("正在读取方案包…")
        viewModelScope.launch {
            state = withContext(Dispatchers.IO) { copyAndPreview(uri) }
            releaseIfTerminal()
        }
        return true
    }

    private fun copyAndPreview(uri: Uri): SchemeImportState {
        val file = File(SchemeImportFiles.cacheDir(context), "pkg-${System.nanoTime()}.wpkg")
        pendingFile = file
        SchemeImportFiles.markInUse(file)
        var keep = false
        try {
            val copied = context.contentResolver.openInputStream(uri)?.use { SchemeImportFiles.copyLimited(it, file) }
                ?: return SchemeImportState.Failed("无法读取所选文件")
            if (!copied) {
                return SchemeImportState.Failed("文件超过 ${SchemeImportFiles.MAX_PACKAGE_BYTES shr 20}MB，不像是方案包")
            }
            val core = CoreAccess.obtainCore(context) ?: return SchemeImportState.Failed("输入法核心未就绪")
            val info = core.schemePreview(file.absolutePath)
            keep = !cleared
            return SchemeImportState.Preview(file, info)
        } catch (e: ConfigException) {
            return SchemeImportState.Failed(reasonOf(e))
        } catch (e: Exception) {
            return SchemeImportState.Failed("读取失败：${e.message ?: e}")
        } finally {
            if (!keep) {
                file.delete()
                SchemeImportFiles.release(file)
                if (pendingFile == file) pendingFile = null
            }
        }
    }

    fun setReplace(on: Boolean) {
        val s = state as? SchemeImportState.Preview ?: return
        state = s.copy(replace = on)
    }

    /** 点「导入」：会覆盖已有文件（含自带方案 / 自带资源）时先进二次确认，否则直接导入 */
    fun requestImport() {
        val s = state as? SchemeImportState.Preview ?: return
        if (s.needsConfirm && !s.confirming) state = s.copy(confirming = true) else doImport(s)
    }

    fun cancelConfirm() {
        val s = state as? SchemeImportState.Preview ?: return
        state = s.copy(confirming = false)
    }

    fun confirmImport() {
        val s = state as? SchemeImportState.Preview ?: return
        doImport(s)
    }

    private fun doImport(s: SchemeImportState.Preview) {
        state = SchemeImportState.Working("正在导入…")
        viewModelScope.launch {
            state = withContext(Dispatchers.IO) {
                try {
                    val core = CoreAccess.obtainCore(context) ?: return@withContext SchemeImportState.Failed("输入法核心未就绪")
                    val result = core.schemeImport(s.file.absolutePath, s.replace)
                    val rows = core.installedSchemas().filter { it.id in result.schemaIds }
                    SchemeImportState.Done(result, rows)
                } catch (e: ConfigException) {
                    SchemeImportState.Failed(reasonOf(e))
                } catch (e: Exception) {
                    SchemeImportState.Failed("导入失败：${e.message ?: e}")
                } finally {
                    s.file.delete()
                    SchemeImportFiles.release(s.file)
                    if (pendingFile == s.file) pendingFile = null
                }
            }
            // 进入结果页就放锁：之后的「立即启用」不写方案文件，不必再挡住别的导入 / 删除
            releaseIfTerminal()
            // 成功与失败都刷新：核心在中途失败时磁盘也可能已变，列表要与实际一致
            onChanged()
        }
    }

    /** 结果页「立即启用」 */
    fun enable(id: String) {
        val s = state as? SchemeImportState.Done ?: return
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { setEnabled(context, id) }
            if (error != null) {
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val rows = withContext(Dispatchers.IO) {
                CoreAccess.obtainCore(context)?.installedSchemas()?.filter { r -> s.rows.any { it.id == r.id } }
            }
            if (rows != null) state = s.copy(rows = rows)
            onChanged()
        }
    }

    /** 取消 / 关闭：丢掉缓存文件，放锁，回到空闲 */
    fun dismiss() {
        pendingFile?.let {
            it.delete()
            SchemeImportFiles.release(it)
        }
        pendingFile = null
        state = SchemeImportState.Idle
        releaseIfHeld()
    }

    override fun onCleared() {
        cleared = true
        pendingFile?.let {
            it.delete()
            SchemeImportFiles.release(it)
        }
        releaseIfHeld()
    }

    /** 进入 Done / Failed：不再有文件在处理，放锁 */
    private fun releaseIfTerminal() {
        if (state is SchemeImportState.Done || state is SchemeImportState.Failed) releaseIfHeld()
    }

    private fun releaseIfHeld() {
        if (held) {
            held = false
            SchemeBusy.release()
        }
    }
}

private fun isDebuggable(context: Context): Boolean =
    context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

private fun reasonOf(e: ConfigException): String =
    (e as? ConfigException.Rejected)?.reason ?: e.toString()

/** 启用一个已装方案并重载；失败返回原因（后台线程调用） */
private fun setEnabled(context: Context, id: String): String? {
    val core = CoreAccess.obtainCore(context) ?: return "输入法核心未就绪"
    return try {
        core.setSchemaEnabled(id, true)
        core.runCommand(EngineCommand.ReloadConfig)
        null
    } catch (e: ConfigException) {
        reasonOf(e)
    } catch (e: Exception) {
        "操作失败：${e.message ?: e}"
    }
}

// ── 界面 ─────────────────────────────────────────────────────────

/** 导入流程的全部对话框，按 [SchemeImportController.state] 显示其一 */
@Composable
internal fun SchemeImportDialogs(c: SchemeImportController) {
    when (val s = c.state) {
        SchemeImportState.Idle -> Unit
        is SchemeImportState.Working -> Panel(title = "导入方案", onDismiss = {}) {
            Text(s.text)
        }
        is SchemeImportState.Failed -> Panel(
            title = "导入失败",
            onDismiss = c::dismiss,
            buttons = { TextButton(text = "知道了", onClick = c::dismiss) },
        ) { Text(s.reason) }
        is SchemeImportState.Done -> DonePanel(s, c)
        is SchemeImportState.Preview -> if (s.confirming) ConfirmPanel(s, c) else PreviewPanel(s, c)
    }
}

@Composable
private fun PreviewPanel(s: SchemeImportState.Preview, c: SchemeImportController) {
    val info = s.info
    Panel(
        title = "导入方案",
        onDismiss = c::dismiss,
        buttons = {
            TextButton(text = "取消", onClick = c::dismiss)
            TextButton(text = "导入", onClick = c::requestImport)
        },
    ) {
        if (info.title.isNotEmpty()) Text(info.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (info.description.isNotEmpty()) {
            // 说明是分发者提供的任意文本：只当纯文本渲染，限高可滚动，不解析链接
            Spacer(Modifier.height(6.dp))
            Column(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
                Text(info.description, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("包含方案：" + info.schemaIds.joinToString("、"))
        Text("新增 ${info.willAdd.size} 个文件")
        if (info.conflicts.isNotEmpty()) {
            Text("已存在 ${info.conflicts.size} 个同名文件（默认跳过）")
            ToggleLine("覆盖已存在的文件", null, s.replace) { c.setReplace(it) }
        }
        if (s.effectiveOverrideSchemas.isNotEmpty()) {
            Warn("将覆盖自带方案：" + s.effectiveOverrideSchemas.joinToString("、") + "。之后可在方案设置里「恢复自带版本」")
        }
        if (s.effectiveOverrideFiles.isNotEmpty()) {
            Warn("将遮住自带资源文件 ${s.effectiveOverrideFiles.size} 个：" + brief(s.effectiveOverrideFiles) + "。删除或恢复自带版本时会一并清掉")
        }
        if (info.missing.isNotEmpty()) {
            Warn("缺少引用的资源：" + info.missing.joinToString("、") + "。装上后可能用不了")
        }
        if (info.ignoredConfigPatch) {
            Text("此包带配置片段，本版本不会应用", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun ConfirmPanel(s: SchemeImportState.Preview, c: SchemeImportController) {
    val info = s.info
    Panel(
        title = "确认覆盖",
        onDismiss = c::cancelConfirm,
        buttons = {
            TextButton(text = "返回", onClick = c::cancelConfirm)
            TextButton(text = "覆盖", onClick = c::confirmImport)
        },
    ) {
        if (s.effectiveOverrideSchemas.isNotEmpty()) {
            Warn("将覆盖应用自带的方案：" + s.effectiveOverrideSchemas.joinToString("、"))
            Text("覆盖后会改用这个包里的版本，可随时在方案设置里「恢复自带版本」。")
        }
        if (s.effectiveOverrideFiles.isNotEmpty()) {
            Warn("将遮住应用自带的资源文件 ${s.effectiveOverrideFiles.size} 个：" + brief(s.effectiveOverrideFiles))
            Text("遮住后这些文件改用包里的版本；删除或恢复自带版本时会一并清掉。")
        }
        if (s.replace && info.conflicts.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("将覆盖你已有的 ${info.conflicts.size} 个同名文件，原内容无法找回。")
        }
    }
}

@Composable
private fun DonePanel(s: SchemeImportState.Done, c: SchemeImportController) {
    Panel(
        title = "导入完成",
        onDismiss = c::dismiss,
        buttons = { TextButton(text = "完成", onClick = c::dismiss) },
    ) {
        Text("已写入 ${s.result.imported.size} 个文件" + if (s.result.conflicts.isNotEmpty()) "，跳过 ${s.result.conflicts.size} 个已存在的" else "")
        Spacer(Modifier.height(8.dp))
        for (r in s.rows) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(r.name.ifEmpty { r.id }, modifier = Modifier.weight(1f))
                if (r.enabled) Text("已启用", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                else TextButton(text = "立即启用", onClick = { c.enable(r.id) })
            }
        }
    }
}

/** 文件清单只列前几个，其余写「等 N 个」 */
private fun brief(items: List<String>, max: Int = 3): String =
    if (items.size <= max) items.joinToString("、") else items.take(max).joinToString("、") + " 等"

@Composable
private fun Warn(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(text, color = MiuixTheme.colorScheme.error, fontWeight = FontWeight.Medium)
}

/** 对话框外壳：标题 + 可滚动内容 + 按钮行。内容过高时内容区滚动，按钮始终可见 */
@Composable
private fun Panel(
    title: String,
    onDismiss: () -> Unit,
    buttons: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(Modifier.padding(horizontal = 20.dp).fillMaxWidth().heightIn(max = 560.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { buttons() }
            }
        }
    }
}

// ── 已装方案：删除 / 恢复自带版本 ─────────────────────────────────

/**
 * 用户层有同名文件的已装方案：用户导入的可「删除」，覆盖了自带方案的可「恢复自带版本」。
 * 纯自带方案不出现在这里，也就没有任何删除入口。
 */
@Composable
internal fun UserSchemasCard(installed: List<InstalledSchemaInfo>, onChanged: () -> Unit) {
    val mine = installed.filter { it.user }
    if (mine.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<InstalledSchemaInfo?>(null) }
    SectionTitle("导入的方案")
    Card(Modifier.fillMaxWidth()) {
        for (s in mine) {
            NavRow(
                title = s.name.ifEmpty { s.id },
                summary = when {
                    SchemeBusy.busy -> "正在处理其它方案操作，请稍候"
                    s.builtin -> "覆盖了自带方案 · 点此恢复自带版本"
                    else -> "用户导入 · 点此删除"
                },
            ) { if (!SchemeBusy.busy) target = s }
        }
    }
    val t = target ?: return
    val restore = t.builtin
    val label = t.name.ifEmpty { t.id }
    Panel(
        title = if (restore) "恢复自带版本" else "删除方案",
        onDismiss = { target = null },
        buttons = {
            TextButton(text = "取消", onClick = { target = null })
            TextButton(
                text = if (restore) "恢复" else "删除",
                onClick = {
                    target = null
                    if (!SchemeBusy.tryAcquire()) {
                        Toast.makeText(context, SchemeBusy.MESSAGE, Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    scope.launch {
                        val message = try {
                            withContext(Dispatchers.IO) { deleteScheme(context, t.id, restore) }
                        } finally {
                            SchemeBusy.release()
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        onChanged()
                    }
                },
            )
        },
    ) {
        Text(
            if (restore) "将删除你导入的「$label」覆盖文件，回到应用自带的版本。"
            else "将删除「$label」及它的词库文件。其它方案还在用的文件会保留。",
        )
    }
}

/** 删除用户层方案；返回给用户看的结果文案（后台线程调用） */
private fun deleteScheme(context: Context, id: String, restore: Boolean): String {
    val core = CoreAccess.obtainCore(context) ?: return "输入法核心未就绪"
    return try {
        val r = core.schemeDelete(id)
        when {
            // 被其它方案引用（如混输方案用它当主方案）时核心会保留它的文件，什么都没删：不能报成功
            r.schemaIds.isEmpty() -> "「$id」仍被其它方案使用，未删除。请先删除依赖它的方案"
            else -> {
                val head = if (restore) "已恢复自带版本" else "已删除"
                val notes = buildList {
                    if (r.restoredBuiltin.isNotEmpty()) add("同时恢复了 ${r.restoredBuiltin.size} 个被遮住的自带文件")
                    if (r.keptShared.isNotEmpty()) add("${r.keptShared.size} 个共享文件因被其它方案使用而保留")
                }
                if (notes.isEmpty()) head else head + "，" + notes.joinToString("；")
            }
        }
    } catch (e: ConfigException) {
        reasonOf(e)
    } catch (e: Exception) {
        "删除失败：${e.message ?: e}"
    }
}
