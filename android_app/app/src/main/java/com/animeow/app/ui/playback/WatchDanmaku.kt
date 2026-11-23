package com.animeow.app.ui.playback

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.animeow.app.data.community.WatchItem
import kotlinx.coroutines.isActive

internal data class DanmakuLane(val item: WatchItem, val lane: Int, val width: Float, val speed: Float, val duration: Long)
internal fun danmakuLanes(items: List<WatchItem>, viewport: Float, lanes: Int, speed: Float, measure: (WatchItem) -> Float): List<DanmakuLane> {
    if (viewport <= 0 || lanes <= 0 || speed <= 0) return emptyList()
    val next = LongArray(lanes)
    val free = LongArray(lanes)
    return buildList {
        for (item in items.filter { it.timeMs != null && !it.unavailable && !it.spoiler }.sortedWith(compareBy({ it.timeMs }, { it.id }))) {
            val time = requireNotNull(item.timeMs)
            val available = when (item.mode) {
                5 -> listOf(0)
                4 -> listOf(lanes - 1)
                else -> if (lanes >= 3) (1 until lanes - 1).toList() else (0 until lanes).toList()
            }
            val lane = available.firstOrNull { next[it] <= time && (item.mode == 1 || free[it] <= time) } ?: continue
            val width = measure(item)
            val duration = if (item.mode == 1) ((viewport + width) / speed * 1000).toLong() else 4000L
            next[lane] = time + if (item.mode == 1) ((width + 24) / speed * 1000).toLong() else duration
            free[lane] = time + duration
            add(DanmakuLane(item, lane, width, speed, duration))
        }
    }
}

@Composable
internal fun WatchDanmakuOverlay(items: List<WatchItem>, settings: DanmakuSettings, observedPosition: Long,
    playing: Boolean, position: () -> Long, modifier: Modifier = Modifier) {
    if (!settings.enabled || items.isEmpty()) return
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var frame by remember { mutableLongStateOf(0) }
    val latestPosition by rememberUpdatedState(position)
    LaunchedEffect(playing, lifecycle) {
        if (playing && lifecycle == Lifecycle.State.RESUMED) while (isActive) withFrameNanos { frame = it }
    }
    val measurer = rememberTextMeasurer()
    val font = MaterialTheme.typography.bodyMedium.fontFamily
    val style = TextStyle(fontSize = settings.size.sp, fontFamily = font, shadow = Shadow(Color.Black, blurRadius = 3f))
    val layouts = remember(items, style, settings.opacity) { items.associate { item -> item.id to measurer.measure(
        text = item.content.replace('\n', ' ').take(80) + if (item.content.length > 80) "…" else "", style = style.copy(color = Color(0xff000000 or item.color.toLong()).copy(alpha = settings.opacity)), maxLines = 1, softWrap = false) } }
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth.toFloat()
        val rowHeight = with(density) { (settings.size * 1.5f).sp.toPx() }
        val count = minOf(settings.density, (constraints.maxHeight * settings.area / rowHeight).toInt()).coerceAtLeast(1)
        val lanes = remember(items, layouts, width, count, density) { danmakuLanes(items, width, count, 110f * density.density) { layouts.getValue(it.id).size.width.toFloat() } }
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val drawClock = frame
            val time = (if (playing) latestPosition() else observedPosition) - settings.offsetMs
            for (row in lanes) {
                val elapsed = time - requireNotNull(row.item.timeMs)
                if (elapsed < 0 || elapsed >= row.duration) continue
                val x = if (row.item.mode == 1) width - elapsed / 1000f * row.speed else (width - row.width) / 2
                drawText(layouts.getValue(row.item.id), topLeft = androidx.compose.ui.geometry.Offset(x, row.lane * rowHeight))
            }
        }
    }
}
