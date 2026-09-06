package com.animeow.app.ui.tier

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.animeRatingSortValue
import com.animeow.app.data.local.normalizeSubjectType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TierGroup(
    val code: String,
    val animes: List<AnimeEntity>,
)

data class TierListUiState(
    val groups: List<TierGroup> = TIER_CODES.map { TierGroup(it, emptyList()) } +
        TierGroup(UNRATED_TIER_CODE, emptyList()),
) {
    val animeCount: Int = groups.sumOf { it.animes.size }
    val ratedCount: Int = groups.filterNot { it.code == UNRATED_TIER_CODE }.sumOf { it.animes.size }
    val unratedCount: Int = groups.firstOrNull { it.code == UNRATED_TIER_CODE }?.animes?.size ?: 0
}

data class TierOperationState(
    val busy: Boolean = false,
    val message: String? = null,
)

class TierListViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as AniMeowApplication).libraryRepository
    private val _operation = MutableStateFlow(TierOperationState())
    val operation: StateFlow<TierOperationState> = _operation.asStateFlow()

    val state: StateFlow<TierListUiState> = repository.observeAnimes().map(::groupTierAnimes).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TierListUiState(),
    )

    fun moveAnime(animeId: Long, targetTier: String) = moveAnime(animeId, targetTier) {}

    fun moveAnime(animeId: Long, targetTier: String, onSuccess: () -> Unit) = launchMutation(
        fallback = "趣味评级保存失败",
        onSuccess = onSuccess,
    ) {
        repository.setFunRatingTier(animeId, targetTier.takeUnless { it == UNRATED_TIER_CODE })
    }

    fun clearBoard(onSuccess: () -> Unit = {}) = launchMutation(
        fallback = "Tier List 清空失败",
        onSuccess = onSuccess,
    ) {
        repository.clearFunRatingTiers()
    }

    fun clearMessage() {
        _operation.value = _operation.value.copy(message = null)
    }

    private fun launchMutation(
        fallback: String,
        onSuccess: () -> Unit,
        block: suspend () -> Unit,
    ) {
        if (_operation.value.busy) return
        _operation.value = TierOperationState(busy = true)
        viewModelScope.launch {
            try {
                block()
                onSuccess()
                _operation.value = TierOperationState()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _operation.value = TierOperationState(message = error.message ?: fallback)
            }
        }
    }
}

internal fun groupTierAnimes(animes: List<AnimeEntity>): TierListUiState {
    val eligible = animes.filter { normalizeSubjectType(it.subjectType) == "anime" }
    val labels = TIER_CODES + UNRATED_TIER_CODE
    return TierListUiState(
        labels.map { code ->
            TierGroup(
                code = code,
                animes = eligible.filter { anime ->
                    val normalized = anime.funRatingTier?.trim()
                        ?.uppercase(java.util.Locale.ROOT)
                        ?.takeIf(TIER_CODES::contains)
                    if (code == UNRATED_TIER_CODE) normalized == null else normalized == code
                }.sortedByDescending { animeRatingSortValue(it) ?: -1 },
            )
        },
    )
}

val TIER_CODES = listOf("S", "A", "B", "C", "D")
const val UNRATED_TIER_CODE = "__unrated__"
