package com.windinput.ime.smoke

/** Test-APK contract. Only debug's implementation can hold the real core. */
interface StartupHandoff : AutoCloseable {
    fun awaitBlocked(timeoutMs: Long): Boolean
}
