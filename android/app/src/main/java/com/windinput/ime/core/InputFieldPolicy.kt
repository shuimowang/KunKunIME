package com.windinput.ime.core

import android.text.InputType

/** Fields that must stay immediately usable without a language engine or deferred key queue. */
fun usesDirectInput(inputType: Int): Boolean {
    val clazz = inputType and InputType.TYPE_MASK_CLASS
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    return when (clazz) {
        InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> true
        InputType.TYPE_CLASS_TEXT -> variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        else -> false
    }
}
