package com.windinput.ime.settings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SchemeImportFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun copiesWhenWithinLimit() {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val dest = tmp.newFile("a.wpkg")
        assertTrue(SchemeImportFiles.copyLimited(data.inputStream(), dest, max = 300_000))
        assertArrayEquals(data, dest.readBytes())
    }

    /** 超限：返回 false 且不留半截文件（描述符报的长度不可信，所以按实际读到的字节判） */
    @Test
    fun overLimitStopsAndDeletesPartialFile() {
        val dest = tmp.newFile("b.wpkg")
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                java.util.Arrays.fill(b, off, off + len, 1)
                return len
            }
        }
        assertFalse(SchemeImportFiles.copyLimited(endless, dest, max = 1_000_000))
        assertFalse(dest.exists())
    }
}
