package com.windinput.ime.update

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.io.File
import java.util.Locale

class UpdateActivity : ComponentActivity() {
    private lateinit var model: UpdateViewModel
    private var canInstall by mutableStateOf(false)
    private var resumed by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        canInstall = packageManager.canRequestPackageInstalls()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[UpdateViewModel::class.java]
        val theme = ThemeController(ColorSchemeMode.System)
        setContent {
            MiuixTheme(controller = theme) {
                LaunchedEffect(model.installerRequest, resumed) {
                    if (resumed) model.takeInstallerRequest()?.let(::openInstaller)
                }
                Column(
                    modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)
                        .safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("应用更新", fontSize = 26.sp)
                    Text("当前版本 ${model.installedVersion}", fontSize = 16.sp)
                    Text(
                        "在这里检查和下载安卓正式版，下载完成后由系统确认安装。只有点击检查或下载时才会连接项目的 GitHub，不会自动在后台检查更新。",
                        fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    UpdateContent(model.state, model, canInstall,
                        onInstall = model::installVerified,
                        onAllowInstall = ::allowInstall,
                    )
                    TextButton(text = "返回", onClick = { finish() })
                }
            }
        }
    }

    private fun allowInstall() {
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }.onFailure {
            Toast.makeText(this, "请在系统设置的「安装未知应用」中允许困困输入法，再返回安装", Toast.LENGTH_LONG).show()
        }
    }

    private fun openInstaller(file: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            canInstall = false
            Toast.makeText(this, "请先允许安装来自此应用的更新，再点安装", Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("困困输入法更新", uri)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "无法打开系统安装界面，请确认允许安装来自此应用的更新", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun UpdateContent(
    state: UpdateState,
    model: UpdateViewModel,
    canInstall: Boolean,
    onInstall: () -> Unit,
    onAllowInstall: () -> Unit,
) {
    when (state) {
        UpdateState.Idle -> TextButton(text = "检查更新", onClick = model::check)
        UpdateState.Checking -> {
            Text("正在检查安卓正式版…")
            TextButton(text = "取消", onClick = model::cancel)
        }
        UpdateState.NoUpdate -> {
            Text("目前没有可安装的新安卓正式版。")
            TextButton(text = "重新检查", onClick = model::check)
        }
        is UpdateState.Available -> {
            ReleaseSummary(state.release)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "下载更新", onClick = { model.download(state.release) })
                TextButton(text = "重新检查", onClick = model::check)
            }
        }
        is UpdateState.Downloading -> {
            Text("正在下载 ${state.release.version.stableName}")
            Text("${megabytes(state.bytes)} / ${megabytes(state.total)}")
            DownloadProgress(state.bytes.toFloat() / state.total.coerceAtLeast(1))
            TextButton(text = "取消下载", onClick = model::cancel)
        }
        is UpdateState.Verifying -> {
            Text("正在核对安装包与签名…")
            TextButton(text = "取消", onClick = model::cancel)
        }
        is UpdateState.Ready -> {
            Text("${state.update.release.version.stableName} 已下载并通过校验", fontSize = 18.sp)
            Text("应用签名一致，可以覆盖升级并保留设置和词库。安装前会再次核对文件。", fontSize = 14.sp)
            if (canInstall) {
                TextButton(text = "安装更新", onClick = onInstall)
            } else {
                Text("首次更新需在系统里允许此应用安装更新；返回后再点「安装更新」。", fontSize = 14.sp)
                TextButton(text = "允许安装更新", onClick = onAllowInstall)
            }
        }
        is UpdateState.Failed -> {
            Text(state.message, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.release != null) TextButton(text = "重新下载", onClick = { model.download(state.release) })
                TextButton(text = "重新检查", onClick = model::check)
            }
        }
    }
}

@Composable
private fun ReleaseSummary(release: AndroidRelease) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("发现新版本 ${release.version.stableName}", fontSize = 20.sp)
            Text("ARM64 · ${megabytes(release.apk.size)}", fontSize = 14.sp)
            if (release.notes.isNotBlank()) {
                val readable = release.notes.lineSequence().joinToString("\n") { it.replace(Regex("^#{1,6}\\s+"), "") }
                    .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1").replace("`", "")
                Text(readable, fontSize = 14.sp, lineHeight = 22.sp)
            }
        }
    }
}

@Composable
private fun DownloadProgress(progress: Float) {
    val foreground = MiuixTheme.colorScheme.primary
    val background = foreground.copy(alpha = 0.15f)
    Canvas(modifier = Modifier.fillMaxWidth().height(6.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(background, cornerRadius = radius)
        drawRoundRect(foreground, size = Size(size.width * progress.coerceIn(0f, 1f), size.height), cornerRadius = radius)
    }
}

private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
