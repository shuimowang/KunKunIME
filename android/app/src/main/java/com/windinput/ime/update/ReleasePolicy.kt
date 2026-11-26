package com.windinput.ime.update

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal class UpdateProblem(message: String) : Exception(message)

internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int, val preview: Boolean = false) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { !it.preview })

    val stableName: String get() = "$major.$minor.$patch"

    companion object {
        private val format = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?$")
        fun parse(value: String): ReleaseVersion? {
            val match = format.matchEntire(value) ?: return null
            val parts = (1..3).map { match.groupValues[it].toIntOrNull() ?: return null }
            return ReleaseVersion(parts[0], parts[1], parts[2], match.groupValues[4].isNotEmpty())
        }
    }
}

internal data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)
internal data class AndroidRelease(
    val tag: String,
    val version: ReleaseVersion,
    val notes: String,
    val apk: ReleaseAsset,
    val checksums: ReleaseAsset,
)

internal data class ReleaseRecord(val tag: String, val version: ReleaseVersion, val json: JSONObject)
internal data class ReleasePage(val count: Int, val records: List<ReleaseRecord>)

/** Fixed repository, explicit stable Android channel, bounded untrusted metadata. */
internal object ReleasePolicy {
    const val REPOSITORY = "shuimowang/KunKunIME"
    const val PACKAGE_NAME = "io.github.shuimowang.kunkunime"
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    const val MAX_METADATA_BYTES = 8L * 1024 * 1024
    const val MAX_CHECKSUM_BYTES = 64L * 1024
    private val shaPattern = Regex("[a-fA-F0-9]{64}")

    fun releasesUrl(page: Int): String {
        require(page in 1..5)
        return "https://api.github.com/repos/$REPOSITORY/releases?per_page=100&page=$page"
    }

    fun parsePage(text: String): ReleasePage {
        val array = JSONArray(text)
        val records = buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optBoolean("draft", true) || obj.optBoolean("prerelease", true)) continue
                val tag = obj.optString("tag_name")
                if (!tag.startsWith("android-v")) continue
                val version = ReleaseVersion.parse(tag.removePrefix("android-v")) ?: continue
                if (version.preview) continue
                add(ReleaseRecord(tag, version, obj))
            }
        }
        return ReleasePage(array.length(), records)
    }

    fun newest(records: List<ReleaseRecord>, installedVersion: String): AndroidRelease? {
        val installed = ReleaseVersion.parse(installedVersion)
            ?: throw UpdateProblem("无法读取当前应用版本，请从应用的发行页面检查更新")
        val newest = records.maxByOrNull { it.version } ?: return null
        if (newest.version <= installed) return null
        val name = "KunKunIME-Android-${newest.version.stableName}-arm64-v8a.apk"
        val assets = newest.json.optJSONArray("assets") ?: throw UpdateProblem("最新正式版尚未提供完整的安装附件，请稍后重试")
        return AndroidRelease(
            newest.tag, newest.version, newest.json.optString("body").take(12_000),
            asset(assets, newest.tag, name, MAX_APK_BYTES),
            asset(assets, newest.tag, "SHA256SUMS.txt", MAX_CHECKSUM_BYTES),
        )
    }

    private fun asset(assets: JSONArray, tag: String, name: String, max: Long): ReleaseAsset {
        val matching = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name") == name }
        if (matching.size != 1) throw UpdateProblem("最新正式版的安装包或校验文件不完整，请稍后重试")
        val obj = matching.single()
        val expectedUrl = "https://github.com/$REPOSITORY/releases/download/$tag/$name"
        val url = obj.optString("browser_download_url")
        val size = obj.optLong("size", -1)
        if (obj.optString("state") != "uploaded" || size !in 1..max || url != expectedUrl) {
            throw UpdateProblem("发行附件的信息不符合要求，已停止更新")
        }
        val digest = obj.optString("digest").takeIf { it.isNotEmpty() && it != "null" }?.let {
            if (!it.startsWith("sha256:") || !shaPattern.matches(it.removePrefix("sha256:"))) {
                throw UpdateProblem("发行附件的校验信息无效，已停止更新")
            }
            it.removePrefix("sha256:").lowercase()
        }
        return ReleaseAsset(name, url, size, digest)
    }

    fun checksum(text: String, apkName: String): String {
        val line = Regex("^([a-fA-F0-9]{64})[ \\t]+\\*?([^\\r\\n]+)$")
        val matches = text.lineSequence().filter { it.isNotBlank() }.map { raw ->
            line.matchEntire(raw.trimEnd('\r')) ?: throw UpdateProblem("校验文件格式无效，请稍后重试")
        }.filter { it.groupValues[2] == apkName }.toList()
        if (matches.size != 1) throw UpdateProblem("校验文件没有唯一匹配的安装包，已停止下载")
        return matches.single().groupValues[1].lowercase()
    }

    /** Every redirect is checked; never follow metadata to arbitrary hosts or HTTP. */
    fun allowedUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        if (uri.scheme != "https" || uri.userInfo != null || uri.fragment != null || uri.port !in listOf(-1, 443)) return false
        when (uri.host?.lowercase()) {
            "api.github.com" -> uri.rawPath == "/repos/$REPOSITORY/releases" &&
                Regex("per_page=100&page=[1-5]").matches(uri.rawQuery.orEmpty())
            "github.com" -> uri.rawQuery == null &&
                Regex("/" + REPOSITORY + "/releases/download/android-v[0-9]+\\.[0-9]+\\.[0-9]+/(KunKunIME-Android-[0-9]+\\.[0-9]+\\.[0-9]+-arm64-v8a\\.apk|SHA256SUMS\\.txt)").matches(uri.rawPath)
            "release-assets.githubusercontent.com", "objects.githubusercontent.com" ->
                uri.rawPath.startsWith("/github-production-release-asset/") && !uri.rawPath.contains("..")
            else -> false
        }
    }.getOrDefault(false)
}

internal data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val debuggable: Boolean,
    val testOnly: Boolean,
    val signers: Set<String>,
)

internal fun verifyApkIdentity(candidate: ApkIdentity, installed: ApkIdentity, expectedVersion: String, deviceSdk: Int) {
    if (installed.packageName != ReleasePolicy.PACKAGE_NAME || candidate.packageName != installed.packageName) {
        throw UpdateProblem("安装包的应用标识不一致，已停止安装")
    }
    if (candidate.versionCode <= installed.versionCode || candidate.versionName != expectedVersion) {
        throw UpdateProblem("安装包不是预期的新版本，已停止安装")
    }
    if (candidate.minSdk < 1 || candidate.minSdk > deviceSdk || candidate.debuggable || candidate.testOnly) {
        throw UpdateProblem("安装包不适用于当前手机或不是正式构建，已停止安装")
    }
    if (candidate.signers.isEmpty() || installed.signers.isEmpty() || candidate.signers != installed.signers) {
        throw UpdateProblem("安装包签名与当前应用不同，不能覆盖安装；请使用同一签名的正式版本")
    }
}
