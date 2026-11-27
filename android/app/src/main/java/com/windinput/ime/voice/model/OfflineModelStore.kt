package com.windinput.ime.voice.model

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class OfflineModelFiles(val directory: File, val model: File, val tokens: File)

/**
 * Application-owned download and installation, independent of an Activity.
 * Models and temporary files live outside backups, recordings never touch disk.
 */
class OfflineModelStore private constructor(context: Context) {
    private val root = File(context.applicationContext.noBackupFilesDir, "offline-voice")
    private val installed = File(root, OfflineModelCatalog.ID)
    private val archive = File(root, "${OfflineModelCatalog.ID}.tar.bz2.part")
    private val staging = File(root, "${OfflineModelCatalog.ID}.installing")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val filesMutex = Mutex()
    private val mutableState = MutableStateFlow(OfflineModelState(OfflineModelPhase.CHECKING))
    val state: StateFlow<OfflineModelState> = mutableState.asStateFlow()
    @Volatile private var transfer: OfflineModelDownload? = null

    init {
        scope.launch {
            filesMutex.withLock {
                try {
                    ensureDirectory()
                    archive.delete()
                    staging.deleteRecursively()
                    mutableState.value = OfflineModelState(
                        if (OfflineModelArchive.verify(installed)) OfflineModelPhase.READY else OfflineModelPhase.MISSING,
                    )
                } catch (_: Exception) {
                    mutableState.value = OfflineModelState(OfflineModelPhase.FAILED, message = "无法读取语音包，请检查可用空间后重试")
                }
            }
        }
    }

    fun isReady(): Boolean = state.value.phase == OfflineModelPhase.READY

    suspend fun verifiedFiles(): OfflineModelFiles? = withVerifiedFiles { it }

    /** Hold this lock only while constructing a recognizer, never while recording/decoding. */
    suspend fun <T> withVerifiedFiles(block: (OfflineModelFiles) -> T): T? = withContext(Dispatchers.IO) {
        filesMutex.withLock {
            if (!isReady()) return@withLock null
            val valid = try {
                OfflineModelArchive.verify(installed)
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
            if (!valid) {
                mutableState.value = OfflineModelState(OfflineModelPhase.FAILED, message = "语音包不完整或校验失败，请重新下载")
                return@withLock null
            }
            block(OfflineModelFiles(installed, File(installed, "model.int8.onnx"), File(installed, "tokens.txt")))
        }
    }

    @Synchronized
    fun download() {
        if (transfer != null || state.value.phase !in setOf(OfflineModelPhase.MISSING, OfflineModelPhase.FAILED)) return
        val operation = OfflineModelDownload()
        transfer = operation
        mutableState.value = OfflineModelState(OfflineModelPhase.DOWNLOADING)
        scope.launch {
            filesMutex.withLock {
                try {
                    ensureDirectory()
                    operation.checkCancelled()
                    // Archive + extracted model coexist until the atomic installation.
                    val required = OfflineModelCatalog.DOWNLOAD_BYTES + OfflineModelCatalog.STORAGE_BYTES + 64L * 1024 * 1024
                    if (root.usableSpace < required) throw IOException("安装语音包需要约 450 MiB 可用空间，请清理后重试")
                    staging.deleteRecursively()
                    archive.delete()
                    operation.download(archive) { bytes ->
                        mutableState.value = OfflineModelState(OfflineModelPhase.DOWNLOADING, downloadedBytes = bytes)
                    }
                    operation.checkCancelled()
                    mutableState.value = OfflineModelState(OfflineModelPhase.VERIFYING,
                        downloadedBytes = OfflineModelCatalog.DOWNLOAD_BYTES)
                    OfflineModelArchive.extract(archive, staging, checkCancelled = operation::checkCancelled)
                    operation.checkCancelled()
                    if (installed.exists() && !installed.deleteRecursively()) throw IOException("无法替换损坏的语音包")
                    if (!staging.renameTo(installed)) throw IOException("无法完成语音包安装")
                    mutableState.value = OfflineModelState(OfflineModelPhase.READY)
                } catch (_: ModelDownloadCancelled) {
                    mutableState.value = OfflineModelState(OfflineModelPhase.MISSING, message = "下载已取消")
                } catch (_: Exception) {
                    mutableState.value = if (runCatching { operation.checkCancelled() }.exceptionOrNull() is ModelDownloadCancelled) {
                        OfflineModelState(OfflineModelPhase.MISSING, message = "下载已取消")
                    } else OfflineModelState(OfflineModelPhase.FAILED,
                        message = "下载或安装语音包失败，请检查网络和可用空间后重试")
                } finally {
                    archive.delete()
                    staging.deleteRecursively()
                    synchronized(this@OfflineModelStore) { if (transfer === operation) transfer = null }
                }
            }
        }
    }

    fun cancelDownload() { transfer?.cancel() }

    @Synchronized
    fun remove() {
        if (transfer != null || state.value.phase !in setOf(OfflineModelPhase.READY, OfflineModelPhase.FAILED)) return
        mutableState.value = OfflineModelState(OfflineModelPhase.CHECKING)
        scope.launch {
            filesMutex.withLock {
                val removed = try {
                    !installed.exists() || installed.deleteRecursively()
                } catch (_: IOException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
                mutableState.value = if (removed) {
                    OfflineModelState(OfflineModelPhase.MISSING)
                } else OfflineModelState(OfflineModelPhase.FAILED, message = "删除语音包失败，请稍后重试")
            }
        }
    }

    private fun ensureDirectory() {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Cannot create model storage")
    }

    companion object {
        @Volatile private var instance: OfflineModelStore? = null
        fun get(context: Context): OfflineModelStore = instance ?: synchronized(this) {
            instance ?: OfflineModelStore(context.applicationContext).also { instance = it }
        }
    }
}
