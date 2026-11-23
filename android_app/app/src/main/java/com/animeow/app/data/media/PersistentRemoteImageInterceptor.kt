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
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal class PersistentRemoteImageInterceptor(context: Context) : Interceptor {
    private val preferences = DiscoveryPreferences(context.applicationContext)
    private val store = PersistentRemoteImageStore(context.applicationContext)

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val source = chain.request.data.remoteUrlOrNull() ?: return chain.proceed(chain.request)
        // Playback artwork follows the source's direct media route; the catalog proxy only accepts metadata hosts.
        if (chain.request.diskCacheKey?.startsWith("community:") == true ||
            chain.request.diskCacheKey?.startsWith("playback-cover:") == true ||
            chain.request.headers["Authorization"] != null ||
            runCatching { java.net.URI(source).path.startsWith("/api/community/") }.getOrDefault(false)
        ) return chain.proceed(chain.request)
        val settings = runCatching { preferences.snapshot() }.getOrDefault(DiscoveryNetworkSettings())
        if (chain.request.diskCacheKey?.startsWith("game-cover:") == true && settings.publicDataRoute == com.animeow.app.data.preferences.PublicDataRoute.DIRECT) return chain.proceed(chain.request)
        val localFile = store.resolve(source, settings) ?: return if (settings.publicDataRoute == com.animeow.app.data.preferences.PublicDataRoute.SERVER) {
            val url = publicServerImageUrl(source, com.animeow.app.BuildConfig.CLOUD_API_BASE)
            if (url == null) coil.request.ErrorResult(null, chain.request, IOException("图片服务器未配置"))
            else chain.proceed(chain.request.newBuilder().data(url).build())
        } else chain.proceed(chain.request)
        return chain.proceed(chain.request.newBuilder().data(localFile).build())
    }
}

private class PersistentRemoteImageStore(context: Context) {
    private val directory = File(context.filesDir, REMOTE_COVER_DIRECTORY).apply { mkdirs() }
    private val locks = Array(64) { Mutex() }
    private val alternatives = RemoteCoverAlternatives(context)

    suspend fun resolve(source: String, settings: DiscoveryNetworkSettings): File? = withContext(Dispatchers.IO) {
        val canonical = canonicalRemoteImageUrl(source, settings.bangumiImageProxyBase) ?: return@withContext null
        val key = canonical.sha256()
        val destination = File(directory, "$key.jpg")
        val failureMarker = File(directory, "$key.failed")
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            if (destination.isUsableImage()) {
                destination.setLastModified(System.currentTimeMillis())
                failureMarker.delete()
                return@withLock destination
            }
            if (destination.exists()) destination.delete()
            val candidates = buildList {
                add(canonical)
                addAll(alternatives.candidates(source, allowProxy = false))
                if (settings.useBangumiImageProxy) {
                    addAll(alternatives.candidates(source, allowProxy = true))
                    bangumiImageProxyFallbackUrl(canonical, settings.bangumiImageProxyBase)?.let(::add)
                }
            }.let { urls -> if (settings.publicDataRoute == com.animeow.app.data.preferences.PublicDataRoute.SERVER)
                urls.mapNotNull { publicServerImageUrl(it, com.animeow.app.BuildConfig.CLOUD_API_BASE) } else urls
            }.distinct().take(4)
            val candidateFingerprint = candidates.joinToString("\n").sha256()
            // A changed proxy or newly contributed cover must become usable immediately.
            if (failureMarker.isRecentFailure() && runCatching { failureMarker.readText() }.getOrNull() == candidateFingerprint) return@withLock null
            val temporaryFiles = candidates.associateWith { File.createTempFile("$key-", ".part", directory) }
            try {
                val winner = firstValidCover(candidates) { candidate ->
                    downloads.withPermit {
                        val temporary = temporaryFiles.getValue(candidate)
                        if (downloadValidated(candidate, temporary)) temporary else null
                    }
                }
                if (winner != null) {
                    currentCoroutineContext().ensureActive()
                    if (!winner.renameTo(destination)) winner.copyTo(destination, overwrite = true)
                    destination.setLastModified(System.currentTimeMillis())
                    failureMarker.delete()
                    pruneIfNeeded(destination)
                    return@withLock destination
                }
            } finally {
                temporaryFiles.values.forEach(File::delete)
            }
            failureMarker.apply {
                parentFile?.mkdirs()
                runCatching { writeText(candidateFingerprint) }
                setLastModified(System.currentTimeMillis())
            }
            null
        }
    }

    private suspend fun downloadValidated(source: String, temporary: File): Boolean = suspendCancellableCoroutine { continuation ->
        val request = runCatching { Request.Builder().url(source).header("Accept", "image/avif,image/webp,image/*")
            .header("User-Agent", USER_AGENT).build() }.getOrNull()
        if (request == null) { continuation.resume(false); return@suspendCancellableCoroutine }
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { temporary.delete(); continuation.resume(false) }
            override fun onResponse(call: Call, response: Response) {
                val usable = runCatching {
                    response.use {
                        val body = it.body ?: return@use false
                        if (!it.isSuccessful || body.contentLength() > MAX_IMAGE_BYTES || !continuation.isActive) return@use false
                        var copied = 0L
                        body.byteStream().use { input -> FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (continuation.isActive) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                copied += read
                                if (copied > MAX_IMAGE_BYTES) throw IOException("图片文件过大")
                                output.write(buffer, 0, read)
                            }
                        } }
                        continuation.isActive && temporary.isUsableImage(decode = true)
                    }
                }.getOrDefault(false)
                if (!usable || call.isCanceled()) temporary.delete()
                continuation.resume(usable)
            }
        })
    }

    private fun File.isUsableImage(decode: Boolean = false): Boolean {
        if (!isFile || length() <= 0L || length() > MAX_IMAGE_BYTES) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(absolutePath, bounds)
        if (bounds.outWidth !in 1..16000 || bounds.outHeight !in 1..16000) return false
        if (!decode) return true
        val options = BitmapFactory.Options().apply {
            inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 512).coerceAtLeast(1)
        }
        val bitmap = BitmapFactory.decodeFile(absolutePath, options) ?: return false
        bitmap.recycle()
        return true
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
        const val CONNECT_TIMEOUT_MS = 4_000
        const val READ_TIMEOUT_MS = 8_000
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        const val MAX_CACHE_BYTES = 256L * 1024 * 1024
        const val CACHE_PRUNE_TARGET_BYTES = 224L * 1024 * 1024
        const val FAILURE_RETRY_DELAY_MS = 60L * 60 * 1_000
        const val USER_AGENT = "AniMeow/2.0 Android cover-cache"
        val downloads = Semaphore(9)
        val http = OkHttpClient.Builder().connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    }
}

private fun Any.remoteUrlOrNull(): String? = when (this) {
    is String -> takeIf(::isRemoteImageUrl)
    is Uri -> toString().takeIf(::isRemoteImageUrl)
    else -> null
}
