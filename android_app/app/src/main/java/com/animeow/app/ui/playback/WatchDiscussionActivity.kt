package com.animeow.app.ui.playback

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.community.WatchService
import com.animeow.app.ui.community.WatchCommunityActivity
import com.animeow.app.ui.preferences.AppearanceViewModel
import com.animeow.app.ui.theme.AniMeowTheme

@Composable
internal fun WatchLifecycle(model: WatchViewModel) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(model, owner) {
        val observer = LifecycleEventObserver { _, _ -> model.setActive(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        owner.lifecycle.addObserver(observer)
        model.setActive(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { owner.lifecycle.removeObserver(observer); model.setActive(false) }
    }
}

class WatchDiscussionActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val subject = intent.getIntExtra("subject", 0); val episode = intent.getIntExtra("episode", -1)
        val library = intent.getLongExtra("library", 0)
        if ((!intent.getBooleanExtra("admin", false) && library <= 0 && subject <= 0) || episode < 0) { finish(); return }
        val focus = intent.getLongExtra("focus", 0).takeIf { it > 0 }
        enableEdgeToEdge()
        setContent {
            val appearance: AppearanceViewModel = viewModel()
            val settings by appearance.settings.collectAsStateWithLifecycle()
            val model: WatchViewModel = viewModel { WatchViewModel(WatchService(this@WatchDiscussionActivity), WatchPreferences(this@WatchDiscussionActivity)) }
            WatchLifecycle(model)
            LaunchedEffect(subject, episode, focus, library) {
                if (library > 0) {
                    val anime = (application as com.animeow.app.AniMeowApplication).database.libraryDao().getAnime(library)
                    if (anime == null || anime.deletedAt != null || com.animeow.app.data.local.normalizeSubjectType(anime.subjectType) != "anime") { finish() }
                    else model.bindLibrary(anime)
                } else if (subject > 0) model.bindDiscussion(subject, episode, focus)
            }
            AniMeowTheme(settings) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("作品讨论") },
                            navigationIcon = {
                                IconButton(onClick = ::finish) {
                                    com.animeow.app.ui.icons.AppIcon(com.animeow.app.ui.icons.AppIcons.Back, contentDescription = "返回")
                                }
                            },
                        )
                    },
                ) { padding ->
                    WatchInteractionPanel(model, Modifier.padding(padding).fillMaxSize().navigationBarsPadding(),
                        onShare = { item ->
                            val id = item.id.toLongOrNull()
                            if (id == null) Toast.makeText(this, "该评论暂不支持分享到社区", Toast.LENGTH_SHORT).show()
                            else startActivity(WatchCommunityActivity.share(this, id))
                        },
                        onPost = { startActivity(WatchCommunityActivity.post(this, it)) },
                        onSeek = { time -> model.state.value.program?.let { openWatchTimestamp(this, it.subjectId, it.episodeId, time) } }, initialTab = if (intent.getBooleanExtra("admin", false)) "管理" else "讨论")
                }
            }
        }
    }
    companion object {
        fun admin(context: Context) = Intent(context, WatchDiscussionActivity::class.java).putExtra("admin", true).putExtra("episode", 0)
        fun library(context: Context, id: Long) = Intent(context, WatchDiscussionActivity::class.java).putExtra("library", id).putExtra("episode", 0)
        fun intent(context: Context, subject: Int, episode: Int, focus: Long? = null) = Intent(context, WatchDiscussionActivity::class.java)
            .putExtra("subject", subject).putExtra("episode", episode).putExtra("focus", focus ?: 0)
    }
}

internal fun openWatchTimestamp(context: Context, subject: Int, episode: Int, time: Long) {
    val link = WatchPreferences(context).playbackLink(subject, episode)
    if (link == null) Toast.makeText(context, "请先在本机为这一集确认播放来源，再定位时间点", Toast.LENGTH_LONG).show()
    else context.startActivity(PlaybackActivity.intent(context, link.getLong("anime_id")).putExtra("watchJump", link.put("time", time).toString()))
}
