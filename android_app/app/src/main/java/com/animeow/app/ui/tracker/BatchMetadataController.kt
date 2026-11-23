package com.animeow.app.ui.tracker

import com.animeow.app.data.LibraryRepository
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.RemoteFetchResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class BatchMetadataOutcome(val label: String) {
    PENDING("待处理"), UPDATED("已更新"), UNCHANGED("无需变更"), UNMATCHED("未匹配"), SKIPPED("已跳过"), FAILED("失败");
    val retryable: Boolean get() = this != UPDATED && this != UNCHANGED
}

data class BatchMetadataResult(val animeId: Long, val title: String, val outcome: BatchMetadataOutcome = BatchMetadataOutcome.PENDING,
    val detail: String = "等待处理")

data class BatchMetadataState(
    val isRunning: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val updated: Int = 0,
    val unmatched: Int = 0,
    val failed: Int = 0,
    val currentTitle: String? = null,
    val currentAnimeId: Long? = null,
    val stage: String = "正在准备",
    val usedSources: Set<String> = emptySet(),
    val results: List<BatchMetadataResult> = emptyList(),
    val message: String? = null,
) {
    val progress: Float get() = if (total <= 0) 0f else completed.toFloat() / total
    val retryIds: Set<Long> get() = results.filter { it.outcome.retryable }.mapTo(linkedSetOf()) { it.animeId }
    fun count(outcome: BatchMetadataOutcome): Int = results.count { it.outcome == outcome }
}

private class MetadataMatchSkipped : Exception()

internal class BatchMetadataController(
    private val scope: CoroutineScope,
    private val repository: LibraryRepository,
    private val search: suspend (AnimeEntity, String) -> RemoteFetchResult,
    private val onError: (Exception) -> Unit,
    private val onFinished: (Set<Long>) -> Unit,
) {
    private val _batchMetadataState = MutableStateFlow(BatchMetadataState())
    val state: StateFlow<BatchMetadataState> = _batchMetadataState.asStateFlow()
    private var batchMetadataJob: Job? = null
    private var batchMetadataOptions = BatchMetadataOptions()
    private var skipMetadataRequested = false
    private var currentMetadataRequest: Deferred<RemoteAnime?>? = null

    fun startBatchMetadataMatch(options: BatchMetadataOptions, ids: Set<Long>) {
        if (_batchMetadataState.value.isRunning || !options.fields.hasSelection || ids.isEmpty()) return
        batchMetadataOptions = options
        _batchMetadataState.value = BatchMetadataState(isRunning = true, total = ids.size)
        batchMetadataJob = scope.launch {
            try {
                val candidates = repository.getActiveAnimesSnapshot().filter { it.id in ids }
                _batchMetadataState.update { it.copy(total = candidates.size,
                    results = candidates.map { anime -> BatchMetadataResult(anime.id, anime.title) }) }
                for (anime in candidates) {
                    skipMetadataRequested = false
                    _batchMetadataState.update { it.copy(currentTitle = anime.title, currentAnimeId = anime.id, stage = "正在搜索并核对资料") }
                    var detail = ""
                    val outcome = try {
                        val remote = findRemoteMetadataMatch(anime)
                        if (skipMetadataRequested) throw MetadataMatchSkipped()
                        if (remote == null) {
                            detail = "未找到可安全匹配的同一部作品，可在编辑页手动选择"
                            BatchMetadataOutcome.UNMATCHED
                        } else {
                            _batchMetadataState.update { it.copy(stage = "正在保存所选资料", usedSources = it.usedSources + remote.sourceLabel) }
                            val changed = repository.applyRemoteMetadata(animeId = anime.id, remote = remote, fields = options.fields,
                                overwriteExisting = options.overwriteExisting, preservePersonalProgress = true)
                            detail = "${remote.sourceLabel} · " + if (changed) "已保存所选字段" else "所选字段没有可补内容或已是最新"
                            BatchMetadataOutcome.UPDATED.takeIf { changed } ?: BatchMetadataOutcome.UNCHANGED
                        }
                    } catch (_: MetadataMatchSkipped) {
                        detail = "本次主动跳过，尚未修改资料"
                        BatchMetadataOutcome.SKIPPED
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        detail = "来源或保存暂时失败，可重试；未完成的写入已撤销"
                        onError(error)
                        BatchMetadataOutcome.FAILED
                    }
                    _batchMetadataState.update { old -> old.copy(completed = old.completed + 1,
                        updated = old.updated + if (outcome == BatchMetadataOutcome.UPDATED) 1 else 0,
                        unmatched = old.unmatched + if (outcome == BatchMetadataOutcome.UNMATCHED) 1 else 0,
                        failed = old.failed + if (outcome == BatchMetadataOutcome.FAILED) 1 else 0,
                        results = old.results.map { if (it.animeId == anime.id) it.copy(outcome = outcome, detail = detail) else it }) }
                }
                _batchMetadataState.update { it.copy(isRunning = false, currentTitle = null, currentAnimeId = null, message = "批量补全已完成") }
            } catch (_: CancellationException) {
                _batchMetadataState.update { it.copy(isRunning = false, currentTitle = null, currentAnimeId = null, message = "已停止，已保存的资料保留") }
            } catch (error: Exception) {
                _batchMetadataState.update { it.copy(isRunning = false, currentTitle = null, currentAnimeId = null, message = "暂时无法开始，请关闭后重试") }
            } finally {
                onFinished(_batchMetadataState.value.results.filter { !it.outcome.retryable }.mapTo(linkedSetOf()) { it.animeId })
                currentMetadataRequest = null
                batchMetadataJob = null
            }
        }
    }

    fun retryBatchMetadataMatch() = startBatchMetadataMatch(batchMetadataOptions, _batchMetadataState.value.retryIds)

    fun skipCurrentMetadataMatch() {
        if (_batchMetadataState.value.stage != "正在搜索并核对资料") return
        skipMetadataRequested = true
        currentMetadataRequest?.cancel()
    }

    fun cancelBatchMetadataMatch() { batchMetadataJob?.cancel() }

    fun clearBatchMetadataMessage() {
        if (!_batchMetadataState.value.isRunning) _batchMetadataState.value = BatchMetadataState()
    }

    private suspend fun findRemoteMetadataMatch(anime: AnimeEntity): RemoteAnime? = supervisorScope {
        suspend fun search(query: String): RemoteAnime? {
            val request = async {
                val result = search(anime, query)
                if (result.items.isEmpty() && result.warnings.isNotEmpty() && result.attempts.none { it.result != "暂不可用" }) {
                    throw java.io.IOException("补全来源暂不可用")
                }
                selectBestRemoteMetadataMatch(anime, result.items)
            }
            currentMetadataRequest = request
            return try {
                kotlinx.coroutines.withTimeout(65_000) { request.await() }
            } catch (error: CancellationException) {
                if (!isActive) throw error
                if (skipMetadataRequested) throw MetadataMatchSkipped()
                throw java.io.IOException("补全请求超时", error)
            } finally {
                request.cancel()
                if (currentMetadataRequest === request) currentMetadataRequest = null
            }
        }
        search(anime.title)?.let { return@supervisorScope it }
        val originalTitle = anime.originalTitle?.trim().orEmpty()
        if (originalTitle.isNotEmpty() && normalizeMetadataTitle(originalTitle) != normalizeMetadataTitle(anime.title)) {
            search(originalTitle)?.let { return@supervisorScope it }
        }
        null
    }
}
