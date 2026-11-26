package com.windinput.ime.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UserConfigTest {

    private fun tempFile(): File = Files.createTempDirectory("userconfig").toFile().resolve("config.toml")

    @Test
    fun putThenGetRoundTrips() {
        val f = tempFile()
        val c = UserConfig(f)
        c.put("ui.android.haptic_strength", 30L)
        c.put("ui.android.theme", "dark")
        assertEquals(30, c.getInt("ui.android.haptic_strength", 0))
        assertEquals("dark", c.getString("ui.android.theme", "system"))
    }

    /** 同一进程里另一个实例（输入法服务 / 设置页各一个）写了，这边立刻读得到 */
    @Test
    fun writeThroughOneInstanceIsSeenByAnother() {
        val f = tempFile()
        val a = UserConfig(f)
        val b = UserConfig(f)
        assertEquals(false, b.getBoolean("ui.android.one", false))
        a.put("ui.android.one", true)
        assertEquals(true, b.getBoolean("ui.android.one", false))
        // 同长度的改动（30 → 31）也要看得到：不能只凭文件长度判断没变
        a.put("ui.android.n", 30L)
        assertEquals(30, b.getInt("ui.android.n", 0))
        a.put("ui.android.n", 31L)
        assertEquals(31, b.getInt("ui.android.n", 0))
    }

    /** 值里有换行（数字键盘左列符号表按 \n 分隔）：写出的文件仍是合法 TOML，读回原样 */
    @Test
    fun multiLineStringIsWrittenAsValidToml() {
        val f = tempFile()
        val value = "+\n\"\n\\\n,"
        UserConfig(f).put("ui.android.number_side_symbols", value)
        val parsed = org.tomlj.Toml.parse(f.readText())
        assertEquals(emptyList<Any>(), parsed.errors())
        assertEquals(value, parsed.getString("ui.android.number_side_symbols"))
    }

    /** 进程外改了文件（测试脚本 run-as 直接写）：按修改时间 / 大小发现、重新解析 */
    @Test
    fun externalEditIsPickedUp() {
        val f = tempFile()
        val c = UserConfig(f)
        c.put("ui.android.theme", "dark")
        assertEquals("dark", c.getString("ui.android.theme", ""))
        f.writeText("[ui.android]\ntheme = \"light\"\nextra = true\n")
        f.setLastModified(f.lastModified() + 2000)
        assertEquals("light", c.getString("ui.android.theme", ""))
        assertEquals(true, c.getBoolean("ui.android.extra", false))
    }
}
