package com.animeow.app.data.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class MediaSeriesBackupTest {
    private fun row(vararg fields: Pair<String, Any?>) = JSONObject().apply { fields.forEach { put(it.first, it.second ?: JSONObject.NULL) } }
    private fun table(vararg rows: JSONObject) = JSONArray(rows.toList())
    private val old = "2026-10-22T00:00:00Z"
    private val recent = "2026-10-23T00:00:00Z"

    @Test fun remapsSeriesAndCanonicalGameIdentityWithoutCrossingModules() {
        val local = row("games" to table(row("id" to 9, "syncId" to "local-game", "bangumiId" to 42, "updatedAt" to old)),
            "media_series" to table(row("id" to 1, "syncId" to "audio-series", "kind" to "AUDIO", "name" to "同名", "updatedAt" to old)))
        val remote = row("games" to table(row("id" to 1, "syncId" to "remote-game", "bangumiId" to 42, "updatedAt" to recent)),
            "media_series" to table(row("id" to 1, "syncId" to "game-series", "kind" to "GAME", "name" to "同名", "updatedAt" to recent)),
            "media_series_members" to table(row("id" to 1, "kind" to "GAME", "workSyncId" to "remote-game", "seriesId" to 1, "sortOrder" to 2, "updatedAt" to recent),
                row("id" to 2, "kind" to "AUDIO", "workSyncId" to "bad-cross-kind", "seriesId" to 1, "sortOrder" to 0, "updatedAt" to recent)))
        val merged = NativeBackupMerger.merge(local, remote, true, false)
        val series = merged.getJSONArray("media_series"); assertEquals(2, series.length())
        val member = merged.getJSONArray("media_series_members"); assertEquals(1, member.length())
        assertEquals(series.getJSONObject(1).getLong("id"), member.getJSONObject(0).getLong("seriesId"))
        assertEquals("local-game", member.getJSONObject(0).getString("workSyncId"))
        assertEquals(2, member.getJSONObject(0).getInt("sortOrder"))
    }

    @Test fun keepsMissingOldTablesAndHonorsNewerDetachAndDeletedSeries() {
        val series = row("id" to 1, "syncId" to "series", "kind" to "AUDIO", "name" to "系列", "updatedAt" to old)
        val member = row("id" to 1, "kind" to "AUDIO", "workSyncId" to "work", "seriesId" to 1, "sortOrder" to 1, "updatedAt" to old)
        val local = row("media_series" to table(series), "media_series_members" to table(member))
        val untouched = NativeBackupMerger.merge(local, JSONObject(), true, false)
        assertEquals(1, untouched.getJSONArray("media_series_members").getJSONObject(0).getInt("seriesId"))
        val detached = row("media_series" to table(JSONObject(series.toString()).put("deletedAt", recent).put("updatedAt", recent)),
            "media_series_members" to table(JSONObject(member.toString()).put("seriesId", JSONObject.NULL).put("updatedAt", recent)))
        val merged = NativeBackupMerger.merge(local, detached, true, false)
        assertTrue(merged.getJSONArray("media_series_members").getJSONObject(0).isNull("seriesId"))
        assertEquals(recent, merged.getJSONArray("media_series").getJSONObject(0).getString("deletedAt"))
        val again = NativeBackupMerger.merge(merged, local, true, false)
        assertTrue(again.getJSONArray("media_series_members").getJSONObject(0).isNull("seriesId"))
    }
}
