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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.windinput.ime.voice.VoiceInputController
import com.windinput.ime.voice.VoiceAvailability
import com.windinput.ime.voice.model.OfflineModelCatalog
import com.windinput.ime.voice.model.OfflineModelPhase
import com.windinput.ime.voice.model.OfflineModelState
import com.windinput.ime.voice.model.OfflineModelStore
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.util.Locale

/** Permission requests need an Activity. Returning never starts a microphone by itself. */
class VoiceSetupActivity : ComponentActivity() {
    private var available by mutableStateOf(VoiceAvailability(onDevice = false, system = false))
    private var selected by mutableStateOf<VoiceEngine?>(null)

    override fun onResume() {
        super.onResume()
        available = VoiceInputController.availability(this)
        selected = if (VoicePreferences.configured(this)) VoicePreferences.engine(this) else null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The application owns the transfer. Rotating/closing this Activity does
        // not restart it or capture an obsolete Activity in a download callback.
        val modelStore = OfflineModelStore.get(applicationContext)
        val theme = ThemeController(ColorSchemeMode.System)
        setContent {
            MiuixTheme(controller = theme) {
                val modelState by modelStore.state.collectAsState()
                var message by rememberSaveable { mutableStateOf("") }
                val finishSetup = {
                    Toast.makeText(this, "语音输入已开启，返回输入框后再次长按空格说话", Toast.LENGTH_LONG).show()
                    finish()
                }
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    if (granted) finishSetup()
                    else message = "未获得麦克风权限，普通打字仍可正常使用。你可以重新选择，或到应用权限里开启麦克风。"
                }
                val choose: (VoiceEngine) -> Unit = { engine ->
                    if (engine == VoiceEngine.LOCAL_MODEL && !modelStore.isReady()) {
                        message = "请先下载并校验离线语音包，再启用离线语音。"
                    } else {
                        when (engine) {
                            VoiceEngine.LOCAL_MODEL -> VoicePreferences.chooseLocal(this@VoiceSetupActivity)
                            VoiceEngine.SYSTEM_ON_DEVICE -> VoicePreferences.choose(this@VoiceSetupActivity, false)
                            VoiceEngine.SYSTEM_ALLOWED -> VoicePreferences.choose(this@VoiceSetupActivity, true)
                        }
                        selected = engine
                        message = ""
                        when {
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> finishSetup()
                            // A one-time grant can expire without a permanent denial.
                            // A fresh user choice may request it again; a fixed denial
                            // returns false and leaves the explicit app-settings link.
                            else -> permission.launch(Manifest.permission.RECORD_AUDIO)
                        }
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
                    selected?.let {
                        Text("当前选择：${voiceEngineLabel(it)}", fontSize = 14.sp)
                    }
                    OfflineModelCard(
                        state = modelState,
                        selected = selected == VoiceEngine.LOCAL_MODEL,
                        onDownload = {
                            message = ""
                            modelStore.download()
                        },
                        onCancel = modelStore::cancelDownload,
                        onChoose = { choose(VoiceEngine.LOCAL_MODEL) },
                        onRemove = {
                            message = ""
                            modelStore.remove()
                        },
                    )
                    if (available.onDevice || available.system) SectionTitle("其他识别方式")
                    if (available.onDevice) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            NavRow("使用系统设备端识别", "使用手机已安装的中文或英文系统语音模型，声音留在设备端。") {
                                choose(VoiceEngine.SYSTEM_ON_DEVICE)
                            }
                        }
                    }
                    if (available.system) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            NavRow(
                                "允许系统语音服务",
                                "优先设备端；无法在本机识别时交给手机的系统语音服务。该服务可能把声音发送到其提供方服务器，联网与数据处理由该服务管理。",
                            ) { choose(VoiceEngine.SYSTEM_ALLOWED) }
                        }
                    }
                    if (!available.onDevice && !available.system) {
                        Text("这台手机未提供可用的系统语音服务。下载上方离线语音包后，困困仍可独立识别。", fontSize = 14.sp)
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
        NavRow("语音输入", "长按空格说话 · 离线语音包与麦克风权限") {
            context.startActivity(Intent(context, VoiceSetupActivity::class.java))
        }
    }
}

private fun voiceEngineLabel(engine: VoiceEngine): String = when (engine) {
    VoiceEngine.LOCAL_MODEL -> "应用内离线语音"
    VoiceEngine.SYSTEM_ON_DEVICE -> "系统设备端识别"
    VoiceEngine.SYSTEM_ALLOWED -> "设备端优先，允许系统语音服务"
}

@Composable
private fun OfflineModelCard(
    state: OfflineModelState,
    selected: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("应用内离线语音 · 推荐", fontSize = 20.sp)
            Text(OfflineModelCatalog.DISPLAY_NAME, fontSize = 16.sp)
            Text(
                "中文、英文等语言 · 长按后先等待准备完成，再开始说话，松手后在手机上识别整句。下载完成后无需联网，也不依赖手机的系统语音服务。",
                fontSize = 14.sp,
            )
            Text(
                "下载 ${voiceModelSize(OfflineModelCatalog.DOWNLOAD_BYTES)} · 安装后约 ${voiceModelSize(OfflineModelCatalog.STORAGE_BYTES)}\n安装时请预留约 450 MiB 可用空间。",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            when (state.phase) {
                OfflineModelPhase.CHECKING -> Text("正在检查本机语音包…", fontSize = 15.sp)
                OfflineModelPhase.MISSING -> {
                    Text("首次下载需要联网，语音识别不上传声音。", fontSize = 14.sp)
                    TextButton(text = "下载离线语音包", onClick = onDownload)
                }
                OfflineModelPhase.DOWNLOADING -> {
                    Text("正在下载离线语音包…", fontSize = 16.sp)
                    Text("${voiceModelSize(state.downloadedBytes)} / ${voiceModelSize(state.totalBytes)}", fontSize = 14.sp)
                    VoiceModelProgress(state.downloadedBytes, state.totalBytes)
                    TextButton(text = "取消下载", onClick = onCancel)
                }
                OfflineModelPhase.VERIFYING -> {
                    Text("正在校验离线语音包…", fontSize = 16.sp)
                    Text("校验完成后即可启用。", fontSize = 14.sp)
                    TextButton(text = "取消", onClick = onCancel)
                }
                OfflineModelPhase.READY -> {
                    Text(if (selected) "语音包已安装 · 当前使用此方式" else "语音包已安装，可以离线使用", fontSize = 15.sp)
                    TextButton(text = "使用离线语音", onClick = onChoose)
                    TextButton(text = "删除语音包", onClick = onRemove)
                }
                OfflineModelPhase.FAILED -> {
                    Text(state.message ?: "语音包未能准备好，请重新下载。", fontSize = 15.sp)
                    TextButton(text = "重新下载语音包", onClick = onDownload)
                }
            }
            if (state.phase != OfflineModelPhase.FAILED) {
                state.message?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 14.sp) }
            }
        }
    }
}

@Composable
private fun VoiceModelProgress(downloadedBytes: Long, totalBytes: Long) {
    val progress = (downloadedBytes.toFloat() / totalBytes.coerceAtLeast(1)).coerceIn(0f, 1f)
    val foreground = MiuixTheme.colorScheme.primary
    val background = foreground.copy(alpha = 0.15f)
    Canvas(modifier = Modifier.fillMaxWidth().height(6.dp).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
    }) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(background, cornerRadius = radius)
        drawRoundRect(foreground, size = Size(size.width * progress, size.height), cornerRadius = radius)
    }
}

private fun voiceModelSize(bytes: Long): String = String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0))
