package com.windinput.ime.panels

import com.windinput.ime.keyboard.setHapticClick
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.windinput.ime.core.ClipboardStore
import com.windinput.ime.core.QuickPhrase
import com.windinput.ime.core.QuickPhraseStore
import com.windinput.ime.core.QuickPhrases
import com.windinput.ime.keyboard.InputWindow
import com.windinput.ime.keyboard.Palette
import com.windinput.ime.keyboard.PanelIcon
import com.windinput.ime.keyboard.KeyboardDimens
import com.windinput.ime.settings.QuickPhraseEditorActivity

/**
 * 剪贴板与持久保存的常用语共用面板，顶部胶囊切换；点击只填入宿主，不发送。
 */
class ClipboardWindow(
    private val store: ClipboardStore,
    private val listener: Listener,
) : InputWindow() {

    interface Listener {
        fun onPasteText(text: String)
        fun onBackToKeyboard()
    }

    private var listContainer: LinearLayout? = null
    private var scrollContainer: ScrollView? = null
    private var phraseStore: QuickPhraseStore? = null
    private var showsPhrases = false
    private var attached = false
    private var clipboardTab: TextView? = null
    private var phrasesTab: TextView? = null
    private var headerAction: TextView? = null
    private val phraseObserver: () -> Unit = { if (attached) refresh() }

    override fun onCreateView(context: Context): View {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        listContainer = list
        phraseStore = QuickPhraseStore.get(context)
        val scroll = ScrollView(context).apply { addView(list); isFillViewport = true }
        scrollContainer = scroll
        headerAction = PanelScaffold.headerButton(context, "清空") {
            if (showsPhrases) openEditor(context) else store.clearUnpinned()
        }.apply { minWidth = dp(context, 52) }
        return PanelScaffold.build(
            context = context,
            title = "剪贴板",
            onBack = listener::onBackToKeyboard,
            content = scroll,
            rightView = headerAction,
            titleView = tabs(context),
        ).also { updateTabs() }
    }

    override fun onAttached() {
        attached = true
        store.onChanged = { if (!showsPhrases) listContainer?.post { if (attached) refresh() } }
        phraseStore?.observe(phraseObserver)
        refresh()
    }

    override fun onDetached() {
        attached = false
        store.onChanged = null
        phraseStore?.removeObserver(phraseObserver)
    }

    private fun refresh() {
        updateTabs()
        if (showsPhrases) refreshPhrases() else refreshClipboard()
    }

    private fun refreshClipboard() {
        val list = listContainer ?: return
        val context = list.context
        val density = context.resources.displayMetrics.density
        list.removeAllViews()

        val entries = store.list()
        if (entries.isEmpty()) {
            list.addView(
                TextView(context).apply {
                    text = "复制的内容会出现在这里"
                    gravity = Gravity.CENTER
                    setTextColor(Palette.current.hint)
                    setPadding(0, (48 * density).toInt(), 0, 0)
                },
            )
            return
        }

        for (entry in entries) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(context, Palette.current.key, 16f)
            }
            row.addView(
                TextView(context).apply {
                    text = entry.text
                    textSize = 16f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Palette.current.text)
                    val p = (10 * density).toInt()
                    setPadding(p, p, p, p)
                    setHapticClick { listener.onPasteText(entry.text) }
                    setOnLongClickListener { anchor ->
                        PopupMenu(context, anchor).apply {
                            menu.add("保存为常用语")
                            setOnMenuItemClickListener { openEditor(context, text = entry.text); true }
                            show()
                        }
                        true
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(
                actionButton(context, if (entry.pinned) PanelIcon.Glyph.PIN else PanelIcon.Glyph.PIN_OUTLINE) {
                    store.setPinned(entry.text, !entry.pinned)
                },
                LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt()),
            )
            row.addView(
                actionButton(context, PanelIcon.Glyph.CLOSE) { store.remove(entry.text) },
                LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt()),
            )

            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.setMargins(dp(context, 12), dp(context, 8), dp(context, 12), 0)
            list.addView(row, lp)
        }
    }

    private fun tabs(context: Context): View {
        val capsule = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            background = rounded(context, Palette.current.key, 28f)
            setPadding(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3))
        }
        fun tab(label: String, phrases: Boolean) = TextView(context).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            minWidth = dp(context, 78)
            contentDescription = "切换到$label"
            setPadding(dp(context, 8), 0, dp(context, 8), 0)
            setHapticClick {
                showsPhrases = phrases
                scrollContainer?.scrollTo(0, 0)
                refresh()
            }
        }
        clipboardTab = tab("剪贴板", false)
        phrasesTab = tab("常用语", true)
        capsule.addView(clipboardTab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        capsule.addView(phrasesTab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        return object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                capsule.layoutParams.width = minOf(dp(context, 180), View.MeasureSpec.getSize(widthMeasureSpec))
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        }.apply {
            addView(capsule, FrameLayout.LayoutParams(
                dp(context, 180),
                minOf(dp(context, 48), KeyboardDimens.barHeightPx(context.resources.displayMetrics.density) - dp(context, 4)),
                Gravity.CENTER,
            ))
        }
    }

    private fun updateTabs() {
        listOf(clipboardTab to !showsPhrases, phrasesTab to showsPhrases).forEach { (view, selected) ->
            view?.apply {
                isSelected = selected
                setTextColor(Palette.current.text)
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                background = if (selected) rounded(context, Palette.current.keySpecial, 24f) else null
            }
        }
        headerAction?.apply {
            text = if (showsPhrases) "＋" else "清空"
            textSize = if (showsPhrases) 26f else 14f
            contentDescription = if (showsPhrases) "新增常用语" else "清空未置顶的剪贴板记录"
            isEnabled = !showsPhrases || phraseStore?.let { it.loaded && !it.busy } == true
            alpha = if (isEnabled) 1f else 0.45f
        }
    }

    private fun refreshPhrases() {
        val list = listContainer ?: return
        val context = list.context
        val phrases = phraseStore ?: return
        list.removeAllViews()
        if (!phrases.loaded || phrases.list().isEmpty()) {
            list.addView(TextView(context).apply {
                text = phrases.error ?: if (phrases.loaded) "点右上角 ＋ 添加常用语" else "正在读取常用语…"
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(Palette.current.hint)
                setPadding(dp(context, 12), dp(context, 36), dp(context, 12), 0)
                if (phrases.error != null) setHapticClick { phrases.reload() }
            })
            return
        }
        for (phrase in phrases.list()) {
            val card = TextView(context).apply {
                text = phrase.text
                textSize = 16f
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                minHeight = dp(context, 58)
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Palette.current.text)
                background = rounded(context, Palette.current.key, 18f)
                setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
                setHapticClick { listener.onPasteText(phrase.text) }
                setOnLongClickListener { phraseMenu(it, phrase); true }
            }
            list.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(context, 12), dp(context, 8), dp(context, 12), 0) })
        }
        list.addView(TextView(context).apply {
            text = "长按可编辑、移到顶部或删除"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Palette.current.hint)
            setPadding(0, dp(context, 12), 0, dp(context, 12))
        })
    }

    private fun phraseMenu(anchor: View, phrase: QuickPhrase) {
        val phrases = phraseStore ?: return
        PopupMenu(anchor.context, anchor).apply {
            menu.add(0, 1, 0, "编辑")
            menu.add(0, 2, 1, "移到顶部")
            menu.add(0, 3, 2, "删除")
            setOnMenuItemClickListener { item ->
                val done: (String?) -> Unit = { error ->
                    Toast.makeText(anchor.context, error ?: "已保存", Toast.LENGTH_SHORT).show()
                }
                when (item.itemId) {
                    1 -> openEditor(anchor.context, phrase.id)
                    2 -> phrases.moveToTop(phrase.id, done)
                    3 -> phrases.remove(phrase.id, done)
                }
                true
            }
            show()
        }
    }

    private fun openEditor(context: Context, id: String? = null, text: String = "") {
        if (text.length > QuickPhrases.MAX_LENGTH) {
            Toast.makeText(context, "常用语最多 ${QuickPhrases.MAX_LENGTH} 字，请先缩短内容", Toast.LENGTH_LONG).show()
            return
        }
        context.startActivity(Intent(context, QuickPhraseEditorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(QuickPhraseEditorActivity.EXTRA_ID, id)
            .putExtra(QuickPhraseEditorActivity.EXTRA_TEXT, text))
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun rounded(context: Context, color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * context.resources.displayMetrics.density
    }

    /** 条目右侧的小按钮（置顶 / 删除）：矢量图标，与键盘其他功能图标同一套 */
    private fun actionButton(context: Context, glyph: PanelIcon.Glyph, onClick: () -> Unit): PanelIcon =
        PanelIcon(context).apply {
            this.glyph = glyph
            tint = if (glyph == PanelIcon.Glyph.PIN) Palette.current.accentText else Palette.current.hint
            scale = 0.5f
            setHapticClick { onClick() }
        }
}
