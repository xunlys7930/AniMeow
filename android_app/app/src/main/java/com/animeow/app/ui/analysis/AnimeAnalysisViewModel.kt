package com.animeow.app.ui.analysis

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.analysis.CustomModelKeyStore
import com.animeow.app.data.analysis.AnimeAnalysisPreferences
import com.animeow.app.data.analysis.AnimeAnalysisService
import com.animeow.app.data.analysis.AnimeAnalysisSettings
import com.animeow.app.data.analysis.AnimeAnalysisStatsPreview
import com.animeow.app.data.cloud.CloudAccountService
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.SecureCloudSessionStore
import com.animeow.app.data.local.AnimeAnalysisRecordEntity
import com.animeow.app.util.runCatchingCancellable
import com.animeow.app.util.userSafeErrorMessage
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject

data class AnimeAnalysisUiState(
    val configured: Boolean = false,
    val session: CloudSession? = null,
    val settings: AnimeAnalysisSettings = AnimeAnalysisSettings(),
    val stats: JSONObject? = null,
    val preview: AnimeAnalysisStatsPreview = AnimeAnalysisStatsPreview(),
    val records: List<AnimeAnalysisRecordEntity> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

class AnimeAnalysisViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AniMeowApplication
    private val cloudService = CloudAccountService(
        context = application,
        nativeBackupService = app.nativeBackupService,
        legacyBackupImporter = app.legacyBackupImporter,
    )
    private val service = AnimeAnalysisService(app.database, cloudService)
    private val preferences = AnimeAnalysisPreferences(application)
    private val customKeys = CustomModelKeyStore(application)
    private val sessionStore = SecureCloudSessionStore(application)
    private val _state = MutableStateFlow(
        AnimeAnalysisUiState(
            configured = cloudService.isConfigured,
            session = sessionStore.load(),
        ),
    )
    val state: StateFlow<AnimeAnalysisUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.settings.collect { settings ->
                _state.value = _state.value.copy(settings = settings)
            }
        }
        viewModelScope.launch {
            service.observeRecords().collect { records ->
                _state.value = _state.value.copy(records = records)
            }
        }
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true, message = null, session = sessionStore.load())
        runCatchingCancellable { service.buildStats() }
            .onSuccess { built -> _state.value = _state.value.copy(stats = built.json, preview = built.preview) }
            .onFailure { error -> _state.value = _state.value.copy(message = userSafeErrorMessage(error, "统计摘要生成失败，请稍后重试")) }
        _state.value = _state.value.copy(busy = false)
    }

    fun generateServerAnalysis() = runBusy {
        val session = sessionStore.load() ?: error("请先登录云账号")
        _state.value = _state.value.copy(session = session)
        val record = service.requestServerAnalysis(session)
        app.operationLog.record("生成云端 AI 看番分析", "AI 分析", mapOf("recordId" to record.id))
        "分析完成并已保存到本地"
    }

    fun generateCustomAnalysis(settings: AnimeAnalysisSettings, apiKey: String, rememberKey: Boolean) = runBusy {
        customKeys.save(settings.endpoint, apiKey, rememberKey)
        preferences.save(settings)
        val record = service.requestCustomAnalysis(settings, apiKey, sessionStore.load())
        app.operationLog.record("生成自定义模型看番分析", "AI 分析", mapOf("model" to settings.model, "recordId" to record.id))
        "自定义模型分析已保存"
    }

    fun saveCustomSettings(settings: AnimeAnalysisSettings, apiKey: String, rememberKey: Boolean) = runBusy {
        customKeys.save(settings.endpoint, apiKey, rememberKey)
        preferences.save(settings)
        "自定义模型配置已保存"
    }

    fun loadCustomKey(endpoint: String): String = customKeys.load(endpoint)

    fun forgetCustomKey(endpoint: String) = customKeys.save(endpoint, "", false)

    suspend fun fetchCustomModels(endpoint: String, apiKey: String): List<String> = service.fetchCustomModels(endpoint, apiKey)

    suspend fun testCustomConnection(settings: AnimeAnalysisSettings, apiKey: String): Long = service.testCustomConnection(settings, apiKey)

    fun resetCustomSettings() = runBusy {
        customKeys.clear()
        preferences.reset()
        "自定义模型配置已恢复默认"
    }

    fun deleteRecord(recordId: Long) = runBusy {
        service.deleteRecord(recordId)
        "分析记录已删除"
    }

    fun currentPrompt(): String? {
        val stats = _state.value.stats ?: return null
        return service.buildPrompt(stats, _state.value.settings.promptTemplate)
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    fun generatedServerToday(): Boolean {
        val session = _state.value.session ?: return false
        val today = java.time.LocalDate.now()
        return _state.value.records.any { record ->
            record.userId == session.userId && record.serverRecordId != null &&
                record.createdAt?.let { runCatching { Instant.parse(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }.getOrNull() } == today
        }
    }

    private fun runBusy(block: suspend () -> String) = viewModelScope.launch {
        if (_state.value.busy) return@launch
        _state.value = _state.value.copy(busy = true, message = null)
        val message = runCatchingCancellable { block() }.fold(
            onSuccess = { it },
            onFailure = { error ->
                app.operationLog.recordError(error, "AI 分析")
                userSafeErrorMessage(error, "AI 分析暂时未能完成，请检查网络与配置后重试")
            },
        )
        _state.value = _state.value.copy(busy = false, message = message)
    }
}
