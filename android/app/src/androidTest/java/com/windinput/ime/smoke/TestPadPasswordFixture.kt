package com.windinput.ime.smoke

import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.windinput.ime.settings.TestPadActivity

/** Compare synthetic key input inside our own test activity without exposing it to accessibility or logs. */
internal fun passwordFixtureEquals(expected: String): Boolean {
    var matches = false
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<TestPadActivity>().singleOrNull() ?: return@runOnMainSync
        val views = ArrayList<View>()
        activity.window.decorView.findViewsWithText(views, TestPadActivity.TEST_PAD_DESC, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        val editor = views.filterIsInstance<EditText>().singleOrNull()
        matches = editor != null && editor.transformationMethod is PasswordTransformationMethod &&
            !editor.isSaveEnabled && editor.text.toString() == expected
    }
    return matches
}
