package com.animeow.app.ui.community

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class CommunityDraftStoreTest {
    @Test fun oldDraftKeysRestoreWithoutInventingTags() {
        val saved = SavedStateHandle(mapOf("community_draft_text" to "旧草稿", "community_draft_images" to arrayListOf("file:///synthetic/image.jpg"),
            "community_draft_preset_media" to arrayListOf("synthetic-preset")))
        val draft = CommunityDraftStore(saved).read()
        assertEquals("旧草稿", draft.text); assertEquals("file:///synthetic/image.jpg", draft.images.single().toString())
        assertEquals(listOf("synthetic-preset"), draft.presetMedia); assertTrue(draft.tags.isEmpty())
    }

    @Test fun allComposerFieldsIncludingTagsSurviveANewStoreAndIndependentHandle() {
        val saved = SavedStateHandle()
        val expected = CommunityComposerDraft("私人合成草稿", listOf(Uri.parse("file:///synthetic/retained.jpg")), listOf("preset"), listOf("日常", "cos"))
        CommunityDraftStore(saved).write(expected)
        val restoredHandle = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        assertEquals(expected, CommunityDraftStore(restoredHandle).read())
        CommunityDraftStore(restoredHandle).write(CommunityComposerDraft())
        assertEquals(CommunityComposerDraft(), CommunityDraftStore(restoredHandle).read())
        assertEquals(expected, CommunityDraftStore(saved).read())
    }

    @Test fun aWatchReferenceSurvivesDraftRestorationAndCanBeRemovedWithoutChangingTheText() {
        val saved = SavedStateHandle()
        val draft = CommunityComposerDraft("合成分享文字", watchReferenceId = 42)
        CommunityDraftStore(saved).write(draft)
        val restored = CommunityDraftStore(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals(draft, restored.read())
        restored.write(restored.read().copy(watchReferenceId = null))
        assertEquals("合成分享文字", restored.read().text); assertNull(restored.read().watchReferenceId)
    }

    @Test fun restoredTagsUseTheExistingValidationDeduplicationAndMaximum() {
        val saved = SavedStateHandle(mapOf("community_draft_tags" to arrayListOf("#日常", "日常", "cos", "COS", "第三", "第四", "第五", "第六", "#")))
        assertEquals(listOf("日常", "cos", "第三", "第四", "第五"), CommunityDraftStore(saved).read().tags)
    }
}
