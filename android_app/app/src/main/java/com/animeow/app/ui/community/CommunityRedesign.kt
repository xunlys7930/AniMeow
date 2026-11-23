package com.animeow.app.ui.community

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.animeow.app.data.community.COMMUNITY_MAX_POST_TAGS
import com.animeow.app.data.community.COMMUNITY_MAX_TAG_LENGTH
import com.animeow.app.data.community.CommunityPost
import com.animeow.app.data.community.CommunityProfile
import com.animeow.app.data.community.CommunityTag
import com.animeow.app.data.community.normalizeCommunityTag
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.components.motionFadeThroughSpec
import com.animeow.app.ui.theme.LocalMotionLevel
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.icons.AppIcon as Icon

/** 瀑布流顶部页签：最新 / 关注 / 精华。不使用“热门”，避免暗示真实热度排名。 */
internal fun waterfallTabOf(state: CommunityUiState): String = when {
    state.feedSort == "following" -> "following"
    state.feedFeaturedOnly -> "featured"
    else -> "latest"
}

@Composable
internal fun WaterfallFeedTabs(current: String, onSelect: (String) -> Unit) {
    val tabs = listOf("latest" to "最新", "following" to "关注", "featured" to "精华")
    PrimaryTabRow(selectedTabIndex = tabs.indexOfFirst { it.first == current }.coerceAtLeast(0)) {
        tabs.forEach { (key, label) ->
            Tab(selected = current == key, onClick = { onSelect(key) }, text = { Text(label) })
        }
    }
}

/** 标签筛选行：全部 + 常用标签。标签接口不可用时调用方不渲染本行。 */
@Composable
internal fun CommunityTagFilterRow(tags: List<CommunityTag>, selected: String?, onSelect: (String?) -> Unit) {
    val names = buildList {
        selected?.let { add(it) }
        tags.forEach { tag -> if (none { it.equals(tag.tag, ignoreCase = true) }) add(tag.tag) }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item { FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("全部") }) }
        items(names, key = { it }) { name ->
            FilterChip(
                selected = selected.equals(name, ignoreCase = true),
                onClick = { onSelect(if (selected.equals(name, ignoreCase = true)) null else name) },
                label = { Text("#$name") },
                modifier = Modifier.semantics { contentDescription = "按标签 $name 筛选" },
            )
        }
    }
}

/** 帖子上的标签（只读）；点击回到社区并按该标签筛选。 */
@Composable
internal fun CommunityPostTags(tags: List<String>, onClick: ((String) -> Unit)?) {
    if (tags.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(tags, key = { it }) { tag ->
            AssistChip(
                onClick = { onClick?.invoke(tag) },
                enabled = onClick != null,
                label = { Text("#$tag") },
                modifier = Modifier.semantics { contentDescription = "标签 $tag，点按查看同标签帖子" },
            )
        }
    }
}

/** 发帖的标签输入：已选标签可移除；输入框回车或点“添加”加入；下方给出常用标签建议。 */
@Composable
internal fun CommunityDraftTagEditor(
    tags: List<String>,
    suggestions: List<CommunityTag>,
    enabled: Boolean,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val full = tags.size >= COMMUNITY_MAX_POST_TAGS
    val candidate = normalizeCommunityTag(input)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("标签（可选）", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("${tags.size}/$COMMUNITY_MAX_POST_TAGS", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (tags.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(tags, key = { it }) { tag ->
                    InputChip(
                        selected = true,
                        onClick = { onRemove(tag) },
                        enabled = enabled,
                        label = { Text("#$tag") },
                        trailingIcon = { Icon(AppIcons.Close, contentDescription = "移除标签 $tag", modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
        if (!full) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { value ->
                        if (value.endsWith(" ") || value.endsWith(",") || value.endsWith("，")) {
                            normalizeCommunityTag(value.dropLast(1))?.let(onAdd)
                            input = ""
                        } else {
                            input = value.take(COMMUNITY_MAX_TAG_LENGTH + 2)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = enabled,
                    placeholder = { Text("例如 截图、感想") },
                    isError = input.isNotBlank() && candidate == null,
                    supportingText = if (input.isNotBlank() && candidate == null) {
                        { Text("标签需为 1–$COMMUNITY_MAX_TAG_LENGTH 个字，且不含 # 或逗号") }
                    } else null,
                )
                TextButton(
                    onClick = { candidate?.let(onAdd); input = "" },
                    enabled = enabled && candidate != null,
                ) { Text("添加") }
            }
            val available = suggestions.filter { s -> tags.none { it.equals(s.tag, ignoreCase = true) } }
            if (available.isNotEmpty()) {
                Text("常用标签", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(available, key = { it.tag }) { suggestion ->
                        AssistChip(onClick = { onAdd(suggestion.tag) }, enabled = enabled, label = { Text("#${suggestion.tag}") })
                    }
                }
            }
        }
    }
}

/** 关注按钮：isFollowing 为 null（旧服务器）或查看自己时不显示。 */
@Composable
internal fun CommunityFollowButton(profile: CommunityProfile?, isSelf: Boolean, busy: Boolean, loggedIn: Boolean, onToggle: () -> Unit) {
    val following = profile?.isFollowing ?: return
    if (isSelf || !loggedIn) return
    val motionLevel = LocalMotionLevel.current
    AnimatedContent(
        targetState = following,
        transitionSpec = { motionFadeThroughSpec(motionLevel, 160, 110) },
        label = "followButtonState",
    ) { isFollowing ->
        if (isFollowing) {
            OutlinedButton(onClick = onToggle, enabled = !busy) { Text("已关注") }
        } else {
            FilledTonalButton(onClick = onToggle, enabled = !busy) { Text("关注") }
        }
    }
}

/** 数字统计行：只展示已知的项，未知项省略而不是显示 0。 */
@Composable
internal fun CommunityCountRow(
    items: List<Pair<String, Int?>>,
    modifier: Modifier = Modifier,
    clickableLabels: Set<String> = emptySet(),
    onLabelClick: (String) -> Unit = {},
) {
    val known = items.mapNotNull { (label, value) -> value?.let { label to it } }
    if (known.isEmpty()) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        known.forEach { (label, value) ->
            val clickable = label in clickableLabels
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (clickable) Modifier.animatedPressClick(onClickLabel = "查看$label") { onLabelClick(label) } else Modifier)
                    .semantics(mergeDescendants = true) {}) {
                Text(formatCount(value), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = if (clickable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = if (clickable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun formatCount(value: Int): String = when {
    value >= 10_000 -> String.format(java.util.Locale.ROOT, "%.1f万", value / 10_000.0).replace(".0万", "万")
    else -> value.toString()
}

/** 图片横向翻页，右上角显示“1 / N”；点击图片打开放大查看。 */
@Composable
internal fun CommunityImagePager(post: CommunityPost, token: String?, onImageClick: (String) -> Unit) {
    if (post.media.isEmpty()) return
    val pager = rememberPagerState(pageCount = { post.media.size })
    val first = post.media.first()
    val ratio = if ((first.width ?: 0) > 0 && (first.height ?: 0) > 0) {
        (first.width!!.toFloat() / first.height!!).coerceIn(0.6f, 1.8f)
    } else 1f
    Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().aspectRatio(ratio)) { page ->
            val media = post.media[page]
            AsyncImage(
                model = authenticatedCommunityImageModel(media.url, token),
                contentDescription = "帖子图片 ${page + 1}，共 ${post.media.size} 张，点按放大",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onImageClick(media.url) },
            )
        }
        if (post.media.size > 1) {
            Text(
                "${pager.currentPage + 1} / ${post.media.size}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** 小方格：有图显示首图，纯文字帖显示正文摘要。用于相关推荐与个人空间作品页。 */
@Composable
internal fun CommunityPostTile(post: CommunityPost, token: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val media = post.media.firstOrNull()
    Box(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .animatedPressClick(onClick = onClick)
            .semantics { contentDescription = "打开帖子：" + post.content.take(24).ifBlank { "图片帖子" } },
    ) {
        if (media != null) {
            AsyncImage(
                model = authenticatedCommunityImageModel(media.url, token),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (post.media.size > 1) {
                Text("+${post.media.size - 1}", style = MaterialTheme.typography.labelSmall, color = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp))
            }
        } else {
            Text(
                post.content,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
        }
        if (post.isQuarantined) {
            Text("复审中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp))
        }
    }
}

/** 按列数切成若干行的小方格网格（放在 LazyColumn 条目内，不嵌套可滚动网格）。 */
@Composable
internal fun CommunityPostTileGrid(posts: List<CommunityPost>, columns: Int, token: String?, onPostClick: (CommunityPost) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        posts.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { post -> CommunityPostTile(post, token, { onPostClick(post) }, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 瀑布流的空状态文案：区分关注页未登录、没有关注、服务器不支持筛选。 */
@Composable
internal fun WaterfallEmptyState(state: CommunityUiState, onLogin: () -> Unit) {
    val (title, body) = when {
        state.feedFilterUnsupported -> "当前服务器暂不支持此筛选" to "服务器更新后即可按关注或标签查看，现在可以切回“最新”浏览全部帖子。"
        state.feedSort == "following" && state.session == null -> "登录后查看关注" to "登录后，在喜欢的作者个人空间点“关注”，他们的新帖子会出现在这里。"
        state.feedSort == "following" -> "还没有关注的作者" to "进入作者的个人空间，点“关注”后，对方的新帖子会出现在这里。"
        state.feedTag != null -> "这个标签下还没有帖子" to "发帖时添加 #${state.feedTag}，就会出现在这里。"
        state.feedFeaturedOnly -> "还没有精华帖子" to "管理员设为精华的帖子会出现在这里。"
        else -> "茶话会还很安静" to "发一条文字或图片，成为第一个开场的人吧。"
    }
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (state.feedSort == "following" && state.session == null) {
            FilledTonalButton(onClick = onLogin) { Text("登录或注册") }
        }
    }
}

@Composable
internal fun CommunityLoadMoreFooter(loading: Boolean, failed: Boolean, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            failed -> OutlinedButton(onClick = onRetry) { Text("加载失败，点此重试") }
        }
    }
}

@Composable
internal fun CommunitySectionTitle(text: String) {
    Surface(color = Color.Transparent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).size(3.dp, 16.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * 详情页点标签后回到社区并按标签筛选。详情页与社区页各自持有 ViewModel，
 * 这里用进程内的一次性请求传递，社区页取走后清空。
 */
internal object CommunityTagRequests {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    fun request(tag: String) { pending.value = tag }
    fun consume(): String? = pending.value.also { pending.value = null }
}
