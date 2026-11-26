package com.windinput.ime.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningPolicyTest {

    private val text = InputType.TYPE_CLASS_TEXT

    @Test
    fun appRequestSuspendsEvenInPlainText() {
        assertTrue(suspendsLearning(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
    }

    @Test
    fun passwordFieldsSuspend() {
        for (v in listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) {
            assertTrue("变体 $v", suspendsLearning(text or v, 0))
        }
        assertTrue(suspendsLearning(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0))
    }

    /** 普通框必须是 false：开关不落盘，靠每个框显式复位 */
    @Test
    fun ordinaryFieldsLearn() {
        assertFalse(suspendsLearning(text, 0))
        assertFalse(suspendsLearning(text or InputType.TYPE_TEXT_VARIATION_URI, 0))
        assertFalse(suspendsLearning(InputType.TYPE_CLASS_NUMBER, 0))
        assertFalse(suspendsLearning(0, 0))
    }
}
