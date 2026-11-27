package com.windinput.ime.voice.model

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class OfflineModelArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val weights = "test model weights".toByteArray()
    private val tokens = "a 1\nb 2\n".toByteArray()
    private val expected get() = listOf(spec("model.onnx", weights), spec("tokens.txt", tokens))

    @Test fun installsOnlyPinnedFilesAndVerifiesTheirContents() {
        val archive = archive(listOf("model/model.onnx" to weights, "model/tokens.txt" to tokens,
            "model/test_wavs/example.wav" to byteArrayOf(1, 2, 3)))
        val output = temporary.newFolder()
        OfflineModelArchive.extract(archive, output, "model", expected)
        assertEquals(setOf("model.onnx", "tokens.txt"), output.list()!!.toSet())
        assertTrue(OfflineModelArchive.verify(output, expected))
        assertEquals(String(weights), File(output, "model.onnx").readText())
    }

    @Test fun rejectsWeightsThatKeepTheSizeButChangeTheDigest() {
        val wrong = weights.clone().apply { this[0] = (this[0] + 1).toByte() }
        val archive = archive(listOf("model/model.onnx" to wrong, "model/tokens.txt" to tokens))
        assertThrows(IOException::class.java) {
            OfflineModelArchive.extract(archive, temporary.newFolder(), "model", expected)
        }
    }

    @Test fun refusesMissingOrDuplicateRequiredFiles() {
        for (items in listOf(
            listOf("model/model.onnx" to weights),
            listOf("model/model.onnx" to weights, "model/model.onnx" to weights, "model/tokens.txt" to tokens),
        )) {
            val archive = archive(items)
            assertThrows(IOException::class.java) {
                OfflineModelArchive.extract(archive, temporary.newFolder(), "model", expected)
            }
        }
    }

    @Test fun rejectsTraversalAndSymlinksEvenWhenNotSelectedForExtraction() {
        val traversal = archive(listOf("model/../../outside" to byteArrayOf(1)))
        assertThrows(IOException::class.java) {
            OfflineModelArchive.extract(traversal, temporary.newFolder(), "model", expected)
        }
        val symlink = temporary.newFile()
        TarArchiveOutputStream(BZip2CompressorOutputStream(symlink.outputStream())).use { tar ->
            val entry = TarArchiveEntry("model/unselected", '2'.code.toByte()).apply { linkName = "../../outside" }
            tar.putArchiveEntry(entry)
            tar.closeArchiveEntry()
        }
        assertThrows(IOException::class.java) {
            OfflineModelArchive.extract(symlink, temporary.newFolder(), "model", expected)
        }
    }

    @Test fun cancellationDuringExtractionIsObservedBeforeItCanComplete() {
        val archive = archive(listOf("model/model.onnx" to weights, "model/tokens.txt" to tokens))
        var calls = 0
        assertThrows(ModelDownloadCancelled::class.java) {
            OfflineModelArchive.extract(archive, temporary.newFolder(), "model", expected) {
                if (++calls >= 2) throw ModelDownloadCancelled()
            }
        }
        assertTrue(calls >= 2)
    }

    @Test fun downloadedModelIsNotReadyIfAnyInstalledFileIsCorrupt() {
        val output = temporary.newFolder()
        File(output, "model.onnx").writeBytes(weights)
        File(output, "tokens.txt").writeBytes(tokens)
        assertTrue(OfflineModelArchive.verify(output, expected))
        File(output, "tokens.txt").writeText("corrupt")
        assertFalse(OfflineModelArchive.verify(output, expected))
    }

    private fun archive(entries: List<Pair<String, ByteArray>>): File = temporary.newFile().also { file ->
        TarArchiveOutputStream(BZip2CompressorOutputStream(file.outputStream())).use { tar ->
            for ((name, bytes) in entries) {
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }

    private fun spec(name: String, bytes: ByteArray) = ModelFileSpec(name, bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) })
}
