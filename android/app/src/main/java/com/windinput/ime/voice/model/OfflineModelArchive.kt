package com.windinput.ime.voice.model

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** No archive-provided pathname is ever used as an output pathname. */
internal object OfflineModelArchive {
    fun extract(
        archive: File,
        destination: File,
        archiveRoot: String = OfflineModelCatalog.ARCHIVE_ROOT,
        expectedFiles: List<ModelFileSpec> = OfflineModelCatalog.files,
        checkCancelled: () -> Unit = {},
    ) {
        check(destination.isDirectory || destination.mkdirs()) { "Cannot create model directory" }
        val wanted = expectedFiles.associateBy { "$archiveRoot/${it.name}" }
        val seen = mutableSetOf<String>()
        var entries = 0
        var unpacked = 0L
        // The pinned archive contains two model files and a few small examples.
        val maximumUnpacked = expectedFiles.sumOf { it.size } + 8L * 1024 * 1024
        archive.inputStream().buffered().use { input ->
            BZip2CompressorInputStream(input).use { bzip ->
                TarArchiveInputStream(bzip).use { tar ->
                    while (true) {
                        checkCancelled()
                        val entry = tar.nextEntry ?: break
                        if (++entries > 64 || entry.size < 0 || entry.size > maximumUnpacked ||
                            entry.isSymbolicLink || entry.isLink ||
                            entry.name.startsWith('/') || '\\' in entry.name ||
                            entry.name.split('/').any { it == ".." } ||
                            (!entry.isDirectory && !entry.isFile)
                        ) throw IOException("Invalid model archive entry")
                        unpacked += entry.size
                        if (unpacked > maximumUnpacked) throw IOException("Model archive exceeds size limit")
                        val spec = wanted[entry.name]
                        if (spec == null) {
                            // Read through unused entries with cancellation and size bounds.
                            drainEntry(tar, entry.size, checkCancelled)
                            continue
                        }
                        if (!entry.isFile || !seen.add(spec.name) || entry.size != spec.size) {
                            throw IOException("Unexpected or duplicate model file")
                        }
                        val output = File(destination, spec.name)
                        val digest = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        FileOutputStream(output).use { sink ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                checkCancelled()
                                val size = tar.read(buffer)
                                if (size == -1) break
                                count += size
                                if (count > spec.size) throw IOException("Model file exceeds size limit")
                                sink.write(buffer, 0, size)
                                digest.update(buffer, 0, size)
                            }
                            sink.fd.sync()
                        }
                        if (count != spec.size || digest.digest().hex() != spec.sha256) {
                            throw IOException("Model file integrity check failed")
                        }
                        if (!output.setReadOnly()) throw IOException("Cannot protect installed model")
                    }
                }
            }
        }
        if (seen != expectedFiles.map { it.name }.toSet()) throw IOException("Model archive is incomplete")
    }

    fun verify(directory: File, expectedFiles: List<ModelFileSpec> = OfflineModelCatalog.files,
               checkCancelled: () -> Unit = {}): Boolean = expectedFiles.all { spec ->
        checkCancelled()
        val file = File(directory, spec.name)
        file.isFile && file.length() == spec.size && sha256(file, checkCancelled) == spec.sha256
    }

    fun sha256(file: File, checkCancelled: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancelled()
                val size = input.read(buffer)
                if (size == -1) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().hex()
    }

    private fun drainEntry(input: InputStream, limit: Long, checkCancelled: () -> Unit) {
        val buffer = ByteArray(16 * 1024)
        var count = 0L
        while (true) {
            checkCancelled()
            val size = input.read(buffer)
            if (size == -1) return
            count += size
            if (count > limit) throw IOException("Invalid archive entry size")
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
