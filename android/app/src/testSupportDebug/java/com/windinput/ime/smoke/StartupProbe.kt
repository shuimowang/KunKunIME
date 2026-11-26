package com.windinput.ime.smoke

import com.windinput.ime.core.CoreStartupGate

object StartupProbe {
    fun hold(): StartupHandoff? {
        val hold = CoreStartupGate.holdForTest()
        return object : StartupHandoff {
            override fun awaitBlocked(timeoutMs: Long): Boolean = hold.awaitBlocked(timeoutMs)
            override fun close() = hold.close()
        }
    }
}
