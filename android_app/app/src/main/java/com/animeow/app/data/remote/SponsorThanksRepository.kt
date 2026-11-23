package com.animeow.app.data.remote

import android.content.Context
import androidx.core.content.edit
import com.animeow.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI

internal data class SponsorThank(val id: String, val displayName: String, val avatarUrl: String, val supportMonth: String)
internal data class SponsorThanks(val version: Int, val items: List<SponsorThank>)

internal class SponsorThanksRepository(context: Context, private val fetch: suspend () -> String = {
    check(BuildConfig.CLOUD_API_BASE.isNotBlank())
    JsonHttpClient(4_000, 6_000, MAX_BYTES).get(BuildConfig.CLOUD_API_BASE.trimEnd('/') + "/api/sponsor/thanks")
}) {
    private val preferences = context.applicationContext.getSharedPreferences("sponsor_thanks_v1", Context.MODE_PRIVATE)
    suspend fun cached(): SponsorThanks? = withContext(Dispatchers.IO) {
        preferences.getString("snapshot", null)?.let { runCatching { parse(it) }.getOrNull() }
    }
    suspend fun refresh(): SponsorThanks = lock.withLock {
        val text = fetch()
        val snapshot = parse(text)
        withContext(Dispatchers.IO) { preferences.edit { putString("snapshot", text) } }
        snapshot
    }
    companion object {
        private val lock = Mutex()
        private const val MAX_BYTES = 2_097_152
        fun parse(text: String): SponsorThanks {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val root = JSONObject(text)
            require(root.getString("status") == "success")
            val data = root.getJSONObject("data")
            val version = data.getInt("version")
            require(version >= 0)
            val rows = data.getJSONArray("items")
            require(rows.length() <= 1000)
            val ids = mutableSetOf<String>()
            val items = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                val id = row.getString("id")
                val name = row.getString("displayName")
                val avatar = row.getString("avatarUrl")
                val month = row.getString("supportMonth")
                require(id.matches(Regex("[a-fA-F0-9-]{36}")) && ids.add(id))
                require(name.isNotBlank() && name.codePointCount(0, name.length) <= 60 && name.none { Character.isISOControl(it) })
                require(month.isEmpty() || month.matches(Regex("20\\d{2}-(0[1-9]|1[0-2])")))
                val safeAvatar = runCatching { URI(avatar) }.getOrNull()?.let { url ->
                    avatar.takeIf { url.scheme == "https" && url.host?.matches(Regex("pic[1-9]\\d*\\.afdiancdn\\.com")) == true &&
                        url.userInfo == null && url.port == -1 && url.fragment == null && avatar.length <= 1024 }
                }.orEmpty()
                SponsorThank(id, name, safeAvatar, month)
            }
            return SponsorThanks(version, items)
        }
    }
}
