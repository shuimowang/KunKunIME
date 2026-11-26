package com.windinput.ime.skin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinParserTest {

    private val d = SkinRuntime.DEFAULT

    private fun parse(text: String) = SkinParser.parse(text)

    @Test
    fun emptyFileIsDefaultSkin() {
        val r = parse("")
        assertSame(d, r.skin)
    }

    @Test
    fun brokenTomlFallsBackToDefaultWithWarning() {
        val r = parse("version = = =\n[colors")
        assertSame(d, r.skin)
        assertTrue(r.warnings.isNotEmpty())
    }

    @Test
    fun newerVersionIsIgnoredWholesale() {
        val r = parse("version = 99\n[colors]\ntext = \"#FF0000\"\n")
        assertSame(d, r.skin)
        assertTrue(r.warnings.any { "版本" in it })
        assertTrue(r.tooNew)
    }

    @Test
    fun missingVersionIsTreatedAsOne() {
        val r = parse("[colors]\naccent = \"#112233\"\n")
        assertEquals(0xFF112233.toInt(), r.skin.light.accent)
    }

    @Test
    fun rrggbbIsOpaque() {
        val r = parse("version = 1\n[colors]\naccent = \"#112233\"\n")
        assertEquals(0xFF112233.toInt(), r.skin.light.accent)
        assertEquals(0xFF112233.toInt(), r.skin.dark.accent)
    }

    /** AA 在末尾：#RRGGBBAA → Android ARGB。0x1A2F6FEB 是现有 accentSoft 的 ARGB 写法 */
    @Test
    fun rrggbbaaHasAlphaLast() {
        val r = parse("[colors]\naccentSoft = \"#2F6FEB1A\"\n")
        assertEquals(0x1A2F6FEB, r.skin.light.accentSoft)
    }

    @Test
    fun lightDarkTableSetsEachMode() {
        val r = parse("[colors]\ntext = { light = \"#010203\", dark = \"#040506\" }\n")
        assertEquals(0xFF010203.toInt(), r.skin.light.text)
        assertEquals(0xFF040506.toInt(), r.skin.dark.text)
    }

    @Test
    fun lightDarkTableWithOneSideKeepsDefaultForTheOther() {
        val r = parse("[colors]\ntext = { light = \"#010203\" }\n")
        assertEquals(0xFF010203.toInt(), r.skin.light.text)
        assertEquals(d.dark.text, r.skin.dark.text)
    }

    @Test
    fun referenceResolvesPerMode() {
        val r = parse(
            "[colors]\naccent = { light = \"#0000FF\", dark = \"#00FF00\" }\nonAccent = \"\${accent}\"\n",
        )
        assertEquals(0xFF0000FF.toInt(), r.skin.light.onAccent)
        assertEquals(0xFF00FF00.toInt(), r.skin.dark.onAccent)
    }

    @Test
    fun referenceToUnsetSlotTakesItsDefault() {
        val r = parse("[colors]\nicon = \"\${hint}\"\n")
        assertEquals(d.light.hint, r.skin.light.icon)
    }

    @Test
    fun referenceCycleDoesNotCrashAndFallsBackToDefaults() {
        val r = parse("[colors]\nkey = \"\${keySpecial}\"\nkeySpecial = \"\${key}\"\n")
        assertEquals(d.light.key, r.skin.light.key)
        assertEquals(d.light.keySpecial, r.skin.light.keySpecial)
        assertTrue(r.warnings.any { "环" in it })
    }

    @Test
    fun selfReferenceIsACycleToo() {
        val r = parse("[colors]\ntext = \"\${text}\"\n")
        assertEquals(d.light.text, r.skin.light.text)
        assertTrue(r.warnings.any { "环" in it })
    }

    @Test
    fun referenceToUnknownSlotFallsBack() {
        val r = parse("[colors]\ntext = \"\${nope}\"\n")
        assertEquals(d.light.text, r.skin.light.text)
        assertTrue(r.warnings.isNotEmpty())
    }

    @Test
    fun badColorStringFallsBackAndOthersStillApply() {
        val r = parse("[colors]\ntext = \"red\"\naccent = \"#ABCDEF\"\n")
        assertEquals(d.light.text, r.skin.light.text)
        assertEquals(0xFFABCDEF.toInt(), r.skin.light.accent)
        assertTrue(r.warnings.isNotEmpty())
    }

    @Test
    fun unknownSlotsAndSectionsAreIgnored() {
        val r = parse("future = 1\n[colors]\nkeyText = \"#123456\"\n[mystery]\nx = 2\n")
        assertEquals(d.light.text, r.skin.light.text)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun wrongTypedValuesDoNotCrash() {
        val r = parse("version = \"one\"\n[colors]\ntext = 5\naccent = [1, 2]\n[geometry]\nkey_radius = \"big\"\n")
        assertEquals(d.light.text, r.skin.light.text)
        assertEquals(d.geometry.keyRadiusDp, r.skin.geometry.keyRadiusDp, 0f)
    }

    @Test
    fun geometryIsClampedNotRejected() {
        val r = parse("[geometry]\nkey_height = 10\nkey_radius = 500\nkey_gap_h = 0.5\n")
        assertEquals("键帽高钳到 40", 40f, r.skin.geometry.keyHeightDp, 0f)
        assertEquals("圆角钳到键高一半", 20f, r.skin.geometry.keyRadiusDp, 0f)
        assertEquals("键间隙钳到 2（对外值），内部存一半", 1f, r.skin.geometry.keyGapHalfDp, 1e-6f)
        assertTrue(r.warnings.isNotEmpty())
    }

    @Test
    fun geometryDefaultsKeepUnsetValues() {
        val r = parse("[geometry]\nkey_radius = 12\n")
        assertEquals(12f, r.skin.geometry.keyRadiusDp, 0f)
        assertEquals(d.geometry.keyHeightDp, r.skin.geometry.keyHeightDp, 0f)
    }
}
