package com.windinput.ime.core

import android.util.Log

/**
 * Rust 核心（libwind_android.so）加载器。
 *
 * UniFFI 生成绑定（uniffi.wind_android 包）由 JNA 按库名自动加载；
 * 这里仅做一次显式预加载探测，失败则 [available] = false，
 * 会话层展示初始化错误并保持直输，不用演示候选伪装中文引擎。
 */
object RustCoreLoader {
    private const val TAG = "RustCoreLoader"

    val available: Boolean by lazy {
        try {
            System.loadLibrary("wind_android")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "无法加载 libwind_android.so：安装包缺少本机 ABI 的中文输入核心，或核心依赖不兼容", e)
            false
        }
    }
}
