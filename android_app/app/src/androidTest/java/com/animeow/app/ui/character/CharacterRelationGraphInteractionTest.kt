package com.animeow.app.ui.character

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.animeow.app.data.local.CharacterEntity
import com.animeow.app.data.local.CharacterRelationEntity
import com.animeow.app.data.local.CharacterRelationItem
import com.animeow.app.ui.theme.AniMeowTheme
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.MotionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class CharacterRelationGraphInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val center = CharacterEntity(id = 1, name = "林夏")
    private var opened: Long? = null

    @Test
    fun nodeSelectionShowsBothRolesBeforeExplicitNavigation() {
        show(listOf(relation(2, "陈羽", "老师", "学生")))
        compose.onNodeWithContentDescription("陈羽，学生").performClick()
        compose.onNodeWithText("陈羽是林夏的学生").assertExists()
        compose.onNodeWithText("林夏是陈羽的老师").assertExists()
        compose.runOnIdle { assertEquals(null, opened) }
        compose.onNodeWithText("查看角色资料").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2L, opened) }
    }

    @Test
    fun fullScreenDragRotatesAndReturningKeepsTheChosenMode() {
        show(listOf(relation(2, "陈羽", "老师", "学生")))
        compose.onNodeWithText("空间").performClick()
        compose.onNodeWithText("全屏").performClick()
        compose.onNodeWithTag("character-relation-graph-fullscreen").assertExists()
        compose.onNodeWithText("调整视角").performScrollTo().performClick()
        val before = compose.onNodeWithText("水平旋转", substring = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].toString()
        compose.onNodeWithTag("character-relation-graph-fullscreen").performScrollTo().performTouchInput { swipeLeft() }
        val after = compose.onNodeWithText("水平旋转", substring = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].toString()
        assertNotEquals(before, after)
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag("character-relation-graph-fullscreen").assertDoesNotExist()
        compose.onNodeWithText("空间").assertIsSelected()
    }

    @Test
    fun pinchStartingOnPortraitsZoomsWithoutChangingRotation() {
        show(listOf(relation(2, "陈羽", "老师", "学生"), relation(3, "许宁", "朋友", "朋友")))
        compose.onNodeWithText("空间").performClick()
        compose.onNodeWithText("全屏").performClick()
        compose.onNodeWithText("调整视角").performScrollTo().performClick()
        val beforeYaw = valueText("水平旋转")
        val beforeZoom = valueText("缩放")
        val graph = compose.onNodeWithTag("character-relation-graph-fullscreen")
        graph.performScrollTo()
        val scene = graph.fetchSemanticsNode().boundsInRoot
        val inScene = hasAnyAncestor(hasTestTag("character-relation-graph-fullscreen"))
        val first = compose.onNode(hasContentDescription("陈羽，学生") and inScene).fetchSemanticsNode().boundsInRoot.center - scene.topLeft
        val second = compose.onNode(hasContentDescription("许宁，朋友") and inScene).fetchSemanticsNode().boundsInRoot.center - scene.topLeft
        val difference = second - first
        graph.performTouchInput {
            down(0, first)
            down(1, second)
            repeat(6) { step ->
                val delta = difference * ((step + 1) * 0.05f)
                moveTo(0, first - delta)
                moveTo(1, second + delta)
            }
            up(0)
            up(1)
        }
        assertEquals(beforeYaw, valueText("水平旋转"))
        assertNotEquals(beforeZoom, valueText("缩放"))
    }

    @Test
    fun pageControlsReachRelationshipsBeyondTheOldTenNodeLimit() {
        show((2L..14L).map { relation(it, "角色$it", "", "朋友") })
        compose.onNodeWithText("显示 1–8 / 13 个关联角色").assertExists()
        compose.onNodeWithText("下一页").performScrollTo().performClick()
        compose.onNodeWithText("显示 9–13 / 13 个关联角色").assertExists()
        compose.onNodeWithContentDescription("角色14，朋友").performScrollTo().performClick()
        compose.onNodeWithText("角色14是林夏的朋友").assertExists()
        compose.onNodeWithText("下一页").assertIsNotEnabled()
    }

    private fun valueText(label: String) = compose.onNode(SemanticsMatcher("$label 数值") { node ->
        node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text.startsWith(label) } == true
    }).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].toString()

    private fun show(relations: List<CharacterRelationItem>) {
        compose.setContent {
            AniMeowTheme(AppearanceSettings(useDynamicColor = false, motionLevel = MotionLevel.NONE)) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        CharacterRelationGraph(center, relations, onCharacterClick = { opened = it })
                    }
                }
            }
        }
    }

    private fun relation(id: Long, name: String, sourceRole: String, targetRole: String) = CharacterRelationItem(
        relation = CharacterRelationEntity(id = id, sourceCharacterId = 1, targetCharacterId = id, sourceRole = sourceRole, targetRole = targetRole),
        relatedCharacterId = id, relatedBgmId = null, relatedName = name, relatedNameCn = null,
        relatedImageUrl = null, relatedImageDisplayMode = null, relatedGender = null, relatedSummary = null, relatedRating = null,
    )
}
