package com.animeow.app.ui.duplicates

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.normalizeSubjectType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DuplicateAnimeGroup(
    val key: String,
    val title: String,
    val subjectType: String,
    val items: List<AnimeEntity>,
) {
    val recommendedKeepId: Long = items.maxByOrNull(::animeCompletenessScore)?.id ?: items.first().id
}

class DuplicateCleanupViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AniMeowApplication
    private val repository = app.libraryRepository
    private val _mergingGroupKey = MutableStateFlow<String?>(null)
    val mergingGroupKey: StateFlow<String?> = _mergingGroupKey.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    val groups: StateFlow<List<DuplicateAnimeGroup>> = repository.observeAnimes()
        .map(::findDuplicateAnimeGroups)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun merge(group: DuplicateAnimeGroup, keepId: Long) {
        if (_mergingGroupKey.value != null) return
        _mergingGroupKey.value = group.key
        viewModelScope.launch {
            try {
                val result = repository.mergeAnimes(
                    keepId,
                    group.items.mapTo(mutableSetOf(), AnimeEntity::id),
                ) ?: error("重复项已发生变化，请返回后重新扫描")
                var reminderFailed = false
                result.sourceIds.forEach { animeId ->
                    try {
                        app.reminderScheduler.cancel(animeId)
                    } catch (error: Exception) {
                        reminderFailed = true
                        app.operationLog.recordError(error, "番剧查重 · 清理来源提醒")
                    }
                }
                try {
                    app.reminderScheduler.schedule(result.target)
                } catch (error: Exception) {
                    reminderFailed = true
                    app.operationLog.recordError(error, "番剧查重 · 重建保留项提醒")
                }
                _messages.emit(
                    if (reminderFailed) {
                        "合并已完成；提醒同步失败，请前往提醒管理重新同步"
                    } else {
                        "已合并 ${result.sourceIds.size + 1} 个条目"
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalArgumentException) {
                _messages.emit(error.message?.takeIf(String::isNotBlank) ?: "合并失败")
            } catch (error: IllegalStateException) {
                _messages.emit(error.message?.takeIf(String::isNotBlank) ?: "合并失败")
            } catch (error: Exception) {
                app.operationLog.recordError(error, "番剧查重 · 合并")
                _messages.emit("合并失败，请稍后重试")
            } finally {
                _mergingGroupKey.value = null
            }
        }
    }
}

internal fun normalizeDuplicateTitle(title: String): String = title
    .trim()
    .lowercase(java.util.Locale.ROOT)
    .replace(Regex("\\s+"), "")
    .replace("　", "")

internal fun findDuplicateAnimeGroups(items: List<AnimeEntity>): List<DuplicateAnimeGroup> = items
    .filter { it.title.isNotBlank() }
    .groupBy { anime -> "${normalizeSubjectType(anime.subjectType)}|${normalizeDuplicateTitle(anime.title)}" }
    .filterValues { it.size > 1 }
    .map { (key, groupItems) ->
        DuplicateAnimeGroup(
            key = key,
            title = groupItems.first().title,
            subjectType = normalizeSubjectType(groupItems.first().subjectType),
            items = groupItems.sortedByDescending(AnimeEntity::id),
        )
    }
    .sortedWith(compareBy(DuplicateAnimeGroup::subjectType, DuplicateAnimeGroup::title))

private fun animeCompletenessScore(anime: AnimeEntity): Int =
    listOf(
        anime.coverUrl,
        anime.review,
        anime.studio,
        anime.airDate,
        anime.watchStartDate,
        anime.watchFinishDate,
        anime.ratingGrade,
    ).count { !it.isNullOrBlank() } * 10 +
        (if (anime.rating != null) 8 else 0) +
        (if (anime.totalEpisodes > 0) 6 else 0) +
        anime.watchedEpisodes.coerceAtMost(20)
