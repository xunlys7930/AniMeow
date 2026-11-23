package com.animeow.app.ui.management

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.animeow.app.data.local.TagEntity
import com.animeow.app.ui.components.motionDragReorderPlacementSpec
import com.animeow.app.ui.icons.AppIcon as Icon
import com.animeow.app.ui.icons.AppIcons
import kotlin.math.abs
import kotlinx.coroutines.isActive

@Composable
internal fun TagOrderDialog(
    tags: List<TagEntity>,
    busy: Boolean,
    onSave: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var order by remember { mutableStateOf(tags) }
    var dragged by remember { mutableStateOf<Long?>(null) }
    var startOrder by remember { mutableStateOf(order) }
    var dragTop by remember { mutableFloatStateOf(0f) }
    var dragHeight by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()
    val stale = tags.map { it.id }.toSet() != order.map { it.id }.toSet()
    val canMove by rememberUpdatedState(!busy && !stale)
    val edge = with(LocalDensity.current) { 32.dp.toPx() }
    val handleWidth = with(LocalDensity.current) { 48.dp.toPx() }
    val speed = with(LocalDensity.current) { 180.dp.toPx() }

    fun move(id: Long, target: Int): Boolean {
        if (!canMove) return false
        val from = order.indexOfFirst { it.id == id }
        if (from < 0 || target !in order.indices || target == from) return false
        order = order.toMutableList().apply { add(target, removeAt(from)) }
        return true
    }
    fun reorderAtPointer() {
        val id = dragged ?: return
        val visible = list.layoutInfo.visibleItemsInfo
        // Wait until item positions reflect the last swap before comparing their geometry.
        if (visible.any { order.getOrNull(it.index)?.id != it.key }) return
        val center = dragTop + dragHeight / 2f
        val target = visible.minByOrNull { abs(center - (it.offset + it.size / 2f)) } ?: return
        if (target.key != id) move(id, target.index)
    }
    fun finish(cancelled: Boolean) {
        if (dragged != null && cancelled) order = startOrder
        dragged = null
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
            if (canMove && amount != 0f) list.scrollBy(amount * abs(amount) * speed * seconds)
            reorderAtPointer()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("调整标签顺序") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("拖动左侧把手或使用上移、下移，点击保存后用于标签选择和筛选；取消不会更改原顺序。")
                if (stale) Text("标签列表已变化，请取消后重新打开", color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.heightIn(max = 360.dp).testTag("tag-order-list")
                    // Measure in the stationary list, never in the row that moves under the finger.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (!canMove || down.position.x > handleWidth) return@awaitEachGesture
                            val row = list.layoutInfo.visibleItemsInfo.firstOrNull {
                                down.position.y >= it.offset && down.position.y < it.offset + it.size
                            } ?: return@awaitEachGesture
                            val id = row.key as? Long ?: return@awaitEachGesture
                            // Reserve only the handle; ordinary swipes still belong to the list.
                            down.consume()
                            var completed = false
                            try {
                                while (true) {
                                    val pointer = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                                    if (!pointer.pressed) { completed = !pointer.isConsumed; pointer.consume(); break }
                                    val distance = pointer.position.y - down.position.y
                                    if (dragged == null && abs(distance) >= viewConfiguration.touchSlop) {
                                        startOrder = order.toList(); dragHeight = row.size; dragged = id
                                    }
                                    if (dragged == id) {
                                        pointer.consume(); dragTop = row.offset + distance; reorderAtPointer()
                                    }
                                }
                            } finally { finish(cancelled = !completed) }
                        }
                    }, state = list,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(order, key = TagEntity::id) { tag ->
                        val index = order.indexOfFirst { it.id == tag.id }
                        val moving = dragged == tag.id
                        Row(Modifier.fillMaxWidth()
                            .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = motionDragReorderPlacementSpec(moving))
                            .zIndex(if (moving) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (moving) dragTop - (list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == tag.id }?.offset ?: dragTop.toInt()) else 0f
                            }
                            .clip(MaterialTheme.shapes.medium)
                            .background(if (moving) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .testTag("tag-order-row-${tag.id}")
                            .semantics {
                                customActions = buildList {
                                    if (canMove && dragged == null && index > 0) add(CustomAccessibilityAction("上移") { move(tag.id, index - 1) })
                                    if (canMove && dragged == null && index < order.lastIndex) add(CustomAccessibilityAction("下移") { move(tag.id, index + 1) })
                                }
                            }, verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).testTag("tag-order-drag-${tag.id}"),
                                contentAlignment = Alignment.Center) { Icon(AppIcons.Drag, "拖动${tag.name}排序") }
                            Text(tag.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { move(tag.id, index - 1) }, enabled = canMove && dragged == null && index > 0) {
                                Icon(AppIcons.Collapse, "上移${tag.name}")
                            }
                            IconButton(onClick = { move(tag.id, index + 1) }, enabled = canMove && dragged == null && index < order.lastIndex) {
                                Icon(AppIcons.Expand, "下移${tag.name}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(order.map { it.id }) }, enabled = canMove && dragged == null) { Text(if (busy) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}
