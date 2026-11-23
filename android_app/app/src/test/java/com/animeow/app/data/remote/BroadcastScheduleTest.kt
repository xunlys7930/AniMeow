package com.animeow.app.data.remote

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class BroadcastScheduleTest {
    private val date = LocalDate.of(2026, 10, 16)
    private val zone = ZoneId.of("Asia/Shanghai")
    private val media = """{"id":1,"title":{"native":"作品"},"episodes":12,"averageScore":85,"studios":{"nodes":[{"name":"Studio"}]}}"""
    private fun row(id: Long, time: Long, episode: Int = 2) = JSONObject().put("id", id).put("airingAt", time).put("episode", episode).put("media", JSONObject(media))
    private fun response(rows: JSONArray, page: Int = 1, more: Boolean = false) = JSONObject().put("data", JSONObject().put("Page",
        JSONObject().put("pageInfo", JSONObject().put("currentPage", page).put("hasNextPage", more)).put("airingSchedules", rows)))

    @Test fun localDayHandlesTimeZoneAndDaylightSavingInsteadOfAssuming24Hours() {
        val (start, end) = broadcastBounds(date, zone)
        assertEquals(Instant.parse("2026-10-15T16:00:00Z").epochSecond, start)
        assertEquals(86400L, end - start)
        val spring = broadcastBounds(LocalDate.of(2026, 3, 8), ZoneId.of("America/New_York"))
        val autumn = broadcastBounds(LocalDate.of(2026, 11, 1), ZoneId.of("America/New_York"))
        assertEquals(23L * 3600, spring.second - spring.first)
        assertEquals(25L * 3600, autumn.second - autumn.first)
    }

    @Test fun parserKeepsRealEpisodeTimeAndCachedFreshnessWithoutInventingMissingSchedules() {
        val start = broadcastBounds(date, zone).first
        val value = response(JSONArray().put(row(9, start + 600)).put(row(10, start + 1000, 0)).put(JSONObject.NULL))
            .put("_animeow_cache", JSONObject().put("stale", true).put("updatedAt", "2026-10-15T15:00:00Z"))
        val parsed = AniListService().parseAiringPage(value.toString(), 1)
        assertEquals(1, parsed.entries.size)
        assertEquals(2, parsed.entries.single().episode)
        assertEquals(Instant.ofEpochSecond(start + 600), parsed.entries.single().airingAt)
        assertEquals("Studio", parsed.entries.single().anime.studio)
        assertEquals(85.0, parsed.entries.single().anime.score!!, 0.0)
        assertEquals(2, parsed.warnings.size)
        assertEquals(Instant.parse("2026-10-15T15:00:00Z"), parsed.updatedAt)
        assertTrue(parsed.entries.single().anime.cacheStale)
        val missing = row(11, start).apply { getJSONObject("media").apply {
            put("description", JSONObject.NULL); put("format", JSONObject.NULL); put("siteUrl", JSONObject.NULL)
            put("studios", JSONObject().put("nodes", JSONArray().put(JSONObject().put("name", JSONObject.NULL))))
            put("coverImage", JSONObject().put("extraLarge", JSONObject.NULL).put("large", JSONObject.NULL))
        } }
        val unknown = AniListService().parseAiringPage(response(JSONArray().put(missing)).toString(), 1).entries.single().anime
        assertNull(unknown.summary); assertNull(unknown.studio); assertNull(unknown.format); assertNull(unknown.coverUrl); assertNull(unknown.siteUrl)
    }

    @Test fun malformedAndOldProxyRepliesAreErrorsButValidEmptyPageIsNot() {
        val service = AniListService()
        assertTrue(service.parseAiringPage(response(JSONArray()).toString(), 1).entries.isEmpty())
        for (raw in listOf("{}", """{"errors":[{"message":"upstream"}]}""", response(JSONArray(), page = 1).toString())) {
            try { service.parseAiringPage(raw, 2); fail("must reject unsupported pagination") } catch (_: IllegalStateException) { }
        }
    }

    @Test fun dayLoaderUsesHalfOpenDayRangeDeduplicatesAndOrdersRealInstants() = runBlocking {
        val (start, end) = broadcastBounds(date, zone)
        val anime = RemoteAnime(RemoteCatalogSource.ANILIST, "1", "作品")
        fun entry(id: Long, time: Long) = BroadcastEntry(id, anime, 2, Instant.ofEpochSecond(time))
        var calls = 0
        val result = loadBroadcastDay(date, zone) { page -> calls++
            if (page == 1) BroadcastPage(listOf(entry(2, start + 3600), entry(1, start), entry(3, end)), true)
            else BroadcastPage(listOf(entry(1, start), entry(4, start - 1)), false)
        }
        assertEquals(listOf(1L, 2L), result.entries.map { it.id })
        assertEquals(2, calls); assertTrue(result.warnings.isEmpty())
    }

    @Test fun laterFailureAndQueryLimitArePartialWhileInitialFailureAndCancellationPropagate() = runBlocking {
        val item = BroadcastEntry(1, RemoteAnime(RemoteCatalogSource.ANILIST, "1", "作品"), 1, Instant.ofEpochSecond(broadcastBounds(date, zone).first))
        val partial = loadBroadcastDay(date, zone) { if (it == 1) BroadcastPage(listOf(item), true) else error("offline") }
        assertEquals(1, partial.entries.size); assertFalse(partial.warnings.isEmpty())
        var calls = 0
        val capped = loadBroadcastDay(date, zone) { calls++; BroadcastPage(listOf(item), true) }
        assertEquals(10, calls); assertFalse(capped.warnings.isEmpty())
        try { loadBroadcastDay(date, zone) { error("offline") }; fail("initial failure") } catch (_: IllegalStateException) { }
        try { loadBroadcastDay(date, zone) { throw CancellationException() }; fail("cancel") } catch (_: CancellationException) { }
    }
}
