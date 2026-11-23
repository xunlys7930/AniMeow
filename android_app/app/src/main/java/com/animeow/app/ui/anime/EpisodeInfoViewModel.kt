package com.animeow.app.ui.anime

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.bangumi.EpisodeNumbering
import com.animeow.app.data.bangumi.bangumiSubjectIdOf
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.EpisodeDetailEntity
import com.animeow.app.data.remote.BangumiService
import com.animeow.app.util.runCatchingCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 分集信息的读写入口。
 *
 * 集号始终是本季集数，与观看进度、观看记录同口径；连续集号只用于显示，
 * 不影响保存的内容。单集资料只存在本机，随完整备份迁移，不上报 Bangumi。
 */
class EpisodeInfoViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val repository = (application as AniMeowApplication).libraryRepository
    val animeId: Long = checkNotNull(savedStateHandle.get<Long>("animeId"))

    val anime: StateFlow<AnimeEntity?> = repository.observeAnime(animeId).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null,
    )

    val episodes: StateFlow<List<EpisodeDetailEntity>> = repository.observeEpisodeDetails(animeId).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    /** 只读元数据：拿到连续集号时列表附带显示，保存的集号仍是本季集数。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal val episodeNumbering: StateFlow<EpisodeNumbering?> = anime
        .map { current ->
            current?.takeIf { it.subjectType == "anime" && it.totalEpisodes > 0 }
                ?.let { value -> bangumiSubjectIdOf(value)?.let { it to value.totalEpisodes } }
        }
        .distinctUntilChanged()
        .transformLatest { key ->
            emit(null)
            if (key != null) {
                val numbering = runCatchingCancellable {
                    withTimeoutOrNull(10_000) {
                        BangumiService(context = application).fetchEpisodeNumbering(key.first, key.second)
                    }
                }.getOrNull()
                if (numbering?.usesContinuousNumbering == true) emit(numbering)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 返回 true 表示这一集留下了资料；false 表示字段全空、该集记录已被清空。 */
    suspend fun save(
        episode: Int,
        title: String,
        durationMinutes: Int?,
        synopsis: String,
        review: String,
        rating: Int?,
    ): Boolean = repository.saveEpisodeDetail(
        animeId = animeId,
        episode = episode,
        title = title,
        durationMinutes = durationMinutes,
        synopsis = synopsis,
        review = review,
        rating = rating,
    ) != null
}
