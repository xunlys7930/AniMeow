package com.animeow.app.ui.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.community.CommunityComment
import com.animeow.app.data.community.CommunityPost
import com.animeow.app.ui.components.ZoomableImageViewer

/**
 * 帖子详情页：展示单条帖子及其评论，支持评论与子评论的排序筛选。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityPostDetailScreen(
    postId: Long,
    onBack: () -> Unit,
    onUserClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CommunityViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var viewingImageUrl by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<CommunityPost?>(null) }
    var editTarget by remember { mutableStateOf<CommunityPost?>(null) }
    var deleteCommentTarget by remember { mutableStateOf<CommunityComment?>(null) }
    var replyTarget by remember { mutableStateOf<CommunityComment?>(null) }
    var replyDraft by remember { mutableStateOf("") }
    var expandedReplies by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var replySorts by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

    LaunchedEffect(postId) { viewModel.loadPostDetail(postId) }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("帖子详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            if (state.session != null && state.selectedPost != null) {
                CommentComposer(
                    value = if (replyTarget != null) replyDraft else state.commentDraft,
                    placeholder = replyTarget?.let { "回复 ${it.author.nickname}…" } ?: "写下你的评论…",
                    enabled = !state.busy,
                    onValueChange = { value ->
                        if (replyTarget != null) replyDraft = value.take(1000) else viewModel.updateCommentDraft(value)
                    },
                    onSend = {
                        val target = replyTarget
                        if (target != null) {
                            viewModel.replyComment(target.id, replyDraft)
                            replyTarget = null
                            replyDraft = ""
                        } else {
                            viewModel.addComment(state.commentDraft)
                        }
                    },
                    sendEnabled = if (replyTarget != null) replyDraft.isNotBlank() else state.commentDraft.isNotBlank(),
                )
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val post = state.selectedPost
            if (post == null) {
                item {
                    if (state.loading) {
                        Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        CommunityUnavailable("帖子不存在", "可能已被删除或当前不可见。")
                    }
                }
            } else {
                item {
                    CommunityPostCard(
                        post = post,
                        token = state.session?.token,
                        onAuthorClick = { onUserClick(post.author.userId) },
                        onReport = { },
                        onDelete = { deleteTarget = post },
                        onLike = { viewModel.likePost(post.id) },
                        onComment = { },
                        onFavorite = { viewModel.favoritePost(post.id) },
                        onPin = { pin -> viewModel.pinPost(post.id, pin) },
                        canPin = state.currentUser?.isDeveloper == true,
                        onFeature = { feature -> viewModel.featurePost(post.id, feature) },
                        canFeature = state.currentUser?.isDeveloper == true,
                        onEdit = { editTarget = post },
                        canEdit = state.currentUser?.userId == post.author.userId,
                        onImageClick = { url -> viewingImageUrl = url },
                    )
                }
                item {
                    CommentSortChips(
                        current = state.commentSort,
                        onSelect = viewModel::setCommentSort,
                    )
                }
                if (state.commentsLoading && state.comments.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (state.comments.isEmpty()) {
                    item {
                        Text("还没有评论，来抢沙发吧", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    items(state.comments, key = CommunityComment::id) { comment ->
                        PostDetailCommentItem(
                            comment = comment,
                            token = state.session?.token,
                            replies = state.replies[comment.id].orEmpty(),
                            expanded = comment.id in expandedReplies,
                            replySort = replySorts[comment.id] ?: "newest",
                            onReply = {
                                replyTarget = comment
                                replyDraft = ""
                            },
                            onLike = { viewModel.likeComment(comment.id) },
                            onEdit = { },
                            onDelete = { deleteCommentTarget = comment },
                            onToggleReplies = {
                                if (comment.id in expandedReplies) {
                                    expandedReplies = expandedReplies - comment.id
                                } else {
                                    expandedReplies = expandedReplies + comment.id
                                    viewModel.loadReplies(comment.id, replySorts[comment.id] ?: "newest")
                                }
                            },
                            onReplySort = { sort ->
                                replySorts = replySorts + (comment.id to sort)
                                viewModel.loadReplies(comment.id, sort)
                            },
                            onReplyLike = { viewModel.likeComment(it.id) },
                            onReplyDelete = { deleteCommentTarget = it },
                            onReplyReply = { replyComment ->
                                replyTarget = replyComment
                                replyDraft = ""
                            },
                        )
                    }
                }
            }
        }
    }

    viewingImageUrl?.let { url ->
        ZoomableImageViewer(
            imageUrl = url,
            title = "帖子图片",
            onDismiss = { viewingImageUrl = null },
            authToken = state.session?.token,
        )
    }

    deleteTarget?.let { post ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除这条帖子？") },
            text = { Text("删除后不会继续在社区显示。") },
            confirmButton = {
                Button(onClick = {
                    viewModel.deletePost(post)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    editTarget?.let { post ->
        CommunityEditDialog(
            title = "编辑帖子",
            initial = post.content,
            onDismiss = { editTarget = null },
            onConfirm = { text ->
                viewModel.editPost(post.id, text)
                editTarget = null
            },
        )
    }

    deleteCommentTarget?.let { comment ->
        AlertDialog(
            onDismissRequest = { deleteCommentTarget = null },
            title = { Text("删除这条评论？") },
            text = { Text("删除后不会恢复。") },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteComment(comment.id)
                    deleteCommentTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteCommentTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun CommentSortChips(current: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("newest" to "最新", "oldest" to "最旧", "likes" to "点赞最多").forEach { (value, label) ->
            FilterChip(
                selected = current == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun CommentComposer(
    value: String,
    placeholder: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    sendEnabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(placeholder) },
            maxLines = 4,
            enabled = enabled,
        )
        IconButton(onClick = onSend, enabled = enabled && sendEnabled) {
            Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送")
        }
    }
}

@Composable
private fun PostDetailCommentItem(
    comment: CommunityComment,
    token: String?,
    replies: List<CommunityComment>,
    expanded: Boolean,
    replySort: String,
    onReply: () -> Unit,
    onLike: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleReplies: () -> Unit,
    onReplySort: (String) -> Unit,
    onReplyLike: (CommunityComment) -> Unit,
    onReplyDelete: (CommunityComment) -> Unit,
    onReplyReply: (CommunityComment) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        CommunityCommentRow(
            comment = comment,
            token = token,
            onReply = onReply,
            onLike = onLike,
            onEdit = onEdit,
            onDelete = onDelete,
        )
        if (comment.replyCount > 0 || replies.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(start = 52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onToggleReplies) {
                    Text(if (expanded) "收起回复" else "查看 ${comment.replyCount} 条回复")
                }
                if (expanded) {
                    Spacer(Modifier.width(4.dp))
                    FilterChip(
                        selected = replySort == "newest",
                        onClick = { onReplySort("newest") },
                        label = { Text("最新") },
                    )
                    FilterChip(
                        selected = replySort == "oldest",
                        onClick = { onReplySort("oldest") },
                        label = { Text("最旧") },
                    )
                }
            }
            if (expanded) {
                Column(Modifier.padding(start = 36.dp)) {
                    if (replies.isEmpty()) {
                        Text(
                            "暂无回复",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    } else {
                        replies.forEach { reply ->
                            CommunityCommentRow(
                                comment = reply,
                                token = token,
                                onReply = { onReplyReply(reply) },
                                onLike = { onReplyLike(reply) },
                                onEdit = { },
                                onDelete = { onReplyDelete(reply) },
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
    }
}
