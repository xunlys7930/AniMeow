package com.animeow.app.data.community

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.animeow.app.testing.LocalHttpServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresetImageNetworkTest {
    @Before
    fun requireLoopbackFixture() {
        assumeTrue(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
    }

    @Test
    fun cachedImagesRemainAvailableAfterNetworkFailure() = runBlocking {
        lateinit var repository: PresetImageRepository
        val expected = LocalHttpServer {
            """{"images":[{"url":"/preset-images/fixture.png","name":"fixture.png"}],"disclaimer":"Local fixture"}""".toByteArray()
        }.use { server ->
            repository = PresetImageRepository(server.baseUrl)
            val result = repository.fetchPresetImages()
            assertEquals("/preset-images/list", server.request.get(5, TimeUnit.SECONDS).path)
            assertEquals("fixture.png", result!!.images.single().name)
            assertEquals("${server.baseUrl}/preset-images/fixture.png", result.images.single().url)
            result
        }
        assertEquals(expected, repository.fetchPresetImages())
        assertEquals(expected, repository.fetchPresetImages(forceRefresh = true))
    }

    @Test
    fun invalidResponseWithoutCacheReturnsNoOptionalImages() = runBlocking {
        LocalHttpServer { "temporarily unavailable".toByteArray() }.use { server ->
            assertNull(PresetImageRepository(server.baseUrl).fetchPresetImages())
        }
    }

    @Test
    fun cancellingSlowPresetRequestDoesNotWaitForReadTimeout() = runBlocking {
        val release = CountDownLatch(1)
        LocalHttpServer {
            release.await(15, TimeUnit.SECONDS)
            """{"images":[]}""".toByteArray()
        }.use { server ->
            val repository = PresetImageRepository(server.baseUrl)
            val job = launch(Dispatchers.Main) { repository.fetchPresetImages() }
            try {
                withContext(Dispatchers.IO) { server.request.get(5, TimeUnit.SECONDS) }
                withTimeout(3_000) { job.cancelAndJoin() }
                assertTrue(job.isCancelled)
            } finally {
                release.countDown()
                job.cancelAndJoin()
            }
        }
    }
}
