package com.windinput.ime.core

/** This optional background index does not prevent typing; an Android toast obscures the keys. */
internal fun isBackgroundIndexNotice(text: String): Boolean = text == "正在建立词库索引…"
