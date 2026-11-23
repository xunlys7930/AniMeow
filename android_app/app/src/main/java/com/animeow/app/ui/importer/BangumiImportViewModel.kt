package com.animeow.app.ui.importer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.BangumiImportConflictStrategy
import com.animeow.app.data.BangumiImportSummary
import com.animeow.app.data.bangumi.BangumiRelayService
import com.animeow.app.data.bangumi.BangumiMergeRow
import com.animeow.app.data.bangumi.BangumiRelayUnavailableException
import com.animeow.app.data.bangumi.BangumiRouteMemory
import com.animeow.app.data.bangumi.BangumiSyncFailureKind
import com.animeow.app.data.bangumi.BangumiSyncRoute
import com.animeow.app.data.bangumi.classifyBangumiSyncFailure
import com.animeow.app.data.bangumi.isDirectRouteDown
import com.animeow.app.data.bangumi.shouldFallbackToRelay
import com.animeow.app.data.cloud.BangumiAccountService
import com.animeow.app.data.cloud.isBangumiAuthRejected
import com.animeow.app.data.importer.BangumiCollectionImporter
import com.animeow.app.data.importer.BangumiCollectionPreview
import com.animeow.app.data.remote.bangumiFailureMessage
import com.animeow.app.data.remote.normalizeBangumiUsername
import com.animeow.app.util.runCatchingCancellable
import com.animeow.app.util.withDataTransferLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BangumiImportUiState {
    data object Idle : BangumiImportUiState
    data class Loading(val username: String) : BangumiImportUiState
    data class Ready(
        val preview: BangumiCollectionPreview,
        val strategy: BangumiImportConflictStrategy = BangumiImportConflictStrategy.MERGE,
        val selectedSubjectIds: Set<Long> = preview.candidates.mapTo(linkedSetOf()) { it.item.subjectId },
        val importTags: Boolean = true,
        val message: String? = null,
        /** 非阻塞黄标（如 token 失败降级公开路径），导入照常可继续。 */
        val warning: String? = null,
        val comparison: List<BangumiMergeRow>? = null,
        val showingComparison: Boolean = false,
    ) : BangumiImportUiState
    data class Importing(val request: Ready) : BangumiImportUiState {
        val preview: BangumiCollectionPreview get() = request.preview
    }
    data class Success(val summary: BangumiImportSummary) : BangumiImportUiState
    data class Error(val message: String) : BangumiImportUiState
}

class BangumiImportViewModel(application: Application) : AndroidViewModel(application) {
    private val importer = BangumiCollectionImporter((application as AniMeowApplication).database, application)
    private val accountService = BangumiAccountService(context = application)
    private val relayService = BangumiRelayService(application)
    private val _state = MutableStateFlow<BangumiImportUiState>(BangumiImportUiState.Idle)
    val state: StateFlow<BangumiImportUiState> = _state.asStateFlow()
    private val _boundAccount = MutableStateFlow<String?>(null)
    val boundAccount: StateFlow<String?> = _boundAccount.asStateFlow()

    init {
        viewModelScope.launch {
            _boundAccount.value = runCatchingCancellable { accountService.fetchProfile() }
                .getOrNull()?.bangumi?.username
                ?: accountService.loadTokens()?.username?.takeIf(String::isNotBlank)
        }
    }

    /** 未绑定路径：一字不改的公开收藏预检。 */
    fun inspect(username: String) {
        val cleanUsername = normalizeBangumiUsername(username)
        if (cleanUsername.isEmpty()) {
            _state.value = BangumiImportUiState.Error("请输入 Bangumi 用户名或 UID")
            return
        }
        viewModelScope.launch {
            _state.value = BangumiImportUiState.Loading(cleanUsername)
            _state.value = runCatchingCancellable { importer.preview(cleanUsername) }
                .fold(
                    onSuccess = { BangumiImportUiState.Ready(it) },
                    onFailure = { BangumiImportUiState.Error(it.userFacingMessage("读取公开收藏失败")) },
                )
        }
    }

    /** 已绑定路径：免输用户名、token 直连（含私密收藏），失败按「换新重试一次 → 降级公开」兜底。 */
    fun inspectMine() {
        viewModelScope.launch {
            val username = _boundAccount.value.orEmpty()
            _state.value = BangumiImportUiState.Loading(username)
            val resolved = username.ifEmpty {
                val name = runCatchingCancellable { accountService.fetchProfile() }
                    .getOrNull()?.bangumi?.username.orEmpty()
                if (name.isNotEmpty()) _boundAccount.value = name
                name
            }
            if (resolved.isEmpty()) {
                _state.value = BangumiImportUiState.Error(
                    "尚未绑定 Bangumi 账号：可到「账号与同步」绑定后免输用户名导入（含私密收藏），或输入用户名导入公开收藏",
                )
                return@launch
            }
            _state.value = runCatchingCancellable {
                val (preview, warning) = previewMineWithFallback(resolved)
                BangumiImportUiState.Ready(preview = preview, warning = warning, comparison = importer.compareAnime(preview))
            }
                .fold(
                    onSuccess = { it },
                    onFailure = { BangumiImportUiState.Error(it.userFacingMessage("读取收藏失败")) },
                )
        }
    }

    /**
     * 读取「我的收藏」（含私密）：按线路计划先直连或先经服务器中转，都失败再降级公开收藏 + 黄标。
     * 直连令牌被拒时先换新重试一次；10 分钟内直连失败过则先走中转，省掉一次超时。
     */
    private suspend fun previewMineWithFallback(username: String): Pair<BangumiCollectionPreview, String?> {
        var directError: Throwable? = null
        var relayError: Throwable? = null
        for (route in BangumiRouteMemory.plan()) {
            when (route) {
                BangumiSyncRoute.DIRECT -> {
                    val direct = runCatchingCancellable { previewDirect(username) }
                    if (direct.isSuccess) {
                        BangumiRouteMemory.markDirectOk()
                        return direct.getOrThrow() to null
                    }
                    val error = direct.exceptionOrNull()!!
                    directError = error
                    if (isDirectRouteDown(error)) BangumiRouteMemory.markDirectDown()
                    // 未登录 / 未绑定 / 授权过期：中转也会失败，直接降级公开收藏
                    if (!shouldFallbackToRelay(error)) break
                }
                BangumiSyncRoute.RELAY -> {
                    val relayed = runCatchingCancellable {
                        importer.previewSelfVia(username) { offset, limit ->
                            relayService.fetchMyAnimeCollectionsPage(offset, limit)
                        }
                    }
                    if (relayed.isSuccess) {
                        BangumiRouteMemory.markRelayOk()
                        return relayed.getOrThrow() to null
                    }
                    val error = relayed.exceptionOrNull()!!
                    relayError = error
                    if (error is BangumiRelayUnavailableException) {
                        BangumiRouteMemory.markRelayMissing()
                    } else if (directError == null && !shouldFallbackToRelay(error)) {
                        // 中转给出了账号层面的结论（未绑定 / 授权过期）：直连同样会失败
                        break
                    }
                }
            }
        }
        val fallback = runCatchingCancellable { importer.preview(username) }
        if (fallback.isSuccess) {
            return fallback.getOrThrow() to privateImportFallbackWarning(directError, relayError)
        }
        throw fallback.exceptionOrNull()!!
    }

    /** 直连（带令牌）读取；Bangumi 拒绝令牌时向服务端换新后重试一次。 */
    private suspend fun previewDirect(username: String): BangumiCollectionPreview {
        val first = runCatchingCancellable { importer.previewSelf(accountService.accessToken(), username) }
        if (first.isSuccess) return first.getOrThrow()
        val firstError = first.exceptionOrNull()!!
        if (!isBangumiAuthRejected(firstError)) throw firstError
        return importer.previewSelf(accountService.refreshTokens().accessToken, username)
    }

    fun selectStrategy(strategy: BangumiImportConflictStrategy) {
        val current = _state.value as? BangumiImportUiState.Ready ?: return
        _state.value = current.copy(strategy = strategy)
    }

    fun toggleComparison() {
        val current = _state.value as? BangumiImportUiState.Ready ?: return
        if (current.comparison != null) _state.value = current.copy(showingComparison = !current.showingComparison)
    }

    fun toggleSubject(subjectId: Long) {
        val current = _state.value as? BangumiImportUiState.Ready ?: return
        _state.value = current.copy(
            selectedSubjectIds = if (subjectId in current.selectedSubjectIds) {
                current.selectedSubjectIds - subjectId
            } else {
                current.selectedSubjectIds + subjectId
            },
        )
    }

    fun toggleAll() {
        val current = _state.value as? BangumiImportUiState.Ready ?: return
        val allIds = current.preview.candidates.mapTo(linkedSetOf()) { it.item.subjectId }
        _state.value = current.copy(
            selectedSubjectIds = if (current.selectedSubjectIds.size == allIds.size) emptySet() else allIds,
        )
    }

    fun setImportTags(enabled: Boolean) {
        val current = _state.value as? BangumiImportUiState.Ready ?: return
        _state.value = current.copy(importTags = enabled)
    }

    fun confirmImport() {
        val current = _state.value as? BangumiImportUiState.Ready ?: return
        val selectedPreview = current.preview.copy(
            candidates = current.preview.candidates.filter { it.item.subjectId in current.selectedSubjectIds },
        )
        if (selectedPreview.candidates.isEmpty()) return
        viewModelScope.launch {
            val request = current.copy(preview = selectedPreview, message = null)
            _state.value = BangumiImportUiState.Importing(request)
            _state.value = runCatchingCancellable {
                withDataTransferLock {
                    importer.applyImport(selectedPreview, current.strategy, current.importTags)
                }
            }
                .fold(
                    onSuccess = { BangumiImportUiState.Success(it) },
                    onFailure = {
                        current.copy(message = it.userFacingMessage("导入失败，资料库未发生改变"))
                    },
                )
        }
    }

    fun reset() {
        _state.value = BangumiImportUiState.Idle
    }
}

private fun Throwable.userFacingMessage(fallback: String): String =
    bangumiFailureMessage(this, fallback)

/** 私密收藏读不到、已改用公开收藏时的黄标：按最能说明问题的原因给下一步。 */
internal fun privateImportFallbackWarning(directError: Throwable?, relayError: Throwable?): String {
    val errors = listOfNotNull(relayError, directError)
    val kinds = errors.map { classifyBangumiSyncFailure(it).kind }
    return when {
        BangumiSyncFailureKind.AUTH_BLOCKED in kinds ->
            "Bangumi 授权不可用，已按公开收藏导入（不含私密收藏）；可在「账号与同步」重新连接后重试"
        BangumiSyncFailureKind.SESSION_BLOCKED in kinds ->
            "AniMeow 登录状态不可用，已按公开收藏导入（不含私密收藏）；重新登录后可读取私密收藏"
        errors.any { it is BangumiRelayUnavailableException } ->
            "直连 Bangumi 失败，服务器暂未开通 Bangumi 中转，已按公开收藏导入（不含私密收藏）"
        else -> "暂时读不到私密收藏，已按公开收藏导入（不含私密收藏）；可稍后重试"
    }
}
