package com.windinput.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LayoutRepositoryTest {

    private val dir: File = Files.createTempDirectory("layouts").toFile()
    private val qwerty = File(dir, "layouts/qwerty.toml").apply { parentFile!!.mkdirs() }
    private val repo = LayoutRepository { rel -> File(dir, rel).takeIf { it.isFile } }

    private fun writeRow(chars: String) {
        qwerty.writeText("[[rows]]\nkey = [ { chars = \"$chars\" } ]\n")
    }

    @Test
    fun parsedRowsAreCachedUntilTheFileChanges() {
        writeRow("abc")
        val first = repo.rows(KeyboardLayer.LETTERS)
        assertSame("没改文件不该重新解析", first, repo.rows(KeyboardLayer.LETTERS))
        writeRow("xyz")
        qwerty.setLastModified(qwerty.lastModified() + 2000)
        val second = repo.rows(KeyboardLayer.LETTERS)
        assertEquals(KeyAction.Text('x'), second[0][0].action)
    }

    @Test
    fun fileRewrittenWithinTheSameMtimeIsReparsedWhenLengthDiffers() {
        // 升级拷贝出厂数据时，预热线程可能读到拷贝中途的文件；写完 mtime 可能还是同一秒
        writeRow("a")
        val stamp = qwerty.lastModified()
        assertEquals(KeyAction.Text('a'), repo.rows(KeyboardLayer.LETTERS)[0][0].action)
        writeRow("xyz")
        qwerty.setLastModified(stamp)
        assertEquals(KeyAction.Text('x'), repo.rows(KeyboardLayer.LETTERS)[0][0].action)
    }

    @Test
    fun missingFileFallsBackToBuiltin() {
        assertEquals(KeyboardLayouts.rows(KeyboardLayer.NUMBER), repo.rows(KeyboardLayer.NUMBER))
    }
}
