package com.windinput.ime.keyboard

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 单手模式让出来的那一条：键盘收窄靠向一侧，空出的一侧放两个按钮——「换边」把键盘挪到另一侧，
 * 「退出」回到满宽。外观同功能键（键帽底色、圆角），读屏念按钮上的字。
 */
class OneHandStrip(
    context: Context,
    private val onSwitchSide: () -> Unit,
    private val onExit: () -> Unit,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Palette.current.background)
        addView(button("换边", onSwitchSide), LayoutParams(LayoutParams.MATCH_PARENT, dp(64)))
        addView(button("退出\n单手", onExit), LayoutParams(LayoutParams.MATCH_PARENT, dp(64)).apply { topMargin = dp(12) })
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun button(label: String, onClick: () -> Unit): TextView {
        val p = Palette.current
        val gap = dp(6)
        fun face(color: Int) = InsetDrawable(
            GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(color)
            },
            gap,
        )
        return TextView(context).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(p.text)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), face(p.keyPressed))
                addState(intArrayOf(), face(p.keySpecial))
            }
            setOnClickListener {
                KeyHaptics.tap(it)
                onClick()
            }
        }
    }

    companion object {
        /** 让出一侧占屏宽的比例：再窄按钮点不准，再宽键盘就不够「单手」了 */
        const val WIDTH_FRACTION = 0.18f

        const val OFF = "off"
        const val LEFT = "left"
        const val RIGHT = "right"

        /** 快捷面板开关：关着时打开到上次的那一侧（没用过 = 右手），开着时关掉 */
        fun toggled(current: String, lastSide: String): String =
            if (current == LEFT || current == RIGHT) OFF else if (lastSide == LEFT) LEFT else RIGHT

        fun otherSide(current: String): String = if (current == LEFT) RIGHT else LEFT
    }
}
