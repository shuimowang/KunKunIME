package com.windinput.ime.smoke

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real settings activity on the dedicated test emulator without clearing its dictionary. */
@RunWith(AndroidJUnit4::class)
class PersonalDictionaryUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    @Test(timeout = 120_000)
    fun draftSurvivesRecreationAndFilteredDeleteKeepsItsHomophone() {
        val syllables = listOf("林" to "lin", "云" to "yun", "星" to "xing", "风" to "feng",
            "舟" to "zhou", "山" to "shan", "青" to "qing", "海" to "hai")
        val seed = UUID.randomUUID().leastSignificantBits
        val prefix = (0 until 8).map { syllables[((seed ushr (it * 3)) and 7).toInt()] }
        val text = prefix.joinToString("") { it.first }
        val code = prefix.joinToString(" ") { it.second } + " ning"
        val first = text + "宁"
        val other = text + "凝"

        open("添加词条").use { activity ->
            fill(0, first)
            fill(1, code)
            activity.recreate()
            await("draft restored") { edits().map { it.text.toString() } == listOf(first, code) }
            clickText("添加")
            await("first addition completed") { cleared(first, code) }
            fill(0, other)
            fill(1, code)
            clickText("添加")
            await("second addition completed") { cleared(other, code) }
        }
        open("我的词条").use { activity ->
            fill(0, first)
            await("one matching word") { hasText("找到 1 条") && word(first) != null }
            assertFalse("A filtered result offered to clear the entire dictionary", hasText("清空个人词"))
            activity.recreate()
            await("filtered query restored") { edits().singleOrNull()?.text.toString() == first && hasText("找到 1 条") }
            assertTrue(click(requireNotNull(word(first))))
            clickText("删除")
            await("filtered word was removed") { hasText("没有找到匹配的词条") }
            fill(0, other)
            await("unselected homophone still present") { word(other) != null && hasText("找到 1 条") }
            assertTrue(click(requireNotNull(word(other))))
            clickText("删除")
            await("test word cleaned up") { hasText("没有找到匹配的词条") }
        }
    }

    private fun open(sub: String): ActivityScenario<Activity> =
        ActivityScenario.launch(Intent().setClassName(context, "com.windinput.ime.settings.SettingsActivity")
            .putExtra("page", "dict").putExtra("sub", sub)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))

    private fun nodes(): List<AccessibilityNodeInfo> {
        automation.clearCache()
        val root = automation.rootInActiveWindow ?: return emptyList()
        val list = mutableListOf<AccessibilityNodeInfo>()
        fun collect(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() != context.packageName) return
            list.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::collect)
        }
        collect(root)
        return list
    }

    private fun edits() = nodes().filter { it.isEditable }.sortedBy { node -> Rect().also(node::getBoundsInScreen).top }
    private fun cleared(word: String, code: String): Boolean {
        val fields = edits()
        return fields.size == 2 && fields[0].text?.toString() != word && fields[1].text?.toString() != code
    }
    private fun hasText(part: String) = nodes().any { part in it.text?.toString().orEmpty() }
    private fun word(value: String) = nodes().firstOrNull { !it.isEditable && value in it.text?.toString().orEmpty() }

    private fun fill(index: Int, value: String) {
        await("editable field $index") {
            val node = edits().getOrNull(index) ?: return@await false
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            })
        }
    }

    private fun clickText(label: String) {
        await("button $label") {
            val node = nodes().firstOrNull { it.text?.toString() == label } ?: return@await false
            click(node)
        }
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable && current.isEnabled) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = current.parent
        }
        return false
    }

    private fun await(description: String, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue("Timed out waiting for $description", condition())
    }
}
