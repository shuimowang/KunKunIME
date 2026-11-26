package com.windinput.ime.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * 出厂数据安装器：把 APK assets/data 整树拷到 filesDir/data。
 *
 * 版本锚点 = `versionCode:lastUpdateTime`（写入 data/.installed_version）：
 * - 首启 / 升级 / **重装同版本** → 出厂层整体重放（用户层不动）；
 * - 未变 → 跳过，零 I/O。
 *
 * ⚠ 锚点里必须带 `lastUpdateTime`，只用 versionCode 会在开发迭代中骗人：改了 assets
 * 但没动版本号时 `installDebug` 装上去也不会重放，引擎读到的还是旧数据，现象是
 * 「明明加了 system.phrases.toml，日志还说缺失」。重装同版本多拷一次的代价只有
 * 几十毫秒，远低于这种误判的排查成本。
 *
 * 词库 yaml（GPL 合规，原文分发）后续同走此通道进入 data/schemas/，
 * mmap 与端上编译均要求真实文件路径，故必须拷出而非直接读 assets。
 */
class AssetsInstaller(private val context: Context, private val dirs: DataDirs) {

    private companion object {
        const val TAG = "AssetsInstaller"
        const val ASSET_ROOT = "data"
        const val VERSION_MARKER = ".installed_version"
        // Application 预装、IME 初始化和设置页各有实例；必须按进程串行检查与拷贝，
        // 否则首次启动会同时写同一个 .tmp 文件，导致 rename 失败或读到不完整数据。
        val installLock = Any()
    }

    /** 同步执行（调用方放后台线程）。@return 本次是否执行了拷贝 */
    fun installIfNeeded(): Boolean = synchronized(installLock) {
        val current = currentVersion()
        val marker = File(dirs.dataDir, VERSION_MARKER)
        if (marker.isFile && marker.readText().trim() == current) {
            return@synchronized false
        }
        dirs.ensureCreated()
        copyAssetDir(ASSET_ROOT, dirs.dataDir)
        marker.writeText(current)
        Log.i(TAG, "factory data installed, version=$current")
        true
    }

    private fun currentVersion(): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "$code:${info.lastUpdateTime}"
    } catch (e: PackageManager.NameNotFoundException) {
        "unknown"
    }

    private fun copyAssetDir(assetPath: String, target: File) {
        val assets = context.assets
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            // 叶子 = 文件
            target.parentFile?.mkdirs()
            // 先写临时文件再改名：核心与键盘布局的预热线程可能正在读同一个文件，
            // 直接覆盖写会让它们读到截断后的半个文件
            val tmp = File(target.parentFile, ".${target.name}.tmp")
            assets.open(assetPath).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("cannot replace ${target.path}")
            }
            return
        }
        target.mkdirs()
        for (child in children) {
            copyAssetDir("$assetPath/$child", File(target, child))
        }
    }
}
