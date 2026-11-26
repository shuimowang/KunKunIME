package com.windinput.ime.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModelProvider
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 文件关联入口：在文件管理器 / 微信 / 浏览器里点 `.wpkg` / `.zip`（VIEW），或用「分享」发给本应用（SEND）。
 *
 * 只是个透明壳：把 content:// 交给 [SchemeImportController]（ViewModel，转屏不丢）复制进缓存目录后
 * 走预览 / 导入，界面与设置页「导入方案」是同一套 [SchemeImportDialogs]，流程结束就 finish。
 * `.zip` 不一定是方案包——核心预览不认时会如实报「不是有效的方案包」，不会装进任何东西。
 */
class SchemeImportActivity : ComponentActivity() {

    private lateinit var controller: SchemeImportController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = ViewModelProvider(this)[SchemeImportController::class.java]
        // 转屏重建（savedInstanceState != null）时流程状态在 ViewModel 里，不重复开始
        if (savedInstanceState == null) {
            val uri = sourceUri(intent)
            if (uri == null) {
                finish()
                return
            }
            controller.begin(uri)
        }

        val theme = ThemeController(ColorSchemeMode.System)
        setContent {
            MiuixTheme(controller = theme) {
                SchemeImportDialogs(controller)
                LaunchedEffect(controller.state) {
                    if (controller.state is SchemeImportState.Idle) finish()
                }
            }
        }
    }

    /** singleTop：导入进行中又来了第二个文件——明说忙，不静默丢弃 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = sourceUri(intent) ?: return
        when (controller.state) {
            // 上一个已经出结果：先收掉，再处理新文件
            is SchemeImportState.Done, is SchemeImportState.Failed -> {
                controller.dismiss()
                controller.begin(uri)
            }
            is SchemeImportState.Idle -> controller.begin(uri)
            is SchemeImportState.Working ->
                Toast.makeText(this, "正在处理上一个方案包，请稍候再试", Toast.LENGTH_LONG).show()
            is SchemeImportState.Preview ->
                Toast.makeText(this, "请先对上一个方案包选择「导入」或「取消」", Toast.LENGTH_LONG).show()
        }
    }

    private fun sourceUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> null
    }
}
