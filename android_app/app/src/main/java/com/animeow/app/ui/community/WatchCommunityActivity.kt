package com.animeow.app.ui.community

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.animeow.app.ui.character.CharacterCommunityScreen
import com.animeow.app.ui.cloud.CloudAccountScreen
import com.animeow.app.ui.components.motionExpandIn
import com.animeow.app.ui.components.motionShrinkOut
import com.animeow.app.ui.components.pageForwardEnterTransition
import com.animeow.app.ui.components.pageForwardExitTransition
import com.animeow.app.ui.components.pagePopEnterTransition
import com.animeow.app.ui.components.pagePopExitTransition
import com.animeow.app.ui.components.scaledMotionDurationMillis
import com.animeow.app.ui.navigation.*
import com.animeow.app.ui.preferences.AppearanceViewModel
import com.animeow.app.ui.theme.AniMeowTheme
import com.animeow.app.ui.theme.MotionLevel

/** Reuses the existing composer, post details and account screens; opening a share never publishes. */
class WatchCommunityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val post = intent.getLongExtra("post", 0)
        val share = intent.getLongExtra("share", 0)
        if (post <= 0 && share <= 0) { finish(); return }
        enableEdgeToEdge()
        setContent {
            val appearance: AppearanceViewModel = viewModel()
            val settings by appearance.settings.collectAsStateWithLifecycle()
            val nav = rememberNavController()
            val motionEnabled = settings.motionLevel != MotionLevel.NONE
            val reducedMotion = settings.motionLevel == MotionLevel.REDUCED
            val pageDuration = scaledMotionDurationMillis(300, settings.motionLevel)
            AniMeowTheme(settings) {
                NavHost(
                    navController = nav,
                    startDestination = if (post > 0) "watch-post" else AppDestination.COMMUNITY.route,
                    enterTransition = {
                        if (!motionEnabled) EnterTransition.None
                        else pageForwardEnterTransition(settings.pageTransitionStyle, pageDuration, reduced = reducedMotion)
                    },
                    exitTransition = {
                        if (!motionEnabled) ExitTransition.None
                        else pageForwardExitTransition(settings.pageTransitionStyle, pageDuration, reduced = reducedMotion)
                    },
                    popEnterTransition = {
                        if (!motionEnabled) EnterTransition.None
                        else pagePopEnterTransition(settings.pageTransitionStyle, pageDuration, reduced = reducedMotion)
                    },
                    popExitTransition = {
                        if (!motionEnabled) ExitTransition.None
                        else pagePopExitTransition(settings.pageTransitionStyle, pageDuration, reduced = reducedMotion)
                    },
                ) {
                    composable("watch-post") { CommunityPostDetailScreen(postId = post, onBack = ::finish, onUserClick = { nav.navigate("community/user/$it") }) }
                    composable(AppDestination.COMMUNITY.route) { entry ->
                        val requests = rememberPageActions(entry)
                        val model: CommunityViewModel = viewModel()
                        val state by model.state.collectAsStateWithLifecycle()
                        var opened by rememberSaveable { mutableStateOf(false) }
                        var preparedFor by rememberSaveable { mutableStateOf<Long?>(null) }
                        var completed by rememberSaveable { mutableStateOf(false) }
                        LaunchedEffect(state.publishedPostId) { if (state.publishedPostId != null) completed = true }
                        LaunchedEffect(share, state.session) {
                            val account = state.session?.userId
                            if (account == null) { opened = false; preparedFor = null }
                            else if (share > 0 && !completed && (!opened || preparedFor != account)) {
                                opened = false; preparedFor = account; model.prepareWatchShare(share)
                            }
                        }
                        LaunchedEffect(state.draftWatchReferenceId, state.session, state.bootstrap, state.watchShareLoading) {
                            if (!opened && state.session != null && state.bootstrap != null && !state.watchShareLoading && state.draftWatchReferenceId == share) {
                                requests.request(PageAction.COMPOSE_POST); opened = true
                            }
                        }
                        Column(Modifier.fillMaxSize()) {
                            val showLoginPrompt = share > 0 && !completed && state.session == null && !state.loading
                            val showPreparingBanner = share > 0 && !completed && !showLoginPrompt &&
                                (state.watchShareLoading || (state.bootstrap == null && state.loading))
                            AnimatedVisibility(
                                visible = showLoginPrompt,
                                enter = motionExpandIn(),
                                exit = motionShrinkOut(),
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Text(
                                            "登录云账号后即可将这条作品评论分享到社区",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.weight(1f),
                                        )
                                        FilledTonalButton(onClick = { nav.navigate("watch-account") }) { Text("去登录") }
                                    }
                                }
                            }
                            AnimatedVisibility(
                                visible = showPreparingBanner,
                                enter = motionExpandIn(),
                                exit = motionShrinkOut(),
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Text("正在准备待分享的作品评论…", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            CommunityScreen(
                                modifier = Modifier.weight(1f),
                                pageActions = requests, onBack = ::finish, showBack = true, viewModel = model,
                                onCloudAccountRequested = { nav.navigate("watch-account") },
                                onCharacterCommunityRequested = { nav.navigate("watch-characters") },
                                onManagementRequested = { nav.navigate(COMMUNITY_MANAGEMENT_ROUTE) },
                                onPostClick = { nav.navigate("community/post/${it.id}") }, onUserClick = { nav.navigate("community/user/$it") },
                            )
                        }
                    }
                    composable("watch-account") { CloudAccountScreen(onBack = nav::navigateUp, onFeedbackPoolRequested = { nav.navigateUp() }) }
                    composable("watch-characters") { CharacterCommunityScreen(settings = settings, onBack = nav::navigateUp) }
                    communityRoutes(nav)
                }
            }
        }
    }
    companion object {
        fun post(context: Context, id: Long) = Intent(context, WatchCommunityActivity::class.java).putExtra("post", id)
        fun share(context: Context, id: Long) = Intent(context, WatchCommunityActivity::class.java).putExtra("share", id)
    }
}
