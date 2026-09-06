@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.animeow.app.ui.legacy

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.local.formatAnimeRating
import com.animeow.app.data.local.isBookSubjectType
import com.animeow.app.data.local.subjectTypeDisplayName
import com.animeow.app.ui.anime.AnimeDetailViewModel
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.theme.AppearanceSettings
import kotlinx.coroutines.launch

@Composable
fun LegacyAnimeDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    settings: AppearanceSettings,
    modifier: Modifier = Modifier,
    viewModel: AnimeDetailViewModel = viewModel(),
) {
    val anime by viewModel.anime.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val tagIds by viewModel.tagIds.collectAsStateWithLifecycle()
    val series by viewModel.series.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var showStatus by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    val current = anime
    if (current == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val total = current.totalEpisodes.coerceAtLeast(current.watchedEpisodes)
    val progress = if (total > 0) current.watchedEpisodes.toFloat() / total else 0f
    val rating = formatAnimeRating(current.rating).ifBlank { current.ratingGrade.orEmpty() }
    val linkedTags = allTags.filter { it.id in tagIds }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 42.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth().height(520.dp)) {
                    AsyncImage(
                        model = current.coverUrl,
                        contentDescription = current.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent, Color.Black.copy(alpha = 0.9f)),
                            ),
                        ),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.3f),
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = Color.White)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.3f)) {
                                IconButton(onClick = { onEdit(current.id) }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = "编辑", tint = Color.White)
                                }
                            }
                            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.3f)) {
                                IconButton(onClick = { showMore = true }) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多", tint = Color.White)
                                }
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.align(Alignment.BottomStart).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            subjectTypeDisplayName(current.subjectType),
                            modifier = Modifier.background(Color.White.copy(alpha = 0.22f), MaterialTheme.shapes.small)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            current.title,
                            color = Color.White,
                            style = MaterialTheme.typography.displaySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                current.status,
                                modifier = Modifier.background(
                                    if (current.status == "看完") LegacyGreen else LegacyBlue,
                                    CircleShape,
                                ).padding(horizontal = 12.dp, vertical = 5.dp),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                            )
                            current.airDate?.takeIf(String::isNotBlank)?.let {
                                Text(it, color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.Bold)
                            }
                            if (rating.isNotBlank()) {
                                Text("★ $rating", color = Color(0xFFFFC107), style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.74f)),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        LegacySectionTitle(Icons.Outlined.ShowChart, "观看进度")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { showStatus = true }) {
                                Icon(Icons.Outlined.Flag, contentDescription = null)
                                Text(" ${current.status}")
                                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${current.watchedEpisodes} / $total",
                                style = MaterialTheme.typography.headlineMedium,
                                color = LegacyBlue,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
                            color = LegacyBlue,
                            trackColor = LegacyBlue.copy(alpha = 0.12f),
                        )
                        Text("${(progress * 100).toInt()}% 完成", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch { viewModel.setProgress((current.watchedEpisodes - 1).coerceAtLeast(0)) }
                                },
                                enabled = current.watchedEpisodes > 0,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Outlined.Remove, contentDescription = null)
                                Text(" 减少")
                            }
                            Button(
                                onClick = {
                                    scope.launch {
                                        val changed = viewModel.incrementProgress(
                                            settings.autoCompleteStatus,
                                            settings.completionStatus,
                                        )
                                        if (changed == null) snackbar.showSnackbar("已经达到目标进度")
                                    }
                                },
                                enabled = total <= 0 || current.watchedEpisodes < total,
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Outlined.Add, contentDescription = null)
                                Text(" 记录一集")
                            }
                        }
                    }
                }
            }
            if (com.animeow.app.ui.theme.DetailModule.WATCH_DATES !in settings.hiddenDetailModules) item {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    com.animeow.app.ui.anime.WatchTimelineContent(current, { onEdit(current.id) }, Modifier.padding(20.dp))
                }
            }
            if (!current.review.isNullOrBlank()) item {
                LegacyTextSection(
                    title = "评价 / 备注",
                    text = current.review.orEmpty(),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            if (!current.synopsis.isNullOrBlank()) item {
                LegacyTextSection(
                    title = "作品简介",
                    text = current.synopsis.orEmpty(),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.74f)),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        LegacySectionTitle(Icons.Outlined.Subject, "作品资料")
                        LegacyMetadataRow("类型", subjectTypeDisplayName(current.subjectType))
                        current.mediaFormat?.takeIf(String::isNotBlank)?.let { LegacyMetadataRow("格式", it) }
                        current.studio?.takeIf(String::isNotBlank)?.let { LegacyMetadataRow("制作", it) }
                        series?.let { LegacyMetadataRow("系列", it.name) }
                        if (current.tvEpisodes > 0 || current.spEpisodes > 0) {
                            LegacyMetadataRow("集数", "TV ${current.tvEpisodes} · SP ${current.spEpisodes}")
                        }
                        if (linkedTags.isNotEmpty()) LegacyMetadataRow("标签", linkedTags.joinToString(" · ") { it.name })
                    }
                }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    if (showStatus) {
        StableModalBottomSheet(onDismissRequest = { showStatus = false }, sheetGesturesEnabled = false) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 20.dp),
            ) {
                item { Text("当前状态", style = MaterialTheme.typography.headlineSmall) }
                item { Spacer(Modifier.height(12.dp)) }
                items(statuses, key = { it.id }) { status ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            scope.launch { viewModel.setStatus(status.name) }
                            showStatus = false
                        }.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LegacyDot(Color(status.color.toInt()))
                        Text(status.name, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }

    if (showMore) {
        ModalBottomSheet(onDismissRequest = { showMore = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text("作品操作", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = {
                        showMore = false
                        onEdit(current.id)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = null)
                    Text(" 编辑作品")
                }
                TextButton(
                    onClick = {
                        showMore = false
                        showDelete = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                    Text(" 移入回收站")
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("移入回收站？") },
            text = { Text("作品会保留在回收站中，可从“我的”页面恢复。") },
            confirmButton = {
                Button(onClick = {
                    showDelete = false
                    scope.launch {
                        viewModel.deleteAnime()
                        onBack()
                    }
                }) { Text("移入回收站") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun LegacyTextSection(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.74f)),
        ) {
            Text(text, modifier = Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun LegacyMetadataRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(width = 64.dp, height = 28.dp))
        Text(value, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
    }
}
