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

    /** 明确枚举实际功能所需权限，不能无提示地扩大到存储等权限。 */
    @Test
    fun manifestRequestsOnlyDisclosedInputAndUpdatePermissions() {
        assertEquals(
            setOf("android.permission.VIBRATE", "android.permission.RECORD_AUDIO",
                "android.permission.INTERNET", "android.permission.REQUEST_INSTALL_PACKAGES"),
            permissions,
        )
    }

    @Test
    fun policyDistinguishesLocalTypingFromRequestedDownloadsAndSystemServices() {
        val network = PRIVACY_POLICY.single { it.first == "联网" }.second
        assertTrue(network.contains("主动检查或下载更新"))
        assertTrue(network.contains("下载离线语音包"))
        assertTrue(network.contains("不需要联网识别"))
        assertTrue(network.contains("不会在后台自动检查更新"))
        assertTrue(network.contains("GitHub"))
        assertTrue(network.contains("浏览器"))
        assertTrue(network.contains("系统备份可能联网"))
    }

    @Test
    fun policyCoversRequiredTopics() {
        val titles = PRIVACY_POLICY.map { it.first }
        for (t in listOf("联网", "下载与更新", "离线语音包", "启用时的系统提示", "数据只存本地", "语音输入", "权限用途", "删除数据")) {
            assertTrue("隐私政策缺「$t」", t in titles)
        }
    }

    @Test
    fun policyExplainsMicrophoneConsentAndSystemSpeechDataHandling() {
        val voice = PRIVACY_POLICY.single { it.first == "语音输入" }.second
        assertTrue(voice.contains("明确选择系统语音服务"))
        assertTrue(voice.contains("可能把声音发送给其提供者"))
        assertTrue(voice.contains("不保存录音"))
        assertTrue(voice.contains("密码框禁用"))
        val use = PRIVACY_POLICY.single { it.first == "权限用途" }.second
        assertTrue(use.contains("拒绝授权仍可正常打字"))
    }

    @Test
    fun policyExplainsVerifiedUpdatesAndSystemControlledInstallation() {
        val updates = PRIVACY_POLICY.single { it.first == "下载与更新" }.second
        assertTrue(updates.contains("不会携带你的输入文字"))
        assertTrue(updates.contains("SHA-256、包名、版本和签名"))
        assertTrue(updates.contains("系统界面交给你确认"))
        assertTrue(updates.contains("不会静默安装"))
        assertTrue(updates.contains("不需要存储权限"))
    }

    @Test
    fun policyExplainsLocalModelDownloadStorageAndDeletion() {
        val model = PRIVACY_POLICY.single { it.first == "离线语音包" }.second
        assertTrue(model.contains("点「下载离线语音包」"))
        assertTrue(model.contains("不上传录音、输入文字、个人词库或常用语"))
        assertTrue(model.contains("不进入系统备份"))
        assertTrue(model.contains("不需要存储权限"))
        val voice = PRIVACY_POLICY.single { it.first == "语音输入" }.second
        assertTrue(voice.contains("本机内存中临时处理"))
        assertTrue(voice.contains("不联网识别、不保存录音"))
        val removal = PRIVACY_POLICY.single { it.first == "删除数据" }.second
        assertTrue(removal.contains("删除语音包"))
        assertTrue(removal.contains("不会自动改用联网识别"))
    }
}
