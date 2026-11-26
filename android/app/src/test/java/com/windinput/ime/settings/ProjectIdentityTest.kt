package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/** 分支身份与组件类名分开：更换 applicationId 后仍必须能打开输入法的设置组件。 */
class ProjectIdentityTest {
    @Test
    fun installationHasItsOwnApplicationIdAndUserVisibleName() {
        val gradle = File("build.gradle.kts").readText()
        val appId = Regex("""applicationId\s*=\s*"([^"]+)"""")
            .find(gradle)!!.groupValues[1]
        assertEquals("io.github.shuimowang.kunkunime", appId)
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
        val names = strings.getElementsByTagName("string")
        for (i in 0 until names.length) {
            val node = names.item(i)
            if (node.attributes.getNamedItem("name").nodeValue in setOf("app_name", "ime_name", "subtype_zh")) {
                assertEquals("困困输入法", node.textContent)
            }
        }
    }

    @Test
    fun systemSettingsEntryUsesTheExistingKotlinNamespace() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val method = factory.newDocumentBuilder().parse(File("src/main/res/xml/method.xml"))
            .documentElement
        val activity = method.getAttributeNS("http://schemas.android.com/apk/res/android", "settingsActivity")
        assertEquals("com.windinput.ime.settings.SettingsActivity", activity)
        assertTrue(File("src/main/java/${activity.replace('.', '/')}.kt").isFile)
    }

    @Test
    fun downloadsAndFeedbackStayWithTheFork() {
        val release = URI(ProjectLinks.RELEASES_URL)
        val issues = URI(ProjectLinks.ISSUES_URL)
        assertEquals("https", release.scheme)
        assertEquals("github.com", release.host)
        assertEquals("/shuimowang/KunKunIME/releases", release.path)
        assertEquals("https", issues.scheme)
        assertEquals("github.com", issues.host)
        assertEquals("/shuimowang/KunKunIME/issues/new", issues.path)
    }
}
