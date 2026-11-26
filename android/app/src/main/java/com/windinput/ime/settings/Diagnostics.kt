package com.windinput.ime.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import com.windinput.ime.core.CrashLog
import com.windinput.ime.core.LogLevel
import com.windinput.ime.core.UiTrace
import com.windinput.ime.core.UserConfig
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「关于」里的反馈与诊断：打开 issue 页、经系统分享发出一份诊断信息。
 *
 * 诊断信息 = 版本、系统、机型 + 本应用最近的日志尾部 + 最近一次 K 侧崩溃栈。**不含用户文字**：
 * - 日志只取 INFO 及以上（核心的约定是 info 及以上不记用户输入；带文字的逐键日志是 DEBUG），
 * - 测试台（设置首页「试一试」）把输入框原文记在 WindTestPad 标签下（e2e 靠它读结果），整个排除。
 * 发不发、发给谁都由用户在系统分享面板里选，本应用自己不联网。
 */
object Diagnostics {

    const val ISSUE_URL = ProjectLinks.ISSUES_URL

    /** 日志尾部取几行：够看清出错前后，又不至于贴进 issue 里一大屏 */
    const val LOG_LINES = 300

    /** 压缩包里的日志取几行：跨进程（含崩溃前的那次运行），比分享文本里的尾部大得多 */
    const val ZIP_LOG_LINES = 5000

    private const val TAG = "Diagnostics"
    private const val ZIP_DIR = "diagnostics"

    /**
     * 额外的诊断段：别的模块（如触摸丢键统计）在这里登记一个标题与取文本的函数，导出诊断时
     * 各自成段追加。这样诊断类不必依赖那些模块，也不用每加一类数据就改一遍 [collect]。
     * 取文本的函数在导出线程里调用，抛异常的段会被跳过而不影响其余内容。
     */
    private val extraSections = CopyOnWriteArrayList<Pair<String, () -> String>>()

    fun registerExtraSection(title: String, provider: () -> String) {
        extraSections.removeAll { it.first == title }
        extraSections += title to provider
    }

    internal fun collectExtraSections(): List<Pair<String, String>> =
        extraSections.mapNotNull { (title, provider) -> runCatching { title to provider() }.getOrNull() }

    /**
     * 与键盘空白 / 尺寸相关的用户设置，一行写完。读不到的项写 ?：空白可能是用户自己设了留空，
     * 光看合成后的避让值分不出来。只含数值，不含任何输入内容。
     */
    internal fun keyboardSettingsLine(readInt: (key: String, default: Int) -> Int?): String {
        fun v(key: String, default: Int) = readInt(key, default)?.toString() ?: "?"
        return "竖屏键行高 ${v("ui.android.row_height_portrait", 60)}dp  横屏键行高 ${v("ui.android.row_height_landscape", 44)}dp  " +
            "底部留空 ${v("ui.android.keyboard_bottom_gap", 0)}dp  侧边留空 ${v("ui.android.keyboard_side_gap", 0)}dp"
    }

    /** 压缩包里的 README：让收到包的人（和导出包的用户）知道里面是什么、怎么复现 */
    internal fun readme(versionName: String): String = """
        困困输入法诊断包（版本 $versionName）

        反馈键盘显示问题（如下方空白）时的操作：
        1. 打开出问题的界面，让键盘弹出并停留约 2 秒；
        2. 设置 → 关于 → 导出诊断压缩包，发给开发者；
        3. 另附一张截图，标出空白的位置。

        包内文件：
        - diagnostics.txt  版本、系统、机型、键盘设置、窗口与 insets 数值、界面诊断轨迹、最近日志
        - logcat.txt / logcat-crash.txt  INFO 及以上日志（含崩溃前一次运行）与系统崩溃缓冲区
        - crash.txt  最近一次崩溃栈

        隐私：只含数值、开关、应用包名与输入类型位，不含输入的文字；
        不含设备序列号、IMEI 或账号。本应用不联网，发给谁由你在系统分享面板里选。
    """.trimIndent() + "\n"

    /** 只读本进程的日志（应用读不到别的进程），过滤见类注释 */
    internal fun logcatCommand(pid: Int): List<String> = listOf(
        "logcat", "-d", "-t", LOG_LINES.toString(), "--pid", pid.toString(), "-v", "time",
        "WindTestPad:S", "*:I",
    )

    /**
     * 压缩包用的日志命令：不按 pid 过滤。应用读 logcat 本来就只能读到**自己 uid** 的日志，
     * 去掉 pid 过滤后能带上崩溃前那次运行（旧进程）的日志——按 pid 取的话重启后全是新进程的。
     * 过滤规则同 [logcatCommand]（INFO 及以上，测试台标签静音）。[crashBuffer] 读系统的 crash 缓冲区。
     */
    internal fun zipLogcatCommand(crashBuffer: Boolean): List<String> = buildList {
        add("logcat"); add("-d")
        if (crashBuffer) { add("-b"); add("crash") }
        addAll(listOf("-t", ZIP_LOG_LINES.toString(), "-v", "threadtime", "WindTestPad:S", "*:I"))
    }

    private fun readLogcat(cmd: List<String>): List<String> = runCatching {
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().use { it.readLines() }.also { proc.waitFor() }
    }.onFailure { Log.w(TAG, "读日志失败", it) }.getOrDefault(emptyList())

    /**
     * 打成压缩包（**后台线程调用**）：diagnostics.txt（同分享文本）、logcat.txt、logcat-crash.txt、crash.txt。
     * 放 cacheDir/diagnostics/，每次先清掉旧包；经 FileProvider 分享，内容范围同分享文本（不含用户输入）。
     */
    fun buildZip(context: Context, versionName: String): File {
        val dir = File(context.cacheDir, ZIP_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "KunKunIME-diagnostics-$versionName-$stamp.zip")
        val entries = linkedMapOf(
            "README.txt" to readme(versionName),
            "diagnostics.txt" to collect(context, versionName),
            "logcat.txt" to readLogcat(zipLogcatCommand(false)).joinToString("\n"),
            "logcat-crash.txt" to readLogcat(zipLogcatCommand(true)).joinToString("\n"),
            "crash.txt" to (CrashLog.last(context) ?: "（无）"),
        )
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out
    }

    fun shareZip(context: Context, zip: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_SUBJECT, "困困输入法诊断包")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "导出诊断包").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 拼成最终文本（纯函数，单测覆盖） */
    fun compose(
        header: List<Pair<String, String>>,
        logTail: List<String>,
        crash: String?,
        ui: List<String> = emptyList(),
        extra: List<Pair<String, String>> = emptyList(),
    ): String = buildString {
        append("困困输入法诊断信息\n")
        for ((k, v) in header) append(k).append("：").append(v).append('\n')
        append("\n── 最近一次崩溃 ──\n")
        append(crash?.trimEnd() ?: "（无）").append('\n')
        if (ui.isNotEmpty()) {
            append("\n── 界面诊断（键盘避让/insets，不含用户输入）──\n")
            for (line in ui) append(line).append('\n')
        }
        for ((title, body) in extra) {
            append("\n── ").append(title).append(" ──\n")
            append(body.trimEnd()).append('\n')
        }
        append("\n── 最近日志（INFO 及以上，").append(logTail.size).append(" 行）──\n")
        if (logTail.isEmpty()) append("（读不到日志）\n")
        for (line in logTail) append(line).append('\n')
    }

    /** 收集并拼好（读日志要起子进程，**后台线程调用**） */
    fun collect(context: Context, versionName: String): String {
        val header = listOf(
            "版本" to versionName,
            "系统" to "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
            "机型" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "架构" to Build.SUPPORTED_ABIS.joinToString(),
            "键盘设置" to keyboardSettingsLine { key, default ->
                runCatching {
                    UserConfig((context.applicationContext as com.windinput.ime.WindInputApp).dataDirs.keyboardConfigFile)
                        .getInt(key, default)
                }.getOrNull()
            },
            "核心日志级别" to LogLevel.read(
                (context.applicationContext as com.windinput.ime.WindInputApp).dataDirs.engineConfigFile,
                File(context.filesDir, "data"),
            ),
        )
        val logs = runCatching {
            val proc = ProcessBuilder(logcatCommand(Process.myPid())).redirectErrorStream(true).start()
            proc.inputStream.bufferedReader().use { it.readLines() }.also { proc.waitFor() }
        }.onFailure { Log.w(TAG, "读日志失败", it) }.getOrDefault(emptyList())
        return compose(header, logs, CrashLog.last(context), UiTrace.deviceInfo(context) + UiTrace.snapshot(), collectExtraSections())
    }

    /** 交给浏览器打开项目发布页等链接。只传 https，别的一律不开。 */
    fun openUrl(context: Context, url: String) {
        if (!url.startsWith("https://")) return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "打不开链接", it) }
    }

    fun openIssues(context: Context) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "打不开反馈页", it) }
    }

    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "困困输入法诊断信息")
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "分享诊断信息").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
