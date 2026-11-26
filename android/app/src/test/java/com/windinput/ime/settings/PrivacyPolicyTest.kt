package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 隐私政策里写的事实要与清单一致：改了权限却忘了改政策，这里失败 */
class PrivacyPolicyTest {

    private val permissions = Regex("""<uses-permission\s+android:name="([^"]+)"""")
        .findAll(File("src/main/AndroidManifest.xml").readText())
        .map { it.groupValues[1] }
        .toSet()

    /** 输入法本身离线工作；浏览器打开发布页不需要输入法申请网络权限。 */
    @Test
    fun manifestRequestsOnlyVibrate() {
        assertEquals(
            setOf("android.permission.VIBRATE"),
            permissions,
        )
    }

    @Test
    fun policyDistinguishesOfflineImeFromBrowserAndSystemBackup() {
        val network = PRIVACY_POLICY.single { it.first == "联网" }.second
        assertTrue(network.contains("不申请网络权限"))
        assertTrue(network.contains("浏览器"))
        assertTrue(network.contains("系统备份可能联网"))
    }

    @Test
    fun policyCoversRequiredTopics() {
        val titles = PRIVACY_POLICY.map { it.first }
        for (t in listOf("联网", "启用时的系统提示", "数据只存本地", "权限用途", "删除数据")) {
            assertTrue("隐私政策缺「$t」", t in titles)
        }
    }
}
