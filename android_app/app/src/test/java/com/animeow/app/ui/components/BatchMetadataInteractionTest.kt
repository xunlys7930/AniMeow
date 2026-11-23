package com.animeow.app.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.animeow.app.ui.screens.BatchMetadataProgressContent
import com.animeow.app.ui.tracker.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatchMetadataInteractionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun resultsStayVisibleAndFilteringAndRetryAreExplicit() {
        val rows = listOf(BatchMetadataResult(1, "成功作品", BatchMetadataOutcome.UPDATED, "已保存"),
            BatchMetadataResult(2, "失败作品", BatchMetadataOutcome.FAILED, "网络失败，可重试"), BatchMetadataResult(3, "待处理作品"))
        var retries = 0; var closes = 0
        compose.setContent { MaterialTheme { BatchMetadataProgressContent(BatchMetadataState(total = 3, completed = 2,
            results = rows, message = "已停止，已保存的资料保留"), {}, {}, { retries++ }, { closes++ }) } }
        compose.onNodeWithText("已停止，已保存的资料保留").assertExists()
        compose.onNodeWithText("待处理 2").performClick()
        compose.onNodeWithText("成功作品").assertDoesNotExist()
        compose.onNodeWithTag("batch-metadata-results").performScrollToNode(hasText("待处理作品"))
        compose.onNodeWithText("待处理作品").assertIsDisplayed()
        assertEquals(0, retries)
        compose.onNodeWithText("仅重试待处理 2 部").performClick()
        assertEquals(1, retries); assertEquals(0, closes)
        compose.onNodeWithText("完成").performClick()
        assertEquals(1, closes)
    }

    @Test fun savingCannotBeSkippedAndStopDoesNotCloseTheResults() {
        var state by mutableStateOf(BatchMetadataState(isRunning = true, total = 1, currentTitle = "当前作品", currentAnimeId = 1,
            stage = "正在搜索并核对资料", results = listOf(BatchMetadataResult(1, "当前作品"))))
        var skips = 0; var closes = 0
        compose.setContent { MaterialTheme { BatchMetadataProgressContent(state, { skips++ }, {
            state = state.copy(isRunning = false, message = "已停止，已保存的资料保留")
        }, {}, { closes++ }) } }
        compose.onNodeWithText("跳过当前").assertIsEnabled().performClick()
        assertEquals(1, skips)
        compose.runOnIdle { state = state.copy(stage = "正在保存所选资料") }
        compose.onNodeWithText("跳过当前").assertIsNotEnabled()
        compose.onNodeWithText("停止补全").performClick()
        compose.onNodeWithText("已停止，已保存的资料保留").assertExists()
        assertEquals(0, closes)
    }
}
