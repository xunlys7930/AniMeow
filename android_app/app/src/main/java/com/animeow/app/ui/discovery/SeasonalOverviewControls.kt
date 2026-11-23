package com.animeow.app.ui.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import com.animeow.app.data.remote.DiscoveryFilters
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeasonalOverviewControls(
    filters: DiscoveryFilters,
    onFiltersChanged: (DiscoveryFilters) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    androidx.compose.material3.AssistChip(onClick = { expanded = true }, modifier = modifier,
        label = { Text("${filters.year} 年 ${filters.seasonMonth} 月新番") },
        trailingIcon = { com.animeow.app.ui.icons.AppIcon(com.animeow.app.ui.icons.AppIcons.Expand, "选择年份与季度") })
    if (expanded) com.animeow.app.ui.components.StableModalBottomSheet(onDismissRequest = { expanded = false }) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SeasonalOverviewPicker(filters, onFiltersChanged)
            androidx.compose.material3.FilledTonalButton(onClick = { expanded = false }, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeasonalOverviewPicker(
    filters: DiscoveryFilters,
    onFiltersChanged: (DiscoveryFilters) -> Unit,
    modifier: Modifier = Modifier,
) {
    val year = filters.year ?: return
    val today = LocalDate.now()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${year} 年 ${filters.seasonMonth} 月新番总览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onFiltersChanged(filters.copy(year = year - 1)) }, enabled = year > 1) { Text("上一年") }
            TextButton(onClick = { onFiltersChanged(filters.copy(year = year + 1)) }, enabled = year < 9999) { Text("下一年") }
            TextButton(onClick = {
                val current = seasonalDiscoveryFilters(today)
                onFiltersChanged(filters.copy(year = current.year, seasonMonth = current.seasonMonth))
            }) { Text("本季") }
            TextButton(onClick = {
                val next = seasonalDiscoveryFilters(today.plusMonths(3))
                onFiltersChanged(filters.copy(year = next.year, seasonMonth = next.seasonMonth))
            }) { Text("下一季") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to "1 月 · 冬季", 4 to "4 月 · 春季", 7 to "7 月 · 夏季", 10 to "10 月 · 秋季").forEach { (month, label) ->
                FilterChip(
                    selected = filters.seasonMonth == month,
                    onClick = { onFiltersChanged(filters.copy(seasonMonth = month)) },
                    label = { Text(label) },
                )
            }
        }
        Text(
            "片单由服务器定期整理，未开播作品也会显示；尚未整理的季度会提示稍后重试。点开查看详情，加入资料库即可收藏为「想看」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
