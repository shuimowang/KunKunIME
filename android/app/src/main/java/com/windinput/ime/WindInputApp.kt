package com.windinput.ime

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import com.windinput.ime.core.AssetsInstaller
import com.windinput.ime.core.CrashLog
import com.windinput.ime.core.DataDirs
import com.windinput.ime.core.RustCoreLoader
import kotlin.concurrent.thread

class WindInputApp : Application() {

    lateinit var dataDirs: DataDirs
        private set

    override fun onCreate() {
        super.onCreate()
        // 最先装：后面任何一步崩了都要留下栈（「关于 → 分享诊断信息」带上）
        CrashLog.install(this)
        // 调试包把主线程读写盘记进 logcat（tag StrictMode）：输入法每一帧都在主线程上，
        // 读一次盘在真机上就可能掉帧；正式包不开
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyLog().build(),
            )
        }
        // 触摸丢键统计进诊断导出：用户反馈「碰了字母没上屏」时，按原因计数和最近记录能看出丢在哪条路径
        com.windinput.ime.settings.Diagnostics.registerExtraSection("触摸丢键统计") {
            val k = com.windinput.ime.core.KeyDropStats.global
            buildString {
                appendLine(k.summary())
                val fmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                for (e in k.recent()) {
                    appendLine("${fmt.format(java.util.Date(e.timeMs))} ${e.reason}${e.detail?.let { " $it" } ?: ""}")
                }
            }.trimEnd()
        }
        // 独立应用使用私有目录；沿用 0.132 核心的路径，不运行上游外部目录迁移。
        dataDirs = DataDirs(this).also {
            it.ensureCreated()
        }
        // 出厂数据拷出（首启/升级时执行；小文件阶段秒级，词库进来后改带进度）
        thread(name = "assets-install") {
            AssetsInstaller(this, dataDirs).installIfNeeded()
        }
        // 核心 .so 有几十 MB，dlopen 在主线程上是十几到几十毫秒；先在后台载入，输入法服务
        // onCreate 里再问 available 时多半已经载好
        thread(name = "core-preload") { RustCoreLoader.available }
        // Rust 核心（libwind_android.so）由 IME 服务按需加载（RustCoreLoader）
    }
}
