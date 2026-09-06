package com.animeow.app.ui.statistics

import android.app.Application
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.MovieFilter
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.animeRatingSortValue
import com.animeow.app.data.local.formatAnimeRating
import com.animeow.app.data.local.normalizeAnimeRatingGrade
import com.animeow.app.ui.theme.AppearanceSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

internal const val UNCLASSIFIED_STATUS_ROUTE_TOKEN = "__animeow_unclassified__"

internal enum class StatusCollectionSort(val displayName: String) {
    RECENT("最近添加"),
    TITLE("标题"),
    RATING("评分"),
    PROGRESS("观看进度"),
}

data class StatusAnimeUiState(
    val status: String = "",
    val animes: List<AnimeEntity> = emptyList(),
    val color: Long? = null,
)

class StatusAnimeViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val repository = (application as AniMeowApplication).libraryRepository
    private val routeValue = checkNotNull(savedStateHandle.get<String>("statusName"))
    private val status = routeValue.takeUnless { it == UNCLASSIFIED_STATUS_ROUTE_TOKEN }.orEmpty()

    val state: StateFlow<StatusAnimeUiState> = combine(
        repository.observeAnimes(),
        repository.observeWatchStatuses(),
    ) { animes, statuses ->
        StatusAnimeUiState(
            status = status,
            animes = animes.filter { it.status == status },
            color = statuses.firstOrNull { it.name == status }?.color,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatusAnimeUiState(status = status),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusAnimeScreen(
    settings: AppearanceSettings,
    onBack: () -> Unit,
    onAnimeClick: (Long) -> Unit,
    viewModel: StatusAnimeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(StatusCollectionSort.RECENT) }
    val filtered = remember(state.animes, query, sort) {
        filterStatusAnime(state.animes, query, sort)
    }
    val title = state.status.ifBlank { "未分类" }
    val statusColor = state.color?.let(::Color) ?: MaterialTheme.colorScheme.primary
    val duration = (180 * settings.motionLevel.durationScale).toInt().coerceAtLeast(1)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        Text(
                            "${filtered.size} / ${state.animes.size} 部作品",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                singleLine = true,
                label = { Text("在 $title 中搜索") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Clear, contentDescription = "清空搜索")
                        }
                    }
                } else null,
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(StatusCollectionSort.entries, key = StatusCollectionSort::name) { option ->
                    FilterChip(
                        selected = sort == option,
                        onClick = { sort = option },
                        label = { Text(option.displayName) },
                    )
                }
            }
            AnimatedContent(
                targetState = filtered.isEmpty(),
                modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn(tween(duration)) togetherWith fadeOut(tween(duration)) },
                label = "status_collection_content",
            ) { empty ->
                if (empty) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Outlined.MovieFilter,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                            Text(if (state.animes.isEmpty()) "这个状态还没有作品" else "没有匹配的作品")
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy((10 * settings.contentDensity.scale).dp),
                    ) {
                        items(filtered, key = AnimeEntity::id) { anime ->
                            StatusAnimeCard(anime, settings, statusColor) { onAnimeClick(anime.id) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusAnimeCard(
    anime: AnimeEntity,
    settings: AppearanceSettings,
    statusColor: Color,
    onClick: () -> Unit,
) {
    val scale = settings.contentDensity.scale
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(
            role = Role.Button,
            onClickLabel = "查看《${anime.title}》详情",
            onClick = onClick,
        ),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding((12 * scale).dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy((12 * scale).dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = (70 * scale).dp, height = (100 * scale).dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (anime.coverUrl.isNullOrBlank()) {
                    Icon(Icons.Outlined.MovieFilter, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                } else {
                    AsyncImage(
                        model = anime.coverUrl,
                        contentDescription = anime.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    anime.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                anime.originalTitle?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (settings.showStatus) {
                        val chipAlpha = if (settings.statusChipFilled) 0.25f else 0.14f
                        Text(
                            anime.status.ifBlank { "未分类" },
                            modifier = Modifier.clip(CircleShape).background(statusColor.copy(alpha = chipAlpha))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            color = statusColor,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (settings.showProgress && anime.totalEpisodes > 0) {
                        Text("${anime.watchedEpisodes}/${anime.totalEpisodes} 集", style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (settings.showRating && (anime.rating != null || !anime.ratingGrade.isNullOrBlank())) {
                    val rating = normalizeAnimeRatingGrade(anime.ratingGrade) ?: formatAnimeRating(anime.rating)
                    Text(
                        listOf(settings.ratingIconStyle.symbol, rating).filter(String::isNotBlank).joinToString(" "),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = "打开 ${anime.title}",
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

internal fun filterStatusAnime(
    animes: List<AnimeEntity>,
    query: String,
    sort: StatusCollectionSort,
): List<AnimeEntity> {
    val keyword = query.trim()
    return animes.asSequence()
        .filter {
            keyword.isEmpty() || it.title.contains(keyword, ignoreCase = true) ||
                it.originalTitle?.contains(keyword, ignoreCase = true) == true
        }
        .let { sequence ->
            when (sort) {
                StatusCollectionSort.RECENT -> sequence.sortedByDescending(AnimeEntity::id)
                StatusCollectionSort.TITLE -> sequence.sortedBy { it.title.lowercase(java.util.Locale.ROOT) }
                StatusCollectionSort.RATING -> sequence.sortedByDescending { animeRatingSortValue(it) ?: -1 }
                StatusCollectionSort.PROGRESS -> sequence.sortedByDescending {
                    if (it.totalEpisodes > 0) it.watchedEpisodes.toDouble() / it.totalEpisodes else it.watchedEpisodes.toDouble()
                }
            }
        }
        .toList()
}
