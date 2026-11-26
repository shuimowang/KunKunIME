package com.windinput.ime.keyboard

import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat

/** Keep Android's keyboard-switch and dismiss buttons legible against the keyboard background. */
internal object KeyboardSystemBars {
    @Suppress("DEPRECATION")
    fun apply(window: Window, palette: Palette) {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        // Older Android versions paint the navigation bar themselves. Edge-to-edge versions
        // use InputRootView's matching background underneath the system buttons instead.
        window.navigationBarColor = palette.background
        if (Build.VERSION.SDK_INT >= 28) {
            window.navigationBarDividerColor = palette.background
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars =
            ColorUtils.calculateLuminance(palette.background) > 0.5
    }
}
