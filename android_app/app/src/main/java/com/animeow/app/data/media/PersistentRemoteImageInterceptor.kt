package com.animeow.app.data.media

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import coil.intercept.Interceptor
import coil.request.ImageResult
import com.animeow.app.data.preferences.DiscoveryNetworkSettings
import com.animeow.app.data.preferences.DiscoveryPreferences
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class PersistentRemoteImageInterceptor(context: Context) : Interceptor {
    private val preferences = DiscoveryPreferences(context.applicationContext)
    private val store = PersistentRemoteImageStore(context.applicationContext)

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val source = chain.request.data.remoteUrlOrNull() ?: return chain.proceed(chain.request)
        val settings = runCatching { preferences.snapshot() }.getOrDefault(DiscoveryNetworkSettings())
        val localFile = store.resolve(source, settings) ?: return chain.proceed(chain.request)
        return chain.proceed(chain.request.newBuilder().data(localFile).build())
    }
}

private class PersistentRemoteImageStore(context: Context) {
    private val directory = File(context.filesDir, REMOTE_COVER_DIRECTORY).apply { mkdirs() }
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun resolve(source: String, settings: DiscoveryNetworkSettings): File? = withContext(Dispatchers.IO) {
        val canonical = canonicalRemoteImageUrl(source, settings.bangumiImageProxyBase) ?: return@withContext null
        val key = canonical.sha256()
        val destination = File(directory, "$key.jpg")
        val failureMarker = File(directory, "$key.failed")
        locks.getOrPut(key) { Mutex() }.withLock {
            if (destination.isUsableImage()) {
                destination.setLastModified(System.currentTimeMillis())
                failureMarker.delete()
                return@withLock destination
            }
            if (destination.exists()) destination.delete()
            if (failureMarker.isRecentFailure()) return@withLock null

            val candidates = buildList {
                if (settings.useBangumiImageProxy) {
                    bangumiImageProxyFallbackUrl(canonical, settings.bangumiImageProxyBase)?.let(::add)
                }
                add(canonical)
            }.distinct()
            for (candidate in candidates) {
                if (downloadValidated(candidate, destination)) {
                    failureMarker.delete()
                    pruneIfNeeded(destination)
                    return@withLock destination
                }
            }
            failureMarker.apply {
                parentFile?.mkdirs()
                runCatching { writeText(System.currentTimeMillis().toString()) }
                setLastModified(System.currentTimeMillis())
            }
            null
        }
    }

    private fun downloadValidated(source: String, destination: File): Boolean {
        val temporary = File(directory, "${destination.name}.part")
        temporary.delete()
        val connection = runCatching { URL(source).openConnection() as HttpURLConnection }.getOrNull() ?: return false
        return try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val status = connection.responseCode
            if (status !in 200..299) return false
            val declaredLength = connection.contentLengthLong
            if (declaredLength > MAX_IMAGE_BYTES) return false
            var copied = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_IMAGE_BYTES) throw IOException("图片文件过大")
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (!temporary.isUsableImage()) return false
            destination.parentFile?.mkdirs()
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            destination.setLastModified(System.currentTimeMillis())
            true
        } catch (_: Throwable) {
            false
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    private fun File.isUsableImage(): Boolean {
        if (!isFile || length() <= 0L || length() > MAX_IMAGE_BYTES) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(absolutePath, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    private fun File.isRecentFailure(): Boolean =
        isFile && System.currentTimeMillis() - lastModified() < FAILURE_RETRY_DELAY_MS

    private fun pruneIfNeeded(activeFile: File) {
        val cached = directory.listFiles { file -> file.isFile && file.extension.equals("jpg", true) }
            ?.sortedBy(File::lastModified)
            .orEmpty()
        var total = cached.sumOf(File::length)
        if (total <= MAX_CACHE_BYTES) return
        for (candidate in cached) {
            if (candidate == activeFile) continue
            val size = candidate.length()
            if (candidate.delete()) total -= size
            if (total <= CACHE_PRUNE_TARGET_BYTES) break
        }
    }

    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        // Keep recreatable network images outside filesDir/covers so native/cloud backups
        // stay small and do not change merely because the user scrolled through more covers.
        const val REMOTE_COVER_DIRECTORY = "remote_image_cache/covers"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        const val MAX_CACHE_BYTES = 256L * 1024 * 1024
        const val CACHE_PRUNE_TARGET_BYTES = 224L * 1024 * 1024
        const val FAILURE_RETRY_DELAY_MS = 60L * 60 * 1_000
        const val USER_AGENT = "AniMeow/2.0 Android cover-cache"
    }
}

private fun Any.remoteUrlOrNull(): String? = when (this) {
    is String -> takeIf(::isRemoteImageUrl)
    is Uri -> toString().takeIf(::isRemoteImageUrl)
    else -> null
}
