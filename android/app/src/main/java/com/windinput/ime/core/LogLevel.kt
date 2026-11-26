package com.windinput.ime.core

import java.io.File

/**
 * 日志级别（设置项 `debug.log_level`）→ Rust 日志过滤串。
 *
 * 核心的调试日志里有用户文字（自造词原文、上屏内容），输入法把它们写进 logcat 过不了隐私审核，
 * 所以出厂是 `warn`（安卓出厂 config.toml 有意与电脑版的 `info` 不同）。
 *
 * 核心构造前就要定日志级别，这时拿不到核心的配置快照，只能自己读两层文件：核心用户层
 * （设置页写的）优先、出厂层兜底，都没有或值不认识就用 [FALLBACK]。
 * 日志订阅器整个进程只装一次，改了设置要等输入法进程重启才生效。
 */
object LogLevel {

    /** 与设置清单 `debug.log_level` 的选项一致 */
    private val LEVELS = setOf("off", "error", "warn", "info", "debug", "trace")

    const val FALLBACK = "warn"

    const val KEY = "debug.log_level"

    /** 用户层优先、出厂层兜底；认不出的值（手改写错）不采用 */
    fun resolve(user: String?, factory: String?): String =
        listOf(user, factory).firstOrNull { it in LEVELS } ?: FALLBACK

    /** 调试 / 跟踪级别：此时 K 侧也记带文字的逐键日志 */
    fun verbose(level: String): Boolean = level == "debug" || level == "trace"

    /**
     * 读盘求当前级别。
     * @param engineConfig 核心用户层 config.toml（用户目录根下）
     * @param dataDir 出厂数据层（filesDir/data）
     */
    fun read(engineConfig: File, dataDir: File): String = resolve(
        UserConfig(engineConfig).readAll()[KEY] as? String,
        UserConfig(File(dataDir, "config.toml")).readAll()[KEY] as? String,
    )
}
