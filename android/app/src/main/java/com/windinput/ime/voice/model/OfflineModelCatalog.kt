package com.windinput.ime.voice.model

/** Immutable, upstream-published model. Updating it requires a reviewed app release. */
object OfflineModelCatalog {
    const val ID = "sensevoice-small-int8-2024-07-17"
    const val DISPLAY_NAME = "SenseVoiceSmall INT8"
    const val DOWNLOAD_BYTES = 163_002_883L
    const val STORAGE_BYTES = 239_549_735L
    const val ARCHIVE_ROOT = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
    const val DOWNLOAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
        "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
    const val ARCHIVE_SHA256 = "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e"

    internal val files = listOf(
        ModelFileSpec("model.int8.onnx", 239_233_841L, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
        ModelFileSpec("tokens.txt", 315_894L, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
    )
}

internal data class ModelFileSpec(val name: String, val size: Long, val sha256: String)

enum class OfflineModelPhase { CHECKING, MISSING, DOWNLOADING, VERIFYING, READY, FAILED }

data class OfflineModelState(
    val phase: OfflineModelPhase,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = OfflineModelCatalog.DOWNLOAD_BYTES,
    val message: String? = null,
)
