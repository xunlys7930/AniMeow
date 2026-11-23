package com.animeow.app.ui.navigation

import androidx.lifecycle.SavedStateHandle
import com.animeow.app.data.remote.CommunityLaunchRequest
import com.animeow.app.data.remote.parseAniMeowDeepLink

/** Only an explicitly approved preview is saved on the destination entry; it never imports data. */
internal class CharacterShareRequests(private val saved: SavedStateHandle) {
    val pending = saved.getStateFlow<String?>(KEY, null)

    init { if (pending.value != null && decode(pending.value) == null) saved[KEY] = null }

    fun request(request: CommunityLaunchRequest): Boolean {
        if (decode(request.key) != request) return false
        saved[KEY] = request.key
        return true
    }

    fun consume(request: CommunityLaunchRequest): Boolean {
        if (pending.value != request.key) return false
        saved[KEY] = null
        return true
    }

    fun decode(value: String?): CommunityLaunchRequest? {
        if (value == null || value.length > 96) return null
        val path = when {
            value.startsWith("share:") -> "share/${value.removePrefix("share:")}"
            value.startsWith("group:") -> "group/${value.removePrefix("group:")}"
            else -> return null
        }
        return parseAniMeowDeepLink("animeow://community/$path")?.community?.takeIf { it.key == value }
    }

    private companion object { const val KEY = "character_approved_share_preview" }
}
