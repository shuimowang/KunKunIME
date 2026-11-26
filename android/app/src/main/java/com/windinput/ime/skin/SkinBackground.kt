package com.windinput.ime.skin

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable

/**
 * 键盘整体的背景（docs/mobile-skin-design.md §3.4）。
 *
 * 阶段 0 的目的只是**收口**：根视图（`InputRootView`）从此只画这一个 Drawable，将来的渐变 / 图片
 * 背景换这里的实现即可，不用再到各子视图里找 `setBackgroundColor`。现在只有纯色，且子视图仍各自刷实色，
 * 所以外观与行为不变。
 */
sealed interface SkinBackground {
    fun toDrawable(): Drawable

    data class Solid(val color: Int) : SkinBackground {
        override fun toDrawable(): Drawable = ColorDrawable(color)
    }
}
