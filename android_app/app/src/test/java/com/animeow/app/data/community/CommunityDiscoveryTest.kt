package com.animeow.app.data.community

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class CommunityDiscoveryTest {
    @Test fun tagNormalizationMatchesServerRules() {
        assertEquals("截图", normalizeCommunityTag("  #截图 "))
        assertEquals("Cos 服", normalizeCommunityTag("＃＃Cos   服"))
        assertEquals("a".repeat(16), normalizeCommunityTag("a".repeat(16)))
        assertNull(normalizeCommunityTag("a".repeat(17)))
        assertNull(normalizeCommunityTag("逗号,不行"))
        assertNull(normalizeCommunityTag("#"))
        assertNull(normalizeCommunityTag(null))
    }

    @Test fun addingTagsDedupesAndRespectsLimit() {
        var tags = emptyList<String>()
        listOf("#截图", "截图", "cos", "COS", "同人", "手办", "感想", "推荐").forEach { tags = tags.withCommunityTag(it) }
        assertEquals(listOf("截图", "cos", "同人", "手办", "感想"), tags)
        assertEquals(tags, tags.withCommunityTag("   "))
    }

    @Test fun optionalFieldsAreUnknownWhenMissing() {
        val legacy = JSONObject("""{"user_id":3}""")
        assertNull(legacy.optCount("follower_count"))
        assertNull(legacy.optNullableBoolean("is_following"))
        assertNull(parseCommunityProfileStats(legacy.optJSONObject("stats")))
        assertNull(parseCommunityProfileStats(JSONObject("{}")))
        assertTrue(parseCommunityPostTags(null).isEmpty())
    }

    @Test fun optionalFieldsParseWhenPresent() {
        val json = JSONObject("""{"is_following":1,"stats":{"post_count":"4","follower_count":2,"likes_received":null}}""")
        assertEquals(true, json.optNullableBoolean("is_following"))
        val stats = parseCommunityProfileStats(json.optJSONObject("stats"))!!
        assertEquals(4, stats.postCount)
        assertEquals(2, stats.followerCount)
        assertNull(stats.followingCount)
        assertNull(stats.likesReceived)
        assertEquals(listOf("截图", "同人"), parseCommunityPostTags(JSONArray("""["截图","#截图","同人",""]""")))
    }

    @Test fun tagListFallsBackToDefaults() {
        val list = parseCommunityTagList(JSONObject("""{"items":[{"tag":"截图","post_count":3,"is_suggested":false},{"tag":"推荐","is_suggested":true},{"tag":"bad,tag"}]}"""))
        assertEquals(listOf("截图", "推荐"), list.items.map { it.tag })
        assertTrue(list.items[1].isSuggested)
        assertEquals(COMMUNITY_MAX_POST_TAGS, list.maxTags)
        assertFalse(parseCommunityFollowResult(JSONObject("""{"following":false}""")).following)
        assertNull(parseCommunityFollowResult(JSONObject("""{"following":true}""")).followerCount)
    }

    @Test fun oldServerIgnoringFiltersIsDetected() {
        val group = CommunityGroup(1, "", "", "", true, true, null)
        val legacy = CommunityFeedPage(group, emptyList(), nextBeforeId = 9)
        assertFalse(legacy.matchesRequest("following", null))
        assertFalse(legacy.matchesRequest("newest", "截图"))
        assertTrue(legacy.matchesRequest("newest", null))
        assertTrue(legacy.matchesRequest("likes", null))
        val modern = legacy.copy(appliedSort = "following", appliedTag = "截图")
        assertTrue(modern.matchesRequest("following", "截图"))
        assertEquals(CommunityFeedCursor(beforeId = 9), legacy.nextCursor())
        assertEquals(CommunityFeedCursor(offset = 40), legacy.copy(nextOffset = 40).nextCursor())
    }

    @Test fun onlyNewEndpointsAreTreatedAsOptional() {
        assertTrue(isOptionalCommunityFeaturePath("/api/community/tags?limit=12"))
        assertTrue(isOptionalCommunityFeaturePath("/api/community/follows"))
        assertTrue(isOptionalCommunityFeaturePath("/api/community/posts/5/related?limit=6"))
        assertFalse(isOptionalCommunityFeaturePath("/api/community/posts/5"))
        assertFalse(isOptionalCommunityFeaturePath("/api/community/posts"))
    }
}
