package com.animeow.app.ui.community

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.animeow.app.data.community.withCommunityTag

internal data class CommunityComposerDraft(
    val text: String = "", val images: List<Uri> = emptyList(),
    val presetMedia: List<String> = emptyList(), val tags: List<String> = emptyList(),
    val watchReferenceId: Long? = null,
)

/** Stores the complete composer draft synchronously, before Android can save lifecycle state. */
internal class CommunityDraftStore(private val saved: SavedStateHandle) {
    fun read() = CommunityComposerDraft(
        text = saved.get<String>(TEXT).orEmpty(),
        watchReferenceId = saved.get<Long>(WATCH_REFERENCE)?.takeIf { it > 0 },
        images = saved.get<ArrayList<String>>(IMAGES).orEmpty().mapNotNull { runCatching { Uri.parse(it) }.getOrNull() },
        presetMedia = saved.get<ArrayList<String>>(PRESETS).orEmpty(),
        tags = saved.get<ArrayList<String>>(TAGS).orEmpty().fold(emptyList()) { list, tag -> list.withCommunityTag(tag) },
    )

    fun write(draft: CommunityComposerDraft) {
        saved[TEXT] = draft.text
        saved[WATCH_REFERENCE] = draft.watchReferenceId
        saved[IMAGES] = ArrayList(draft.images.map(Uri::toString))
        saved[PRESETS] = ArrayList(draft.presetMedia)
        saved[TAGS] = ArrayList(draft.tags)
    }

    private companion object {
        const val WATCH_REFERENCE = "community_draft_watch_reference"
        const val TEXT = "community_draft_text"
        const val IMAGES = "community_draft_images"
        const val PRESETS = "community_draft_preset_media"
        const val TAGS = "community_draft_tags"
    }
}
