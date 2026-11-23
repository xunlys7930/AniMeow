package com.animeow.app.ui.community

import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.community.*

internal fun communityTestProfile() = CommunityProfile(1, "synthetic", "合成昵称", "", "public", false, false, false, null, false, emptyMap(), null)
internal fun communityTestPost(id: Long = 1) = CommunityPost(id, 1, "合成帖子$id", emptyList(), communityTestProfile(), "approved", false,
    false, null, false, null, 0, 0, false, false, true, false, "2026-11-06", "2026-11-06")
internal fun communityTestComment(id: Long = 10, postId: Long = 1, parent: Long? = null) =
    CommunityComment(id, postId, parent, null, "合成评论$id", communityTestProfile(), 0, false, 0, true, "2026-11-06")

internal class FakeCommunityThreadApi : CommunityThreadApi {
    val writes = mutableListOf<String>()
    var readPost: suspend (Long) -> CommunityPost = { communityTestPost(it) }
    var readComments: suspend (Long, Long?, String, Long?) -> CommunityCommentPage = { post, parent, _, _ ->
        CommunityCommentPage(listOf(communityTestComment(parent?.plus(100) ?: post * 10, post, parent)), null)
    }
    var onCreate: suspend () -> Unit = {}
    var onReply: suspend () -> Unit = {}
    var onDelete: suspend () -> Unit = {}
    var onEdit: suspend () -> Unit = {}
    override suspend fun post(session: CloudSession, id: Long) = readPost(id)
    override suspend fun comments(session: CloudSession?, postId: Long, parentId: Long?, sort: String, cursor: Long?) =
        readComments(postId, parentId, sort, cursor)
    override suspend fun related(session: CloudSession?, postId: Long) = emptyList<CommunityPost>()
    override suspend fun create(session: CloudSession, postId: Long, text: String) { writes += "create:$postId:$text"; onCreate() }
    override suspend fun reply(session: CloudSession, commentId: Long, text: String) { writes += "reply:$commentId:$text"; onReply() }
    override suspend fun delete(session: CloudSession, commentId: Long) { writes += "delete:$commentId"; onDelete() }
    override suspend fun edit(session: CloudSession, commentId: Long, text: String) { writes += "edit:$commentId:$text"; onEdit() }
}
