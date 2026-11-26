package com.windinput.ime.skin

import com.windinput.ime.keyboard.KeyboardDimens
import com.windinput.ime.keyboard.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 黄金对照：默认皮肤必须与重构前的 `Palette.light / dark` 与 `KeyboardDimens` 常量**逐项相等**。
 * 期望值是重构前（2026-11-24）的字面量，不是从 [SkinRuntime] 反推——否则改坏了两边一起坏、测试照绿。
 */
class SkinRuntimeGoldenTest {

    private fun slots(p: Palette): List<Pair<String, Int>> = listOf(
        "background" to p.background, "surface" to p.surface, "key" to p.key,
        "keySpecial" to p.keySpecial, "keyPressed" to p.keyPressed, "text" to p.text,
        "hint" to p.hint, "accent" to p.accent, "onAccent" to p.onAccent,
        "accentSoft" to p.accentSoft, "accentText" to p.accentText, "preedit" to p.preedit,
        "separator" to p.separator, "icon" to p.icon, "border" to p.border,
    )

    private val lightGolden = listOf(
        "background" to 0xFFE2E3E7.toInt(), "surface" to 0xFFF1F2F5.toInt(), "key" to 0xFFFFFFFF.toInt(),
        "keySpecial" to 0xFFC1C5CE.toInt(), "keyPressed" to 0xFFBAC2CF.toInt(), "text" to 0xFF1A1D24.toInt(),
        "hint" to 0xFF8A91A0.toInt(), "accent" to 0xFF2F6FEB.toInt(), "onAccent" to 0xFFFFFFFF.toInt(),
        "accentSoft" to 0x1A2F6FEB, "accentText" to 0xFF2563EB.toInt(), "preedit" to 0xFF5A6072.toInt(),
        "separator" to 0xFFCBD0D9.toInt(), "icon" to 0xFF4A5163.toInt(), "border" to 0xFFE2E5EB.toInt(),
    )

    private val darkGolden = listOf(
        "background" to 0xFF12151C.toInt(), "surface" to 0xFF1B1F2A.toInt(), "key" to 0xFF2E3442.toInt(),
        "keySpecial" to 0xFF232833.toInt(), "keyPressed" to 0xFF444C5E.toInt(), "text" to 0xFFF2F3F7.toInt(),
        "hint" to 0xFF8F97A8.toInt(), "accent" to 0xFF3A74E2.toInt(), "onAccent" to 0xFFFFFFFF.toInt(),
        "accentSoft" to 0x3360A5FA, "accentText" to 0xFF7AB0FF.toInt(), "preedit" to 0xFF9AA3B4.toInt(),
        "separator" to 0xFF2A3040.toInt(), "icon" to 0xFFB4BBC9.toInt(), "border" to 0xFF3A4152.toInt(),
    )

    @Test
    fun defaultLightColorsMatchPreRefactorPalette() {
        assertEquals(lightGolden, slots(SkinRuntime.DEFAULT.light))
    }

    @Test
    fun defaultDarkColorsMatchPreRefactorPalette() {
        assertEquals(darkGolden, slots(SkinRuntime.DEFAULT.dark))
    }

    /** Service 用 `Palette.resolve(...) !== Palette.current` 判断要不要重建视图，必须是同一个实例 */
    @Test
    fun paletteLightAndDarkAreTheSkinInstances() {
        assertSame(SkinRuntime.DEFAULT.light, Palette.light)
        assertSame(SkinRuntime.DEFAULT.dark, Palette.dark)
    }

    @Test
    fun defaultGeometryMatchesKeyboardDimensConstants() {
        val g = SkinRuntime.DEFAULT.geometry
        assertEquals(KeyboardDimens.KEY_GAP_DP, g.keyGapHalfDp, 0f)
        assertEquals(KeyboardDimens.EDGE_PAD_DP, g.edgePadDp, 0f)
        assertEquals(KeyboardDimens.KEY_RADIUS_DP, g.keyRadiusDp, 0f)
        assertEquals(KeyboardDimens.KEY_HEIGHT_DP, g.keyHeightDp, 0f)
        assertEquals(KeyboardDimens.PAD_TOP_DP, g.padTopDp, 0f)
        assertEquals(KeyboardDimens.SWIPE_ACTIVATE_DP, g.swipeActivateDp, 0f)
        assertEquals(KeyboardDimens.SWIPE_THRESHOLD_DP, g.swipeThresholdDp, 0f)
        assertEquals(KeyboardDimens.CURSOR_STEP_DP, g.cursorStepDp, 0f)
        // 行高 60、条区 19 / 38 是 KeyboardDimens 的可变初值（非 const），按其初值对照
        assertEquals(KeyboardDimens.rowHeightDp.toFloat(), g.defaultRowHeightDp, 0f)
        assertEquals(KeyboardDimens.barPreeditDp, g.barPreeditDp, 0f)
        assertEquals(KeyboardDimens.barMainRowDp, g.barMainRowDp, 0f)
    }

    /** 文档 §3.3：key_gap_h 对外写整数 5.4，内部存一半；row_gap = 行高 − 键帽高 = 9 */
    @Test
    fun derivedGeometryValuesMatchDocumentedDefaults() {
        val g = SkinRuntime.DEFAULT.geometry
        assertEquals(5.4f, g.keyGapHDp, 1e-6f)
        assertEquals(9f, g.rowGapDp, 1e-6f)
    }
}
