package com.animeow.app.data.community

import com.animeow.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PresetImage(val url: String, val name: String)

data class PresetImageList(
    val images: List<PresetImage>,
    val disclaimer: String,
)

/**
 * 根据预设图片名称构造完整 URL。
 *
 * 后端预设图片路径形如 `/preset-images/preset_001.jpeg`，
 * 组合 `CLOUD_API_BASE` 后即可得到完整可访问地址。
 */
internal fun presetImageUrl(name: String): String {
    val baseUrl = BuildConfig.CLOUD_API_BASE.trim().trimEnd('/')
    val path = if (name.startsWith("/")) name else "/preset-images/$name"
    return if (baseUrl.isNotEmpty()) "$baseUrl$path" else path
}

/**
 * 预设图片数据层：从后端拉取群友共享的预设头像/配图列表。
 *
 * 对应公开接口 GET /preset-images/list（无需登录鉴权），返回结构示例：
 * {
 *   "images": [{"url": "/preset-images/preset_001.jpeg", "name": "preset_001.jpeg"}, ...],
 *   "disclaimer": "图片由群友上传，仅用于交流分享。如有违规内容请联系管理员处理。"
 * }
 *
 * 其中 `url` 为相对路径，组合 `CLOUD_API_BASE + url` 后得到图片的完整可访问地址。
 *
 * 客户端缓存策略：列表极少变动，缓存 2 小时内直接返回内存结果，避免重复网络请求。
 */
object PresetImageService {

    @Volatile
    private var cached: PresetImageList? = null

    @Volatile
    private var cachedAt: Long = 0L

    private const val CACHE_TTL_MS = 2 * 60 * 60 * 1000L // 2 小时

    /**
     * 拉取预设图片列表。
     *
     * @param forceRefresh 为 true 时跳过内存缓存，强制请求网络。
     * @return 解析成功时返回 [PresetImageList]；网络异常或解析失败时返回 null。
     */
    suspend fun fetchPresetImages(forceRefresh: Boolean = false): PresetImageList? = withContext(Dispatchers.IO) {
        // 内存缓存命中
        if (!forceRefresh) {
            val snapshot = cached
            if (snapshot != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
                return@withContext snapshot
            }
        }

        try {
            val baseUrl = BuildConfig.CLOUD_API_BASE.trim().trimEnd('/').takeIf(String::isNotEmpty)
                ?: return@withContext null
            val endpoint = "$baseUrl/preset-images/list"

            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
            }

            try {
                val responseCode = connection.responseCode
                // 304 Not Modified：服务端 ETag 命中，直接用缓存
                if (responseCode == 304) {
                    return@withContext cached
                }
                if (responseCode !in 200..299) return@withContext cached // 网络失败时降级返回旧缓存

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                val imagesArray = root.optJSONArray("images") ?: return@withContext cached

                val images = buildList {
                    for (index in 0 until imagesArray.length()) {
                        val item = imagesArray.optJSONObject(index) ?: continue
                        val path = item.optString("url")
                        if (path.isBlank()) continue
                        // 完整 URL = CLOUD_API_BASE + url（后端返回的是以 / 开头的相对路径）
                        add(
                            PresetImage(
                                url = baseUrl + path,
                                name = item.optString("name").ifBlank { path.substringAfterLast('/') },
                            ),
                        )
                    }
                }

                val result = PresetImageList(
                    images = images,
                    disclaimer = root.optString("disclaimer"),
                )
                cached = result
                cachedAt = System.currentTimeMillis()
                result
            } finally {
                connection.disconnect()
            }
        } catch (_: Throwable) {
            // 网络异常时降级返回旧缓存（如果有）
            cached
        }
    }
}
