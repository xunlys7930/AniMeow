package com.animeow.app.data.remote

import com.animeow.app.BuildConfig
import com.animeow.app.util.runCatchingCancellable
import java.net.URI
import org.json.JSONObject

internal class SponsorService {
    suspend fun publicPage(): String? {
        if (BuildConfig.CLOUD_API_BASE.isBlank()) return DEFAULT_PAGE
        return runCatchingCancellable {
            val root = JSONObject(JsonHttpClient(3_000, 4_000).get(BuildConfig.CLOUD_API_BASE.trimEnd('/') + "/api/sponsor/config"))
            val data = root.optJSONObject("data") ?: return@runCatchingCancellable DEFAULT_PAGE
            if (!data.optBoolean("enabled", true)) null else validatedPage(data.optString("sponsor_page_url"))
        }.getOrElse { DEFAULT_PAGE }
    }
    companion object {
        const val DEFAULT_PAGE = "https://afdian.com/a/XunsAniMeow"
        fun validatedPage(value: String): String? = runCatching {
            val url = URI(value)
            value.takeIf { url.scheme == "https" && url.host in listOf("afdian.com", "afdian.net") && url.port == -1 &&
                url.userInfo == null && url.query == null && url.fragment == null && url.path.matches(Regex("/a/[A-Za-z0-9_-]{1,100}/?")) }
        }.getOrNull()
    }
}
