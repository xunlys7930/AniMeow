package com.animeow.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.community.*
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.preferences.TrackerSettings
import com.animeow.app.ui.community.CommunityInboxContent
import com.animeow.app.ui.community.CommunityInboxUiState
import com.animeow.app.ui.screens.SeriesQuickEditSheet
import com.animeow.app.ui.series.SeriesAnimeCard
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.AniMeowTheme
import com.animeow.app.ui.theme.CoverTitlePosition
import com.animeow.app.ui.tracker.LibraryDisplayItem
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MobileFollowupInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val work = AnimeEntity(id = 1, title = "星海旅人", status = "在看", watchedEpisodes = 3, totalEpisodes = 12)
    private val actor = CommunityProfile(2, "friend", "喵友", "", "members", false, false, false, null, false, emptyMap(), null)

    @Test fun nestedAppearancePreviewDoesNotMultiplyTheCurrentAppsFontScaleAgain() {
        val currentScale = mutableStateOf(1.2f)
        val importedScale = mutableStateOf(0.9f)
        var outer = 0f
        var preview = 0f
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, 1.5f)) {
                AniMeowTheme(AppearanceSettings(fontScale = currentScale.value)) {
                    val outerDensity = LocalDensity.current
                    SideEffect { outer = outerDensity.fontScale }
                    AniMeowTheme(AppearanceSettings(fontScale = importedScale.value)) {
                        val previewDensity = LocalDensity.current
                        SideEffect { preview = previewDensity.fontScale }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(1.8f, outer, 0.001f)
            assertEquals(1.35f, preview, 0.001f)
            currentScale.value = 1f
            importedScale.value = 1.1f
        }
        compose.runOnIdle {
            assertEquals(1.5f, outer, 0.001f)
            assertEquals(1.65f, preview, 0.001f)
        }
    }

    @Test fun overlaySeriesCardHasNoEmptyFooterAndBelowTitleKeepsItsSpace() {
        val appearance = mutableStateOf(AppearanceSettings(showProgress = false, coverTitlePosition = CoverTitlePosition.OVERLAY))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(120.dp)) {
                    SeriesAnimeCard(work, appearance.value, TrackerSettings(showCoverSubjectType = false, showCoverUpdateWeekday = false),
                        Color.Blue, false, {}, {}, Modifier.testTag("series-card"))
                }
            }
        }
        val expected = (120 / appearance.value.coverAspectRatio.ratio).dp
        compose.onNodeWithTag("series-card").assertHeightIsEqualTo(expected)
        val original = compose.onNodeWithTag("series-card").fetchSemanticsNode().boundsInRoot.height
        compose.runOnIdle { appearance.value = appearance.value.copy(coverTitlePosition = CoverTitlePosition.BELOW) }
        assertTrue(compose.onNodeWithTag("series-card").fetchSemanticsNode().boundsInRoot.height > original)
        compose.onNodeWithText(work.title).assertIsDisplayed()
    }

    @Test fun seriesQuickEditorTargetsTheChosenWorkAndKeepsProgressSeparate() {
        var edited: Long? = null
        var progress: Long? = null
        val second = work.copy(id = 2, title = "星海旅人 第二季")
        compose.setContent {
            MaterialTheme {
                SeriesQuickEditSheet(LibraryDisplayItem.SeriesItem(SeriesEntity(id = 1, name = "星海系列"), listOf(work, second)),
                    onDismiss = {}, onEdit = { edited = it }, onProgress = { progress = it.id }, onOpenSeries = {})
            }
        }
        compose.onNodeWithContentDescription("为星海旅人记录一集").performClick()
        compose.runOnIdle { assertEquals(1L, progress); assertNull(edited) }
        compose.onNodeWithContentDescription("编辑星海旅人 第二季").performClick()
        compose.runOnIdle { assertEquals(2L, edited) }
    }

    @Test fun inboxSeparatesActorNavigationFromRelatedPostAndExposesFilters() {
        val notification = CommunityNotification(10, "comment", 42, 11, actor, "分享一份片单", "我也喜欢这部", "2026-09-12T00:00:00Z", false)
        var opened: Long? = null
        var user: Long? = null
        var selected = CommunityInboxFilter.ALL
        compose.setContent {
            MaterialTheme {
                CommunityInboxContent(CommunityInboxUiState(session = CloudSession(1, "qa", "local-only"), items = listOf(notification), loaded = true),
                    onFilter = { selected = it }, onRefresh = {}, onLoadMore = {}, onLogin = {},
                    onOpen = { opened = it.postId }, onUserClick = { user = it })
            }
        }
        compose.onNodeWithText("评论了你的帖子").assertIsDisplayed()
        compose.onNodeWithContentDescription("查看喵友的主页", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(2L, user); assertNull(opened) }
        compose.runOnIdle { user = null }
        compose.onNodeWithText("喵友", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(2L, user); assertNull(opened) }
        compose.onNode(hasClickLabel("查看相关帖子")).performClick()
        compose.runOnIdle { assertEquals(42L, opened) }
        compose.onNodeWithText("收藏", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(CommunityInboxFilter.FAVORITES, selected) }
    }

    @Test fun oldServerInboxFailureExplainsAvailabilityAndOffersRetry() {
        var retries = 0
        compose.setContent {
            MaterialTheme {
                CommunityInboxContent(CommunityInboxUiState(session = CloudSession(1, "qa", "local-only"), available = false, error = "消息服务暂未启用，请稍后重试"),
                    onFilter = {}, onRefresh = { retries++ }, onLoadMore = {}, onLogin = {}, onOpen = {}, onUserClick = {})
            }
        }
        compose.onNodeWithText("消息服务暂未启用").assertIsDisplayed()
        compose.onNodeWithText("重新加载").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    private fun hasClickLabel(label: String) = SemanticsMatcher("click label $label") {
        it.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.label == label
    }
}
