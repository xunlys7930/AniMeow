@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.animeow.app.ui.playback

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.ProgressChange
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.playback.*
import com.animeow.app.data.preferences.AppearancePreferences
import com.animeow.app.data.remote.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import androidx.media3.exoplayer.offline.Download

data class PlaybackSession(val id: Long, val source: String, val work: PlaybackWork, val episode: PlaybackEpisode,
    val stream: PlaybackStream, val checkpoint: PlaybackCheckpoint, val offline: Boolean = false, val downloadId: String? = null)
data class PlaybackSearchGroup(val source: PlaybackSource, val loading: Boolean = true,
    val results: List<PlaybackWork> = emptyList(), val error: String? = null)
data class PlaybackLineCheck(val phase: String, val detail: String = "", val checkedAt: Long = 0, val running: Boolean = true)
data class DownloadRetryResult(val id: String, val title: String, val episode: String, val status: String, val detail: String = "")
data class PlaybackUiState(val anime: AnimeEntity? = null, val loaded: Boolean = false, val sources: List<PlaybackSource> = emptyList(),
    val source: String = "", val query: String = "", val searchGroups: List<PlaybackSearchGroup> = emptyList(), val work: PlaybackWork? = null,
    val lines: List<PlaybackLine> = emptyList(), val line: String = "", val session: PlaybackSession? = null,
    val busy: Boolean = false, val error: String? = null, val notice: String? = null, val undo: ProgressChange? = null,
    val resume: PlaybackCheckpoint? = null, val settings: PlaybackSettings = PlaybackSettings(),
    val downloads: List<PlaybackDownloadRow> = emptyList(), val wifiOnly: Boolean = true, val availableBytes: Long = 0,
    val downloadBusy: Boolean = false, val downloadNotice: String? = null,
    val downloadResults: List<DownloadRetryResult> = emptyList(),
    val checks: Map<String, PlaybackLineCheck> = emptyMap(), val bufferedBytes: Long = 0,
    val openingEpisode: String? = null, val playbackStatus: String = "正在准备", val upscaleStatus: String = "",
    val offlineOnly: Boolean = false,
    val openingTarget: PlaybackEpisode? = null,
    val sourcesLoading: Boolean = false, val sourcesError: String? = null)

class PlaybackViewModel internal constructor(application: Application, private val animeId: Long,
    private val catalog: MediaCatalogService = MediaCatalogService(), private val initialDownloadId: String? = null,
    private val initialNextFrom: String? = null, private val initialWatchJump: String? = null) : AndroidViewModel(application) {
    private val app = application as AniMeowApplication
    private val history = PlaybackHistory(application)
    private val appearance = AppearancePreferences(application)
    private val preferences = PlaybackPreferences(application)
    private val associations = PlaybackAssociations(application)
    private val identities = PlaybackIdentities(application)
    private val mutable = MutableStateFlow(PlaybackUiState(settings = preferences.read()))
    val state = mutable.asStateFlow()
    private var request: Job? = null
    private var requestSerial = 0L
    private var searchRequest: Job? = null
    private var sourceRequest: Job? = null
    private var searchSerial = 0L
    private var rememberedSession = 0L
    private var endedSession = 0L
    private var sequence = 0L
    private var checkpoint: PlaybackCheckpoint? = null
    private var lastPosition: Long? = null
    private var lastSample = 0L
    private var lastPlaying = false
    private var lastSaved = 0L
    private var marking = false
    private var workKey = ""
    private val downloads by lazy { PlaybackDownloads.get(application) }
    private var downloadObserver: Job? = null
    private val checks = mutableMapOf<String, Job>()
    internal var stopPlayback: (() -> Unit)? = null
    internal var pausePlayback: (() -> Unit)? = null
    internal var seekPlayback: ((Long) -> Unit)? = null
    private var watchJump = runCatching { org.json.JSONObject(initialWatchJump.orEmpty()) }.getOrNull()
    internal fun consumeWatchJump(session: PlaybackSession): Long? {
        val jump = watchJump ?: return null
        if (jump.optString("source") != session.source || jump.optString("work") != session.work.id || jump.optString("episode") != session.episode.id) return null
        watchJump = null
        return (jump.optLong("time") + jump.optLong("offset")).coerceIn(0, 86400000)
    }

    init {
        viewModelScope.launch {
            try {
                if (animeId == 0L) { mutable.update { it.copy(loaded = true) }; watchDownloads(); return@launch }
                val anime = app.database.libraryDao().getAnime(animeId)?.takeIf { it.deletedAt == null && it.subjectType == "anime" }
                if (anime == null) { mutable.update { it.copy(loaded = true, error = "作品已不存在或不是动画，请返回收藏页") }; return@launch }
                workKey = identities.key(anime)
                val linked = associations.read(workKey)
                mutable.update { it.copy(anime = anime, loaded = true, query = anime.title.take(100), resume = history.read(workKey),
                    source = linked?.source.orEmpty(), work = linked?.work, lines = linked?.lines.orEmpty(), line = linked?.line.orEmpty()) }
                if (initialWatchJump != null) {
                    val jump = watchJump
                    val line = linked?.lines?.firstOrNull { row -> row.episodes.any { it.id == jump?.optString("episode") } }
                    if (jump == null || linked == null || line == null || jump.optString("source") != linked.source ||
                        jump.optString("work") != linked.work.id || jump.optString("created_at") != anime.createdAt.orEmpty()) {
                        watchJump = null
                        mutable.update { it.copy(error = "本集播放关联已变化，请重新确认来源与集数后定位") }
                    } else {
                        mutable.update { it.copy(line = line.id) }
                        play(line.episodes.first { it.id == jump.optString("episode") })
                    }
                } else if (initialDownloadId != null) {
                    val local = downloads.find(initialDownloadId)
                    val info = local?.let { PlaybackDownloadInfo.decode(it.request.data) }
                    if (local != null && info?.workKey == workKey) openDownload(PlaybackDownloadRow(local.request.id, info, local.state, local.bytesDownloaded, local.contentLength, local.percentDownloaded, "已缓存"))
                    else mutable.update { it.copy(error = "缓存与当前作品记录不匹配，可返回缓存管理删除失效任务") }
                } else if (initialNextFrom != null && state.value.resume == null) mutable.update { it.copy(error = "续播记录已变化，请返回播放历史重新选择") }
                else if (state.value.resume != null) resume(initialNextFrom)
                else loadSources()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(loaded = true, error = "作品资料暂时无法读取，请返回收藏页重试") } }
        }
    }
    fun query(value: String) {
        searchSerial++; searchRequest?.cancel()
        mutable.update { it.copy(query = value.take(100), searchGroups = emptyList()) }
    }
    fun status(value: String) { mutable.update { it.copy(playbackStatus = value) } }
    fun upscaleStatus(value: String) { mutable.update { it.copy(upscaleStatus = value) } }
    fun cancelOpening() { requestSerial++; request?.cancel(); mutable.update { it.copy(busy = false, openingEpisode = null, openingTarget = null,
        playbackStatus = if (it.session != null) "已暂停" else it.playbackStatus, error = null) } }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun source(value: String) {
        if (value == state.value.source || state.value.sources.none { it.id == value }) return
        requestSerial++; request?.cancel(); flush()
        mutable.update { it.copy(source = value, work = null, lines = emptyList(), line = "", busy = false, error = null, openingTarget = null, openingEpisode = null) }
    }
    fun line(value: String) {
        if (state.value.lines.none { it.id == value }) return
        mutable.update { it.copy(line = value) }; saveAssociation()
    }
    private fun saveAssociation() {
        val snapshot = state.value; val work = snapshot.work ?: return
        if (workKey.isNotBlank()) associations.save(workKey, PlaybackAssociation(snapshot.source, work, snapshot.line, snapshot.lines))
    }
    fun loadSources(refreshSearch: Boolean = false): Job {
        sourceRequest?.cancel()
        return viewModelScope.launch {
            mutable.update { it.copy(sourcesLoading = true, sourcesError = null) }
            try {
                val preferred = preferences.preferredSource(workKey)
                val values = catalog.sources().sortedBy { if (it.id == preferred) 0 else 1 }
                mutable.update { it.copy(sources = values, source = it.source.ifBlank { values.firstOrNull()?.id.orEmpty() },
                    sourcesLoading = false, sourcesError = null) }
                if (refreshSearch || (state.value.work == null && state.value.resume == null)) search()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                val message = "来源列表暂时无法获取，可重试；已缓存视频仍可离线观看"
                mutable.update { it.copy(sourcesLoading = false, sourcesError = message) }
            }
        }.also { sourceRequest = it }
    }
    fun search(onlySource: String? = null) {
        val snapshot = state.value
        if (snapshot.sources.isEmpty() || snapshot.query.isBlank()) return
        searchRequest?.cancel()
        val serial = ++searchSerial
        val selectedSources = snapshot.sources.filter { onlySource == null || it.id == onlySource }
        mutable.update { it.copy(searchGroups = snapshot.sources.map { source ->
            if (source in selectedSources) PlaybackSearchGroup(source)
            else snapshot.searchGroups.firstOrNull { it.source.id == source.id } ?: PlaybackSearchGroup(source, loading = false)
        }) }
        searchRequest = viewModelScope.launch {
            supervisorScope {
                selectedSources.forEach { source -> launch {
                    val group = try { PlaybackSearchGroup(source, loading = false, results = catalog.search(source.id, snapshot.query.trim())) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { PlaybackSearchGroup(source, loading = false, error = "此来源读取失败，可重试；其他来源仍可选择") }
                    if (serial == searchSerial) mutable.update { it.copy(searchGroups = it.searchGroups.map { old -> if (old.source.id == source.id) group else old }) }
                } }
            }
        }
    }
    fun choose(work: PlaybackWork) = choose(state.value.source, work)
    fun choose(source: String, work: PlaybackWork) {
        if (state.value.sources.none { it.id == source }) return
        runRequest {
            val values = catalog.episodes(source, work.id)
            currentCoroutineContext().ensureActive()
            check(values.any { it.episodes.isNotEmpty() }) { "此作品暂时没有可用集数，请选择其他来源" }
            flush()
            checks.values.forEach { it.cancel() }; checks.clear()
            mutable.update { it.copy(source = source, work = work, lines = values, line = values.firstOrNull()?.id.orEmpty(), checks = emptyMap(),
                offlineOnly = false) }
            saveAssociation()
        }
    }
    fun reloadEpisodes() { state.value.work?.let(::choose) }
    fun play(episode: PlaybackEpisode) {
        val snapshot = state.value; val work = snapshot.work ?: return
        flush()
        pausePlayback?.invoke()
        mutable.update { it.copy(openingEpisode = episode.id, openingTarget = episode, busy = true, error = null, playbackStatus = "正在解析视频地址") }
        runRequest {
            // A cached episode plays from the card; the session keeps the full online line and
            // any other episode resolves online, so lines stay selectable.
            val local = downloads.find(playbackDownloadId(workKey, snapshot.source, work.id, episode.id))
                ?.takeIf { it.state == Download.STATE_COMPLETED }
            val stream = resolvePlaybackStream(local?.let { PlaybackStream(it.request.uri.toString(), it.request.mimeType.orEmpty(), emptyMap()) },
                snapshot.offlineOnly) { catalog.resolve(snapshot.source, work.id, episode.id) }
            currentCoroutineContext().ensureActive()
            flush()
            checkpoint = history.readEpisode(workKey, snapshot.source, work.id, episode.id)
                ?: PlaybackCheckpoint(snapshot.source, work.id, episode.id)
            resetSampling()
            mutable.update { it.copy(session = PlaybackSession(++sequence, snapshot.source, work, episode, stream, checkpoint!!, local != null, local?.request?.id),
                line = snapshot.lines.firstOrNull { line -> line.episodes.any { ep -> ep.id == episode.id } }?.id ?: it.line,
                playbackStatus = "正在准备", openingTarget = null, undo = null, notice = null) }
            saveAssociation()
        }
    }
    fun retry() {
        state.value.openingTarget?.let { play(it); return }
        val session = state.value.session ?: return
        flush()
        runRequest {
            val stream = if (session.offline) session.stream else catalog.resolve(session.source, session.work.id, session.episode.id)
            currentCoroutineContext().ensureActive()
            checkpoint = history.readEpisode(workKey, session.source, session.work.id, session.episode.id) ?: session.checkpoint
            resetSampling()
            mutable.update { it.copy(session = session.copy(id = ++sequence, stream = stream, checkpoint = checkpoint!!)) }
        }
    }
    fun settings(value: PlaybackSettings) { preferences.save(value); mutable.update { it.copy(settings = value) } }
    fun checkLine(line: PlaybackLine) {
        val snapshot = state.value; val work = snapshot.work ?: return
        if (snapshot.offlineOnly || checks[line.id]?.isActive == true) return
        checks[line.id] = viewModelScope.launch {
            fun publish(value: PlaybackLineCheck) {
                mutable.update { if (it.source == snapshot.source && it.work?.id == work.id) it.copy(checks = it.checks + (line.id to value)) else it }
            }
            try {
                publish(PlaybackLineCheck("正在读取集数"))
                val fresh = catalog.episodes(snapshot.source, work.id).firstOrNull { it.id == line.id }
                val episode = fresh?.episodes?.firstOrNull { it.id == snapshot.session?.episode?.id } ?: fresh?.episodes?.firstOrNull()
                    ?: error("线路暂无可用集数")
                publish(PlaybackLineCheck("正在解析视频", episode.label))
                val stream = catalog.resolve(snapshot.source, work.id, episode.id)
                publish(PlaybackLineCheck("正在检查媒体连接", episode.label))
                val result = catalog.checkMedia(stream)
                publish(PlaybackLineCheck("检查完成", result, System.currentTimeMillis(), false))
            } catch (cancelled: CancellationException) {
                publish(PlaybackLineCheck("已取消检查", checkedAt = System.currentTimeMillis(), running = false)); throw cancelled
            } catch (_: Exception) {
                val phase = state.value.checks[line.id]?.phase.orEmpty()
                publish(PlaybackLineCheck("检查失败", "$phase 时未能读取来源，可重试或换线路", System.currentTimeMillis(), false))
            }
        }
    }
    fun cancelCheck(line: String) { checks[line]?.cancel() }
    fun nextEpisode() {
        val snapshot = state.value
        if (snapshot.busy) return
        val session = snapshot.session ?: return
        if (session.source != snapshot.source || session.work.id != snapshot.work?.id) return
        nextPlaybackEpisode(session.episode, snapshot.lines)?.let(::play)
    }
    fun ended(id: Long, foreground: Boolean) {
        val snapshot = state.value
        if (snapshot.session?.id != id || !foreground || snapshot.busy || endedSession == id) return
        endedSession = id; flush()
        if (snapshot.settings.autoNext) nextEpisode()
    }
    fun resume(nextFrom: String? = null) {
        val previous = state.value.resume ?: return
        runRequest {
            var saved = previous
            val refreshedLines = if (nextFrom != null) {
                check(playbackCompleted(previous) && playbackResumeToken(previous) == nextFrom) { "续播记录已变化，请返回播放历史重新选择" }
                val values = catalog.episodes(previous.source, previous.workId)
                val current = values.flatMap { it.episodes }.firstOrNull { it.id == previous.episodeId }
                    ?: error("来源集数已变化，请重新选择集数")
                val next = nextPlaybackEpisode(current, values) ?: error("暂无可确认的下一集，请在选集区查看")
                saved = history.readEpisode(workKey, previous.source, previous.workId, next.id)
                    ?: PlaybackCheckpoint(previous.source, previous.workId, next.id)
                values
            } else null
            val local = downloads.find(playbackDownloadId(workKey, saved.source, saved.workId, saved.episodeId))
            if (local?.state == Download.STATE_COMPLETED) {
                val info = PlaybackDownloadInfo.decode(local.request.data)
                if (info != null && info.workKey == workKey) {
                    openCachedDownload(PlaybackDownloadRow(local.request.id, info, local.state, local.bytesDownloaded, local.contentLength, 100f, "已缓存"), offlineOnly = false)
                    if (refreshedLines != null) {
                        mutable.update { it.copy(lines = refreshedLines, line = refreshedLines.first { line -> line.episodes.any { ep -> ep.id == saved.episodeId } }.id) }
                        saveAssociation()
                    }
                    if (state.value.sources.isEmpty()) loadSources()
                    return@runRequest
                }
            }
            val values = refreshedLines ?: catalog.episodes(saved.source, saved.workId)
            val line = values.firstOrNull { it.episodes.any { ep -> ep.id == saved.episodeId } }
                ?: error("来源集数已变化，请重新搜索并选择作品")
            val episode = line.episodes.first { it.id == saved.episodeId }
            val stream = catalog.resolve(saved.source, saved.workId, saved.episodeId)
            currentCoroutineContext().ensureActive()
            flush()
            checkpoint = saved; resetSampling()
            val work = state.value.work?.takeIf { it.id == saved.workId && state.value.source == saved.source }
                ?: PlaybackWork(saved.workId, state.value.anime?.title.orEmpty(), "")
            mutable.update { it.copy(source = saved.source, work = work, lines = values, line = line.id, offlineOnly = false,
                session = PlaybackSession(++sequence, saved.source, work, episode, stream, saved), undo = null) }
            saveAssociation()
            if (state.value.sources.isEmpty()) loadSources()
        }
    }
    fun watchDownloads() {
        if (animeId != 0L && workKey.isBlank()) return
        if (downloadObserver?.isActive == true) return
        downloadObserver = viewModelScope.launch {
            var previous = emptyMap<String, Long>()
            while (true) {
                try {
                    val rows = downloads.list().mapNotNull { download ->
                        val info = PlaybackDownloadInfo.decode(download.request.data)?.takeIf { workKey.isBlank() || it.workKey == workKey } ?: return@mapNotNull null
                        val status = when (download.state) {
                            Download.STATE_COMPLETED -> "已缓存"
                            Download.STATE_FAILED -> downloads.failure(download.request.id)
                            Download.STATE_REMOVING -> "正在删除"
                            Download.STATE_RESTARTING -> "正在重新下载"
                            Download.STATE_STOPPED -> "已暂停"
                            Download.STATE_DOWNLOADING -> "下载中"
                            else -> if (downloads.manager.notMetRequirements != 0) (if (downloads.wifiOnly) "等待不计费网络" else "等待网络") else "等待下载"
                        }
                        PlaybackDownloadRow(download.request.id, info, download.state, download.bytesDownloaded, download.contentLength,
                            download.percentDownloaded, status,
                            if (download.state == Download.STATE_DOWNLOADING) (download.bytesDownloaded - (previous[download.request.id] ?: download.bytesDownloaded)).coerceAtLeast(0) else 0)
                    }
                    previous = rows.associate { it.id to it.bytes }
                    mutable.update { it.copy(downloads = rows, wifiOnly = downloads.wifiOnly, availableBytes = downloads.availableBytes, bufferedBytes = downloads.bufferedBytes) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutable.update { it.copy(downloadNotice = "缓存任务暂时无法读取，请重新打开重试") } }
                delay(1000)
            }
        }
    }
    fun cacheEpisodes(episodes: List<PlaybackEpisode>) {
        val snapshot = state.value; val work = snapshot.work ?: return
        if (snapshot.downloadBusy || episodes.isEmpty()) return
        mutable.update { it.copy(downloadBusy = true, downloadNotice = "正在准备缓存任务…") }
        viewModelScope.launch {
            var success = 0; var failed = 0; var reason: String? = null
            try {
                episodes.distinctBy { it.id }.forEach { episode ->
                    try {
                        val line = snapshot.lines.firstOrNull { it.episodes.any { ep -> ep.id == episode.id } } ?: return@forEach
                        val existing = downloads.find(playbackDownloadId(workKey, snapshot.source, work.id, episode.id))
                        if (existing?.state == Download.STATE_COMPLETED) { success++; return@forEach }
                        val stream = catalog.resolve(snapshot.source, work.id, episode.id)
                        downloads.enqueue(PlaybackDownloadInfo(animeId, workKey, snapshot.anime?.title.orEmpty(), snapshot.source, work, episode, line.id, line.name, stream.headers), stream)
                        success++
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { failed++; reason = error.message?.takeIf { it.length < 120 && !it.contains("Exception") } }
                }
                mutable.update { it.copy(downloadNotice = "已准备 $success 集${if (failed > 0) "，$failed 集失败${reason?.let { message -> "：$message" }.orEmpty()}" else "；下载进度请查看缓存任务"}") }
            } finally { mutable.update { it.copy(downloadBusy = false) } }
        }
    }
    fun stopWatchingDownloads() { downloadObserver?.cancel(); downloadObserver = null }
    fun pauseDownload(row: PlaybackDownloadRow) { runDownloadAction { downloads.pause(row.id) } }
    fun resumeDownload(row: PlaybackDownloadRow) { runDownloadAction { downloads.resume(row.id) } }
    fun removeDownload(row: PlaybackDownloadRow) {
        if (state.value.downloadBusy) return
        if (state.value.session?.downloadId == row.id) {
            stopPlayback?.invoke()
            flush(); mutable.update { it.copy(session = null, notice = "已停止本地播放") }
        }
        runDownloadAction { downloads.remove(row.id) }
    }
    fun wifiOnly(value: Boolean) { runDownloadAction { downloads.wifiOnly(value); mutable.update { it.copy(wifiOnly = value) } } }
    fun clearBuffer() { viewModelScope.launch { try { downloads.clearBuffer(); mutable.update { it.copy(downloadNotice = "已清理播放缓冲，已下载视频和观看记录保留") } }
        catch (_: Exception) { mutable.update { it.copy(downloadNotice = "缓冲清理失败，请稍后重试") } } } }
    private fun runDownloadAction(action: () -> Unit) {
        try { action() } catch (_: Exception) { mutable.update { it.copy(downloadNotice = "缓存操作未完成，请重新打开页面重试") } }
    }
    fun retryDownload(row: PlaybackDownloadRow) = retryDownloads(listOf(row))
    fun retryDownloads(rows: List<PlaybackDownloadRow>) {
        val pending = rows.distinctBy { it.id }.filter { it.state == Download.STATE_FAILED && (workKey.isBlank() || it.info.workKey == workKey) }
        if (state.value.downloadBusy || pending.isEmpty()) return
        mutable.update { it.copy(downloadBusy = true, downloadResults = emptyList()) }
        viewModelScope.launch {
            try {
                pending.forEachIndexed { index, row ->
                    mutable.update { it.copy(downloadNotice = "正在重新解析 ${index + 1}/${pending.size}：${row.info.episode.label}") }
                    val result = try {
                        if (downloads.find(row.id)?.state != Download.STATE_FAILED) DownloadRetryResult(row.id, row.info.title, row.info.episode.label, "已跳过", "任务已移除或状态已变化")
                        else {
                            val stream = catalog.resolve(row.info.source, row.info.work.id, row.info.episode.id)
                            downloads.enqueue(row.info.copy(headers = stream.headers), stream)
                            DownloadRetryResult(row.id, row.info.title, row.info.episode.label, "已提交", "等待下载，完成后可离线播放")
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { DownloadRetryResult(row.id, row.info.title, row.info.episode.label, "失败", "解析或提交未完成，可稍后重试或换线路") }
                    mutable.update { it.copy(downloadResults = it.downloadResults + result) }
                }
                mutable.update { snapshot -> snapshot.copy(downloadNotice = "已提交 ${snapshot.downloadResults.count { it.status == "已提交" }} 项，失败 ${snapshot.downloadResults.count { it.status == "失败" }} 项，跳过 ${snapshot.downloadResults.count { it.status == "已跳过" }} 项") }
            }
            finally { mutable.update { it.copy(downloadBusy = false) } }
        }
    }
    fun openDownload(row: PlaybackDownloadRow) {
        if (row.state != Download.STATE_COMPLETED || row.info.workKey != workKey) return
        flush()
        runRequest { openCachedDownload(row) }
    }
    private suspend fun openCachedDownload(row: PlaybackDownloadRow, offlineOnly: Boolean = true) {
        val info = row.info
        val stored = downloads.find(row.id)?.takeIf { it.state == Download.STATE_COMPLETED } ?: error("缓存已被移除")
        val localEpisodes = downloads.list().filter { it.state == Download.STATE_COMPLETED }.mapNotNull { PlaybackDownloadInfo.decode(it.request.data) }
            .filter { it.workKey == workKey && it.source == info.source && it.work.id == info.work.id && it.line == info.line }
            .map { it.episode }.distinctBy { it.id }.sortedBy { it.number ?: Int.MAX_VALUE }
        val associatedLines = associations.read(workKey)?.takeIf { it.source == info.source && it.work.id == info.work.id }?.lines.orEmpty()
        val lines = if (!offlineOnly && associatedLines.any { it.episodes.any { episode -> episode.id == info.episode.id } }) associatedLines
            else listOf(PlaybackLine(info.line, info.lineName, localEpisodes))
        currentCoroutineContext().ensureActive()
        flush()
        checkpoint = history.readEpisode(workKey, info.source, info.work.id, info.episode.id) ?: PlaybackCheckpoint(info.source, info.work.id, info.episode.id)
        resetSampling()
        mutable.update { it.copy(source = info.source, work = info.work, lines = lines, line = info.line, error = null, offlineOnly = offlineOnly,
            session = PlaybackSession(++sequence, info.source, info.work, info.episode,
                PlaybackStream(stored.request.uri.toString(), stored.request.mimeType.orEmpty(), emptyMap()), checkpoint!!, true, stored.request.id)) }
    }
    private fun resetSampling() { lastPosition = null; lastSample = 0; lastPlaying = false }
    internal fun checkpointFor(id: Long): PlaybackCheckpoint? = state.value.session?.takeIf { it.id == id }?.let { checkpoint ?: it.checkpoint }
    fun discontinuity(id: Long) { if (state.value.session?.id == id) resetSampling() }
    fun sample(id: Long, positionMs: Long, durationMs: Long, playing: Boolean, speed: Float) {
        val session = state.value.session?.takeIf { it.id == id } ?: return
        val current = checkpoint ?: return
        if (playing && rememberedSession != id) {
            preferences.played(workKey, session.source)
            state.value.anime?.let { history.describe(workKey, it, session.episode.label) }
            rememberedSession = id
        }
        val now = SystemClock.elapsedRealtime(); val previous = lastPosition
        val step = previous?.let { positionMs - it } ?: 0L; val elapsed = now - lastSample
        val ranges = if (playing && lastPlaying && previous != null && elapsed in 1..5000 && step in 1..(elapsed * speed.coerceIn(0.1f, 4f) + 750).toLong())
            mergePlaybackRanges(current.ranges, previous, positionMs) else current.ranges
        checkpoint = current.copy(positionMs = positionMs.coerceAtLeast(0), durationMs = durationMs.coerceAtLeast(0), ranges = ranges)
        lastPosition = positionMs; lastSample = now; lastPlaying = playing
        if (now - lastSaved > 5000 || !playing) flush()
        if (state.value.settings.autoMarkProgress && !current.handled && !marking && playbackComplete(ranges, durationMs)) markWatched(session)
    }
    private fun markWatched(session: PlaybackSession) {
        val number = session.episode.number ?: return
        marking = true
        viewModelScope.launch {
            try {
                val settings = appearance.snapshot()
                val (allowed, change) = com.animeow.app.util.withDataTransferLock {
                    val current = app.database.libraryDao().getAnime(animeId)
                    check(current != null && current.deletedAt == null && identities.key(current) == workKey) { "作品记录已变化，请重新打开" }
                    if (!state.value.settings.autoMarkProgress || state.value.session?.id != session.id) false to null
                    else true to app.libraryRepository.incrementProgress(animeId, settings.autoCompleteStatus, settings.completionStatus, expectedProgress = number - 1)
                }
                if (!allowed) return@launch
                if (state.value.session?.id == session.id) {
                    checkpoint = checkpoint?.copy(handled = true); flush()
                    mutable.update { it.copy(undo = change, notice = if (change != null) "已自动标记看过第 $number 集" else "播放位置已保存；已看过或跳集时不重复增加进度") }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutable.update { it.copy(notice = "播放位置已保存，已看进度更新失败，请返回详情手动调整") }
                if (state.value.session?.id == session.id) { checkpoint = checkpoint?.copy(handled = true); flush() }
            }
            finally { marking = false }
        }
    }
    fun undo() {
        val change = state.value.undo ?: return
        mutable.update { it.copy(undo = null) }
        viewModelScope.launch {
            try {
                val restored = com.animeow.app.util.withDataTransferLock {
                    val current = app.database.libraryDao().getAnime(animeId)
                    if (current == null || identities.key(current) != workKey) false
                    else app.libraryRepository.restoreProgress(change, syncBangumi = true)
                }
                mutable.update { it.copy(notice = if (restored) "已撤销自动标记，本次观看不会再次自动标记" else "进度已经变化，未覆盖后续修改") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(undo = change, notice = "撤销未完成，请重试") } }
        }
    }
    fun flush() { checkpoint?.let { if (workKey.isNotBlank()) history.save(workKey, it) }; lastSaved = SystemClock.elapsedRealtime() }
    private fun runRequest(action: suspend () -> Unit) {
        request?.cancel()
        val serial = ++requestSerial
        request = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (serial == requestSerial) mutable.update { it.copy(error = if (failure is IllegalStateException) failure.message?.takeIf { text -> text.length <= 120 } ?: "来源读取失败，请重试" else "来源读取失败，请检查网络后重试") } }
            finally { if (serial == requestSerial) mutable.update { it.copy(busy = false, openingEpisode = null) } }
        }
    }
    override fun onCleared() { flush(); super.onCleared() }
}
