package com.animeow.app.ui.community

import com.animeow.app.ui.icons.AppIcons

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import com.animeow.app.ui.icons.AppIcon as Icon
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
import com.animeow.app.ui.components.LoadErrorCard
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionAnimateItem
import com.animeow.app.ui.components.motionAnimationSpec
import com.animeow.app.ui.components.motionBarEnter
import com.animeow.app.ui.components.motionBarExit
import com.animeow.app.ui.components.motionExpandIn
import com.animeow.app.ui.components.motionFadeThroughSpec
import com.animeow.app.ui.components.motionShrinkOut
import com.animeow.app.ui.theme.LocalMotionLevel
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.animeow.app.ui.preferences.AppearanceViewModel
import com.animeow.app.ui.theme.CommunityStyle
import androidx.compose.foundation.layout.size

/**
 * 帖子详情页：展示单条帖子及其评论，支持评论与子评论的排序筛选。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CommunityPostDetailScreen(
    postId: Long,
    onBack: () -> Unit,
    onUserClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CommunityViewModel = viewModel(),
    appearanceViewModel: AppearanceViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val detailError = state.postDetailError
    val commentsError = state.commentsError
    val appearance by appearanceViewModel.settings.collectAsStateWithLifecycle()
    // 形态 B（图片瀑布流）使用图片优先的详情布局；形态 A 保持原样。
    val styleB = appearance.communityStyle == CommunityStyle.WATERFALL
    val snackbar = remember { SnackbarHostState() }
    var viewingImageUrl by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<CommunityPost?>(null) }
    var editTarget by remember { mutableStateOf<CommunityPost?>(null) }
    var deleteCommentTarget by remember { mutableStateOf<CommunityComment?>(null) }
    val replyTarget = state.replyTarget
    val replyDraft = state.replyDraft
    val threadRows = remember(state.comments, state.replies, state.replyBranches) {
        visibleThreadRows(state.comments, state.replies, state.replyBranches)
    }

    LaunchedEffect(postId) { viewModel.loadPostDetail(postId) }
    val authorId = state.selectedPost?.author?.userId
    LaunchedEffect(authorId) { if (authorId != null) viewModel.loadAuthorProfile(authorId) }
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
                        Icon(AppIcons.Back, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = state.session != null && state.selectedPost != null,
                enter = motionBarEnter(),
                exit = motionBarExit(),
            ) {
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                ) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                        if (styleB) state.selectedPost?.let { post ->
                            DetailActionBar(post = post, onLike = { viewModel.likePost(post.id) }, onFavorite = { viewModel.favoritePost(post.id) })
                        }
                        CommentComposer(
                            value = if (replyTarget != null) replyDraft else state.commentDraft,
                            placeholder = replyTarget?.let { "回复 ${it.author.nickname}…" } ?: "写下你的评论…",
                            enabled = !state.busy && !state.commentsBusy,
                            onValueChange = { value ->
                                if (replyTarget != null) viewModel.updateReplyDraft(value) else viewModel.updateCommentDraft(value)
                            },
                            onSend = {
                                val target = replyTarget
                                if (target != null) {
                                    viewModel.replyComment(target.id, replyDraft)
                                } else {
                                    viewModel.addComment(state.commentDraft)
                                }
                            },
                            sendEnabled = if (replyTarget != null) replyDraft.isNotBlank() else state.commentDraft.isNotBlank(),
                            replyingTo = replyTarget?.author?.nickname,
                            onCancelReply = viewModel::cancelReply,
                        )
                    }
                }
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
                    if (state.postDetailLoading) {
                        Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (detailError != null) {
                        CommunityThreadError("帖子暂时无法读取", detailError, { viewModel.loadPostDetail(postId) })
                    } else {
                        CommunityUnavailable("帖子不存在", "可能已被删除或当前不可见。")
                    }
                }
            } else if (styleB) {
                item("gallery") {
                    CommunityImagePager(post, state.session?.token) { url -> viewingImageUrl = url }
                }
                item("author") {
                    val author = state.selectedProfile?.takeIf { it.userId == post.author.userId } ?: post.author
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CommunityAvatar(post.author, state.session?.token,
                            Modifier.size(40.dp).clickable { onUserClick(post.author.userId) })
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f).clickable { onUserClick(post.author.userId) }) {
                            Text(post.author.nickname, fontWeight = FontWeight.SemiBold)
                            Text(formatCommunityTime(post.createdAt), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        CommunityFollowButton(
                            profile = author,
                            isSelf = post.author.userId == state.currentUser?.userId,
                            busy = state.followBusyUserId != null,
                            loggedIn = state.session != null,
                            onToggle = { viewModel.toggleFollow(post.author.userId) },
                        )
                    }
                }
                if (post.isQuarantined) item("quarantined") {
                    Text("这条帖子正在等待管理员复审，目前仅你自己可见。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                if (post.content.isNotBlank()) item("content") {
                    Text(post.content, style = MaterialTheme.typography.bodyLarge)
                }
                post.watchReference?.let { reference -> item("watch-reference") { WatchReferenceCard(reference) } }
                if (post.tags.isNotEmpty()) item("tags") {
                    CommunityPostTags(post.tags) { tag ->
                        CommunityTagRequests.request(tag)
                        onBack()
                    }
                }
                item("stats") {
                    CommunityCountRow(listOf("浏览" to post.viewCount, "点赞" to post.likeCount,
                        "收藏" to post.favoriteCount, "评论" to post.commentCount))
                }
                val canModerate = state.currentUser?.isDeveloper == true
                if (state.currentUser?.userId == post.author.userId || post.canDelete || canModerate) item("owner-actions") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canModerate) {
                            TextButton(onClick = { viewModel.pinPost(post.id, !post.isPinned) }) {
                                Text(if (post.isPinned) "取消置顶" else "置顶帖子")
                            }
                            TextButton(onClick = { viewModel.featurePost(post.id, !post.isFeatured) }) {
                                Text(if (post.isFeatured) "取消精华" else "设为精华")
                            }
                        }
                        if (state.currentUser?.userId == post.author.userId) TextButton(onClick = { editTarget = post }) { Text("编辑正文") }
                        if (post.canDelete) TextButton(onClick = { deleteTarget = post }) { Text("删除帖子") }
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
                item("follow") {
                    val author = state.selectedProfile?.takeIf { it.userId == post.author.userId } ?: post.author
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        CommunityFollowButton(
                            profile = author,
                            isSelf = post.author.userId == state.currentUser?.userId,
                            busy = state.followBusyUserId != null,
                            loggedIn = state.session != null,
                            onToggle = { viewModel.toggleFollow(post.author.userId) },
                        )
                    }
                }
                if (post.tags.isNotEmpty()) item("tags") {
                    CommunityPostTags(post.tags, null)
                }
                item("stats") {
                    CommunityCountRow(listOf("浏览" to post.viewCount, "点赞" to post.likeCount,
                        "收藏" to post.favoriteCount, "评论" to post.commentCount))
                }
            }
            if (post != null) {
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
                } else if (commentsError != null) {
                    item { CommunityThreadError("评论暂时无法读取", commentsError, { viewModel.loadComments(postId, state.commentSort) }) }
                } else if (state.comments.isEmpty()) {
                    item {
                        Text("还没有评论，来抢沙发吧", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    items(threadRows, key = { it.comment.id }) { (comment, parent, depth) ->
                        val branch = state.replyBranches[comment.id] ?: CommunityReplyBranch()
                        PostDetailCommentItem(
                            modifier = motionAnimateItem(),
                            comment = comment,
                            replyTo = parent?.author?.nickname,
                            depth = depth,
                            token = state.session?.token,
                            hasLoadedReplies = state.replies[comment.id].orEmpty().isNotEmpty(),
                            branch = branch,
                            onReply = { viewModel.startReply(comment.id) },
                            onLike = { viewModel.likeComment(comment.id) },
                            onEdit = null,
                            onDelete = { deleteCommentTarget = comment },
                            onToggleReplies = { viewModel.toggleReplies(comment.id) },
                            onReplySort = { viewModel.loadReplies(comment.id, it) },
                            onLoadReplies = { append -> viewModel.loadReplies(comment.id, branch.sort, append) },
                        )
                    }
                }
                if (state.relatedPosts.isNotEmpty()) {
                    item("related-title") { CommunitySectionTitle("相关推荐") }
                    item("related") {
                        CommunityPostTileGrid(state.relatedPosts, columns = 3, token = state.session?.token) { related ->
                            viewModel.loadPostDetail(related.id)
                        }
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
internal fun CommunityThreadError(title: String, message: String, onRetry: () -> Unit) {
    LoadErrorCard(title, message, onRetry)
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
internal fun CommentComposer(
    value: String,
    placeholder: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    sendEnabled: Boolean,
    replyingTo: String? = null,
    onCancelReply: (() -> Unit)? = null,
) {
    Column(Modifier.navigationBarsPadding().imePadding().motionAnimateContentSize()) {
        AnimatedVisibility(
            visible = replyingTo != null && onCancelReply != null,
            enter = motionExpandIn(),
            exit = motionShrinkOut(),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("回复 ${replyingTo.orEmpty()}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = { onCancelReply?.invoke() }, enabled = enabled) { Text("取消回复") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).semantics { contentDescription = placeholder },
                placeholder = { Text(placeholder) },
                maxLines = 4,
                enabled = enabled,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
            IconButton(onClick = onSend, enabled = enabled && sendEnabled) {
                Icon(AppIcons.Send, contentDescription = "发送", tint = if (enabled && sendEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            }
        }
    }
}

internal data class CommunityThreadRow(val comment: CommunityComment, val parent: CommunityComment?, val depth: Int)

internal fun visibleThreadRows(
    comments: List<CommunityComment>,
    replies: Map<Long, List<CommunityComment>>,
    branches: Map<Long, CommunityReplyBranch>,
): List<CommunityThreadRow> = buildList {
    val pending = ArrayDeque<CommunityThreadRow>()
    val visited = mutableSetOf<Long>()
    for (comment in comments.asReversed()) pending.addLast(CommunityThreadRow(comment, null, 0))
    while (pending.isNotEmpty()) {
        val row = pending.removeLast()
        if (!visited.add(row.comment.id)) continue
        add(row)
        if (branches[row.comment.id]?.expanded == true) {
            for (reply in replies[row.comment.id].orEmpty().asReversed()) {
                pending.addLast(CommunityThreadRow(reply, row.comment, row.depth + 1))
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PostDetailCommentItem(
    comment: CommunityComment,
    replyTo: String?,
    depth: Int,
    token: String?,
    hasLoadedReplies: Boolean,
    branch: CommunityReplyBranch,
    onReply: () -> Unit,
    onLike: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: () -> Unit,
    onToggleReplies: () -> Unit,
    onReplySort: (String) -> Unit,
    onLoadReplies: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().motionAnimateContentSize().padding(start = (depth.coerceAtMost(3) * 12).dp)) {
        CommunityCommentRow(
            comment = comment,
            token = token,
            onReply = onReply,
            onLike = onLike,
            onEdit = onEdit,
            onDelete = onDelete,
            replyTo = replyTo,
        )
        if (comment.replyCount > 0 || hasLoadedReplies || branch.expanded) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onToggleReplies) {
                    Text(if (branch.expanded) "收起回复" else "查看 ${comment.replyCount} 条回复")
                }
                if (branch.expanded) {
                    FilterChip(
                        selected = branch.sort == "newest",
                        onClick = { onReplySort("newest") },
                        label = { Text("最新") },
                    )
                    FilterChip(
                        selected = branch.sort == "oldest",
                        onClick = { onReplySort("oldest") },
                        label = { Text("最旧") },
                    )
                }
            }
            if (branch.expanded) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    when {
                        branch.loading -> Text("正在读取回复…", style = MaterialTheme.typography.bodySmall)
                        branch.error != null -> {
                            Text(branch.error, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onLoadReplies(branch.retryAppend) }) { Text("重试") }
                        }
                        branch.nextCursor != null -> TextButton(onClick = { onLoadReplies(true) }) { Text("加载更多回复") }
                        !hasLoadedReplies -> Text("暂无回复", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
    }
}

/** 形态 B 详情底部常驻的点赞、收藏操作（评论输入框紧随其下）。 */
@Composable
private fun DetailActionBar(post: CommunityPost, onLike: () -> Unit, onFavorite: () -> Unit) {
    val motionLevel = LocalMotionLevel.current
    val likeTint by animateColorAsState(
        targetValue = if (post.likedByViewer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = motionAnimationSpec(180),
        label = "detailLikeTint",
    )
    val favoriteTint by animateColorAsState(
        targetValue = if (post.isFavorited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = motionAnimationSpec(180),
        label = "detailFavoriteTint",
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onLike) {
            AnimatedContent(
                targetState = post.likedByViewer,
                transitionSpec = { motionFadeThroughSpec(motionLevel, 150, 90) },
                label = "detailLikeIcon",
            ) { liked ->
                Icon(
                    if (liked) AppIcons.HeartFilled else AppIcons.Heart,
                    contentDescription = if (liked) "取消点赞" else "点赞",
                    tint = likeTint,
                )
            }
        }
        Text(post.likeCount.toString(), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(16.dp))
        IconButton(onClick = onFavorite) {
            AnimatedContent(
                targetState = post.isFavorited,
                transitionSpec = { motionFadeThroughSpec(motionLevel, 150, 90) },
                label = "detailFavoriteIcon",
            ) { favorited ->
                Icon(
                    if (favorited) AppIcons.StarFilled else AppIcons.Star,
                    contentDescription = if (favorited) "取消收藏" else "收藏",
                    tint = favoriteTint,
                )
            }
        }
        post.favoriteCount?.let { Text(it.toString(), style = MaterialTheme.typography.labelMedium) }
    }
}
