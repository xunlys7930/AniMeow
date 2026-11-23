package com.animeow.app.data.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.animeow.app.testing.ControlledHttpServer
import com.animeow.app.testing.TestHttpResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateDownloadResolverTest {
    @Test fun relativeRedirectsResolveToApkWithoutForwardingAnyAccountHeaders() = runBlocking {
        ControlledHttpServer { request ->
            when (request.path) {
                "/api/download/latest" -> TestHttpResponse(302, headers = mapOf("Location" to "../../packages/app.apk"))
                "/packages/app.apk" -> TestHttpResponse(headers = mapOf("Content-Type" to "application/vnd.android.package-archive"))
                else -> TestHttpResponse(404)
            }
        }.use { server ->
            assertEquals(server.baseUrl + "/packages/app.apk", UpdateDownloadResolver().resolve(server.baseUrl + "/api/download/latest"))
            repeat(2) { assertFalse(server.awaitRequest().headers.containsKey("authorization")) }
        }
    }

    @Test fun olderServerWithoutHeadUsesOneByteRange() = runBlocking {
        ControlledHttpServer { request ->
            if (request.method == "HEAD") TestHttpResponse(405)
            else TestHttpResponse(206, "P", mapOf("Content-Type" to "application/octet-stream"))
        }.use { server ->
            assertEquals(server.baseUrl + "/app.apk", UpdateDownloadResolver().resolve(server.baseUrl + "/app.apk"))
            assertEquals("HEAD", server.awaitRequest().method)
            val fallback = server.awaitRequest()
            assertEquals("GET", fallback.method)
            assertEquals("bytes=0-0", fallback.headers["range"])
        }
    }

    @Test fun htmlErrorPageAndRedirectLoopNeverBecomeAnApkDownload() = runBlocking {
        ControlledHttpServer { TestHttpResponse(200, "<html>error</html>", mapOf("Content-Type" to "text/html")) }.use { server ->
            assertTrue(runCatching { UpdateDownloadResolver().resolve(server.baseUrl + "/app.apk") }.exceptionOrNull()?.message.orEmpty().contains("网页"))
        }
        ControlledHttpServer { TestHttpResponse(307, headers = mapOf("Location" to "/loop")) }.use { server ->
            assertTrue(runCatching { UpdateDownloadResolver().resolve(server.baseUrl + "/loop") }.exceptionOrNull()?.message.orEmpty().contains("重复跳转"))
        }
    }
}
