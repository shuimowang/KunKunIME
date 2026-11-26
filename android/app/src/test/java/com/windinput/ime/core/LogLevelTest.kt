package com.windinput.ime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogLevelTest {

    @Test
    fun userLayerWinsOverFactory() {
        assertEquals("debug", LogLevel.resolve("debug", "warn"))
    }

    @Test
    fun factoryUsedWhenUserUnset() {
        assertEquals("error", LogLevel.resolve(null, "error"))
    }

    @Test
    fun unknownValuesFallBackToWarn() {
        assertEquals("warn", LogLevel.resolve("verbose", "loud"))
        assertEquals("warn", LogLevel.resolve(null, null))
    }

    @Test
    fun onlyDebugAndTraceAreVerbose() {
        assertTrue(LogLevel.verbose("debug"))
        assertTrue(LogLevel.verbose("trace"))
        assertFalse(LogLevel.verbose("info"))
        assertFalse(LogLevel.verbose("warn"))
    }

    /** 出厂 config.toml 必须是 warn：核心 debug 日志里有用户文字（隐私） */
    @Test
    fun bundledFactoryLevelIsWarn() {
        val data = File("src/main/assets/data")
        assertEquals("warn", LogLevel.read(File("build/nonexistent-config.toml"), data))
    }
}
