package com.windinput.ime.core

/**
 * 核心调用的兜底：Rust 侧 panic 经 UniFFI 变成 Kotlin 的 `InternalException`，没人接的话一路抛到
 * 主线程，整个输入法进程崩溃、键盘当场消失。
 *
 * 兜住是有意义的：核心里取锁普遍「中毒也照常取出」，一次 panic 后核心还能接着用。
 * 这里把那一下当作「核心没处理」（按键直接交给宿主上屏），记日志，**首次**出错时提示用户一次
 * （之后每次照记日志、不再打扰）。
 *
 * 只兜 [Exception]：[Error]（内存不足等）照常抛出，那不是「核心这一下出错」能糊过去的。
 * 不依赖 Android（日志与提示由调用方注入），JVM 单测直接跑。
 */
class CoreGuard(
    private val onFailure: (what: String, error: Exception, firstTime: Boolean) -> Unit,
) {
    @Volatile
    private var failed = false

    /** 执行 [block]；抛异常则回调 [onFailure] 并返回 [fallback] */
    fun <T> run(what: String, fallback: T, block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            val first = !failed
            failed = true
            onFailure(what, e, first)
            fallback
        }
}
