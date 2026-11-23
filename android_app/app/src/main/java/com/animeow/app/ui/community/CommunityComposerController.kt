package com.animeow.app.ui.community

import android.net.Uri
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.community.CommunityPost
import com.animeow.app.data.community.CommunityUploadImage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Owns one publication, including compression and cleanup, without clearing a newer draft. */
internal class CommunityComposerController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<CommunityUiState>,
    private val loadSession: () -> CloudSession?,
    private val compress: suspend (Uri, Int) -> CommunityUploadImage,
    private val create: suspend (CloudSession, Long, CommunityComposerDraft, List<CommunityUploadImage>) -> CommunityPost,
    private val saveDraft: (CommunityComposerDraft) -> Unit,
    private val release: suspend (Collection<Uri>) -> Unit,
    private val failure: (Throwable, String) -> String,
    private val published: (CloudSession, CommunityPost) -> Unit,
) {
    private var publishing = false
    private var generation = 0
    fun cancel() { generation++; state.value = state.value.copy(busy = false, busyLabel = null) }

    fun publish() {
        val snapshot = state.value
        val session = snapshot.session ?: return message("请先登录云账号")
        val group = snapshot.group ?: return message("群聊尚未加载")
        if (snapshot.busy || snapshot.importingImages || snapshot.watchShareLoading || publishing) return
        if (session != loadSession()) return message("账号状态已更新，请重新进入社区")
        val draft = snapshot.composerDraft()
        if (draft.text.isBlank() && draft.images.isEmpty() && draft.presetMedia.isEmpty() && draft.watchReferenceId == null)
            return message("写点内容，或选择图片后再发布")
        val ticket = generation
        fun current() = ticket == generation && state.value.session == session && loadSession() == session
        publishing = true
        state.value = snapshot.copy(busy = true, busyLabel = "正在压缩并发布…", message = null)
        scope.launch {
            try {
                val images = draft.images.map { compress(it, minOf(snapshot.limits.imageBytes, 768 * 1024)) }
                require(images.sumOf { it.bytes.size.toLong() } <= snapshot.limits.postMediaBytes.toLong()) {
                    "所选图片合计仍然过大，请减少图片数量"
                }
                if (!current()) return@launch
                val post = create(session, group.id, draft, images)
                published(session, post)
                if (!current()) return@launch
                val unchanged = state.value.composerDraft() == draft
                if (unchanged) saveDraft(CommunityComposerDraft())
                state.value = state.value.copy(
                    posts = if (state.value.group?.id == group.id && state.value.feedSort == "newest" &&
                        !state.value.feedFeaturedOnly && (state.value.feedTag == null || state.value.feedTag in draft.tags))
                        listOf(post) + state.value.posts.filterNot { it.id == post.id } else state.value.posts,
                    publishedPostId = post.id,
                    draftText = if (unchanged) "" else state.value.draftText,
                    draftImages = if (unchanged) emptyList() else state.value.draftImages,
                    draftPresetMedia = if (unchanged) emptyList() else state.value.draftPresetMedia,
                    draftTags = if (unchanged) emptyList() else state.value.draftTags,
                    draftWatchReferenceId = if (unchanged) null else state.value.draftWatchReferenceId,
                    message = if (post.isQuarantined) "帖子已保存，管理员复审完成前仅你自己可见" else "发布成功",
                )
                // The server already confirmed the post; cleanup failure must never invite a duplicate publication.
                if (unchanged) withContext(NonCancellable) { runCatching { release(draft.images) } }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (current()) message(failure(error, "发布失败，请先确认帖子是否已出现，再重试"))
            } finally {
                publishing = false
                if (ticket == generation && state.value.session == session)
                    state.value = state.value.copy(busy = false, busyLabel = null)
            }
        }
    }

    private fun message(value: String) { state.value = state.value.copy(message = value) }
}

internal fun CommunityUiState.composerDraft() = CommunityComposerDraft(draftText, draftImages, draftPresetMedia, draftTags, draftWatchReferenceId)
