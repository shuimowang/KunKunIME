package com.windinput.ime.keyboard

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.TextView

/**
 * 功能键图标（矢量）：键盘与各面板共用一套，退格键在哪儿都长一个样。
 *
 * 不用字体里的 ⇧ / ⌫：笔画细、各家字体字形不一，和字母键面放一起很突兀（真机反馈）。
 * 笔画粗细与字母键面的视觉重量对齐；拐角用 CornerPathEffect 磨圆，与键帽圆角一个味道。
 * 图标以 (cx, cy) 为中心、[ICON_DP] 为基准尺寸画。
 */
object KeyIcons {

    const val ICON_DP = 20f

    fun strokePaint(density: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.8f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        pathEffect = CornerPathEffect(2f * density)
    }

    /** 上档键：小写态描边箭头；单次大写实心；大写锁定实心 + 底下一横（三家通行的三态画法） */
    fun drawShift(canvas: Canvas, cx: Float, centerY: Float, density: Float, filled: Boolean, locked: Boolean, paint: Paint, path: Path) {
        val s = ICON_DP * density
        // 锁定态多一横，整体上移一点保持视觉居中
        val cy = centerY - if (locked) 2.5f * density else 0f
        fun x(u: Float) = cx + u * s
        fun y(v: Float) = cy + v * s
        path.reset()
        path.moveTo(x(0f), y(-0.48f))
        path.lineTo(x(0.48f), y(0.04f))
        path.lineTo(x(0.2f), y(0.04f))
        path.lineTo(x(0.2f), y(0.42f))
        path.lineTo(x(-0.2f), y(0.42f))
        path.lineTo(x(-0.2f), y(0.04f))
        path.lineTo(x(-0.48f), y(0.04f))
        path.close()
        paint.style = if (filled || locked) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        canvas.drawPath(path, paint)
        if (locked) {
            paint.style = Paint.Style.STROKE
            canvas.drawLine(x(-0.2f), y(0.62f), x(0.2f), y(0.62f), paint)
        }
    }

    /** 退格键：左尖的标签形外框，里面一个 × */
    fun drawBackspace(canvas: Canvas, cx: Float, cy: Float, density: Float, paint: Paint, path: Path) {
        val s = ICON_DP * density
        fun x(u: Float) = cx + u * s
        fun y(v: Float) = cy + v * s
        path.reset()
        path.moveTo(x(-0.62f), y(0f))
        path.lineTo(x(-0.3f), y(-0.36f))
        path.lineTo(x(0.58f), y(-0.36f))
        path.lineTo(x(0.58f), y(0.36f))
        path.lineTo(x(-0.3f), y(0.36f))
        path.close()
        paint.style = Paint.Style.STROKE
        canvas.drawPath(path, paint)
        val d = 0.13f
        canvas.drawLine(x(0.14f - d), y(-d), x(0.14f + d), y(d), paint)
        canvas.drawLine(x(0.14f - d), y(d), x(0.14f + d), y(-d), paint)
    }

    /** 删光标前全部：退格标签外框，里面「|←」（箭头撞向一竖，意为一路删到开头） */
    fun drawDeleteBefore(canvas: Canvas, cx: Float, cy: Float, density: Float, paint: Paint, path: Path) {
        val s = ICON_DP * density
        fun x(u: Float) = cx + u * s
        fun y(v: Float) = cy + v * s
        path.reset()
        path.moveTo(x(-0.62f), y(0f))
        path.lineTo(x(-0.3f), y(-0.36f))
        path.lineTo(x(0.58f), y(-0.36f))
        path.lineTo(x(0.58f), y(0.36f))
        path.lineTo(x(-0.3f), y(0.36f))
        path.close()
        paint.style = Paint.Style.STROKE
        canvas.drawPath(path, paint)
        canvas.drawLine(x(-0.14f), y(-0.15f), x(-0.14f), y(0.15f), paint)
        canvas.drawLine(x(-0.04f), y(0f), x(0.4f), y(0f), paint)
        canvas.drawLine(x(-0.04f), y(0f), x(0.08f), y(-0.11f), paint)
        canvas.drawLine(x(-0.04f), y(0f), x(0.08f), y(0.11f), paint)
    }

    /** 清空整个输入框：垃圾桶（盖 + 提手 + 桶身两道竖纹） */
    fun drawTrash(canvas: Canvas, cx: Float, cy: Float, density: Float, paint: Paint, path: Path) {
        val s = ICON_DP * density
        fun x(u: Float) = cx + u * s
        fun y(v: Float) = cy + v * s
        paint.style = Paint.Style.STROKE
        canvas.drawLine(x(-0.4f), y(-0.28f), x(0.4f), y(-0.28f), paint)
        path.reset()
        path.moveTo(x(-0.12f), y(-0.28f))
        path.lineTo(x(-0.12f), y(-0.42f))
        path.lineTo(x(0.12f), y(-0.42f))
        path.lineTo(x(0.12f), y(-0.28f))
        canvas.drawPath(path, paint)
        path.reset()
        path.moveTo(x(-0.3f), y(-0.28f))
        path.lineTo(x(-0.24f), y(0.44f))
        path.lineTo(x(0.24f), y(0.44f))
        path.lineTo(x(0.3f), y(-0.28f))
        canvas.drawPath(path, paint)
        canvas.drawLine(x(-0.09f), y(-0.12f), x(-0.09f), y(0.28f), paint)
        canvas.drawLine(x(0.09f), y(-0.12f), x(0.09f), y(0.28f), paint)
    }

    /** 面板里的退格按钮（TextView）：清掉字符，改由前景画矢量图标，居中于整个按钮 */
    fun applyBackspace(view: TextView, color: Int) {
        view.text = ""
        if (view.contentDescription == null) view.contentDescription = "退格"
        view.foreground = BackspaceDrawable(color, view.resources.displayMetrics.density)
    }

    private class BackspaceDrawable(color: Int, private val density: Float) : Drawable() {
        private val paint = strokePaint(density).apply { this.color = color }
        private val path = Path()

        override fun draw(canvas: Canvas) {
            drawBackspace(canvas, bounds.exactCenterX(), bounds.exactCenterY(), density, paint, path)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
