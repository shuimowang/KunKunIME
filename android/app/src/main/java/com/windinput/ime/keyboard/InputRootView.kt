package com.windinput.ime.keyboard

import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import com.windinput.ime.core.UiTrace
import com.windinput.ime.skin.SkinBackground

/**
 * 输入法根视图：候选条 + 面板区的容器，负责**手势导航条/导航栏避让**。
 *
 * Android 10+ 的手势条与三键导航栏会占据 IME 窗口底部，键盘最后一排会被挤掉。
 * Android 15(API 35) 起 edge-to-edge 强制生效，这条更不能省。
 *
 * ⚠ 用**底部占位 View** 而不是 `setPadding` 避让：IME 窗口高度取自根视图的测量
 * 结果，padding 只会压缩内容区（最后一排被裁），占位 View 才会把 inset 高度
 * 计入测量、让窗口整体长高。
 */
class InputRootView(context: Context) : LinearLayout(context) {

    private val bottomSpacer = View(context)

    /**
     * 当前的避让高度。**不能从 0 起步**：系统的 insets 要等窗口第一次布局之后才派发，
     * 从 0 起步的话键盘刚弹出那一下按无避让的高度测量，最后一排被手势条盖住，
     * insets 到了才长高（肉眼可见地「先被遮住、再恢复」）。所以先用上次收到的真值，
     * 没有就按当前窗口的 WindowMetrics 估一个，insets 到了再校正（通常不变，不会跳）。
     * API 30+ 按 WindowMetrics 估：它跟着当前横竖屏走。
     *
     * 估值在部分系统上不可靠（真机反馈：首次弹出仍被小横条盖住，之后才恢复）。故再按
     * **横竖屏分别**记住上次收到的真值、跨进程持久化（进程被杀后的「首次」也有据可依），
     * 首帧取两者较大的——宁可首帧多垫几个像素，也不能让最后一排被盖住。
     */
    private val portrait = context.resources.configuration.orientation !=
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private val estimated = initialInset(context)
    private var insetBottom = maxOf(estimated, savedInset(context, portrait))

    /**
     * 用户设的额外留空（px）：底部加在避让占位上（窗口跟着长高，最后一排不被裁），左右用内边距
     * （只收窄内容，不影响窗口高度）。设置项 ui.android.keyboard_bottom_gap / keyboard_side_gap。
     */
    fun setGaps(bottomPx: Int, sidePx: Int) {
        extraBottom = bottomPx
        setPadding(sidePx, 0, sidePx, 0)
        applySpacer()
    }

    private var extraBottom = 0

    private fun applySpacer() {
        val h = insetBottom.coerceAtLeast(0) + extraBottom
        if (bottomSpacer.layoutParams.height != h) {
            bottomSpacer.layoutParams = bottomSpacer.layoutParams.apply { height = h }
            requestLayout()
        }
    }

    init {
        orientation = VERTICAL
        // 与键盘同色，否则底部露出异色带。根视图只画这一个背景 Drawable（皮肤背景的收口点，
        // 将来渐变 / 图片换 SkinBackground 的实现即可）；纯色时各子视图仍刷实色，外观不变
        background = SkinBackground.Solid(Palette.current.background).toDrawable()
        fitsSystemWindows = false
        addView(bottomSpacer, LayoutParams(LayoutParams.MATCH_PARENT, insetBottom))
        // 诊断：布局落定后的实测高度。空白是我们垫的（spacer 高）还是系统给的窗口比内容大
        // （根视图高 − 内容高 − spacer 高 > 0），只有实测值分得清。尺寸没变不重复记
        addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top == oldBottom - oldTop && oldBottom != 0) return@addOnLayoutChangeListener
            val loc = IntArray(2).also(v::getLocationOnScreen)
            val spacerH = bottomSpacer.height
            UiTrace.add(
                UiTrace.LAYOUT,
                describeLayout(
                    rootH = bottom - top,
                    spacerH = spacerH,
                    contentH = bottom - top - spacerH,
                    topOnScreen = loc[1],
                    screenH = resources.displayMetrics.heightPixels,
                    extraBottom = extraBottom,
                ),
            )
        }
    }

    /** 占位 View 必须始终在最后一个（子 View 由 Service 依次 addView） */
    override fun addView(child: View, params: android.view.ViewGroup.LayoutParams) {
        if (child === bottomSpacer) {
            super.addView(child, params)
        } else {
            super.addView(child, childCount - 1, params)
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bottom = if (Build.VERSION.SDK_INT >= 30) {
            val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            val caption = insets.getInsets(WindowInsets.Type.captionBar()).bottom
            val gesture = insets.getInsets(WindowInsets.Type.systemGestures()).bottom
            val density = resources.displayMetrics.density
            chooseBottomAvoidance(nav, gesture, density, caption).also {
                UiTrace.add(UiTrace.INSETS, "避让取值 " + describeAvoidance(nav, gesture, density, it, caption))
            }
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }
        lastInsetBottom = bottom
        UiTrace.add(UiTrace.INSETS, "${if (portrait) "竖" else "横"} 取值=$bottom 当前避让=$insetBottom 额外留空=$extraBottom | ${UiTrace.describeInsets(insets)} | ${UiTrace.describeNavVisibility(insets)}")
        // 横屏时挖孔与三键导航栏在左右两侧：窗口铺到底（服务里开了 cutout ALWAYS），
        // 键往里收，背景照样铺满——否则侧边露出一条底下的应用
        if (Build.VERSION.SDK_INT >= 30) {
            val side = insets.getInsets(WindowInsets.Type.displayCutout() or WindowInsets.Type.navigationBars())
            if (paddingLeft != side.left || paddingRight != side.right) setPadding(side.left, 0, side.right, 0)
        }
        removeCallbacks(applyLowered)
        if (bottom < insetBottom && bottom <= 0) {
            // 真机日志（小米 HyperOS）：键盘弹出的头一次派发会给出 0，紧接着又给回真值（56→0→56）。
            // 照单全收的话避让先塌掉、手势条盖住最后一排，还把 0 写进持久化真值，下次首帧更没依据。
            // 变小到 0 的报告先压一会儿，期间来了正常值就当没发生；真为 0（隐藏了导航栏）再落实
            pendingLowered = bottom
            postDelayed(applyLowered, LOWERED_SETTLE_MS)
            return super.onApplyWindowInsets(insets)
        }
        commitInset(bottom)
        return super.onApplyWindowInsets(insets)
    }

    private var pendingLowered = 0
    private val applyLowered = Runnable {
        // 落实前再读一次窗口当前的 insets：瞬时 0 之后若没有新的派发，真值只在这里能拿到
        val now = if (Build.VERSION.SDK_INT >= 30) {
            rootWindowInsets?.let {
                chooseBottomAvoidance(
                    it.getInsets(WindowInsets.Type.navigationBars()).bottom,
                    it.getInsets(WindowInsets.Type.systemGestures()).bottom,
                    resources.displayMetrics.density,
                    it.getInsets(WindowInsets.Type.captionBar()).bottom,
                )
            } ?: 0
        } else {
            0
        }
        commitInset(maxOf(pendingLowered, now))
    }

    /** 落实一个避让高度：持久化真值、更新占位 */
    private fun commitInset(bottom: Int) {
        if (bottom != savedInset(context, portrait)) saveInset(context, portrait, bottom)
        if (bottom != insetBottom) {
            UiTrace.add(UiTrace.COMMIT, "避让 $insetBottom → $bottom（首帧估值 $estimated）")
            android.util.Log.i("InputRootView", "导航避让首帧 $insetBottom（估值 $estimated）→ 实际 $bottom")
            insetBottom = bottom
            applySpacer()
        }
    }

    companion object {
        /** 避让高度突然变成 0 时等多久再信：头一次派发的瞬时 0 通常几帧内就被真值覆盖 */
        private const val LOWERED_SETTLE_MS = 400L

        /** 纯手势导航下的避让上限（dp）：盖住手势条即可，荣耀识别区 40dp 垫满就是空白反馈 */
        private const val GESTURE_BAR_CAP_DP = 16

        /** 进程内记住上次的真值：API 26–29 没有 WindowMetrics，重建输入视图时用它 */
        private var lastInsetBottom = -1

        /**
         * 底部避让取值（纯函数，单测见 InputRootViewInsetsTest）。
         *
         * 三键/两键导航是实色栏，按导航栏高度避让（nav>0）。
         * 纯手势导航（nav=0）不能垫整个 systemGestures 识别区：荣耀 MagicOS 报 140px
         * （40dp@560dpi），垫下去就是「键盘下方空白」的用户反馈；小米报 56px（约 13dp），
         * 空白不明显所以之前没暴露。手势条本身浮在键盘上，远低于识别区高度，
         * 垫到手势条典型高度 16dp 为止。（tappableElement 荣耀报 0，不作依据。）
         *
         * IME 的系统返回/输入法切换按钮另以 captionBar 发布，可能高于 navigationBars。
         * 这是真实按钮区域，不能按手势条钳制。
         * 两种区域都从底边起算，取较大值，不累加；getInsets 只取当前可见区域。
         */
        internal fun chooseBottomAvoidance(nav: Int, gesture: Int, density: Float, caption: Int = 0): Int =
            maxOf(caption, if (nav > 0) nav else minOf(gesture, (GESTURE_BAR_CAP_DP * density).toInt()))

        /**
         * 一次避让取值是怎么算出来的（纯函数，单测见 InputRootViewDiagnosticsTest）：走了 nav 分支
         * 还是手势分支、16dp 钳制有没有生效。用户导出诊断包时，据此就能看出荣耀那类机型的空白是
         * 钳制之后仍留下的，还是系统给的窗口本身更高。
         */
        internal fun describeAvoidance(nav: Int, gesture: Int, density: Float, result: Int, caption: Int = 0): String {
            val cap = (GESTURE_BAR_CAP_DP * density).toInt()
            return if (caption > 0) {
                "nav=$nav caption=$caption gesture=$gesture → 分支=IME导航区(与原避让取较大值) 结果=$result"
            } else if (nav > 0) {
                "nav=$nav gesture=$gesture → 分支=导航栏(取nav) 结果=$result"
            } else {
                "nav=0 gesture=$gesture cap=${cap}px(${GESTURE_BAR_CAP_DP}dp) → 分支=纯手势 " +
                    "钳制=${if (gesture > cap) "生效" else "未触发"} 结果=$result"
            }
        }

        /** 布局落定后的实测值；[contentH] 之外多出来的高度就是 spacer（避让 + 用户留空） */
        internal fun describeLayout(rootH: Int, spacerH: Int, contentH: Int, topOnScreen: Int, screenH: Int, extraBottom: Int): String =
            "根视图高=$rootH 内容高=$contentH 底部占位=$spacerH(含用户留空$extraBottom) " +
                "顶边屏幕y=$topOnScreen 底边屏幕y=${topOnScreen + rootH} 屏幕高=$screenH 底边距屏幕底=${screenH - topOnScreen - rootH}"

        private fun prefs(context: Context) =
            context.getSharedPreferences("ime_insets", Context.MODE_PRIVATE)

        private fun savedInset(context: Context, portrait: Boolean): Int =
            prefs(context).getInt(if (portrait) "bottom_portrait" else "bottom_landscape", 0)

        private fun saveInset(context: Context, portrait: Boolean, value: Int) {
            prefs(context).edit().putInt(if (portrait) "bottom_portrait" else "bottom_landscape", value).apply()
        }

        private fun initialInset(context: Context): Int =
            if (Build.VERSION.SDK_INT >= 30) {
                estimateBottomInset(context)
            } else {
                lastInsetBottom.takeIf { it >= 0 } ?: estimateBottomInset(context)
            }

        private fun estimateBottomInset(context: Context): Int {
            if (Build.VERSION.SDK_INT >= 30) {
                // 估不出来就退回 0（旧行为）：只是首帧不准，insets 到了照样校正，不值得因此崩
                return runCatching {
                    val wm = context.getSystemService(android.view.WindowManager::class.java)
                    val insets = wm.currentWindowMetrics.windowInsets
                    chooseBottomAvoidance(
                        insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                        insets.getInsets(WindowInsets.Type.systemGestures()).bottom,
                        context.resources.displayMetrics.density,
                        insets.getInsets(WindowInsets.Type.captionBar()).bottom,
                    )
                }.getOrDefault(0)
            }
            // 26–29：没有 WindowMetrics，取系统资源里的导航栏高度
            val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            return if (id != 0) context.resources.getDimensionPixelSize(id) else 0
        }
    }
}
