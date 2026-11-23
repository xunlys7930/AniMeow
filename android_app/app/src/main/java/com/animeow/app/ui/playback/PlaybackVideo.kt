package com.animeow.app.ui.playback

import android.media.AudioManager
import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.icons.IconGlyph
import androidx.media3.ui.R as MediaR
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.animeow.app.data.playback.playbackSeekPosition
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation

internal fun playbackTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(seconds / 60, seconds % 60)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun PlaybackVideo(session: PlaybackSession, model: PlaybackViewModel, fullScreen: Boolean, locked: Boolean,
    toggleFullScreen: () -> Unit, toggleLock: () -> Unit, back: () -> Unit, episodes: () -> Unit,
    sources: () -> Unit, settings: () -> Unit, modifier: Modifier = Modifier, controlsBlocked: Boolean = false, unlockSignal: Int = 0, watch: WatchViewModel? = null, interaction: () -> Unit = {}) {
    val activity = LocalActivity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by model.state.collectAsState()
    val watchState by watch?.state?.collectAsState() ?: remember { mutableStateOf(WatchUiState()) }
    val latestSession by rememberUpdatedState(session)
    val latestSettings by rememberUpdatedState(state.settings)
    val http = remember { DefaultHttpDataSource.Factory().setConnectTimeoutMs(15000).setReadTimeoutMs(20000) }
    // Keep one player at this composition position; fullscreen and episode sheets only change its presentation.
    val player = remember(model) {
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
        }
    }
    var error by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0) }
    var duration by remember { mutableLongStateOf(0) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var ended by remember { mutableStateOf(false) }
    var seekCancelled by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var previewTarget by remember { mutableLongStateOf(0) }
    var previewLoading by remember { mutableStateOf(false) }
    val previewReader = remember(session.id) { PlaybackPreview(context.applicationContext, session) }
    DisposableEffect(previewReader) { onDispose { previewReader.close() } }
    var feedback by remember { mutableStateOf<String?>(null) }
    var playerSession by remember { mutableLongStateOf(0) }
    var upscaleFallback by remember { mutableStateOf<String?>(null) }
    fun sample() { if (playerSession == latestSession.id) model.sample(latestSession.id, player.currentPosition, player.duration.takeIf { it != C.TIME_UNSET } ?: 0,
        player.isPlaying, player.playbackParameters.speed) }
    LaunchedEffect(session.id) {
        error = null; visible = true; buffering = true; ended = false; dragging = null
        model.status("正在准备")
        player.stop()
        playerSession = session.id
        val dataSource = com.animeow.app.data.playback.PlaybackDownloads.get(context).playbackFactory(session.stream.headers, session.offline, session.downloadId)
        player.setMediaSource(DefaultMediaSourceFactory(dataSource).createMediaSource(MediaItem.Builder().setUri(session.stream.url).setMimeType(session.stream.mimeType).build()))
        val saved = model.checkpointFor(session.id) ?: session.checkpoint
        val watchSeek = model.consumeWatchJump(session)
        val start = playbackStart(saved, watchSeek, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        player.seekTo(start.positionMs)
        player.setPlaybackSpeed(state.settings.speed)
        player.prepare(); player.playWhenReady = start.autoplay
    }
    DisposableEffect(player, lifecycle) {
        model.stopPlayback = { sample(); player.pause(); player.stop() }
        model.pausePlayback = { sample(); player.pause() }
        model.seekPlayback = { time -> player.pause(); player.seekTo(time.coerceIn(0, player.duration.takeIf { it > 0 } ?: 86400000)); visible = true }
        val listener = object : Player.Listener {
            override fun onPlayerError(failure: PlaybackException) {
                if (latestSettings.upscale != com.animeow.app.data.playback.PlaybackUpscale.OFF &&
                    failure.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED) {
                    val causes = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
                    val detail = causes.joinToString(" ") { it.javaClass.simpleName + " " + it.message.orEmpty() }
                    val reason = when {
                        causes.any { it is OutOfMemoryError } || detail.contains("OUT_OF_MEMORY", true) -> "设备显存不足"
                        detail.contains("compile", true) || detail.contains("link", true) -> "当前设备暂不支持该画质增强效果"
                        else -> "画质增强启动失败"
                    }
                    upscaleFallback = "$reason，已自动切回原画播放"
                    model.settings(latestSettings.copy(upscale = com.animeow.app.data.playback.PlaybackUpscale.OFF))
                    player.setVideoEffects(emptyList()); player.prepare()
                    feedback = upscaleFallback
                    return
                }
                error = when (failure.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "视频连接失败，请检查网络后重试"
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "视频地址失效或来源拒绝连接，请重新解析或换线路"
                    PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "设备无法解码此视频，请换线路"
                    else -> if (latestSession.offline) "本地视频数据不完整或无法读取，请查看下载" else "视频暂时无法播放，请重新解析或换线路"
                }; visible = true; buffering = false; model.status("打开失败")
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                model.discontinuity(latestSession.id)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) model.status("播放中") else if (!buffering && !ended && error == null) model.status("已暂停")
                if (isPlaying) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                sample()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                ended = playbackState == Player.STATE_ENDED
                if (buffering) model.status("正在缓冲")
                if (playbackState == Player.STATE_ENDED) {
                    model.status("已结束")
                    sample(); visible = true
                    model.ended(latestSession.id, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { dragging = null; seekCancelled = false; sample(); model.flush(); player.pause() }
        }
        player.addListener(listener); lifecycle.addObserver(observer)
        val originalBrightness = activity?.window?.attributes?.screenBrightness
        onDispose {
            model.stopPlayback = null
            model.pausePlayback = null
            model.seekPlayback = null
            sample(); model.flush(); lifecycle.removeObserver(observer); player.removeListener(listener); player.release()
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (originalBrightness != null) activity.window.attributes = activity.window.attributes.apply { screenBrightness = originalBrightness }
        }
    }
    LaunchedEffect(state.settings.speed) { player.setPlaybackSpeed(state.settings.speed) }
    LaunchedEffect(state.settings.crop) { resizeMode = if (state.settings.crop) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT }
    LaunchedEffect(state.settings.upscale, state.settings.aiQuality, session.id) {
        val mode = state.settings.upscale
        val quality = state.settings.aiQuality
        if (mode == com.animeow.app.data.playback.PlaybackUpscale.OFF) {
            player.setVideoEffects(emptyList()); model.upscaleStatus(upscaleFallback ?: "原画输出")
        } else {
            upscaleFallback = null
            val pending = "正在开启${if (mode == com.animeow.app.data.playback.PlaybackUpscale.AI) " AI 超清放大" else "清晰锐化放大"}，画面加载后生效…"
            model.upscaleStatus(pending)
            val report: (String) -> Unit = { message ->
                if (model.state.value.session?.id == session.id && model.state.value.settings.upscale == mode &&
                    model.state.value.settings.aiQuality == quality) model.upscaleStatus(message)
            }
            player.setVideoEffects(if (mode == com.animeow.app.data.playback.PlaybackUpscale.AI) listOf(
                Anime4kModel("restore-${quality.model}", "AI ${quality.title}修复") {},
                Anime4kModel("upscale-${quality.model}", "AI ${quality.title} · 修复与 2× 放大", report)
            ) else listOf(SpatialUpscale(report)))
            // A paused decoder also needs to output a frame to configure the new GPU pipeline.
            if (!player.playWhenReady && duration > 0) player.seekTo(player.currentPosition)
            delay(7000)
            if (model.state.value.upscaleStatus == pending) model.upscaleStatus(
                "等待视频画面加载（${model.state.value.playbackStatus}），开始播放后自动生效；若一直黑屏可换线路重试。")
        }
    }
    LaunchedEffect(locked, fullScreen, session.id, controlsBlocked, unlockSignal) { dragging = null; seekCancelled = false; visible = true }
    // A paused player keeps a black surface after its view is resized; re-seek so the frame is drawn again.
    LaunchedEffect(locked, fullScreen) {
        delay(150)
        if (duration > 0 && !player.isPlaying) player.seekTo(player.currentPosition)
    }
    LaunchedEffect(dragging != null, session.id) {
        preview = null
        previewTarget = 0
        if (dragging == null) return@LaunchedEffect
        try {
            var attempts = 0
            previewLoading = true; delay(250)
            while (dragging != null) {
                // Read the newest target after each extraction instead of queuing every pointer move.
                val target = (dragging!! * duration).toLong()
                // Two misses per drag settle on "unavailable" instead of looping on a stuck extractor.
                if (attempts >= 2) { previewLoading = false; delay(350); continue }
                attempts++; previewLoading = true
                val frame = previewReader.frame(target)
                previewTarget = target; preview = frame; previewLoading = false
                delay(350)
            }
        } finally { previewLoading = false }
    }
    LaunchedEffect(player) { while (true) {
        position = player.currentPosition.coerceAtLeast(0); duration = player.duration.takeIf { it > 0 } ?: 0
        sample(); watch?.sample(position); delay(500)
    } }
    LaunchedEffect(visible, playing, dragging, error, locked) {
        if (visible && (playing || locked) && dragging == null && error == null) { delay(3500); visible = false }
    }
    LaunchedEffect(feedback) { if (feedback != null) { delay(1200); feedback = null } }
    fun seek(delta: Long) {
        player.seekTo(playbackSeekPosition(player.currentPosition, player.duration, delta))
        feedback = if (delta > 0) "快进 ${delta / 1000} 秒" else "后退 ${-delta / 1000} 秒"
    }
    Box(modifier.background(Color.Black)) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = false } },
            update = { it.resizeMode = resizeMode }, modifier = Modifier.fillMaxSize(), onRelease = { it.player = null })
        if (state.openingTarget == null) WatchDanmakuOverlay(watchState.danmaku, watchState.settings, position, playing, { player.currentPosition }, Modifier.fillMaxSize())
        if (state.openingTarget != null) {
            PlaybackOpeningVideo(state, model, sources, Modifier.fillMaxSize())
            return@Box
        }
        Box(Modifier.fillMaxSize().pointerInput(locked, controlsBlocked) {
            if (!controlsBlocked) detectTapGestures(onTap = { visible = if (locked) true else !visible }, onDoubleTap = {
                if (!locked && error == null) { if (player.isPlaying) player.pause() else player.play(); visible = true }
            })
        }.pointerInput(locked, controlsBlocked) {
            if (!locked && !controlsBlocked) awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (awaitLongPressOrCancellation(down.id) != null) {
                    player.setPlaybackSpeed(2f); feedback = "临时 2×"
                    try { waitForUpOrCancellation() } finally { player.setPlaybackSpeed(state.settings.speed); feedback = null }
                }
            }
        }.pointerInput(locked, fullScreen, duration, session.id, controlsBlocked) {
            if (!locked && !controlsBlocked) {
                var total = 0f; var dx = 0f; var dy = 0f; var axis = 0; var startY = 0f; var startPosition = 0L
                var left = false; var starting = 0f
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                detectDragGestures(onDragEnd = {
                    if (axis == 1 && !seekCancelled) dragging?.let { player.seekTo((it * duration).toLong()); model.discontinuity(session.id) }
                    dragging = null; seekCancelled = false
                }, onDragCancel = { dragging = null; seekCancelled = false }, onDragStart = { offset ->
                    dx = 0f; dy = 0f; axis = 0; startY = offset.y; startPosition = player.currentPosition
                    total = 0f; left = offset.x < size.width / 2
                    starting = if (left) activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f
                    else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maximum
                }) { change, amount ->
                    dx += amount.x; dy += amount.y
                    if (axis == 0) axis = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) 1 else 2
                    if (axis == 1 && duration > 0) {
                        change.consume()
                        dragging = playbackDragTarget(startPosition, dx, size.width, duration).toFloat() / duration
                        seekCancelled = startY + dy < size.height * 0.24f
                    } else if (axis == 2 && fullScreen) {
                        change.consume(); total -= amount.y / size.height
                        val value = (starting + total * 2).coerceIn(0f, 1f)
                        if (left) activity?.window?.let { window -> window.attributes = window.attributes.apply { screenBrightness = value.coerceAtLeast(0.01f) } }
                        else audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value * maximum).toInt(), 0)
                        feedback = "${if (left) "亮度" else "音量"} ${(value * 100).toInt()}%"
                    }
                }
            }
        })
        if (buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp), color = Color.White)
        if (feedback != null) Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.72f),
            contentColor = Color.White,
            modifier = Modifier.align(Alignment.Center),
        ) {
            Text(feedback!!, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
        }
        if (!controlsBlocked && (visible || !locked && (error != null || !playing)) && dragging == null && (error == null || locked) && (!ended || locked)) {
            PlaybackOverlay("${state.anime?.title ?: session.work.title} · ${session.episode.label}", position, duration, playing, locked, fullScreen, dragging,
                togglePlay = { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play() } },
                seekBack = { seek(-state.settings.skipSeconds * 1000L) }, seekForward = { seek(state.settings.skipSeconds * 1000L) },
                toggleFullScreen = toggleFullScreen, toggleLock = toggleLock, back = back, episodes = episodes,
                sources = sources, settings = settings, next = model::nextEpisode,
                hasNext = !state.busy && session.source == state.source && session.work.id == state.work?.id && com.animeow.app.data.playback.nextPlaybackEpisode(session.episode, state.lines) != null,
                ratio = { model.settings(state.settings.copy(crop = !state.settings.crop)); resizeMode = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    feedback = if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) "适应画面" else "裁剪填充" },
                drag = { dragging = it }, dragFinished = { dragging?.let { player.seekTo((it * duration).toLong()) }; dragging = null }, speed = state.settings.speed, interaction = interaction)
        }
        if (!locked && dragging != null) {
            val target = (dragging!! * duration).toLong()
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                color = if (seekCancelled) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.72f),
                contentColor = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            ) {
                Text(if (seekCancelled) "松手取消" else "取消跳转", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
            }
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.82f),
                contentColor = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    preview?.takeIf { kotlin.math.abs(previewTarget - target) < 5000 }?.let {
                        Image(it.asImageBitmap(), "近似目标画面", Modifier.width(160.dp).heightIn(max = 100.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                        Text("近似画面", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                    } ?: Text(if (previewLoading) "正在读取预览…" else "当前片段暂未取得预览", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                    Text("${playbackTime(target)} / ${playbackTime(duration)}", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    val delta = target - position
                    Text("${if (delta >= 0) "前进" else "后退"} ${playbackTime(kotlin.math.abs(delta))}", color = Color.White)
                    Text(if (seekCancelled) "松手取消" else "松手跳转 · 上滑取消", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (!locked && ended && error == null) Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            color = Color(0xDD17191F),
            contentColor = Color.White,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            modifier = Modifier.align(Alignment.Center).padding(16.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("本集播放结束", style = MaterialTheme.typography.titleSmall)
                val hasNext = com.animeow.app.data.playback.nextPlaybackEpisode(session.episode, state.lines) != null
                if (!hasNext) Text("当前线路暂无连续下一集", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
                Row {
                    if (hasNext) TextButton(onClick = model::nextEpisode) { Text("下一集") }
                    TextButton(onClick = { player.seekTo(0); player.play() }) { Text("重播") }
                    TextButton(onClick = episodes) { Text("选集") }
                    TextButton(onClick = back) { Text("返回") }
                }
            }
        }
        if (!locked) error?.let { message -> Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            color = Color(0xEE17191F),
            contentColor = Color.White,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(message)
                Row {
                    TextButton(onClick = if (session.offline) sources else model::retry, enabled = !state.busy) { Text(if (session.offline) "查看下载" else "重新解析") }
                    if (!state.offlineOnly) TextButton(onClick = sources) { Text("换线路") }
                    TextButton(onClick = back) { Text("返回") }
                }
            }
        } }
        if (!locked && error == null) state.error?.let { message -> Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            color = Color(0xEE17191F),
            contentColor = Color.White,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            modifier = Modifier.align(Alignment.Center).padding(24.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(message)
                Text("当前播放已保留", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
                Row { TextButton(onClick = { model.dismissError(); sources() }) { Text("选择其他来源") }; TextButton(onClick = model::dismissError) { Text("关闭") } }
            }
        } }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PlaybackOverlay(title: String, position: Long, duration: Long, playing: Boolean, locked: Boolean,
    fullScreen: Boolean, dragging: Float?, togglePlay: () -> Unit, seekBack: () -> Unit, seekForward: () -> Unit,
    toggleFullScreen: () -> Unit, toggleLock: () -> Unit, back: () -> Unit, episodes: () -> Unit,
    sources: () -> Unit, settings: () -> Unit, next: () -> Unit, hasNext: Boolean, ratio: () -> Unit,
    drag: (Float) -> Unit, dragFinished: () -> Unit, speed: Float = 1f, interaction: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().then(if (fullScreen) Modifier.displayCutoutPadding().navigationBarsPadding() else Modifier)) {
        if (locked) {
            Surface(
                onClick = toggleLock,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.58f),
                contentColor = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp).heightIn(min = 48.dp).semantics { contentDescription = "解锁触控" },
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    com.animeow.app.ui.icons.AppIcon(AppIcons.Unlock, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text("解锁触控", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().height(112.dp).align(Alignment.TopCenter)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent))))
            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerIcon(AppIcons.Back, if (fullScreen) "退出全屏" else "返回详情", back)
                Text(title, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                TextButton(onClick = interaction) { Text("弹幕／评论", color = Color.White) }
                PlayerIcon(AppIcons.Lock, "锁定触控", toggleLock)
                PlayerIcon(MediaR.drawable.exo_ic_settings, "播放设置", settings)
            }
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerIcon(MediaR.drawable.exo_icon_rewind, "后退", seekBack)
                PlayerIcon(if (playing) MediaR.drawable.exo_icon_pause else MediaR.drawable.exo_icon_play, if (playing) "暂停" else "播放", togglePlay, Modifier.size(64.dp))
                PlayerIcon(MediaR.drawable.exo_icon_fastforward, "快进", seekForward)
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(playbackTime(dragging?.let { (it * duration).toLong() } ?: position), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Slider(value = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = drag, onValueChangeFinished = dragFinished, enabled = duration > 0,
                        thumb = { Box(Modifier.size(10.dp).background(Color.White, androidx.compose.foundation.shape.CircleShape)) },
                        track = { sliderState -> SliderDefaults.Track(sliderState, Modifier.height(3.dp),
                            colors = SliderDefaults.colors(activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.35f)),
                            thumbTrackGapSize = 0.dp, drawStopIndicator = null) },
                        colors = SliderDefaults.colors(activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.35f)),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(32.dp).semantics { contentDescription = "播放进度" })
                    Text(playbackTime(duration), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PlayerIcon(MediaR.drawable.exo_icon_next, "下一集", next, enabled = hasNext)
                    TextButton(onClick = episodes) { Text("选集", color = Color.White) }
                    TextButton(onClick = sources) { Text("线路", color = Color.White) }
                    TextButton(onClick = settings) { Text("${speed}×", color = Color.White, maxLines = 1) }
                    Spacer(Modifier.weight(1f))
                    if (fullScreen) PlayerIcon(AppIcons.Crop, "切换画面比例", ratio)
                    PlayerIcon(if (fullScreen) MediaR.drawable.exo_icon_fullscreen_exit else MediaR.drawable.exo_icon_fullscreen_enter, if (fullScreen) "退出全屏" else "全屏", toggleFullScreen)
                }
            }
        }
    }
}

@Composable
private fun PlayerIcon(icon: Int, label: String, action: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    IconButton(onClick = action, enabled = enabled, modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
        Icon(painterResource(icon), label, tint = if (enabled) Color.White else Color.Gray)
    }
}

@Composable
private fun PlayerIcon(icon: IconGlyph, label: String, action: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = action, modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
        com.animeow.app.ui.icons.AppIcon(icon, label, tint = Color.White)
    }
}
