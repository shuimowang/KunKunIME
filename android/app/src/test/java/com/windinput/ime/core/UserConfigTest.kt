package com.windinput.ime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    /** 一次应用配置保持同一版本；另一个实例写入/恢复默认后，下次快照立即更新。 */
    @Test
    fun snapshotsRemainStableAcrossOtherInstanceWritesAndReset() {
        val file = tempFile()
        val writer = UserConfig(file)
        val reader = UserConfig(file)
        assertEquals("system", reader.snapshot().getString("ui.android.theme", "system"))
        writer.put("ui.android.theme", "dark")
        writer.put("other.keep", true)
        val before = reader.snapshot()

        writer.put("ui.android.theme", "light")
        val after = reader.snapshot()
        assertEquals("dark", before.getString("ui.android.theme", "system"))
        assertEquals("light", after.getString("ui.android.theme", "system"))

        assertEquals(1, writer.removeIf { it.startsWith("ui.android.") })
        val reset = reader.snapshot()
        assertEquals("system", reset.getString("ui.android.theme", "system"))
        assertEquals(true, reset.getBoolean("other.keep", false))
        assertEquals("light", after.getString("ui.android.theme", "system"))
    }

    /** getter 只读已取快照；下次取快照仍能识别进程外写入和删除。 */
    @Test
    fun snapshotReadsDoNotTouchFileAndNextSnapshotDetectsExternalChanges() {
        val file = tempFile()
        UserConfig(file).put("ui.android.theme", "dark")
        val tracked = CountingFile(file)
        val config = UserConfig(tracked)
        val before = config.snapshot()
        val reads = tracked.metadataReads
        repeat(22) {
            assertEquals("dark", before.getString("ui.android.theme", "system"))
            assertEquals(true, before.getBoolean("ui.android.haptic", true))
            assertEquals(60, before.getInt("ui.android.row_height_portrait", 60))
        }
        assertEquals("snapshot getters must not stat the file", reads, tracked.metadataReads)

        file.writeText("[ui.android]\ntheme = \"light\"\nextra = true\n")
        assertTrue(file.setLastModified(file.lastModified() + 2000))
        val after = config.snapshot()
        assertEquals("light", after.getString("ui.android.theme", "system"))
        assertEquals(true, after.getBoolean("ui.android.extra", false))
        assertEquals("dark", before.getString("ui.android.theme", "system"))

        assertTrue(file.delete())
        assertEquals("system", config.snapshot().getString("ui.android.theme", "system"))
    }

    /** 慢文件的 IO 尚未结束时，无关配置仍可完成写入和读取。旧的全局 cache 锁会卡住它。 */
    @Test
    fun blockedReadDoesNotBlockADifferentConfigFile() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slowFile = object : File(tempFile().path) {
            override fun isFile(): Boolean {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "test read was not released" }
                return super.isFile()
            }
        }
        val other = UserConfig(tempFile())
        other.put("ui.android.theme", "dark")
        val workers = Executors.newFixedThreadPool(2)
        try {
            val slow = workers.submit<Map<String, Any>> { UserConfig(slowFile).readAll() }
            assertTrue("slow read did not start", entered.await(5, TimeUnit.SECONDS))
            workers.submit<Unit> {
                other.put("ui.android.theme", "light")
                assertEquals("light", other.getString("ui.android.theme", "system"))
            }.get(5, TimeUnit.SECONDS)
            release.countDown()
            assertEquals(emptyMap<String, Any>(), slow.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    /** 拆锁后，同一路径的 Service/Settings 多实例仍必须串行读改写，不能覆盖对方的键。 */
    @Test
    fun concurrentInstancesKeepBothWritersUpdates() {
        val file = tempFile()
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val writers = listOf("service", "settings").map { prefix ->
                workers.submit<Unit> {
                    check(start.await(5, TimeUnit.SECONDS))
                    val config = UserConfig(
                        if (prefix == "service") file else File(file.parentFile, "./${file.name}"),
                    )
                    repeat(12) { config.put("$prefix.key$it", it) }
                }
            }
            start.countDown()
            writers.forEach { it.get(10, TimeUnit.SECONDS) }
            val result = UserConfig(file).readAll()
            assertEquals(24, result.size)
            for (prefix in listOf("service", "settings")) {
                repeat(12) { assertEquals(it.toLong(), result["$prefix.key$it"]) }
            }
        } finally {
            start.countDown()
            workers.shutdownNow()
        }
    }

    private class CountingFile(file: File) : File(file.path) {
        var metadataReads = 0
            private set

        override fun isFile(): Boolean {
            metadataReads++
            return super.isFile()
        }

        override fun lastModified(): Long {
            metadataReads++
            return super.lastModified()
        }

        override fun length(): Long {
            metadataReads++
            return super.length()
        }
    }
}
