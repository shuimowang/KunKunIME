package com.windinput.ime.core

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * 这个输入框里要不要暂停学习（无痕）。
 *
 * - 应用明确要求：`IME_FLAG_NO_PERSONALIZED_LEARNING`（浏览器无痕窗口、部分聊天的私密输入框）。
 * - 密码类输入框：文本密码框本来就直输、不经引擎，但数字密码框（支付密码、锁屏 PIN）
 *   走的是数字层，按键可能经过引擎——一律暂停，不赌哪条路不记东西。
 *
 * 参数用整数而不是 EditorInfo，JVM 单测里就能直接跑。
 */
fun suspendsLearning(inputType: Int, imeOptions: Int): Boolean {
    if (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true
    val clazz = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (clazz) {
        InputType.TYPE_CLASS_TEXT -> variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        else -> false
    }
}
