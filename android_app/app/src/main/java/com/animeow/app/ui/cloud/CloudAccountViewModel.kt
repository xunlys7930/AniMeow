package com.animeow.app.ui.cloud

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.backup.NativeRestorePoint
import com.animeow.app.data.backup.NativeRestoreSelection
import com.animeow.app.data.backup.PreparedNativeRestore
import com.animeow.app.data.cloud.CloudAccountService
import com.animeow.app.data.cloud.CloudBackupFormat
import com.animeow.app.data.cloud.CloudBackupInfo
import com.animeow.app.data.cloud.CloudConflictPolicy
import com.animeow.app.data.cloud.CloudFeedbackItem
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.CloudSessionExpiredException
import com.animeow.app.data.cloud.CloudSyncCoordinator
import com.animeow.app.data.cloud.CloudSyncPreferences
import com.animeow.app.data.cloud.CloudSyncResultType
import com.animeow.app.data.cloud.CloudSyncScheduler
import com.animeow.app.data.cloud.CloudSyncSettings
import com.animeow.app.data.cloud.PreparedCloudRestore
import com.animeow.app.util.runCatchingCancellable
import com.animeow.app.util.withDataTransferLock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class PreparedRestorePoint(
    val point: NativeRestorePoint,
    val prepared: PreparedNativeRestore,
)

data class CloudAccountUiState(
    val configured: Boolean = false,
    val session: CloudSession? = null,
    val backups: List<CloudBackupInfo> = emptyList(),
    val feedback: List<CloudFeedbackItem> = emptyList(),
    val viewerIsAdmin: Boolean = false,
    val viewerIsDeveloper: Boolean = false,
    val restorePoints: List<NativeRestorePoint> = emptyList(),
    val syncSettings: CloudSyncSettings = CloudSyncSettings(),
    val preparedRestore: PreparedCloudRestore? = null,
    val preparedRestorePoint: PreparedRestorePoint? = null,
    val conflictBackup: CloudBackupInfo? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

class CloudAccountViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AniMeowApplication
    private val service = CloudAccountService(
        context = application,
        nativeBackupService = app.nativeBackupService,
        legacyBackupImporter = app.legacyBackupImporter,
    )
    private val syncPreferences = CloudSyncPreferences(application)
    private val syncCoordinator = CloudSyncCoordinator(service, syncPreferences, app.operationLog)
    private val _state = MutableStateFlow(
        CloudAccountUiState(
            configured = service.isConfigured,
            session = service.loadSession(),
            restorePoints = service.listRestorePoints(),
        ),
    )
    val state: StateFlow<CloudAccountUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            syncPreferences.settings.collect { settings ->
                val current = _state.value
                _state.value = current.copy(
                    syncSettings = settings,
                    conflictBackup = current.conflictBackup ?: current.backups.firstOrNull {
                        it.id == settings.pendingConflictBackupId
                    },
                )
            }
        }
        if (_state.value.configured) {
            if (_state.value.session != null) refresh() else refreshFeedback()
        }
    }

    fun login(username: String, password: String) = runBusy {
        val session = service.login(username, password)
        _state.value = _state.value.copy(session = session)
        refreshNow(session)
        "欢迎回来，${session.username}"
    }

    fun register(username: String, password: String) = runBusy {
        val session = service.register(username, password)
        _state.value = _state.value.copy(session = session)
        refreshNow(session)
        "账号已创建并登录"
    }

    fun resetPassword(username: String, password: String, inviteCode: String) = runBusy {
        service.resetPassword(username, password, inviteCode)
        discardPreparedState()
        service.logout()
        _state.value = _state.value.copy(
            session = null,
            backups = emptyList(),
            feedback = emptyList(),
            preparedRestore = null,
            preparedRestorePoint = null,
            conflictBackup = null,
        )
        "密码已修改，请使用新密码重新登录"
    }

    fun logout() {
        discardPreparedState()
        service.logout()
        _state.value = _state.value.copy(
            session = null,
            backups = emptyList(),
            feedback = emptyList(),
            preparedRestore = null,
            preparedRestorePoint = null,
            conflictBackup = null,
            busy = false,
            message = "已退出云账号；本地资料不受影响",
        )
    }

    fun refresh() = viewModelScope.launch {
        val session = _state.value.session ?: return@launch
        _state.value = _state.value.copy(busy = true, message = null)
        runCatchingCancellable { refreshNow(session) }
            .onFailure { _state.value = _state.value.copy(message = handleFailure(it, "刷新云端数据失败")) }
        _state.value = _state.value.copy(busy = false)
    }

    fun refreshFeedback() = viewModelScope.launch {
        if (!_state.value.configured || _state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true, message = null)
        runCatchingCancellable { service.listFeedback(_state.value.session) }
            .onSuccess { pool ->
                _state.value = _state.value.copy(
                    feedback = pool.items,
                    viewerIsAdmin = pool.viewerIsAdmin,
                    viewerIsDeveloper = pool.viewerIsDeveloper,
                )
            }
            .onFailure { error ->
                _state.value = _state.value.copy(message = handleFailure(error, "刷新反馈池失败"))
            }
        _state.value = _state.value.copy(busy = false)
    }

    fun uploadBackup() = runBusy {
        withDataTransferLock {
            val session = requireSession()
            val sync = syncPreferences.snapshot()
            val prepared = service.prepareUpload()
            val fingerprint = prepared.summary.dataFingerprint
            val uploaded = service.uploadPrepared(session, prepared, sync.deviceId)
            syncPreferences.markSynced(
                fingerprint = fingerprint,
                remoteBackupId = uploaded.id,
                message = "已手动上传本机完整备份",
                syncedAt = Instant.now().toString(),
            )
            refreshNow(session)
            "已上传 ${formatBytes(uploaded.payloadSize)} 的完整备份"
        }
    }

    fun syncNow() = runBusy {
        val result = syncCoordinator.synchronize(requireSession())
        _state.value = _state.value.copy(
            conflictBackup = result.remoteBackup.takeIf { result.type == CloudSyncResultType.CONFLICT },
        )
        refreshNow(requireSession())
        result.message
    }

    fun updateSyncConfiguration(
        enabled: Boolean,
        intervalHours: Int,
        unmeteredOnly: Boolean,
        conflictPolicy: CloudConflictPolicy,
    ) = runBusy {
        syncPreferences.updateConfiguration(enabled, intervalHours, unmeteredOnly, conflictPolicy)
        val updated = syncPreferences.snapshot()
        CloudSyncScheduler.apply(getApplication(), updated)
        if (enabled) "后台同步已启用" else "后台同步已关闭"
    }

    fun resolveConflictWithLocal() = runBusy {
        val result = syncCoordinator.resolveWithLocal(requireSession())
        _state.value = _state.value.copy(conflictBackup = null)
        refreshNow(requireSession())
        result.message
    }

    fun resolveConflictWithCloud() = runBusy {
        val backup = _state.value.conflictBackup ?: error("没有待处理的云端冲突")
        val result = syncCoordinator.resolveWithCloud(requireSession(), backup)
        _state.value = _state.value.copy(conflictBackup = null, restorePoints = service.listRestorePoints())
        refreshNow(requireSession())
        result.message
    }

    fun resolveConflictSelectively(selection: NativeRestoreSelection) = runBusy {
        val backup = _state.value.conflictBackup ?: error("没有待处理的云端冲突")
        val result = syncCoordinator.resolveSelectively(requireSession(), backup, selection)
        _state.value = _state.value.copy(conflictBackup = null, restorePoints = service.listRestorePoints())
        refreshNow(requireSession())
        result.message
    }

    fun dismissConflict() {
        _state.value = _state.value.copy(conflictBackup = null)
        viewModelScope.launch { syncPreferences.clearPendingConflict() }
    }

    fun prepareRestore(backup: CloudBackupInfo) = runBusy {
        _state.value.preparedRestore?.let(service::discard)
        val prepared = service.prepareRestore(requireSession(), backup)
        _state.value = _state.value.copy(preparedRestore = prepared)
        if (prepared.format == CloudBackupFormat.LEGACY) {
            "已识别为 1.3.9 旧版备份，可安全迁移到 2.0"
        } else {
            "备份校验完成"
        }
    }

    fun confirmRestore(selection: NativeRestoreSelection) = runBusy {
        require(!selection.isEmpty) { "至少选择一类需要恢复的数据" }
        val prepared = _state.value.preparedRestore ?: error("没有待恢复的备份")
        withDataTransferLock {
            val result = service.restore(prepared, selection)
            if (prepared.format == CloudBackupFormat.LEGACY) {
                syncPreferences.resetBaseline(
                    "已迁移旧版云备份，下一次同步将上传 2.0 格式快照",
                    Instant.now().toString(),
                )
            } else {
                syncPreferences.markSynced(
                    result.summary.dataFingerprint,
                    prepared.backup.id,
                    "已手动恢复云端备份",
                    Instant.now().toString(),
                )
            }
            _state.value = _state.value.copy(
                preparedRestore = null,
                restorePoints = service.listRestorePoints(),
            )
            if (prepared.format == CloudBackupFormat.LEGACY) {
                "已迁移 ${result.summary.animeCount} 部作品；当前外观和账号设置已保留，恢复前快照已创建"
            } else {
                val restored = buildList {
                    if (selection.libraryAndCharacters) add("资料库与封面")
                    if (selection.analysisHistory) add("AI 分析历史")
                    if (selection.appearanceSettings) add("自定义配置与品牌资源")
                }.joinToString("、")
                "已恢复$restored；恢复前快照已保留"
            }
        }
    }

    fun cancelRestore() {
        _state.value.preparedRestore?.let(service::discard)
        _state.value = _state.value.copy(preparedRestore = null)
    }

    fun deleteBackup(backup: CloudBackupInfo) = runBusy {
        withDataTransferLock {
            service.deleteBackup(requireSession(), backup)
            if (_state.value.conflictBackup?.id == backup.id) {
                _state.value = _state.value.copy(conflictBackup = null)
            }
            refreshNow(requireSession())
            "云端备份已删除"
        }
    }

    fun prepareRestorePoint(point: NativeRestorePoint) = runBusy {
        _state.value.preparedRestorePoint?.let { service.discardRestorePoint(it.prepared) }
        val prepared = service.prepareRestorePoint(point)
        _state.value = _state.value.copy(preparedRestorePoint = PreparedRestorePoint(point, prepared))
        "恢复点校验完成"
    }

    fun confirmRestorePoint() = runBusy {
        val prepared = _state.value.preparedRestorePoint ?: error("没有待恢复的本机恢复点")
        withDataTransferLock {
            val summary = service.restorePoint(prepared.prepared)
            syncPreferences.resetBaseline("已恢复本机恢复点，等待重新同步", Instant.now().toString())
            _state.value = _state.value.copy(preparedRestorePoint = null)
            "已回退到 ${prepared.point.createdAt.replace('T', ' ').take(19)}，共 ${summary.animeCount} 部作品"
        }
    }

    fun cancelRestorePoint() {
        _state.value.preparedRestorePoint?.let { service.discardRestorePoint(it.prepared) }
        _state.value = _state.value.copy(preparedRestorePoint = null)
    }

    fun submitFeedback(content: String) = runBusy {
        service.submitFeedback(requireSession(), content)
        val pool = service.listFeedback(requireSession())
        _state.value = _state.value.copy(
            feedback = pool.items,
            viewerIsAdmin = pool.viewerIsAdmin,
            viewerIsDeveloper = pool.viewerIsDeveloper,
        )
        "反馈已提交"
    }

    fun setFeedbackStatus(feedbackId: Long, status: String) = runBusy {
        service.setFeedbackStatus(requireSession(), feedbackId, status)
        refreshFeedbackNow(requireSession())
        "反馈状态已更新"
    }

    fun replyFeedback(feedbackId: Long, reply: String) = runBusy {
        service.replyFeedback(requireSession(), feedbackId, reply)
        refreshFeedbackNow(requireSession())
        "反馈已回复"
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    override fun onCleared() {
        discardPreparedState()
        super.onCleared()
    }

    private fun discardPreparedState() {
        _state.value.preparedRestore?.let(service::discard)
        _state.value.preparedRestorePoint?.let { service.discardRestorePoint(it.prepared) }
    }

    private fun runBusy(block: suspend () -> String) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true, message = null)
        val message = try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            handleFailure(error, "云端操作失败")
        }
        _state.value = _state.value.copy(busy = false, message = message)
    }

    private suspend fun refreshNow(session: CloudSession) {
        val backups = service.listBackups(session)
        val pool = service.listFeedback(session)
        _state.value = _state.value.copy(
            backups = backups,
            feedback = pool.items,
            viewerIsAdmin = pool.viewerIsAdmin,
            viewerIsDeveloper = pool.viewerIsDeveloper,
            restorePoints = service.listRestorePoints(),
            conflictBackup = _state.value.conflictBackup ?: backups.firstOrNull {
                it.id == _state.value.syncSettings.pendingConflictBackupId
            },
        )
    }

    private suspend fun refreshFeedbackNow(session: CloudSession) {
        val pool = service.listFeedback(session)
        _state.value = _state.value.copy(
            feedback = pool.items,
            viewerIsAdmin = pool.viewerIsAdmin,
            viewerIsDeveloper = pool.viewerIsDeveloper,
        )
    }

    private fun handleFailure(error: Throwable, fallback: String): String {
        if (error is CloudSessionExpiredException) {
            discardPreparedState()
            service.logout()
            _state.value = _state.value.copy(
                session = null,
                backups = emptyList(),
                feedback = emptyList(),
                preparedRestore = null,
                preparedRestorePoint = null,
                conflictBackup = null,
            )
            viewModelScope.launch { syncPreferences.clearPendingConflict() }
        }
        return error.message ?: fallback
    }

    private fun requireSession(): CloudSession = _state.value.session ?: error("请先登录云账号")

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        else -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
    }
}
