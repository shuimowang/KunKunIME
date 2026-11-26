package com.windinput.ime.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import uniffi.wind_android.DailyTotalInfo
import uniffi.wind_android.StatsSummaryInfo
import java.time.LocalDate

/** 每日图覆盖的天数 */
private const val CHART_DAYS = 30L

/**
 * 统计页的概览区：数字 + 近 30 天每日字数柱状图 + 清空。数据形状照电脑版统计页
 * （`wind-setting/src/pages/stats`），算法在核心（与电脑版同一份，见主仓 `stats_report`）。
 * 「启用输入统计」开关来自清单，画在本区之后。
 */
@Composable
internal fun StatsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf<StatsSummaryInfo?>(null) }
    var days by remember { mutableStateOf<List<DailyTotalInfo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        withContext(Dispatchers.IO) {
            val core = CoreAccess.obtainCore(context)
            val to = LocalDate.now()
            val from = to.minusDays(CHART_DAYS - 1)
            summary = core?.statsSummary()
            days = core?.statsDaily(from.toString(), to.toString()).orEmpty()
        }
        loaded = true
    }

    if (!loaded) {
        SectionTitle("正在读取统计…")
        return
    }
    val s = summary
    if (s == null) {
        SectionTitle("统计暂不可用（输入法核心未就绪）")
        return
    }

    SectionTitle("概览")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatRow("今日输入", "${s.todayChars} 字", "中 ${s.todayChinese} · 英 ${s.todayEnglish}")
            StatRow("累计输入", "${s.totalChars} 字", "${s.activeDays} 天")
            StatRow("日均输入", "${s.dailyAvg} 字", "连续 ${s.streakCurrent} 天，最长 ${s.streakMax} 天")
            StatRow("本周 / 本月", "${s.weekChars} / ${s.monthChars} 字", null)
            StatRow(
                "打字速度",
                "${s.todaySpeed} 字/分钟",
                "平均 ${s.overallSpeed} · 历史最快 ${s.maxSpeed}",
            )
        }
    }

    SectionTitle("近 $CHART_DAYS 天")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            DailyBars(days)
            val max = days.maxOfOrNull { it.total.toInt() } ?: 0
            Text(
                text = if (max > 0) "最高一天 $max 字" else "还没有输入记录",
                modifier = Modifier.padding(top = 8.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }

    SectionTitle("数据")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            NavRow(
                if (confirmClear) "再点一次确认清空" else "清空统计",
                if (confirmClear) "所有日期的输入统计都会删除，不能恢复" else "删除全部输入统计",
            ) {
                if (!confirmClear) {
                    confirmClear = true
                } else {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { CoreAccess.obtainCore(context)?.statsClear() }
                        }
                        reload++
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(title: String, value: String, detail: String?) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            Text(text = value)
            if (detail != null) {
                Text(text = detail, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

/** 每日字数柱状图：一天一根，高度按区间最大值归一；没数据的日子画一条底线 */
@Composable
private fun DailyBars(days: List<DailyTotalInfo>) {
    val barColor = MiuixTheme.colorScheme.primary
    val baseColor = MiuixTheme.colorScheme.dividerLine
    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
        if (days.isEmpty()) return@Canvas
        val max = days.maxOf { it.total.toInt() }.coerceAtLeast(1)
        val slot = size.width / days.size
        val barW = slot * 0.6f
        days.forEachIndexed { i, d ->
            val x = i * slot + (slot - barW) / 2
            val h = size.height * d.total.toInt() / max
            if (h > 0f) {
                drawRect(barColor, Offset(x, size.height - h), Size(barW, h))
            } else {
                drawRect(baseColor, Offset(x, size.height - 2f), Size(barW, 2f))
            }
        }
    }
}
