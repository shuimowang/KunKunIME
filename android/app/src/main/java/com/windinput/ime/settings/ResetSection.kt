package com.windinput.ime.settings

import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.windinput.ime.WindInputApp
import com.windinput.ime.core.UserConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import uniffi.wind_android.EngineCommand
import java.io.File

/** 两档恢复（哪些键属于哪档见 [SettingsReset]） */
private enum class ResetTier(val title: String, val summary: String) {
    KEYBOARD("恢复键盘设置", "键盘高度、振动与按键音、手势、工具栏等键盘侧设置回到出厂值"),
    ENGINE("恢复输入设置", "方案页、输入页、外观等输入设置回到出厂值；当前方案与启用的方案保留"),
}

/**
 * 「高级」页底部的恢复默认设置。用户把配置改乱了，此前只能清数据，词库也跟着丢。
 * 两档分开恢复，都不动词库、方案覆盖、扩展词库与导入的方案。确认方式同「清空个人词」：点两下。
 */
@Composable
internal fun ResetSection(values: SettingsValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf<ResetTier?>(null) }
    var confirmAt by remember { mutableLongStateOf(0L) }
    confirming?.let {
        LaunchedEffect(it, confirmAt) {
            delay(CONFIRM_RESET_MS)
            confirming = null
        }
    }

    SectionTitle("恢复默认")
    Card(modifier = Modifier.fillMaxWidth()) {
        for (tier in ResetTier.entries) {
            val armed = confirming == tier
            NavRow(
                if (armed) "再点一次确认${tier.title}" else tier.title,
                if (armed) "${tier.summary}。词库不受影响" else tier.summary,
                icon = SettingsIcons.reset,
                highlight = armed,
            ) {
                val now = SystemClock.uptimeMillis()
                if (!armed) {
                    confirming = tier
                    confirmAt = now
                    return@NavRow
                }
                if (now - confirmAt < CONFIRM_MIN_GAP_MS) return@NavRow
                confirming = null
                scope.launch {
                    val message = withContext(Dispatchers.IO) { runReset(context, tier, values) }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

private fun runReset(context: android.content.Context, tier: ResetTier, values: SettingsValues): String = try {
    when (tier) {
        ResetTier.KEYBOARD -> {
            val n = SettingsReset.resetKeyboard(UserConfig((context.applicationContext as WindInputApp).dataDirs.keyboardConfigFile))
            values.touch()
            if (n == 0) "键盘设置已是出厂值" else "已恢复 $n 项键盘设置，下次弹出键盘时生效"
        }
        ResetTier.ENGINE -> {
            val n = SettingsReset.resetEngine((context.applicationContext as WindInputApp).dataDirs.engineConfigFile)
            // 核心没起来也无妨：文件已改，下次构造时读到的就是出厂值
            CoreAccess.obtainCore(context)?.runCommand(EngineCommand.ReloadConfig)
            values.load()
            values.touch()
            if (n == 0) "输入设置已是出厂值" else "已恢复 $n 项输入设置"
        }
    }
} catch (e: Exception) {
    Log.w("ResetSection", "恢复默认设置失败：$tier", e)
    "恢复失败：${e.message ?: e}"
}
