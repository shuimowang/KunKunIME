package com.windinput.ime.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 按键振动，强度可调（设置项 ui.android.haptic / haptic_strength）。
 *
 * 系统的 `performHapticFeedback(KEYBOARD_TAP)` 强度由厂商定死，不少机型偏重，调不了；
 * 这里自己调振动器，按机器能力从细到粗三档：
 * 1. 支持振动原语（API 30+）：`PRIMITIVE_CLICK` 按强度缩放——与系统按键振动同一种手感，只是更轻。
 * 2. 能调幅：短脉冲，幅度按强度换算。
 * 3. 都不能：只能缩短时长来变轻。
 *
 * 振动标明「触摸反馈」用途（[VibrationAttributes.USAGE_TOUCH]，旧系统用对应的 AudioAttributes）：
 * 系统据此套用「触摸振动」开关与勿扰规则——自己调振动器若不标用途，用户在系统里关了
 * 触摸振动，键盘照振，和系统键盘的表现对不上。
 */
/** 点击时先振一下再执行 [onClick]：面板、工具栏等非按键区的可点图标统一走这里，与按键同一套强度开关 */
fun View.setHapticClick(onClick: () -> Unit) {
    setOnClickListener {
        KeyHaptics.tap(it)
        onClick()
    }
}

object KeyHaptics {

    var enabled = true

    /** 强度 5–100（百分比） */
    var strength = DEFAULT_STRENGTH
        set(value) {
            field = value.coerceIn(MIN_STRENGTH, 100)
        }

    const val DEFAULT_STRENGTH = 30

    /** 与设置项 ui.android.haptic_strength 的 min 一致 */
    private const val MIN_STRENGTH = 5

    private var vibrator: Vibrator? = null

    /** 振动器能力：init 时查一次，不在每次按键时跨 binder 问 */
    private var hasPrimitives = false
    private var hasAmplitude = false

    fun init(context: Context) {
        if (vibrator != null) return
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }?.takeIf { it.hasVibrator() } ?: return
        vibrator = v
        hasPrimitives = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
        hasAmplitude = v.hasAmplitudeControl()
    }

    /** 按键按下的一下。[view] 只在拿不到振动器时用来走系统反馈兜底 */
    fun tap(view: View) {
        if (!enabled) return
        val v = vibrator
        if (v == null) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            return
        }
        val effect = effect()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, TOUCH_AUDIO_ATTRS)
        }
    }

    /**
     * API 33 以前标用途的办法：AudioAttributes 的 SONIFICATION（系统按触摸反馈对待）。
     * 懒构造：对象初始化时就建的话，JVM 单测里 Android 桩类一碰就抛，整个对象都用不了
     */
    private val TOUCH_AUDIO_ATTRS: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    private fun effect(): VibrationEffect {
        val scale = strength / 100f
        if (hasPrimitives && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, scale)
                .compose()
        }
        if (hasAmplitude) {
            return VibrationEffect.createOneShot(PULSE_MS, amplitudeFor(strength))
        }
        return VibrationEffect.createOneShot(durationFor(strength), VibrationEffect.DEFAULT_AMPLITUDE)
    }

    private const val PULSE_MS = 12L

    /** 强度 → 幅度 1..255 */
    fun amplitudeFor(strength: Int): Int = (strength.coerceIn(1, 100) * 255 / 100).coerceIn(1, 255)

    /** 不能调幅时强度 → 时长：4ms（几乎只是一下颤）到 20ms */
    fun durationFor(strength: Int): Long = 4L + strength.coerceIn(1, 100) * 16L / 100
}
