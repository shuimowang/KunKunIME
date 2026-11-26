package com.windinput.ime.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import uniffi.wind_android.EmojiModuleInfo
import uniffi.wind_android.EngineCommand

/**
 * 输入页「Emoji/OpenCC」卡片下的「附加模块」：白霜的 emoji / 偏旁 / 希腊字母等表，逐个开关。
 *
 * 条数不定（用户能往 `emoji/` 目录放自己的 OpenCC 表），所以不进设置清单，列表由核心给。
 * 标题照抄电脑版的「附加模块」；电脑版是对话框，手机上直接摊开成一张卡。
 */
@Composable
internal fun EmojiModulesSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var modules by remember { mutableStateOf<List<EmojiModuleInfo>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        modules = withContext(Dispatchers.IO) {
            runCatching { CoreAccess.obtainCore(context)?.emojiModules() }.getOrNull().orEmpty()
        }
    }
    if (modules.isEmpty()) return

    SectionTitle("附加模块")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            for (m in modules) {
                ToggleLine(
                    title = if (m.user) "${m.label}（自己放的表）" else m.label,
                    summary = m.description.ifEmpty { null },
                    checked = m.enabled,
                ) { on ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val core = CoreAccess.obtainCore(context) ?: return@withContext
                            runCatching {
                                core.setEmojiModule(m.id, on)
                                core.runCommand(EngineCommand.ReloadConfig)
                            }
                        }
                        reload++
                    }
                }
            }
        }
    }
}
