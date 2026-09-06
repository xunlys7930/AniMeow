package com.animeow.app.ui.series

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut

private enum class ShelfSection(val label: String) {
    SERIES("系列"),
    STANDALONE("独立作品"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesShelfScreen(
    onBack: () -> Unit,
    onSeriesClick: (Long) -> Unit,
    onAnimeClick: (Long) -> Unit,
    viewModel: SeriesShelfViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var section by remember { mutableStateOf(ShelfSection.SERIES) }
    val sectionEnter = motionFadeIn(220)
    val sectionExit = motionFadeOut(150)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("系列书架") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ShelfSection.entries.forEach { option ->
                    FilterChip(
                        selected = section == option,
                        onClick = { section = option },
                        label = {
                            Text(
                                "${option.label} (${if (option == ShelfSection.SERIES) state.series.size else state.standalone.size})",
                            )
                        },
                    )
                }
            }
            AnimatedContent(
                targetState = section,
                modifier = Modifier.weight(1f),
                transitionSpec = { sectionEnter togetherWith sectionExit },
                label = "series_shelf_section",
            ) { current ->
                when (current) {
                    ShelfSection.SERIES -> SeriesList(state.series, onSeriesClick)
                    ShelfSection.STANDALONE -> StandaloneList(state.standalone, onAnimeClick)
                }
            }
        }
    }
}

@Composable
private fun SeriesList(items: List<SeriesShelfItem>, onClick: (Long) -> Unit) {
    if (items.isEmpty()) {
        EmptyShelf("还没有包含作品的系列")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.series.id }) { item ->
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().clickable { onClick(item.series.id) },
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShelfCover(item.coverUrl, item.series.name)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.series.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        item.series.description?.let {
                            Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("${item.animes.size} 部作品 · ${item.watchedEpisodes}/${item.totalEpisodes.takeIf { it > 0 } ?: "?"} 集")
                        if (item.totalEpisodes > 0) {
                            LinearProgressIndicator(
                                progress = { (item.watchedEpisodes.toFloat() / item.totalEpisodes).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StandaloneList(items: List<AnimeEntity>, onClick: (Long) -> Unit) {
    if (items.isEmpty()) {
        EmptyShelf("所有作品都已归入系列")
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = AnimeEntity::id) { anime ->
            ElevatedCard(modifier = Modifier.fillMaxWidth().clickable { onClick(anime.id) }) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ShelfCover(anime.coverUrl, anime.title, compact = true)
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(anime.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${anime.status} · ${anime.watchedEpisodes}/${anime.totalEpisodes.takeIf { it > 0 } ?: "?"} 集", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ShelfCover(url: String?, title: String, compact: Boolean = false) {
    val width = if (compact) 48.dp else 76.dp
    val height = if (compact) 68.dp else 108.dp
    Box(
        modifier = Modifier.size(width, height).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(title.take(2), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EmptyShelf(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.CollectionsBookmark, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.primary)
            Text(message, style = MaterialTheme.typography.titleMedium)
        }
    }
}
