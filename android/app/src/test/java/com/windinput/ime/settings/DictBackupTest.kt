package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class DictBackupTest {

    /** 与电脑版导出同名同后缀，电脑版的文件对话框按 yaml 过滤才看得见 */
    @Test
    fun fileNameMatchesDesktop() {
        val name = DictBackup.fileName("wubi86", LocalDateTime.of(2026, 10, 20, 9, 5, 7))
        assertEquals("wubi86-20261020-090507.wdict.yaml", name)
    }

    @Test
    fun describeListsNonZeroCountsPerSection() {
        val text = DictBackup.describe(
            listOf(
                DictBackup.Section("userWords", added = 3, updated = 1, unchanged = 9, skipped = 0),
                DictBackup.Section("freq", added = 120, updated = 0, unchanged = 0, skipped = 2),
            ),
        )
        assertEquals("已导入：个人词新增 3、更新 1；词频 120 条、跳过 2", text)
    }

    /** 重导同一份文件：全是 unchanged，要说清楚不是失败 */
    @Test
    fun describeSaysNothingNewWhenAllUnchanged() {
        val text = DictBackup.describe(listOf(DictBackup.Section("userWords", 0, 0, 42, 0)))
        assertEquals("导入完成，没有新内容", text)
    }

    /** 全被跳过：要说「没导入」和怎么改，不能写成「已导入：跳过 1」 */
    @Test
    fun describeAllSkippedExplainsFormat() {
        val text = DictBackup.describe(listOf(DictBackup.Section("userWords", 0, 0, 0, 1)))
        assertEquals("没有导入任何词条：1 行认不出。文本词表每行写「编码<Tab>词」", text)
    }

    /** 读到上限以内照常返回；超出就停下返回 null，不靠文件描述符报的长度 */
    @Test
    fun readLimitedStopsPastTheCap() {
        val small = ByteArray(100) { 1 }
        assertEquals(100, DictBackup.readLimited(small.inputStream(), max = 100)!!.size)
        assertEquals(null, DictBackup.readLimited(ByteArray(101).inputStream(), max = 100))
    }
}
