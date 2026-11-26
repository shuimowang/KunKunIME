package com.windinput.ime.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import uniffi.wind_android.ConfigException
import uniffi.wind_android.InstalledSchemaInfo
import uniffi.wind_android.SchemaSettingGroup
import uniffi.wind_android.SchemaSettingItem
import uniffi.wind_android.SchemaSettingKind
import uniffi.wind_android.SchemaSettingsInfo

/**
 * 方案详情页：「启用的方案」里每行的设置图标进来。设计见 docs/schema-settings-plan.md。
 *
 * 值、默认值、有没有被改过、按方案类型给哪些项，全部来自核心（`schema_settings`）；这里只管画。
 * 码表覆盖项的标题与选项文字取设置清单「码表」节（同一个键在全局页怎么叫，这里就怎么叫）。
 *
 * 「跟随」的表达：选回**默认值**就是恢复（删掉这一项的覆盖），不另设「跟随」勾选——手机上
 * 一行一个控件。辅助码例外：PC 是三态（跟随全局 / 开 / 关），这里同样三态。
 */
@Composable
internal fun SchemaDetailSection(schemaId: String, manifest: SettingsManifestData, onLoaded: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val writeOrder = remember { Mutex() }
    var info by remember { mutableStateOf<SchemaSettingsInfo?>(null) }
    var installed by remember { mutableStateOf<InstalledSchemaInfo?>(null) }
    var layoutNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        val loaded = withContext(Dispatchers.IO) { loadSchemaDetail(context, schemaId) }
        when (loaded) {
            is DetailLoad.Failed -> error = loaded.reason
            is DetailLoad.Ok -> {
                info = loaded.info
                installed = loaded.installed
                layoutNames = loaded.layoutNames
                onLoaded(loaded.info.name.ifEmpty { schemaId })
            }
        }
    }
    error?.let {
        Card(modifier = Modifier.fillMaxWidth()) { BasicComponent(title = "读不到方案设置", summary = it) }
        return
    }
    val s = info ?: return

    /** 写一项 / 恢复一项 / 全部恢复：后台写盘（核心随即重载），完后重读一遍真值 */
    fun write(action: SchemaWrite) {
        scope.launch {
            // 串行：快速连点同一项时，后点的必须后落盘（锁只保证互斥，不保证先后）
            val failure = writeOrder.withLock { withContext(Dispatchers.IO) {
                val core = CoreAccess.obtainCore(context) ?: return@withContext "输入法核心未就绪"
                try {
                    when (action) {
                        is SchemaWrite.Set -> core.setSchemaSetting(schemaId, action.key, action.value)
                        is SchemaWrite.Reset -> core.resetSchemaSetting(schemaId, action.key)
                        SchemaWrite.ResetAll -> core.resetSchemaSettings(schemaId)
                    }
                    null
                } catch (e: ConfigException) {
                    (e as? ConfigException.Rejected)?.reason ?: e.toString()
                } catch (e: Exception) {
                    "操作失败：${e.message ?: e}"
                }
            } }
            if (failure != null) Toast.makeText(context, failure, Toast.LENGTH_SHORT).show()
            reload++
        }
    }

    SchemaInfoCard(s, installed)

    // 基本：双拼布局 + 扩展词库
    val layout = s.items.firstOrNull { it.key == KEY_SHUANGPIN_LAYOUT }
    if (layout != null) {
        SectionTitle("方案参数")
        Card(modifier = Modifier.fillMaxWidth()) {
            ChoiceSettingRow("双拼布局", "决定声母韵母怎么落到键位上", layout, layoutNames) { write(it) }
        }
    }
    SchemaDictsSection("扩展词库", listOf(schemaId))

    val behavior = s.items.filter { it.group == SchemaSettingGroup.BEHAVIOR && it.key in BEHAVIOR_ROWS }
    if (behavior.isNotEmpty()) {
        SectionTitle("自定义行为")
        Card(modifier = Modifier.fillMaxWidth()) {
            for (item in behavior) {
                val (title, summary) = BEHAVIOR_ROWS.getValue(item.key)
                when (item.kind) {
                    SchemaSettingKind.BOOL -> BoolSettingRow(title, summary, item) { write(it) }
                    else -> ChoiceSettingRow(title, summary, item, PUNCT_LABELS) { write(it) }
                }
            }
        }
    }

    val codetable = codetableRows(manifest, s.items)
    if (codetable.isNotEmpty()) {
        SectionTitle("方案覆盖")
        Card(modifier = Modifier.fillMaxWidth()) {
            for ((m, item) in codetable) {
                if (item.kind == SchemaSettingKind.BOOL) {
                    BoolSettingRow(m.title, m.summary, item) { write(it) }
                } else {
                    ChoiceSettingRow(m.title, m.summary, item, m.options.toMap()) { write(it) }
                }
            }
        }
    }

    s.items.firstOrNull { it.key == KEY_AUX_ENABLED }?.let { aux ->
        SectionTitle("辅助码")
        Card(modifier = Modifier.fillMaxWidth()) { AuxSettingRow(aux) { write(it) } }
    }

    s.items.firstOrNull { it.key == KEY_SENTENCE }?.let { sentence ->
        SectionTitle("实验性功能")
        Card(modifier = Modifier.fillMaxWidth()) {
            BoolSettingRow("整句输入", "码长超过上限时按码表拼整句（实验性）", sentence) { write(it) }
        }
    }

    // 全部恢复默认：只清本页的项，扩展词库开关不动（核心按键删）
    var confirm by remember { mutableStateOf(false) }
    // 计数口径 = 实际会删的范围：含界面不列的 candidate.layout（PC 写过时也能在这里清掉）
    val changed = s.items.count { it.overridden }
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow(
            title = "全部恢复默认",
            summary = if (changed > 0) "已自定义 $changed 项" else "没有自定义的项",
            onClick = if (changed > 0) ({ confirm = true }) else null,
        )
    }
    if (confirm) {
        Dialog(onDismissRequest = { confirm = false }) {
            Card(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("全部恢复默认", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text("本页自定义过的 $changed 项回到方案默认。扩展词库开关不受影响。")
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(text = "取消", onClick = { confirm = false })
                        TextButton(text = "恢复", onClick = { confirm = false; write(SchemaWrite.ResetAll) })
                    }
                }
            }
        }
    }
}

private sealed interface DetailLoad {
    data class Ok(
        val info: SchemaSettingsInfo,
        val installed: InstalledSchemaInfo?,
        val layoutNames: Map<String, String>,
    ) : DetailLoad
    data class Failed(val reason: String) : DetailLoad
}

/** 后台线程调用：方案设置、来源（已装列表里的层信息）、双拼布局显示名 */
private fun loadSchemaDetail(context: android.content.Context, schemaId: String): DetailLoad {
    val core = CoreAccess.obtainCore(context) ?: return DetailLoad.Failed("输入法核心未就绪")
    return try {
        DetailLoad.Ok(
            info = core.schemaSettings(schemaId),
            installed = core.installedSchemas().firstOrNull { it.id == schemaId },
            layoutNames = core.shuangpinLayouts().associate { it.id to it.name.ifEmpty { it.id } },
        )
    } catch (e: ConfigException) {
        DetailLoad.Failed((e as? ConfigException.Rejected)?.reason ?: e.toString())
    } catch (e: Exception) {
        DetailLoad.Failed(e.message ?: e.toString())
    }
}

/** 方案信息：一行名字，下面一行「类型 · 来源 · 版本 · 作者」，再下面是方案说明 */
@Composable
private fun SchemaInfoCard(s: SchemaSettingsInfo, installed: InstalledSchemaInfo?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(title = s.name.ifEmpty { s.id }, summary = infoSummary(s, installed))
    }
}

/** 开关项：拨回默认值 = 恢复 */
@Composable
private fun BoolSettingRow(title: String, summary: String?, item: SchemaSettingItem, onWrite: (SchemaWrite) -> Unit) {
    ToggleLine(title, rowSummary(summary, item, emptyMap()), item.value == "true") { on ->
        onWrite(writeFor(item, on.toString()))
    }
}

/** 下拉项：值域来自核心，文字取 [labels]（没有的显示原值）；选回默认值 = 恢复 */
@Composable
private fun ChoiceSettingRow(
    title: String,
    summary: String?,
    item: SchemaSettingItem,
    labels: Map<String, String>,
    onWrite: (SchemaWrite) -> Unit,
) {
    // 整数项（码表「自动上屏最短码长」「出简让全」）核心不给值域：用清单选项
    val (values, names) = choiceEntries(item, labels)
    val selected = values.indexOf(item.value)
    WindowDropdownPreference(
        items = names,
        selectedIndex = selected,
        title = title,
        summary = rowSummary(summary, item, labels),
        // 点回当前项不写：否则 PC 钉住、恰等于默认值的覆盖会被当成「选回默认」删掉
        onSelectedIndexChange = { i ->
            if (i != selected) values.getOrNull(i)?.let { onWrite(writeFor(item, it)) }
        },
    )
}

/**
 * 下拉的值与文字。当前值不在值域里（PC / 手改写了清单外的整数、双拼布局已删）时追加一项
 * 「原值（自定义）」，不然下拉会静默显示成第一项，与实际值对不上。
 */
internal fun choiceEntries(item: SchemaSettingItem, labels: Map<String, String>): Pair<List<String>, List<String>> {
    val base = item.options.ifEmpty { labels.keys.toList() }
    val values = if (item.value in base) base else base + item.value
    val names = values.map { v -> labels[v] ?: if (v in base) v else "$v（自定义）" }
    return values to names
}

/** 辅助码三态：跟随全局（显示全局当前值）/ 开 / 关，同 PC 方案设置的「本方案启用辅助码」 */
@Composable
private fun AuxSettingRow(item: SchemaSettingItem, onWrite: (SchemaWrite) -> Unit) {
    WindowDropdownPreference(
        items = auxLabels(item),
        selectedIndex = auxIndex(item),
        title = "本方案启用辅助码",
        summary = "打出候选后按触发键，用字形码进一步筛选",
        onSelectedIndexChange = { i -> if (i != auxIndex(item)) auxWrite(item, i)?.let(onWrite) },
    )
}

// ── 纯逻辑（JVM 单测覆盖）──────────────────────────────────────

internal const val KEY_SHUANGPIN_LAYOUT = "engine.pinyin.shuangpin.layout"
internal const val KEY_AUX_ENABLED = "engine.aux_code.enabled"
internal const val KEY_SENTENCE = "engine.codetable.sentence_input"

/**
 * 自定义行为一节列的项：标题与说明。
 *
 * 不列 `candidate.layout`（候选横排 / 竖排）：手机候选条没有这个区分，核心的布局意图在移动端
 * 没有消费者，开了没反应（核心照样给这一项，数据与 PC 对等）。
 */
internal val BEHAVIOR_ROWS = linkedMapOf(
    "punct.mode" to ("标点" to "切到本方案时用中文还是英文标点；正在使用的方案要切走再切回才生效"),
    "phrases.enabled" to ("加载命令短语" to "关掉后本方案不出短语候选（如 date、tel 这类码）"),
)

internal val PUNCT_LABELS = mapOf(
    "follow" to "跟随全局",
    "chinese" to "中文标点",
    "english" to "英文标点",
)

internal sealed interface SchemaWrite {
    data class Set(val key: String, val value: String) : SchemaWrite
    data class Reset(val key: String) : SchemaWrite
    data object ResetAll : SchemaWrite
}

/** 选回默认值就是恢复（删覆盖、回到跟随），否则写新值 */
internal fun writeFor(item: SchemaSettingItem, value: String): SchemaWrite =
    if (value == item.defaultValue) SchemaWrite.Reset(item.key) else SchemaWrite.Set(item.key, value)

/** 行摘要：说明 + 改过时注明默认值 */
internal fun rowSummary(description: String?, item: SchemaSettingItem, labels: Map<String, String>): String? {
    val changed = if (item.overridden) "已自定义 · 默认：${valueLabel(item, item.defaultValue, labels)}" else null
    return listOfNotNull(description, changed).joinToString("\n").ifEmpty { null }
}

internal fun valueLabel(item: SchemaSettingItem, value: String, labels: Map<String, String>): String =
    when (item.kind) {
        SchemaSettingKind.BOOL -> if (value == "true") "开" else "关"
        else -> labels[value] ?: value
    }

internal fun auxLabels(item: SchemaSettingItem): List<String> =
    listOf("跟随全局（${if (item.defaultValue == "true") "开" else "关"}）", "开启", "关闭")

internal fun auxIndex(item: SchemaSettingItem): Int = when {
    !item.overridden -> 0
    item.value == "true" -> 1
    else -> 2
}

/** 三态的写法：跟随 = 删覆盖；开 / 关 = 写实值（哪怕与全局相同——用户显式选的就钉住） */
internal fun auxWrite(item: SchemaSettingItem, index: Int): SchemaWrite? = when (index) {
    0 -> SchemaWrite.Reset(item.key)
    1 -> SchemaWrite.Set(item.key, "true")
    2 -> SchemaWrite.Set(item.key, "false")
    else -> null
}

internal fun infoSummary(s: SchemaSettingsInfo, installed: InstalledSchemaInfo?): String {
    val facts = listOfNotNull(
        engineTypeLabel(s.engineType),
        installed?.let { sourceLabel(it.user, it.builtin) },
        s.version.takeIf { it.isNotEmpty() }?.let { "版本 $it" },
        s.author.takeIf { it.isNotEmpty() }?.let { "作者 $it" },
    ).joinToString(" · ")
    return listOf(facts, s.description).filter { it.isNotEmpty() }.joinToString("\n")
}

internal fun engineTypeLabel(t: String): String = when (t) {
    "pinyin" -> "全拼"
    "shuangpin" -> "双拼"
    "codetable" -> "码表"
    "mixed" -> "混输"
    "english" -> "英文"
    else -> t
}

internal fun sourceLabel(user: Boolean, builtin: Boolean): String = when {
    user && builtin -> "用户导入（覆盖了自带版本）"
    user -> "用户导入"
    else -> "应用自带"
}

/**
 * 码表覆盖行：设置清单「码表」节里 `schema.codetable.*` 的项，与核心给的 `engine.codetable.*` 一一对上。
 * 顺序、标题、选项文字跟清单；清单有而核心没给的（`auto_phrase.*`：核心没有方案级段）不列。
 */
internal fun codetableRows(
    manifest: SettingsManifestData,
    items: List<SchemaSettingItem>,
): List<Pair<SettingItem, SchemaSettingItem>> =
    manifest.items
        .filter { it.page == "schema" && it.section == "码表" && it.key.startsWith(MANIFEST_CODETABLE) }
        .mapNotNull { m ->
            val key = "engine.codetable." + m.key.removePrefix(MANIFEST_CODETABLE)
            items.firstOrNull { it.key == key && it.group == SchemaSettingGroup.CODE_TABLE }?.let { m to it }
        }

private const val MANIFEST_CODETABLE = "schema.codetable."

/** 本页画得出来的项（「全部恢复」的计数只数看得见的） */
internal fun isShown(item: SchemaSettingItem, manifest: SettingsManifestData): Boolean = when (item.group) {
    SchemaSettingGroup.BEHAVIOR -> item.key in BEHAVIOR_ROWS
    SchemaSettingGroup.CODE_TABLE -> codetableRows(manifest, listOf(item)).isNotEmpty()
    else -> true
}
