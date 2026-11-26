package com.windinput.ime.keyboard

import android.view.Window
import android.view.WindowManager
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat

/** Keep Android's keyboard-switch and dismiss buttons legible against the keyboard background. */
internal object KeyboardSystemBars {
    @Suppress("DEPRECATION")
    fun apply(window: Window, palette: Palette) {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        // Match the navigation surface to InputRootView's background under the system buttons.
        window.navigationBarColor = palette.background
        window.navigationBarDividerColor = palette.background
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars =
            ColorUtils.calculateLuminance(palette.background) > 0.5
    }
}
