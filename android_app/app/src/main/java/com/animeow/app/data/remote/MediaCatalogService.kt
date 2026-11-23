package com.animeow.app.data.remote

import com.animeow.app.BuildConfig
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

data class PlaybackSource(val id: String, val name: String, val version: String = "", val rulesVersion: String = "")
data class PlaybackWork(val id: String, val title: String, val coverUrl: String)
data class PlaybackEpisode(val id: String, val label: String, val number: Int?)
data class PlaybackLine(val id: String, val name: String, val episodes: List<PlaybackEpisode>)
data class PlaybackStream(val url: String, val mimeType: String, val headers: Map<String, String>)
data class AudioMetadata(val title: String, val coverUrl: String, val voiceActors: String, val circle: String,
    val releaseDate: String, val workCode: String, val sourceUrl: String, val tags: String, val category: String,
    val originalTitle: String = "")
data class AudioSearchCandidate(val workCode: String, val title: String, val coverUrl: String, val circle: String)

internal fun validPlaybackUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
        uri.host?.let { it.contains('.') && !it.first().isDigit() && !it.startsWith("[") &&
            !it.endsWith(".local") && !it.endsWith(".internal") && !it.endsWith(".localhost") } == true
}.getOrDefault(false)

internal class MediaCatalogService(private val client: JsonHttpClient = JsonHttpClient(),
    private val baseUrl: String = BuildConfig.CLOUD_API_BASE.trimEnd('/')) {
    private suspend fun request(path: String, parameters: Map<String, String> = emptyMap()): JSONObject {
        check(baseUrl.isNotBlank()) { "当前构建未配置资料与观看服务" }
        val query = parameters.entries.joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, "UTF-8")}" }
        try {
            val root = JSONObject(client.get("$baseUrl/api/$path" + if (query.isBlank()) "" else "?$query"))
            check(root.optString("status") == "success") { "来源暂时不可用，请稍后重试" }
            return root
        } catch (error: CancellationException) { throw error }
        catch (error: RemoteHttpException) {
            when (error.statusCode) {
                404, 405 -> error("服务器尚未开通此功能，请稍后重试")
                400 -> error(runCatching { JSONObject(error.responseBody).optString("message").takeIf { it.length in 1..100 && !it.contains('<') } }.getOrNull() ?: "请检查作品编号或链接")
                429 -> error("请求较多，请稍后再试")
                else -> error("来源暂时无法读取，请重试或切换其他来源与线路")
            }
        }
        catch (error: Exception) { throw IllegalStateException("来源暂时无法读取，请检查网络后重试", error) }
    }
    suspend fun sources(): List<PlaybackSource> {
        val rows = request("playback/sources").getJSONArray("data")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.map { PlaybackSource(it.getString("id"), it.getString("name"), it.optString("version"), it.optString("rulesVersion")) }
    }
    suspend fun search(source: String, query: String): List<PlaybackWork> {
        val rows = request("playback/search", mapOf("source" to source, "q" to query)).getJSONArray("data")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.map { PlaybackWork(it.getString("id"), it.getString("title"), it.optString("coverUrl")) }
    }
    suspend fun episodes(source: String, workId: String): List<PlaybackLine> {
        val rows = request("playback/episodes", mapOf("source" to source, "workId" to workId)).getJSONObject("data").getJSONArray("lines")
        return (0 until rows.length()).map { index ->
            val line = rows.getJSONObject(index); val episodes = line.getJSONArray("episodes")
            PlaybackLine(line.getString("id"), line.getString("name"), (0 until episodes.length()).map {
                val episode = episodes.getJSONObject(it)
                PlaybackEpisode(episode.getString("id"), episode.getString("label"), episode.optInt("number").takeIf { number -> number > 0 })
            })
        }
    }
    suspend fun resolve(source: String, workId: String, episodeId: String): PlaybackStream {
        val data = request("playback/resolve", mapOf("source" to source, "workId" to workId, "episodeId" to episodeId)).getJSONObject("data")
        val url = data.getString("url")
        check(validPlaybackUrl(url)) { "来源返回的播放地址不可用，请切换线路" }
        val values = data.optJSONObject("headers")
        val headers = listOf("Referer", "User-Agent").mapNotNull { key -> values?.optString(key)?.takeIf { it.isNotBlank() && !it.contains('\r') && !it.contains('\n') }?.let { key to it } }.toMap()
        return PlaybackStream(url, data.getString("mimeType"), headers)
    }
    suspend fun audio(input: String): AudioMetadata {
        val data = request("audio/metadata", mapOf("input" to input)).getJSONObject("data")
        return AudioMetadata(data.getString("title"), data.optString("coverUrl"), data.optString("voiceActors"), data.optString("circle"),
            data.optString("releaseDate"), data.getString("workCode"), data.getString("sourceUrl"), data.optString("tags"), data.optString("category", "其他"),
            data.optString("originalTitle"))
    }
    suspend fun searchAudio(query: String): List<AudioSearchCandidate> {
        val rows = request("audio/search", mapOf("q" to query)).getJSONObject("data").getJSONArray("items")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.map {
            AudioSearchCandidate(it.getString("workCode"), it.getString("title"), it.optString("coverUrl"), it.optString("circle"))
        }
    }

    suspend fun checkMedia(stream: PlaybackStream): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        check(validPlaybackUrl(stream.url)) { "来源返回的地址不可用" }
        val connection = java.net.URL(stream.url).openConnection() as java.net.HttpURLConnection
        try {
            connection.connectTimeout = 8000; connection.readTimeout = 8000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Range", "bytes=0-0")
            stream.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            val code = connection.responseCode
            when {
                code in 200..299 -> connection.inputStream.use { if (it.read() >= 0) "媒体连接正常" else "来源返回空数据" }
                code in 300..399 -> "来源返回重定向，请用实际播放核对"
                else -> "媒体连接被拒绝（$code）"
            }
        } finally { connection.disconnect() }
    }
}
