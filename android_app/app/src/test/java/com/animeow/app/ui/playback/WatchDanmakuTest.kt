package com.animeow.app.ui.playback

import org.junit.Assert.*
import org.junit.Test

class WatchDanmakuTest {
    @Test fun equalSpeedLanesDoNotOverlapAndDenseBurstsAreDroppedInsteadOfQueuedIntoTheWrongTime() {
        val rows = (0..9).map { watchTestItem(it.toString()).copy(kind = "danmaku", timeMs = 0) }
        val layout = danmakuLanes(rows, 400f, 4, 100f) { 100f }
        assertEquals(2, layout.size); assertEquals(2, layout.map { it.lane }.distinct().size)
        assertTrue(layout.all { it.duration == 5000L })
    }
    @Test fun fixedMessagesWaitUntilTheLaneIsFullyEmptyEvenIfAScrollingTailHasClearedTheEntrance() {
        val rows = listOf(watchTestItem("1").copy(timeMs = 0), watchTestItem("2").copy(timeMs = 1500, mode = 5))
        assertEquals(1, danmakuLanes(rows, 400f, 1, 100f) { 100f }.size)
    }
    @Test fun hiddenAndSpoilerBodiesAreNeverDrawnAndMalformedViewportDoesNotAllocateLanes() {
        val row = watchTestItem().copy(timeMs = 0)
        assertTrue(danmakuLanes(listOf(row.copy(spoiler = true), row.copy(unavailable = true)), 400f, 4, 100f) { 100f }.isEmpty())
        assertTrue(danmakuLanes(listOf(row), 0f, 4, 100f) { 100f }.isEmpty())
    }
}
