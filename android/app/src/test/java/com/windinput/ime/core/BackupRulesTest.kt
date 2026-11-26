package com.windinput.ime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** 系统备份规则：旧版（fullBackupContent）与新版（dataExtractionRules 两段）排除项一致，且挡住隐私数据 */
class BackupRulesTest {

    private fun excludes(file: String, parent: String? = null): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/xml/$file"))
        val root: Element = if (parent == null) {
            doc.documentElement
        } else {
            doc.getElementsByTagName(parent).item(0) as Element
        }
        val nodes = root.getElementsByTagName("exclude")
        return (0 until nodes.length).map { i ->
            val e = nodes.item(i) as Element
            "${e.getAttribute("domain")}:${e.getAttribute("path")}"
        }.toSet()
    }

    @Test
    fun allThreeRuleSetsAgree() {
        val legacy = excludes("backup_rules.xml")
        assertEquals(legacy, excludes("data_extraction_rules.xml", "cloud-backup"))
        assertEquals(legacy, excludes("data_extraction_rules.xml", "device-transfer"))
    }

    @Test
    fun clipboardHistoryAndCachesAreExcluded() {
        val rules = excludes("backup_rules.xml")
        for (must in listOf("file:clipboard.json", "file:data/", "file:rust/data/KunKunIME/cache/", "file:rust/data/KunKunIME/logs/")) {
            assertTrue("缺少排除项 $must", must in rules)
        }
    }

    @Test
    fun privateCoreStateOnlyExcludesTheProcessLock() {
        val rules = excludes("backup_rules.xml")
        val state = rules.filter { it.startsWith("file:rust/userdata.redb.state/") }
        assertEquals(setOf("file:rust/userdata.redb.state/lock"), state.toSet())
        assertTrue("用户数据已迁入私有目录，不应残留 external 规则", rules.none { it.startsWith("external:") })
    }

    /** 用户配置、词库和键盘设置都在私有 file 域，不能连同缓存一起排除。 */
    @Test
    fun settingsAndUserDictsStayIncluded() {
        val rules = excludes("backup_rules.xml")
        for (keep in listOf("file:", "file:rust/", "file:mobile/", "file:rust/data/", "file:rust/config/", "file:rust/config/KunKunIME/", "file:rust/userdata.redb.state/")) {
            assertTrue("$keep 不该被排除", keep !in rules)
        }
    }
}
