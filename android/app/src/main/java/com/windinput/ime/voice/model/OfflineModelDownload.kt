package com.windinput.ime.voice.model

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class ModelDownloadCancelled : IOException("Model download cancelled")

/** A model transfer never sends recordings, editor text, cookies or credentials. */
internal class OfflineModelDownload {
    private val cancelled = AtomicBoolean(false)
    private val connection = AtomicReference<HttpURLConnection?>()
    private val started = System.nanoTime()

    fun cancel() {
        cancelled.set(true)
        connection.getAndSet(null)?.disconnect()
    }

    fun checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw ModelDownloadCancelled()
        if (System.nanoTime() - started > 20L * 60 * 1_000_000_000) throw IOException("语音包下载用时过长，请重试")
    }

    fun download(destination: File, progress: (Long) -> Unit) {
        var next = OfflineModelCatalog.DOWNLOAD_URL
        repeat(6) {
            checkCancelled()
            if (!allowedUrl(next)) throw IOException("语音包下载地址不符合来源要求")
            val active = URI(next).toURL().openConnection() as HttpURLConnection
            active.instanceFollowRedirects = false
            active.connectTimeout = 15_000
            active.readTimeout = 30_000
            active.useCaches = false
            active.setRequestProperty("User-Agent", "KunKunIME-OfflineVoice")
            active.setRequestProperty("Accept", "application/octet-stream")
            active.setRequestProperty("Accept-Encoding", "identity")
            connection.set(active)
            try {
                checkCancelled()
                val status = active.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = active.getHeaderField("Location") ?: throw IOException("下载地址暂时不可用")
                    next = URI(next).resolve(location).toString()
                    return@repeat
                }
                if (status != HttpURLConnection.HTTP_OK) throw IOException(
                    when (status) {
                        403, 429 -> "下载服务暂时限制请求，请稍后重试"
                        404 -> "语音包暂时不可用，请稍后重试"
                        else -> "语音包下载失败（HTTP $status），请重试"
                    },
                )
                val length = active.contentLengthLong
                if (length > 0 && length != OfflineModelCatalog.DOWNLOAD_BYTES) throw IOException("语音包大小不符合预期")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                var lastProgress = 0L
                active.inputStream.buffered().use { source ->
                    FileOutputStream(destination).use { sink ->
                        val bytes = ByteArray(64 * 1024)
                        while (true) {
                            checkCancelled()
                            val count = source.read(bytes)
                            if (count == -1) break
                            total += count
                            if (total > OfflineModelCatalog.DOWNLOAD_BYTES) throw IOException("语音包超过预期大小")
                            sink.write(bytes, 0, count)
                            digest.update(bytes, 0, count)
                            val now = System.nanoTime()
                            if (now - lastProgress > 200_000_000) {
                                progress(total)
                                lastProgress = now
                            }
                        }
                        sink.fd.sync()
                    }
                }
                checkCancelled()
                val sha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                if (total != OfflineModelCatalog.DOWNLOAD_BYTES || sha != OfflineModelCatalog.ARCHIVE_SHA256) {
                    throw IOException("语音包校验失败，请重新下载")
                }
                progress(total)
                return
            } finally {
                connection.compareAndSet(active, null)
                active.disconnect()
            }
        }
        throw IOException("语音包下载重定向过多，请稍后重试")
    }

    companion object {
        internal fun allowedUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.fragment == null &&
                when (uri.host) {
                    "github.com" -> uri.rawPath == URI(OfflineModelCatalog.DOWNLOAD_URL).rawPath && uri.rawQuery == null
                    "release-assets.githubusercontent.com", "objects.githubusercontent.com" -> true
                    else -> false
                }
        }.getOrDefault(false)
    }
}
