package com.animeow.app.ui.cloud

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.backup.NativeRestorePoint
import com.animeow.app.data.bangumi.BangumiSyncRunner
import com.animeow.app.data.bangumi.BangumiSyncScheduler
import com.animeow.app.data.bangumi.bangumiSyncResultMessage
import com.animeow.app.data.backup.NativeRestoreSelection
import com.animeow.app.data.backup.PreparedNativeRestore
import com.animeow.app.data.cloud.BangumiAccountService
import com.animeow.app.data.cloud.BangumiApiException
import com.animeow.app.data.cloud.BangumiBinding
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
import com.animeow.app.data.cloud.uploadDeviceBackup
import com.animeow.app.data.remote.BangumiOAuthCallback
import com.animeow.app.util.runCatchingCancellable
import com.animeow.app.util.withDataTransferLock
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val feedbackError: String? = null,
    val viewerIsAdmin: Boolean = false,
    val viewerIsDeveloper: Boolean = false,
    val restorePoints: List<NativeRestorePoint> = emptyList(),
    val syncSettings: CloudSyncSettings = CloudSyncSettings(),
    val bangumiPendingCount: Int = 0,
    val preparedRestore: PreparedCloudRestore? = null,
    val preparedRestorePoint: PreparedRestorePoint? = null,
    val conflictBackup: CloudBackupInfo? = null,
    val bangumi: BangumiBinding? = null,
    val hasPassword: Boolean = true,
    val bangumiAuthorizeUrl: String? = null,
    val passwordSetupRequested: Boolean = false,
    /** 「立即同步」正在执行（显示进度，按钮不可重复点）。 */
    val bangumiSyncing: Boolean = false,
    /** 开启了「仅不限流量网络」且当前是按流量计费的网络：询问是否仍然立即同步。 */
    val bangumiMeteredPrompt: Boolean = false,
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
    private val bangumiService = BangumiAccountService(context = application)
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
        viewModelScope.launch {
            app.database.libraryDao().observeBangumiPendingSyncCount().collect { count ->
                _state.update { it.copy(bangumiPendingCount = count) }
            }
        }
        if (_state.value.configured) {
            if (_state.value.session != null) refresh() else refreshFeedback()
        }
    }

    fun login(username: String, password: String) = runBusy {
        val session = service.login(username, password)
        finishLogin(session, "欢迎回来，${session.username}")
    }

    fun loginWithDevice() = runBusy {
        finishLogin(service.loginWithDevice(), "已使用本机登录")
    }

    fun register(username: String, password: String) = runBusy {
        val session = service.register(username, password)
        finishLogin(session, "账号已创建并登录")
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

    /** 唤起浏览器走 Bangumi 授权；回跳经 DeepLink 进入 [handleBangumiCallback]。 */
    fun startBangumiAuth() = runBusy {
        val link = bangumiService.requestAuthorizeUrl()
        _state.value = _state.value.copy(bangumiAuthorizeUrl = link.authorizeUrl)
        app.operationLog.record("发起 Bangumi 授权", "账号与同步")
        "正在打开 Bangumi 授权页…"
    }

    fun consumeBangumiAuthorizeUrl() {
        _state.value = _state.value.copy(bangumiAuthorizeUrl = null)
    }

    fun handleBangumiCallback(callback: BangumiOAuthCallback) = runBusy {
        app.operationLog.record(
            "Bangumi 授权回跳",
            "账号与同步",
            mapOf(
                "方式" to if (callback.ticket != null) "中转" else "直跳",
                "error" to callback.error,
            ),
        )
        if (callback.error != null) return@runBusy bangumiCallbackErrorMessage(callback.error)
        val exchanged = bangumiService.exchange(
            code = callback.code,
            state = callback.state,
            ticket = callback.ticket,
        )
        val hadSession = _state.value.session != null
        val hadBinding = _state.value.bangumi != null
        val welcome = if (hadSession) {
            null
        } else {
            finishLogin(
                exchanged.session,
                "已通过 Bangumi 登录，欢迎 ${exchanged.bangumi.nickname ?: exchanged.bangumi.username}",
            )
        }
        val profile = runCatchingCancellable { bangumiService.fetchProfile() }.getOrNull()
        _state.value = _state.value.copy(
            bangumi = profile?.bangumi ?: exchanged.bangumi,
            hasPassword = profile?.hasPassword ?: _state.value.hasPassword,
        )
        app.operationLog.record(
            if (hadSession) "Bangumi 绑定成功" else "Bangumi 登录成功",
            "账号与同步",
            mapOf("bangumiId" to exchanged.bangumi.id),
        )
        // 绑定/换绑成功后立刻排空一次待同步队列（开关未开时是空操作）
        BangumiSyncScheduler.schedule(getApplication())
        welcome ?: if (hadBinding) "Bangumi 授权已更新" else "Bangumi 账号已绑定"
    }

    fun unbindBangumi() = runBusy {
        try {
            bangumiService.unbind()
        } catch (error: BangumiApiException) {
            if (error.code == "password_required") {
                _state.value = _state.value.copy(passwordSetupRequested = true)
                app.operationLog.record("Bangumi 解绑受阻：尚未设置密码", "账号与同步")
                return@runBusy "请先设置登录密码，再解除绑定"
            }
            throw error
        }
        _state.value = _state.value.copy(bangumi = null)
        app.operationLog.record("解除 Bangumi 绑定", "账号与同步")
        "已解除绑定，本地追番记录不受影响"
    }

    /** SSO 自动建号的首设密码（解绑、其他设备密码登录的前置）。 */
    fun setFirstPassword(password: String) = runBusy {
        bangumiService.setFirstPassword(password)
        _state.value = _state.value.copy(hasPassword = true, passwordSetupRequested = false)
        app.operationLog.record("设置登录密码", "账号与同步")
        "登录密码已设置"
    }

    fun consumePasswordSetupRequest() {
        _state.value = _state.value.copy(passwordSetupRequested = false)
    }

    fun logout() {
        if (_state.value.busy) return
        discardPreparedState()
        service.logout()
        bangumiService.clearTokens()
        _state.value = _state.value.copy(
            session = null,
            backups = emptyList(),
            feedback = emptyList(),
            preparedRestore = null,
            preparedRestorePoint = null,
            conflictBackup = null,
            bangumi = null,
            busy = false,
            message = "已退出云账号；本地资料不受影响",
        )
    }

    fun refresh() = viewModelScope.launch {
        if (_state.value.busy) return@launch
        val session = _state.value.session ?: return@launch
        _state.value = _state.value.copy(busy = true, message = null)
        runCatchingCancellable { refreshNow(session) }
            .onFailure { _state.value = _state.value.copy(message = handleFailure(it, "刷新云端数据失败")) }
        _state.value = _state.value.copy(busy = false)
    }

    fun refreshFeedback() = viewModelScope.launch {
        if (!_state.value.configured || _state.value.busy) return@launch
        val session = _state.value.session
        _state.value = _state.value.copy(busy = true, message = null, feedbackError = null)
        runCatchingCancellable { service.listFeedback(session) }
            .onSuccess { pool ->
                if (!isCurrentSession(session)) return@onSuccess
                _state.value = _state.value.copy(
                    feedback = pool.items,
                    feedbackError = null,
                    viewerIsAdmin = pool.viewerIsAdmin,
                    viewerIsDeveloper = pool.viewerIsDeveloper,
                )
            }
            .onFailure { error ->
                if (_state.value.session?.token != session?.token) return@onFailure
                val message = handleFailure(error, "反馈暂时加载失败，请重试")
                _state.value = _state.value.copy(feedbackError = message)
            }
        _state.value = _state.value.copy(busy = false)
    }

    fun uploadBackup() = runBusy {
        val session = requireSession()
        val uploaded = uploadDeviceBackup(session, service, syncPreferences)
        val refreshed = runCatchingCancellable { refreshNow(session) }
        if (refreshed.isFailure) {
            handleFailure(refreshed.exceptionOrNull()!!, "暂时无法刷新备份列表")
            "完整备份已上传，列表暂时未能刷新，可稍后重试"
        } else {
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

    fun updateBangumiSync(
        progressSync: Boolean,
        ratingSync: Boolean,
        wifiOnly: Boolean,
    ) = runBusy {
        syncPreferences.updateBangumiSyncConfiguration(progressSync, ratingSync, wifiOnly)
        BangumiSyncScheduler.apply(getApplication(), syncPreferences.snapshot())
        when {
            progressSync && ratingSync -> "进度与评分将自动同步到 Bangumi"
            progressSync -> "进度与状态将自动同步到 Bangumi"
            ratingSync -> "评分将自动同步到 Bangumi"
            else -> "Bangumi 同步已关闭"
        }
    }

    /**
     * 立即同步：在前台真正执行一次排空并报告结果，而不是只把任务交给 WorkManager。
     * [allowMetered] = 用户在「仅不限流量网络」提示里选择了仍然同步。
     */
    fun syncBangumiNow(allowMetered: Boolean = false) = runBusy {
        _state.update { it.copy(bangumiMeteredPrompt = false) }
        if (_state.value.bangumiPendingCount <= 0) return@runBusy "没有待上报的改动"
        val settings = syncPreferences.snapshot()
        val (connected, metered) = networkState()
        if (!connected) return@runBusy "当前没有网络连接；改动已保留在本机，联网后会自动上报"
        if (settings.bangumiSyncWifiOnly && metered && !allowMetered) {
            _state.update { it.copy(bangumiMeteredPrompt = true) }
            return@runBusy "已开启「仅不限流量网络」，当前是移动数据等计费网络"
        }
        _state.update { it.copy(bangumiSyncing = true) }
        try {
            val runner = BangumiSyncRunner(app)
            val report = runner.drain(manual = true) ?: return@runBusy "没有待上报的改动"
            // 临时故障交给后台任务按退避重试；授权、登录、中转未开通需要用户或服务器先处理
            if (runner.lastRetryable) BangumiSyncScheduler.schedule(getApplication(), settings.bangumiSyncWifiOnly)
            bangumiSyncResultMessage(report)
        } finally {
            _state.update { it.copy(bangumiSyncing = false) }
        }
    }

    fun dismissBangumiMeteredPrompt() {
        _state.update { it.copy(bangumiMeteredPrompt = false) }
    }

    /** 前往 Bangumi 官网授权前的网络提示：用户勾选「不再提示」后保存。 */
    fun setBangumiLoginTipHidden(hidden: Boolean) {
        viewModelScope.launch { syncPreferences.setBangumiLoginTipHidden(hidden) }
    }

    fun setBangumiShowHomeStatus(visible: Boolean) {
        viewModelScope.launch { syncPreferences.setBangumiShowHomeStatus(visible) }
    }

    /** 当前是否联网、是否按流量计费；读取失败按「已联网、不计费」处理，交给同步本身报告结果。 */
    private fun networkState(): Pair<Boolean, Boolean> {
        val manager = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
            ?: return true to false
        return runCatching {
            val network = manager.activeNetwork ?: return@runCatching false to false
            val capabilities = manager.getNetworkCapabilities(network) ?: return@runCatching false to false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) to manager.isActiveNetworkMetered
        }.getOrDefault(true to false)
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
                    "已导入旧版云备份，下次同步将更新为新版云备份",
                    Instant.now().toString(),
                )
            } else if (selection == NativeRestoreSelection()) {
                syncPreferences.markSynced(
                    result.summary.dataFingerprint,
                    prepared.backup.id,
                    "已手动恢复云端备份",
                    Instant.now().toString(),
                )
            } else {
                syncPreferences.markResult("已恢复部分云端备份", Instant.now().toString())
            }
            _state.value = _state.value.copy(
                preparedRestore = null,
                restorePoints = service.listRestorePoints(),
            )
            if (prepared.format == CloudBackupFormat.LEGACY) {
                "已导入 ${result.summary.animeCount} 部作品；当前外观与账号设置不变，已自动保存恢复点"
            } else {
                val restored = buildList {
                    if (selection.libraryAndCharacters) add("收藏资料与封面")
                    if (selection.analysisHistory) add("AI 分析历史")
                    if (selection.appearanceSettings) add("自定义配置与品牌资源")
                }.joinToString("、")
                "已恢复$restored（已自动保存恢复点）"
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
            "已回退到 ${prepared.point.createdAt.replace('T', ' ').take(19)}，共 ${summary.animeCount} 部动画/书籍、${summary.gameCount} 部游戏、${summary.audioCount} 部音声"
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
        if (!isCurrentSession(session)) return
        val feedbackResult = runCatchingCancellable { service.listFeedback(session) }
        feedbackResult.exceptionOrNull()?.let { if (it is CloudSessionExpiredException) throw it }
        val profileResult = runCatchingCancellable { bangumiService.fetchProfile() }
        profileResult.exceptionOrNull()?.let { if (it is CloudSessionExpiredException) throw it }
        if (!isCurrentSession(session)) return
        val pool = feedbackResult.getOrNull()
        _state.value = _state.value.copy(
            backups = backups,
            feedback = pool?.items ?: _state.value.feedback,
            feedbackError = feedbackResult.exceptionOrNull()?.message,
            viewerIsAdmin = pool?.viewerIsAdmin ?: _state.value.viewerIsAdmin,
            viewerIsDeveloper = pool?.viewerIsDeveloper ?: _state.value.viewerIsDeveloper,
            restorePoints = service.listRestorePoints(),
            conflictBackup = _state.value.conflictBackup ?: backups.firstOrNull {
                it.id == _state.value.syncSettings.pendingConflictBackupId
            },
        )
        // 绑定状态与首设密码标记以服务端为准（解绑/换绑后不能留残影）
        profileResult.getOrNull()?.let { profile ->
            _state.value = _state.value.copy(bangumi = profile.bangumi, hasPassword = profile.hasPassword)
        }
    }

    private suspend fun refreshFeedbackNow(session: CloudSession) {
        val pool = service.listFeedback(session)
        if (!isCurrentSession(session)) return
        _state.value = _state.value.copy(
            feedback = pool.items,
            feedbackError = null,
            viewerIsAdmin = pool.viewerIsAdmin,
            viewerIsDeveloper = pool.viewerIsDeveloper,
        )
    }

    private fun handleFailure(error: Throwable, fallback: String): String {
        app.operationLog.recordError(error, "账号与同步")
        if (error is CloudSessionExpiredException) {
            if (!error.isFor(_state.value.session) || service.loadSession() != null) {
                return "账号状态已更新，请重试此操作"
            }
            discardPreparedState()
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

    private fun isCurrentSession(session: CloudSession?): Boolean =
        _state.value.session?.token == session?.token && service.loadSession()?.token == session?.token

    private suspend fun finishLogin(session: CloudSession, welcome: String): String {
        discardPreparedState()
        _state.value = _state.value.copy(
            session = session, backups = emptyList(), feedback = emptyList(), feedbackError = null,
            viewerIsAdmin = false, viewerIsDeveloper = false, preparedRestore = null,
            preparedRestorePoint = null, conflictBackup = null,
        )
        return try {
            refreshNow(session)
            welcome
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = handleFailure(error, "云端数据暂时未能刷新，请稍后重试")
            if (_state.value.session?.token == session.token) "已登录；$message" else message
        }
    }

    private fun requireSession(): CloudSession = _state.value.session ?: error("请先登录云账号")

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        else -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
    }
}

/** 授权回跳 error 参数 → 用户可读文案（S1 直跳与后端中转回跳共用）。 */
internal fun bangumiCallbackErrorMessage(error: String): String = when (error) {
    "access_denied" -> "已取消 Bangumi 授权，未做任何更改"
    "state_invalid" -> "登录凭证已失效，请重新发起登录"
    else -> "Bangumi 授权失败，请稍后重试"
}
