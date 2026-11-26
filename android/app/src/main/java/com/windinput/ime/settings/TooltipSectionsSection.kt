package com.windinput.ime.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import uniffi.wind_android.ConfigException
import uniffi.wind_android.EngineCommand
import uniffi.wind_android.TooltipSectionStateInfo

/**
 * 外观 →「候选字体」底下的「长按候选提示」：长按候选弹出的提示卡显示哪几段（编码 / 拼音 /
 * 拆字 / 释义 / Unicode…），逐段开关。
 *
 * 段列表就是电脑版候选悬停气泡的 `ui.tooltip.sections`（两端共用一份配置），条数与名字
 * 由核心给，所以不进设置清单。「拆字」出厂关着（同电脑版），五笔用户打开就能看字根拆分。
 */
@Composable
internal fun TooltipSectionsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sections by remember { mutableStateOf<List<TooltipSectionStateInfo>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        sections = withContext(Dispatchers.IO) {
            runCatching { CoreAccess.obtainCore(context)?.tooltipSections() }.getOrNull().orEmpty()
        }
    }
    // 「完整原文」只在候选被截断时出现、「调试」是排查用的，手机上开关它们没有意义
    val shown = sections.filter { it.title.isNotEmpty() && it.title !in HIDDEN }
    if (shown.isEmpty()) return

    SectionTitle("长按候选提示")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            for (s in shown) {
                ToggleLine(title = s.title, summary = SUMMARIES[s.title], checked = s.enabled) { on ->
                    scope.launch {
                        val error = withContext(Dispatchers.IO) {
                            val core = CoreAccess.obtainCore(context) ?: return@withContext "输入法核心未就绪"
                            try {
                                core.setTooltipSectionEnabled(s.label, on)
                                core.runCommand(EngineCommand.ReloadConfig)
                                null
                            } catch (e: ConfigException) {
                                (e as? ConfigException.Rejected)?.reason ?: e.toString()
                            } catch (e: Exception) {
                                "操作失败：${e.message ?: e}"
                            }
                        }
                        if (error != null) Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                        reload++
                    }
                }
            }
        }
    }
}

private val HIDDEN = setOf("完整原文", "调试")

private val SUMMARIES = mapOf(
    "编码" to "这个词在码表方案里怎么打",
    "拼音" to "逐字读音",
    "拆字" to "五笔字根拆分（逐字）",
    "释义" to "注释库里的解释",
    "Unicode" to "逐字码位",
)
