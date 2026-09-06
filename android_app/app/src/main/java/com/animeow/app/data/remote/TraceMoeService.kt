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
        candidates.map { candidate ->
            val anime = runCatchingCancellable { aniListService.getAnime(candidate.anilistId) }.getOrNull()
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
                        episode = item.optDouble("episode", Double.NaN).takeUnless(Double::isNaN),
                        fromSeconds = item.optDouble("from", 0.0),
                        toSeconds = item.optDouble("to", 0.0),
                        similarity = item.optDouble("similarity", 0.0),
                        previewImageUrl = item.optString("image").takeIf(String::isNotBlank),
                        previewVideoUrl = item.optString("video").takeIf(String::isNotBlank),
                        fileName = item.optString("filename").takeIf(String::isNotBlank),
                    ),
                )
            }
        }.sortedByDescending(TraceCandidate::similarity)
    }

    private fun uploadImage(uri: Uri): String {
        val boundary = "AniMeow-${UUID.randomUUID()}"
        val connection = URL(TRACE_ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "AniMeow/2.0 Android")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.outputStream.buffered().use { output ->
                output.write("--$boundary\r\n".toByteArray())
                output.write(
                    "Content-Disposition: form-data; name=\"image\"; filename=\"search.jpg\"\r\n".toByteArray(),
                )
                output.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())
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
                    error("无法读取所选图片（权限被拒绝）\nURI: $uri\n原因: ${e.message}")
                } catch (e: java.io.FileNotFoundException) {
                    error("无法读取所选图片\nURI: $uri\n原因: ${e.message}")
                } catch (e: java.io.IOException) {
                    error("无法读取所选图片（IO 错误）\nURI: $uri\n原因: ${e.javaClass.simpleName}: ${e.message}")
                }
                output.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) error("trace.moe 返回 HTTP $status：${response.take(300)}")
            return response
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
