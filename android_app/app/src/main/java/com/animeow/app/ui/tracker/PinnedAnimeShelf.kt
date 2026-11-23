package com.animeow.app.ui.tracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.ui.icons.AppIcon
import com.animeow.app.ui.icons.AppIcons

@Composable
internal fun PinnedAnimeShelf(animes: List<AnimeEntity>, onOpen: (Long) -> Unit, onUnpin: (Long) -> Unit, showPinBadges: Boolean = true) {
    if (animes.isEmpty()) return
    var unpinTarget by remember { mutableStateOf<AnimeEntity?>(null) }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showPinBadges) AppIcon(AppIcons.Pin, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text("置顶作品 · ${animes.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(animes, key = AnimeEntity::id) { anime ->
                Surface(shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(112.dp)
                        .combinedClickable(onClick = { onOpen(anime.id) }, onLongClick = { unpinTarget = anime }, onLongClickLabel = "取消置顶")) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(136.dp)) {
                            AsyncImage(anime.coverUrl, anime.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (showPinBadges) IconButton(onClick = { onUnpin(anime.id) }, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(40.dp)) {
                                Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                    shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.size(22.dp).testTag("pin-badge-${anime.id}")) {
                                    Box(contentAlignment = Alignment.Center) {
                                        AppIcon(AppIcons.Pin, "取消置顶《${anime.title}》", Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                        Text(anime.title, Modifier.padding(10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    unpinTarget?.let { anime ->
        AlertDialog(onDismissRequest = { unpinTarget = null }, title = { Text("取消置顶？") },
            text = { Text("《${anime.title}》会保留在资料库中。") },
            confirmButton = { TextButton(onClick = { onUnpin(anime.id); unpinTarget = null }) { Text("取消置顶") } },
            dismissButton = { TextButton(onClick = { unpinTarget = null }) { Text("保留置顶") } })
    }
}
