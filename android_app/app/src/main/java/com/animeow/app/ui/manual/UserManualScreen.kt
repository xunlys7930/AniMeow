package com.animeow.app.ui.manual

import com.animeow.app.ui.icons.AppIcons

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import com.animeow.app.ui.icons.AppIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.animeow.app.BuildConfig
import com.animeow.app.data.preferences.ManualDisplayMode
import com.animeow.app.data.preferences.UserManualPreferences
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserManualScreen(
    onBack: () -> Unit,
    initialQuery: String = "",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { UserManualPreferences(context) }
    val settings by preferences.settings.collectAsState(initial = com.animeow.app.data.preferences.UserManualSettings())
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var showTips by rememberSaveable { mutableStateOf(false) }
    val allSections = remember {
        manualSections() + ManualSection("2.1.8 功能入口", version218GuideEntries())
    }
    val normalized = query.trim().lowercase(Locale.ROOT)
    val sections = remember(allSections, normalized, category) {
        allSections.filter { category == null || it.title == category }.mapNotNull { section ->
            val filtered = if (normalized.isBlank()) section.entries else section.entries.filter { entry ->
                listOf(section.title, entry.title, entry.description, entry.location, entry.keywords)
                    .any { it.lowercase(Locale.ROOT).contains(normalized) }
            }
            if (filtered.isEmpty()) null else section.copy(entries = filtered)
        }
    }
    val totalEntries = sections.sumOf { it.entries.size }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("用户使用手册")
                        Text(
                            "v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showTips = true }) {
                        Icon(AppIcons.Idea, contentDescription = "每日提示")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotBlank()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(AppIcons.Close, contentDescription = "清空搜索")
                            }
                        }
                    } else {
                        null
                    },
                    placeholder = { Text("搜索功能名称、关键词或位置") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                )
            }
            item(key = "categories") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(category == null, { category = null }, { Text("全部") })
                    allSections.forEach { section ->
                        FilterChip(category == section.title, { category = section.title }, { Text(section.title) })
                    }
                }
            }
            if (query.isNotBlank() || category != null) {
                item {
                    Text(
                        "找到 $totalEntries 条结果",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (sections.isEmpty()) {
                item {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "没有匹配的内容。试试换个关键词，如「备份」「角色」「社区」「主题」。",
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                sections.forEach { section ->
                    item(key = "header-${section.title}") {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(section.entries, key = { "${section.title}-${it.title}" }) { entry ->
                        ManualEntryCard(entry)
                    }
                }
            }
        }
    }
    if (showTips) AlertDialog(
        onDismissRequest = { showTips = false },
        title = { Text("每日提示") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("每天首次打开 App 时，显示一条功能小贴士。")
                listOf(ManualDisplayMode.DAILY to "每日显示", ManualDisplayMode.SKIP_TODAY to "今日不显示", ManualDisplayMode.NEVER to "永不显示").forEach { (mode, label) ->
                    Row(Modifier.fillMaxWidth().selectable(settings.displayMode == mode, role = Role.RadioButton,
                        onClick = { scope.launch { preferences.setDisplayMode(mode) } }).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(settings.displayMode == mode, onClick = null)
                        Text(label, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showTips = false }) { Text("完成") } },
    )
}

@Composable
private fun ManualEntryCard(entry: ManualEntry) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                entry.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    AppIcons.Location,
                    contentDescription = null,
                    modifier = Modifier.padding(top = 2.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "  ${entry.location}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
