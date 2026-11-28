package com.windinput.ime.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.util.Locale

@Composable
internal fun UpdateSection(model: UpdateViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(false) }
    var canInstall by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canInstall = context.packageManager.canRequestPackageInstalls()
                resumed = true
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                resumed = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(model.installerRequest, resumed) {
        if (resumed) model.takeInstallerRequest()?.let { file ->
            canInstall = context.packageManager.canRequestPackageInstalls()
            if (canInstall) context.openInstaller(file)
            else Toast.makeText(context, "请先允许安装来自此应用的更新，再点安装", Toast.LENGTH_LONG).show()
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            UpdateContent(model.state, model, canInstall, model::installVerified, context::allowInstall)
        }
    }
}

private fun Context.allowInstall() {
    runCatching {
        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
    }.onFailure {
        Toast.makeText(this, "请在系统设置的「安装未知应用」中允许困困输入法，再返回安装", Toast.LENGTH_LONG).show()
    }
}

private fun Context.openInstaller(file: File) {
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
            val release = state.release
            Text("发现新版本 ${release.version.stableName}", fontSize = 20.sp)
            Text("ARM64 · ${megabytes(release.apk.size)}", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = "下载更新", onClick = { model.download(release) })
                TextButton(text = "重新检查", onClick = model::check)
            }
            if (release.notes.isNotBlank()) {
                var expanded by remember(release) { mutableStateOf(false) }
                TextButton(text = if (expanded) "收起更新说明" else "查看更新说明", onClick = { expanded = !expanded })
                if (expanded) {
                    val readable = release.notes.lineSequence().joinToString("\n") { it.replace(Regex("^#{1,6}\\s+"), "") }
                        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1").replace("`", "")
                    Text(readable, fontSize = 14.sp, lineHeight = 22.sp)
                }
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
