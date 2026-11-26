package com.windinput.ime.settings

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Send
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 输入测试台：**手动试打 + 自动化测试共用**。做成聊天界面的样子：底部输入栏、发出去的
 * 内容进上面的对话气泡——输入法最常见的使用场景就是聊天框，在这里试最接近真实手感。
 *
 * # 为什么只有一个输入框
 *
 * 曾经做成「普通/搜索/数字/密码」四个框并排，结果**破坏了自动化的确定性**：
 * Activity 从后台回到前台时，Android 恢复的是**上次的焦点**而不是
 * `requestFocus()` 指定的那个；脚本点击目标框后 `InputConnection` 的重建还有延迟。
 * 于是「操作后再打字」类用例读到的是空框，看起来像功能坏了——实际字母进了另一个框。
 *
 * 现在是**单框 + 顶部场景分段**：框只有一个，焦点无处可跑；场景（`inputType` / `imeOptions`）
 * 由分段切换。输入框仍是原生 [EditText]：场景要精确控制 `inputType` 的 variation
 * （可见密码、句首大写），脚本也按它的 `content-desc` 定位、读文本。
 */
class TestPadActivity : ComponentActivity() {

    private data class Scene(val tab: String, val hint: String, val inputType: Int, val imeOptions: Int)

    /** 顺序即分段顺序。脚本按分段文字点（scripts/emu-test.sh 的 tap_text） */
    private val scenes = listOf(
        Scene("普通", "多行文本，回车换行", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_UNSPECIFIED),
        Scene("搜索", "回车键应显示「搜索」", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH),
        Scene("数字", "应直接调起九宫格数字键盘", InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_UNSPECIFIED),
        // 用「可见密码」：同样走直输分支，但字不变成圆点，脚本读得出来
        Scene("密码", "可见密码：应强制英文直输、不学习", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, EditorInfo.IME_ACTION_DONE),
        // 聊天类应用的输入框大多带 CAP_SENTENCES：英文句首自动大写只在这种框里生效
        Scene("句子", "英文句首应自动大写", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_UNSPECIFIED),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val theme = ThemeController(ColorSchemeMode.System)
        setContent {
            MiuixTheme(controller = theme) { TestPad() }
        }
    }

    @Composable
    private fun TestPad() {
        var sceneIndex by rememberSaveable { mutableIntStateOf(0) }
        val messages = remember { mutableStateListOf<String>() }
        val listState = rememberLazyListState()
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
        }
        val colors = MiuixTheme.colorScheme
        val scene = scenes[sceneIndex]

        Scaffold(
            topBar = {
                SmallTopAppBar(
                    title = "试一试",
                    navigationIcon = {
                        IconButton(onClick = { finish() }, modifier = Modifier.padding(start = 12.dp)) {
                            Icon(MiuixIcons.Back, contentDescription = "返回")
                        }
                    },
                )
            },
        ) { padding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
                    .imePadding()
                    .navigationBarsPadding(),
            ) {
            // 横屏弹出键盘后只剩一百多 dp：场景页签与说明让位，否则输入框被挤出屏外、看不见在打什么
            val roomy = maxHeight >= 200.dp
            Column(modifier = Modifier.fillMaxSize()) {
                if (roomy) {
                    TabRowWithContour(
                        tabs = scenes.map { it.tab },
                        selectedTabIndex = sceneIndex,
                        onTabSelected = { sceneIndex = it },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    Text(
                        text = scene.hint,
                        fontSize = 13.sp,
                        color = colors.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
                ) {
                    if (messages.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "在下面试打，点发送就像发一条消息",
                                    fontSize = 13.sp,
                                    color = colors.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                    items(messages) { Bubble(it) }
                }
                InputBar(scene) { text -> if (text.isNotBlank()) messages += text }
            }
            }
        }
    }

    @Composable
    private fun Bubble(text: String) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
            Text(
                text = text,
                color = MiuixTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .background(MiuixTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }

    /** 底部输入栏：原生输入框 + 发送。发送 = 把框里的话放进对话、清空框 */
    @Composable
    private fun InputBar(scene: Scene, onSend: (String) -> Unit) {
        val colors = MiuixTheme.colorScheme
        val fieldBg = colors.secondaryContainer.toArgb()
        val textColor = colors.onSurface.toArgb()
        val hintColor = colors.onSurfaceVariantSummary.toArgb()
        var field by remember { mutableStateOf<EditText?>(null) }
        fun send() {
            val f = field ?: return
            onSend(f.text.toString())
            f.text.clear()
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            AndroidView(
                modifier = Modifier.weight(1f),
                factory = { ctx ->
                    EditText(ctx).apply {
                        // 脚本按 content-desc 定位（代码设的 id 在 uiautomator dump 里不可见）
                        contentDescription = TEST_PAD_DESC
                        hint = "测试输入框"
                        // 内容一变就写一行日志：测试脚本读日志拿输入框内容（几十毫秒），不必每次
                        // 抓整个界面（uiautomator dump，一次 0.2–3 秒，全量一百多次）
                        logText("")
                        addTextChangedListener(object : android.text.TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                            override fun afterTextChanged(s: android.text.Editable?) = logText(s?.toString().orEmpty())
                        })
                        gravity = Gravity.CENTER_VERTICAL or Gravity.START
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                        minHeight = (44 * resources.displayMetrics.density).toInt()
                        maxLines = 5
                        val pad = (14 * resources.displayMetrics.density).toInt()
                        setPadding(pad, pad / 2, pad, pad / 2)
                        // 搜索 / 完成键当发送
                        setOnEditorActionListener { _, actionId, _ ->
                            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                                send()
                                true
                            } else {
                                false
                            }
                        }
                        field = this
                    }
                },
                update = { f ->
                    f.background = GradientDrawable().apply {
                        cornerRadius = 22 * f.resources.displayMetrics.density
                        setColor(fieldBg)
                    }
                    f.setTextColor(textColor)
                    f.setHintTextColor(hintColor)
                    // 换场景要重启输入连接，否则输入法拿到的还是旧的 EditorInfo
                    if (f.inputType != scene.inputType || f.imeOptions != scene.imeOptions) {
                        f.inputType = scene.inputType
                        f.imeOptions = scene.imeOptions
                        f.requestFocus()
                        getSystemService(InputMethodManager::class.java)?.restartInput(f)
                    }
                },
            )
            IconButton(onClick = { send() }, modifier = Modifier.padding(start = 4.dp)) {
                Icon(MiuixIcons.Send, contentDescription = "发送", tint = colors.primary)
            }
        }
        LaunchedEffect(Unit) { field?.requestFocus() }
    }

    companion object {
        /** 与 scripts/emu-test.sh 约定的定位锚点 */
        const val TEST_PAD_DESC = "windinput-testpad"

        /** 与 scripts/emu-test.sh 的 get_pad_text 约定：`text=` 后接内容，反斜杠与换行转义 */
        private const val LOG_TAG = "WindTestPad"

        private fun logText(text: String) {
            android.util.Log.i(LOG_TAG, "text=" + text.replace("\\", "\\\\").replace("\n", "\\n"))
        }
    }
}
