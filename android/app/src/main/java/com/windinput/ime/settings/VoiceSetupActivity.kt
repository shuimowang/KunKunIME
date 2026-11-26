package com.windinput.ime.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.windinput.ime.voice.VoiceInputController
import com.windinput.ime.voice.VoiceAvailability
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Permission requests need an Activity. Returning never starts a microphone by itself. */
class VoiceSetupActivity : ComponentActivity() {
    private var available by mutableStateOf(VoiceAvailability(onDevice = false, system = false))

    override fun onResume() {
        super.onResume()
        available = VoiceInputController.availability(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val theme = ThemeController(ColorSchemeMode.System)
        setContent {
            MiuixTheme(controller = theme) {
                var message by remember { mutableStateOf("") }
                val finishSetup = {
                    Toast.makeText(this, "语音输入已开启，返回输入框后再次长按空格说话", Toast.LENGTH_LONG).show()
                    finish()
                }
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) finishSetup()
                    else message = "未获得麦克风权限，普通打字仍可正常使用。你可以重新选择，或到应用权限里开启麦克风。"
                }
                val choose: (Boolean) -> Unit = { systemFallback ->
                    VoicePreferences.choose(this, systemFallback)
                    when {
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> finishSetup()
                        // A one-time grant can expire without a permanent denial.
                        // A fresh user choice may request it again; a fixed denial
                        // returns false and leaves the explicit app-settings link.
                        else -> permission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                Column(
                    modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).safeDrawingPadding()
                        .verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("语音输入", fontSize = 26.sp)
                    Text("长按空格说话，松手转成文字；向上滑动再松手可取消。短按空格和横滑移动光标照常使用。", fontSize = 16.sp)
                    Text(
                        "仅在你主动开始语音输入时使用麦克风。困困输入法不保存录音，识别中的文字只显示在键盘上，完成后才输入。密码框不能使用语音输入。",
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (available.onDevice) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            NavRow("仅使用设备端识别", "声音留在设备端，需要手机已安装对应的中文或英文语音模型。") { choose(false) }
                        }
                    }
                    if (available.system) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            NavRow(
                                "允许系统语音服务",
                                "优先设备端；无法在本机识别时交给手机的系统语音服务。该服务可能把声音发送到其提供方服务器，联网与数据处理由该服务管理。",
                            ) { choose(true) }
                        }
                    }
                    if (!available.onDevice && !available.system) {
                        Text("这台手机没有提供可用的系统语音识别服务。请先在系统设置中安装或启用语音识别服务，再返回这里；仅安装另一个输入法不一定会提供该服务。", fontSize = 15.sp)
                    }
                    if (VoicePreferences.configured(this)) {
                        Text(
                            "当前选择：" + if (VoicePreferences.allowSystemFallback(this)) "设备端优先，允许系统语音服务" else "仅设备端识别",
                            fontSize = 14.sp,
                        )
                    }
                    if (message.isNotEmpty()) Text(message, fontSize = 15.sp)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        NavRow("应用权限", "查看或修改麦克风权限") {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        }
                        NavRow("返回", "回到输入框；这里不会自动开始录音") { finish() }
                    }
                }
            }
        }
    }
}

@Composable
internal fun VoiceSettingsEntry() {
    val context = LocalContext.current
    SectionTitle("语音")
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow("语音输入", "长按空格说话 · 识别服务与麦克风权限") {
            context.startActivity(Intent(context, VoiceSetupActivity::class.java))
        }
    }
}
