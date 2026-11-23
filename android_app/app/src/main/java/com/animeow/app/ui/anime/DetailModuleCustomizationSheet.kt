package com.animeow.app.ui.anime

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.components.motionDragReorderPlacementSpec
import com.animeow.app.ui.icons.AppIcon as Icon
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.DetailCardStyle
import com.animeow.app.ui.theme.DetailLayout
import com.animeow.app.ui.theme.DetailModule
import kotlin.math.abs
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailModuleCustomizationSheet(
    settings: AppearanceSettings,
    onOrderChanged: (List<DetailModule>) -> Unit,
    onHiddenChanged: (Set<DetailModule>) -> Unit,
    onCardStyleChanged: (DetailCardStyle) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var order by remember { mutableStateOf(settings.detailModuleOrder) }
    var dragged by remember { mutableStateOf<DetailModule?>(null) }
    var startOrder by remember { mutableStateOf(order) }
    var dragTop by remember { mutableFloatStateOf(0f) }
    var dragHeight by remember { mutableIntStateOf(0) }
    val commit by rememberUpdatedState(onOrderChanged)
    val list = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    val speed = with(density) { 560.dp.toPx() }
    LaunchedEffect(settings.detailModuleOrder) {
        if (dragged == null) order = settings.detailModuleOrder
    }

    fun moveTo(index: Int) {
        val module = dragged ?: return
        val from = order.indexOf(module)
        if (index != from && index in order.indices) {
            order = order.toMutableList().apply { removeAt(from); add(index, module) }
        }
    }
    fun reorderAtPointer() {
        val module = dragged ?: return
        val visible = list.layoutInfo.visibleItemsInfo
        // Wait for the previous placement to finish before using its geometry again.
        if (visible.any { info -> order.getOrNull(info.index)?.storageKey != info.key }) return
        val center = dragTop + dragHeight / 2f
        val target = visible.minByOrNull { abs(center - (it.offset + it.size / 2f)) } ?: return
        if (target.key != module.storageKey) moveTo(target.index)
    }
    fun finish(cancelled: Boolean) {
        if (dragged == null) return
        if (cancelled) order = startOrder
        else if (order != startOrder) commit(order.toList())
        dragged = null
    }
    fun accessibleMove(module: DetailModule, delta: Int): Boolean {
        if (dragged != null) return false
        val from = order.indexOf(module)
        val target = from + delta
        if (from < 0 || target !in order.indices) return false
        order = order.toMutableList().apply { removeAt(from); add(target, module) }
        commit(order.toList())
        return true
    }
    LaunchedEffect(dragged) {
        if (dragged == null) return@LaunchedEffect
        var lastFrame = withFrameNanos { it }
        while (isActive && dragged != null) {
            val now = withFrameNanos { it }
            val seconds = ((now - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
            lastFrame = now
            val info = list.layoutInfo
            val center = dragTop + dragHeight / 2f
            val amount = when {
                center < info.viewportStartOffset + edge -> -((info.viewportStartOffset + edge - center) / edge).coerceIn(0f, 1f)
                center > info.viewportEndOffset - edge -> ((center - info.viewportEndOffset + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (amount != 0f) list.scrollBy(amount * speed * seconds)
            reorderAtPointer()
        }
    }

    StableModalBottomSheet(onDismissRequest = onDismissRequest, sheetGesturesEnabled = false) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.82f).dp)
            .navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("详情页模块", style = MaterialTheme.typography.headlineSmall)
            Text("拖动左侧把手调整顺序，松手后保存。隐藏模块不会删除数据。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (settings.detailLayout == DetailLayout.MINIMAL) {
                Text("极简阅读使用留白和分隔线；卡片材质会保留给其他布局。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DetailCardStyle.entries) { style ->
                        FilterChip(selected = settings.detailCardStyle == style, enabled = dragged == null,
                            onClick = { onCardStyleChanged(style) }, label = { Text(style.displayName) })
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("detail-module-sort-list"), state = list,
                contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(order, key = { it.storageKey }) { module ->
                    val index = order.indexOf(module)
                    val moving = dragged == module
                    val visible = module !in settings.hiddenDetailModules
                    val canHide = !visible || settings.hiddenDetailModules.size < DetailModule.entries.size - 1
                    Row(Modifier.fillMaxWidth().animateItem(fadeInSpec = null, fadeOutSpec = null,
                        placementSpec = motionDragReorderPlacementSpec(moving))
                        .zIndex(if (moving) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (moving) {
                                dragTop - (list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == module.storageKey }?.offset ?: dragTop.toInt())
                            } else 0f
                            shadowElevation = if (moving) 6.dp.toPx() else 0f
                        }
                        .clip(MaterialTheme.shapes.large)
                        .background(if (moving) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .testTag("module-row-${module.storageKey}")
                        .semantics {
                            customActions = buildList {
                                if (index > 0) add(CustomAccessibilityAction("上移") { accessibleMove(module, -1) })
                                if (index < order.lastIndex) add(CustomAccessibilityAction("下移") { accessibleMove(module, 1) })
                            }
                        }
                        .padding(end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).testTag("module-drag-${module.storageKey}")
                            // The key must stay stable when order changes, otherwise Compose cancels the active gesture.
                            .pointerInput(module) {
                                detectDragGestures(
                                    onDragStart = {
                                        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == module.storageKey }
                                        if (item != null) {
                                            startOrder = order.toList()
                                            dragTop = item.offset.toFloat()
                                            dragHeight = item.size
                                            dragged = module
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    },
                                    onDragEnd = { finish(cancelled = false) },
                                    onDragCancel = { finish(cancelled = true) },
                                    onDrag = { change, delta ->
                                        change.consume()
                                        if (dragged == module) { dragTop += delta.y; reorderAtPointer() }
                                    },
                                )
                            }, contentAlignment = Alignment.Center) {
                            Icon(AppIcons.Drag, "${module.displayName}，拖动排序")
                        }
                        Column(Modifier.weight(1f)) {
                            Text(module.displayName, fontWeight = FontWeight.SemiBold)
                            Text("位置 ${index + 1}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(visible, enabled = canHide && dragged == null,
                            modifier = Modifier.semantics { contentDescription = "显示${module.displayName}" },
                            onCheckedChange = { show -> onHiddenChanged(if (show) settings.hiddenDetailModules - module else settings.hiddenDetailModules + module) })
                    }
                }
            }
            TextButton(onClick = {
                order = DetailModule.entries.toList()
                commit(order)
                onHiddenChanged(emptySet())
                onCardStyleChanged(DetailCardStyle.TONAL)
            }, enabled = dragged == null, modifier = Modifier.align(Alignment.End)) { Text("恢复默认") }
        }
    }
}
