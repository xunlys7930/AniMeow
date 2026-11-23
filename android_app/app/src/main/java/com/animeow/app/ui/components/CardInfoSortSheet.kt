package com.animeow.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.animeow.app.ui.icons.AppIcon as Icon
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.CardInfoField
import kotlin.math.abs
import kotlinx.coroutines.isActive

/**
 * 追番列表卡与大卡片的信息字段排序弹层。显隐由既有的四个开关负责，
 * 这里只提交最终顺序（机制复制自 DetailModuleCustomizationSheet 的拖动实现）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CardInfoSortSheet(
    settings: AppearanceSettings,
    onOrderChanged: (List<CardInfoField>) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var order by remember { mutableStateOf(settings.cardInfoOrder) }
    var dragged by remember { mutableStateOf<CardInfoField?>(null) }
    var startOrder by remember { mutableStateOf(order) }
    var dragTop by remember { mutableFloatStateOf(0f) }
    var dragHeight by remember { mutableIntStateOf(0) }
    val commit by rememberUpdatedState(onOrderChanged)
    val list = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    val speed = with(density) { 560.dp.toPx() }
    LaunchedEffect(settings.cardInfoOrder) {
        if (dragged == null) order = settings.cardInfoOrder
    }

    fun moveTo(index: Int) {
        val field = dragged ?: return
        val from = order.indexOf(field)
        if (index != from && index in order.indices) {
            order = order.toMutableList().apply { removeAt(from); add(index, field) }
        }
    }
    fun reorderAtPointer() {
        val field = dragged ?: return
        val visible = list.layoutInfo.visibleItemsInfo
        // Wait for the previous placement to finish before using its geometry again.
        if (visible.any { info -> order.getOrNull(info.index)?.storageKey != info.key }) return
        val center = dragTop + dragHeight / 2f
        val target = visible.minByOrNull { abs(center - (it.offset + it.size / 2f)) } ?: return
        if (target.key != field.storageKey) moveTo(target.index)
    }
    fun finish(cancelled: Boolean) {
        if (dragged == null) return
        if (cancelled) order = startOrder
        else if (order != startOrder) commit(order.toList())
        dragged = null
    }
    fun accessibleMove(field: CardInfoField, delta: Int): Boolean {
        if (dragged != null) return false
        val from = order.indexOf(field)
        val target = from + delta
        if (from < 0 || target !in order.indices) return false
        order = order.toMutableList().apply { removeAt(from); add(target, field) }
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
            Text("卡片信息排序", style = MaterialTheme.typography.headlineSmall)
            Text("拖动左侧把手调整追番列表与大卡片上信息字段的顺序，松手后保存。显示与隐藏仍由卡片信息的开关控制。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("card-info-sort-list"), state = list,
                contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(order, key = { it.storageKey }) { field ->
                    val index = order.indexOf(field)
                    val moving = dragged == field
                    val hidden = field.hiddenBy(settings)
                    Row(Modifier.fillMaxWidth().animateItem(fadeInSpec = null, fadeOutSpec = null,
                        placementSpec = motionDragReorderPlacementSpec(moving))
                        .zIndex(if (moving) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (moving) {
                                dragTop - (list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == field.storageKey }?.offset ?: dragTop.toInt())
                            } else 0f
                            shadowElevation = if (moving) 6.dp.toPx() else 0f
                        }
                        .clip(MaterialTheme.shapes.large)
                        .background(if (moving) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .testTag("card-info-row-${field.storageKey}")
                        .semantics {
                            customActions = buildList {
                                if (index > 0) add(CustomAccessibilityAction("上移") { accessibleMove(field, -1) })
                                if (index < order.lastIndex) add(CustomAccessibilityAction("下移") { accessibleMove(field, 1) })
                            }
                        }
                        .padding(end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).testTag("card-info-drag-${field.storageKey}")
                            // The key must stay stable when order changes, otherwise Compose cancels the active gesture.
                            .pointerInput(field) {
                                detectDragGestures(
                                    onDragStart = {
                                        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == field.storageKey }
                                        if (item != null) {
                                            startOrder = order.toList()
                                            dragTop = item.offset.toFloat()
                                            dragHeight = item.size
                                            dragged = field
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    },
                                    onDragEnd = { finish(cancelled = false) },
                                    onDragCancel = { finish(cancelled = true) },
                                    onDrag = { change, delta ->
                                        change.consume()
                                        if (dragged == field) { dragTop += delta.y; reorderAtPointer() }
                                    },
                                )
                            }, contentAlignment = Alignment.Center) {
                            Icon(AppIcons.Drag, "${field.displayName}，拖动排序")
                        }
                        Column(Modifier.weight(1f)) {
                            Text(field.displayName, fontWeight = FontWeight.SemiBold)
                            Text(if (hidden) "位置 ${index + 1} · 已隐藏" else "位置 ${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            TextButton(onClick = {
                order = CardInfoField.entries.toList()
                commit(order)
            }, enabled = dragged == null, modifier = Modifier.align(Alignment.End)) { Text("恢复默认") }
        }
    }
}

/** 该字段是否被现有的显隐开关关掉；仅作提示，排序表不改开关。 */
private fun CardInfoField.hiddenBy(settings: AppearanceSettings): Boolean = when (this) {
    CardInfoField.TITLE -> !settings.showTitle
    CardInfoField.STATUS -> !settings.showStatus
    CardInfoField.PROGRESS -> !settings.showProgress
    CardInfoField.RATING -> !settings.showRating
    CardInfoField.METADATA -> false
}
