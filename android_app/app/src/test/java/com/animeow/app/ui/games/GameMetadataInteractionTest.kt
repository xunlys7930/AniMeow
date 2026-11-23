package com.animeow.app.ui.games

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.animeow.app.data.games.GameEntity
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
class GameMetadataInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun existingFieldsCanBeFilledManuallyWithoutChangingPersonalRecords() {
        var saved: GameEntity? = null
        val game = GameEntity(title = "手动游戏", playMinutes = 123, rating = 9, review = "保留心得")
        compose.setContent { MaterialTheme { GameDetailSheet(game, false, {}, { saved = it }, {}) } }
        compose.onNodeWithText("游戏原名").performScrollTo().performTextInput("Original name")
        compose.onNodeWithText("开发商").performScrollTo().performTextInput("示例开发商")
        compose.onNodeWithText("选择或新建标签").performScrollTo().performClick()
        for (name in listOf("剧情", "治愈")) {
            compose.onNodeWithText("搜索或新建标签").performTextInput(name)
            compose.onNodeWithText("新建并选择“$name”").performClick()
        }
        compose.onNodeWithText("确认").performClick()
        compose.onNodeWithText("发售日期（YYYY-MM-DD，可留空）").performScrollTo().performTextInput("2026-99-99")
        compose.onNodeWithText("保存游戏记录").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("发售日期（YYYY-MM-DD，可留空）").performScrollTo().performTextReplacement("2026-10-23")
        compose.onNodeWithText("保存游戏记录").performScrollTo().performClick()
        assertEquals(game.copy(originalTitle = "Original name", developer = "示例开发商", releaseDate = "2026-10-23", tags = "剧情，治愈"), saved)
    }
    @Test fun backingOutWithChangesKeepsDraftUntilExplicitlyDiscarded() {
        var dismissed = false
        val game = GameEntity(title = "原游戏", review = "旧心得")
        compose.setContent { MaterialTheme { GameDetailSheet(game, false, { dismissed = true }, {}, {}) } }
        compose.onNodeWithText("游戏名称").performTextReplacement("未保存的游戏")
        compose.onNodeWithText("返回").performScrollTo().performClick()
        assertFalse(dismissed)
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText("游戏名称").assertTextContains("未保存的游戏")
        compose.onNodeWithText("返回").performScrollTo().performClick()
        compose.onNodeWithText("放弃修改").performClick()
        assertTrue(dismissed)
    }
}
