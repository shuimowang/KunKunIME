package com.windinput.ime.keyboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgIconParserTest {
    private val smile = """
        <!-- @license lucide-static v0.468.0 - ISC -->
        <svg class="lucide lucide-smile" xmlns="http://www.w3.org/2000/svg" width="24" height="24"
          viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="10" />
          <path d="M8 14s1.5 2 4 2 4-2 4-2" />
          <line x1="9" x2="9.01" y1="9" y2="9" />
        </svg>
    """.trimIndent()

    @Test
    fun `parses viewBox, stroke width and shapes in order`() {
        val icon = SvgIconParser.parse(smile)!!
        assertEquals(24f, icon.viewBox, 0f)
        assertEquals(2f, icon.strokeWidth, 0f)
        assertEquals(3, icon.shapes.size)
        val c = icon.shapes[0] as SvgShape.Circle
        assertEquals(10f, c.r, 0f)
        assertEquals("M8 14s1.5 2 4 2 4-2 4-2", (icon.shapes[1] as SvgShape.PathData).d)
        assertEquals(9.01f, (icon.shapes[2] as SvgShape.Line).x2, 0f)
    }

    @Test
    fun `rect radius falls back to the other axis`() {
        val icon = SvgIconParser.parse(
            """<svg viewBox="0 0 24 24"><rect width="7" height="7" x="3" y="3" rx="1" /></svg>""",
        )!!
        val r = icon.shapes.single() as SvgShape.Rect
        assertEquals(1f, r.rx, 0f)
        assertEquals("只写 rx 时 ry 取同值（SVG 规则）", 1f, r.ry, 0f)
    }

    @Test
    fun `polygon closes and polyline stays open`() {
        val icon = SvgIconParser.parse(
            """<svg viewBox="0 0 24 24"><polyline points="22 12 18 12 15 21"/><polygon points="1 1 5 1 5 5"/></svg>""",
        )!!
        assertEquals(false, (icon.shapes[0] as SvgShape.Poly).closed)
        assertEquals(true, (icon.shapes[1] as SvgShape.Poly).closed)
        assertEquals(6, (icon.shapes[0] as SvgShape.Poly).points.size)
    }

    @Test
    fun `non square viewBox uses the larger side and custom stroke width is kept`() {
        val icon = SvgIconParser.parse(
            """<svg viewBox="0 0 16 20" stroke-width="1.5"><path d="M0 0L16 20"/></svg>""",
        )!!
        assertEquals(20f, icon.viewBox, 0f)
        assertEquals(1.5f, icon.strokeWidth, 0f)
    }

    @Test
    fun `garbage and shapeless files are rejected instead of crashing`() {
        assertNull(SvgIconParser.parse(""))
        assertNull(SvgIconParser.parse("not svg at all"))
        assertNull("没有 <svg> 根", SvgIconParser.parse("""<path d="M0 0L1 1"/>"""))
        assertNull("没有任何可画元素", SvgIconParser.parse("""<svg viewBox="0 0 24 24"></svg>"""))
        assertNull("r=0 的圆不算", SvgIconParser.parse("""<svg><circle cx="1" cy="1" r="0"/></svg>"""))
    }

    /** 随包的每一个图标都得解析得出来，且每个 Glyph 都有对应文件——少一个就是键盘上少一个图标 */
    @Test
    fun `every glyph has a bundled parsable icon`() {
        val dir = listOf("src/main/assets/data/icons", "app/src/main/assets/data/icons")
            .map(::File).first { it.isDirectory }
        for (g in PanelIcon.Glyph.values()) {
            val f = File(dir, "${g.iconName}.svg")
            assertTrue("缺图标文件 ${f.name}", f.isFile)
            val icon = SvgIconParser.parse(f.readText())
            assertNotNull("${f.name} 解析不了", icon)
            assertEquals("${f.name} 须是 24 网格", 24f, icon!!.viewBox, 0f)
        }
    }
}
