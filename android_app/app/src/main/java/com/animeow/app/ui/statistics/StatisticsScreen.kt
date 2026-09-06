package com.animeow.app.ui.statistics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.ui.preferences.AppearanceViewModel
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.StatisticsModule
import com.animeow.app.ui.theme.StatisticsPreset
import com.animeow.app.ui.theme.StatisticsChartStyle
import com.animeow.app.ui.theme.StatisticsLayoutStyle
import com.animeow.app.ui.theme.StatisticsMetric
import com.animeow.app.ui.theme.ContentDensity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    onBack: () -> Unit,
    showBack: Boolean = true,
    topBarContainerColor: Color = Color.Transparent,
    onSearchRequested: () -> Unit = {},
    onAnalysisRequested: () -> Unit,
    onTagIndexRequested: () -> Unit,
    onStatusSelected: (String) -> Unit,
    onTagSelected: (Long) -> Unit,
    viewModel: StatisticsViewModel = viewModel(),
    appearanceViewModel: AppearanceViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by appearanceViewModel.settings.collectAsStateWithLifecycle()
    var showCustomization by remember { mutableStateOf(false) }
    val modules = settings.statisticsModuleOrder.filterNot(settings.hiddenStatisticsModules::contains)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("统计") },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onSearchRequested) {
                        Icon(Icons.Outlined.Search, contentDescription = "全局搜索")
                    }
                    IconButton(onClick = { showCustomization = true }) {
                        Icon(Icons.Outlined.Tune, contentDescription = "自定义统计模块")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = topBarContainerColor,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        if (state.totalAnime == 0) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Insights, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("添加作品后即可生成统计", style = MaterialTheme.typography.titleLarge)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy((12 * settings.statisticsDensity.scale).dp),
            ) {
                items(modules, key = StatisticsModule::storageKey) { module ->
                    AnimatedVisibility(visible = true, enter = motionFadeIn()) {
                        CompositionLocalProvider(
                            LocalStatisticsDensity provides settings.statisticsDensity,
                            LocalStatisticsLayoutStyle provides settings.statisticsLayoutStyle,
                        ) {
                            when (module) {
                                StatisticsModule.OVERVIEW -> OverviewModule(
                                    state = state,
                                    hiddenMetrics = settings.hiddenStatisticsMetrics,
                                )
                                StatisticsModule.STATUS -> when (settings.statisticsStatusChart) {
                                    StatisticsChartStyle.DONUT -> StatusModule(
                                        "状态分布",
                                        state.statusCounts,
                                        onItemClick = { item -> item.filterValue?.let(onStatusSelected) },
                                    )
                                    StatisticsChartStyle.RANKED -> BarModule(
                                        "状态分布",
                                        state.statusCounts,
                                        onItemClick = { item -> item.filterValue?.let(onStatusSelected) },
                                    )
                                }
                                StatisticsModule.RATING -> BarModule("评分分布", state.ratingCounts)
                                StatisticsModule.TAGS -> when (settings.statisticsTagChart) {
                                    StatisticsChartStyle.DONUT -> StatusModule(
                                        title = "标签偏好",
                                        items = state.tagCounts.take(10),
                                        actionLabel = "查看全部",
                                        onAction = onTagIndexRequested,
                                        onItemClick = { item -> item.id?.let(onTagSelected) },
                                    )
                                    StatisticsChartStyle.RANKED -> BarModule(
                                        title = "标签偏好",
                                        items = state.tagCounts.take(10),
                                        actionLabel = "查看全部",
                                        onAction = onTagIndexRequested,
                                        onItemClick = { item -> item.id?.let(onTagSelected) },
                                    )
                                }
                                StatisticsModule.ADAPTATION -> StatusModule(
                                    title = "改编类型",
                                    items = state.adaptationTypeCounts,
                                )
                                StatisticsModule.GENRE -> StatusModule(
                                    title = "题材分布",
                                    items = state.genreCounts,
                                )
                                StatisticsModule.YEAR -> BarModule(
                                    title = "年份分布",
                                    items = state.yearCounts,
                                )
                                StatisticsModule.ACTIVITY -> ActivityModule(state.monthlyActivity)
                                StatisticsModule.ANALYSIS -> AnalysisModule(onAnalysisRequested)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCustomization) {
        StatisticsCustomizationSheet(
            settings = settings,
            onOrderChanged = appearanceViewModel::setStatisticsModuleOrder,
            onHiddenChanged = appearanceViewModel::setHiddenStatisticsModules,
            onPresetSelected = appearanceViewModel::applyStatisticsPreset,
            onDensitySelected = appearanceViewModel::setStatisticsDensity,
            onStatusChartSelected = appearanceViewModel::setStatisticsStatusChart,
            onTagChartSelected = appearanceViewModel::setStatisticsTagChart,
            onLayoutStyleSelected = appearanceViewModel::setStatisticsLayoutStyle,
            onHiddenMetricsChanged = appearanceViewModel::setHiddenStatisticsMetrics,
            onDismiss = { showCustomization = false },
        )
    }
}

@Composable
private fun AnalysisModule(onOpen: () -> Unit) {
    if (LocalStatisticsLayoutStyle.current == StatisticsLayoutStyle.DASHBOARD) {
        ModuleCard("AI 看番风格") {
            AnalysisModuleContent(onOpen)
        }
        return
    }
    val scale = LocalStatisticsDensity.current.scale
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Box(modifier = Modifier.padding((16 * scale).dp)) { AnalysisModuleContent(onOpen) }
    }
}

@Composable
private fun AnalysisModuleContent(onOpen: () -> Unit) {
    val scale = LocalStatisticsDensity.current.scale
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((12 * scale).dp),
    ) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
        Column(Modifier.weight(1f)) {
            Text("生成你的口味画像", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("用汇总统计分析偏好，也可接入自己的模型。", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onOpen) { Text("分析") }
    }
}

private data class OverviewMetricValue(
    val metric: StatisticsMetric,
    val label: String,
    val value: String,
)

private fun overviewMetricValues(state: StatisticsUiState): List<OverviewMetricValue> = listOf(
    OverviewMetricValue(StatisticsMetric.TOTAL, "总收录", state.totalAnime.toString()),
    OverviewMetricValue(StatisticsMetric.ANIME, "动画", state.animeCount.toString()),
    OverviewMetricValue(StatisticsMetric.BOOK, "书籍", state.bookCount.toString()),
    OverviewMetricValue(StatisticsMetric.WATCHED_EPISODES, "已看集数", state.watchedEpisodes.toString()),
    OverviewMetricValue(StatisticsMetric.WATCH_HOURS, "估算时长", "${state.watchHours} 小时"),
    OverviewMetricValue(StatisticsMetric.STREAK, "连续打卡", "${state.currentStreakDays} 天"),
    OverviewMetricValue(
        StatisticsMetric.AVERAGE_RATING,
        "平均评分",
        state.averageRating?.let { "%.1f / 10".format(it) } ?: "暂无评分",
    ),
)

@Composable
private fun OverviewModule(
    state: StatisticsUiState,
    hiddenMetrics: Set<StatisticsMetric>,
) {
    val metrics = overviewMetricValues(state).filterNot { it.metric in hiddenMetrics }
    when (LocalStatisticsLayoutStyle.current) {
        StatisticsLayoutStyle.CLASSIC -> ClassicOverviewModule(metrics)
        StatisticsLayoutStyle.DASHBOARD -> DashboardOverviewModule(metrics)
    }
}

@Composable
private fun ClassicOverviewModule(metrics: List<OverviewMetricValue>) {
    val density = LocalStatisticsDensity.current
    val scale = density.scale
    val averageRating = metrics.firstOrNull { it.metric == StatisticsMetric.AVERAGE_RATING }
    val tileMetrics = metrics.filterNot { it.metric == StatisticsMetric.AVERAGE_RATING }
    Column(verticalArrangement = Arrangement.spacedBy((10 * scale).dp)) {
        if (metrics.isEmpty()) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "概览数据已全部隐藏，可从右上角重新选择。",
                    modifier = Modifier.padding((16 * scale).dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        tileMetrics.chunked(2).forEach { rowMetrics ->
            if (rowMetrics.size == 1) {
                MetricCard(rowMetrics[0].label, rowMetrics[0].value, Modifier.fillMaxWidth())
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy((10 * scale).dp),
                ) {
                    rowMetrics.forEach { metric ->
                        MetricCard(metric.label, metric.value, Modifier.weight(1f))
                    }
                }
            }
        }
        averageRating?.let { metric ->
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(modifier = Modifier.padding((16 * scale).dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(metric.label, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(metric.value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun DashboardOverviewModule(metrics: List<OverviewMetricValue>) {
    val density = LocalStatisticsDensity.current
    val scale = density.scale
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape((28 * density.itemScale).dp))
            .background(
                brush = Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.88f),
                    ),
                ),
            )
            .padding((18 * scale).dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy((12 * scale).dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy((10 * scale).dp),
            ) {
                Box(
                    modifier = Modifier
                        .size((42 * density.itemScale).dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.68f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text("我的追番档案", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "只展示你真正关心的数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (metrics.isEmpty()) {
                Text(
                    "概览数据已全部隐藏，可从右上角重新选择。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                metrics.chunked(3).forEach { rowMetrics ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy((8 * scale).dp),
                    ) {
                        rowMetrics.forEach { metric ->
                            DashboardMetricCell(metric, Modifier.weight(1f))
                        }
                        repeat(3 - rowMetrics.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardMetricCell(metric: OverviewMetricValue, modifier: Modifier = Modifier) {
    val scale = LocalStatisticsDensity.current.scale
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.62f))
            .padding(horizontal = (9 * scale).dp, vertical = (11 * scale).dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            metric.value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            metric.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    val density = LocalStatisticsDensity.current.scale
    ElevatedCard(modifier = modifier) {
        Column(modifier = Modifier.padding((16 * density).dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusModule(
    title: String,
    items: List<NamedCount>,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onItemClick: ((NamedCount) -> Unit)? = null,
) {
    val density = LocalStatisticsDensity.current
    val scale = density.scale
    ModuleCard(title, actionLabel, onAction) {
        if (items.isEmpty()) {
            Text("暂无状态数据")
            return@ModuleCard
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((18 * scale).dp)) {
            DonutChart(items, Modifier.size((150 * density.itemScale).dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy((7 * scale).dp)) {
                items.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .then(
                                if (onItemClick != null) Modifier.clickable { onItemClick(item) }
                                else Modifier,
                            )
                            .padding(horizontal = 4.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(item.color?.let(::Color) ?: STAT_COLORS[index % STAT_COLORS.size]),
                        )
                        Text(item.name, modifier = Modifier.weight(1f).padding(start = 7.dp))
                        Text(item.count.toString(), fontWeight = FontWeight.SemiBold)
                        if (onItemClick != null) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowForward,
                                contentDescription = "查看 ${item.name} 作品",
                                modifier = Modifier.padding(start = 4.dp).size(16.dp),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DonutChart(items: List<NamedCount>, modifier: Modifier = Modifier) {
    val total = items.sumOf(NamedCount::count).coerceAtLeast(1)
    Canvas(modifier = modifier) {
        var start = -90f
        items.forEachIndexed { index, item ->
            val sweep = item.count.toFloat() / total * 360f
            drawArc(
                color = item.color?.let(::Color) ?: STAT_COLORS[index % STAT_COLORS.size],
                startAngle = start,
                sweepAngle = (sweep - 2f).coerceAtLeast(0.5f),
                useCenter = false,
                topLeft = Offset(10.dp.toPx(), 10.dp.toPx()),
                size = Size(size.width - 20.dp.toPx(), size.height - 20.dp.toPx()),
                style = Stroke(width = 22.dp.toPx(), cap = StrokeCap.Round),
            )
            start += sweep
        }
    }
}

@Composable
private fun BarModule(
    title: String,
    items: List<NamedCount>,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onItemClick: ((NamedCount) -> Unit)? = null,
) {
    val scale = LocalStatisticsDensity.current.scale
    ModuleCard(title, actionLabel, onAction) {
        if (items.isEmpty() || items.all { it.count == 0 }) {
            Text("暂无数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val max = items.maxOf(NamedCount::count).coerceAtLeast(1)
            items.forEach { item ->
                val itemColor = item.color?.let(::Color) ?: MaterialTheme.colorScheme.primary
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .then(
                            if (onItemClick != null) Modifier.clickable { onItemClick(item) }
                            else Modifier,
                        )
                        .padding(horizontal = (4 * scale).dp, vertical = (4 * scale).dp),
                    verticalArrangement = Arrangement.spacedBy((3 * scale).dp),
                ) {
                    Row {
                        Text(item.name, modifier = Modifier.weight(1f))
                        Text(item.count.toString(), fontWeight = FontWeight.SemiBold)
                        if (onItemClick != null) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowForward,
                                contentDescription = "查看 ${item.name} 作品",
                                modifier = Modifier.padding(start = 4.dp).size(16.dp),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(
                            Modifier.fillMaxWidth(item.count.toFloat() / max).fillMaxHeight().background(itemColor),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityModule(items: List<NamedCount>) {
    val density = LocalStatisticsDensity.current
    ModuleCard("近 12 个月观看活动") {
        val max = items.maxOfOrNull(NamedCount::count)?.coerceAtLeast(1) ?: 1
        Row(
            modifier = Modifier.fillMaxWidth().height((150 * density.itemScale).dp),
            horizontalArrangement = Arrangement.spacedBy((4 * density.scale).dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items.forEach { item ->
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(item.count.toString(), style = MaterialTheme.typography.labelSmall)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((110f * item.count / max).coerceAtLeast(3f).dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.secondary),
                    )
                    Text(item.name, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ModuleCard(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalStatisticsDensity.current.scale
    val dashboard = LocalStatisticsLayoutStyle.current == StatisticsLayoutStyle.DASHBOARD
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = if (dashboard) RoundedCornerShape(24.dp) else MaterialTheme.shapes.medium,
        colors = if (dashboard) {
            CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            )
        } else {
            CardDefaults.elevatedCardColors()
        },
    ) {
        Column(
            modifier = Modifier.padding((16 * density).dp),
            verticalArrangement = Arrangement.spacedBy((10 * density).dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dashboard) {
                    Icon(
                        Icons.Outlined.Star,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp).size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction) { Text(actionLabel) }
                }
            }
            content()
        }
    }
}

private val LocalStatisticsDensity = staticCompositionLocalOf { ContentDensity.COMFORTABLE }
private val LocalStatisticsLayoutStyle = staticCompositionLocalOf { StatisticsLayoutStyle.CLASSIC }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatisticsCustomizationSheet(
    settings: AppearanceSettings,
    onOrderChanged: (List<StatisticsModule>) -> Unit,
    onHiddenChanged: (Set<StatisticsModule>) -> Unit,
    onPresetSelected: (StatisticsPreset) -> Unit,
    onDensitySelected: (ContentDensity) -> Unit,
    onStatusChartSelected: (StatisticsChartStyle) -> Unit,
    onTagChartSelected: (StatisticsChartStyle) -> Unit,
    onLayoutStyleSelected: (StatisticsLayoutStyle) -> Unit,
    onHiddenMetricsChanged: (Set<StatisticsMetric>) -> Unit,
    onDismiss: () -> Unit,
) {
    val order = remember {
        mutableStateListOf<StatisticsModule>().apply { addAll(settings.statisticsModuleOrder) }
    }
    LaunchedEffect(settings.statisticsModuleOrder) {
        if (order.toList() != settings.statisticsModuleOrder) {
            order.clear()
            order.addAll(settings.statisticsModuleOrder)
        }
    }
    StableModalBottomSheet(onDismissRequest = onDismiss, sheetGesturesEnabled = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("统计页定制", style = MaterialTheme.typography.headlineSmall)
            Text(
                "布局、概览数据、模块与图表都可独立选择；修改后会立即应用并自动保存。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("统计布局", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(StatisticsLayoutStyle.entries) { style ->
                    FilterChip(
                        selected = settings.statisticsLayoutStyle == style,
                        onClick = { onLayoutStyleSelected(style) },
                        label = { Text(style.displayName) },
                    )
                }
            }
            Text(
                settings.statisticsLayoutStyle.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("页面预设", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(StatisticsPreset.entries.filterNot { it == StatisticsPreset.CUSTOM }) { preset ->
                    FilterChip(
                        selected = settings.statisticsPreset == preset,
                        onClick = { onPresetSelected(preset) },
                        label = { Text(preset.displayName) },
                    )
                }
            }
            Text("页面密度", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ContentDensity.entries) { density ->
                    FilterChip(
                        selected = settings.statisticsDensity == density,
                        onClick = { onDensitySelected(density) },
                        label = { Text(density.displayName) },
                    )
                }
            }
            Text(
                settings.statisticsDensity.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("概览数据", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "每项都可以单独显示或隐藏；连续打卡默认关闭，平均评分等数据也可按需关闭。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            StatisticsMetric.entries.forEach { metric ->
                val visible = metric !in settings.hiddenStatisticsMetrics
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(metric.displayName, fontWeight = FontWeight.SemiBold)
                        Text(
                            metric.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = visible,
                        onCheckedChange = { show ->
                            onHiddenMetricsChanged(
                                if (show) settings.hiddenStatisticsMetrics - metric
                                else settings.hiddenStatisticsMetrics + metric,
                            )
                        },
                    )
                }
            }
            Text("状态分布", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(StatisticsChartStyle.entries) { style ->
                    FilterChip(
                        selected = settings.statisticsStatusChart == style,
                        onClick = { onStatusChartSelected(style) },
                        label = { Text(style.displayName) },
                    )
                }
            }
            Text("标签偏好", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(StatisticsChartStyle.entries) { style ->
                    FilterChip(
                        selected = settings.statisticsTagChart == style,
                        onClick = { onTagChartSelected(style) },
                        label = { Text(style.displayName) },
                    )
                }
            }
            Text("模块顺序与显隐", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            order.forEach { module ->
                var dragOffset by remember(module) { mutableFloatStateOf(0f) }
                val visible = module !in settings.hiddenStatisticsModules
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer(translationY = dragOffset)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .pointerInput(module, order.toList()) {
                            val threshold = 44.dp.toPx()
                            detectDragGesturesAfterLongPress(
                                onDragEnd = { dragOffset = 0f },
                                onDragCancel = { dragOffset = 0f },
                            ) { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                                val index = order.indexOf(module)
                                if (dragOffset > threshold && index < order.lastIndex) {
                                    order.removeAt(index)
                                    order.add(index + 1, module)
                                    dragOffset = 0f
                                    onOrderChanged(order.toList())
                                } else if (dragOffset < -threshold && index > 0) {
                                    order.removeAt(index)
                                    order.add(index - 1, module)
                                    dragOffset = 0f
                                    onOrderChanged(order.toList())
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.DragHandle, contentDescription = "拖动排序")
                    Text(module.displayName, modifier = Modifier.weight(1f).padding(start = 10.dp), fontWeight = FontWeight.SemiBold)
                    Switch(
                        checked = visible,
                        enabled = !visible || settings.hiddenStatisticsModules.size < StatisticsModule.entries.size - 1,
                        onCheckedChange = { show ->
                            onHiddenChanged(
                                if (show) settings.hiddenStatisticsModules - module
                                else settings.hiddenStatisticsModules + module,
                            )
                        },
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("完成") }
        }
    }
}

private val STAT_COLORS = listOf(
    Color(0xFF3482FF),
    Color(0xFF8A4FD0),
    Color(0xFF3E9B55),
    Color(0xFFF08A24),
    Color(0xFFE4568F),
    Color(0xFF00A2A5),
)
