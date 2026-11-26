package com.windinput.ime.core

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFieldPolicyTest {
    @Test fun supportsTextIncludingMultilineButNeverPasswords() {
        assertTrue(allowsVoiceInput(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        for (variation in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            assertFalse(allowsVoiceInput(InputType.TYPE_CLASS_TEXT or variation))
        }
    }

    @Test fun doesNotStartRecordingForNumericOrMissingEditors() {
        for (type in listOf(0, InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)) {
            assertFalse(allowsVoiceInput(type))
        }
    }
}
