package com.animeow.app.ui.tags

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.DeletedTagSnapshot
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AnimeTagEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.WatchStatusEntity
import com.animeow.app.data.local.normalizeTagKey
import java.nio.charset.Charset
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TagIndexSort(val displayName: String) {
    INITIAL("首字母"),
    USAGE("使用次数"),
    NAME("名称"),
}

data class TagIndexItem(
    val tag: TagEntity,
    val animeCount: Int,
    val initial: String = tagInitial(tag.name),
)

data class TagIndexUiState(
    val items: List<TagIndexItem> = emptyList(),
    val totalTags: Int = 0,
    val usedTags: Int = 0,
    val totalLinks: Int = 0,
    val query: String = "",
    val sort: TagIndexSort = TagIndexSort.INITIAL,
)

data class TagUndoEvent(val count: Int)

private data class RawTagIndex(
    val items: List<TagIndexItem>,
    val totalLinks: Int,
)

class TagIndexViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as AniMeowApplication).libraryRepository
    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(TagIndexSort.INITIAL)

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private val _undoEvents = Channel<TagUndoEvent>(Channel.BUFFERED)
    val undoEvents: Flow<TagUndoEvent> = _undoEvents.receiveAsFlow()

    private var lastDeletedSnapshot: List<DeletedTagSnapshot>? = null

    private val raw = combine(
        repository.observeAnimes(),
        repository.observeAllTags(),
        repository.observeAnimeTags(),
    ) { animes, tags, links ->
        val activeIds = animes.mapTo(hashSetOf(), AnimeEntity::id)
        val counts = links.asSequence()
            .filter { it.animeId in activeIds }
            .groupingBy(AnimeTagEntity::tagId)
            .eachCount()
        RawTagIndex(
            items = tags.map { tag -> TagIndexItem(tag, counts[tag.id] ?: 0) },
            totalLinks = counts.values.sum(),
        )
    }

    val state: StateFlow<TagIndexUiState> = combine(raw, query, sort) { raw, currentQuery, currentSort ->
        val normalizedQuery = currentQuery.trim()
        val filtered = raw.items.filter {
            normalizedQuery.isEmpty() || normalizeTagKey(it.tag.name).contains(normalizeTagKey(normalizedQuery))
        }
        val sorted = when (currentSort) {
            TagIndexSort.INITIAL -> filtered.sortedWith(
                compareBy<TagIndexItem>(
                    { it.initial == "#" },
                    { it.initial },
                    { it.tag.name.lowercase(java.util.Locale.ROOT) },
                ),
            )
            TagIndexSort.USAGE -> filtered.sortedWith(
                compareByDescending<TagIndexItem>(TagIndexItem::animeCount)
                    .thenBy { it.tag.name.lowercase(java.util.Locale.ROOT) },
            )
            TagIndexSort.NAME -> filtered.sortedBy { it.tag.name.lowercase(java.util.Locale.ROOT) }
        }
        TagIndexUiState(
            items = sorted,
            totalTags = raw.items.size,
            usedTags = raw.items.count { it.animeCount > 0 },
            totalLinks = raw.totalLinks,
            query = currentQuery,
            sort = currentSort,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TagIndexUiState(),
    )

    fun setQuery(value: String) {
        query.value = value
    }

    fun setSort(value: TagIndexSort) {
        sort.value = value
    }

    fun enterSelectionMode() {
        _selectionMode.value = true
    }

    fun exitSelectionMode() {
        _selectionMode.value = false
        _selectedIds.value = emptySet()
    }

    fun toggleSelection(tagId: Long) {
        _selectedIds.value = _selectedIds.value.toMutableSet().apply {
            if (!add(tagId)) remove(tagId)
        }
    }

    fun selectAll(ids: Collection<Long>) {
        if (ids.isNotEmpty()) _selectedIds.value = ids.toSet()
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun deleteSelected() {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val snapshot = repository.deleteTagsWithSnapshot(ids)
            lastDeletedSnapshot = snapshot
            _selectedIds.value = emptySet()
            _selectionMode.value = false
            _undoEvents.send(TagUndoEvent(snapshot.size))
        }
    }

    fun restoreLastDeleted() {
        val snapshot = lastDeletedSnapshot ?: return
        viewModelScope.launch {
            repository.restoreTags(snapshot)
            lastDeletedSnapshot = null
        }
    }

    fun setSelectedBlocked(blocked: Boolean) {
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { repository.setTagBlocked(it, blocked) }
            _selectedIds.value = emptySet()
            _selectionMode.value = false
        }
    }

    fun mergeSelected(targetId: Long) {
        val ids = _selectedIds.value
        if (ids.size < 2 || targetId !in ids) return
        viewModelScope.launch {
            repository.mergeTags(ids, targetId)
            _selectedIds.value = emptySet()
            _selectionMode.value = false
        }
    }
}

data class TagCollectionUiState(
    val tag: TagEntity? = null,
    val animes: List<AnimeEntity> = emptyList(),
    val statuses: List<WatchStatusEntity> = emptyList(),
)

class TagCollectionViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val repository = (application as AniMeowApplication).libraryRepository
    private val tagId: Long = checkNotNull(savedStateHandle["tagId"])

    val state: StateFlow<TagCollectionUiState> = combine(
        repository.observeAnimes(),
        repository.observeAllTags(),
        repository.observeAnimeTags(),
        repository.observeWatchStatuses(),
    ) { animes, tags, links, statuses ->
        val animeIds = links.asSequence()
            .filter { it.tagId == tagId }
            .mapTo(hashSetOf(), AnimeTagEntity::animeId)
        TagCollectionUiState(
            tag = tags.firstOrNull { it.id == tagId },
            animes = animes.filter { it.id in animeIds },
            statuses = statuses,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TagCollectionUiState(),
    )
}

internal fun tagInitial(value: String): String {
    val first = value.trim().firstOrNull() ?: return "#"
    val upper = first.uppercaseChar()
    if (upper in 'A'..'Z') return upper.toString()
    if (upper.isDigit()) return "0-9"
    val bytes = runCatching { first.toString().toByteArray(Charset.forName("GB2312")) }.getOrNull()
        ?: return "#"
    if (bytes.size < 2) return "#"
    val area = (bytes[0].toInt() and 0xFF) - 160
    val position = (bytes[1].toInt() and 0xFF) - 160
    val code = area * 100 + position
    val index = PINYIN_THRESHOLDS.indexOfLast { code >= it }
    return PINYIN_INITIALS.getOrNull(index)?.toString() ?: "#"
}

internal fun tagGroupHeaderIndices(
    items: List<TagIndexItem>,
    leadingItemCount: Int = 3,
): Map<String, Int> {
    var listIndex = leadingItemCount
    return buildMap {
        items.groupBy(TagIndexItem::initial).forEach { (initial, tags) ->
            put(initial, listIndex)
            listIndex += 1 + tags.size
        }
    }
}

private val PINYIN_THRESHOLDS = intArrayOf(
    1601, 1637, 1833, 2078, 2274, 2302, 2433, 2594, 2787, 3106, 3212, 3472,
    3635, 3722, 3730, 3858, 4027, 4086, 4390, 4558, 4684, 4925, 5249,
)
private const val PINYIN_INITIALS = "ABCDEFGHJKLMNOPQRSTWXYZ"
