package com.windinput.ime.settings

import androidx.compose.foundation.layout.fillMaxWidth
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
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import uniffi.wind_android.ConfigException
import uniffi.wind_android.SchemaDictItem

/**
 * 方案的附加词库开关（拼音的 16 个细胞词库、五笔的 Emoji / 行政区域…），列表由核心给。
 *
 * [schemaIds] 的第一个是显示来源，开关同时写到每一个：拼音与双拼各有一份同样的细胞词库清单，
 * 用户在「拼音」页里开一个「成语」，指的是两种拼音都要有，不该让他再去双拼那边找一遍。
 * 不在列表里的方案（某方案没有这个库）跳过，不报错。
 */
@Composable
internal fun SchemaDictsSection(title: String, schemaIds: List<String>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dicts by remember { mutableStateOf<List<SchemaDictItem>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        dicts = withContext(Dispatchers.IO) {
            // 读列表出错（核心 panic 经 UniFFI 抛出）就当没有：设置页不该因此崩
            runCatching { CoreAccess.obtainCore(context)?.schemaDicts(schemaIds.first()) }.getOrNull().orEmpty()
        }
    }
    if (dicts.isEmpty()) return

    SectionTitle(title)
    Card(modifier = Modifier.fillMaxWidth()) {
        for (d in dicts) {
            ToggleLine(d.label.ifEmpty { d.id }, d.description.ifEmpty { null }, d.enabled) { on ->
                scope.launch {
                    val error = dictWriteLock.withLock {
                        withContext(Dispatchers.IO) {
                        val core = CoreAccess.obtainCore(context) ?: return@withContext "输入法核心未就绪"
                        try {
                            for (id in schemaIds) {
                                if (core.schemaDicts(id).any { it.id == d.id }) core.setSchemaDictEnabled(id, d.id, on)
                            }
                            null
                        } catch (e: ConfigException) {
                            (e as? ConfigException.Rejected)?.reason ?: e.toString()
                        } catch (e: Exception) {
                            "操作失败：${e.message ?: e}"
                        }
                        }
                    }
                    if (error != null) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                    reload++
                }
            }
        }
    }
}

/**
 * 附加词库的写入串行：核心每次是「读整份 dictionaries → 改一项 → 整份写回覆盖」，
 * 连点两个开关时两个写入并行，后写的会把先写的落盘结果盖掉（内存里已翻、重启后对不上）。
 */
private val dictWriteLock = Mutex()
