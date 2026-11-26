package com.windinput.ime.update

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

internal class UpdateCancelled : IOException("Update cancelled")

/** One user action owns one transfer and can disconnect it immediately. */
internal class UpdateOperation {
    private val cancelled = AtomicBoolean(false)
    private val active = AtomicReference<HttpURLConnection?>()

    fun cancel() {
        cancelled.set(true)
        active.getAndSet(null)?.disconnect()
    }

    fun checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw UpdateCancelled()
    }

    private fun open(url: String): HttpURLConnection {
        var next = url
        repeat(6) {
            checkCancelled()
            if (!ReleasePolicy.allowedUrl(next)) throw UpdateProblem("更新下载地址不符合项目来源，已停止连接")
            val connection = URI(next).toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "KunKunIME-Android-Updater")
            connection.setRequestProperty("Accept", if (URI(next).host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
            connection.setRequestProperty("Accept-Encoding", "identity")
            active.set(connection)
            try {
                checkCancelled()
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location")
                        ?: throw UpdateProblem("更新服务器未返回下载地址，请稍后重试")
                    next = URI(next).resolve(location).toString()
                    active.compareAndSet(connection, null)
                    connection.disconnect()
                } else {
                    if (status != HttpURLConnection.HTTP_OK) {
                        throw UpdateProblem(when (status) {
                            403, 429 -> "GitHub 暂时限制了请求，请稍后再检查更新"
                            404 -> "发行附件暂时不可用，请重新检查更新"
                            else -> "更新服务器暂时不可用（HTTP $status），请稍后重试"
                        })
                    }
                    checkCancelled()
                    return connection
                }
            } catch (error: Exception) {
                active.compareAndSet(connection, null)
                connection.disconnect()
                checkCancelled()
                throw error
            }
        }
        throw UpdateProblem("下载地址跳转过多，已停止连接")
    }

    fun bytes(url: String, max: Long): ByteArray {
        val output = ByteArrayOutputStream()
        transfer(url, max, null, { buffer, count -> output.write(buffer, 0, count) }, { _, _ -> })
        return output.toByteArray()
    }

    fun download(asset: ReleaseAsset, destination: File, onProgress: (Long, Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        destination.outputStream().use { output ->
            transfer(asset.url, ReleasePolicy.MAX_APK_BYTES, asset.size, { buffer, count ->
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
            }, onProgress)
            output.fd.sync()
        }
        return digest.digest().hex()
    }

    private fun transfer(
        url: String,
        max: Long,
        expected: Long?,
        consume: (ByteArray, Int) -> Unit,
        onProgress: (Long, Long) -> Unit,
    ) {
        val connection = open(url)
        try {
            val declared = connection.contentLengthLong
            if (declared > max || expected != null && declared >= 0 && declared != expected) {
                throw UpdateProblem("下载文件的大小与发行信息不一致，已停止下载")
            }
            var total = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkCancelled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    if (total > max || expected != null && total > expected) throw UpdateProblem("下载文件超过预期大小，已停止下载")
                    consume(buffer, count)
                    onProgress(total, expected ?: declared)
                }
            }
            checkCancelled()
            if (expected != null && total != expected) throw UpdateProblem("下载未完成，请重新下载更新")
        } finally {
            active.compareAndSet(connection, null)
            connection.disconnect()
        }
    }
}

internal data class VerifiedUpdate(val release: AndroidRelease, val file: File, val sha256: String)

internal class UpdateClient(private val context: Context) {
    val installedVersion: String
        get() = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    fun check(operation: UpdateOperation): AndroidRelease? {
        val records = mutableListOf<ReleaseRecord>()
        for (page in 1..5) {
            operation.checkCancelled()
            val body = operation.bytes(ReleasePolicy.releasesUrl(page), ReleasePolicy.MAX_METADATA_BYTES)
            val parsed = ReleasePolicy.parsePage(body.toString(Charsets.UTF_8))
            records += parsed.records
            if (parsed.count < 100) return ReleasePolicy.newest(records, installedVersion)
        }
        // Do not say "up to date" after silently stopping in the middle of history.
        throw UpdateProblem("发行记录较多，暂时未能完整检查安卓版本，请稍后重试")
    }

    fun download(release: AndroidRelease, operation: UpdateOperation, onProgress: (Long, Long) -> Unit): VerifiedUpdate {
        if ("arm64-v8a" !in Build.SUPPORTED_ABIS) throw UpdateProblem("应用内更新目前仅提供 ARM64 手机安装包")
        val checksumBytes = operation.bytes(release.checksums.url, ReleasePolicy.MAX_CHECKSUM_BYTES)
        if (checksumBytes.size.toLong() != release.checksums.size ||
            release.checksums.sha256?.let { sha256(checksumBytes) != it } == true) {
            throw UpdateProblem("校验文件与发行信息不一致，请重新检查更新")
        }
        val expected = ReleasePolicy.checksum(checksumBytes.toString(Charsets.UTF_8), release.apk.name)
        if (release.apk.sha256 != null && release.apk.sha256 != expected) {
            throw UpdateProblem("安装包的两份校验信息不一致，已停止下载")
        }
        val directory = updateDirectory()
        clearStale(directory)
        val partial = File.createTempFile("kunkun-", ".apk.part", directory)
        var finalFile: File? = null
        try {
            val actual = operation.download(release.apk, partial, onProgress)
            if (actual != expected) throw UpdateProblem("安装包校验失败，请重新下载")
            operation.checkCancelled()
            val destination = File(directory, partial.name.removeSuffix(".part"))
            if (!partial.renameTo(destination)) throw UpdateProblem("无法保存已验证的安装包，请稍后重试")
            finalFile = destination
            // Package parsers get a normal .apk path, while incomplete bytes
            // remain .part. No Ready state or URI is exposed before validation.
            verifyArchive(destination, release)
            operation.checkCancelled()
            if (!destination.setReadOnly()) throw UpdateProblem("无法保护已下载的安装包，请重试")
            return VerifiedUpdate(release, destination, expected)
        } catch (error: Exception) {
            partial.delete()
            finalFile?.delete()
            throw error
        }
    }

    /** Re-read the same private APK immediately before handing it to the installer. */
    fun verifyForInstall(update: VerifiedUpdate, operation: UpdateOperation) {
        val file = update.file
        if (file.canonicalFile.parentFile != updateDirectory().canonicalFile || !file.name.endsWith(".apk") ||
            !file.isFile || file.length() != update.release.apk.size) {
            throw UpdateProblem("下载的安装包已失效，请重新下载")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                operation.checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (digest.digest().hex() != update.sha256) throw UpdateProblem("安装包校验已变化，请重新下载")
        verifyArchive(file, update.release)
        operation.checkCancelled()
    }

    private fun verifyArchive(file: File, release: AndroidRelease) {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val pm = context.packageManager
        val installed = pm.getPackageInfo(context.packageName, flags)
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw UpdateProblem("无法验证安装包的签名与版本，请重新下载")
        verifyApkIdentity(candidate.identity(), installed.identity(), release.version.stableName, Build.VERSION.SDK_INT)
        val abis = ZipFile(file).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("lib/") }
                .mapNotNull { it.name.split('/').getOrNull(1) }.toSet()
        }
        if (abis != setOf("arm64-v8a")) throw UpdateProblem("安装包的处理器架构不符合本次手机更新，已停止安装")
    }

    private fun PackageInfo.identity(): ApkIdentity {
        val info = applicationInfo ?: throw UpdateProblem("安装包缺少应用信息，已停止安装")
        return ApkIdentity(
            packageName, longVersionCode, versionName.orEmpty(), info.minSdkVersion,
            info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            info.flags and ApplicationInfo.FLAG_TEST_ONLY != 0,
            signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty(),
        )
    }

    private fun updateDirectory(): File = File(context.cacheDir, "updates").apply {
        if (!isDirectory && !mkdirs()) throw UpdateProblem("无法创建更新缓存，请检查剩余空间")
    }

    private fun clearStale(directory: File) {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L
        directory.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
