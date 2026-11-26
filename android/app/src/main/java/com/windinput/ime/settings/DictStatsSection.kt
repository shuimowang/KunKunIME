package com.windinput.ime.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.windinput.ime.core.ImeSession
import com.windinput.ime.core.SchemaEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import uniffi.wind_android.ConfigException

/** 重码 Top 取多少组（电脑版对话框默认也是 20 组） */
private const val TOP_N = 20u

/** 页面上先摊开的 Top 组数；其余点「查看更多」 */
private const val TOP_SHOWN = 5

/**
 * 词库统计：当前方案的系统词库条目、码长、简码、重码、字集覆盖。数字与电脑版「词库统计」
 * 对话框同一份（核心 `dict_stats_api`），文字见 [DictStatsFormat]。
 *
 * 只看**当前方案**（与词库页其余部分一致）。整表扫一遍，拼音大词库百毫秒到秒级、峰值几十 MB，
 * 放 IO 线程；核心按词库指纹缓存结果，再次打开是秒出的。
 */
@Composable
internal fun DictStatsSection(session: ImeSession?) {
    val context = LocalContext.current
    val schema by produceState<SchemaEntry?>(null, session) {
        value = withContext(Dispatchers.IO) { session?.schemas()?.firstOrNull { it.active } }
    }
    var result by remember { mutableStateOf<Result<JSONObject>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var showAllTop by remember { mutableStateOf(false) }
    val current = schema

    LaunchedEffect(current?.id, refresh) {
        val id = current?.id ?: return@LaunchedEffect
        result = null
        result = withContext(Dispatchers.IO) {
            runCatching {
                val core = CoreAccess.obtainCore(context) ?: error("输入法核心未就绪")
                JSONObject(core.dictStats(id, TOP_N, refresh > 0))
            }
        }
    }

    if (current == null) {
        SectionTitle("正在启动输入法核心…")
        return
    }
    val r = result
    if (r == null) {
        SectionTitle("统计中…首次统计可能需要先编译词库，请稍候")
        return
    }
    val v = r.getOrElse { e ->
        val reason = (e as? ConfigException.Rejected)?.reason ?: e.message ?: e.toString()
        SectionTitle("统计失败：$reason")
        Card(modifier = Modifier.fillMaxWidth()) {
            NavRow("重试", null) { refresh++ }
        }
        return
    }

    val columns = DictStatsFormat.columns(v)
    for (col in columns) {
        val name = col.optString("schemaName").ifEmpty { col.optString("schemaId") }
        SectionTitle(if (columns.size > 1) "成员方案：$name" else name)
        Card(modifier = Modifier.fillMaxWidth()) {
            for (line in DictStatsFormat.overviewLines(col)) NavRow(line.title, line.detail)
        }

        SectionTitle("各词库")
        Card(modifier = Modifier.fillMaxWidth()) {
            for (line in DictStatsFormat.layers(col)) NavRow(line.title, line.detail)
        }

        val codeLen = DictStatsFormat.codeLen(col)
        val shortCodes = DictStatsFormat.shortCodes(col)
        if (codeLen.isNotEmpty() || shortCodes != null) {
            SectionTitle("码长分布")
            Card(modifier = Modifier.fillMaxWidth()) {
                for (line in codeLen) NavRow(line.title, line.detail)
                shortCodes?.let { NavRow("简码", it.removePrefix("简码：")) }
            }
        }

        SectionTitle(if (col.optString("engineType") == "pinyin") "同码" else "重码")
        Card(modifier = Modifier.fillMaxWidth()) {
            NavRow("合计", DictStatsFormat.dup(col))
            DictStatsFormat.mainDup(col)?.let { NavRow("主库", it.removePrefix("主库：")) }
            DictStatsFormat.charFull(col)?.let { NavRow("单字全码", it.removePrefix("单字全码：")) }
            DictStatsFormat.extra(col)?.let { NavRow("多码 / 多音", it) }
        }
        val top = DictStatsFormat.top(col, TOP_N.toInt())
        if (top.isNotEmpty()) {
            SectionTitle("重码最多的编码（成员按序位）")
            Card(modifier = Modifier.fillMaxWidth()) {
                val shown = if (showAllTop) top else top.take(TOP_SHOWN)
                for (line in shown) NavRow(line.title, line.detail)
                if (!showAllTop && top.size > TOP_SHOWN) {
                    NavRow("查看更多", "共 ${top.size} 组") { showAllTop = true }
                }
            }
        }

        val charsets = DictStatsFormat.charsets(col)
        if (charsets.isNotEmpty()) {
            SectionTitle("字集覆盖")
            Card(modifier = Modifier.fillMaxWidth()) {
                for (line in charsets) NavRow(line.title, line.detail)
                DictStatsFormat.freq(col)?.let { NavRow("说明", "静重：字集内按单字全码分组的非首选字数；选重率：按字频加权、最优码不在首选的比例。$it") }
            }
        }
    }

    SectionTitle(DictStatsFormat.status(v))
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow("重新统计", "不用缓存，重新扫一遍词库") { refresh++ }
    }
}
