package com.windinput.ime.core

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kotlin 侧未捕获异常记录：崩溃栈写进私有目录，「关于 → 分享诊断信息」带上最近一次。
 *
 * 没有云端崩溃收集，用户说「闪退了」时手里什么都没有；Rust 的 panic 已由 [CoreGuard] 兜住，
 * 这里管的是 K 侧自己抛出去的。写完交还系统原来的处理器（照常弹「已停止运行」、结束进程）。
 *
 * 文件放 `noBackupFilesDir`：系统备份天然不收这个目录（备份规则里另有说明）。只留最近一次。
 */
object CrashLog {

    private const val DIR = "crash"
    private const val FILE = "last_crash.txt"

    /** 单个崩溃栈最多记这么多字：栈溢出那种几千帧的，前面的帧就够定位 */
    private const val MAX_CHARS = 16_000

    fun file(context: Context): File = File(File(context.noBackupFilesDir, DIR), FILE)

    /** 在 Application.onCreate 最前面调；重复调用只装一层 */
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(file(context.applicationContext), previous))
    }

    /** 最近一次崩溃的记录；没有返回 null */
    fun last(context: Context): String? = file(context).takeIf { it.isFile }?.let { f ->
        runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 一条崩溃记录的文本：时间、线程、栈（超长截断） */
    fun format(time: Date, threadName: String, error: Throwable): String {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val clipped = if (stack.length > MAX_CHARS) stack.take(MAX_CHARS) + "\n…（已截断）" else stack
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(time)
        return "时间：$stamp\n线程：$threadName\n$clipped"
    }

    private class Handler(
        private val target: File,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            // 记录本身出错也不能挡住系统的处理（否则进程卡在半死状态）
            runCatching {
                target.parentFile?.mkdirs()
                target.writeText(format(Date(), t.name, e))
            }
            previous?.uncaughtException(t, e)
        }
    }
}
