package com.windinput.ime.core

/** Release builds never hold startup delivery and expose no test control. */
internal object CoreStartupGate {
    fun awaitHandoff() = Unit
}
