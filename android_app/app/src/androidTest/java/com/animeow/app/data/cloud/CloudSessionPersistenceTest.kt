package com.animeow.app.data.cloud

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.announcements.AnnouncementService
import com.animeow.app.data.community.CommunityService
import com.animeow.app.testing.ControlledHttpServer
import com.animeow.app.testing.TestHttpResponse
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudSessionPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = SecureCloudSessionStore(context)
    private val earlier = CloudSession(9001, "local_fixture", "earlier-fixture-session")
    private val latest = earlier.copy(token = "latest-fixture-session")

    @Before fun before() { store.clear() }
    @After fun after() { store.clear() }

    @Test fun encryptedLegacyFormatSurvivesStoreRecreationAndDeviceClockExpiry() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":1}""".toByteArray())
        val fixture = earlier.copy(token = "header.$payload.signature")
        store.save(fixture)
        val preferences = context.getSharedPreferences("cloud_account_secure", Context.MODE_PRIVATE)
        assertNotEquals(fixture.token, preferences.getString("token_ciphertext", null))
        assertEquals(fixture, SecureCloudSessionStore(context).load())
        assertTrue(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("animeow_cloud_session_v1"))
    }

    @Test fun unreadableCredentialsArePreservedAndCanBeReadAfterRecovery() {
        store.save(earlier)
        val preferences = context.getSharedPreferences("cloud_account_secure", Context.MODE_PRIVATE)
        val ciphertext = preferences.getString("token_ciphertext", null)
        preferences.edit().putString("token_ciphertext", "temporarily-unreadable").commit()
        assertNull(store.load())
        assertEquals(earlier.userId, preferences.getLong("user_id", -1))
        assertEquals("temporarily-unreadable", preferences.getString("token_ciphertext", null))
        preferences.edit().putString("token_ciphertext", ciphertext).commit()
        assertEquals(earlier, SecureCloudSessionStore(context).load())
    }

    @Test fun concurrentFirstSaveCannotReplaceAnotherStoresEncryptionKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("animeow_cloud_session_v1") }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val start = CountDownLatch(1)
            val jobs = (1..12).map { index -> pool.submit {
                check(start.await(5, TimeUnit.SECONDS))
                SecureCloudSessionStore(context).save(earlier.copy(token = "fixture-$index"))
                assertNotNull(SecureCloudSessionStore(context).load())
            } }
            start.countDown()
            jobs.forEach { it.get(15, TimeUnit.SECONDS) }
            store.save(latest)
            assertEquals(latest, SecureCloudSessionStore(context).load())
        } finally { pool.shutdownNow() }
    }

    @Test fun oldAnnouncementGateway401DoesNotLogOutAValidSession() = runBlocking {
        store.save(latest)
        ControlledHttpServer { TestHttpResponse(401, """{"message":"Unauthorized: Access Denied"}""") }.use { server ->
            val failure = runCatching { AnnouncementService(context, apiBase = server.baseUrl).list() }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(failure is CloudSessionExpiredException)
            assertEquals(latest, store.load())
        }
    }

    @Test fun lateCloud401CannotClearANewerLogin() = runBlocking {
        verifyDelayedRejection { base -> accountService(base).listBackups(earlier) }
    }

    @Test fun lateCommunity401CannotClearANewerLogin() = runBlocking {
        verifyDelayedRejection { base -> CommunityService(context, apiBase = base).getProfile(earlier, earlier.userId) }
    }

    @Test fun confirmedRejectionClearsOnlyItsOwnSession() = runBlocking {
        store.save(earlier)
        ControlledHttpServer { TestHttpResponse(401, """{"message":"请先登录账号"}""") }.use { server ->
            val failure = runCatching { accountService(server.baseUrl).listBackups(earlier) }.exceptionOrNull()
            assertTrue(failure is CloudSessionExpiredException)
            assertNull(store.load())
            assertTrue((failure as CloudSessionExpiredException).isFor(earlier))
            assertFalse(failure.isFor(latest))
        }
    }

    @Test fun successfulLoginRemainsSavedAfterOptionalServiceFails() = runBlocking {
        ControlledHttpServer { request ->
            if (request.path == "/api/auth/login") TestHttpResponse(body = """{"status":"success","data":{"user":{"id":9001,"username":"local_fixture"},"token":"latest-fixture-session"}}""")
            else TestHttpResponse(401, """{"message":"Unauthorized: Access Denied"}""")
        }.use { server ->
            val session = accountService(server.baseUrl).login("local_fixture", "fixture-password")
            runCatching { AnnouncementService(context, apiBase = server.baseUrl).list() }
            assertEquals(latest, session)
            assertEquals(session, SecureCloudSessionStore(context).load())
        }
    }

    private suspend fun verifyDelayedRejection(request: suspend (String) -> Any) = kotlinx.coroutines.coroutineScope {
        store.save(earlier)
        val release = CountDownLatch(1)
        ControlledHttpServer {
            check(release.await(10, TimeUnit.SECONDS))
            TestHttpResponse(401, """{"message":"登录会话已失效，请重新登录"}""")
        }.use { server ->
            try {
                val pending = async(Dispatchers.IO) { runCatching { request(server.baseUrl) }.exceptionOrNull() }
                server.awaitRequest()
                SecureCloudSessionStore(context).save(latest)
                release.countDown()
                assertTrue(pending.await() is CloudSessionExpiredException)
                assertEquals(latest, store.load())
            } finally { release.countDown() }
        }
    }

    private fun accountService(base: String): CloudAccountService {
        val app = context.applicationContext as AniMeowApplication
        return CloudAccountService(context, app.nativeBackupService, app.legacyBackupImporter, apiBase = base)
    }
}
