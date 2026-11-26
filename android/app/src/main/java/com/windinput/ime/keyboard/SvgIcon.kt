package com.windinput.ime.keyboard

/**
 * 线性图标的 SVG 子集模型与解析（纯 Kotlin，不碰 Android，便于单测）。
 *
 * 只认图标集（Lucide 一类）实际用到的写法：根元素的 `viewBox` / `stroke-width`，子元素
 * `path / circle / ellipse / line / polyline / polygon / rect`（含 `rx`/`ry` 圆角）。
 * 颜色、填充一律不读——图标色由键盘主题给，描边/填充由 [IconSet] 的调用方决定。
 * 解析不了的元素直接跳过，坏文件不会让键盘崩。
 */
class SvgIcon(
    /** viewBox 边长（图标集都是正方形；取宽高较大者） */
    val viewBox: Float,
    /** 根元素声明的线宽（viewBox 单位） */
    val strokeWidth: Float,
    val shapes: List<SvgShape>,
)

sealed interface SvgShape {
    class PathData(val d: String) : SvgShape
    class Circle(val cx: Float, val cy: Float, val r: Float) : SvgShape
    class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float) : SvgShape
    class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : SvgShape
    class Poly(val points: List<Float>, val closed: Boolean) : SvgShape
    class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val rx: Float, val ry: Float) : SvgShape
}

object SvgIconParser {
    private val ATTR = Regex("""([A-Za-z_:][\w:.-]*)\s*=\s*"([^"]*)"""")
    private val TAG = Regex("""<\s*([A-Za-z]+)\b([^>]*?)/?>""")
    private val NUM = Regex("""-?\d*\.?\d+(?:[eE][-+]?\d+)?""")

    /** 解析失败（没有 `<svg>` 根、没有任何可画元素）返回 null */
    fun parse(text: String): SvgIcon? {
        var viewBox = 24f
        var strokeWidth = 2f
        var sawRoot = false
        val shapes = ArrayList<SvgShape>()
        for (m in TAG.findAll(text)) {
            val attrs = ATTR.findAll(m.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
            fun f(key: String, def: Float = 0f) = attrs[key]?.trim()?.toFloatOrNull() ?: def
            when (m.groupValues[1].lowercase()) {
                "svg" -> {
                    sawRoot = true
                    attrs["viewBox"]?.let { vb ->
                        val n = NUM.findAll(vb).mapNotNull { it.value.toFloatOrNull() }.toList()
                        if (n.size == 4 && n[2] > 0f && n[3] > 0f) viewBox = maxOf(n[2], n[3])
                    }
                    strokeWidth = f("stroke-width", 2f)
                }
                "path" -> attrs["d"]?.takeIf { it.isNotBlank() }?.let { shapes += SvgShape.PathData(it) }
                "circle" -> if (f("r") > 0f) shapes += SvgShape.Circle(f("cx"), f("cy"), f("r"))
                "ellipse" -> if (f("rx") > 0f && f("ry") > 0f) {
                    shapes += SvgShape.Ellipse(f("cx"), f("cy"), f("rx"), f("ry"))
                }
                "line" -> shapes += SvgShape.Line(f("x1"), f("y1"), f("x2"), f("y2"))
                "polyline", "polygon" -> {
                    val pts = attrs["points"]?.let { p -> NUM.findAll(p).mapNotNull { it.value.toFloatOrNull() }.toList() }
                    if (pts != null && pts.size >= 4) {
                        shapes += SvgShape.Poly(pts, closed = m.groupValues[1].equals("polygon", ignoreCase = true))
                    }
                }
                "rect" -> if (f("width") > 0f && f("height") > 0f) {
                    // SVG 规则：只写了 rx 或 ry 之一时，另一个取同值
                    val rxA = attrs["rx"]?.toFloatOrNull()
                    val ryA = attrs["ry"]?.toFloatOrNull()
                    shapes += SvgShape.Rect(
                        f("x"), f("y"), f("width"), f("height"),
                        rxA ?: ryA ?: 0f, ryA ?: rxA ?: 0f,
                    )
                }
            }
        }
        if (!sawRoot || shapes.isEmpty()) return null
        return SvgIcon(viewBox, strokeWidth, shapes)
    }
}
