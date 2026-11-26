package com.windinput.ime.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasePolicyTest {
    private val hash = "a".repeat(64)

    private fun release(version: String, tag: String = "android-v$version", preview: Boolean = false, draft: Boolean = false): JSONObject {
        val apk = "KunKunIME-Android-$version-arm64-v8a.apk"
        fun asset(name: String, size: Long) = JSONObject()
            .put("name", name).put("size", size).put("state", "uploaded")
            .put("digest", "sha256:$hash")
            .put("browser_download_url", "https://github.com/${ReleasePolicy.REPOSITORY}/releases/download/$tag/$name")
        return JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", preview)
            .put("body", "Update notes")
            .put("assets", JSONArray().put(asset(apk, 40_000_000)).put(asset("SHA256SUMS.txt", 200)))
    }

    private fun parsed(vararg entries: JSONObject) = ReleasePolicy.parsePage(JSONArray(entries.toList()).toString()).records

    @Test fun onlyPublishedAndroidStableReleasesCanBeChosen() {
        val records = parsed(
            release("99.0.0", tag = "v99.0.0"),
            release("5.0.0-preview.1", preview = true),
            release("8.0.0", draft = true),
            release("1.1.0"),
        )
        assertEquals("1.1.0", ReleasePolicy.newest(records, "1.0.0")!!.version.stableName)
    }

    @Test fun incorrectlyLabelledPreviewTagIsStillExcluded() {
        assertTrue(parsed(release("2.0.0-preview.1", preview = false)).isEmpty())
    }

    @Test fun previewCanUpgradeToStableWithSameVersionNumbers() {
        assertNotNull(ReleasePolicy.newest(parsed(release("0.1.0")), "0.1.0-preview.4"))
        assertNull(ReleasePolicy.newest(parsed(release("0.1.0")), "0.1.0"))
    }

    @Test fun versionOrderIsNumericAndDoesNotDependOnReleaseOrder() {
        val records = parsed(release("1.9.0"), release("1.10.0"), release("1.2.0"))
        assertEquals("1.10.0", ReleasePolicy.newest(records, "1.8.0")!!.version.stableName)
        assertNull(ReleasePolicy.newest(records, "2.0.0"))
    }

    @Test fun unknownInstalledVersionIsNotTreatedAsVersionZero() {
        assertThrows(UpdateProblem::class.java) { ReleasePolicy.newest(parsed(release("1.0.0")), "custom build") }
        assertNull(ReleaseVersion.parse("999999999999999999.1.0"))
    }

    @Test fun incompleteNewestReleaseDoesNotSilentlyOfferAnOlderPackage() {
        val newest = release("1.2.0").put("assets", JSONArray())
        assertThrows(UpdateProblem::class.java) { ReleasePolicy.newest(parsed(release("1.1.0"), newest), "1.0.0") }
    }

    @Test fun duplicatedOrOffRepositoryAssetsAreRejected() {
        val duplicate = release("1.1.0")
        val assets = duplicate.getJSONArray("assets")
        assets.put(assets.getJSONObject(0))
        assertThrows(UpdateProblem::class.java) { ReleasePolicy.newest(parsed(duplicate), "1.0.0") }
        val wrong = release("1.1.0")
        wrong.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://github.com/other/repo/app.apk")
        assertThrows(UpdateProblem::class.java) { ReleasePolicy.newest(parsed(wrong), "1.0.0") }
    }

    @Test fun checksumRequiresExactlyOneExactFilename() {
        val name = "KunKunIME-Android-1.1.0-arm64-v8a.apk"
        assertEquals(hash, ReleasePolicy.checksum("$hash  Cargo-sources.zip\r\n$hash  $name\r\n", name))
        assertEquals(hash, ReleasePolicy.checksum("${hash.uppercase()} *$name\n", name))
        assertThrows(UpdateProblem::class.java) { ReleasePolicy.checksum("$hash  other.apk\n", name) }
        assertThrows(UpdateProblem::class.java) { ReleasePolicy.checksum("$hash  $name\n$hash  $name\n", name) }
        assertThrows(UpdateProblem::class.java) { ReleasePolicy.checksum("broken  $name\n", name) }
    }

    @Test fun redirectPolicyOnlyAcceptsHttpsRepositoryOrGithubAssetCdn() {
        assertTrue(ReleasePolicy.allowedUrl(ReleasePolicy.releasesUrl(2)))
        assertTrue(ReleasePolicy.allowedUrl("https://github.com/shuimowang/KunKunIME/releases/download/android-v1.1.0/SHA256SUMS.txt"))
        assertTrue(ReleasePolicy.allowedUrl("https://release-assets.githubusercontent.com/github-production-release-asset/123/file?token=public-download"))
        for (url in listOf(
            "http://github.com/shuimowang/KunKunIME/releases/download/android-v1.1.0/SHA256SUMS.txt",
            "https://github.com.evil.example/shuimowang/KunKunIME/releases/download/android-v1.1.0/SHA256SUMS.txt",
            "https://github.com@evil.example/file.apk",
            "https://evil.example@release-assets.githubusercontent.com/github-production-release-asset/file",
            "https://api.github.com/repos/other/repo/releases?per_page=100&page=1",
            "https://api.github.com/repos/shuimowang/KunKunIME/releases?per_page=100&page=1&token=secret",
            "https://release-assets.githubusercontent.com:444/github-production-release-asset/file",
            "https://objects.githubusercontent.com/unrelated/file.apk",
        )) assertFalse(url, ReleasePolicy.allowedUrl(url))
    }

    private fun installed() = ApkIdentity(ReleasePolicy.PACKAGE_NAME, 4, "0.1.0-preview.4", 36, false, false, setOf(hash))
    private fun next() = installed().copy(versionCode = 5, versionName = "0.1.0")

    @Test fun installRequiresSamePackageAndSigningCertificate() {
        verifyApkIdentity(next(), installed(), "0.1.0", 36)
        for (bad in listOf(next().copy(packageName = "other.app"), next().copy(signers = emptySet()), next().copy(signers = setOf("b".repeat(64))))) {
            assertThrows(UpdateProblem::class.java) { verifyApkIdentity(bad, installed(), "0.1.0", 36) }
        }
    }

    @Test fun installRejectsDowngradesWrongVersionAndIncompatibleBuilds() {
        for (bad in listOf(
            next().copy(versionCode = 4), next().copy(versionName = "0.2.0"),
            next().copy(minSdk = 37), next().copy(minSdk = 0),
            next().copy(debuggable = true), next().copy(testOnly = true),
        )) assertThrows(UpdateProblem::class.java) { verifyApkIdentity(bad, installed(), "0.1.0", 36) }
    }
}
