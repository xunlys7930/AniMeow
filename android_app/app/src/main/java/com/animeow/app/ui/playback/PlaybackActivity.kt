@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.animeow.app.ui.playback

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp
import androidx.core.view.*
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.playback.*
import com.animeow.app.data.remote.PlaybackWork
import com.animeow.app.ui.components.StableModalBottomSheet
import com.animeow.app.ui.preferences.AppearanceViewModel
import com.animeow.app.ui.theme.AniMeowTheme

class PlaybackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val animeId = intent.getLongExtra("animeId", 0)
        if (animeId < 0) { finish(); return }
        enableEdgeToEdge()
        setContent {
            val appearance: AppearanceViewModel = viewModel()
            val settings by appearance.settings.collectAsStateWithLifecycle()
            AniMeowTheme(settings) {
                if (intent.getBooleanExtra("history", false)) PlaybackHistoryScreen(::finish)
                else {
                    val model: PlaybackViewModel = viewModel { PlaybackViewModel(application, animeId, initialDownloadId = intent.getStringExtra("downloadId"), initialNextFrom = intent.getStringExtra("nextFromCheckpoint"), initialWatchJump = intent.getStringExtra("watchJump")) }
                    if (animeId == 0L) PlaybackDownloadLibrary(model, ::finish) else PlaybackScreen(model, ::finish)
                }
            }
        }
    }
    companion object {
        fun intent(context: android.content.Context, animeId: Long) = Intent(context, PlaybackActivity::class.java).putExtra("animeId", animeId)
        fun downloads(context: android.content.Context) = intent(context, 0)
        fun history(context: android.content.Context) = intent(context, 0).putExtra("history", true)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackScreen(model: PlaybackViewModel, onBack: () -> Unit) {
    PlaybackDisclaimer("watch")
    val state by model.state.collectAsStateWithLifecycle()
    ObservePlaybackDownloads(model, state.loaded)
    val cache = rememberCacheAction(model)
    val watchContext = androidx.compose.ui.platform.LocalContext.current
    val watch: WatchViewModel = viewModel { WatchViewModel(com.animeow.app.data.community.WatchService(watchContext), WatchPreferences(watchContext)) }
    WatchLifecycle(watch)
    LaunchedEffect(state.anime, state.session?.id) { watch.bind(state.anime, state.session) }
    @Composable fun WatchPanelContent(modifier: Modifier) {
        WatchInteractionPanel(watch, modifier, hasPlayback = true,
            onShare = { item ->
                val id = item.id.toLongOrNull()
                if (id == null) android.widget.Toast.makeText(watchContext, "该评论暂不支持分享到社区", android.widget.Toast.LENGTH_SHORT).show()
                else watchContext.startActivity(com.animeow.app.ui.community.WatchCommunityActivity.share(watchContext, id))
            },
            onPost = { watchContext.startActivity(com.animeow.app.ui.community.WatchCommunityActivity.post(watchContext, it)) },
            onSeek = { model.seekPlayback?.invoke((it + watch.state.value.settings.offsetMs).coerceAtLeast(0)) })
    }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var unlockSignal by remember { mutableIntStateOf(0) }
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var lastPanel by remember { mutableStateOf("settings") }
    SideEffect { panel?.let { lastPanel = it } }
    var pendingWork by remember { mutableStateOf<Pair<String, PlaybackWork>?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val activity = LocalActivity.current
    val originalLightStatusBars = remember(activity) { activity?.let { WindowCompat.getInsetsController(it.window, it.window.decorView).isAppearanceLightStatusBars } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val originalOrientation = remember(activity) { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    val latestFullScreen by rememberUpdatedState(fullScreen)
    val hasSession by rememberUpdatedState(state.session != null)
    val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    fun applyWindow() {
        activity?.let {
            val controller = WindowCompat.getInsetsController(it.window, it.window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.isAppearanceLightStatusBars = !latestFullScreen && !hasSession && lightSurface
            if (latestFullScreen) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(fullScreen, state.session != null) {
        activity?.requestedOrientation = if (fullScreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else originalOrientation
        if (fullScreen && state.settings.autoLock) locked = true
        applyWindow()
    }
    LaunchedEffect(state.session?.id) { if (state.session != null && panel == "episodes") panel = null }
    LaunchedEffect(state.openingTarget) { if (state.openingTarget != null) panel = null }
    LaunchedEffect(state.session) { if (state.session == null) { fullScreen = false; locked = false } }
    DisposableEffect(activity, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) applyWindow() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer); activity?.requestedOrientation = originalOrientation
            activity?.let {
                val controller = WindowCompat.getInsetsController(it.window, it.window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
                originalLightStatusBars?.let { original -> controller.isAppearanceLightStatusBars = original }
            }
        }
    }
    fun openSources() {
        if (state.openingTarget != null) model.cancelOpening()
        panel = "sources"
        if (state.sources.isEmpty()) model.loadSources() else if (state.searchGroups.isEmpty()) model.search()
    }
    LaunchedEffect(panel, state.sources) {
        if (panel == "sources" && state.sources.isNotEmpty() && state.searchGroups.isEmpty()) model.search()
    }
    fun back() { if (!locked) { if (fullScreen) fullScreen = false else onBack() } }
    BackHandler(panel != null && fullScreen) { panel = null }
    BackHandler(panel == null && (fullScreen || locked)) { if (locked) unlockSignal++ else fullScreen = false }
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            if (!fullScreen && state.session != null) Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Color.Black))
            // Layout changes keep this player in the same composition slot and preserve its session.
            Column(Modifier.fillMaxSize().then(if (fullScreen) Modifier else Modifier.statusBarsPadding().navigationBarsPadding())) {
                state.session?.let { session ->
                    PlaybackVideo(session, model, fullScreen, locked, { fullScreen = !fullScreen }, { locked = !locked }, ::back,
                        { panel = "episodes" }, { if (state.openingTarget != null) model.cancelOpening(); panel = "lines" }, { panel = "settings" },
                        if (fullScreen || locked) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        controlsBlocked = panel != null || pendingWork != null, unlockSignal = unlockSignal, watch = watch, interaction = { panel = "watch" })
                }
                if (state.session == null && state.openingTarget != null) PlaybackOpeningVideo(state, model,
                    { model.cancelOpening(); panel = "lines" }, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                if (!fullScreen && !locked) {
                    if (state.session == null && state.openingTarget == null) TopAppBar(title = { Text(if (state.work == null) "查找观看来源" else "选择集数") },
                        navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                        actions = { TextButton(onClick = { panel = "downloads" }) { Text("下载管理") } })
                    if (state.busy && state.openingTarget == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.session == null && state.openingTarget == null) state.error?.let { ErrorNotice(it, { if (state.work != null) model.reloadEpisodes() else model.loadSources() }, ::openSources) }
                    state.notice?.let { message -> Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        if (state.undo != null) TextButton(onClick = model::undo) { Text("撤销") }
                    } }
                    if (state.work == null) SourcePicker(state, model, { source, work -> pendingWork = source to work }, Modifier.weight(1f))
                    else {
                        PlaybackWorkHeader(state, { if (state.openingTarget != null) model.cancelOpening(); panel = "lines" }, ::openSources)
                        if (state.settings.layout == WatchingLayout.TABBED) {
                            PrimaryTabRow(selectedTabIndex = selectedTab) {
                                listOf("选集", "下载").forEachIndexed { index, title -> Tab(selectedTab == index, { selectedTab = index }, text = { Text(title) }) }
                            }
                        }
                        when {
                            state.settings.layout == WatchingLayout.TABBED && selectedTab == 1 ->
                                Column(Modifier.weight(1f)) {
                                    TextButton(onClick = { panel = "downloadSelection" }, enabled = !state.offlineOnly) { Text("下载集数") }
                                    PlaybackDownloadPanel(state, model, Modifier.weight(1f))
                                }
                            state.session != null && state.settings.layout == WatchingLayout.CONTINUE ->
                                ContinueWatchingContent(state, model, { panel = "episodes" }, { panel = "downloadSelection" }, Modifier.weight(1f))
                            else -> EpisodePicker(state, model, Modifier.weight(1f), cache, onDownload = { panel = "downloadSelection" }, compact = state.settings.layout == WatchingLayout.COMPACT)
                        }
                    }
                }
            }
            AnimatedVisibility(fullScreen && panel != null, modifier = Modifier.fillMaxSize(),
                enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.16f)).clickable { panel = null })
            }
            AnimatedVisibility(fullScreen && panel != null,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.46f),
                enter = slideInHorizontally(tween(260)) { it } + fadeIn(tween(180)),
                exit = slideOutHorizontally(tween(220)) { it } + fadeOut(tween(160))) {
                val panelColors = darkColorScheme(primary = MaterialTheme.colorScheme.inversePrimary,
                    surface = Color(0xFF151820).copy(alpha = 0.78f), onSurface = Color.White,
                    onSurfaceVariant = Color.White.copy(alpha = 0.78f), outlineVariant = Color.White.copy(alpha = 0.18f),
                    surfaceContainerLow = Color.White.copy(alpha = 0.07f))
                MaterialTheme(colorScheme = panelColors) {
                    Surface(Modifier.fillMaxSize(), color = panelColors.surface, contentColor = Color.White,
                        shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))) {
                        Column(Modifier.navigationBarsPadding().displayCutoutPadding()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { panel = null }) { Text("关闭面板") }
                            }
                            if (panel == "watch") WatchPanelContent(Modifier.weight(1f)) else PlaybackPanel(panel ?: lastPanel, state, model, cache, { panel = it }, ::openSources,
                                { source, work -> pendingWork = source to work }, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
    if (!fullScreen && panel != null) StableModalBottomSheet(onDismissRequest = { panel = null }) {
        if (panel == "watch") WatchPanelContent(Modifier.fillMaxWidth().heightIn(max = 600.dp)) else PlaybackPanel(panel!!, state, model, cache, { panel = it }, ::openSources,
            { source, work -> pendingWork = source to work }, Modifier.fillMaxWidth().heightIn(max = 600.dp))
        Spacer(Modifier.height(16.dp))
    }
    pendingWork?.let { (source, work) -> ConfirmPlaybackWork(state, source, work,
        dismiss = { pendingWork = null }, confirm = { pendingWork = null; panel = null; model.choose(source, work) }) }
}
