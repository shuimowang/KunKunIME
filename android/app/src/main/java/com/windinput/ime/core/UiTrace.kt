package com.windinput.ime.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.WindowInsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 界面诊断轨迹：键盘布局相关的关键事件（窗口 insets 原始值、避让取值与最终高度等）记在进程内的
 * 小环形缓冲里，「分享诊断信息」带上。
 *
 * 为什么不靠 logcat：logcat 尾部被系统/厂商日志刷屏，且 INFO 以下默认不带；而「键盘下方空白」
 * 这类问题的关键就是**各类 insets 的原始值**，没有它只能猜。这里只记数值与开关，不含任何用户输入。
 */
object UiTrace {

    /**
     * 缓冲条数。输入时一次 insets 抖动就是十几条，60 条会把「键盘弹出那一刻」的记录刷掉，
     * 而空白类问题恰恰要看弹出瞬间，故放到 150。
     */
    internal const val MAX = 150
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 事件类别：写进每条记录的方括号里，读诊断包时能一眼分出是哪一类 */
    const val INSETS = "insets"
    const val COMMIT = "commit"
    const val LAYOUT = "layout"
    const val REBUILD = "rebuild"
    const val INPUT = "input"

    @Synchronized
    fun add(msg: String) = add(null, msg)

    /** 带类别的一条；[category] 为空则不加标签 */
    @Synchronized
    fun add(category: String?, msg: String) {
        if (lines.size >= MAX) lines.removeFirst()
        lines.addLast(formatLine(fmt.format(Date()), category, msg))
    }

    /** 一条记录的文本形态（纯函数，单测覆盖） */
    internal fun formatLine(time: String, category: String?, msg: String): String =
        if (category.isNullOrEmpty()) "$time $msg" else "$time [$category] $msg"

    /**
     * 是否被系统强制 edge-to-edge：Android 15（API 35）起，targetSdk ≥ 35 的应用无法再退出。
     * 输入法窗口的底部处理在两种模式下差别很大，诊断里要带上。
     */
    internal fun edgeToEdgeForced(sdk: Int, targetSdk: Int): Boolean = sdk >= 35 && targetSdk >= 35

    /** 一次开始输入的记录：只记包名与输入类型位，**不含**输入框里的任何文字、提示或标签 */
    internal fun describeInput(pkg: String?, inputType: Int, imeOptions: Int, restarting: Boolean, cutoutMode: Int?): String =
        "开始输入 pkg=${pkg ?: "?"} inputType=0x${Integer.toHexString(inputType)} " +
            "imeOptions=0x${Integer.toHexString(imeOptions)} restarting=$restarting " +
            "cutoutMode=${cutoutMode ?: "?"}"

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    /** 各类 insets 的底部/左右原始值，一行写完（API 30+）；更早的系统只有一个 systemWindowInsetBottom */
    fun describeInsets(insets: WindowInsets): String {
        if (Build.VERSION.SDK_INT < 30) {
            @Suppress("DEPRECATION")
            return "systemWindowInsetBottom=${insets.systemWindowInsetBottom}"
        }
        fun b(name: String, type: Int) = insets.getInsets(type).let { "$name=${it.bottom}" + if (it.left != 0 || it.right != 0) "(L${it.left}R${it.right})" else "" }
        return listOf(
            b("nav", WindowInsets.Type.navigationBars()),
            b("gesture", WindowInsets.Type.systemGestures()),
            b("mandGesture", WindowInsets.Type.mandatorySystemGestures()),
            b("tappable", WindowInsets.Type.tappableElement()),
            b("statusBar", WindowInsets.Type.statusBars()),
            b("ime", WindowInsets.Type.ime()),
            b("cutout", WindowInsets.Type.displayCutout()),
        ).joinToString(" ")
    }

    /**
     * 导航栏的「忽略可见性」取值与当前是否可见（API 30+）。纯手势导航下系统有时把导航栏报成
     * 不可见但保留高度，只看 getInsets 会误判成 0；两个值并排才看得出系统到底怎么给的。
     */
    fun describeNavVisibility(insets: WindowInsets): String {
        if (Build.VERSION.SDK_INT < 30) return "navIgnoringVisibility=? navVisible=?"
        val type = WindowInsets.Type.navigationBars()
        val ignoring = insets.getInsetsIgnoringVisibility(type)
        val cutout = insets.displayCutout?.let { "safe(L${it.safeInsetLeft} T${it.safeInsetTop} R${it.safeInsetRight} B${it.safeInsetBottom})" } ?: "无"
        return "navIgnoringVisibility=${ignoring.bottom}(L${ignoring.left}R${ignoring.right}) navVisible=${insets.isVisible(type)} cutout=$cutout"
    }

    /** 设备/系统侧的静态信息：导航模式、屏幕与窗口尺寸、密度。读不到的写 ?，不抛 */
    fun deviceInfo(context: Context): List<String> {
        val dm = context.resources.displayMetrics
        val cfg = context.resources.configuration
        fun secure(k: String) = runCatching { Settings.Secure.getInt(context.contentResolver, k).toString() }.getOrDefault("?")
        fun global(k: String) = runCatching { Settings.Global.getInt(context.contentResolver, k).toString() }.getOrDefault("?")
        val out = mutableListOf(
            "机型详情 brand=${Build.BRAND} device=${Build.DEVICE} display=${Build.DISPLAY} patch=${Build.VERSION.SECURITY_PATCH}",
            "edge-to-edge 强制=${edgeToEdgeForced(Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion)} " +
                "（SDK ${Build.VERSION.SDK_INT} / targetSdk ${context.applicationInfo.targetSdkVersion}）",
            "屏幕 ${dm.widthPixels}x${dm.heightPixels}px 密度 ${dm.density}（${dm.densityDpi}dpi） 字体缩放 ${cfg.fontScale}",
            "导航模式 navigation_mode=${secure("navigation_mode")}（0 三键 / 1 两键 / 2 手势） " +
                "小米 force_fsg_nav_bar=${global("force_fsg_nav_bar")} hide_gesture_line=${global("hide_gesture_line")}",
        )
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val wm = context.getSystemService(android.view.WindowManager::class.java)
                val m = wm.currentWindowMetrics
                out += "窗口 ${m.bounds.width()}x${m.bounds.height()}px  " + describeInsets(m.windowInsets)
                out += "窗口导航  " + describeNavVisibility(m.windowInsets)
                // 窗口小于最大窗口 = 分屏 / 小窗 / 浮动；只看尺寸，不读别的应用信息
                val max = wm.maximumWindowMetrics.bounds
                out += "最大窗口 ${max.width()}x${max.height()}px 多窗口=${m.bounds.width() != max.width() || m.bounds.height() != max.height()}"
            }
        }
        return out
    }
}
