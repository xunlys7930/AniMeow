package com.animeow.app.ui

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.SecureCloudSessionStore
import com.animeow.app.testing.*
import com.animeow.app.ui.community.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ManagementPagingDataTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as AniMeowApplication
    private val sessions = SecureCloudSessionStore(app)
    private val models = ViewModelStore()
    private val group = """{"id":1,"name":"合成测试社区","is_active":true}"""
    private val profile = """{"user_id":9001,"username":"local_fixture","nickname":"合成管理员","is_developer":true,"is_admin":true}"""

    @Before fun before() { sessions.save(CloudSession(9001, "local_fixture", "offline-management-fixture")) }
    @After fun after() { main { models.clear() }; sessions.clear() }

    @Test fun sensitiveWordSearchDiscardsLateOldResults() {
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        ControlledHttpServer { request ->
            bootstrap(request) ?: run {
                val query = Uri.parse("http://fixture" + request.path).getQueryParameter("search").orEmpty()
                if (query == "旧") { oldStarted.countDown(); check(releaseOld.await(8, TimeUnit.SECONDS)) }
                words(1, 1, query)
            }
        }.use { server ->
            try {
                val vm = community(server.baseUrl)
                main { vm.searchSensitiveWords("旧") }
                assertTrue(oldStarted.await(5, TimeUnit.SECONDS))
                main { vm.searchSensitiveWords("新") }
                await { vm.state.value.sensitiveWords.firstOrNull()?.word == "新1" }
                releaseOld.countDown()
                Thread.sleep(250)
                assertEquals("新1", vm.state.value.sensitiveWords.single().word)
                assertEquals(1, vm.state.value.sensitiveWordsPage)
                assertFalse(vm.state.value.sensitiveWordsLoading)
            } finally { releaseOld.countDown() }
        }
    }

    @Test fun deletingTheOnlyWordOnLastPageReturnsToPreviousNonEmptyPage() {
        val total = AtomicInteger(101)
        ControlledHttpServer { request ->
            bootstrap(request) ?: if (request.method == "DELETE") {
                total.set(100); TestHttpResponse(body = """{"status":"success"}""")
            } else {
                val page = Uri.parse("http://fixture" + request.path).getQueryParameter("page")?.toIntOrNull() ?: 1
                words(page, total.get())
            }
        }.use { server ->
            val vm = community(server.baseUrl)
            main { vm.loadSensitiveWordsPage(3) }
            await { vm.state.value.sensitiveWordsPage == 3 && !vm.state.value.sensitiveWordsLoading }
            assertEquals(101L, vm.state.value.sensitiveWords.single().id)
            main { vm.deleteSensitiveWord(101) }
            await { vm.state.value.sensitiveWordsPage == 2 && !vm.state.value.sensitiveWordsLoading }
            assertEquals(100, vm.state.value.sensitiveWordsTotal)
            assertEquals(50, vm.state.value.sensitiveWords.size)
            assertEquals(51L, vm.state.value.sensitiveWords.first().id)
        }
    }

    @Test fun failedPageNavigationPreservesPreviousPageAndCanBeRetried() {
        val fail = AtomicBoolean(true)
        ControlledHttpServer { request ->
            bootstrap(request) ?: run {
                val page = Uri.parse("http://fixture" + request.path).getQueryParameter("page")?.toIntOrNull() ?: 1
                if (page == 2 && fail.get()) TestHttpResponse(503, """{"message":"服务暂忙"}""") else words(page, 130)
            }
        }.use { server ->
            val vm = community(server.baseUrl)
            main { vm.refreshSensitiveWords() }
            await { vm.state.value.sensitiveWords.size == 50 }
            main { vm.loadSensitiveWordsPage(2) }
            await { vm.state.value.sensitiveWordsError != null }
            assertEquals(1, vm.state.value.sensitiveWordsPage)
            assertEquals(1L, vm.state.value.sensitiveWords.first().id)
            assertNotNull(sessions.load())
            fail.set(false)
            main { vm.loadSensitiveWordsPage(2) }
            await { vm.state.value.sensitiveWordsPage == 2 }
            assertNull(vm.state.value.sensitiveWordsError)
        }
    }

    @Test fun cursorUsersJumpBackInOneRequestAndSearchStartsANewIndex() {
        val requests = CopyOnWriteArrayList<Long>()
        ControlledHttpServer { request ->
            val uri = Uri.parse("http://fixture" + request.path)
            val start = uri.getQueryParameter("after_id")?.toLongOrNull() ?: 0L
            val query = uri.getQueryParameter("q").orEmpty()
            requests += start
            users(start, query)
        }.use { server ->
            lateinit var vm: CommunitySafetyViewModel
            main { vm = CommunitySafetyViewModel(app, server.baseUrl); models.put("safety", vm); vm.loadUserPage(4) }
            await { vm.state.value.userPage == 4 && !vm.state.value.loading }
            assertEquals(listOf(0L, 30L, 60L, 90L), requests.toList())
            assertEquals(30, vm.state.value.users.size)
            requests.clear()
            main { vm.loadUserPage(2) }
            await { vm.state.value.userPage == 2 && !vm.state.value.loading }
            assertEquals(listOf(30L), requests.toList())
            main { vm.loadUsers("新查询") }
            await { vm.state.value.userPage == 1 && !vm.state.value.loading }
            assertEquals("新查询1", vm.state.value.users.first().profile.nickname)
            assertEquals(30, vm.state.value.users.size)
        }
    }

    @Test fun cancellingDistantCursorJumpKeepsCurrentPageAndStopsLoading() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        ControlledHttpServer { request ->
            val start = Uri.parse("http://fixture" + request.path).getQueryParameter("after_id")?.toLongOrNull() ?: 0L
            if (start >= 30) { started.countDown(); check(release.await(8, TimeUnit.SECONDS)) }
            users(start, "")
        }.use { server ->
            try {
                lateinit var vm: CommunitySafetyViewModel
                main { vm = CommunitySafetyViewModel(app, server.baseUrl); models.put("safety", vm); vm.loadUsers() }
                await { vm.state.value.users.size == 30 }
                main { vm.loadUserPage(40) }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                main { vm.cancelPageLoad() }
                release.countDown()
                Thread.sleep(150)
                assertFalse(vm.state.value.loading)
                assertEquals(1, vm.state.value.userPage)
                assertEquals(1L, vm.state.value.users.first().profile.userId)
            } finally { release.countDown() }
        }
    }

    private fun community(base: String): CommunityViewModel {
        lateinit var vm: CommunityViewModel
        main { vm = CommunityViewModel(app, SavedStateHandle(), base); models.put("community", vm) }
        await { vm.state.value.currentUser?.isDeveloper == true && !vm.state.value.loading }
        return vm
    }
    private fun bootstrap(request: TestHttpRequest): TestHttpResponse? = when {
        request.path.startsWith("/api/community/bootstrap") -> TestHttpResponse(body = """{"status":"success","data":{"current_user":$profile,"group":$group}}""")
        request.path.startsWith("/api/community/posts?") -> TestHttpResponse(body = """{"status":"success","data":{"group":$group,"items":[]}}""")
        else -> null
    }
    private fun words(page: Int, total: Int, prefix: String = "测试词"): TestHttpResponse {
        val rows = JSONArray()
        for (id in ((page - 1) * 50 + 1)..minOf(page * 50, total)) rows.put(JSONObject().put("id", id).put("word", "$prefix$id"))
        return TestHttpResponse(body = JSONObject().put("status", "success").put("data", rows).put("pagination",
            JSONObject().put("total", total).put("page", page).put("pageSize", 50)).toString())
    }
    private fun users(start: Long, prefix: String): TestHttpResponse {
        val rows = JSONArray()
        for (id in start + 1..minOf(start + 30, 300)) rows.put(JSONObject(profile).put("user_id", id).put("nickname", "$prefix$id"))
        return TestHttpResponse(body = JSONObject().put("status", "success").put("data", JSONObject().put("items", rows)
            .put("next_after_id", if (start + 30 < 300) start + 30 else JSONObject.NULL)).toString())
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val limit = System.currentTimeMillis() + 7000
        while (!condition() && System.currentTimeMillis() < limit) Thread.sleep(20)
        assertTrue("Local fixture condition did not complete", condition())
    }
}
