package com.windinput.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * 线性矢量图标（Canvas Path 绘制）。
 *
 * 取代先前的 emoji 文本图标：emoji 由系统字体渲染，**各机型长相不一、颜色写死、
 * 与键盘配色格格不入**，也无法跟随主题变色。Path 绘制的图标粗细统一、可着色，
 * 后续接 wind-theme 调色板时只需换 [tint]。
 *
 * 图标在 24×24 的名义网格里描点，绘制时按实际尺寸等比缩放。
 */
class PanelIcon(context: Context) : View(context) {

    enum class Glyph {
        /** 返回（左箭头） */
        BACK,

        /** 快捷功能（田字格） */
        GRID,

        /** 表情（笑脸） */
        EMOJI,

        /** 剪贴板 */
        CLIPBOARD,

        /** 文本编辑（光标 I 形 + 方向） */
        TEXT_EDIT,

        /** 设置（齿轮简化：圆 + 六向短齿） */
        SETTINGS,

        /** 收起键盘（下箭头） */
        COLLAPSE,

        /** 展开候选（下三角） */
        EXPAND,

        /** 关闭（叉） */
        CLOSE,

        /** 无痕（礼帽 + 眼镜） */
        INCOGNITO,

        /** 语音（话筒） */
        MIC,

        /** 切换输入法（地球） */
        GLOBE,

        /** 单手（手机 + 侧向箭头） */
        ONE_HAND,

        /** 键盘（键盘轮廓）：键盘选择面板的入口 */
        KEYBOARD,

        /** 置顶（实心图钉） */
        PIN,

        /** 未置顶（空心图钉） */
        PIN_OUTLINE,

        /** 最近（钟） */
        CLOCK,

        /** 方向键 */
        ARROW_UP,
        ARROW_DOWN,
        ARROW_LEFT,
        ARROW_RIGHT,

        /** 锁定（挂锁，锁梁合上） */
        LOCK,

        /** 未锁定（挂锁，锁梁打开） */
        UNLOCK,

        /** 快捷输入（闪电）：计算、日期、金额大写、重复上屏都是「一键出结果」 */
        QUICK_INPUT,
        ;

        /**
         * 语义名，也是图标文件名（`text_edit` → `icons/text_edit.svg`）。
         * 调用方只认枚举，具体长什么样由 [IconSet] 决定，所以换图标集或主题自定义都不动调用方。
         */
        val iconName: String get() = name.lowercase()

        /** 描边之外再填充（「已置顶」的实心图钉）。其余图标一律只描边 */
        val filled: Boolean get() = this == PIN
    }

    /**
     * 只作装饰（放在可点的格子里，点击归外层）：不拦触摸、不单独报读屏节点——
     * 外层格子自己有文字标签，图标再念一遍是重复。
     */
    var decorative: Boolean = false
        set(value) {
            field = value
            isClickable = !value
            importantForAccessibility =
                if (value) IMPORTANT_FOR_ACCESSIBILITY_NO else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }

    var glyph: Glyph = Glyph.GRID
        set(value) {
            field = value
            // 画出来的图标对读屏是空白：按图标给一个说法（调用方另设过 contentDescription 的不覆盖）
            if (contentDescription == null || contentDescription in A11Y_LABELS.values) {
                contentDescription = A11Y_LABELS[value]
            }
            invalidate()
        }

    init {
        // 默认图标走的是属性初值、不经 setter，这里补一次
        contentDescription = A11Y_LABELS[glyph]
    }

    var tint: Int = Palette.current.icon
        set(value) {
            field = value
            invalidate()
        }

    /** 图标边长占控件短边的比例；留白让触摸区大于视觉尺寸 */
    var scale: Float = 0.5f

    private val density = resources.displayMetrics.density


    private var pressed = false

    init {
        isClickable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (decorative) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                KeyHaptics.tap(this)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressed = false
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }

    private val painter = GlyphPainter()

    /**
     * 图标框的绝对边长（dp）；0 = 按控件短边乘 [scale]。空闲工具栏用它：各图标同一个框、
     * 同一线宽（线宽随框等比缩放），不随格子大小变。
     */
    var boxDp: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** 圆形底托的直径（dp），0 = 不画。设置项 ui.android.toolbar_icon_chip 打开时工具栏图标用，同微信、豆包的圆托按钮 */
    var chipDp: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        if (chipDp > 0f) {
            chipPaint.color = if (pressed) Palette.current.keyPressed else Palette.current.key
            canvas.drawCircle(width / 2f, height / 2f, chipDp * density / 2f, chipPaint)
        }
        val size = if (boxDp > 0f) boxDp * density else minOf(width, height) * scale
        painter.draw(canvas, glyph, width / 2f, height / 2f, size, tint, if (pressed) 140 else 255)
    }

    companion object {
        fun labelOf(glyph: Glyph): String? = A11Y_LABELS[glyph]

        /** 读屏念的说法（也让 uiautomator 能按描述找到它们） */
        private val A11Y_LABELS = mapOf(
            Glyph.BACK to "返回",
            Glyph.GRID to "快捷功能",
            Glyph.EMOJI to "表情",
            Glyph.CLIPBOARD to "剪贴板",
            Glyph.TEXT_EDIT to "文本编辑",
            Glyph.SETTINGS to "设置",
            Glyph.COLLAPSE to "收起键盘",
            Glyph.EXPAND to "展开候选",
            Glyph.CLOSE to "关闭",
            Glyph.INCOGNITO to "无痕",
            Glyph.MIC to "语音输入",
            Glyph.GLOBE to "切换输入法",
            Glyph.ONE_HAND to "单手",
            Glyph.KEYBOARD to "键盘",
            Glyph.PIN to "取消置顶",
            Glyph.PIN_OUTLINE to "置顶",
            Glyph.CLOCK to "最近",
            Glyph.ARROW_UP to "上",
            Glyph.ARROW_DOWN to "下",
            Glyph.ARROW_LEFT to "左",
            Glyph.ARROW_RIGHT to "右",
            Glyph.QUICK_INPUT to "快捷输入",
        )
    }
}

/**
 * [PanelIcon] 各图标的画法，单独拿出来：面板里的文字按钮（方向键、表情分类的「最近」）
 * 也要画同一套图标（[GlyphDrawable]），不能各画各的。
 */
class GlyphPainter {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * 以 ([cx], [cy]) 为中心、边长 [size] 画 [glyph]。图标本身来自 [IconSet]（SVG），
     * 这里只管缩放与着色：线宽跟随 SVG 声明的 stroke-width，随整体等比缩放，
     * 所以任何一套图标换进来，线粗与外框的比例都保持图标集自己的设计。
     */
    fun draw(canvas: Canvas, glyph: PanelIcon.Glyph, cx: Float, cy: Float, size: Float, tint: Int, alpha: Int = 255) {
        val loaded = IconSet.get(glyph.iconName) ?: return
        stroke.color = tint
        stroke.alpha = alpha
        stroke.strokeWidth = loaded.icon.strokeWidth
        fill.color = tint
        fill.alpha = alpha
        val scale = size / loaded.icon.viewBox
        canvas.save()
        canvas.translate(cx - size / 2f, cy - size / 2f)
        canvas.scale(scale, scale)
        if (glyph.filled) for (p in loaded.paths) canvas.drawPath(p, fill)
        for (p in loaded.paths) canvas.drawPath(p, stroke)
        canvas.restore()
    }
}

/** 把一个图标当前景画在按钮中央（文字清空后用），尺寸 [sizeDp] */
class GlyphDrawable(
    private val glyph: PanelIcon.Glyph,
    private val tint: Int,
    private val sizePx: Float,
) : android.graphics.drawable.Drawable() {
    private val painter = GlyphPainter()

    override fun draw(canvas: Canvas) {
        painter.draw(canvas, glyph, bounds.exactCenterX(), bounds.exactCenterY(), sizePx, tint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    companion object {
        /** [view] 的文字换成居中的矢量图标（尺寸 [sizeDp]） */
        fun apply(view: android.widget.TextView, glyph: PanelIcon.Glyph, tint: Int, sizeDp: Float = 22f) {
            view.text = ""
            view.contentDescription = PanelIcon.labelOf(glyph)
            view.foreground = GlyphDrawable(glyph, tint, sizeDp * view.resources.displayMetrics.density)
        }
    }
}
