package com.animeow.app.ui.maintenance

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.BuildConfig
import com.animeow.app.data.maintenance.AppCacheManager
import com.animeow.app.data.maintenance.CacheCategory
import com.animeow.app.data.preferences.MaintenancePreferences
import com.animeow.app.data.preferences.MaintenanceSettings
import com.animeow.app.data.update.AppUpdateDownloadOption
import com.animeow.app.data.update.AppUpdateInfo
import com.animeow.app.data.update.AppUpdateService
import com.animeow.app.data.update.UpdateDownloadState
import com.animeow.app.util.runCatchingCancellable
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CacheUiState(
    val categories: List<CacheCategory> = emptyList(),
    val persistentDataBytes: Long = 0L,
    val isScanning: Boolean = false,
    val isClearing: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
) {
    val totalCacheBytes: Long get() = categories.sumOf(CacheCategory::sizeBytes)
}

class CacheViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = AppCacheManager(application)
    private val _state = MutableStateFlow(CacheUiState())
    val state: StateFlow<CacheUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_state.value.isClearing || _state.value.isScanning) return
        viewModelScope.launch {
            _state.update { it.copy(isScanning = true, message = null, isError = false) }
            runCatchingCancellable { manager.scan() }
                .onSuccess { snapshot ->
                    _state.value = CacheUiState(
                        categories = snapshot.categories,
                        persistentDataBytes = snapshot.persistentDataBytes,
                        isScanning = false,
                    )
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(isScanning = false, message = "缓存统计失败：${error.message.orEmpty()}", isError = true)
                    }
                }
        }
    }

    fun clear(ids: Set<String>) {
        if (ids.isEmpty() || _state.value.isClearing || _state.value.isScanning) return
        viewModelScope.launch {
            _state.update { it.copy(isClearing = true, message = null, isError = false) }
            runCatchingCancellable { manager.clear(ids) }
                .onSuccess { result ->
                    val snapshot = manager.scan()
                    _state.value = CacheUiState(
                        categories = snapshot.categories,
                        persistentDataBytes = snapshot.persistentDataBytes,
                        isScanning = false,
                        message = if (result.failedPaths == 0) {
                            "已释放 ${formatBytes(result.clearedBytes)}"
                        } else {
                            "已释放 ${formatBytes(result.clearedBytes)}，${result.failedPaths} 个正在使用的项目稍后再清理"
                        },
                    )
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(isClearing = false, message = "清理失败：${error.message.orEmpty()}", isError = true)
                    }
                }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null, isError = false) }
    }
}

sealed interface UpdateDialogState {
    data class Available(val info: AppUpdateInfo) : UpdateDialogState
    data class Latest(val currentVersion: String) : UpdateDialogState
    data class Error(val message: String) : UpdateDialogState
    data class DownloadQueued(
        val downloadId: Long,
        val versionName: String,
        val progressPercent: Int? = null,
        val forceUpdate: Boolean = false,
    ) : UpdateDialogState
    data class DownloadReady(
        val downloadId: Long,
        val versionName: String,
        val forceUpdate: Boolean = false,
    ) : UpdateDialogState
}

data class UpdateUiState(
    val isChecking: Boolean = false,
    val autoCheckUpdates: Boolean = true,
    val ignoredUpdateVersion: String = "",
    val lastCheckedAt: String? = null,
    val dialog: UpdateDialogState? = null,
)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val service = AppUpdateService(application)
    private val preferences = MaintenancePreferences(application)
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()
    private var downloadMonitor: Job? = null

    init {
        viewModelScope.launch {
            val settings = preferences.snapshot()
            val trackedDownload = withContext(Dispatchers.IO) { service.trackedDownload() }
            // InProgress 状态不自动弹窗，静默监控即可；只有 Ready/Failed 才恢复弹窗
            val restoredDialog = when (trackedDownload?.state) {
                is UpdateDownloadState.InProgress -> null
                else -> trackedDownload?.toDialogState()
            }
            _state.update {
                it.copy(
                    autoCheckUpdates = settings.autoCheckUpdates,
                    ignoredUpdateVersion = settings.ignoredUpdateVersion,
                    dialog = restoredDialog,
                )
            }
            trackedDownload?.let { download ->
                when (download.state) {
                    is UpdateDownloadState.InProgress ->
                        monitorDownload(download.downloadId, download.versionName, download.forceUpdate)
                    is UpdateDownloadState.Failed, UpdateDownloadState.Missing ->
                        withContext(Dispatchers.IO) { service.discardTrackedDownload(download.downloadId) }
                    UpdateDownloadState.Ready -> Unit
                }
            }
            if (settings.autoCheckUpdates && trackedDownload == null) check(silent = true)
        }
    }

    fun check(silent: Boolean = false) {
        if (_state.value.isChecking) return
        viewModelScope.launch {
            _state.update { it.copy(isChecking = true, dialog = if (silent) it.dialog else null) }
            runCatchingCancellable { service.check() }
                .onSuccess { info ->
                    val newer = AppUpdateService.isUpdateAvailable(
                        currentCode = BuildConfig.VERSION_CODE,
                        currentName = BuildConfig.VERSION_NAME,
                        latestCode = info.versionCode,
                        latestName = info.versionName,
                    )
                    val ignored = _state.value.ignoredUpdateVersion == info.versionName
                    _state.update {
                        it.copy(
                            isChecking = false,
                            lastCheckedAt = Instant.now().toString(),
                            dialog = when {
                                newer && (!silent || !ignored || info.isForceUpdate) -> UpdateDialogState.Available(info)
                                !silent -> UpdateDialogState.Latest(BuildConfig.VERSION_NAME)
                                else -> it.dialog
                            },
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isChecking = false,
                            lastCheckedAt = Instant.now().toString(),
                            dialog = if (silent) it.dialog else UpdateDialogState.Error(error.message ?: "无法连接更新服务器"),
                        )
                    }
                }
        }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        viewModelScope.launch {
            val current = preferences.snapshot().copy(autoCheckUpdates = enabled)
            preferences.save(current)
            _state.update { it.copy(autoCheckUpdates = enabled) }
        }
    }

    fun ignoreAvailableVersion() {
        val info = (_state.value.dialog as? UpdateDialogState.Available)?.info ?: return
        if (info.isForceUpdate) return
        viewModelScope.launch {
            val current = preferences.snapshot().copy(ignoredUpdateVersion = info.versionName)
            preferences.save(current)
            _state.update { it.copy(ignoredUpdateVersion = info.versionName, dialog = null) }
        }
    }

    fun clearIgnoredVersion() {
        viewModelScope.launch {
            val current = preferences.snapshot().copy(ignoredUpdateVersion = "")
            preferences.save(current)
            _state.update { it.copy(ignoredUpdateVersion = "") }
        }
    }

    fun downloadAvailableUpdate(option: AppUpdateDownloadOption) {
        val info = (_state.value.dialog as? UpdateDialogState.Available)?.info ?: return
        runCatching { service.enqueueDownload(info, option) }
            .onSuccess { id ->
                _state.update {
                    it.copy(
                        dialog = UpdateDialogState.DownloadQueued(
                            downloadId = id,
                            versionName = info.versionName,
                            forceUpdate = info.isForceUpdate,
                        ),
                    )
                }
                monitorDownload(id, info.versionName, info.isForceUpdate)
            }
            .onFailure { error ->
                _state.update { it.copy(dialog = UpdateDialogState.Error(error.message ?: "无法创建下载任务")) }
            }
    }

    fun installDownloadedUpdate(downloadId: Long) {
        when (val download = service.launchInstaller(downloadId)) {
            UpdateDownloadState.Ready -> _state.update { it.copy(dialog = null) }
            is UpdateDownloadState.InProgress -> _state.update {
                val version = when (val current = it.dialog) {
                    is UpdateDialogState.DownloadQueued -> current.versionName
                    is UpdateDialogState.DownloadReady -> current.versionName
                    else -> ""
                }
                it.copy(
                    dialog = UpdateDialogState.DownloadQueued(
                        downloadId = downloadId,
                        versionName = version,
                        progressPercent = download.progressPercent,
                        forceUpdate = when (val current = it.dialog) {
                            is UpdateDialogState.DownloadQueued -> current.forceUpdate
                            is UpdateDialogState.DownloadReady -> current.forceUpdate
                            else -> false
                        },
                    ),
                )
            }
            is UpdateDownloadState.Failed -> _state.update {
                it.copy(dialog = UpdateDialogState.Error(download.message))
            }
            UpdateDownloadState.Missing -> _state.update {
                it.copy(dialog = UpdateDialogState.Error("找不到对应的更新下载记录，请重新检查更新"))
            }
        }
    }

    fun dismissDialog() {
        val current = _state.value.dialog
        if (
            (current is UpdateDialogState.Available && current.info.isForceUpdate) ||
            (current is UpdateDialogState.DownloadQueued && current.forceUpdate) ||
            (current is UpdateDialogState.DownloadReady && current.forceUpdate)
        ) return
        downloadMonitor?.cancel()
        downloadMonitor = null
        // 仅关闭弹窗并停止监控，系统下载任务继续在后台运行；
        // 重启后 init 块会静默恢复监控，下载完成时再弹出 Ready 弹窗
        _state.update { it.copy(dialog = null) }
    }

    fun cancelDownload() {
        val current = _state.value.dialog
        val downloadId = when (current) {
            is UpdateDialogState.DownloadQueued -> current.downloadId
            is UpdateDialogState.DownloadReady -> current.downloadId
            else -> return
        }
        downloadMonitor?.cancel()
        downloadMonitor = null
        viewModelScope.launch(Dispatchers.IO) {
            service.discardTrackedDownload(downloadId)
        }
        _state.update { it.copy(dialog = null) }
    }

    private fun monitorDownload(downloadId: Long, versionName: String, forceUpdate: Boolean) {
        downloadMonitor?.cancel()
        downloadMonitor = viewModelScope.launch {
            while (true) {
                delay(DOWNLOAD_POLL_INTERVAL_MS)
                when (val download = withContext(Dispatchers.IO) { service.downloadState(downloadId) }) {
                    UpdateDownloadState.Ready -> {
                        _state.update {
                            it.copy(
                                dialog = UpdateDialogState.DownloadReady(
                                    downloadId = downloadId,
                                    versionName = versionName,
                                    forceUpdate = forceUpdate,
                                ),
                            )
                        }
                        return@launch
                    }

                    is UpdateDownloadState.InProgress -> {
                        // 仅在弹窗已显示时更新进度，不重新创建已关闭的弹窗
                        if (_state.value.dialog is UpdateDialogState.DownloadQueued) {
                            _state.update {
                                it.copy(
                                    dialog = UpdateDialogState.DownloadQueued(
                                        downloadId = downloadId,
                                        versionName = versionName,
                                        progressPercent = download.progressPercent,
                                        forceUpdate = forceUpdate,
                                    ),
                                )
                            }
                        }
                    }

                    is UpdateDownloadState.Failed -> {
                        withContext(Dispatchers.IO) { service.discardTrackedDownload(downloadId) }
                        _state.update { it.copy(dialog = UpdateDialogState.Error(download.message)) }
                        return@launch
                    }

                    UpdateDownloadState.Missing -> {
                        withContext(Dispatchers.IO) { service.discardTrackedDownload(downloadId) }
                        _state.update { it.copy(dialog = UpdateDialogState.Error("更新下载记录已失效，请重新检查更新")) }
                        return@launch
                    }
                }
            }
        }
    }

    private fun com.animeow.app.data.update.TrackedUpdateDownload.toDialogState(): UpdateDialogState =
        when (val currentState = state) {
            UpdateDownloadState.Ready -> UpdateDialogState.DownloadReady(downloadId, versionName, forceUpdate)
            is UpdateDownloadState.InProgress -> UpdateDialogState.DownloadQueued(
                downloadId = downloadId,
                versionName = versionName,
                progressPercent = currentState.progressPercent,
                forceUpdate = forceUpdate,
            )
            is UpdateDownloadState.Failed -> UpdateDialogState.Error(currentState.message)
            UpdateDownloadState.Missing -> UpdateDialogState.Error("更新下载记录已失效，请重新检查更新")
        }

    private companion object {
        const val DOWNLOAD_POLL_INTERVAL_MS = 1_000L
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = -1
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex += 1
    }
    return if (value >= 100) "%.0f %s".format(value, units[unitIndex])
    else "%.1f %s".format(value, units[unitIndex])
}
