package com.animeow.app.ui.components

import android.app.Application
import android.app.Instrumentation
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.community.CommunityActivityChanges
import com.animeow.app.data.community.CommunityService
import com.animeow.app.data.community.PersonalActivityTab
import com.animeow.app.data.community.PresetImage
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.playback.PlaybackCheckpoint
import com.animeow.app.data.playback.PlaybackHistoryEntry
import com.animeow.app.data.preferences.AppearancePreferences
import com.animeow.app.data.preferences.TrackerPreferences
import com.animeow.app.data.preferences.TrackerSettings
import com.animeow.app.ui.community.PresetImagePickerDialog
import com.animeow.app.ui.preferences.CustomizationScreen
import com.animeow.app.ui.preferences.CustomizationViewModel
import com.animeow.app.ui.screens.PersonalCenterContent
import com.animeow.app.ui.screens.PersonalCenterUiState
import com.animeow.app.ui.screens.PosterAnimeCard
import com.animeow.app.ui.screens.SettingsScreen
import com.animeow.app.ui.screens.coverBadgeInset
import com.animeow.app.ui.statistics.StatisticsUiState
import com.animeow.app.ui.theme.AniMeowTheme
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.CoverBadgeStyle
import com.animeow.app.ui.theme.CoverTitlePosition
import com.animeow.app.ui.theme.MotionLevel
import com.animeow.app.ui.theme.NavigationBarStyle
import com.animeow.app.ui.tracker.PinnedAnimeShelf
import com.animeow.app.ui.tracker.RecentPlaybackCard
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarginalPolishAndCardsUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun syncProfileStatisticsSendsOnlyStatisticsPayloadAndEmitsProfileUpdate() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        var capturedMethod = ""
        var capturedPath = ""
        var capturedAuth = ""
        var capturedBody = ""
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val responseJson = """
            {
              "status": "success",
              "data": {
                "user_id": 7,
                "username": "alice",
                "nickname": "小喵",
                "signature": "记录好作品",
                "avatar_url": "",
                "is_private": false,
                "show_statistics_card": true,
                "statistics": {
                  "totalAnime": 18,
                  "watchingCount": 4,
                  "completedCount": 11,
                  "watchedEpisodes": 210,
                  "watchHours": 84,
                  "watchMinutes": 5040
                }
              }
            }
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val responder = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val requestLine = input.readLine().orEmpty()
                    val parts = requestLine.split(' ')
                    capturedMethod = parts.getOrNull(0).orEmpty()
                    capturedPath = parts.getOrNull(1).orEmpty()
                    var length = 0
                    while (true) {
                        val header = input.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Authorization:", ignoreCase = true)) {
                            capturedAuth = header.substringAfter(':').trim()
                        }
                        if (header.startsWith("Content-Length:", ignoreCase = true)) {
                            length = header.substringAfter(':').trim().toInt()
                        }
                    }
                    val bodyChars = CharArray(length)
                    var offset = 0
                    while (offset < length) {
                        val count = input.read(bodyChars, offset, length - offset)
                        check(count > 0)
                        offset += count
                    }
                    capturedBody = String(bodyChars)
                    socket.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${responseJson.size}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8),
                        )
                        write(responseJson)
                        flush()
                    }
                }
            }
        }
        try {
            val service = CommunityService(
                context = context,
                apiBase = "http://127.0.0.1:${server.localPort}",
            )
            val updates = mutableListOf<String>()
            val collector = launch {
                val event = withTimeout(5_000) {
                    CommunityActivityChanges.profileUpdates.first { it.profile.statistics["totalAnime"]?.toInt() == 18 }
                }
                updates += "${event.sessionToken}:${event.profile.statistics["totalAnime"]?.toInt()}"
            }
            val result = service.syncProfileStatistics(
                session = CloudSession(userId = 7L, username = "alice", token = "tok-sync-1"),
                statistics = mapOf(
                    "totalAnime" to 18.0,
                    "watchingCount" to 4.0,
                    "completedCount" to 11.0,
                    "watchedEpisodes" to 210.0,
                    "watchHours" to 84.0,
                    "watchMinutes" to 5040.0,
                ),
            )
            responder.join(5_000)
            collector.join()
            assertEquals("PUT", capturedMethod)
            assertEquals("/api/community/profile/me", capturedPath)
            assertEquals("Bearer tok-sync-1", capturedAuth)
            assertTrue(capturedBody.contains("\"statistics\""))
            assertFalse(capturedBody.contains("\"nickname\""))
            assertFalse(capturedBody.contains("\"signature\""))
            assertEquals(18, result.statistics["totalAnime"]?.toInt())
            assertEquals(listOf("tok-sync-1:18"), updates)
        } finally {
            server.close()
            responder.join(5_000)
        }
    }

    @Test
    fun coverBadgeInsetScalesWithCornerRadiusAndBottomBarStaysBelowOverlayTitle() {
        val cornerInset = coverBadgeInset(CoverBadgeStyle.CORNER, 24f)
        assertTrue(cornerInset.calculateTopPadding() >= 10.dp)
        val edgeInset = coverBadgeInset(CoverBadgeStyle.EDGE, 24f)
        assertTrue(edgeInset.calculateTopPadding() >= 8.dp)

        val anime = AnimeEntity(
            id = 42L,
            title = "超长动画标题用于测试叠加底栏与顶部标签在圆角与选中态下不会相互遮挡",
            status = "在看",
            rating = 92,
            watchedEpisodes = 8,
            totalEpisodes = 12,
        )
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.setContent {
                MaterialTheme {
                    CompositionLocalProvider(LocalDensity provides Density(2f, 1.3f)) {
                        Box(Modifier.width(150.dp).testTag("poster-bottom-bar")) {
                            PosterAnimeCard(
                                anime = anime,
                                settings = AppearanceSettings(
                                    coverBadgeStyle = CoverBadgeStyle.BOTTOM_BAR,
                                    coverTitlePosition = CoverTitlePosition.OVERLAY,
                                    showTitle = true,
                                    showStatus = true,
                                    showRating = true,
                                ),
                                trackerSettings = TrackerSettings(coverCornerDp = 24f),
                                selected = false,
                                onClick = {},
                                onLongClick = {},
                                swipeEnabled = false,
                                onSwipe = { _, _ -> },
                            )
                        }
                    }
                }
            }
            controller.visible()
            compose.waitForIdle()
            val titleBounds = compose.onNodeWithText(anime.title, useUnmergedTree = true).getBoundsInRoot()
            val statusBounds = compose.onNodeWithText("在看", useUnmergedTree = true).getBoundsInRoot()
            val ratingBounds = compose.onNodeWithText("9.2", substring = true, useUnmergedTree = true).getBoundsInRoot()
            assertTrue(
                "Overlay title (${titleBounds.bottom}) must stay above bottom badge bar (${statusBounds.top})",
                titleBounds.bottom <= statusBounds.top + 1.dp,
            )
            assertTrue(
                "Status badge (${statusBounds.right}) must stay left of rating badge (${ratingBounds.left})",
                statusBounds.right <= ratingBounds.left,
            )
        }
    }

    @Test
    fun posterSelectedCornerBadgeKeepsClearanceFromRightSelectionIndicator() {
        val anime = AnimeEntity(
            id = 43L,
            title = "角落标签选中间距测试",
            status = "在看",
            rating = 92,
            watchedEpisodes = 3,
            totalEpisodes = 12,
        )
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.setContent {
                MaterialTheme {
                    Box(Modifier.width(160.dp).testTag("selected-poster-card")) {
                        PosterAnimeCard(
                            anime = anime,
                            settings = AppearanceSettings(
                                coverBadgeStyle = CoverBadgeStyle.CORNER,
                                coverTitlePosition = CoverTitlePosition.BELOW,
                                showTitle = true,
                                showStatus = true,
                                showRating = true,
                            ),
                            trackerSettings = TrackerSettings(coverCornerDp = 24f),
                            selected = true,
                            onClick = {},
                            onLongClick = {},
                            swipeEnabled = false,
                            onSwipe = { _, _ -> },
                        )
                    }
                }
            }
            controller.visible()
            compose.waitForIdle()
            val cardBounds = compose.onNodeWithTag("selected-poster-card").getBoundsInRoot()
            val ratingBounds = compose.onNodeWithText("9.2", substring = true, useUnmergedTree = true).getBoundsInRoot()
            assertTrue(
                "Rating badge right (${ratingBounds.right}) must leave >= 28dp room from card right (${cardBounds.right})",
                cardBounds.right - ratingBounds.right >= 28.dp,
            )
        }
    }

    @Test
    fun recentPlaybackAndPinnedShelfStayReadableWithoutCornerOverlapAtLargeFont() {
        val row = PlaybackHistoryEntry(
            key = "anime-7",
            animeId = 7L,
            title = "特别长的动画标题用来确认最近观看卡片不会遮蔽左上角和右上角文字",
            cover = "",
            episode = "第 24 话 超长分集副标题测试文本溢出处理",
            updatedAt = 10_000L,
            checkpoint = PlaybackCheckpoint(
                source = "local",
                workId = "7",
                episodeId = "ep24",
                positionMs = 180_000L,
                durationMs = 1_440_000L,
            ),
        )
        val pinned = AnimeEntity(
            id = 7L,
            title = "置顶卡片测试作品",
            status = "在看",
        )
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.setContent {
                AniMeowTheme(AppearanceSettings(cornerScale = 1.5f)) {
                    CompositionLocalProvider(LocalDensity provides Density(2f, 1.4f)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            RecentPlaybackCard(
                                row = row,
                                onOpen = {},
                                onResume = {},
                                onNext = {},
                                modifier = Modifier.width(300.dp),
                            )
                            PinnedAnimeShelf(
                                animes = listOf(pinned),
                                onOpen = {},
                                onUnpin = {},
                                showPinBadges = true,
                            )
                        }
                    }
                }
            }
            controller.visible()
            compose.waitForIdle()
            compose.onNodeWithText(row.title).assertIsDisplayed()
            compose.onNodeWithText(row.episode).assertIsDisplayed()
            compose.onNodeWithText("查看详情").assertIsDisplayed()
            compose.onNodeWithText("继续观看").assertIsDisplayed()
            compose.onNodeWithTag("pin-badge-7", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun personalCenterHidesCommunityTabsAndKeepsReadingTools() {
        val showActivity = mutableStateOf(true)

        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            controller.get().setContent {
                MaterialTheme {
                    PersonalCenterContent(
                        state = PersonalCenterUiState(),
                        statistics = StatisticsUiState(),
                        showVersion = false,
                        showCommunityActivity = showActivity.value,
                        onTabSelected = {},
                        onRefresh = {},
                        onLoadMore = {},
                        onLogin = {},
                        onEditProfile = {},
                        onCommunity = {},
                        onCreatePost = {},
                        onPostClick = {},
                        onTools = {},
                        onStatistics = {},
                        onReminders = {},
                        onCustomization = {},
                    )
                }
            }
            controller.visible()
            compose.onNode(hasScrollAction()).performScrollToIndex(3)
            PersonalActivityTab.entries.forEach { compose.onNodeWithText(it.label).assertIsDisplayed() }
            compose.onNodeWithText("登录，留住每一次分享").assertIsDisplayed()

            compose.runOnIdle { showActivity.value = false }
            PersonalActivityTab.entries.forEach { compose.onNodeWithText(it.label).assertDoesNotExist() }
            compose.onNodeWithText("登录，留住每一次分享").assertDoesNotExist()
            compose.onNodeWithText("最近阅读").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun avatarAndStickerVotingUiIsRemovedFromPickerAndPersonalCenter() {
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.setContent {
                MaterialTheme {
                    PresetImagePickerDialog(
                        images = listOf(PresetImage(url = "https://example.test/cat.png", name = "cat-1")),
                        disclaimer = "预设说明",
                        multiSelect = false,
                        onSelected = {},
                        onDismiss = {},
                    )
                }
            }
            controller.visible()
            compose.waitForIdle()
            compose.onNodeWithText("选择预设头像").assertIsDisplayed()
            compose.onNodeWithText("期待正式上架？来投票吧！").assertDoesNotExist()
            compose.onNodeWithText("投票", substring = true).assertDoesNotExist()
        }

        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.setContent {
                MaterialTheme {
                    PersonalCenterContent(
                        state = PersonalCenterUiState(),
                        statistics = StatisticsUiState(),
                        showVersion = false,
                        onTabSelected = {},
                        onRefresh = {},
                        onLoadMore = {},
                        onLogin = {},
                        onEditProfile = {},
                        onCommunity = {},
                        onCreatePost = {},
                        onPostClick = {},
                        onTools = {},
                        onStatistics = {},
                        onReminders = {},
                        onCustomization = {},
                    )
                }
            }
            controller.visible()
            compose.waitForIdle()
            compose.onNodeWithText("外观与阅读").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("投票", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun customizationDirectApplyCommitsSlidersAndUndoRevertsDraftCleanly() {
        val app = Instrumentation.newApplication(AniMeowApplication::class.java, RuntimeEnvironment.getApplication()) as AniMeowApplication
        val appearancePreferences = AppearancePreferences(app)
        val trackerPreferences = TrackerPreferences(app)
        val originalAppearance = runBlocking { appearancePreferences.snapshot() }
        val originalTracker = runBlocking { trackerPreferences.snapshot() }
        runBlocking {
            appearancePreferences.restore(
                AppearanceSettings(motionLevel = MotionLevel.NONE, navigationBarStyle = NavigationBarStyle.FLOATING, gridColumns = 3),
            )
            trackerPreferences.save(TrackerSettings(infoTextOpacity = 0.9f))
        }
        val store = ViewModelStore()
        val model = CustomizationViewModel(app, "").also { store.put("customization", it) }
        try {
            Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
                val activity = controller.get()
                activity.setContent {
                    MaterialTheme {
                        CustomizationScreen(
                            onBack = {},
                            onBrandingRequested = {},
                            initialSection = "cover",
                            viewModel = model,
                        )
                    }
                }
                controller.visible()
                var ready = false
                compose.runOnIdle { model.beginDraft().invokeOnCompletion { ready = true } }
                await { ready }

                compose.onNodeWithContentDescription("文字透明度")
                    .performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress) { it(0.6f) }
                await { model.uiState.value.hasChanges }

                compose.onNodeWithText("直接应用").performClick()
                await { !model.uiState.value.hasChanges }
                assertEquals(0.6f, runBlocking { trackerPreferences.snapshot() }.infoTextOpacity, 0.05f)

                compose.runOnIdle { model.setGridColumns(5) }
                await { model.uiState.value.hasChanges }
                assertEquals(5, runBlocking { appearancePreferences.snapshot() }.gridColumns)

                compose.onNodeWithText("撤销上一步").performClick()
                await { !model.uiState.value.hasChanges }
                assertEquals(3, runBlocking { appearancePreferences.snapshot() }.gridColumns)
            }
        } finally {
            store.clear()
            runBlocking {
                appearancePreferences.restore(originalAppearance)
                trackerPreferences.save(originalTracker)
            }
        }
    }

    @Test
    fun settingsNavigationAndBehaviorExposeTopLevelControlsDirectly() {
        var discoveryToggled: Boolean? = null
        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val appearance = androidx.lifecycle.ViewModelProvider(activity)[com.animeow.app.ui.preferences.AppearanceViewModel::class.java]
            activity.setContent {
                MaterialTheme {
                    SettingsScreen(
                        settings = AppearanceSettings(showDiscovery = true),
                        initialSection = "navigation",
                        onThemeModeSelected = {},
                        onFrontendModeSelected = {},
                        onPredictiveBackEnabledChanged = {},
                        onExitBehaviorChanged = {},
                        onShowDiscoveryChanged = { discoveryToggled = it },
                        onShowCalendarChanged = {},
                        onShowCommunityChanged = {},
                        onShowStatisticsChanged = {},
                        onShowVersionInProfileChanged = {},
                        onNavigationOrderChanged = {},
                        onStartDestinationSelected = {},
                        onCustomizationRequested = {},
                        appearanceViewModel = appearance,
                    )
                }
            }
            controller.visible()
            compose.waitForIdle()
            compose.onNodeWithText("显示发现").assertIsDisplayed().performClick()
            assertEquals(false, discoveryToggled)
            compose.onNodeWithText("显示日历").assertIsDisplayed()
            compose.onNodeWithText("显示社区").assertIsDisplayed()
            compose.onNodeWithText("显示统计").assertIsDisplayed()
        }

        Robolectric.buildActivity(ComponentActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            val appearance = androidx.lifecycle.ViewModelProvider(activity)[com.animeow.app.ui.preferences.AppearanceViewModel::class.java]
            activity.setContent {
                MaterialTheme {
                    SettingsScreen(
                        settings = AppearanceSettings(),
                        initialSection = "behavior",
                        onThemeModeSelected = {},
                        onFrontendModeSelected = {},
                        onPredictiveBackEnabledChanged = {},
                        onExitBehaviorChanged = {},
                        onShowDiscoveryChanged = {},
                        onShowCalendarChanged = {},
                        onShowCommunityChanged = {},
                        onShowStatisticsChanged = {},
                        onShowVersionInProfileChanged = {},
                        onNavigationOrderChanged = {},
                        onStartDestinationSelected = {},
                        onCustomizationRequested = {},
                        appearanceViewModel = appearance,
                    )
                }
            }
            controller.visible()
            compose.waitForIdle()
            compose.onNodeWithText("返回退出方式").assertIsDisplayed()
            compose.onNodeWithText("预测性返回手势").assertIsDisplayed()
        }
    }

    private fun await(condition: () -> Boolean) {
        compose.waitUntil(15_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }
}
