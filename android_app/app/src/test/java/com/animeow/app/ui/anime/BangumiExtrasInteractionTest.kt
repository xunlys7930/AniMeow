package com.animeow.app.ui.anime

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.animeow.app.data.remote.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class BangumiExtrasInteractionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun pagingRetainsVisibleCommentsOnErrorAndLongTextExpands() {
        var requested = 0; var retries = 0
        val comment = BangumiComment(1, "读者", "", "长评论内容".repeat(100), 9, null, "https://bgm.tv/subject/253/comments")
        val page = BangumiDiscussionPage(listOf(comment), 41, 2, true, true, null)
        val state = BangumiExtrasUiState(error = "读取失败，保留原内容", discussions = mapOf("comments" to page))
        compose.setContent { MaterialTheme { BangumiExtrasContent(253, "作品", 2, state, {}, { requested = it }, { retries++ }) } }
        compose.onNodeWithText("读取失败，保留原内容").assertExists()
        compose.onNodeWithText("重试").performClick(); assertEquals(1, retries)
        compose.onNodeWithText("展开文字").performScrollTo().performClick()
        compose.onNodeWithText("收起").assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("下一页"))
        compose.onNodeWithText("下一页").performClick(); assertEquals(3, requested)
        compose.onNodeWithText("上一页").performClick(); assertEquals(1, requested)
    }

    @Test fun tabsAndStaffSearchOperateOnRealContent() {
        var tab by mutableIntStateOf(0)
        val state = BangumiExtrasUiState(rating = RemoteAnime(RemoteCatalogSource.BANGUMI, "253", "作品", score = 8.5, ratingCount = 200, sourceRank = 1),
            staff = BangumiStaffPage(listOf(BangumiPerson(1, "导演甲", "导演", "全部"), BangumiPerson(2, "编剧乙", "脚本", "1-3")), false, null))
        compose.setContent { MaterialTheme { BangumiExtrasContent(253, "作品", tab, state, { tab = it }, {}, {}) } }
        compose.onNodeWithText("200 人评分").assertExists()
        compose.onNodeWithText("制作人员").performClick()
        compose.onNodeWithText("查找人员或职位").performTextInput("脚本")
        compose.onNodeWithText("编剧乙").assertExists(); compose.onNodeWithText("导演甲").assertDoesNotExist()
    }

    @Test fun tagMatchingControlsSwitchExplicitlyWithoutChangingTheSelection() {
        var all by mutableStateOf(true)
        val ids = setOf(1L, 2L)
        compose.setContent { MaterialTheme { com.animeow.app.ui.tags.TagMatchModeRow(all) { all = it } } }
        compose.onNodeWithText("同时包含全部标签").assertIsSelected()
        compose.onNodeWithText("包含任一标签").performClick()
        assertFalse(all); assertEquals(setOf(1L, 2L), ids)
        compose.onNodeWithText("包含任一标签").assertIsSelected()
        compose.onNodeWithText("同时包含全部标签").performClick(); assertTrue(all)
    }
}
