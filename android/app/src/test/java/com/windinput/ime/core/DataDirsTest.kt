package com.windinput.ime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataDirsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun engineSettingsUseTheKunKunCoreConfigPath() {
        val files = temp.newFolder("files")
        val dirs = DataDirs(files, temp.newFolder("cache"))
        dirs.ensureCreated()

        assertEquals(File(files, "rust"), dirs.userRoot)
        assertEquals(File(files, "rust/config/KunKunIME/config.toml"), dirs.engineConfigFile)
        assertEquals(File(files, "rust/data/KunKunIME"), dirs.localRoot)
        assertTrue(dirs.engineConfigDir.isDirectory)
        assertTrue(dirs.localRoot.isDirectory)

        dirs.engineConfigFile.writeText("[debug]\nlog_level = \"error\"\n")
        assertEquals("error", LogLevel.read(dirs.engineConfigFile, dirs.dataDir))
        assertFalse(File(dirs.userRoot, "config.toml").exists())
        assertFalse(File(dirs.userRoot, "config/WindInput").exists())
    }

    @Test
    fun keyboardOverridesStayInTheirOwnPrivateDirectory() {
        val files = temp.newFolder("files")
        val dirs = DataDirs(files, temp.newFolder("cache"))
        dirs.ensureCreated()
        val factory = File(dirs.dataDir, "layouts/qwerty.toml")
        factory.parentFile!!.mkdirs()
        factory.writeText("factory")
        assertEquals(factory, dirs.resolveDataFile("layouts/qwerty.toml"))

        val custom = File(dirs.mobileDir, "layouts/qwerty.toml")
        custom.parentFile!!.mkdirs()
        custom.writeText("custom")
        assertEquals(File(files, "mobile"), dirs.mobileDir)
        assertEquals(custom, dirs.resolveDataFile("layouts/qwerty.toml"))
    }
}
