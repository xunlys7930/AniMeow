package com.animeow.app.data.remote

import android.content.Context
import android.net.Uri
import com.animeow.app.data.media.openInputStreamCompat
import com.animeow.app.util.runCatchingCancellable
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.json.JSONArray
import org.json.JSONObject

data class ImageSearchMatch(
    val anime: RemoteAnime,
    val episode: Double?,
    val fromSeconds: Double,
    val toSeconds: Double,
    val similarity: Double,
    val previewImageUrl: String?,
    val previewVideoUrl: String?,
    val fileName: String?,
)

internal data class TraceCandidate(
    val anilistId: Int,
    val fallbackTitle: String,
    val episode: Double?,
    val fromSeconds: Double,
    val toSeconds: Double,
    val similarity: Double,
    val previewImageUrl: String?,
    val previewVideoUrl: String?,
    val fileName: String?,
)

internal class TraceMoeService(
    context: Context,
    private val aniListService: AniListService,
) {
    private val appContext = context.applicationContext

    suspend fun search(uri: Uri): List<ImageSearchMatch> = withContext(Dispatchers.IO) {
        val json = uploadImage(uri)
        val candidates = parseResponse(json).take(MAX_RESULTS)
        val metadata = coroutineScope {
            val limit = Semaphore(3)
            candidates.map { it.anilistId }.distinct().map { id -> async {
                id to limit.withPermit { runCatchingCancellable { aniListService.getAnime(id) }.getOrNull() }
            } }.awaitAll().toMap()
        }
        candidates.map { candidate ->
            val anime = metadata[candidate.anilistId]
                ?: RemoteAnime(
                    source = RemoteCatalogSource.ANILIST,
                    id = candidate.anilistId.toString(),
                    title = candidate.fallbackTitle,
                    coverUrl = candidate.previewImageUrl,
                    siteUrl = "https://anilist.co/anime/${candidate.anilistId}",
                )
            ImageSearchMatch(
                anime = anime,
                episode = candidate.episode,
                fromSeconds = candidate.fromSeconds,
                toSeconds = candidate.toSeconds,
                similarity = candidate.similarity,
                previewImageUrl = candidate.previewImageUrl,
                previewVideoUrl = candidate.previewVideoUrl,
                fileName = candidate.fileName,
            )
        }
    }

    internal fun parseResponse(json: String): List<TraceCandidate> {
        val root = JSONObject(json)
        root.optString("error").takeIf(String::isNotBlank)?.let(::error)
        val results = root.optJSONArray("result") ?: JSONArray()
        return buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val anilist = item.optJSONObject("anilist") ?: continue
                val id = anilist.optInt("id", 0).takeIf { it > 0 } ?: continue
                val titles = anilist.optJSONObject("title")
                val title = titles?.optString("english")?.takeIf(String::isNotBlank)
                    ?: titles?.optString("romaji")?.takeIf(String::isNotBlank)
                    ?: titles?.optString("native")?.takeIf(String::isNotBlank)
                    ?: "AniList #$id"
                add(
                    TraceCandidate(
                        anilistId = id,
                        fallbackTitle = title,
                        episode = item.optDouble("episode", Double.NaN).takeIf { it.isFinite() && it >= 0 },
                        fromSeconds = item.optDouble("from", 0.0).takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0,
                        toSeconds = item.optDouble("to", 0.0).takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0,
                        similarity = item.optDouble("similarity", 0.0).takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.0,
                        previewImageUrl = item.optString("image").takeIf(String::isNotBlank),
                        previewVideoUrl = item.optString("video").takeIf(String::isNotBlank),
                        fileName = item.optString("filename").takeIf(String::isNotBlank),
                    ),
                )
            }
        }.distinctBy { "${it.anilistId}:${it.episode}:${it.fromSeconds}" }.sortedByDescending(TraceCandidate::similarity)
    }

    private suspend fun uploadImage(uri: Uri): String = suspendCancellableCoroutine { continuation ->
        val boundary = "AniMeow-${UUID.randomUUID()}"
        val connection = URL(TRACE_ENDPOINT).openConnection() as HttpURLConnection
        continuation.invokeOnCancellation { connection.disconnect() }
        try {
            if (!continuation.isActive) return@suspendCancellableCoroutine
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "AniMeow/${com.animeow.app.BuildConfig.VERSION_NAME} Android")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.outputStream.buffered().use { output ->
                output.write("--$boundary\r\n".toByteArray())
                output.write(
                    "Content-Disposition: form-data; name=\"image\"; filename=\"search.jpg\"\r\n".toByteArray(),
                )
                val mime = when (uri.lastPathSegment?.substringAfterLast('.')?.lowercase()) {
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    else -> "image/jpeg"
                }
                output.write("Content-Type: $mime\r\n\r\n".toByteArray())
                var total = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                try {
                    openInputStreamCompat(appContext, uri).use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_IMAGE_BYTES) { "图片超过 20 MB，请先裁剪或压缩" }
                            output.write(buffer, 0, read)
                        }
                    }
                } catch (e: SecurityException) {
                    error("这张图片的读取权限已失效，请重新选择")
                } catch (e: java.io.FileNotFoundException) {
                    error("图片已移动或删除，请重新选择")
                } catch (e: java.io.IOException) {
                    error("图片读取或上传中断，请检查网络后重试")
                }
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error(when (status) {
                429 -> "识别服务暂时繁忙，请稍等一会儿再试"
                400, 413, 415 -> "这张图片暂时无法识别，请换一张清晰的 JPG 或 PNG 截图"
                else -> "识别服务暂时无法连接，请检查网络后重试"
            })
            continuation.resume(response)
        } catch (error: Exception) {
            val message = when (error) {
                is java.net.SocketTimeoutException -> "识别等待时间较长，请检查网络后重试"
                is java.io.IOException -> "识别服务暂时无法连接，请检查网络后重试"
                else -> error.message ?: "识别暂时失败，请重试"
            }
            continuation.resumeWithException(IllegalStateException(message, error))
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TRACE_ENDPOINT = "https://api.trace.moe/search?cutBorders&anilistInfo"
        const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        const val MAX_RESULTS = 8
    }
}
