package com.windinput.ime.keyboard

import android.content.Context
import android.view.View

/** 键盘主窗口：包装 KeyboardView 进 InputWindow 体系 */
class KeyboardWindow(
    private val listener: KeyboardView.Listener,
    private val layoutProvider: (KeyboardLayer) -> List<List<KeyDef>>,
) : InputWindow() {

    var keyboardView: KeyboardView? = null
        private set

    override fun onCreateView(context: Context): View =
        KeyboardView(context).also {
            it.listener = listener
            it.layoutProvider = layoutProvider
            keyboardView = it
        }
}
