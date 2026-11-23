package com.animeow.app.ui.games

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.games.*
import com.animeow.app.data.preferences.AppearancePreferences
import com.animeow.app.data.remote.userFriendlyRemoteFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import com.animeow.app.data.remote.RemoteHttpException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class GameSearchState(val items: List<GameEntity> = emptyList(), val total: Int = 0, val busy: Boolean = false, val error: String? = null, val hasMore: Boolean = false)

data class GameDiscoveryState(val query: String = "", val sort: String = "heat", val page: Int = 1,
    val items: List<GameEntity> = emptyList(), val total: Int = 0, val hasMore: Boolean = false,
    val stale: Boolean = false, val updatedAt: String? = null, val scope: String? = null,
    val busy: Boolean = false, val loaded: Boolean = false, val error: String? = null,
    val freshness: GameDiscoveryFreshness? = null, val totalPages: Int = 1)

class GameViewModel(application: Application) : AndroidViewModel(application) {
    internal val seriesRepository = com.animeow.app.data.MediaSeriesRepository((application as AniMeowApplication).database, com.animeow.app.data.MediaSeriesKind.GAME)
    private val repository = GameRepository(application, (application as AniMeowApplication).database)
    private val catalog = GameCatalogService()
    private val appearance = AppearancePreferences(application)
    val games = repository.games.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allSeries = (application as AniMeowApplication).libraryRepository.observeSeries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pendingCount = repository.pendingCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val searched = MutableStateFlow(false)
    val search = MutableStateFlow(GameSearchState())
    val discovery = MutableStateFlow(GameDiscoveryState())
    val selected = MutableStateFlow<GameEntity?>(null)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    private var searchJob: Job? = null
    private var searchQuery = ""
    private var offset = 0
    private var discoveryJob: Job? = null

    fun startDiscovery(initialQuery: String = "") {
        if (!discovery.value.loaded && !discovery.value.busy) loadDiscovery(query = initialQuery)
    }
    fun loadDiscovery(query: String = discovery.value.query, sort: String = discovery.value.sort,
        page: Int = discovery.value.page, debounce: Boolean = false) {
        if (query.length > 80 || sort !in listOf("heat", "score", "date") || page !in 1..501) return
        discoveryJob?.cancel()
        val previous = discovery.value
        val sameFilter = previous.query == query && previous.sort == sort
        val samePage = previous.query == query && previous.sort == sort && previous.page == page
        discovery.value = previous.copy(query = query, sort = sort, page = page, busy = true, error = null,
            items = if (samePage) previous.items else emptyList(), total = if (sameFilter) previous.total else 0,
            hasMore = if (samePage) previous.hasMore else false, scope = if (samePage) previous.scope else null)
        discoveryJob = viewModelScope.launch {
            if (debounce) kotlinx.coroutines.delay(450)
            try {
                val result = catalog.discover(query, sort, page)
                check(result.offset == (page - 1) * 20) { "服务器返回的游戏页码不一致，请刷新重试" }
                discovery.value = GameDiscoveryState(query, sort, page, result.items, result.total, result.hasMore,
                    result.stale, result.updatedAt, result.scope, loaded = true, freshness = result.freshness, totalPages = result.totalPages)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { discovery.value = discovery.value.copy(busy = false, loaded = true, error = gameError(error)) }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchQuery = ""
        offset = 0
        searched.value = false
        search.value = GameSearchState()
    }

    fun search(query: String, more: Boolean = false) {
        if (more && (search.value.busy || query != searchQuery)) return
        searchJob?.cancel()
        searched.value = true
        if (!more) { offset = 0; searchQuery = query; search.value = GameSearchState() }
        searchJob = viewModelScope.launch {
            search.value = search.value.copy(busy = true, error = null)
            try {
                val result = catalog.search(query, offset)
                offset = result.nextOffset
                search.value = GameSearchState((if (more) search.value.items else emptyList()).plus(result.items).distinctBy { it.catalogKey }, result.total,
                    error = if (result.partial) "部分资料来源暂时不可用，可稍后重试" else null, hasMore = result.hasMore)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { search.value = search.value.copy(busy = false, error = gameError(error)) }
        }
    }

    fun open(game: GameEntity) {
        selected.value = game
        if ((game.bangumiId != null || game.vndbId != null) && (game.id == 0L || game.needsMetadata)) completeMetadata(game)
    }
    fun completeMetadata(game: GameEntity) = runAction {
            val full = catalog.detail(game)
            check(full.catalogKey == game.catalogKey) { "来源返回了不同游戏，请稍后重试" }
            val enriched = if (game.id > 0) repository.enrich(game.id, full) else games.value.firstOrNull { sameCatalogGame(it, game) } ?: full
            if (selected.value?.catalogKey == game.catalogKey) selected.value = enriched
            message.value = if (enriched == game) "没有取得新的空白资料，可在修改记录中手动填写" else "已补全可取得的空白资料，已有内容与个人记录保留"
    }
    fun save(game: GameEntity) = runAction {
        repository.save(game)
        selected.value = null
        message.value = "已保存游戏记录"
    }
    fun delete(game: GameEntity) = runAction {
        repository.delete(game); selected.value = null; message.value = "已从游戏库移除"
    }
    fun updateTags(ids: Set<Long>?, tags: String, remove: Boolean, onSuccess: () -> Unit) = runAction {
        repository.updateTags(ids, tags, remove); message.value = "已更新游戏标签"; onSuccess()
    }
    fun changeTag(old: String, new: String?) = runAction {
        repository.updateTags(null, old, remove = new == null, rename = new)
        message.value = "已更新游戏标签"
    }
    fun updateSelection(ids: Set<Long>, status: String? = null, delete: Boolean = false, onSuccess: () -> Unit) = runAction {
        val count = repository.updateSelection(ids, status, delete)
        message.value = if (delete) "已移除 $count 款游戏，本地移除不会删除 Bangumi 收藏" else "已修改 $count 款游戏的状态"
        onSuccess()
    }
    fun record(game: GameEntity, minutes: Long) = runAction {
        require(minutes in 1..1440) { "本次游玩时长应为 1–1440 分钟" }
        repository.save(game.copy(playMinutes = game.playMinutes + minutes, status = if (game.status.trim() in setOf("想玩", "未玩")) "在玩" else game.status,
            startedOn = game.startedOn.ifBlank { java.time.LocalDate.now().toString() }))
        message.value = "已记录本次游玩"
    }
    fun importCollections() = runAction { message.value = "已导入 ${repository.importCollections()} 条游戏收藏，保留本地时长与心得" }
    fun uploadCollections() = runAction { message.value = "已向 Bangumi 上报 ${repository.uploadCollections()} 条游戏收藏" }
    fun setLayout(layout: GameLayout) { viewModelScope.launch { appearance.setGameLayout(layout) } }
    fun setColumns(columns: Int) { viewModelScope.launch { appearance.setGameGridColumns(columns) } }
    private fun runAction(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { message.value = gameError(error) }
            finally { busy.value = false }
        }
    }
}

private fun gameError(error: Exception): String = when (error) {
    is IllegalArgumentException, is IllegalStateException -> if (error is RemoteHttpException) "游戏资料暂时无法获取，请稍后重试" else error.message ?: "操作失败，请重试"
    is java.io.IOException -> error.message ?: "网络暂时不可用，请重试"
    else -> "操作失败，请稍后重试"
}
