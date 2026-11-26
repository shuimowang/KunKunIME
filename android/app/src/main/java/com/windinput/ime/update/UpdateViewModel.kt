package com.windinput.ime.update

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object NoUpdate : UpdateState
    data class Available(val release: AndroidRelease) : UpdateState
    data class Downloading(val release: AndroidRelease, val bytes: Long, val total: Long) : UpdateState
    data class Verifying(val release: AndroidRelease) : UpdateState
    data class Ready(val update: VerifiedUpdate) : UpdateState
    data class Failed(val message: String, val release: AndroidRelease?) : UpdateState
}

/** User-initiated work survives rotation; leaving the page cancels its transfer. */
internal class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val client = UpdateClient(application)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var operation: UpdateOperation? = null
    private var work: Job? = null
    private var latest: AndroidRelease? = null

    val installedVersion: String = client.installedVersion
    var state: UpdateState by mutableStateOf(UpdateState.Idle)
        private set
    var installerRequest: File? by mutableStateOf(null)
        private set

    fun check() {
        if (operation != null) return
        val task = UpdateOperation()
        operation = task
        state = UpdateState.Checking
        work = viewModelScope.launch {
            try {
                val release = withContext(Dispatchers.IO) { client.check(task) }
                if (operation === task) {
                    latest = release
                    state = release?.let { UpdateState.Available(it) } ?: UpdateState.NoUpdate
                }
            } catch (error: Exception) {
                failed(task, error, null)
            } finally {
                if (operation === task) operation = null
            }
        }
    }

    fun download(release: AndroidRelease) {
        if (operation != null) return
        latest = release
        val task = UpdateOperation()
        operation = task
        state = UpdateState.Downloading(release, 0, release.apk.size)
        work = viewModelScope.launch {
            try {
                var lastProgress = 0L
                val update = withContext(Dispatchers.IO) {
                    client.download(release, task) { bytes, total ->
                        val now = SystemClock.elapsedRealtime()
                        if (bytes == total || now - lastProgress >= 150) {
                            lastProgress = now
                            mainHandler.post {
                                if (operation === task) {
                                    state = if (bytes == total) UpdateState.Verifying(release)
                                    else UpdateState.Downloading(release, bytes, total)
                                }
                            }
                        }
                    }
                }
                if (operation === task) state = UpdateState.Ready(update)
            } catch (error: Exception) {
                failed(task, error, release)
            } finally {
                if (operation === task) operation = null
            }
        }
    }

    fun installVerified() {
        val ready = state as? UpdateState.Ready ?: return
        if (operation != null) return
        val task = UpdateOperation()
        operation = task
        state = UpdateState.Verifying(ready.update.release)
        work = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { client.verifyForInstall(ready.update, task) }
                if (operation === task) {
                    state = ready
                    // The current resumed Activity consumes this event. Do not
                    // retain an old Activity callback across rotation.
                    installerRequest = ready.update.file
                }
            } catch (error: Exception) {
                failed(task, error, ready.update.release)
            } finally {
                if (operation === task) operation = null
            }
        }
    }

    fun cancel() {
        operation?.cancel()
        operation = null
        work?.cancel()
        work = null
        installerRequest = null
        state = latest?.let { UpdateState.Available(it) } ?: UpdateState.Idle
    }

    fun takeInstallerRequest(): File? = installerRequest.also { installerRequest = null }

    private fun failed(task: UpdateOperation, error: Exception, release: AndroidRelease?) {
        if (operation !== task || error is CancellationException || error is UpdateCancelled) return
        val message = when (error) {
            is UpdateProblem -> error.message.orEmpty()
            is SocketTimeoutException -> "连接或下载超时，请检查网络后重试"
            is IOException -> "无法完成更新下载，请检查网络和手机剩余空间后重试"
            is org.json.JSONException -> "更新信息格式暂时无法识别，请稍后重试"
            else -> "暂时无法完成更新，请重新检查版本后再试"
        }
        state = UpdateState.Failed(message, release)
    }

    override fun onCleared() {
        cancel()
        super.onCleared()
    }
}
