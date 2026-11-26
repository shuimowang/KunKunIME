package com.windinput.ime.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** In-process instrumentation hook. No setting, component or external intent can enable it. */
object CoreStartupGate {
    private const val MAX_WAIT_SECONDS = 60L

    @Volatile
    private var hold: Hold? = null

    @Synchronized
    fun holdForTest(): Hold {
        check(hold == null) { "A startup handoff is already held" }
        return Hold().also { hold = it }
    }

    /** Called on the constructor thread, after the real Rust core has been obtained. */
    fun awaitHandoff() {
        val current = hold ?: return
        current.blocked.countDown()
        check(current.released.await(MAX_WAIT_SECONDS, TimeUnit.SECONDS)) {
            "The instrumentation startup handoff was not released within $MAX_WAIT_SECONDS seconds"
        }
    }

    class Hold internal constructor() : AutoCloseable {
        internal val blocked = CountDownLatch(1)
        internal val released = CountDownLatch(1)

        fun awaitBlocked(timeoutMs: Long): Boolean = blocked.await(timeoutMs, TimeUnit.MILLISECONDS)

        override fun close() {
            synchronized(CoreStartupGate) {
                if (CoreStartupGate.hold === this) CoreStartupGate.hold = null
                released.countDown()
            }
        }
    }
}
