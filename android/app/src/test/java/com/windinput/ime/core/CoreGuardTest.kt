package com.windinput.ime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoreGuardTest {

    private val calls = mutableListOf<Pair<String, Boolean>>()
    private val guard = CoreGuard { what, _, first -> calls += what to first }

    @Test
    fun passesThroughResultWhenNothingThrows() {
        assertEquals(42, guard.run("ok", 0) { 42 })
        assertEquals(emptyList<Pair<String, Boolean>>(), calls)
    }

    /** 核心抛异常：返回兜底值、记一笔；只有第一次算「首次」（提示只弹一次） */
    @Test
    fun exceptionFallsBackAndOnlyFirstIsFirst() {
        assertEquals(false, guard.run("keyDown", false) { error("panic") })
        assertEquals(-1, guard.run("select", -1) { throw IllegalStateException() })
        assertEquals(listOf("keyDown" to true, "select" to false), calls)
    }

    /** Error（内存不足等）不吞：那不是「核心这一下出错」能糊过去的 */
    @Test
    fun errorsAreNotSwallowed() {
        assertThrows(OutOfMemoryError::class.java) { guard.run("big", 0) { throw OutOfMemoryError() } }
        assertEquals(emptyList<Pair<String, Boolean>>(), calls)
    }
}
