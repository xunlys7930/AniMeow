package com.animeow.app.ui.character

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.animeow.app.data.local.CharacterEntity
import com.animeow.app.data.local.CharacterRelationItem
import kotlin.math.abs
import kotlin.math.roundToInt

@Stable
private class RelationGraphState(centerId: Long) {
    var spatial by mutableStateOf(false)
    var camera by mutableStateOf(RelationGraphCamera())
    var selectedId by mutableLongStateOf(centerId)
    var pageIndex by mutableIntStateOf(0)
    var showViewControls by mutableStateOf(false)

    fun reset(centerId: Long) {
        camera = RelationGraphCamera()
        selectedId = centerId
    }

    companion object {
        val Saver = listSaver<RelationGraphState, Any>(
            save = { listOf(it.spatial, it.camera.yaw, it.camera.pitch, it.camera.zoom, it.camera.panX, it.camera.panY, it.selectedId, it.pageIndex, it.showViewControls) },
            restore = {
                RelationGraphState(it[6] as Long).apply {
                    spatial = it[0] as Boolean
                    camera = RelationGraphCamera(it[1] as Float, it[2] as Float, it[3] as Float, it[4] as Float, it[5] as Float).normalized()
                    pageIndex = it[7] as Int
                    showViewControls = it[8] as Boolean
                }
            },
        )
    }
}

@Composable
internal fun CharacterRelationGraph(
    center: CharacterEntity,
    relations: List<CharacterRelationItem>,
    onCharacterClick: (Long) -> Unit,
) {
    val state = rememberSaveable(center.id, saver = RelationGraphState.Saver) { RelationGraphState(center.id) }
    var fullscreen by rememberSaveable(center.id) { mutableStateOf(false) }
    val ordered = remember(center.id, relations) {
        relations.filter {
            it.relatedCharacterId != center.id &&
                (it.relation.sourceCharacterId == center.id || it.relation.targetCharacterId == center.id)
        }.distinctBy { it.relatedCharacterId }.sortedBy { it.relatedCharacterId }
    }
    ElevatedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        RelationGraphContent(center, ordered, state, false, { fullscreen = true }, onCharacterClick, 320.dp)
    }
    if (fullscreen) {
        RelationGraphDialog(center, ordered, state, onDismiss = { fullscreen = false }, onCharacterClick = {
            fullscreen = false
            onCharacterClick(it)
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelationGraphDialog(
    center: CharacterEntity,
    relations: List<CharacterRelationItem>,
    state: RelationGraphState,
    onDismiss: () -> Unit,
    onCharacterClick: (Long) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("关系星图", maxLines = 1) },
                    navigationIcon = { TextButton(onClick = onDismiss) { Text("返回") } },
                )
            },
        ) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                val chartHeight = (maxHeight * 0.62f).coerceIn(280.dp, 520.dp)
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        RelationGraphContent(center, relations, state, true, onDismiss, onCharacterClick, chartHeight)
                    }
                }
            }
        }
    }
}

@Composable
private fun RelationGraphContent(
    center: CharacterEntity,
    relations: List<CharacterRelationItem>,
    state: RelationGraphState,
    fullscreen: Boolean,
    onFullscreenClick: () -> Unit,
    onCharacterClick: (Long) -> Unit,
    chartHeight: androidx.compose.ui.unit.Dp,
) {
    val page = remember(relations, state.pageIndex) { relationGraphPage(relations.map { it.relatedCharacterId }, state.pageIndex) }
    val pageIds = page.ids.toSet()
    val visible = remember(relations, page.ids) { relations.filter { it.relatedCharacterId in pageIds } }
    val selected = visible.firstOrNull { it.relatedCharacterId == state.selectedId }
    val activeId = selected?.relatedCharacterId ?: center.id
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !state.spatial, onClick = { state.spatial = false }, label = { Text("平面") })
            FilterChip(selected = state.spatial, onClick = { state.spatial = true }, label = { Text("空间") })
            Box(Modifier.weight(1f))
            if (!fullscreen) TextButton(onClick = onFullscreenClick) { Text("全屏") }
        }
        Text("${center.displayName}的直接关系", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
        RelationGraphScene(center, visible, state, activeId, fullscreen, Modifier.fillMaxWidth().height(chartHeight))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (page.total == 0) "0 个关联角色" else "显示 ${page.first}–${page.last} / ${page.total} 个关联角色",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { state.reset(center.id) }) { Text("回到中心") }
        }
        if (page.count > 1) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = page.index > 0, onClick = { state.pageIndex = page.index - 1; state.reset(center.id) }) { Text("上一页") }
                Text("${page.index + 1} / ${page.count}", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = page.index + 1 < page.count, onClick = { state.pageIndex = page.index + 1; state.reset(center.id) }) { Text("下一页") }
            }
        }
        if (visible.isNotEmpty()) {
            LazyRow(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(visible, key = { it.relatedCharacterId }) { relation ->
                    FilterChip(
                        selected = activeId == relation.relatedCharacterId,
                        onClick = { state.selectedId = relation.relatedCharacterId },
                        label = { Text(relation.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
                    )
                }
            }
        }
        if (selected != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(selected.displayName, style = MaterialTheme.typography.titleMedium)
                selected.relation.roleOf(selected.relatedCharacterId).takeIf(String::isNotBlank)?.let {
                    Text("${selected.displayName}是${center.displayName}的$it", style = MaterialTheme.typography.bodyMedium)
                }
                selected.relation.roleOf(center.id).takeIf(String::isNotBlank)?.let {
                    Text("${center.displayName}是${selected.displayName}的$it", style = MaterialTheme.typography.bodyMedium)
                }
                Text("关系强度 ${selected.relation.strength}/5", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                selected.relation.note?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                TextButton(onClick = { onCharacterClick(selected.relatedCharacterId) }) { Text("查看角色资料") }
            }
        }
        Text(
            if (fullscreen) {
                if (state.spatial) "单指拖动旋转，双指缩放和移动；点击角色查看关系。" else "拖动移动，双指缩放；点击角色查看关系。"
            } else "点击角色查看关系，进入全屏可调整视角。",
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.spatial) Text("空间远近不代表辈分或关系强弱", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (fullscreen) {
            TextButton(onClick = { state.showViewControls = !state.showViewControls }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(if (state.showViewControls) "收起视角调整" else "调整视角")
            }
            if (state.showViewControls) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    if (state.spatial) {
                        Text("水平旋转 ${state.camera.yaw.roundToInt()}°", style = MaterialTheme.typography.bodySmall)
                        Slider(value = state.camera.yaw, onValueChange = { state.camera = state.camera.copy(yaw = it).normalized() }, valueRange = -180f..180f, modifier = Modifier.semantics { contentDescription = "水平旋转" })
                        Text("俯仰 ${state.camera.pitch.roundToInt()}°", style = MaterialTheme.typography.bodySmall)
                        Slider(value = state.camera.pitch, onValueChange = { state.camera = state.camera.copy(pitch = it).normalized() }, valueRange = -55f..55f, modifier = Modifier.semantics { contentDescription = "俯仰" })
                    }
                    Text("缩放 ${(state.camera.zoom * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                    Slider(value = state.camera.zoom, onValueChange = { state.camera = state.camera.copy(zoom = it).normalized() }, valueRange = 0.6f..2.5f, modifier = Modifier.semantics { contentDescription = "缩放" })
                }
            }
        }
    }
}

private data class GraphSceneNode(val character: CharacterEntity, val role: String, val strength: Int, val position: RelationGraphProjection)
private data class GraphLabel(val text: TextLayoutResult, val position: Offset)

@Composable
private fun RelationGraphScene(
    center: CharacterEntity,
    relations: List<CharacterRelationItem>,
    state: RelationGraphState,
    activeId: Long,
    interactive: Boolean,
    modifier: Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val foreground = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.bodySmall.copy(color = foreground)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.clipToBounds().testTag(if (interactive) "character-relation-graph-fullscreen" else "character-relation-graph")) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val inset = with(density) { 44.dp.toPx() }
        val centerPosition = projectRelationGraphPoint(RelationGraphPoint(0f, 0f, 0f), state.camera, width, height, inset, state.spatial)
        val nodes = listOf(GraphSceneNode(center, "", 0, centerPosition)) + relations.mapIndexed { index, relation ->
            GraphSceneNode(
                CharacterEntity(id = relation.relatedCharacterId, name = relation.displayName, imageUrl = relation.relatedImageUrl, imageDisplayMode = relation.relatedImageDisplayMode),
                relation.relation.roleOf(relation.relatedCharacterId), relation.relation.strength,
                projectRelationGraphPoint(relationGraphPoint(index, relations.size, state.spatial), state.camera, width, height, inset, state.spatial),
            )
        }
        val gestureModifier = if (interactive) Modifier.pointerInput(state, state.spatial) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var pastSlop = false
                var usedMultiplePointers = false
                var accumulated = Offset.Zero
                var zoomMotion = 0f
                do {
                    val event = awaitPointerEvent()
                    // Clickable portraits consume pointer-down, which must not cancel a pinch.
                    // Only a movement claimed by another recognizer cancels this gesture.
                    if (!pastSlop && event.changes.any { it.isConsumed && it.position != it.previousPosition }) break
                    val pointerCount = event.changes.count { it.pressed }
                    val pan = event.calculatePan()
                    val zoom = event.calculateZoom()
                    accumulated += pan
                    zoomMotion += abs(1f - zoom) * minOf(size.width, size.height)
                    if (pointerCount >= 2) usedMultiplePointers = true
                    if (!pastSlop && (accumulated.getDistance() > viewConfiguration.touchSlop || zoomMotion > viewConfiguration.touchSlop)) pastSlop = true
                    if (pastSlop) {
                        // Lifting one finger after pinching must not suddenly rotate the graph.
                        if (!usedMultiplePointers || pointerCount >= 2) {
                            val centroid = event.calculateCentroid(useCurrent = false)
                            state.camera = moveRelationGraphCamera(
                                state.camera, state.spatial, pointerCount >= 2,
                                pan.x / size.width.coerceAtLeast(1), pan.y / size.height.coerceAtLeast(1), zoom,
                                centroid.x / size.width.coerceAtLeast(1) - 0.5f, centroid.y / size.height.coerceAtLeast(1) - 0.5f,
                            )
                        }
                        event.changes.forEach { if (it.position != it.previousPosition) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        } else Modifier
        Box(Modifier.fillMaxSize().then(gestureModifier)) {
            Canvas(Modifier.fillMaxSize()) {
                nodes.drop(1).forEach { node ->
                    val highlighted = activeId == center.id || activeId == node.character.id
                    drawLine(
                        color = if (highlighted) primary.copy(alpha = 0.4f + node.strength.coerceIn(1, 5) * 0.07f) else outline,
                        start = Offset(centerPosition.x, centerPosition.y), end = Offset(node.position.x, node.position.y),
                        strokeWidth = (1f + node.strength.coerceIn(1, 5) * 0.3f).dp.toPx(),
                    )
                }
            }
            nodes.sortedBy { it.position.depth }.forEach { node ->
                val visualSize = if (node.character.id == center.id) 56.dp else (44f * node.position.scale).dp
                val hitSize = maxOf(48.dp, visualSize)
                val halfHit = with(density) { hitSize.toPx() } / 2f
                val isSelected = activeId == node.character.id
                Box(
                    Modifier.offset { IntOffset((node.position.x - halfHit).roundToInt(), (node.position.y - halfHit).roundToInt()) }
                        .size(hitSize).zIndex(node.position.depth + 2f)
                        .then(if (isSelected) Modifier.border(2.dp, primary, MaterialTheme.shapes.small) else Modifier)
                        .semantics(mergeDescendants = true) {
                            selected = isSelected
                            contentDescription = listOf(node.character.displayName, node.role.takeIf(String::isNotBlank)).filterNotNull().joinToString("，")
                        }
                        .clickable(role = Role.Button, onClickLabel = "查看关系") { state.selectedId = node.character.id },
                    contentAlignment = Alignment.Center,
                ) {
                    CharacterPortrait(node.character, Modifier.size(visualSize))
                }
            }
            // Text is laid out in screen space and never rotates with the portrait plane.
            val labels = mutableListOf<GraphLabel>()
            val occupied = mutableListOf<Rect>()
            val nodeBounds = nodes.map { node ->
                val half = with(density) { maxOf(48.dp, (44f * node.position.scale).dp).toPx() } / 2f
                Rect(node.position.x - half, node.position.y - half, node.position.x + half, node.position.y + half)
            }
            nodes.sortedByDescending { if (it.character.id == activeId) 2 else if (it.character.id == center.id) 1 else 0 }.forEach { node ->
                val text: AnnotatedString = buildAnnotatedString {
                    append(node.character.displayName)
                    if (node.role.isNotBlank()) { append('\n'); withStyle(SpanStyle(color = primary)) { append(node.role) } }
                }
                val layout = textMeasurer.measure(text, style = labelStyle, maxLines = 2, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = with(density) { 92.dp.toPx() }.roundToInt()))
                val w = layout.size.width.toFloat()
                val h = layout.size.height.toFloat()
                val gap = with(density) { 6.dp.toPx() }
                val half = with(density) { maxOf(56.dp, (44f * node.position.scale).dp).toPx() } / 2f
                val candidates = listOf(
                    Offset(node.position.x - w / 2f, node.position.y + half + gap),
                    Offset(node.position.x - w / 2f, node.position.y - half - gap - h),
                    Offset(node.position.x + half + gap, node.position.y - h / 2f),
                    Offset(node.position.x - half - gap - w, node.position.y - h / 2f),
                )
                val placement = candidates.firstOrNull { candidate ->
                    val rect = Rect(candidate.x, candidate.y, candidate.x + w, candidate.y + h)
                    rect.left >= gap && rect.right <= width - gap && rect.top >= gap && rect.bottom <= height - gap &&
                        occupied.none { it.inflate(gap).overlaps(rect) } && nodeBounds.none { it.overlaps(rect) }
                }
                if (placement != null) {
                    labels += GraphLabel(layout, placement)
                    occupied += Rect(placement.x, placement.y, placement.x + w, placement.y + h)
                }
            }
            Canvas(Modifier.fillMaxSize().zIndex(10f)) {
                labels.forEach { drawText(it.text, topLeft = it.position) }
            }
        }
    }
}
