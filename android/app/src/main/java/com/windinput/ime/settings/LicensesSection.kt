package com.windinput.ime.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tomlj.Toml
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 一条第三方声明。格式见 assets/licenses/notices.toml 头部注释 */
data class Notice(
    val group: String,
    val name: String,
    val usage: String,
    val license: String,
    val url: String,
)

object Notices {
    /** assets 下的目录：notices.toml、各许可证全文（<id>.txt）、Rust 依赖清单 */
    const val DIR = "licenses"

    /** 原文件随共享许可目录复制；不另存一份省略版权或 NOTICE 的通用文本。 */
    private val OFFLINE_TEXTS = mapOf(
        "sherpa-onnx · Apache-2.0" to "sherpa-onnx-LICENSE.txt",
        "ONNX Runtime · MIT" to "onnxruntime-LICENSE.txt",
        "ONNX Runtime 第三方许可" to "onnxruntime-ThirdPartyNotices.txt",
        "原生依赖许可与声明" to "NATIVE-LICENSES.txt",
        "FunASR Model Open Source License Agreement v1.1" to "SenseVoice-FunASR-MODEL-LICENSE.txt",
        "SenseVoice 归档许可引用" to "SenseVoice-archive-LICENSE.txt",
        "Apache Commons · Apache-2.0 与 NOTICE" to "APACHE-COMMONS-LICENSES.txt",
        "离线语音来源与归属" to "SOURCES.md",
    )

    /** 有全文的许可证 id；共享数据和语音许可由 fullTextAsset 定位。 */
    val FULL_TEXTS = setOf("GPL-3.0", "LGPL-3.0", "Apache-2.0", "MIT", "MPL-2.0", "ISC", "Unicode-3.0", "CC-BY-SA-4.0", "CC-BY-4.0", "BSD-3-Clause", "CMUdict", "CC0-1.0") + OFFLINE_TEXTS.keys

    /** 共享数据许可由 prepare-assets.py 原样复制，避免另维护一份版权文本。 */
    fun fullTextAsset(id: String): String = OFFLINE_TEXTS[id]?.let { "data/licenses/offline-voice/$it" }
        ?: when (id) {
            "CMUdict", "CC0-1.0" -> "data/licenses/data/$id.txt"
            else -> "$DIR/$id.txt"
        }

    /** 政府发布的公开标准保留来源链接。 */
    val NO_TEXT = setOf("公开标准")

    fun parse(toml: String): List<Notice> {
        val result = Toml.parse(toml)
        require(!result.hasErrors()) { "TOML errors: ${result.errors()}" }
        val arr = result.getArray("notice") ?: return emptyList()
        return (0 until arr.size()).map { i ->
            val t = arr.getTable(i)
            val name = requireNotNull(t.getString("name")) { "notice 缺少 name" }
            Notice(
                group = t.getString("group") ?: "",
                name = name,
                usage = t.getString("usage") ?: "",
                license = requireNotNull(t.getString("license")) { "$name 缺少 license" },
                url = t.getString("url") ?: "",
            )
        }
    }
}

/**
 * 开源许可：随包分发的词库数据与程序库逐条列出（名称、用途、许可证、来源），
 * 点开许可证看全文；共享数据的完整来源声明随 data/licenses 分发。
 * Rust 依赖条目多，单独一张卡按行列出。
 */
@Composable
internal fun LicensesSection() {
    val context = LocalContext.current
    val notices = remember {
        runCatching {
            Notices.parse(context.assets.open("${Notices.DIR}/notices.toml").bufferedReader().use { it.readText() })
        }.getOrDefault(emptyList())
    }
    // 记「哪一组展开了哪份全文」：同一许可证出现在几组里（Apache-2.0 数据与程序库都有），
    // 只按许可证记的话点一下会在每组下面各铺一遍全文
    var openText by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showCrates by remember { mutableStateOf(false) }

    for ((group, items) in notices.groupBy { it.group }) {
        SectionTitle(group)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                for (n in items) {
                    val hasText = n.license in Notices.FULL_TEXTS
                    NavRow(
                        n.name,
                        "${n.usage}\n${n.license}${if (hasText) "（点开看全文）" else ""} · ${n.url}",
                        onClick = if (hasText) {
                            {
                                val key = group to n.license
                                openText = if (openText == key) null else key
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        val open = openText
        if (open != null && open.first == group) {
            LicenseText(assetText(Notices.fullTextAsset(open.second)))
        }
    }

    SectionTitle("Rust 依赖库")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            NavRow(
                if (showCrates) "收起" else "展开全部 Rust 依赖",
                "输入法核心链接的第三方 crate 及各自的许可证（MIT / Apache-2.0 / MPL-2.0 等）",
            ) { showCrates = !showCrates }
            if (showCrates) {
                for (id in listOf("MIT", "MPL-2.0", "Unicode-3.0")) {
                    NavRow("$id 全文", null) { openText = if (openText == RUST to id) null else RUST to id }
                }
                openText?.takeIf { it.first == RUST }?.let { LicenseText(assetText(Notices.fullTextAsset(it.second))) }
                LicenseText(
                    assetText("${Notices.DIR}/rust-crates.txt")
                        .lines()
                        .filterNot { it.startsWith("#") || it.isBlank() }
                        .joinToString("\n") { it.replace('\t', ' ') },
                )
            }
        }
    }
}

/** Rust 依赖卡在 [openText] 里的组名 */
private const val RUST = "rust"

@Composable
private fun assetText(path: String): String {
    val context = LocalContext.current
    return remember(path) {
        runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }
            .getOrDefault("（缺少 $path）")
    }
}

@Composable
private fun LicenseText(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
