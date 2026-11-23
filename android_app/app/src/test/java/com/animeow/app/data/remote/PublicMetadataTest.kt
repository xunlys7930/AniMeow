package com.animeow.app.data.remote

import com.animeow.app.data.preferences.DiscoveryNetworkSettings
import com.animeow.app.data.preferences.DiscoveryPreferences
import com.animeow.app.data.preferences.PublicDataRoute
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class PublicMetadataTest {
    @Test fun configurationProfilesPreserveDirectChoiceAndDefaultOldProfilesToServer() {
        val codec = com.animeow.app.data.preferences.ConfigurationBundleCodec
        val bundle = com.animeow.app.data.preferences.ConfigurationBundle(
            appearance = com.animeow.app.ui.theme.AppearanceSettings(),
            discovery = DiscoveryNetworkSettings(publicDataRoute = PublicDataRoute.DIRECT),
        )
        val encoded = codec.toJson(bundle)
        assertEquals(PublicDataRoute.DIRECT, codec.fromJson(encoded).discovery!!.publicDataRoute)
        encoded.getJSONObject("discovery").remove("publicDataRoute")
        assertEquals(PublicDataRoute.SERVER, codec.fromJson(encoded).discovery!!.publicDataRoute)
    }

    @Test fun oldSettingsDefaultToServerAndDirectRequiresExplicitChoice() = runBlocking {
        assertEquals(PublicDataRoute.SERVER, DiscoveryNetworkSettings().publicDataRoute)
        assertEquals(PublicDataRoute.SERVER, PublicDataRoute.fromStorage(null))
        assertEquals(PublicDataRoute.SERVER, PublicDataRoute.fromStorage("future-value"))
        val preferences = DiscoveryPreferences(RuntimeEnvironment.getApplication())
        val original = preferences.snapshot()
        try {
            preferences.save(original.copy(publicDataRoute = PublicDataRoute.DIRECT))
            assertEquals(PublicDataRoute.DIRECT, preferences.snapshot().publicDataRoute)
            preferences.save(original.copy(publicDataRoute = PublicDataRoute.SERVER))
            assertEquals(PublicDataRoute.SERVER, preferences.snapshot().publicDataRoute)
        } finally { preferences.save(original) }
    }

    @Test fun wrappedCalendarRetainsWeekdayAndStaleTimestamp() {
        val response = """{"_animeow_data":[{"weekday":{"id":1},"items":[{"id":42,"name":"周一","air_date":"2026-10-05"}]}],"_animeow_cache":{"stale":true,"updatedAt":"2026-10-15T16:00:00Z"}}"""
        val items = BangumiService().parseCalendarResponse(response)
        assertEquals(1, items.single().broadcastDay)
        assertTrue(items.single().cacheStale)
        assertEquals("2026-10-15T16:00:00Z", items.single().cacheUpdatedAt)
        assertTrue(publicCacheNotice(items)!!.contains("更新于"))
    }

    @Test fun freshAndLegacyRepliesDoNotShowAnOutageWarning() {
        val legacy = BangumiService().parseSearchResponse("""{"data":[{"id":42,"name":"作品"}]}""")
        assertNull(publicCacheNotice(legacy))
        val fresh = legacy.withPublicCache("""{"_animeow_cache":{"stale":false,"updatedAt":"2026-10-15T16:00:00Z"}}""")
        assertFalse(fresh.single().cacheStale)
        assertNull(publicCacheNotice(fresh))
        assertEquals(1, publicArray("[{}]").length())
    }
}
