package com.windinput.ime.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/** One app-wide store prevents the IME panel and its editor from overwriting each other. */
class QuickPhraseStore private constructor(context: Context) {
    private val file = File(context.filesDir, "quick_phrases.json")
    private val io = Executors.newSingleThreadExecutor { Thread(it, "quick-phrases-io") }
    private val main = Handler(Looper.getMainLooper())
    private val observers = linkedSetOf<() -> Unit>()
    private var entries = emptyList<QuickPhrase>()

    var loaded = false
        private set
    var busy = false
        private set
    var error: String? = null
        private set

    init { reload() }

    fun list(): List<QuickPhrase> = entries

    fun observe(observer: () -> Unit) { observers += observer; observer() }
    fun removeObserver(observer: () -> Unit) { observers -= observer }
    private fun changed() = observers.toList().forEach { it() }

    fun reload() {
        if (busy) return
        busy = true
        error = null
        changed()
        io.execute {
            val result = runCatching { QuickPhrases.read(file) }
            main.post {
                busy = false
                loaded = result.isSuccess
                if (result.isSuccess) entries = result.getOrThrow()
                else error = "常用语读取失败，原有文件没有改动，请重试"
                changed()
            }
        }
    }

    fun save(id: String?, text: String, onDone: (String?) -> Unit) =
        modify(onDone) { QuickPhrases.upsert(it, id, text) }

    fun remove(id: String, onDone: (String?) -> Unit) = modify(onDone) { list -> list.filter { it.id != id } }

    fun moveToTop(id: String, onDone: (String?) -> Unit) = modify(onDone) { list ->
        list.firstOrNull { it.id == id }?.let { first -> listOf(first) + list.filter { it.id != id } } ?: list
    }

    private fun modify(onDone: (String?) -> Unit, transform: (List<QuickPhrase>) -> List<QuickPhrase>) {
        if (!loaded || busy) {
            onDone(error ?: "常用语正在读取或保存，请稍后再试")
            return
        }
        val next = try { transform(entries) } catch (problem: IllegalArgumentException) {
            onDone(problem.message ?: "常用语内容无效")
            return
        }
        busy = true
        changed()
        io.execute {
            val result = runCatching { QuickPhrases.write(file, next) }
            main.post {
                if (result.isSuccess) entries = next
                busy = false
                changed()
                onDone(if (result.isSuccess) null else "保存失败，请检查手机可用空间后重试；原有常用语没有改动")
            }
        }
    }

    companion object {
        @Volatile private var instance: QuickPhraseStore? = null
        fun get(context: Context): QuickPhraseStore = instance ?: synchronized(this) {
            instance ?: QuickPhraseStore(context.applicationContext).also { instance = it }
        }
    }
}
