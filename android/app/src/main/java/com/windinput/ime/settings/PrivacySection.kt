package com.windinput.ime.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 隐私政策（纯文本）。每一条都要与代码事实一致，改功能时一起改：
 * - 联网：清单只申请 VIBRATE，不申请 INTERNET。项目链接交给浏览器打开；
 *   PrivacyPolicyTest 核对 AndroidManifest；
 * - 备份范围：见 res/xml/backup_rules.xml、data_extraction_rules.xml；
 * - 诊断信息的内容：见 [Diagnostics]。
 */
internal val PRIVACY_POLICY: List<Pair<String, String>> = listOf(
    "联网" to
        "困困输入法不申请网络权限。启动、打字和打开设置页时都不会主动联网，也不在应用内检查或下载更新。\n" +
        "没有云输入、云同步、广告或统计上报；输入法自己不上传你输入的内容。\n" +
        "「关于」里的「下载与更新」「反馈问题」以及项目源码链接，会在你点击后交给浏览器打开 GitHub 网页。" +
        "「分享诊断信息」「导出诊断压缩包」由你在系统分享面板里选择发给谁。" +
        "浏览器、分享目标和系统备份可能联网，它们由对应应用或系统管理。",
    "启用时的系统提示" to
        "在系统里启用任何第三方输入法时，系统都会提示「此输入法可能会收集您输入的所有文字，包括密码和信用卡号等个人数据」。" +
        "这是系统对所有输入法的统一提示：输入法确实能看到你打的每一个字，所以要选信得过的。" +
        "困困输入法看到的文字只用于在本机出候选、学习词频，输入法自己不会把它上传到任何地方（系统备份见下文）。" +
        "密码框与应用要求不学习的输入框里，不记词频、不造词、不计统计。",
    "数据只存本地" to
        "以下数据保存在本应用的私有目录里，其他应用读不到：\n" +
        "· 设置（键盘与输入设置）\n" +
        "· 个人词库：你造的词、调整过的词频、自己添加的词条与命令短语\n" +
        "· 输入统计（每天打了多少字，不记内容）\n" +
        "· 剪贴板历史：复制过的文字，未置顶的最多保留 50 条；系统标记为敏感的内容（如密码管理器复制的密码）不记\n" +
        "· 崩溃记录：程序出错时的调用栈，不含你输入的文字",
    "系统备份" to
        "如果你在系统里开了备份（如 Google 备份或手机厂商的换机 / 云备份），设置与个人词库会随系统备份，" +
        "换机后可以恢复。剪贴板历史、崩溃记录、日志与可重建的缓存不进备份。",
    "权限用途" to
        "· 振动：按键振动反馈（强度可在设置里调，也可关闭）\n" +
        "· 网络：不申请网络权限；网页链接交给浏览器打开\n" +
        "· 输入法服务：系统要求输入法声明的绑定权限，只有系统能连接它\n" +
        "困困输入法不申请麦克风、通讯录、位置、存储等权限。语音输入是把你转到系统里另装的语音输入法，" +
        "本应用不录音。导入方案、导入导出词库时读写的文件，都由你在系统文件选择器里逐次选定，或由你从别的应用打开 / 分享过来。",
    "日志" to
        "程序日志写进系统日志（logcat），出厂只记警告与错误，不含输入的文字。" +
        "「高级 → 调试」里把级别调到「调试」「跟踪」时会记下输入内容，只供排查问题，用完请改回。" +
        "例外：设置首页「试一试」的测试输入框会把框里的文字记进系统日志（供自动化测试读取），" +
        "请不要在那里输入隐私内容；「分享诊断信息」与诊断压缩包不包含这部分日志。",
    "删除数据" to
        "· 剪贴板历史：剪贴板面板里逐条删除或清空\n" +
        "· 输入统计：统计页清空\n" +
        "· 个人词：词库页删除或清空\n" +
        "· 全部数据：系统设置 → 应用 → 困困输入法 → 存储 → 清除数据；卸载本应用也会删除全部数据",
)

@Composable
internal fun PrivacySection() {
    val context = LocalContext.current
    for ((title, body) in PRIVACY_POLICY) {
        SectionTitle(title)
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = body,
                modifier = Modifier.padding(16.dp),
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
    SectionTitle("更多")
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow("项目源码", ProjectLinks.SOURCE_URL, icon = SettingsIcons.website) {
            Diagnostics.openUrl(context, ProjectLinks.SOURCE_URL)
        }
    }
}
