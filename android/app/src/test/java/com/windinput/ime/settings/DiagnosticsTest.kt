package com.windinput.ime.settings

import com.windinput.ime.core.CrashLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class DiagnosticsTest {

    @Test
    fun composeListsHeaderCrashAndLogs() {
        val text = Diagnostics.compose(
            listOf("版本" to "0.3.0", "机型" to "Xiaomi LX04"),
            listOf("I/WindIme: a", "W/RustSession: b"),
            "时间：x\njava.lang.IllegalStateException: boom\n",
        )
        assertTrue(text.contains("版本：0.3.0\n机型：Xiaomi LX04\n"))
        assertTrue(text.contains("IllegalStateException: boom"))
        assertTrue(text.contains("（INFO 及以上，2 行）"))
        assertTrue(text.endsWith("W/RustSession: b\n"))
    }

    @Test
    fun composeMarksMissingCrashAndLogs() {
        val text = Diagnostics.compose(emptyList(), emptyList(), null)
        assertTrue(text.contains("── 最近一次崩溃 ──\n（无）"))
        assertTrue(text.contains("（读不到日志）"))
    }

    /** 不含用户文字：只要 INFO 及以上，测试台记原文的标签整个静音，只读本进程 */
    @Test
    fun logcatFiltersOutVerboseAndTestPad() {
        val cmd = Diagnostics.logcatCommand(1234)
        assertEquals(listOf("WindTestPad:S", "*:I"), cmd.takeLast(2))
        assertEquals("1234", cmd[cmd.indexOf("--pid") + 1])
        assertTrue("-d" in cmd)
    }

    @Test
    fun crashRecordHasThreadAndStack() {
        val text = CrashLog.format(Date(0), "main", IllegalStateException("boom"))
        assertTrue(text.contains("线程：main"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
        assertTrue(text.contains("DiagnosticsTest"))
    }

    @Test
    fun hugeStacksAreClipped() {
        val deep = RuntimeException("x".repeat(40_000))
        val text = CrashLog.format(Date(0), "t", deep)
        assertTrue(text.length < 17_000)
        assertTrue(text.endsWith("（已截断）"))
    }

    /** 压缩包的日志不按 pid 过滤（要带上崩溃前的旧进程），但过滤级别与静音标签不能松 */
    @Test
    fun zipLogcatKeepsPrivacyFiltersWithoutPid() {
        val cmd = Diagnostics.zipLogcatCommand(false)
        assertTrue("--pid" !in cmd)
        assertEquals(listOf("WindTestPad:S", "*:I"), cmd.takeLast(2))
        assertEquals(listOf("-b", "crash"), Diagnostics.zipLogcatCommand(true).let { it.subList(it.indexOf("-b"), it.indexOf("-b") + 2) })
    }
    @Test
    fun composeAppendsExtraSectionsBeforeLogs() {
        val text = Diagnostics.compose(
            emptyList(), listOf("I/x: a"), null,
            extra = listOf("触摸丢键统计" to "取消=3\n漂出=1\n"),
        )
        assertTrue(text.contains("── 触摸丢键统计 ──\n取消=3\n漂出=1\n"))
        assertTrue(text.indexOf("触摸丢键统计") < text.indexOf("最近日志"))
    }

    @Test
    fun extraSectionsSkipFailingProvidersAndReplaceSameTitle() {
        Diagnostics.registerExtraSection("T1") { "old" }
        Diagnostics.registerExtraSection("T1") { "new" }
        Diagnostics.registerExtraSection("T2") { error("boom") }
        val got = Diagnostics.collectExtraSections().filter { it.first.startsWith("T") }
        assertEquals(listOf("T1" to "new"), got)
    }

    @Test
    fun keyboardSettingsLineShowsValuesAndMarksUnreadable() {
        val line = Diagnostics.keyboardSettingsLine { key, default ->
            if (key.endsWith("side_gap")) null else if (key.endsWith("bottom_gap")) 12 else default
        }
        assertTrue(line, "竖屏键行高 60dp" in line)
        assertTrue(line, "横屏键行高 44dp" in line)
        assertTrue(line, "底部留空 12dp" in line)
        assertTrue(line, "侧边留空 ?dp" in line)
    }

    @Test
    fun readmeCarriesVersionStepsAndPrivacyNote() {
        val text = Diagnostics.readme("0.5.4-dev.2")
        assertTrue(text.contains("0.5.4-dev.2"))
        assertTrue(text.contains("停留约 2 秒"))
        assertTrue(text.contains("导出诊断压缩包"))
        assertTrue(text.contains("不含输入的文字"))
    }
}
