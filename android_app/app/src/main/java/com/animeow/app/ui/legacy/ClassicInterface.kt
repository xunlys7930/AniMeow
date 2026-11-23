@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.animeow.app.ui.legacy

import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.icons.AppIcon as Icon

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.animeow.app.ui.theme.FrontendMode

val LocalFrontendMode = staticCompositionLocalOf { FrontendMode.MODERN }

/** Flutter-era Material 3: titled app bars, filled search, flat navigation and icon-led sections. */
@Composable
internal fun ClassicTopAppBar(title: String, subtitle: String, actions: @Composable RowScope.() -> Unit) {
    Column {
        TopAppBar(
            title = { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    }
}

@Composable
internal fun ClassicLibraryToolbar(query: String, onQuery: (String) -> Unit, sortLabel: String, onSort: () -> Unit,
    filterCount: Int, onFilter: () -> Unit, selectionMode: Boolean, onSelection: () -> Unit) {
    val feedback = com.animeow.app.ui.components.LocalActionFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("搜索标题、系列或别名", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(AppIcons.Search, null) },
            trailingIcon = {
                Row {
                    if (query.isNotBlank()) IconButton(onClick = { onQuery("") }) { Icon(AppIcons.Close, "清除搜索") }
                    IconButton(onClick = { feedback.selection(); onFilter() }) {
                        BadgedBox(badge = { if (filterCount > 0) Badge { Text(filterCount.toString()) } }) { Icon(AppIcons.Filter, "筛选作品") }
                    }
                }
            }, shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { feedback.selection(); onSort() }) {
                Icon(AppIcons.Sort, null, Modifier.size(18.dp))
                Text(sortLabel, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { feedback.selection(); onSelection() }) {
                Icon(if (selectionMode) AppIcons.Close else AppIcons.CheckCircle, null, Modifier.size(18.dp))
                Text(if (selectionMode) "结束多选" else "多选作品", Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
internal fun ClassicSectionHeading(title: String, subtitle: String, modifier: Modifier = Modifier) {
    val icon = when {
        "书" in title -> AppIcons.Book
        "正在" in title -> AppIcons.Play
        "最近" in title -> AppIcons.History
        else -> AppIcons.Grid
    }
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
            Icon(icon, null, Modifier.padding(9.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ClassicStatusTabs(options: List<String>, selected: String, onSelect: (String) -> Unit, counts: Map<String, Int>) {
    val state = rememberLazyListState()
    val feedback = com.animeow.app.ui.components.LocalActionFeedback.current
    LaunchedEffect(selected) {
        val index = options.indexOf(selected)
        if (index >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == index }) state.animateScrollToItem(index)
    }
    Column {
        LazyRow(state = state, modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(options, key = { it }) { label ->
                val active = label == selected
                Column(Modifier.semantics { this.selected = active }.clickable(role = Role.Tab) { feedback.selection(); onSelect(label) }
                    .padding(horizontal = 12.dp).heightIn(min = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(label + if ((counts[label] ?: 0) > 0) " ${counts[label]}" else "", style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(7.dp))
                    Box(Modifier.width(22.dp).height(3.dp).background(if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(2.dp)))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}
