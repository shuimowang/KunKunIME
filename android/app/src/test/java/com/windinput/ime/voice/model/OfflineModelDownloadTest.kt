package com.windinput.ime.voice.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineModelDownloadTest {
    @Test fun acceptsOnlyTheFixedUpstreamArchiveAndHttpsAssetRedirects() {
        assertTrue(OfflineModelDownload.allowedUrl(OfflineModelCatalog.DOWNLOAD_URL))
        assertTrue(OfflineModelDownload.allowedUrl("https://release-assets.githubusercontent.com/example?signature=x"))
        for (url in listOf(
            OfflineModelCatalog.DOWNLOAD_URL.replace("https://", "http://"),
            OfflineModelCatalog.DOWNLOAD_URL.replace("k2-fsa", "untrusted"),
            "https://release-assets.githubusercontent.com.evil.example/model",
            "https://user:password@release-assets.githubusercontent.com/model",
            "https://release-assets.githubusercontent.com:8443/model",
            "file:///private/model",
        )) assertFalse(url, OfflineModelDownload.allowedUrl(url))
    }

    @Test fun cancellingBeforeConnectionAlwaysStopsTheTransfer() {
        val operation = OfflineModelDownload()
        operation.cancel()
        assertThrows(ModelDownloadCancelled::class.java) { operation.checkCancelled() }
    }
}
