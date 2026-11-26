package com.windinput.ime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.windinput.ime.voice.VoicePhase
import com.windinput.ime.voice.VoiceState

/** Replaces the candidate strip without moving the keyboard under a held finger. */
class VoiceStatusView(context: Context, onStop: () -> Unit, onCancel: () -> Unit) : LinearLayout(context) {
    private val palette = Palette.current
    private val density = resources.displayMetrics.density
    private val meter = LevelMeter(context)
    private val title = TextView(context).apply {
        textSize = 14f
        setTextColor(palette.text)
        maxLines = 1
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val detail = TextView(context).apply {
        textSize = 12f
        setTextColor(palette.hint)
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val done = action("完成", onStop)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(4), 0)
        setBackgroundColor(palette.background)
        addView(meter, LayoutParams(dp(26), LayoutParams.MATCH_PARENT))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title)
            addView(detail)
        }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(done, LayoutParams(dp(48), LayoutParams.MATCH_PARENT))
        addView(action("取消", onCancel), LayoutParams(dp(48), LayoutParams.MATCH_PARENT))
    }

    private fun dp(value: Int) = (value * density).toInt()

    private fun action(label: String, block: () -> Unit) = TextView(context).apply {
        text = label
        contentDescription = "语音$label"
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(palette.accentText)
        isClickable = true
        isFocusable = true
        setOnClickListener { block() }
    }

    fun render(state: VoiceState, cancelOnRelease: Boolean, held: Boolean) {
        val heading = when {
            cancelOnRelease -> "松开取消"
            state.phase == VoicePhase.CHECKING || state.phase == VoicePhase.STARTING -> "正在准备麦克风…"
            state.phase == VoicePhase.PROCESSING -> "正在识别…"
            state.phase == VoicePhase.RESULT_READY -> if (held) "松手输入，上滑取消" else "识别完成"
            else -> if (held) "正在聆听 · 松手输入" else "正在聆听 · 点完成输入"
        }
        if (title.text.toString() != heading) title.text = heading
        title.setTextColor(if (cancelOnRelease) 0xffc53929.toInt() else palette.text)
        val summary = if (cancelOnRelease) "滑回可继续说话" else state.partialText.ifBlank {
            (if (state.onDevice) "设备端识别" else "系统语音服务") + if (held) " · 上滑取消" else ""
        }
        if (detail.text.toString() != summary) detail.text = summary
        done.visibility = if (!held && state.phase != VoicePhase.PROCESSING) View.VISIBLE else View.GONE
        meter.level = state.level
    }

    private class LevelMeter(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.current.accent }
        var level = 0f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }

        override fun onDraw(canvas: Canvas) {
            val barWidth = width / 7f
            for (i in 0..2) {
                val amount = (0.15f + level * if (i == 1) 0.7f else 0.5f)
                val h = height * amount
                val x = barWidth * (1 + i * 2)
                canvas.drawRoundRect(x, (height - h) / 2f, x + barWidth, (height + h) / 2f, barWidth, barWidth, paint)
            }
        }
    }
}
