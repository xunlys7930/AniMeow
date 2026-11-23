package com.animeow.app.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.animeow.app.data.remote.RemoteAnime
import com.animeow.app.data.remote.RemoteCatalogSource
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParallelCoverLoadingTest {
    @Test fun decodableCoverWinsRealConcurrentRequestsAndIsCachedOffline() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newCachedThreadPool()
        val started = Collections.synchronizedSet(mutableSetOf<String>())
        val allStarted = CountDownLatch(3)
        val slowCancelled = CountDownLatch(1)
        val base = "http://127.0.0.1:${server.localPort}"
        executor.execute {
            repeat(3) {
                runCatching { server.accept() }.getOrNull()?.let { socket -> executor.execute {
                    socket.use {
                        socket.soTimeout = 6000
                        val input = socket.getInputStream().bufferedReader()
                        val path = input.readLine().split(' ')[1]
                        while (!input.readLine().isNullOrEmpty()) { }
                        started.add(path)
                        allStarted.countDown()
                        check(allStarted.await(5, TimeUnit.SECONDS)) { "Sources were requested sequentially" }
                        if (path == "/slow") {
                            if (runCatching { input.read() }.getOrDefault(-1) == -1) slowCancelled.countDown()
                        } else {
                            val body = if (path == "/invalid") "not an image".toByteArray() else png
                            // The invalid HTTP 200 deliberately arrives before the real image.
                            if (path == "/valid") Thread.sleep(100)
                            val header = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                            runCatching { socket.getOutputStream().apply { write(header.toByteArray()); write(body); flush() } }
                        }
                    }
                } }
            }
        }
        val loader = ImageLoader.Builder(context).components { add(PersistentRemoteImageInterceptor(context)) }.build()
        try {
            RemoteCoverAlternatives(context).remember(RemoteAnime(RemoteCatalogSource.BANGUMI, "race", "并发封面", coverUrl = "$base/slow",
                coverUrls = mapOf("bangumi" to "$base/invalid", "anilist" to "$base/valid")))
            // Refreshing one source must retain the valid alternative learned from the server.
            RemoteCoverAlternatives(context).remember(RemoteAnime(RemoteCatalogSource.BANGUMI, "race", "并发封面", coverUrl = "$base/slow",
                coverUrls = mapOf("bangumi" to "$base/invalid")))
            val request = ImageRequest.Builder(context).data("$base/slow").allowHardware(false)
                .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build()
            val result = withTimeout(10_000) { loader.execute(request) }
            assertTrue(result.toString(), result is SuccessResult)
            assertEquals(Color.GREEN, ((result as SuccessResult).drawable as BitmapDrawable).bitmap.getPixel(0, 0))
            assertEquals(setOf("/slow", "/invalid", "/valid"), started.toSet())
            assertTrue("Losing request must release its socket", withContext(Dispatchers.IO) { slowCancelled.await(3, TimeUnit.SECONDS) })
            server.close()
            assertTrue(withTimeout(3_000) { loader.execute(request) } is SuccessResult)
        } finally {
            server.close()
            executor.shutdownNow()
            loader.shutdown()
        }
    }
}
