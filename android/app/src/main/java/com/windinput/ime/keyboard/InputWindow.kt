package com.windinput.ime.keyboard

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * 输入窗口抽象：键盘、剪贴板、表情、文本编辑等面板都是可互换的 InputWindow，
 * 共用候选条下方的面板区（模式参考 fcitx5-android 的 InputWindowManager，
 * 见 docs/android-ime-features.md §10）。
 */
abstract class InputWindow {

    /**
     * 本窗口是否**保留上方条区**（而不是让位）。
     *
     * 默认 false：面板套 [com.windinput.ime.panels.PanelScaffold]，自带左上角返回，
     * 条区让位给它，前后总高不变。
     *
     * 展开候选面板是唯一的 true：它刻意不套脚手架（选中即返回，为它专设一行返回栏
     * 是浪费），返回入口就在条区尾格上。**曾经把它一并让位掉，结果是展开后无路可退**
     * ——这条属性就是为了让「我自带返回吗」由窗口自己声明，而不是靠一句
     * 「非键盘窗口一律让位」的一般化规则去猜。
     */
    open val keepsBar: Boolean get() = false

    /** 懒创建的视图（附着期间缓存，detach 不销毁） */
    private var view: View? = null

    fun view(context: Context): View =
        view ?: onCreateView(context).also { view = it }

    protected abstract fun onCreateView(context: Context): View

    /** 窗口进入面板区 */
    open fun onAttached() {}

    /** 窗口离开面板区 */
    open fun onDetached() {}
}

/**
 * 面板区窗口管理器：同一时刻只有一个窗口附着。
 * 非键盘窗口关闭后总是回到键盘（`showKeyboard`）。
 */
class InputWindowManager(
    private val container: FrameLayout,
    private val keyboardWindow: InputWindow,
) {
    var current: InputWindow = keyboardWindow
        private set

    /** 窗口切换监听（服务用于同步候选条/工具栏状态） */
    var onWindowChanged: ((InputWindow) -> Unit)? = null

    fun attachInitial() {
        container.removeAllViews()
        container.addView(keyboardWindow.view(container.context))
        keyboardWindow.onAttached()
        current = keyboardWindow
    }

    fun show(window: InputWindow) {
        if (window === current) return
        current.onDetached()
        container.removeAllViews()
        container.addView(window.view(container.context))
        window.onAttached()
        current = window
        onWindowChanged?.invoke(window)
    }

    fun showKeyboard() = show(keyboardWindow)

    val isKeyboardShown: Boolean get() = current === keyboardWindow
}
