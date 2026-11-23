package com.animeow.app.data.community

import android.app.Application
import com.animeow.app.data.cloud.CloudSession
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36], application = Application::class)
class CommunityIdentityCacheTest {
    @Test fun identitySurvivesRecreationButNeverAppearsInAnotherLogin() {
        val context = RuntimeEnvironment.getApplication()
        val cache = CommunityIdentityCache(context)
        val session = CloudSession(7, "first", "private-session-token")
        cache.write(session, JSONObject().put("user_id", 7).put("nickname", "已保存昵称"), null)
        assertEquals("已保存昵称", CommunityIdentityCache(context).read(session)?.optString("nickname"))
        assertNull(cache.read(session.copy(token = "another-login")))
        assertNull(cache.read(session.copy(userId = 8)))
        assertNull(cache.read(null))
        val file = java.io.File(context.noBackupFilesDir, "community_identity.json")
        assertFalse(file.readText().contains(session.token))
        cache.clear()
        assertNull(cache.read(session))
        assertFalse(file.exists())
    }

    @Test fun malformedOrMismatchedIdentityDoesNotReplaceCurrentProfile() {
        val context = RuntimeEnvironment.getApplication()
        val cache = CommunityIdentityCache(context)
        val session = CloudSession(7, "first", "session-token")
        cache.write(session, JSONObject().put("user_id", 7), null)
        cache.write(session, JSONObject().put("user_id", 8), null)
        assertEquals(7L, cache.read(session)?.optLong("user_id"))
        java.io.File(context.noBackupFilesDir, "community_identity.json").writeText("broken")
        assertNull(cache.read(session))
        cache.clear()
    }
}
