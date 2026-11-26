package com.windinput.ime.settings

import android.content.Context

/** Device-specific consent; excluded from backup so another phone asks again. */
object VoicePreferences {
    const val PREFS_NAME = "voice_input"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun configured(context: Context): Boolean = prefs(context).getBoolean("configured", false)
    fun allowSystemFallback(context: Context): Boolean = prefs(context).getBoolean("system_fallback", false)

    fun choose(context: Context, allowSystemFallback: Boolean) {
        prefs(context).edit().putBoolean("configured", true)
            .putBoolean("system_fallback", allowSystemFallback).apply()
    }
}
