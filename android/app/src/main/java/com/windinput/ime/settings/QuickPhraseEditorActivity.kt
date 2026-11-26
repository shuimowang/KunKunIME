package com.windinput.ime.settings

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import com.windinput.ime.core.QuickPhraseStore
import com.windinput.ime.core.QuickPhrases
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** A real text editor Activity avoids stealing the host editor's IME connection. */
class QuickPhraseEditorActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ID = "phrase_id"
        const val EXTRA_TEXT = "phrase_text"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[QuickPhraseEditorModel::class.java]
        model.begin(intent.getStringExtra(EXTRA_ID), intent.getStringExtra(EXTRA_TEXT).orEmpty())
        val theme = ThemeController(ColorSchemeMode.System)
        setContent {
            MiuixTheme(controller = theme) {
                LaunchedEffect(model.saved) { if (model.saved) finish() }
                Column(
                    modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)
                        .safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(if (model.editing) "编辑常用语" else "新增常用语", fontSize = 24.sp)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(text = "取消", enabled = !model.busy, onClick = { finish() })
                        TextButton(text = if (model.busy) "正在保存…" else "保存", enabled = model.canSave, onClick = model::save)
                    }
                    TextField(
                        value = model.text,
                        onValueChange = model::changeText,
                        label = "填写回复、地址或其他常用文字",
                        enabled = !model.busy && !model.saved,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${model.text.length} / ${QuickPhrases.MAX_LENGTH} 字", fontSize = 13.sp)
                    Text("保存后会固定在「剪贴板 → 常用语」。点击短语填入输入框，再由你发送。", fontSize = 14.sp)
                    model.error?.let { Text(it, fontSize = 14.sp) }
                    if (!model.ready && !model.busy) TextButton(text = "重新读取", onClick = model::reload)
                }
            }
        }
    }
}

class QuickPhraseEditorModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val store = QuickPhraseStore.get(application)
    private var started = false
    private var id: String? = null
    private var draftReady = savedState.get<Boolean>("draft_ready") == true
    private val observer: () -> Unit = ::refresh

    var text by mutableStateOf(savedState.get<String>("text").orEmpty())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var ready by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var saved by mutableStateOf(false)
        private set
    val editing get() = id != null
    val canSave get() = ready && !busy && !saved && text.isNotBlank() && text.length <= QuickPhrases.MAX_LENGTH

    fun begin(id: String?, prefill: String) {
        if (started) return
        started = true
        this.id = id
        if (!draftReady && id == null) setDraft(prefill)
        store.observe(observer)
    }

    private fun refresh() {
        busy = store.busy
        ready = store.loaded
        error = store.error
        if (ready && id != null && store.list().none { it.id == id }) {
            ready = false
            error = "这条常用语已被删除，请返回后重新添加"
        }
        if (ready && !draftReady) setDraft(store.list().first { it.id == id }.text)
    }

    private fun setDraft(value: String) {
        text = value
        draftReady = true
        savedState["text"] = value
        savedState["draft_ready"] = true
    }

    fun changeText(value: String) {
        if (busy || saved) return
        if (value.length > QuickPhrases.MAX_LENGTH) {
            error = "每条常用语最多 ${QuickPhrases.MAX_LENGTH} 字，请缩短内容后再粘贴"
            return
        }
        setDraft(value)
        error = null
    }

    fun reload() = store.reload()

    fun save() {
        if (!canSave) return
        store.save(id, text) { problem ->
            error = problem
            if (problem == null) saved = true
        }
    }

    override fun onCleared() {
        store.removeObserver(observer)
        super.onCleared()
    }
}
