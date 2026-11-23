package com.animeow.app.ui.discovery

import com.animeow.app.data.remote.DiscoveryFeedMode
import org.junit.Assert.*
import org.junit.Test

class DiscoveryModulesTest {
    @Test fun dailyBroadcastWeekContainsMondayThroughSundayWithoutMovingSundayToNextWeek() {
        val sunday = java.time.LocalDate.of(2026, 10, 18)
        assertEquals(java.time.LocalDate.of(2026, 10, 12), broadcastWeek(sunday).first())
        assertEquals(sunday, broadcastWeek(sunday).last())
        assertEquals(7, broadcastWeek(sunday).distinct().size)
    }
    @Test fun novelsRemainPartOfBooksAndGamesHaveTheirOwnModule() {
        assertEquals(listOf("anime", "book", "game"), DiscoveryModule.entries.map { it.key })
        assertEquals("书籍", DiscoveryModule.BOOK.label)
        assertEquals(DiscoveryModule.ANIME, DiscoveryModule.fromKey("unknown"))
        assertEquals(DiscoveryModule.GAME, DiscoveryModule.fromKey("game"))
        assertEquals(DiscoveryModule.BOOK, DiscoveryModule.fromKey("novel"))
        assertEquals(DiscoveryModule.BOOK, DiscoveryModule.fromKey("manga"))
    }
    @Test fun seasonalOverviewIsOnlyAvailableForAnimation() {
        assertTrue(DiscoveryFeedMode.SEASONAL in discoveryFeeds("anime"))
        assertFalse(DiscoveryFeedMode.SEASONAL in discoveryFeeds("book"))
        assertTrue(discoveryFeeds("game").isEmpty())
    }
}
