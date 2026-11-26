package com.windinput.ime.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 文字项：标题 + 说明 + 一个输入框。编辑只改本地草稿，点「保存」才写（打字过程中每个字符都写配置没有意义，
 * 也会让核心反复重载）。草稿与当前值不同时才出现「保存 / 还原」；核心拒绝（如配对不是两个字符）时把原因贴在框下，草稿保留供修改。
 *
 * 列表型配置（配对字符、键名）在框里是空格分隔的一行，见 [SettingsValues.text]。
 *
 * @param onCommit 写入并返回错误说明（成功为 null）；在协程里调用，内部自己切线程
 */
@Composable
internal fun TextRow(item: SettingItem, value: String, onCommit: suspend (String) -> String?) {
    val scope = rememberCoroutineScope()
    var draft by remember(value) { mutableStateOf(value) }
    var error by remember(value) { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = item.title)
        item.summary?.let {
            Text(text = it, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        TextField(
            value = draft,
            onValueChange = {
                draft = it
                error = null
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        error?.let { Text(text = it, color = MiuixTheme.colorScheme.error) }
        if (draft != value) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(text = "还原", modifier = Modifier.weight(1f), onClick = {
                    draft = value
                    error = null
                })
                TextButton(text = "保存", modifier = Modifier.weight(1f), onClick = {
                    scope.launch { error = onCommit(draft) }
                })
            }
        }
    }
}
