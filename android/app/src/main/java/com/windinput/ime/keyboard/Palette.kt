package com.windinput.ime.keyboard

import android.content.Context
import android.content.res.Configuration
import com.windinput.ime.skin.SkinRuntime

/**
 * 键盘域调色板：所有自绘视图与面板的颜色**只从这里取**。
 *
 * 这是主题系统（docs/architecture.md §3.4「主题键盘域节点 + 共享调色板」）的落点：
 * 数据来自 [SkinRuntime]（皮肤的不可变运行时形态，见 docs/mobile-skin-design.md）：本类是它的
 * 颜色视图，视图层一行不用改。移动端皮肤在 Kotlin 侧求值，不复用 PC 的 wind-theme。槽位按**语义**命名而不是按颜色——`accentSoft` 在深色里并不"浅"。
 *
 * ⚠ 视图在构造时取色、不监听变化。这是有意的：IME 的输入视图在系统配置变化
 * （深浅色切换、旋转）时**整个重建**（`onCreateInputView` 重跑），所以每次构造
 * 取到的就是当时该用的那套；给每个 View 挂监听反而多一条不会被走到的路。
 */
class Palette(
    /** 键盘底色（键与键之间的缝） */
    val background: Int,
    /** 列表行 / 面板内容区的面（条区与面板顶栏用 [background]，与键盘同色） */
    val surface: Int,
    /** 普通键面 */
    val key: Int,
    /** 功能键面（Shift / 退格 / 切换等） */
    val keySpecial: Int,
    /** 按下高亮 */
    val keyPressed: Int,
    /** 主文字 */
    val text: Int,
    /** 次要文字：副字符提示、页码、空态说明 */
    val hint: Int,
    /** 强调色：模式标签底、选中的 chip、顶栏动作文字 */
    val accent: Int,
    /** 强调色上的文字 */
    val onAccent: Int,
    /** 强调色的淡底：选中项背景、候选按下 */
    val accentSoft: Int,
    /** 强调色的文字形态：气泡选中项、编辑面板的选择模式 */
    val accentText: Int,
    /** 编码栏里的编码文字 */
    val preedit: Int,
    /** 候选分隔线 */
    val separator: Int,
    /** 工具栏图标 */
    val icon: Int,
    /** 气泡描边 */
    val border: Int,
) {
    companion object {
        /**
         * 内置浅 / 深两套的**取值**（数值在 [SkinRuntime.DEFAULT]，黄金测试钉死与重构前逐项相等）。
         * 是函数式取值而不是存起来的字段：见 SkinRuntime.DEFAULT 注释里的类初始化环。
         * 两次取到的是同一个实例——Service 靠 `!==` 判断配色有没有变。
         */
        val light: Palette get() = SkinRuntime.DEFAULT.light
        val dark: Palette get() = SkinRuntime.DEFAULT.dark

        /**
         * 当前生效的调色板。由 Service 在 `onCreateInputView` 里按系统深浅色设定，
         * 视图层只读。与 [KeyboardDimens] 同一种"Service 注入、视图取用"的模式。
         */
        @Volatile
        private var applied: Palette? = null

        val current: Palette get() = applied ?: light

        /**
         * 选一套设为当前。[mode] 来自设置项 ui.android.theme：`light` / `dark` / 其它 = 跟随系统。
         */
        fun apply(context: Context, mode: String): Palette {
            return resolve(context, mode).also { applied = it }
        }

        /**
         * 按设置与系统深浅色算出该用哪套，不改 [current]（Service 用它核对当前视图配色对不对）。
         *
         * 「跟随系统」读**系统全局**配置（`Resources.getSystem()`），不读 Service 自己的：
         * 刚装好、进程刚起来时 IME 服务拿到的配置可能是旧的——真机上出现过系统浅色、
         * 键盘却是深色，切一次输入法（服务重建）才对。
         */
        fun resolve(context: Context, mode: String): Palette = when (mode) {
            "light" -> SkinRuntime.active.light
            "dark" -> SkinRuntime.active.dark
            else -> {
                val uiMode = android.content.res.Resources.getSystem().configuration.uiMode
                    .takeIf { it and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_UNDEFINED }
                    ?: context.resources.configuration.uiMode
                val night = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                SkinRuntime.active.palette(night)
            }
        }
    }
}
