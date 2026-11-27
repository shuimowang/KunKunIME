package com.windinput.ime.settings

import android.content.Context

enum class VoiceEngine(val storageValue: String) {
    LOCAL_MODEL("local_model"),
    SYSTEM_ON_DEVICE("system_on_device"),
    SYSTEM_ALLOWED("system_allowed"),
}

/** Preserve an explicit 0.1.0 choice without treating a default as cloud consent. */
internal fun restoredVoiceEngine(stored: String?, configured: Boolean, legacySystemFallback: Boolean): VoiceEngine {
    if (!configured) return VoiceEngine.LOCAL_MODEL
    if (stored != null) return VoiceEngine.entries.firstOrNull { it.storageValue == stored }
        ?: VoiceEngine.LOCAL_MODEL
    return if (legacySystemFallback) VoiceEngine.SYSTEM_ALLOWED else VoiceEngine.SYSTEM_ON_DEVICE
}

/** Device-specific consent; excluded from backup so another phone asks again. */
object VoicePreferences {
    const val PREFS_NAME = "voice_input"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun configured(context: Context): Boolean = prefs(context).getBoolean("configured", false)
    fun engine(context: Context): VoiceEngine = prefs(context).let {
        restoredVoiceEngine(
            it.getString("engine", null),
            it.getBoolean("configured", false),
            it.getBoolean("system_fallback", false),
        )
    }

    fun allowSystemFallback(context: Context): Boolean = engine(context) == VoiceEngine.SYSTEM_ALLOWED

    fun chooseLocal(context: Context) = chooseEngine(context, VoiceEngine.LOCAL_MODEL)

    /** Kept for existing callers and the system recognition IPC tests. */
    fun choose(context: Context, allowSystemFallback: Boolean) {
        chooseEngine(context, if (allowSystemFallback) VoiceEngine.SYSTEM_ALLOWED else VoiceEngine.SYSTEM_ON_DEVICE)
    }

    private fun chooseEngine(context: Context, engine: VoiceEngine) {
        prefs(context).edit()
            .putBoolean("configured", true)
            .putString("engine", engine.storageValue)
            .putBoolean("system_fallback", engine == VoiceEngine.SYSTEM_ALLOWED)
            .apply()
    }
}
