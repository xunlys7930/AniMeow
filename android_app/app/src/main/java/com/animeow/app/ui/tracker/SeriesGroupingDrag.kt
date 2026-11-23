package com.animeow.app.ui.tracker

import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.animeow.app.ui.components.StableModalBottomSheet
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.ui.components.LocalActionFeedback
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.anime.seriesBaseTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal fun canGroupDrop(source: AnimeEntity, target: LibraryDisplayItem): Boolean = when (target) {
    is LibraryDisplayItem.AnimeItem -> source.id != target.anime.id &&
        (target.anime.seriesId == null || source.seriesId != target.anime.seriesId)
    is LibraryDisplayItem.SeriesItem -> source.seriesId != target.series.id && source.id !in target.memberAnimeIds
}

internal data class SeriesDropArea(val item: LibraryDisplayItem, val bounds: Rect, val select: () -> Unit)

internal class SeriesGroupingDragState {
    val bounds = mutableMapOf<Any, SeriesDropArea>()
    var source by mutableStateOf<AnimeEntity?>(null)
    var position by mutableStateOf(Offset.Zero)
    var target by mutableStateOf<LibraryDisplayItem?>(null)
    var pending by mutableStateOf<Pair<AnimeEntity, LibraryDisplayItem>?>(null)
    var pickingSource by mutableStateOf<AnimeEntity?>(null)
    var viewport by mutableStateOf(Rect.Zero)
    fun move(point: Offset) {
        position = point
        target = source?.let { anime -> bounds.values.lastOrNull { it.bounds.contains(point) && canGroupDrop(anime, it.item) }?.item }
    }
    fun finish(commit: Boolean) {
        if (commit) source?.let { anime -> target?.let { pending = anime to it } }
        source = null
        target = null
    }
}

internal val LocalSeriesGroupingDrag = staticCompositionLocalOf<SeriesGroupingDragState?> { null }

/** Keep click semantics for accessibility; the pointer path distinguishes hold/release from hold/drag. */
@Composable
internal fun Modifier.libraryItemClick(item: LibraryDisplayItem, onLongClick: () -> Unit, onClick: () -> Unit): Modifier {
    val drag = LocalSeriesGroupingDrag.current ?: return animatedPressClick(onLongClick = onLongClick, onClick = onClick)
    val select by rememberUpdatedState(onLongClick)
    val token = remember { Any() }
    DisposableEffect(drag, token) { onDispose { drag.bounds.remove(token) } }
    val highlighted = drag.target?.stableKey == item.stableKey
    return this.onGloballyPositioned {
        drag.bounds[token] = SeriesDropArea(item, it.boundsInRoot(), select)
    }.then(if (highlighted) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier)
        .graphicsLayer { alpha = if (drag.source?.id in item.memberAnimeIds) 0.45f else 1f }
        .semantics {
            onLongClick("选择作品；也可长按后拖到作品或系列归组") { select(); true }
            if (item is LibraryDisplayItem.AnimeItem) customActions = listOf(CustomAccessibilityAction("归入系列") { drag.pickingSource = item.anime; true })
        }
        .animatedPressClick(onClick = onClick)
}

@Composable
internal fun SeriesDragAutoScroll(state: ScrollableState) {
    val drag = LocalSeriesGroupingDrag.current
    val edge = with(LocalDensity.current) { 72.dp.toPx() }
    val speed = with(LocalDensity.current) { 8.dp.toPx() }
    LaunchedEffect(drag, state, edge, speed) {
        if (drag == null) return@LaunchedEffect
        snapshotFlow { drag.source != null }.collect { active ->
            if (active) while (drag.source != null) {
                val delta = when {
                    drag.position.y < drag.viewport.top + edge -> -speed
                    drag.position.y > drag.viewport.bottom - edge -> speed
                    else -> 0f
                }
                if (delta != 0f) { state.scrollBy(delta); drag.move(drag.position) }
                delay(16)
            }
        }
    }
}

@Composable
internal fun SeriesGroupingHost(onConfirm: suspend (AnimeEntity, LibraryDisplayItem, String) -> Unit, onGrouped: () -> Unit,
    targets: List<LibraryDisplayItem> = emptyList(), content: @Composable () -> Unit) {
    val drag = remember { SeriesGroupingDragState() }
    val scope = rememberCoroutineScope()
    val feedback = LocalActionFeedback.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(drag.pending) { name = drag.pending?.second?.title?.let(::seriesBaseTitle).orEmpty().take(100); error = null }
    CompositionLocalProvider(LocalSeriesGroupingDrag provides drag) {
        Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot(); drag.viewport = it.boundsInRoot() }
            .pointerInput(drag) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val area = drag.bounds.values.lastOrNull { it.bounds.contains(down.position + origin) } ?: return@awaitEachGesture
                    val held = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    feedback.longPress()
                    var moved = false
                    var released = false
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == held.id } ?: break
                            if (event.changes.size > 1 || change.isConsumed) break
                            if (!moved && (change.position - held.position).getDistance() > viewConfiguration.touchSlop && area.item is LibraryDisplayItem.AnimeItem) {
                                moved = true
                                drag.source = area.item.anime
                            }
                            if (moved) drag.move(change.position + origin)
                            change.consume()
                            if (!change.pressed) { released = true; break }
                        } while (true)
                        if (!moved && released) area.select()
                    } finally { if (moved) drag.finish(released) }
                }
            }) {
            content()
            val source = drag.source
            if (source != null) {
                Surface(Modifier.offset { IntOffset((drag.position.x - origin.x - 100).roundToInt().coerceAtLeast(0),
                    (drag.position.y - origin.y - 130).roundToInt().coerceAtLeast(0)) }.widthIn(max = 240.dp),
                    shape = RoundedCornerShape(16.dp), tonalElevation = 8.dp, shadowElevation = 8.dp) {
                    Column(Modifier.padding(12.dp)) {
                        Text(source.title, maxLines = 2, style = MaterialTheme.typography.titleSmall)
                        Text(drag.target?.let { "松手归入：${it.title}" } ?: "拖到另一部作品或已有系列", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    drag.pickingSource?.let { source ->
        StableModalBottomSheet(onDismissRequest = { drag.pickingSource = null }) {
            Text("选择作品或系列", Modifier.padding(20.dp), style = MaterialTheme.typography.headlineSmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).navigationBarsPadding()) {
                val choices = targets.filter { canGroupDrop(source, it) }
                if (choices.isEmpty()) item { Text("当前列表没有其他目标，请先清除筛选或添加作品。", Modifier.padding(20.dp)) }
                items(choices, key = { it.stableKey }) { target ->
                    TextButton(onClick = { drag.pickingSource = null; drag.pending = source to target }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(target.title)
                    }
                }
            }
        }
    }
    drag.pending?.let { (source, target) ->
        val existing = target is LibraryDisplayItem.SeriesItem || (target as? LibraryDisplayItem.AnimeItem)?.anime?.seriesId != null
        StableModalBottomSheet(onDismissRequest = { if (!busy) drag.pending = null }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (existing) "移入系列" else "组成新系列", style = MaterialTheme.typography.headlineSmall)
                Text(if (existing) "将《${source.title}》移入${if (target is LibraryDisplayItem.SeriesItem) "系列「${target.title}」" else "《${target.title}》所属系列"}。"
                    else "将《${source.title}》与《${target.title}》归入同一系列。")
                if (source.seriesId != null) Text("这部作品会从原系列移出，原系列和其他成员保留。", style = MaterialTheme.typography.bodySmall)
                if (!existing) OutlinedTextField(name, { name = it.take(100); error = null }, label = { Text("系列名称") }, enabled = !busy, singleLine = true)
                Text("保留作品资料、进度、评分与标签。", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && (existing || name.isNotBlank()), onClick = {
                    busy = true
                    scope.launch {
                        try { onConfirm(source, target, name); drag.pending = null; onGrouped() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "归组失败，请重试" }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "正在归组…" else "确认归组") }
                TextButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = { drag.pending = null }) { Text("取消") }
            }
        }
    }
}
