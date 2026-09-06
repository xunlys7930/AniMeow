package com.animeow.app.ui.management

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.local.WatchStatusEntity
import com.animeow.app.ui.components.CustomizableColorSelector
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut

private enum class ManagementSection(val label: String) {
    STATUS("状态"),
    TAG("标签"),
    SERIES("系列"),
}

private data class EditorRequest(
    val section: ManagementSection,
    val id: Long = 0,
    val name: String = "",
    val description: String = "",
    val color: Long? = null,
    val count: Int? = null,
    val coverUrl: String? = null,
)

private data class DeleteRequest(
    val section: ManagementSection,
    val id: Long,
    val name: String,
    val count: Int = 0,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryManagementScreen(
    onBack: () -> Unit,
    onTagIndexRequested: () -> Unit,
    onTagClick: (Long) -> Unit,
    viewModel: LibraryManagementViewModel = viewModel(),
) {
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val statusItems by viewModel.statusItems.collectAsStateWithLifecycle()
    val tagItems by viewModel.tagItems.collectAsStateWithLifecycle()
    val seriesItems by viewModel.seriesItems.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var section by remember { mutableStateOf(ManagementSection.STATUS) }
    var editor by remember { mutableStateOf<EditorRequest?>(null) }
    var deleteRequest by remember { mutableStateOf<DeleteRequest?>(null) }
    var showStatusOrder by remember { mutableStateOf(false) }
    var coverSeries by remember { mutableStateOf<ManagedSeries?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val sectionEnter = motionFadeIn(180)
    val sectionExit = motionFadeOut(140)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("状态、标签与系列") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (section == ManagementSection.STATUS) {
                        IconButton(onClick = { showStatusOrder = true }, enabled = !busy) {
                            Icon(Icons.Outlined.SwapVert, contentDescription = "调整状态顺序")
                        }
                    }
                    if (section == ManagementSection.TAG) {
                        IconButton(onClick = onTagIndexRequested, enabled = !busy) {
                            Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = "打开标签索引")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!busy) {
                FloatingActionButton(
                    onClick = { editor = EditorRequest(section = section) },
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "新增${section.label}")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ManagementSection.entries) { option ->
                    FilterChip(selected = section == option, onClick = { section = option }, label = { Text(option.label) })
                }
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            AnimatedContent(
                targetState = section,
                modifier = Modifier.weight(1f),
                transitionSpec = { sectionEnter togetherWith sectionExit },
                label = "management_section",
            ) { current ->
                val rows = when (current) {
                    ManagementSection.STATUS -> statusItems.map {
                        EditorRequest(current, it.status.id, it.status.name, color = it.status.color, count = it.animeCount)
                    }
                    ManagementSection.TAG -> tagItems.map {
                        EditorRequest(current, it.tag.id, it.tag.name, color = it.tag.color, count = it.animeCount)
                    }
                    ManagementSection.SERIES -> seriesItems.map {
                        EditorRequest(
                            section = current,
                            id = it.series.id,
                            name = it.series.name,
                            description = it.series.description.orEmpty(),
                            count = it.animes.size,
                            coverUrl = it.coverUrl,
                        )
                    }
                }
                if (rows.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("还没有${current.label}，点击 + 新建")
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 96.dp),
                    ) {
                        items(rows, key = { "${it.section}_${it.id}" }) { row ->
                            ListItem(
                                leadingContent = {
                                    if (row.section == ManagementSection.SERIES) {
                                        Box(
                                            modifier = Modifier.size(width = 42.dp, height = 58.dp)
                                                .clip(MaterialTheme.shapes.medium)
                                                .background(MaterialTheme.colorScheme.primaryContainer),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (!row.coverUrl.isNullOrBlank()) {
                                                AsyncImage(
                                                    model = row.coverUrl,
                                                    contentDescription = null,
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentScale = ContentScale.Crop,
                                                )
                                            } else {
                                                Icon(Icons.Outlined.CollectionsBookmark, contentDescription = null)
                                            }
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier.size(38.dp).clip(CircleShape)
                                                .background(Color(row.color ?: DEFAULT_MANAGEMENT_COLOR).copy(alpha = 0.16f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                Icons.AutoMirrored.Outlined.Label,
                                                contentDescription = null,
                                                tint = Color(row.color ?: DEFAULT_MANAGEMENT_COLOR),
                                            )
                                        }
                                    }
                                },
                                headlineContent = {
                                    Text(row.name, fontWeight = FontWeight.SemiBold)
                                },
                                supportingContent = when (row.section) {
                                    ManagementSection.TAG -> ({ Text("${row.count ?: 0} 部作品使用 · 点击浏览") })
                                    ManagementSection.SERIES -> ({
                                        Text(
                                            listOfNotNull(
                                                row.description.takeIf(String::isNotBlank),
                                                "${row.count ?: 0} 部作品",
                                            ).joinToString(" · "),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    })
                                    ManagementSection.STATUS -> ({ Text("${row.count ?: 0} 部作品使用") })
                                },
                                trailingContent = {
                                    Row {
                                        if (row.section == ManagementSection.SERIES) {
                                            IconButton(
                                                onClick = {
                                                    coverSeries = seriesItems.firstOrNull { it.series.id == row.id }
                                                },
                                                enabled = !busy,
                                            ) {
                                                Icon(Icons.Outlined.PhotoLibrary, contentDescription = "选择系列封面")
                                            }
                                        }
                                        IconButton(onClick = { editor = row }, enabled = !busy) {
                                            Icon(Icons.Outlined.Edit, contentDescription = "编辑")
                                        }
                                        IconButton(
                                            onClick = {
                                                deleteRequest = DeleteRequest(
                                                    row.section,
                                                    row.id,
                                                    row.name,
                                                    row.count ?: 0,
                                                )
                                            },
                                            enabled = !busy,
                                        ) {
                                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除")
                                        }
                                    }
                                },
                                modifier = Modifier.clickable(enabled = !busy) {
                                    if (row.section == ManagementSection.TAG) onTagClick(row.id) else editor = row
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    editor?.let { request ->
        ManagementEditorDialog(
            request = request,
            busy = busy,
            onDismiss = { if (!busy) editor = null },
            onSave = { name, description, color ->
                when (request.section) {
                    ManagementSection.STATUS -> viewModel.saveStatus(request.id, name, color) { editor = null }
                    ManagementSection.TAG -> viewModel.saveTag(request.id, name, color) { editor = null }
                    ManagementSection.SERIES -> viewModel.saveSeries(request.id, name, description) { editor = null }
                }
            },
        )
    }

    deleteRequest?.let { request ->
        ManagementDeleteDialog(
            request = request,
            statuses = statuses,
            busy = busy,
            onDelete = { replacementStatusId ->
                when (request.section) {
                    ManagementSection.STATUS -> viewModel.deleteStatus(request.id, replacementStatusId) {
                        deleteRequest = null
                    }
                    ManagementSection.TAG -> viewModel.deleteTag(request.id) { deleteRequest = null }
                    ManagementSection.SERIES -> viewModel.deleteSeries(request.id) { deleteRequest = null }
                }
            },
            onDismiss = { if (!busy) deleteRequest = null },
        )
    }

    if (showStatusOrder) {
        StatusOrderDialog(
            statuses = statuses,
            busy = busy,
            onSave = { ids -> viewModel.reorderStatuses(ids) { showStatusOrder = false } },
            onDismiss = { if (!busy) showStatusOrder = false },
        )
    }

    coverSeries?.let { item ->
        SeriesCoverSheet(
            item = item,
            busy = busy,
            onSelected = { coverUrl ->
                viewModel.saveSeriesCover(item.series.id, coverUrl) { coverSeries = null }
            },
            onDismiss = { if (!busy) coverSeries = null },
        )
    }
}

@Composable
private fun ManagementEditorDialog(
    request: EditorRequest,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, Long?) -> Unit,
) {
    var name by remember(request) { mutableStateOf(request.name) }
    var description by remember(request) { mutableStateOf(request.description) }
    var color by remember(request) { mutableStateOf(request.color ?: DEFAULT_MANAGEMENT_COLOR) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(if (request.id > 0) "编辑${request.section.label}" else "新增${request.section.label}")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                )
                if (request.section == ManagementSection.SERIES) {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("系列说明") },
                        minLines = 3,
                    )
                } else {
                    Text("颜色", style = MaterialTheme.typography.labelLarge)
                    CustomizableColorSelector(
                        color = color,
                        presets = MANAGEMENT_COLORS,
                        onColorChanged = { color = it },
                        dialogTitle = "自定义${request.section.label}颜色",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, description, color) },
                enabled = name.isNotBlank() && !busy,
            ) { Text(if (busy) "保存中…" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

@Composable
private fun ManagementDeleteDialog(
    request: DeleteRequest,
    statuses: List<WatchStatusEntity>,
    busy: Boolean,
    onDelete: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val needsMigration = request.section == ManagementSection.STATUS && request.count > 0
    val alternatives = statuses.filterNot { it.id == request.id }
    var replacementStatusId by remember(request.id) { mutableStateOf<Long?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(if (needsMigration) "迁移并删除状态？" else "删除${request.section.label}？")
        },
        text = {
            when {
                needsMigration -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "“${request.name}”仍被 ${request.count} 部作品使用（含回收站）。请选择迁移目标，作品不会丢失。",
                        )
                        if (alternatives.isEmpty()) {
                            Text(
                                "当前没有其他状态可迁移。请先新建状态，或修改相关作品后再删除。",
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Text("迁移到", style = MaterialTheme.typography.labelLarge)
                            LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                                items(alternatives, key = WatchStatusEntity::id) { status ->
                                    ListItem(
                                        headlineContent = { Text(status.name) },
                                        leadingContent = {
                                            Box(
                                                modifier = Modifier.size(24.dp).clip(CircleShape)
                                                    .background(Color(status.color)),
                                            )
                                        },
                                        trailingContent = {
                                            RadioButton(
                                                selected = replacementStatusId == status.id,
                                                onClick = null,
                                            )
                                        },
                                        modifier = Modifier.clickable(
                                            role = Role.RadioButton,
                                            onClick = { replacementStatusId = status.id },
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                request.section == ManagementSection.STATUS -> Text("没有作品使用“${request.name}”，可安全删除。")
                request.section == ManagementSection.TAG -> Text("作品本身不会删除，只移除这个标签关联。")
                else -> Text("系列内作品会保留，并变为无系列。")
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDelete(replacementStatusId) },
                enabled = (!needsMigration || replacementStatusId != null) && !busy,
            ) { Text(if (busy) "处理中…" else if (needsMigration) "迁移并删除" else "删除") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

@Composable
private fun StatusOrderDialog(
    statuses: List<WatchStatusEntity>,
    busy: Boolean,
    onSave: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var ordered by remember(statuses) { mutableStateOf(statuses) }

    fun move(from: Int, to: Int) {
        if (from !in ordered.indices || to !in ordered.indices || from == to) return
        ordered = ordered.toMutableList().apply { add(to, removeAt(from)) }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("调整状态顺序") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "此顺序同时用于首页筛选和“切换到下一个状态”快捷动作。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(ordered, key = WatchStatusEntity::id) { status ->
                        val index = ordered.indexOfFirst { it.id == status.id }
                        ListItem(
                            leadingContent = {
                                Box(
                                    modifier = Modifier.size(26.dp).clip(CircleShape)
                                        .background(Color(status.color)),
                                )
                            },
                            headlineContent = { Text(status.name, fontWeight = FontWeight.SemiBold) },
                            trailingContent = {
                                Row {
                                    IconButton(
                                        onClick = { move(index, index - 1) },
                                        enabled = index > 0 && !busy,
                                    ) {
                                        Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "上移")
                                    }
                                    IconButton(
                                        onClick = { move(index, index + 1) },
                                        enabled = index < ordered.lastIndex && !busy,
                                    ) {
                                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "下移")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(ordered.map(WatchStatusEntity::id)) },
                enabled = !busy,
            ) { Text(if (busy) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeriesCoverSheet(
    item: ManagedSeries,
    busy: Boolean,
    onSelected: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val candidates = item.animes.filter { !it.coverUrl.isNullOrBlank() }
        .sortedByDescending { it.id }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("选择系列封面", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        item.series.name,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { onSelected(null) }, enabled = !busy) { Text("恢复自动") }
            }
            if (candidates.isEmpty()) {
                Text(
                    "系列成员暂时都没有封面。添加封面后即可在这里选择。",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    gridItems(candidates, key = { it.id }) { anime ->
                        Column(
                            modifier = Modifier.clickable(
                                enabled = !busy,
                                onClickLabel = "设为系列封面",
                                onClick = { onSelected(anime.coverUrl) },
                            ),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxWidth().aspectRatio(0.7f)
                                    .clip(MaterialTheme.shapes.large)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                            ) {
                                AsyncImage(
                                    model = anime.coverUrl,
                                    contentDescription = anime.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                                if (item.series.customCoverUrl == anime.coverUrl) {
                                    Box(
                                        modifier = Modifier.fillMaxSize()
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Outlined.CheckCircle,
                                            contentDescription = "当前封面",
                                            tint = Color.White,
                                            modifier = Modifier.size(34.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                anime.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val DEFAULT_MANAGEMENT_COLOR = 0xFF8A4FD0
private val MANAGEMENT_COLORS = listOf(
    0xFF3482FF,
    0xFF8A4FD0,
    0xFFE85D75,
    0xFFFF8A34,
    0xFF18A999,
    0xFF3E9B55,
    0xFF6C6CE5,
    0xFF707680,
)
