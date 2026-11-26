package com.windinput.ime.skin

import com.windinput.ime.keyboard.Palette

/**
 * 皮肤的运行时形态：**不可变**，视图只读它（docs/mobile-skin-design.md §3.1）。
 *
 * 颜色已经全部解成 ARGB `Int`、几何已经全部解成 dp；解析（[SkinParser]）与求值都发生在构造之前，
 * 视图层不碰 TOML、不碰文件。深浅色各一套 [Palette]，视图仍通过 [Palette.current] 取色，
 * 所以接入皮肤不需要改调用点。
 *
 * 阶段 0 只有一个来源：[DEFAULT]，它逐项复刻重构前的 `Palette.light / dark` 与 `KeyboardDimens` 常量
 * （黄金测试 SkinRuntimeGoldenTest 钉死）。几何值此阶段还没有被视图读取——视图继续读
 * `KeyboardDimens`，参数化在阶段 2。
 */
class SkinRuntime(
    val light: Palette,
    val dark: Palette,
    val geometry: SkinGeometry,
) {
    fun palette(night: Boolean): Palette = if (night) dark else light

    companion object {
        /**
         * 蓝色只出现在三个角色里（同一色相）：实心强调色 accent、半透明淡底 accentSoft、强调字 accentText；
         * 按下态、编码字、分隔线等一律中性灰。沿用困困的蓝色强调，手机端实心色略深（白字对比 4.57）、
         * 淡底用透明度叠。条区与键盘区共用 background：两块异色会在交界处拉出一道硬边。
         * 底色取中性浅灰 E2E3E7：白键压在上面要有清楚的对比。
         *
         * 深色不是把浅色反相：键面要比底色**亮**一级才有「键」的立体感，强调色提亮一档保证在深底上可读。
         *
         * ⚠ 不能在 Palette 的 companion 里直接引用本对象的字段做初始化（类初始化会互相等待成环），
         * 所以 [Palette.light] / [Palette.dark] 是取值函数，不是存起来的字段。
         */
        val DEFAULT: SkinRuntime = SkinRuntime(
            light = Palette(
                background = 0xFFE2E3E7.toInt(),
                surface = 0xFFF1F2F5.toInt(),
                key = 0xFFFFFFFF.toInt(),
                keySpecial = 0xFFC1C5CE.toInt(),
                keyPressed = 0xFFBAC2CF.toInt(),
                text = 0xFF1A1D24.toInt(),
                hint = 0xFF8A91A0.toInt(),
                accent = 0xFF2F6FEB.toInt(),
                onAccent = 0xFFFFFFFF.toInt(),
                accentSoft = 0x1A2F6FEB,
                accentText = 0xFF2563EB.toInt(),
                preedit = 0xFF5A6072.toInt(),
                separator = 0xFFCBD0D9.toInt(),
                icon = 0xFF4A5163.toInt(),
                border = 0xFFE2E5EB.toInt(),
            ),
            dark = Palette(
                background = 0xFF12151C.toInt(),
                surface = 0xFF1B1F2A.toInt(),
                key = 0xFF2E3442.toInt(),
                keySpecial = 0xFF232833.toInt(),
                keyPressed = 0xFF444C5E.toInt(),
                text = 0xFFF2F3F7.toInt(),
                hint = 0xFF8F97A8.toInt(),
                accent = 0xFF3A74E2.toInt(),
                onAccent = 0xFFFFFFFF.toInt(),
                accentSoft = 0x3360A5FA,
                accentText = 0xFF7AB0FF.toInt(),
                preedit = 0xFF9AA3B4.toInt(),
                separator = 0xFF2A3040.toInt(),
                icon = 0xFFB4BBC9.toInt(),
                border = 0xFF3A4152.toInt(),
            ),
            geometry = SkinGeometry(),
        )

        /** 当前生效的皮肤；阶段 1 起由皮肤选择写入，现在恒为 [DEFAULT] */
        @Volatile
        var active: SkinRuntime = DEFAULT
    }
}

/**
 * 几何，全部 dp。缺省值 = 今天的常量，所以不写 `[geometry]` 的皮肤外观与现在逐像素一致。
 *
 * 键间隙内部存「一半」（与 `KeyboardDimens.KEY_GAP_DP` 同口径），对外（skin.toml 的 `key_gap_h`）写整数，
 * 加载时除以 2。行高不属于皮肤（是用户按手型调的偏好，留在 `ui.android.*`）；[rowGapDp] + [keyHeightDp]
 * 只在用户没动过行高时作缺省，见 [defaultRowHeightDp]。
 */
data class SkinGeometry(
    val keyGapHalfDp: Float = 2.7f,
    val edgePadDp: Float = 3.4f,
    val keyRadiusDp: Float = 8f,
    val keyHeightDp: Float = 51f,
    /** 上下两行之间的间隙；取代「行高 − 键帽高」的隐式推导 */
    val rowGapDp: Float = 9f,
    val padTopDp: Float = 0f,
    val swipeActivateDp: Float = 16f,
    val swipeThresholdDp: Float = 40f,
    val cursorStepDp: Float = 12f,
    val barPreeditDp: Float = 19f,
    val barMainRowDp: Float = 38f,
) {
    /** 两键横向间隙（整数口径，= skin.toml 的 key_gap_h） */
    val keyGapHDp: Float get() = keyGapHalfDp * 2

    /** 皮肤给出的缺省行高 = 键帽高 + 行间距（60 = 51 + 9） */
    val defaultRowHeightDp: Float get() = keyHeightDp + rowGapDp
}
