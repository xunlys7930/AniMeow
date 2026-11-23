package com.animeow.app.data.community

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.ImageLoader
import coil.disk.DiskCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(coil.annotation.ExperimentalCoilApi::class)
class OwnAvatarCacheTest {
    @Test fun privateNoCacheAvatarCanBeDecodedOfflineWithoutMemoryCache() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nCache-Control: private, no-cache\r\nContent-Type: image/png\r\nContent-Length: ${png.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(png); flush()
                }
            }
        }
        val directory = File(context.cacheDir, "own-avatar-qa-${System.nanoTime()}")
        val cache = DiskCache.Builder().directory(directory).maxSizeBytes(1024 * 1024).build()
        val loader = ImageLoader.Builder(context).diskCache(cache).components { add(CachedOwnAvatarInterceptor { cache }) }.build()
        try {
            val key = "community:synthetic:${server.localPort}"
            val request = ImageRequest.Builder(context).data("http://127.0.0.1:${server.localPort}/avatar")
                .diskCacheKey(key).memoryCacheKey(key).memoryCachePolicy(CachePolicy.DISABLED).allowHardware(false)
                .addHeader("Authorization", "Bearer synthetic-avatar-test")
                .setParameter(CACHE_OWN_AVATAR_PARAMETER, true).build()
            assertTrue(withTimeout(6000) { loader.execute(request) } is SuccessResult)
            assertTrue("Private no-cache response is retained only for the explicitly cached own avatar", cache.openSnapshot(key)?.use { true } == true)
            server.close()
            val offline = withTimeout(3000) { loader.execute(request) }
            assertTrue(offline.toString(), offline is SuccessResult)
            assertEquals(Color.YELLOW, ((offline as SuccessResult).drawable as BitmapDrawable).bitmap.getPixel(0, 0))
        } finally {
            server.close(); executor.shutdownNow(); loader.shutdown(); cache.clear(); directory.deleteRecursively()
        }
    }
}
