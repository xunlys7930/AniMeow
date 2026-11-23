package com.animeow.app.ui.anime

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** These controls edit the existing draft; persistence stays with the editor's save/exit policy. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProgressEditorCard(
    watchedEpisodes: String,
    totalEpisodes: String,
    status: String,
    statuses: List<String>,
    onWatchedChanged: (String) -> Unit,
    onTotalChanged: (String) -> Unit,
    onStatusSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    spacingScale: Float = 1f,
) {
    val watched = if (watchedEpisodes.isBlank()) 0 else watchedEpisodes.toIntOrNull()
    val total = if (totalEpisodes.isBlank()) 0 else totalEpisodes.toIntOrNull()
    ElevatedCard(modifier.fillMaxWidth().testTag("editor-progress-card"), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding((16 * spacingScale).dp), verticalArrangement = Arrangement.spacedBy((12 * spacingScale).dp)) {
            Text("观看进度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (watched != null && total != null) {
                Text(episodeProgressLabel(watched, total), style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold)
                if (total > 0) LinearProgressIndicator(progress = { (watched.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth())
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 300.dp || LocalDensity.current.fontScale > 1.2f
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EpisodeCountField(watchedEpisodes, "已看集数", onWatchedChanged, Modifier.fillMaxWidth())
                        EpisodeCountField(totalEpisodes, "总集数", onTotalChanged, Modifier.fillMaxWidth(), "未知时填 0")
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        EpisodeCountField(watchedEpisodes, "已看集数", onWatchedChanged, Modifier.weight(1f))
                        EpisodeCountField(totalEpisodes, "总集数", onTotalChanged, Modifier.weight(1f), "未知时填 0")
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { watched?.let { onWatchedChanged((it - 1).toString()) } },
                    enabled = watched != null && watched > 0, modifier = Modifier.testTag("editor-progress-decrement")) {
                    Text("−1 集")
                }
                FilledTonalButton(onClick = { watched?.let { onWatchedChanged((it + 1).toString()) } },
                    enabled = watched != null && total != null && watched < Int.MAX_VALUE && (total <= 0 || watched < total),
                    modifier = Modifier.testTag("editor-progress-increment")) {
                    Text("+1 集")
                }
            }
            if (watched != null && total != null && total > 0 && watched > total) {
                Text("已看集数超过总集数，请核对总集数。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            Text("记录状态", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (statuses.ifEmpty { listOf("想看", "在看", "看完", "搁置", "弃坑") } + status)
                    .distinct().filter(String::isNotBlank).forEach { label ->
                    FilterChip(selected = status == label, onClick = { onStatusSelected(label) }, label = { Text(label) })
                }
            }
            Text("点击「保存」更新观看进度。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EpisodeCountField(value: String, label: String, onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier, hint: String? = null) {
    val invalid = value.isNotBlank() && value.toIntOrNull() == null
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid || hint != null) ({ Text(if (invalid) "集数太大，请检查输入" else hint.orEmpty()) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
