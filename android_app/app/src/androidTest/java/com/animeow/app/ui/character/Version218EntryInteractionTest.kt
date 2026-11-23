package com.animeow.app.ui.character

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.CharacterEntity
import com.animeow.app.data.local.CharacterListItem
import com.animeow.app.data.local.CharacterWithRole
import com.animeow.app.data.local.CharacterWorkItem
import com.animeow.app.data.local.EpisodeDetailEntity
import com.animeow.app.ui.anime.EpisodeReadingScreen
import com.animeow.app.ui.theme.AniMeowTheme
import com.animeow.app.ui.theme.AppearanceSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class Version218EntryInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun episodeReadingShowsCompleteTextWithoutOpeningEditor() {
        var edits = 0
        compose.setContent { AniMeowTheme(AppearanceSettings()) {
            EpisodeReadingScreen(AnimeEntity(id = 1, title = "合成作品", totalEpisodes = 12), 2, "集",
                EpisodeDetailEntity(animeId = 1, episode = 2, title = "新的旅途", durationMinutes = 25,
                    synopsis = "完整单集简介", review = "完整个人评价", rating = 85), 14, {}, { edits++ })
        } }
        compose.onNodeWithText("完整单集简介").assertExists()
        compose.onNodeWithText("完整个人评价").assertExists()
        compose.onNodeWithText("未看 · 连续集号 第 14 集 · 25 分钟 · 评分 8.5").assertExists()
        compose.runOnIdle { assertEquals(0, edits) }
        compose.onNodeWithText("编辑").performClick()
        compose.runOnIdle { assertEquals(1, edits) }
    }

    @Test fun targetBeyondThirtyCanBeSelectedAndFormIsImmediatelyVisible() {
        compose.setContent { AniMeowTheme(AppearanceSettings()) {
            RelationEditorDialog(1, "林夏", emptyList(), null,
                (2L..42L).map { CharacterListItem(CharacterEntity(id = it, name = "候选$it"), 1, 0, 0) },
                { _, _, _, _, _ -> }, {})
        } }
        compose.onNodeWithTag("relation-candidates").performScrollToNode(hasTestTag("relation-candidate-42"))
        compose.onNodeWithTag("relation-candidate-42").performClick()
        compose.onNodeWithTag("relation-source-role").assertIsDisplayed()
        compose.onNodeWithText("保存关系").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("relation-source-role").performTextInput("师父")
        compose.onNodeWithText("保存关系").assertIsEnabled()
    }

    @Test fun workCastBindsOnlyTheChosenCharacter() {
        var saved: Pair<Long, String>? = null
        compose.setContent { AniMeowTheme(AppearanceSettings()) {
            WorkCastDialog("合成作品", listOf(
                CharacterWithRole(CharacterEntity(id = 1, name = "角色甲"), "主角", 0, "声优甲"),
                CharacterWithRole(CharacterEntity(id = 2, name = "角色乙"), "配角", 1)),
                "旧名单A，旧名单B", { id, name -> saved = id to name }, {}, {}, {})
        } }
        compose.onNodeWithText("声优甲").assertExists()
        compose.onNodeWithText("旧名单A，旧名单B").assertDoesNotExist()
        compose.onNodeWithTag("work-cast-list").performScrollToNode(hasTestTag("bind-actor-2"))
        compose.onNodeWithTag("bind-actor-2").performClick()
        compose.onNodeWithTag("voice-actor-name").performTextInput("声优乙")
        compose.onNodeWithText("保存绑定").performClick()
        compose.waitUntil { saved != null }
        compose.runOnIdle { assertEquals(2L to "声优乙", saved) }
    }

    @Test fun characterWorkProvidesVisibleBindingEntryWhenEmpty() {
        var edits = 0
        compose.setContent { AniMeowTheme(AppearanceSettings()) {
            WorkCard(CharacterWorkItem(AnimeEntity(id = 1, title = "合成作品"), "主角", 0), {}, { edits++ })
        } }
        compose.onNodeWithText("尚未绑定声优").assertExists()
        compose.onNodeWithText("绑定声优").performClick()
        compose.runOnIdle { assertEquals(1, edits) }
    }
}
