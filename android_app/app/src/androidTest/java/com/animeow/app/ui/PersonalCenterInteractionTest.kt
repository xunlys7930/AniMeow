package com.animeow.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.community.CommunityPost
import com.animeow.app.data.community.CommunityProfile
import com.animeow.app.data.community.PersonalActivityPage
import com.animeow.app.data.community.PersonalActivityTab
import com.animeow.app.ui.community.CommunityProfileEditorDialog
import com.animeow.app.ui.components.ConfirmationDialog
import com.animeow.app.ui.screens.PersonalCenterContent
import com.animeow.app.ui.screens.PersonalCenterUiState
import com.animeow.app.ui.statistics.StatisticsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PersonalCenterInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val profile = CommunityProfile(7, "qa", "喵友", "喜欢好故事", "members", false, false, false, null, false, emptyMap(), null)
    private val session = CloudSession(7, "qa", "local-fixture")
    private fun post(id: Long, text: String) = CommunityPost(
        id, 1, text, emptyList(), profile, "visible", false, false, null, false, null,
        1, 0, true, true, false, true, "2026-09-08T00:00:00Z", "2026-09-08T00:00:00Z",
    )

    @Test fun personalTabsUseTheirOwnRecordsAndOpenTheSelectedPost() {
        val state = mutableStateOf(PersonalCenterUiState(configured = true, session = session, profile = profile,
            pages = mapOf(
                PersonalActivityTab.POSTS to PersonalActivityPage(listOf(post(1, "我发布的帖子")), loaded = true, endReached = true),
                PersonalActivityTab.LIKES to PersonalActivityPage(listOf(post(2, "我赞过的帖子")), loaded = true, endReached = true),
                PersonalActivityTab.FAVORITES to PersonalActivityPage(listOf(post(3, "我收藏的帖子")), loaded = true, endReached = true),
            )))
        var opened: Long? = null
        compose.setContent { Content(state.value, onTab = { state.value = state.value.copy(tab = it) }, onPost = { opened = it }) }
        compose.onNodeWithText("点赞", useUnmergedTree = true).performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("我赞过的帖子"))
        compose.onNodeWithText("我发布的帖子").assertDoesNotExist()
        compose.onNodeWithText("我赞过的帖子").performClick()
        compose.runOnIdle { assertEquals(2L, opened) }
        compose.onNodeWithText("收藏", useUnmergedTree = true).performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("我收藏的帖子"))
        compose.onNodeWithText("我收藏的帖子").assertIsDisplayed()
    }

    @Test fun sparseHistoryOffersContinuationInsteadOfFalseEmptyState() {
        var continued = 0
        compose.setContent { Content(PersonalCenterUiState(configured = true, session = session, profile = profile,
            tab = PersonalActivityTab.LIKES,
            pages = mapOf(PersonalActivityTab.LIKES to PersonalActivityPage(loaded = true, nextBeforeId = 30))), onMore = { continued++ }) }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("继续查找"))
        compose.onNodeWithText("还没有点赞记录").assertDoesNotExist()
        compose.onNodeWithText("继续查找").performClick()
        compose.runOnIdle { assertEquals(1, continued) }
    }

    @Test fun historyErrorsExposeRetryAndPreserveTheirMeaning() {
        var retries = 0
        compose.setContent { Content(PersonalCenterUiState(configured = true, session = session, profile = profile,
            pages = mapOf(PersonalActivityTab.POSTS to PersonalActivityPage(error = "连接失败"))), onMore = { retries++ }) }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("重试"))
        compose.onNodeWithText("连接失败").assertIsDisplayed()
        compose.onNodeWithText("还没有发布过帖子").assertDoesNotExist()
        compose.onNodeWithText("重试").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun loggedOutUsersKeepLocalToolsAtLargeFontSizes() {
        var tools = 0
        var login = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.4f)) {
                Content(PersonalCenterUiState(), onTools = { tools++ }, onLogin = { login++ })
            }
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("全部工具"))
        compose.onNodeWithText("全部工具").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("登录 / 注册"))
        compose.onNodeWithText("登录 / 注册").performClick()
        compose.runOnIdle { assertEquals(1, tools); assertEquals(1, login) }
    }

    @Test fun cancelAndSystemBackNeverExecuteTheConsequentialAction() {
        val show = mutableStateOf(false)
        var logouts = 0
        compose.setContent { MaterialTheme {
            TextButton(onClick = { show.value = true }) { Text("请求退出") }
            if (show.value) ConfirmationDialog("退出当前账号？", "本地资料保留", "退出登录",
                onConfirm = { logouts++; show.value = false }, onDismiss = { show.value = false })
        } }
        compose.onNodeWithText("请求退出").performClick()
        compose.runOnIdle { assertEquals(0, logouts) }
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("请求退出").performClick()
        Espresso.pressBack()
        compose.runOnIdle { assertEquals(0, logouts) }
        compose.onNodeWithText("请求退出").performClick()
        compose.onNodeWithText("退出登录").performClick()
        compose.runOnIdle { assertEquals(1, logouts) }
    }

    @Test fun profileEditsSurviveCancelingTheDiscardDialog() {
        var dismissed = 0
        compose.setContent { MaterialTheme { CommunityProfileEditorDialog(
            profile, StatisticsUiState(), null, false, emptyList(), "", saveError = "保存失败，请重试",
            onDismiss = { dismissed++ }, onSave = { _, _, _, _, _, _, _, _ -> },
        ) } }
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("新的昵称")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("放弃资料修改？").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, dismissed) }
        compose.onAllNodesWithText("取消").onLast().performClick()
        compose.onNodeWithText("新的昵称").assertExists()
        compose.onNodeWithText("保存失败，请重试").assertExists()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("放弃修改").performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Composable private fun Content(
        state: PersonalCenterUiState,
        onTab: (PersonalActivityTab) -> Unit = {},
        onPost: (Long) -> Unit = {},
        onMore: () -> Unit = {},
        onTools: () -> Unit = {},
        onLogin: () -> Unit = {},
    ) {
        MaterialTheme { Box(Modifier.width(320.dp).height(640.dp)) {
            PersonalCenterContent(
                state, StatisticsUiState(totalAnime = 126), false, onTab, {}, onMore, onLogin, {}, {}, {}, onPost,
                onTools, {}, {}, {},
            )
        } }
    }
}
