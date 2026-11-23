package com.animeow.app.ui.tracker

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.SeriesEntity
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
class SeriesGroupingInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val first = LibraryDisplayItem.AnimeItem(AnimeEntity(id = 1, title = "约会大作战"))
    private val second = LibraryDisplayItem.AnimeItem(AnimeEntity(id = 2, title = "约会大作战 第二季"))

    @Test fun releaseAfterHoldSelectsAndDragRequiresExplicitConfirmation() {
        var selections = 0; var clicks = 0; var saves = 0
        compose.setContent { MaterialTheme {
            SeriesGroupingHost({ source, target, _ -> assertEquals(1, source.id); assertEquals(second, target); saves++ }, {}) {
                Column(Modifier.fillMaxSize().testTag("drag-host")) {
                    listOf(first, second).forEach { item ->
                        Surface(Modifier.fillMaxWidth().height(120.dp).testTag(item.stableKey)
                            .libraryItemClick(item, { selections++ }, { clicks++ })) { Text(item.title) }
                    }
                }
            }
        } }
        compose.onNodeWithTag(first.stableKey).performTouchInput { longClick() }
        assertEquals(1, selections); assertEquals(0, clicks)
        dragToSecond()
        compose.onNodeWithText("组成新系列").assertExists()
        assertEquals(0, saves); assertEquals(1, selections)
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, saves)
        dragToSecond()
        compose.onNodeWithText("确认归组").performClick()
        assertEquals(1, saves)
    }

    @Test fun cancelOutsideAndSameSeriesAreSafeAndExistingSeriesIsAValidTarget() {
        val member = first.anime.copy(seriesId = 5)
        val series = LibraryDisplayItem.SeriesItem(SeriesEntity(id = 5, name = "约会系列"), listOf(member))
        assertFalse(canGroupDrop(member, series))
        assertFalse(canGroupDrop(member, LibraryDisplayItem.AnimeItem(member)))
        assertFalse(canGroupDrop(member, second.copy(anime = second.anime.copy(seriesId = 5))))
        assertTrue(canGroupDrop(second.anime, series))
        var saves = 0
        compose.setContent { MaterialTheme { SeriesGroupingHost({ _, _, _ -> saves++ }, {}) {
            Column(Modifier.fillMaxSize().testTag("drag-host")) {
                Surface(Modifier.fillMaxWidth().height(120.dp).testTag(first.stableKey).libraryItemClick(first, {}, {})) { Text(first.title) }
                Surface(Modifier.fillMaxWidth().height(120.dp).testTag(second.stableKey).libraryItemClick(second, {}, {})) { Text(second.title) }
            }
        } } }
        val host = compose.onNodeWithTag("drag-host")
        host.performTouchInput { down(Offset(80f, 80f)); advanceEventTime(650); moveTo(Offset(80f, 350f)); cancel() }
        compose.onNodeWithText("组成新系列").assertDoesNotExist()
        host.performTouchInput { down(Offset(80f, 80f)); advanceEventTime(650); moveTo(Offset(80f, 1200f)); up() }
        compose.onNodeWithText("组成新系列").assertDoesNotExist()
        assertEquals(0, saves)
    }

    private fun dragToSecond() {
        val start = compose.onNodeWithTag(first.stableKey).fetchSemanticsNode().boundsInRoot.center
        val end = compose.onNodeWithTag(second.stableKey).fetchSemanticsNode().boundsInRoot.center
        val host = compose.onNodeWithTag("drag-host")
        val origin = host.fetchSemanticsNode().boundsInRoot.topLeft
        host.performTouchInput { down(start - origin); advanceEventTime(650); moveTo(end - origin); up() }
    }

    @Test fun edgeScrollKeepsTheGestureWhenTheOriginalCardLeavesTheScreen() {
        val works = (1L..30L).map { LibraryDisplayItem.AnimeItem(AnimeEntity(id = it, title = "作品$it")) }
        var groupedWith = 0L
        compose.setContent { MaterialTheme { SeriesGroupingHost({ _, target, _ -> groupedWith = (target as LibraryDisplayItem.AnimeItem).anime.id }, {}) {
            val scroll = rememberLazyListState()
            SeriesDragAutoScroll(scroll)
            LazyColumn(Modifier.fillMaxSize().testTag("drag-host"), state = scroll) {
                items(works, key = { it.stableKey }) { item ->
                    Surface(Modifier.fillMaxWidth().height(100.dp).testTag(item.stableKey).libraryItemClick(item, {}, {})) { Text(item.title) }
                }
            }
        } } }
        val host = compose.onNodeWithTag("drag-host")
        val bounds = host.fetchSemanticsNode().boundsInRoot
        val start = compose.onNodeWithTag("anime:1").fetchSemanticsNode().boundsInRoot.center - bounds.topLeft
        compose.mainClock.autoAdvance = false
        host.performTouchInput { down(start); advanceEventTime(650); moveTo(Offset(start.x, bounds.height - 20)) }
        compose.mainClock.advanceTimeBy(2400)
        host.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("确认归组").performClick()
        assertTrue("dragging must reach an initially offscreen work: $groupedWith", groupedWith > 8)
    }
}
