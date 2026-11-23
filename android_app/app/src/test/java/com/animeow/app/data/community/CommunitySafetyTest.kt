package com.animeow.app.data.community

import com.animeow.app.data.cloud.feedbackItemFromJson
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class CommunitySafetyTest {
    @Test fun reminderWatermarksDoNotRepeatSeenTasksAcrossDevices() {
        val task = CommunityReviewSummary(pendingCount = 3, latestEventId = 18, seenThroughId = 12)
        assertTrue(task.needsNotification(17))
        assertFalse(task.needsNotification(18))
        assertFalse(task.copy(seenThroughId = 18).needsNotification(0))
        assertFalse(task.copy(pendingCount = 0).needsNotification(0))
        assertTrue(task.copy(latestEventId = 19).needsNotification(18))
    }

    @Test fun authorizationOnlyFollowsTheExactCommunityOrigin() {
        val base = "https://community.example/api"
        assertTrue(sameCommunityOrigin("https://community.example:443/api/avatar/1", base))
        listOf("http://community.example/a", "https://community.example.evil/a", "https://other.example/a", "https://community.example:444/a", "https://secret@community.example/a", "file:///avatar.png").forEach {
            assertFalse(it, sameCommunityOrigin(it, base))
        }
    }

    @Test fun feedbackHonorsLegacyNumericRolesAndRejectsForeignAvatarUrls() {
        val legacy = feedbackItemFromJson(JSONObject("""{"id":1,"username":"开发者","is_developer":1,"avatar_url":null,"nickname":null}"""), "https://community.example")
        assertTrue(legacy.isDeveloper)
        assertEquals("开发者", legacy.nickname)
        assertNull(legacy.avatarUrl)
        val profile = JSONObject("""{"id":2,"username":"喵友","avatar_url":"/api/community/avatar/2?v=3"}""")
        assertEquals("https://community.example/api/community/avatar/2?v=3", feedbackItemFromJson(profile, "https://community.example").avatarUrl)
        profile.put("avatar_url", "https://foreign.example/photo")
        assertNull(feedbackItemFromJson(profile, "https://community.example").avatarUrl)
    }

    @Test fun feedbackUsesTheExistingAvatarEndpointOnlyWhenOldServersOmitTheField() {
        val legacy = JSONObject("""{"id":7,"user_id":4,"username":"喵友"}""")
        assertEquals("https://community.example/api/community/avatar/4", feedbackItemFromJson(legacy, "https://community.example").avatarUrl)
        legacy.put("avatar_url", JSONObject.NULL)
        assertNull(feedbackItemFromJson(legacy, "https://community.example").avatarUrl)
        legacy.remove("avatar_url")
        legacy.put("user_id", 0)
        assertNull(feedbackItemFromJson(legacy, "https://community.example").avatarUrl)
    }
}
