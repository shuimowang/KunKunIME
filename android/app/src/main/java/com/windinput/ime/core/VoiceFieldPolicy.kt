package com.windinput.ime.core

import android.text.InputType

/** Speech is text input; never start it for passwords, PINs, phone or date fields. */
fun allowsVoiceInput(inputType: Int): Boolean =
    (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT && !usesDirectInput(inputType)
