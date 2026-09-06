package com.animeow.app.ui.maintenance

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.animeow.app.data.update.AppUpdateDownloadOption
import com.animeow.app.ui.components.AniMeowEnterEasing
import com.animeow.app.ui.components.AniMeowExitEasing
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionDurationMillis
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.BuildConfig
import com.animeow.app.R
import com.animeow.app.data.maintenance.CacheCategory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutCenterScreen(
    updateState: UpdateUiState,
    onBack: () -> Unit,
    onCheckUpdate: () -> Unit,
    onAutoCheckChanged: (Boolean) -> Unit,
    onClearIgnoredVersion: () -> Unit,
    onCacheRequested: () -> Unit,
    onHelpRequested: () -> Unit,
    onChangelogRequested: () -> Unit,
    onDisclaimerRequested: () -> Unit,
    onCreditsRequested: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun open(url: String) {
        if (!openExternal(context, url)) scope.launch { snackbar.showSnackbar("没有可打开此链接的应用") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于与维护") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.foundation.Image(
                            painter = painterResource(R.drawable.app_icon),
                            contentDescription = "AniMeow",
                            modifier = Modifier.size(78.dp).clip(MaterialTheme.shapes.extraLarge),
                        )
                        Text("追番喵 AniMeow", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                        Text(
                            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text("本地优先、可自由塑造的追番资料库", textAlign = TextAlign.Center)
                    }
                }
            }
            item { SectionLabel("软件与存储") }
            item {
                AboutActionCard(
                    icon = Icons.Outlined.SystemUpdate,
                    title = if (updateState.isChecking) "正在检查更新" else "检查更新",
                    subtitle = updateState.lastCheckedAt?.let { "上次检查：${it.replace('T', ' ').take(16)}" }
                        ?: "优先应用内下载安装新版本，支持从服务器更新",
                    actionLabel = if (updateState.isChecking) "检查中" else "检查",
                    enabled = !updateState.isChecking,
                    onClick = onCheckUpdate,
                )
            }
            item {
                AboutActionCard(
                    icon = Icons.Outlined.Groups,
                    title = "官方 QQ 交流群",
                    subtitle = "QQ 群号：$QQ_GROUP（最新安装包、反馈与交流）",
                    actionLabel = "复制群号",
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText("AniMeow 用户交流群", QQ_GROUP)),
                            )
                            snackbar.showSnackbar("QQ 群号 $QQ_GROUP 已复制")
                        }
                    },
                )
            }
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text("启动时检查更新", fontWeight = FontWeight.SemiBold)
                            Text("关闭后仍可手动检查", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = updateState.autoCheckUpdates, onCheckedChange = onAutoCheckChanged)
                    }
                    if (updateState.ignoredUpdateVersion.isNotBlank()) {
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(
                                role = Role.Button,
                                onClickLabel = "恢复 ${updateState.ignoredUpdateVersion} 的更新提示",
                                onClick = onClearIgnoredVersion,
                            ).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("已忽略 ${updateState.ignoredUpdateVersion}", modifier = Modifier.weight(1f))
                            Text("恢复提示", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            item {
                AboutActionCard(
                    icon = Icons.Outlined.CleaningServices,
                    title = "缓存管理",
                    subtitle = "按类别统计与清理，不触碰数据库和本地封面",
                    actionLabel = "管理",
                    onClick = onCacheRequested,
                )
            }
            item {
                var showSwapDialog by remember { mutableStateOf(false) }
                AboutActionCard(
                    icon = Icons.Outlined.SwapHoriz,
                    title = "批量互换简介与评价",
                    subtitle = "将所有作品的「番剧详情」与「我的评价」内容对调",
                    actionLabel = "执行",
                    onClick = { showSwapDialog = true },
                )
                if (showSwapDialog) {
                    AlertDialog(
                        onDismissRequest = { showSwapDialog = false },
                        title = { Text("批量互换简介与评价") },
                        text = { Text("此操作将对资料库中所有作品执行：将「番剧详情」内容移至「我的评价」，将「我的评价」内容移至「番剧详情」。此操作不可撤销，确定继续？") },
                        confirmButton = {
                            FilledTonalButton(onClick = {
                                showSwapDialog = false
                                scope.launch {
                                    val app = context.applicationContext as com.animeow.app.AniMeowApplication
                                    val count = app.libraryRepository.swapSynopsisAndReviewBatchForAll()
                                    snackbar.showSnackbar("已互换 $count 部作品的简介与评价")
                                }
                            }) { Text("确认互换") }
                        },
                        dismissButton = { TextButton(onClick = { showSwapDialog = false }) { Text("取消") } },
                    )
                }
            }
            item {
                AboutActionCard(
                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                    title = "帮助与常见问题",
                    subtitle = "可搜索的 Q1–Q10 使用指南、排障和数据安全说明",
                    actionLabel = "查看",
                    onClick = onHelpRequested,
                )
            }
            item {
                AboutActionCard(Icons.Outlined.History, "更新日志", "查看原生重写与历史版本的重要变化", "查看", onClick = onChangelogRequested)
            }
            item {
                AboutActionCard(Icons.Outlined.Gavel, "免责声明", "数据、网络服务、第三方内容与版权说明", "阅读", onClick = onDisclaimerRequested)
            }
            item { SectionLabel("开源、社区与支持") }
            item {
                AboutActionCard(Icons.Outlined.Favorite, "鸣谢与支持", "贡献者、开源项目、交流群与反馈渠道", "查看", onClick = onCreditsRequested)
            }
            item {
                AboutActionCard(Icons.Outlined.Code, "GitHub 源码", GITHUB_REPOSITORY, "打开") { open(GITHUB_REPOSITORY) }
            }
            item {
                AboutActionCard(Icons.Outlined.Code, "Gitee 镜像", GITEE_REPOSITORY, "打开") { open(GITEE_REPOSITORY) }
            }
            item {
                AboutActionCard(Icons.Outlined.Tv, "关注 Bilibili", "项目动态与开发记录", "打开") { open(BILIBILI_SPACE) }
            }
            item {
                AboutActionCard(Icons.Outlined.Groups, "用户交流群", "QQ群：$QQ_GROUP", "复制") {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("AniMeow 用户交流群", QQ_GROUP)),
                        )
                        snackbar.showSnackbar("群号已复制")
                    }
                }
            }
            item {
                Text(
                    "Made with ♥ for every anime fan",
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpCenterScreen(
    onBack: () -> Unit,
    onDiagnosticsRequested: () -> Unit,
    onCacheRequested: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    val normalized = query.trim().lowercase(java.util.Locale.ROOT)
    val articles = if (normalized.isBlank()) {
        HELP_ARTICLES
    } else {
        HELP_ARTICLES.filter { article ->
            listOf(article.id, article.question, article.answer, article.keywords)
                .any { it.lowercase(java.util.Locale.ROOT).contains(normalized) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("帮助与常见问题") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
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
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("先搜索，再展开", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "内容已按安卓原生版现状更新；所有核心资料默认只保存在本机。",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotBlank()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Outlined.Close, contentDescription = "清空搜索")
                            }
                        }
                    } else {
                        null
                    },
                    placeholder = { Text("搜索闪退、封面、提醒、备份或诊断") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                )
            }
            if (articles.isEmpty()) {
                item {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "没有匹配的问题。可以尝试“封面”“恢复”“通知”或“日志”。",
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(articles, key = HelpArticle::id) { article ->
                    val expanded = expandedId == article.id
                    val visibilityDuration = motionDurationMillis(220)
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animatedPressClick(
                                role = Role.Button,
                                onClickLabel = if (expanded) "收起${article.question}" else "展开${article.question}",
                                onClick = { expandedId = if (expanded) null else article.id },
                            )
                            .motionAnimateContentSize(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(
                                    modifier = Modifier.size(38.dp).clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(article.id, color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Black)
                                }
                                Text(article.question, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (expanded) "收起" else "展开",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            AnimatedVisibility(
                                visible = expanded,
                                enter = fadeIn(tween(visibilityDuration, easing = AniMeowEnterEasing)) +
                                    expandVertically(
                                        animationSpec = tween(visibilityDuration, easing = AniMeowEnterEasing),
                                        expandFrom = Alignment.Top,
                                    ),
                                exit = fadeOut(tween(visibilityDuration, easing = AniMeowExitEasing)) +
                                    shrinkVertically(
                                        animationSpec = tween(visibilityDuration, easing = AniMeowExitEasing),
                                        shrinkTowards = Alignment.Top,
                                    ),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    HorizontalDivider()
                                    Text(
                                        article.answer,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = MaterialTheme.typography.bodyLarge.lineHeight,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item { SectionLabel("快速排障") }
            item {
                AboutActionCard(
                    icon = Icons.Outlined.ErrorOutline,
                    title = "诊断与操作轨迹",
                    subtitle = "查看崩溃恢复、错误堆栈和可脱敏复制的本地日志",
                    actionLabel = "打开",
                    onClick = onDiagnosticsRequested,
                )
            }
            item {
                AboutActionCard(
                    icon = Icons.Outlined.CleaningServices,
                    title = "缓存管理",
                    subtitle = "只清理可重建缓存，不会删除资料库和本地封面",
                    actionLabel = "打开",
                    onClick = onCacheRequested,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(value: String) {
    Text(
        value,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun AboutActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    actionLabel: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .animatedPressClick(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = "$actionLabel$title",
                onClick = onClick,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(actionLabel, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, fontWeight = FontWeight.SemiBold)
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheManagementScreen(
    onBack: () -> Unit,
    viewModel: CacheViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.categories) {
        if (selected.isEmpty()) selected = state.categories.filter { it.sizeBytes > 0 }.mapTo(linkedSetOf(), CacheCategory::id)
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("缓存管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.isClearing) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "重新统计")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.isScanning || state.isClearing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StorageMetric("可清理缓存", formatBytes(state.totalCacheBytes), Icons.Outlined.Cached, Modifier.weight(1f))
                        StorageMetric("资料与封面", formatBytes(state.persistentDataBytes), Icons.Outlined.Storage, Modifier.weight(1f))
                    }
                }
                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Text(
                            "资料与封面属于用户数据，仅用于容量参考；下方清理永远不会删除它们或自动恢复点。",
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                items(state.categories, key = CacheCategory::id) { category ->
                    CacheCategoryCard(
                        category = category,
                        selected = category.id in selected,
                        enabled = !state.isClearing && category.sizeBytes > 0,
                        onSelectedChanged = { checked ->
                            selected = if (checked) selected + category.id else selected - category.id
                        },
                    )
                }
                if (state.categories.isEmpty() && !state.isScanning) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                            Text("当前没有可清理缓存", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            FilledTonalButton(
                onClick = { confirmClear = true },
                enabled = selected.isNotEmpty() && !state.isClearing,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("清理所选 ${formatBytes(state.categories.filter { it.id in selected }.sumOf(CacheCategory::sizeBytes))}")
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清理所选缓存？") },
            text = { Text("图片和网络内容下次使用时会重新加载；正在执行的导入或云同步可能暂时占用部分文件。") },
            confirmButton = {
                FilledTonalButton(
                    onClick = {
                        confirmClear = false
                        viewModel.clear(selected)
                        selected = emptySet()
                    },
                ) { Text("立即清理") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun StorageMetric(label: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    ElevatedCard(modifier = modifier) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CacheCategoryCard(
    category: CacheCategory,
    selected: Boolean,
    enabled: Boolean,
    onSelectedChanged: (Boolean) -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().toggleable(
            value = selected,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = onSelectedChanged,
        ),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(category.label, fontWeight = FontWeight.SemiBold)
                Text(category.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(formatBytes(category.sizeBytes), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    var expandedVersion by rememberSaveable { mutableStateOf<String?>(null) }
    SimpleTopBarPage("更新日志", onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(CHANGELOG, key = ChangelogEntry::version) { entry ->
                val expanded = expandedVersion == entry.version
                ElevatedCard(modifier = Modifier.fillMaxWidth().motionAnimateContentSize()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.version, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                            Text(entry.date, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        }
                        entry.items.take(if (expanded) entry.items.size else 3).forEach {
                            Text("• $it", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (entry.items.size > 3) {
                            TextButton(
                                onClick = { expandedVersion = if (expanded) null else entry.version },
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text(if (expanded) "收起" else "展开全部 ${entry.items.size} 项")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisclaimerScreen(onBack: () -> Unit) {
    SimpleTopBarPage("免责声明", onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(DISCLAIMER_SECTIONS, key = LegalSection::title) { section ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(section.title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(section.body, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = MaterialTheme.typography.bodyLarge.lineHeight)
                    }
                }
            }
            item {
                Text(
                    "继续使用即表示你理解上述说明。建议定期导出完整备份。",
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsSupportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    SimpleTopBarPage("鸣谢与支持", onBack, snackbar) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { CreditCard("特别鸣谢", Icons.Outlined.Favorite, listOf("陈云" to "慷慨赞助与提供建议")) }
            item {
                CreditCard(
                    "建议贡献者",
                    Icons.Outlined.Info,
                    listOf("凛", "情迁ᵇˡᵘᵉ", "玥然", "杳音讯", "乔木店长", "了不起的碳基生物", "全体群友")
                        .map { it to "提供宝贵建议与测试反馈" },
                )
            }
            item {
                CreditCard(
                    "开放生态",
                    Icons.Outlined.Code,
                    listOf(
                        "Kotlin / Jetpack Compose" to "原生客户端与现代界面",
                        "Room / WorkManager / DataStore" to "数据、后台任务与配置基础设施",
                        "Coil" to "图片加载与缓存",
                        "Bangumi / AniList / trace.moe" to "开放元数据与以图搜番服务",
                    ),
                )
            }
            item {
                AboutActionCard(Icons.Outlined.Groups, "加入交流群", "QQ群：$QQ_GROUP", "复制") {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("AniMeow 用户交流群", QQ_GROUP)),
                        )
                        snackbar.showSnackbar("群号已复制")
                    }
                }
            }
            item {
                AboutActionCard(Icons.Outlined.Tv, "关注 Bilibili", "查看开发记录与项目动态", "打开") {
                    if (!openExternal(context, BILIBILI_SPACE)) scope.launch { snackbar.showSnackbar("无法打开链接") }
                }
            }
            item {
                AboutActionCard(Icons.Outlined.Code, "参与开源贡献", "Issue、功能建议与 Pull Request 都很欢迎", "打开") {
                    if (!openExternal(context, GITHUB_REPOSITORY)) scope.launch { snackbar.showSnackbar("无法打开链接") }
                }
            }
        }
    }
}

@Composable
private fun CreditCard(title: String, icon: ImageVector, items: List<Pair<String, String>>) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, modifier = Modifier.padding(start = 10.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
            items.forEach { (name, description) ->
                ListItem(
                    headlineContent = { Text(name, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(description) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleTopBarPage(
    title: String,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
                },
            )
        },
        snackbarHost = { if (snackbarHostState != null) SnackbarHost(snackbarHostState) },
        content = content,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppUpdateDialog(
    dialog: UpdateDialogState?,
    onDownload: (AppUpdateDownloadOption) -> Unit,
    onInstall: (Long) -> Unit,
    onIgnore: () -> Unit,
    onDismiss: () -> Unit,
    onCancelDownload: () -> Unit = {},
) {
    val context = LocalContext.current
    when (dialog) {
        null -> Unit
        is UpdateDialogState.Available -> AlertDialog(
            onDismissRequest = { if (!dialog.info.isForceUpdate) onDismiss() },
            icon = { Icon(Icons.Outlined.CloudDownload, contentDescription = null) },
            title = { Text("发现新版本 ${dialog.info.versionName}") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(dialog.info.updateLog)
                    if (dialog.info.downloadCount > 0) {
                        Text("已有 ${dialog.info.downloadCount} 次下载", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
                            .padding(8.dp),
                    ) {
                        Text(
                            "官方 QQ 交流群：$QQ_GROUP (软件内直接下载更新，失败再去浏览器)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    if (dialog.info.isForceUpdate) {
                        Text("这是必要更新，建议完成安装后继续使用。", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                    when {
                        dialog.info.downloads.isEmpty() -> Text(
                            "服务器暂未提供安装包直链，可点击下方按钮前往 GitHub 或加入 QQ 群获取最新包。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        dialog.info.downloads.size > 1 -> {
                            Text("选择应用内下载线路", fontWeight = FontWeight.SemiBold)
                            dialog.info.downloads.forEach { option ->
                                OutlinedButton(
                                    onClick = { onDownload(option) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("App内下载：${option.name}")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                when (dialog.info.downloads.size) {
                    0 -> FilledTonalButton(onClick = { openExternal(context, GITHUB_RELEASES) }) {
                        Text("前往浏览器下载")
                    }
                    1 -> FilledTonalButton(onClick = { onDownload(dialog.info.downloads.single()) }) {
                        Text("应用内直接下载更新")
                    }
                }
            },
            dismissButton = if (dialog.info.isForceUpdate) null else {
                {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = onIgnore) { Text("忽略此版本") }
                        TextButton(onClick = onDismiss) { Text("以后再说") }
                    }
                }
            },
        )
        is UpdateDialogState.Latest -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Color(0xFF3E9B55)) },
            title = { Text("已是最新版本") },
            text = { Text("当前版本：${dialog.currentVersion}\n官方 QQ 交流群：$QQ_GROUP") },
            confirmButton = { FilledTonalButton(onClick = onDismiss) { Text("完成") } },
        )
        is UpdateDialogState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("检查更新失败") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(dialog.message)
                    Text(
                        "如果应用内下载受阻，可加入官方 QQ 群 $QQ_GROUP 获取安装包，或前往浏览器 GitHub 下载。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                FilledTonalButton(onClick = { openExternal(context, GITHUB_RELEASES) }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("前往浏览器下载")
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        )
        is UpdateDialogState.DownloadQueued -> AlertDialog(
            onDismissRequest = { if (!dialog.forceUpdate) onDismiss() },
            icon = { Icon(Icons.Outlined.CloudDownload, contentDescription = null) },
            title = { Text("正在下载 AniMeow ${dialog.versionName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        dialog.progressPercent?.let { "系统下载进度：$it%。完成后会显示安装通知。" }
                            ?: "下载由 Android 系统管理，完成后会显示安装通知。",
                    )
                    if (dialog.forceUpdate) {
                        Text("必要更新下载期间请保持网络连接。", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                FilledTonalButton(onClick = { onInstall(dialog.downloadId) }) {
                    Text("检查并安装")
                }
            },
            dismissButton = if (dialog.forceUpdate) null else {
                {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onCancelDownload) { Text("取消下载") }
                        TextButton(onClick = onDismiss) { Text("后台运行") }
                    }
                }
            },
        )
        is UpdateDialogState.DownloadReady -> AlertDialog(
            onDismissRequest = { if (!dialog.forceUpdate) onDismiss() },
            icon = { Icon(Icons.Outlined.SystemUpdate, contentDescription = null) },
            title = { Text("更新已准备好") },
            text = { Text("AniMeow ${dialog.versionName} 已下载并通过基础文件检查，可以交给系统安装器继续验证。") },
            confirmButton = {
                FilledTonalButton(onClick = { onInstall(dialog.downloadId) }) { Text("安装更新") }
            },
            dismissButton = if (dialog.forceUpdate) null else {
                { TextButton(onClick = onDismiss) { Text("稍后") } }
            },
        )
    }
}

private fun openExternal(context: android.content.Context, url: String): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)

private data class ChangelogEntry(val version: String, val date: String, val items: List<String>)
private val CHANGELOG = listOf(
    ChangelogEntry(
        "2.1.0",
        "2026-09-06",
        listOf(
            "【系列内筛选与排序记忆】系列详情页新增状态筛选 Chip 行，可直接筛选「在看」等状态；排序方式、升降序、列数和状态筛选会为每个系列独立记住，退出再进入不丢失。",
            "【日历追番排期导入】日历新增「追番排期」入口，可从 Bangumi 拉取当季新番放送表，按星期筛选并一键导入到日历；支持搜索、批量勾选，具体时刻可手动补充。",
            "【状态气泡底色自定义】封面状态标签新增三种底色模式：跟随主题、跟随状态色、自定义颜色；自定义模式下提供预设色板和颜色选择器，支持实时预览。",
            "【观看时间线】番剧详情页新增简洁的时间线视图，显示开追日期和看完日期，自动计算追番旅程天数；撤销打卡时正确回退日期，避免留下错误记录。",
            "【悬浮胶囊紧凑布局】底部导航栏改为专用紧凑布局，新增高度滑块（48-80dp）和标签显示模式（始终显示或仅选中显示）；首页底部预留缩小，「最近添加」改为可选快捷区，默认减少重复展示。",
            "【问题修复】修复系列多选「全选」误选已筛除作品、首页底部多余留白等问题，提升使用体验。",
        ),
    ),
    ChangelogEntry(
        "2.0.7",
        "2026-08-30",
        listOf(
            "【首页时间线布局】新增「年月时间线」首页布局，按时间自动按年月分组（类似手机图库）；分组依据可独立选择：跟随排序、开播日期、完结日期、加入时间、开始观看时间；无日期归入「未标注时间」。",
            "【评分底色自定义】评分徽章底色新增「自定义」选项，支持 ARGB 四通道自由调节，可实时预览颜色效果。",
            "【顶部间距可调】首页顶部间距新增三档：紧凑、适中、宽松，默认紧凑，信息密度更高。",
            "【导航栏更灵活】底部导航可见目的地最低保留数从 3 个调整为 2 个（追番 + 我的固定保留），可隐藏发现与日历。",
            "【导航预览位置优化】导航实时预览移至「导航与日历」设置下方，预览与设置对应更直观。",
            "【首页定制面板增强】首页右上角定制面板新增实时预览；重新打开面板时保留上次滚动位置，不会回到顶部。",
            "【返回退出可配置】新增返回退出行为设置：双击返回退出（默认）、弹窗确认退出、直接退出，可在「我的 - 其他」中切换。",
            "【页面转场统一】社区、统计等界面与主页之间的滑动转场动画已统一为左右平滑切换，方向基于实际可见导航顺序计算，视觉体验更一致。",
            "【顶部间距修复】修复顶部间距和栏目间距设置不生效的问题（restore 遗漏写入）；紧凑模式进一步收紧，信息密度更高。",
            "【返回退出入口优化】返回退出方式设置移至「我的 - 其他」section，不再需要进入外观定制面板寻找。",
            "【角色返回优化】从番剧详情点击角色进入角色页时，返回键与返回箭头现在直接退出回到番剧详情，不再先折叠到角色中心列表；从「我的」或全局搜索打开角色中心时行为不变。",
            "【日历界面优化】日历月视图星期表头（一二三四五六日）改为居中对齐，与下方日期格子正对，视觉更整齐。",
            "【「我的」界面重构】个人信息头部展示首字母头像、用户名、云端同步状态与追番统计；快捷功能网格整合提醒、统计、定制与备份入口；原设置项迁移至独立设置页面，设置入口与 QQ 群横幅移至页面底部。",
            "【设置搜索统一】移除「我的」内置搜索，统一使用全局搜索；补充黑夜模式、导航顺序、返回退出方式等 11 项缺失的设置搜索入口。",
            "【追番提醒卡片统一】提醒管理页面的服务卡片、作品卡片与空状态卡片统一为 Surface 样式，视觉风格更一致。",
            "【外观定制页面滚动持久化】外观定制全页面版本新增滚动位置持久化，离开页面再回来时自动恢复上次浏览位置。",
            "【角色布局简化】角色中心移除「自适应双栏」布局选项（手机上与列表表现相同），简化为列表与海报墙两种选择；已有该设置的用户自动切换为列表。",
            "【关系星图增强】角色关系星图从纯文字圆形升级为头像展示，点击关系节点可直接跳转到该角色详情页。",
            "【社区统计入口优化】社区、统计页面从「我的」进入时确保底部导航栏可用；统计页面标题改为「统计」并添加全局搜索入口。",
            "【问题修复】修复若干界面细节问题，提升使用体验。",
        ),
    ),
    ChangelogEntry(
        "2.0.6",
        "2026-08-21",
        listOf(
            "【番剧详情优化】详情页把作品简介与个人评价明确分开：简介改为「番剧详情」展示官方介绍，新增「我的评价」栏始终展示你的观后感想；同步数据只写入作品简介，不再占用个人评价。",
            "【悬浮导航栏】底部导航栏升级为悬浮胶囊样式，可在「定制」中自由切换经典底栏与悬浮样式。",
            "【标签管理增强】支持屏蔽不需要的标签；可将含义相同但写法不同的标签合并为一处；新增简繁体自动归一，轻百与轻百合这类近义标签可一键查重并合并。",
            "【角色直达】番剧详情点击角色头像即可直接查看角色详情与关系星图，无需再进入角色中心查找。",
            "【系列创建】在番剧编辑界面可直接新建系列并归纳，无需前往系列书架。",
            "【问题修复】修复若干界面细节问题，提升使用体验。",
        ),
    ),
    ChangelogEntry(
        "2.0.5",
        "2026-08-15",
        listOf(
            "【主页系列文件夹】归组逻辑扩展至所有视图；推出全新叠层封面与“系列 · N 部”角标。",
            "【全局搜索强化】在搜索结果的作品卡片下方直接列出同系列成员与直达链接。",
            "【日历视图定制】日历定制面板新增“默认打开视图”设定，支持实时同步月视图/周视图/议程。",
            "【封面星期】修复“封面显示更新星期”开启后多数作品不显示星期：未设置提醒时改为按首播日期推导更新日。",
            "【社区管理入口】管理员与开发者的社区界面回归普通样式，仅保留一个「社区管理」入口；待复审、统计、审核记录、用户管理与敏感词管理统一迁入独立管理页。",
            "【开发者工具】用户管理支持按用户名搜索与按角色筛选；敏感词支持一键导出与批量导入；暂时隐藏随机群码入口。",
            "【社区搜索】支持按帖子内容或用户名搜索，并可按时序、点赞、评论或精华筛选排序；点击用户可直接查看其历史帖子。",
            "【个人资料】「编辑个人资料」并入「云账号与设备备份」，昵称、头像、个签与统计名片统一管理。",
            "【问题修复】修复了若干 bug 与体验细节问题。",
        ),
    ),
    ChangelogEntry(
        "2.0.4",
        "2026-08-12",
        listOf(
            "【云端备份】修复大文件备份时 maximum call stack size exceeded 与备份哈希校验失败的问题。",
            "【图片权限】修复社区发帖与编辑封面时无法读取所选图片的问题，添加媒体访问权限并升级图片选择器。",
            "【重复作品】修复添加作品时来源 ID 重复导致 SQLite 约束报错，现在允许强制保存并清除冲突的来源 ID。",
            "【界面优化】移除「我的」页面冗余蓝色文字按钮；修复版本选择弹窗启动时一闪而过。",
            "【应用更新】修复检查更新后下载弹窗无法关闭、反复弹出的问题；新增取消下载按钮与后台运行选项。",
        ),
    ),
    ChangelogEntry(
        "2.0.3",
        "2026-08-12",
        listOf(
            "【社区完善】修复社区发帖图片选择超限、输入框与键盘间隙、预设图片投票入口等问题，社区功能初步完善。",
            "【问题修复】修复角色图片裁切显示头部、番剧详情角色对齐、版本号位置等多项界面 Bug。",
        ),
    ),
    ChangelogEntry(
        "2.0.2",
        "2026-08-12",
        listOf(
            "【追番喵茶话会】新增仅登录用户可查看和发言的官方群聊；普通用户只能通过默认加入或随机群码加入，暂不开放创建群聊。",
            "【图文交流】支持文字与最多 4 张图片；客户端会先缩放压缩，服务端再次校验格式、单图和总大小，社区图片必须携带登录凭据才能读取。",
            "【个人统计名片】可设置昵称、头像、个签和名片隐私；统计卡默认关闭，开启后可逐项选择总收录、动画、书籍、已看集数、估算时长、平均评分或连续打卡，连续打卡默认不勾选。",
            "【举报即隔离】举报后立即暂时隐藏目标帖子、作者近 7 天帖子及审核期间的新帖，进入管理员人工复审；管理员可恢复全部，或仅移除被举报内容并恢复其余帖子。",
            "【消息与兼容】可关闭群消息自动接收但仍可主动查看和发言；原角色群组社区完整保留为独立分享工具，相关深链接继续进入安全预览。",
        ),
    ),
    ChangelogEntry(
        "2.0.1",
        "2026-08-12",
        listOf(
            "【新旧前端自由选择】完整保留新版体验，并加入可随时切换的经典版前端与对应交互入口。",
            "【触底抖动修复】修复日历定制、待补日期、发现筛选、追番定制等长列表滚动到底部时上下抽搐的问题。",
            "【预测性返回手势】新增可持久化开关；开启后主导航返回会跟随边缘手势预览，关闭后继续使用传统返回行为。",
            "【设置界面整理】页面专属定制回到追番、发现和日历页面；“常用设置”与“帮助与维护”支持展开收起，经典版“我的”按旧版结构重新分组。",
            "【一机一账号注册】新注册不再需要邀请码，同一设备只可创建一个账号；账号仍可在多台设备登录，旧邀请码账号不受影响。",
            "【开放反馈池】所有用户均可浏览公开反馈，登录云账号后可提交问题、建议和功能想法。",
            "【统计页自由定制】保留经典卡片布局，新增档案仪表盘；总收录、动画、书籍、已看集数、估算时长、连续打卡和平均评分均可独立显隐，连续打卡默认不展示。",
            "【问题修复】修复若干界面入口、滚动边界和交互一致性问题。",
        ),
    ),
    ChangelogEntry(
        "2.0.0",
        "2026-08",
        listOf(
            "完成 Android 原生重写，保留原版数据兼容，并带来更现代、可高度自定义的使用体验。",
        ),
    ),
    ChangelogEntry(
        "v1.3.9",
        "2026-08-10",
        listOf(
            "【全沉浸无缝标题栏】Windows 桌面端标题栏彻底与软件背景无缝融为一体！去除了刺眼的顶部白框，右上角无痕融入极简控制按钮，颜值大幅提升",
            "【独立书架与纯动画隔离】书架下的主列表默认专用于呈现“动画作品”，避免书籍与动画在底部混排。主页更可一键切换「仅动画 | 全部作品」",
            "【消灭强迫症刺眼红点】彻底消除了首页列数按钮上的刺眼红点，并将筛选角标替换为优雅柔和的主题色微徽章，告别“每次打开都以为有新消息”的焦虑",
            "【零层级一键黑夜模式】在“我的”页面最上方新增了「黑夜模式」直观 Switch 开关，无需进入二级菜单，随时顺手一键切换深色护眼主题",
            "【二次元主题染彩与银灰调和】选择黑色主题时，浅色模式自适应为极具质感的高级极客银灰底；选择彩色主题时，全应用背景会自动散发主题色彩微晕烘托",
            "【设置全局搜索】“我的”页面新增设置搜索框，输入“黑夜”、“封面”、“备份”等关键词即可秒找设置项并一键直达，配合放缩展开动画，丝滑又省心",
            "【系列作品列数与自由排序】系列番剧内部新增独立的列数调节（2–5 列）与多维排序，可与首页列数独立，满足追大 IP 系列作品的高密度查看需求",
            "【看番风格报告正式发布】“AI 看番风格分析”正式发布并集成至“数据统计”，轻松解锁你的二次元专属偏好解读与生成卡片",
            "【筛选与排序记忆】应用会自动妥善保存你选好的首页作品分类、排序方式及升降序，重新打开后无需重新设定",
            "【全终端响应式与流畅度提升】针对桌面大屏与手机小屏进一步优化响应式双列布局与卡片滑动体验",
        ),
    ),
    ChangelogEntry(
        "v1.3.8",
        "2026-08-08",
        listOf(
            "【夜间模式】新增深色主题切换，在「展示定制」中可一键开启夜间模式，护眼追番",
            "【封面更新日】支持在封面上标注番剧每周更新日，在「展示定制」中可自由开关",
            "【首页快捷设置】支持在首页工具栏直接调整宫格列数（2–5 列）",
            "【可选诊断日志】新增默认关闭的操作日志开关，可记录关键操作与错误堆栈，并支持一键复制或清空",
            "【发现页修复】修复筛选年份月份后部分番剧因缺少具体日期而被错误遗漏的问题，筛选结果更完整",
            "【问题修复】修复了若干 bug",
        ),
    ),
    ChangelogEntry(
        "v1.3.7",
        "2026-07-22",
        listOf(
            "【趣味评级】新增独立番剧梯队榜，支持 S / A / B / C / D 拖拽评级、自定义中英文档位名称，并可生成完整榜单图片分享",
            "【首页数量修复】“全部”旁的作品数改为显示完整数量，不再受每页 250 条的加载上限影响",
            "【角色删除修复】番剧详情中可选择“仅从本作移除”或“永久删除”，角色管理详情页也新增永久删除入口",
            "【ABCD 评级】作品评分新增 A / B / C / D 评级方式，可与原有 0–10 分模式自由切换，并适配首页、列表、详情和排序",
            "【状态颜色自定义】自定义状态管理新增 HSV 拾色器与 RGB / HEX 输入，可使用任意颜色",
        ),
    ),
    ChangelogEntry(
        "v1.3.6",
        "2026-07-05",
        listOf(
            "【首页展示定制】新增首页与封面显示方式自定义，保留现有内容的同时可切换更适合自己的展示样式",
            "【推荐宫格优化】推荐宫格不再强制固定为评分标题条，封面角标样式会跟随用户设置",
            "【以图搜番增强】新增独立入口与专属界面，优化 Trace.moe 识别、结果去重、低置信提示和中文检索匹配",
            "【结果信息补充】以图搜番结果补充相似度、集数、时间点、标题候选等基础信息，方便判断是否为目标番剧",
            "【卡片流交互】精致卡片流左右滑动后可撤回，降低误触成本",
            "【封面角标】新增角标大小调节，让不同首页样式下的信息层级更自然",
            "【移动端适配】优化角色群组弹窗在手机上的布局，修复文本被操作按钮挤压成竖排的问题",
        ),
    ),
    ChangelogEntry(
        "v1.3.5",
        "2026-06-23",
        listOf(
            "【全新角色管理】新增角色管理界面，支持角色数据的增删改查",
            "【角色查重功能】新增角色查重功能，检测重复角色并提示是否删除",
            "【番剧查重优化】优化番剧查重算法，更精准地检测重复收录",
            "【问题修复】修复了部分已知 bug，提升应用稳定性",
        ),
    ),
    ChangelogEntry(
        "v1.3.4",
        "2026-06-12",
        listOf(
            "【测试功能】新增 AI 看番风格分析：登录账号后每天可生成一次专属看番风格报告，用更可爱的方式看看自己的追番口味",
            "【自由分析】也可以直接复制提示词，拿去自己喜欢的 AI 工具里分析",
            "【云端资源发现】月份筛选更准确了：1 / 4 / 7 / 10 月会按新番档期展示对应季度的作品",
            "【首页封面】新增“蓝色信息条”封面样式，更新状态、观看进度和标题会更醒目",
            "【观看进度】把番剧状态改为“看完”时，会自动把观看进度补到总集数，不用再手动改成 x/x",
            "【体验优化】社交与反馈等文案更清爽，阅读起来更轻松",
        ),
    ),
    ChangelogEntry(
        "v1.3.3",
        "2026-06-04",
        listOf(
            "【Bangumi 导入】新增公开收藏导入，输入 Bangumi 用户名或 UID 即可批量迁移番剧记录",
            "【账号与云同步】新增可选云账号，支持每 5 分钟进行一次云存储，并可从云端备份一键恢复到当前设备",
            "【跨设备迁移】云端仅保留最新 3 次存储，换设备或重装后可通过账号快速取回追番数据",
            "【账号安全】注册支持确认密码与密码显隐切换，请妥善保存邀请码，忘记密码时可通过账号 + 邀请码重置",
            "【云端资源发现】年份筛选补齐 2026，并改为按当前年份自动生成，后续新年份无需再手动维护",
            "【资料库命名】入口、搜索页和同步封面提示文案统一使用“资料库”命名",
            "【问题修复】修复少数用户默认观看状态显示乱码的问题，已存在的异常状态会在启动时自动恢复为正常中文",
        ),
    ),
    ChangelogEntry(
        "v1.3.2",
        "2026-05-29",
        listOf(
            "【个性化】应用图标自由切换（仅 Android）",
            "在「我的 → 展示定制 → 应用图标」可在默认图标 + 8 款备选图标中挑选，应用内的头像图标会立即更新",
            "桌面启动器图标通过原生通道同步切换，几秒内即可在桌面看到效果，无需划掉应用",
            "【日历】日历界面背景和主题色优化，自适应跟随系统本命色设置，使视觉风格整体高度统一",
            "【国内同步】新增 Bangumi 国内可用同步方案，搜索、发现页和封面可在官方接口不可用时切换到代理服务；由于代理存在额度限制，若偶尔触发限制还请见谅",
        ),
    ),
    ChangelogEntry(
        "v1.3.1",
        "2026-05-13",
        listOf(
            "【番剧详情页】查看 / 编辑拆分 + 4 套预设布局",
            "点击番剧默认进入只读详情查看页，视觉清爽不再被表单控件干扰；点右上角\"编辑\"按钮才进表单",
            "4 种预设布局可选：经典卡片式（默认）/ 杂志 Hero（大封面 parallax） / 数据看板（宽屏双列） / 极简模式（仅封面 + 标题 + 评分）",
            "模块化定制：在「设置 → 展示定制 → 详情页模块管理」可拖拽重排、显隐 6 个信息模块（观看进度 / 标签 / 评价 / 详细信息 / 追番提醒 / 同系列作品）",
            "所有信息模块在数据为空时自动隐藏，不会留下空白卡片",
            "【首页封面定制】",
            "新增\"封面角标样式\"4 选 1：悬浮显示（默认）/ 贴边显示（紧贴封面四角）/ 底部信息条（合并状态色块 + 评分）/ 角标极简（小圆点 + 纯数字）",
            "新增\"评分图标\"12 种预设：🐾 猫爪（默认替换原来的\"狗爪\"）/ 😺 猫脸 / ⭐ 星星 / 🌟 亮闪星 / ❤️ 爱心 / 🔥 火焰 / 💎 钻石 / ✨ 闪光 / 👍 点赞 / 🍀 四叶草 / 🌸 樱花 / 🏆 奖杯 / 不显示（光秃秃只看数字）",
            "首页卡片和详情页头部都会清晰标识作品类型（动画 / 书籍），不用进详情才知道是哪种",
            "【启动封面增强】",
            "右下角新增\"跳过\"按钮，可立即进入应用",
            "可自定义展示时长（0.5 ~ 5.0 秒，**默认从 2 秒缩短到 1 秒**）",
            "裁剪现在**跨平台支持**：安卓 / iOS / Windows / 桌面端都能用同一套纯 Flutter 裁剪 UI，告别\"部分安卓机型选取封面报错\"的老问题",
            "裁剪页显示完整原图，缩放 + 拖动选取保留区域；输出 PNG 写入临时目录后再复制到永久路径",
            "【设置页瘦身】",
            "\"首页布局\" / \"封面角标样式\" / \"评分图标\" / \"详情页布局\" 改为 tile + 底部弹层选择器，节省约 2/3 纵向空间，更符合安卓拇指可达交互",
            "\"首页样式\"和\"详情页定制\"两个独立分区，结构更清晰",
            "【问题修复】",
            "修复：番剧详情页评分数字看不见（全局 inputDecorationTheme 的 filled + 32px 横向 padding 把 44px 宽度容器里的\"8.5\"挤没了）",
            "修复：点击编辑按钮报\"No Material widget found\"（封面 Hero 飞行时 InkWell 子树被剪贴到 Overlay，脱离了 Scaffold 的 Material 链路）",
            "修复：详情页模块管理页面的拖动手柄与开关按钮重合（关掉了 ReorderableListView 默认的 trailing 自动手柄）",
            "修复：紧凑横滑选择器在某些设备下底部溢出 8px（已改为 tile + 弹层架构，从根本上避免）",
        ),
    ),
    ChangelogEntry(
        "v1.3.0",
        "2026-05-11",
        listOf(
            "【里程碑】全新 Material You / Expressive 视觉系统",
            "彻底重做：升级到 M3 Expressive 设计语言，统一圆角刻度（12/20/28/32）、间距 token 和柔和阴影；告别此前的 Neumorphic 软 UI，整体观感更现代、更轻盈",
            "主题色革新：自定义颜色强制还原为你选择的色相（不再被 M3 算法锁到 tone 40 的\"暗黄/灰紫\"）；onPrimary 按种子色明度自动选黑/白，亮色种子也能正常使用",
            "自定义 RGB 拾色器：在「我的 → 主题色」最后一格点\"自定义\"可调用 HSV 色环、RGB 输入框、HEX 文本框，支持任意色值",
            "【里程碑】4 种首页布局自由切换",
            "智能聚合 (Bento)：默认布局，聚合\"正在追 / 最近添加 / 全部\"模块，Hero 区段响应式宽度适配窄屏",
            "精致卡片流 (CardFeed)：Letterboxd 风格单列大卡片，左大封面 + 状态色渐变背景 + studio/年份/系列副信息 + 进度条",
            "沉浸海报墙 (PosterWall)：封面铺满列布局；长按弹出 BlurSheet 浮层快速查看详情，无需进入二级页",
            "紧凑索引 (CompactIndex)：超大库友好，48×64 缩略 + 单行 meta；按拼音排序时自动分组 + 右侧 A-Z 字母条瞬移",
            "老用户迁移：旧的\"海报式 / 卡片式\"会自动迁移为\"沉浸海报墙 / 精致卡片流\"，数据零风险",
            "【架构】4-Tab 主导航重构",
            "底部导航精简：追番 / 发现 / 日历 / 我的；从原本最多 6 个 Tab 收敛到 4 个，更符合手机拇指可达区域",
            "聚合「我的」页：新建 MyPage 收纳 统计 / 资料库 / 设置 / 主题色 / 关于 / 测试功能 / QQ 群 等入口",
            "展示定制升级：4 张布局预览卡片直观切换，海报墙列数/字号等设置在选中\"海报墙\"时才显示",
            "【手势升级】手机端原生交互",
            "滑动操作：CardFeed 与 CompactIndex 中右滑番剧 +1 集（看完会自动归纳到\"看完\"状态），左滑循环切换状态",
            "触觉反馈：下拉刷新、Tab 切换、滑动操作均附带 HapticFeedback，更有手机感",
            "Hero 封面动画：从沉浸墙 / 卡片流 / 紧凑索引点击封面进入详情页时，封面会做共享元素缩放过渡",
            "【问题修复】",
            "修复：导入备份时黑屏 + \"deactivated widget's ancestor\"报错（popUntil 误弹了 SettingsDataPage 导致 context 失效）",
            "修复：打开部分番剧详情明明没改任何字段，返回时却被询问\"是否保存\"（air_date 自动补\"周X\"在快照之后导致脏检测误报；同时清理了重复注册的 listener）",
            "清理：删除了 800+ 行旧 grid/card 渲染代码与已经无人使用的 series_widget.dart",
        ),
    ),
    ChangelogEntry(
        "v1.2.12",
        "2026-05-05",
        listOf(
            "【个性化升级】自定义启动封面",
            "新增：\"启动封面\"功能：在「设置 → 展示定制 → 启动封面」中可一键启用，并从相册选择/裁剪喜欢的图片，每次打开 APP 都会以你设置的封面与你打招呼喵 ~",
            "细节打磨：未设置图片时不会闪屏，启动体验保持原样",
            "【精细化开关】云端资源发现可独立显隐",
            "在「设置 → 通用 → 页面内入口」中可控制是否在「发现」/「资料库」页面顶部展示「云端资源发现」入口，UI 更简洁",
            "【体验优化】",
            "修复了部分已知 bug，提升整体稳定性",
        ),
    ),
    ChangelogEntry(
        "v1.2.11",
        "2026-04-17",
        listOf(
            "添加了搜索框灵动动画",
        ),
    ),
    ChangelogEntry(
        "v1.2.10",
        "2026-03-24",
        listOf(
            "【进阶搜索】云端资源发现",
            "深度筛选：云端发现页支持按“分类、格式、年份、月份”多重组合筛选，找番更精准",
            "【体验优化】",
            "修复了部分bug",
        ),
    ),
    ChangelogEntry(
        "v1.2.9",
        "2026-03-15",
        listOf(
            "新功能：追番提醒",
            "再也不怕错过更新：在番剧详情页开启“追番提醒”，设定每周提醒时间，喵喵会在更新时准时通知你",
            "修复了部分bug",
        ),
    ),
    ChangelogEntry(
        "v1.2.8",
        "2026-03-14",
        listOf(
            "【重磅优化】首页筛选大整合",
            "极简体验：将类型、状态、年份、标签筛选全部整合进统一的“筛选”面板，首页视觉更清爽，海报展示空间更充裕",
            "快捷操作：支持在首页点击已选条件摘要快速移除过滤，一键即达",
            "【发现页升级】",
            "静默刷新：优化了发现页的刷新逻辑，从详情页返回时不再重置数据或丢失滚动位置，逛番更顺心",
            "【致谢与优化】",
            "名单更新：致谢名单新增“乔木店长”、“了不起的碳基生物”等小伙伴，感谢宝贵建议",
            "细节修复：优化了部分界面的显示效果，提升应用稳定性",
        ),
    ),
    ChangelogEntry(
        "v1.2.6",
        "2026-03-10",
        listOf(
            "【体验突破】首页空间大瘦身",
            "全面翻新：彻底重构首页状态/类型/年份/标签过滤器，将其合并为紧凑的菜单，最大化海报展示空间",
            "【重磅新模块】互助资料库",
            "资料寻宝：新增“资料库”功能（可在“设置 -> 通用”中手动开启）。该功能通过汇聚大家同步的数据来提供便利，各位小伙伴多多点击“同步”会让资料库越来越好用哦！",
            "【功能升级】同步与更新",
            "极速更新：迁移至全新的服务器检查更新机制，支持多线路灵活切换，大幅提升下载成功率",
            "智能缓存：支持后台自动同步封面并新增批量同步选项，无需每次点击保存也可为资料库添砖加瓦",
            "【体验优化与修复】",
            "统计优化：优化了统计模块的展示效果，让您的追番数据更直观",
            "体验升级：修复了部分已知的界面显示 Bug 及其他问题，提升稳定性",
        ),
    ),
    ChangelogEntry(
        "v1.2.5",
        "2026-02-28",
        listOf(
            "【新功能】",
            "新增书籍记录功能：支持收录书籍并与动画分开展示",
            "系列页面支持动画与书籍分类展示",
            "首页支持直接切换卡片/海报布局",
            "【体验优化】",
            "优化排序界面，合并成对排序项，支持点击切换升降序",
            "优化了部分代码，应用运行更加稳定",
        ),
    ),
    ChangelogEntry(
        "v1.2.4",
        "2026-02-24",
        listOf(
            "【重构】设置层级：新增“展示定制”二级页面，收纳了列数调节、标题位置、字体大小及显隐开关，设置主页更清爽",
            "【优化】首页布局：微调了状态筛选行与年份/标签工具行之间的间距，视觉展示更通透",
            "【新增】检查更新功能：在设置中支持一键检查新版本及同步查看最新更新说明",
        ),
    ),
    ChangelogEntry(
        "v1.2.3",
        "2026-02-22",
        listOf(
            "【筛选体验大升级】",
            "年份多选：支持同时勾选多个年份，列表查询逻辑同步升级",
            "实时联动：年份选择、标签模式切换现在均支持点击即时刷新，无需点击“完成”确认",
            "交互优化：将“满足全部/满足任一”切换按钮移至工具行，布局更紧凑，操作更顺手",
            "触控友好：汇总区域的小标签改为“全区域点选”，点击任意位置即可移除筛选，更适合手机用户",
            "状态直选：将观看状态筛选前置到首页顶层，一键直达，减少操作步骤",
        ),
    ),
    ChangelogEntry(
        "v1.2.2",
        "2026-02-13",
        listOf(
            "【视觉与交互焕新】",
            "选择性同步：搜索与批量匹配时新增“同步选项”弹窗，支持勾选特定内容（封面、评分、集数等）进行局部更新",
            "同步预览增强：搜索确认时新增番剧海报与基础信息预览，数据对齐更直观",
            "系列内多选：支持在系列底栏内长按开启多选模式，操作逻辑与主列表完美统一",
            "系列交互修复：修复了系列面板长按开启多选后无法即时同步状态的问题",
            "编辑页美化：全面重构番剧添加/编辑页面，采用卡片式布局与流光阴影，颜值大幅提升",
            "详情页优化：取消“更多信息”折叠，所有字段一目了然，减少点击路径",
            "筛选页增强：移除标签筛选模式切换时的多余勾选图标，界面更清爽简洁",
            "【标签管理升级】",
            "独立管理入口：设置中新增“标签管理”功能，支持查看每个标签的使用频次",
            "快捷维护：支持对已有标签进行重命名（同步更新所有番剧）与批量清理",
            "新建流程优化：创建标签后自动勾选，若标签已存在则智能弹窗提示一键关联",
            "【年度筛选】",
            "新增年份筛选功能：支持按番剧“播出日期”快捷筛选，年份条目智能折叠，界面更清爽",
            "UI 视觉美化：整合了年份与标签筛选区域，采用紧凑型设计，大幅节省首页空间",
            "【多选模式增强】",
            "操作更直观：多选模式新增底部文字操作栏，功能一目了然",
            "系列交互优化：支持在多选模式下直接进入“系列”面板进行勾选与状态同步",
            "快捷进入：长按首页“系列”卡片也可直接开启全局多选模式",
            "【问题修复】",
            "修复逻辑 Bug：解决在已有年份筛选时依然无条件显示所有系列的问题",
            "完善体验：统一了年份选择弹窗的样式，实现选择后自动关闭并刷新",
            "排序重构：统一了“系列”与“番剧”的排序维度，支持精准混合排序",
        ),
    ),
    ChangelogEntry(
        "v1.2.1",
        "2026-02-02",
        listOf(
            "【重磅新功能】",
            "番剧系列：支持创建“系列文件夹”，将多季番剧收纳整理，首页展示更整洁",
            "系列管理：新增专门的管理页面，支持修改系列名称、描述及手动指定系列封面",
            "自定义状态：支持自由添加、重命名和删除观看状态（如“二刷”、“在读”等），并可自定义颜色",
            "评分系统：新增番剧评分功能 (0-10分)，支持手动打分",
            "智能同步：搜索添加番剧时自动同步评分信息",
            "【体验升级】",
            "排序优化：新增按“评分”排序功能，发现高分佳作",
            "显示优化：番剧列表现在会显示总集数（TV + SP），进度更直观",
            "【问题修复】",
            "修复图片显示：修复了自定义上传的本地图片在首页无法正常显示的 Bug",
        ),
    ),
    ChangelogEntry(
        "v1.2.0",
        "2025-01-11",
        listOf(
            "【发现新世界】",
            "全新发现页：上线 Bangumi 番剧索引，按年份、季度、类型、风格精准筛选",
            "【体验升级】",
            "智能预填：点击番剧自动搜索并预填信息，添加追番更快捷",
            "【问题修复】",
            "修复中文乱码：解决发现页部分番剧标题和简介显示乱码的问题",
        ),
    ),
    ChangelogEntry(
        "v1.1.1",
        "2025-12-28",
        listOf(
            "【体验升级】",
            "手势导航：主界面支持左右滑动切换功能，单手逛番更顺滑",
            "日历汉化：日历组件全面中文化，月份和星期显示更亲切",
        ),
    ),
    ChangelogEntry(
        "v1.1.0",
        "2025-12-19",
        listOf(
            "【颜值大进化】",
            "全新架构：引入底部导航栏，首页、日历、统计一触即达，单手操作更顺滑",
            "丝滑联动：首页搜索栏支持自动伸缩 (Sliver)，给番剧列表留出更多视野",
            "卡片式设计：告别枯燥的填表式添加，编辑页面全面卡片化，打卡进度更有仪式感",
            "个性主题：新增\"小粉红\"、\"基佬紫\"等 8 种二次元本命色切换",
            "【重磅新功能】",
            "懒人福音：新增\"批量自动匹配\"，一键刮削封面、简介和制作信息，整理片单超快！",
            "搬家神器：支持 Excel 智能导入，自定义列映射，轻松迁移旧数据",
            "数据看板：新增\"统计\"页面，饼图直观展示追番状态与标签喜好",
            "【体验优化】",
            "标签增强：新增全屏筛选面板，支持\"且/或\"逻辑切换与自动排序",
            "搜索升级：接入了 Anilist API 接口，搜索更高效！",
            "智能查重：添加番剧时自动检测标题，防止重复收录",
            "性能升级：优化封面图缓存策略，离线也能流畅查看海报墙",
        ),
    ),
    ChangelogEntry(
        "v1.0.0",
        "2025-12-16",
        listOf(
            "追番喵初次发布！",
            "支持番剧增删改查",
            "支持多标签筛选与搜索",
            "支持多种排序方式",
            "新增：数据备份与恢复功能，支持跨端同步",
            "新增：批量删除功能（长按番剧进入多选模式）",
            "新增：追番日历功能",
            "新增：Bilibili 主页跳转",
            "新增：设置页面与字体大小调节",
            "优化：全新的 App 图标\"追番喵\"",
            "优化：支持自定义海报墙列数",
            "优化：适配 Android 13+ 权限",
            "修复：解决部分设备无法保存封面的问题",
        ),
    ),
)

private data class HelpArticle(
    val id: String,
    val question: String,
    val answer: String,
    val keywords: String,
)

private val HELP_ARTICLES = listOf(
    HelpArticle(
        "Q1",
        "应用打不开或闪退怎么办？",
        "先重新打开应用并重启设备。仍有问题时进入“诊断与操作轨迹”查看最近崩溃恢复记录；也可以只清理可重建缓存。恢复备份前请先导出当前完整备份，避免覆盖仍可用的数据。",
        "闪退 崩溃 打不开 crash 恢复 缓存 备份",
    ),
    HelpArticle(
        "Q2",
        "封面加载不出来怎么办？",
        "先检查网络与 Bangumi 图片代理设置；原链接失效时可在作品编辑页重新联网匹配或选择本地封面。缓存异常可使用“缓存管理”，它不会删除资料库和本地自定义封面。",
        "封面 图片 加载 代理 网络 缓存 本地图片",
    ),
    HelpArticle(
        "Q3",
        "如何修改作品所属系列？",
        "可在作品编辑页选择或新建系列，也可进入“系列书架”集中管理成员。处理多季同名作品时，编辑器会给出可忽略的自动归纳建议；资料库多选还支持批量整理。",
        "系列 归纳 多季 批量 编辑 书架",
    ),
    HelpArticle(
        "Q4",
        "不小心删错作品，能恢复吗？",
        "可以。普通删除只会把作品移入回收站，观看记录、标签和关联关系仍会保留；在“我的 → 回收站”即可恢复。只有“永久删除”或“清空回收站”不可撤销。",
        "删除 恢复 回收站 撤销 永久删除",
    ),
    HelpArticle(
        "Q5",
        "追番提醒在哪里设置？",
        "在作品编辑页填写星期与时间即可创建每周提醒；“我的 → 追番提醒管理”可统一检查通知权限、精确闹钟能力、同步任务并发送测试通知。精确闹钟不可用时会自动降级为 WorkManager。",
        "提醒 通知 闹钟 权限 workmanager 后台",
    ),
    HelpArticle(
        "Q6",
        "评分和趣味评级有什么区别？",
        "作品评分支持 0–10 分或 A–D 等级评价，并可记录短评；Tier List 是独立的 S/A/B/C/D 趣味分档，可自定义标题、档位名称和分享图片。两套体系互不覆盖。",
        "评分 等级 A-D tier 趣味评级 短评",
    ),
    HelpArticle(
        "Q7",
        "数据能同步到云端吗？",
        "AniMeow 默认本地优先，不登录也能完整使用。配置可用后端并登录后，可上传完整备份、自动同步并在冲突时选择本机、云端或按范围合并；每次远端恢复前都会创建本机恢复点。",
        "云端 同步 登录 备份 冲突 合并 本地优先",
    ),
    HelpArticle(
        "Q8",
        "AniMeow 收费或包含广告吗？",
        "AniMeow 是免费开源软件，不含广告和内购。第三方数据源或自建服务可能有各自的使用条款与费用；使用自定义 AI 接口时，费用由对应服务商按你的账号规则收取。",
        "免费 开源 广告 内购 AI 费用",
    ),
    HelpArticle(
        "Q9",
        "如何联系开发者或反馈建议？",
        "可以加入 QQ 群 1073623448，或前往 GitHub Issues：github.com/xunlys7930/AniMeow/issues。反馈前建议先搜索更新日志和本页常见问题。",
        "联系 开发者 反馈 QQ GitHub issue 建议",
    ),
    HelpArticle(
        "Q10",
        "遇到 Bug 时如何提供诊断日志？",
        "进入“诊断与操作轨迹”，按需开启操作日志后复现问题，再使用脱敏复制或导出。日志只保存在本机，不会自动上传；发送前可预览，完成反馈后可关闭并清空。请同时说明设备型号、Android 版本和复现步骤。",
        "Bug 日志 诊断 脱敏 复现 设备 Android",
    ),
)

private data class LegalSection(val title: String, val body: String)
private val DISCLAIMER_SECTIONS = listOf(
    LegalSection("1. 软件性质", "AniMeow 是免费的个人追番资料管理项目，用于 ACG 文化交流、学习和个人记录，不提供付费内容播放服务。"),
    LegalSection("2. 本地数据与备份", "作品、评价、标签和本地封面默认保存在设备中。请定期导出完整备份；因卸载、设备损坏、系统清理或误操作造成的数据丢失，开发者无法保证恢复。"),
    LegalSection("3. 可选联网功能与隐私", "发现、元数据补全、云账号、云备份、角色组社区、反馈、更新检查和 AI 分析需要联网。仅在你主动启用相关功能时传输完成服务所需的数据；AI 分析自定义 API Key 不持久化、不写入日志或备份。"),
    LegalSection("4. 内容与版权", "应用不存储、不下载、不链接或在线播放视频正片。封面、简介和角色资料可能来自用户输入或 Bangumi、AniList、trace.moe 等公开接口，版权归各自权利人所有；如有侵权请联系处理。"),
    LegalSection("5. 第三方服务", "第三方接口和自建服务器可能调整、限流、中断或返回不准确内容。用户应自行判断并遵守对应服务条款；AniMeow 不对第三方服务的持续可用性作保证。"),
    LegalSection("6. 免责与反馈", "软件按现状提供。在法律允许范围内，开发者不对因使用或无法使用软件产生的间接损失负责。发现 Bug、安全问题或法律风险时，请通过反馈或开源仓库联系。"),
)

private const val GITHUB_REPOSITORY = "https://github.com/xunlys7930/AniMeow"
private const val GITHUB_RELEASES = "$GITHUB_REPOSITORY/releases"
private const val GITEE_REPOSITORY = "https://gitee.com/Xunlys/AniMeow"
private const val BILIBILI_SPACE = "https://space.bilibili.com/60675099"
private const val QQ_GROUP = "1073623448"
