package com.animeow.app.ui.components

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.animeow.app.data.local.TagEntity
import com.animeow.app.ui.management.TagOrderDialog
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
class TagOrderInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val tags = (1L..20L).map { TagEntity(id = it, name = "标签$it") }

    @Test fun continuousDragChangesSeveralRowsButOnlySaveCommitsAndGestureCancelRestoresDraft() {
        var saved = emptyList<Long>()
        var saves = 0
        compose.setContent { MaterialTheme { TagOrderDialog(tags, false, { saved = it; saves++ }, {}) } }
        compose.mainClock.autoAdvance = false
        val list = compose.onNodeWithTag("tag-order-list")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithTag("tag-order-drag-1").fetchSemanticsNode().boundsInRoot
        list.performTouchInput { down(Offset(handle.center.x - bounds.left, handle.center.y - bounds.top)) }
        repeat(4) {
            list.performTouchInput { moveBy(Offset(0f, handle.height * 0.75f)) }
            compose.mainClock.advanceTimeBy(80)
        }
        list.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(320)
        assertEquals(0, saves)
        list.performScrollToNode(hasTestTag("tag-order-row-2"))
        val second = compose.onNodeWithTag("tag-order-drag-2").fetchSemanticsNode().boundsInRoot
        list.performTouchInput { down(Offset(second.center.x - bounds.left, second.center.y - bounds.top)); moveBy(Offset(0f, second.height * 2.6f)) }
        compose.mainClock.advanceTimeBy(160)
        list.performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(320)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("保存").performClick()
        assertEquals(1, saves)
        assertTrue("one drag must cross several rows: $saved", saved.indexOf(1L) >= 2)
        assertEquals(2L, saved.first())
        assertEquals(tags.map { it.id }.toSet(), saved.toSet())
    }

    @Test fun keepingHandleNearBottomReachesInitiallyOffscreenTags() {
        var saved = emptyList<Long>()
        compose.setContent { MaterialTheme { TagOrderDialog(tags, false, { saved = it }, {}) } }
        compose.mainClock.autoAdvance = false
        val list = compose.onNodeWithTag("tag-order-list")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithTag("tag-order-drag-1").fetchSemanticsNode().boundsInRoot
        val initiallyVisible = compose.onAllNodes(hasTestTag("tag-order-row-12")).fetchSemanticsNodes().isNotEmpty()
        assertFalse(initiallyVisible)
        list.performTouchInput {
            down(Offset(handle.center.x - bounds.left, handle.center.y - bounds.top))
            moveTo(Offset(handle.center.x - bounds.left, bounds.height - 5))
        }
        compose.mainClock.advanceTimeBy(4000)
        list.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(320)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("保存").performClick()
        assertTrue("dragging near bottom must scroll beyond initial rows", saved.indexOf(1L) >= 6)
    }

    @Test fun aSmallDragCannotCascadeAcrossManyRowsWhileFingerStaysStill() {
        var saved = emptyList<Long>()
        compose.setContent { MaterialTheme { TagOrderDialog(tags, false, { saved = it }, {}) } }
        compose.mainClock.autoAdvance = false
        val list = compose.onNodeWithTag("tag-order-list")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithTag("tag-order-drag-2").fetchSemanticsNode().boundsInRoot
        list.performTouchInput { down(Offset(handle.center.x - bounds.left, handle.center.y - bounds.top)); moveBy(Offset(0f, handle.height * 0.75f)) }
        compose.mainClock.advanceTimeBy(2000)
        list.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(320); compose.mainClock.autoAdvance = true
        compose.onNodeWithText("保存").performClick()
        assertTrue("small stable movement must not cascade: $saved", saved.indexOf(2L) in 1..2)
        assertEquals(tags.map { it.id }.toSet(), saved.toSet())
    }

    @Test fun changedTagSetDisablesSaveAndCancelDoesNotSubmit() {
        val current = mutableStateOf(tags.take(3))
        var saves = 0
        var cancelled = false
        compose.setContent { MaterialTheme { TagOrderDialog(current.value, false, { saves++ }, { cancelled = true }) } }
        compose.onNodeWithContentDescription("下移标签1").performClick()
        compose.runOnIdle { current.value = current.value + tags[3] }
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("标签列表已变化，请取消后重新打开").assertExists()
        compose.onNodeWithText("取消").performClick()
        assertTrue(cancelled)
        assertEquals(0, saves)
    }
}
