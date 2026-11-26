package com.windinput.ime.settings

import android.content.Context
import android.util.Log
import com.windinput.ime.WindInputApp
import com.windinput.ime.core.AssetsInstaller
import com.windinput.ime.core.ImeSession
import com.windinput.ime.core.RustCoreLoader
import com.windinput.ime.core.RustSession
import java.io.File

/**
 * 设置页取用输入法核心的入口。
 *
 * # 为什么设置页可以直接拿到核心
 *
 * 设置 Activity 与输入法服务**在同一个进程**里，而 [RustSession] 是进程内单例。
 * 键盘正开着时（工具栏 ⚙ 进来的那条路）拿到的就是那个正在服务的核心，改方案立刻生效；
 * 从桌面图标进来、输入法还没起过时则由本对象把它建起来，后续服务启动会复用同一个。
 *
 * 这样只有一条通路。曾经考虑让设置页自己写核心的用户配置文件再让引擎重载——那需要
 * K 侧复刻一份 TOML 路径与合并语义，两边任一处漂移都表现为「设置里改了、打字没变」。
 *
 * 构造要放**后台线程**：首次构造含出厂数据拷出与词库编译，真机实测约 1.1 秒。
 */
object CoreAccess {

    private const val TAG = "CoreAccess"

    /**
     * 取核心会话，没有就建（阻塞，**调用方须在后台线程**）。
     *
     * @return null = 本机没有可用的 .so，或核心构造失败（设置页据此隐藏方案相关项）
     */
    fun obtain(context: Context): ImeSession? {
        if (!RustCoreLoader.available) {
            Log.w(TAG, "无可用核心库，方案设置不可用")
            return null
        }
        val dirs = (context.applicationContext as WindInputApp).dataDirs
        return try {
            AssetsInstaller(context.applicationContext, dirs).installIfNeeded()
            RustSession.getOrCreate(dirs.dataDir, dirs.userRoot, dirs.localRoot)
        } catch (e: Throwable) {
            Log.e(TAG, "核心取用失败", e)
            null
        }
    }

    /** 设置页的数据面（配置读写、统计）。阻塞，后台线程调用 */
    fun obtainCore(context: Context): uniffi.wind_android.WindCore? =
        (obtain(context) as? RustSession)?.core
}
