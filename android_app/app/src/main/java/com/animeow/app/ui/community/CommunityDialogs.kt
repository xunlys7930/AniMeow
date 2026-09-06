package com.animeow.app.ui.community

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.animeow.app.data.community.CommunityPost
import com.animeow.app.data.community.CommunityProfile
import com.animeow.app.data.community.CommunityReviewCase
import com.animeow.app.data.community.PresetImage
import com.animeow.app.data.community.presetImageUrl
import com.animeow.app.ui.statistics.StatisticsUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
internal fun authenticatedCommunityImageModel(url: String?, token: String?): Any? {
    val context = LocalContext.current
    return remember(url, token) {
        url?.takeIf(String::isNotBlank)?.let { value ->
            ImageRequest.Builder(context)
                .data(value)
                .apply {
                    token?.takeIf(String::isNotBlank)?.let { addHeader("Authorization", "Bearer $it") }
                }
                .crossfade(true)
                .build()
        }
    }
}

@Composable
internal fun CommunityAvatar(
    profile: CommunityProfile,
    token: String?,
    modifier: Modifier = Modifier,
) {
    val model = authenticatedCommunityImageModel(profile.avatarUrl, token)
    if (model != null) {
        AsyncImage(
            model = model,
            contentDescription = "${profile.nickname} 的头像",
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        )
    } else {
        Surface(
            modifier = modifier,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    profile.nickname.trim().take(1).ifBlank { "喵" },
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
internal fun CommunityProfileDialog(
    profile: CommunityProfile,
    token: String?,
    isCurrentUser: Boolean,
    posts: List<CommunityPost>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onBlock: (() -> Unit)? = null,
    onOpenBlacklist: (() -> Unit)? = null,
    onPostClick: (CommunityPost) -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isCurrentUser) "我的社区名片" else "个人名片") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // —— 头部：大头像 + 昵称 + 用户名 ——
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CommunityAvatar(profile, token, Modifier.size(88.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            profile.nickname,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        if (profile.isDeveloper) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "神秘人",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                        } else if (profile.isAdmin) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Outlined.AdminPanelSettings,
                                contentDescription = "管理员",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Text(
                        "@${profile.username}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                // —— 隐私保护 ——
                if (profile.isPrivate && !isCurrentUser) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Lock, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("该用户已将详细名片设为仅自己可见。")
                        }
                    }
                } else {
                    // —— 个性签名 ——
                    if (profile.signature.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                profile.signature,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    } else {
                        Text(
                            "这个人还没有写个签。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    // —— 统计名片 ——
                    CommunityStatisticsCard(profile.statistics, profile.showStatisticsCard)
                    // —— 可见性提示 ——
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    ) {
                        Icon(
                            if (profile.profileVisibility == "private") Icons.Outlined.Lock else Icons.Outlined.Public,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (profile.profileVisibility == "private") "名片仅自己可见" else "名片向社区成员展示",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (posts.isNotEmpty()) {
                    HorizontalDivider()
                    Text("TA 的帖子", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    posts.take(5).forEach { post ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().clickable { onPostClick(post) },
                        ) {
                            Text(
                                post.content.ifBlank { "（图片帖子）" },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isCurrentUser) {
                Row {
                    onOpenBlacklist?.let { open ->
                        TextButton(onClick = open) { Text("黑名单") }
                    }
                    Button(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("编辑")
                    }
                }
            } else {
                Row {
                    onBlock?.let { block ->
                        TextButton(onClick = block) { Text("屏蔽") }
                    }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        },
        dismissButton = if (isCurrentUser) {
            { TextButton(onClick = onDismiss) { Text("关闭") } }
        } else {
            null
        },
    )
}

@Composable
internal fun CommunityProfileEditorDialog(
    profile: CommunityProfile,
    localStatistics: StatisticsUiState,
    token: String?,
    busy: Boolean,
    presetImages: List<PresetImage>,
    presetDisclaimer: String,
    onDismiss: () -> Unit,
    onSave: (
        nickname: String,
        signature: String,
        privateProfile: Boolean,
        showStatistics: Boolean,
        selectedStatistics: Set<String>,
        avatarUri: Uri?,
        removeAvatar: Boolean,
        presetAvatar: String?,
    ) -> Unit,
) {
    var nickname by remember(profile.userId, profile.updatedAt) { mutableStateOf(profile.nickname) }
    var signature by remember(profile.userId, profile.updatedAt) { mutableStateOf(profile.signature) }
    var privateProfile by remember(profile.userId, profile.updatedAt) {
        mutableStateOf(profile.profileVisibility == "private")
    }
    var showStatistics by remember(profile.userId, profile.updatedAt) {
        mutableStateOf(profile.showStatisticsCard)
    }
    var selectedStatistics by remember(profile.userId, profile.updatedAt) {
        mutableStateOf(
            profile.statistics.keys
                .filter { key -> CommunityStatisticMetric.entries.any { it.key == key } }
                .toSet()
                .ifEmpty { DefaultCommunityStatisticKeys },
        )
    }
    var avatarUri by remember(profile.userId, profile.updatedAt) { mutableStateOf<Uri?>(null) }
    var removeAvatar by remember(profile.userId, profile.updatedAt) { mutableStateOf(false) }
    var presetAvatarName by remember(profile.userId, profile.updatedAt) { mutableStateOf<String?>(null) }
    var showPresetPicker by remember { mutableStateOf(false) }
    var showCropDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑社区名片") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val avatarModel: Any? = when {
                        presetAvatarName != null -> presetImageUrl(presetAvatarName!!)
                        avatarUri != null -> avatarUri
                        removeAvatar -> null
                        else -> authenticatedCommunityImageModel(profile.avatarUrl, token)
                    }
                    if (avatarModel != null) {
                        AsyncImage(
                            model = avatarModel,
                            contentDescription = "头像预览",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(72.dp).clip(CircleShape),
                        )
                    } else {
                        Surface(Modifier.size(72.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(34.dp))
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { showPresetPicker = true }, enabled = !busy) {
                            Icon(Icons.Outlined.Image, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("选择预设头像")
                        }
                        if (presetAvatarName != null) {
                            OutlinedButton(onClick = { showCropDialog = true }, enabled = !busy) {
                                Icon(Icons.Outlined.Crop, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("裁剪头像")
                            }
                        }
                        TextButton(
                            onClick = {
                                avatarUri = null
                                removeAvatar = true
                                presetAvatarName = null
                            },
                            enabled = !busy && (profile.avatarUrl != null || avatarUri != null || presetAvatarName != null),
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("移除头像")
                        }
                    }
                }
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it.take(24) },
                    label = { Text("昵称") },
                    supportingText = { Text("${nickname.length}/24") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = signature,
                    onValueChange = { signature = it.take(120) },
                    label = { Text("个性签名") },
                    supportingText = { Text("${signature.length}/120") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                SettingSwitchRow(
                    title = "仅自己可见",
                    subtitle = "开启后，其他成员只能看到帖子中的基础昵称",
                    checked = privateProfile,
                    onCheckedChange = { privateProfile = it },
                )
                SettingSwitchRow(
                    title = "展示统计名片",
                    subtitle = "默认关闭；只上传下方勾选的汇总数字，不上传作品明细",
                    checked = showStatistics,
                    onCheckedChange = { showStatistics = it },
                )
                if (showStatistics) {
                    ElevatedCard {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("选择对外展示的数据", fontWeight = FontWeight.SemiBold)
                            CommunityStatisticMetric.entries.forEach { metric ->
                                val selected = metric.key in selectedStatistics
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedStatistics = if (selected) {
                                                selectedStatistics - metric.key
                                            } else {
                                                selectedStatistics + metric.key
                                            }
                                        }
                                        .padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = {
                                            selectedStatistics = if (it) {
                                                selectedStatistics + metric.key
                                            } else {
                                                selectedStatistics - metric.key
                                            }
                                        },
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(metric.label)
                                        Text(
                                            localStatisticValue(metric, localStatistics),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Text(
                                "连续打卡默认不勾选，可按个人偏好开启。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        nickname,
                        signature,
                        privateProfile,
                        showStatistics,
                        selectedStatistics,
                        avatarUri,
                        removeAvatar,
                        presetAvatarName,
                    )
                },
                enabled = !busy && nickname.isNotBlank() && (!showStatistics || selectedStatistics.isNotEmpty()),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )

    if (showPresetPicker) {
        PresetImagePickerDialog(
            images = presetImages,
            disclaimer = presetDisclaimer,
            multiSelect = false,
            onSelected = { name ->
                presetAvatarName = name
                avatarUri = null
                removeAvatar = false
            },
            onDismiss = { showPresetPicker = false },
        )
    }

    if (showCropDialog && presetAvatarName != null) {
        ImageCropDialog(
            imageUrl = presetImageUrl(presetAvatarName!!),
            cropShape = CropShape.Circle,
            onConfirm = { uri ->
                avatarUri = uri
                presetAvatarName = null
                removeAvatar = false
                showCropDialog = false
            },
            onDismiss = { showCropDialog = false },
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun CommunityStatisticsCard(
    statistics: Map<String, Double>,
    visible: Boolean,
) {
    if (!visible || statistics.isEmpty()) return
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("追番档案", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            // 统计数据以紧凑双列网格展示
            statistics.entries.chunked(2).forEach { rowEntries ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowEntries.forEach { (key, value) ->
                        Surface(
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(
                                    communityStatisticLabel(key),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                )
                                Text(
                                    communityStatisticValue(key, value),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    if (rowEntries.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun CommunityJoinDialog(
    currentCode: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onJoin: (String) -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("使用随机群码加入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("目前仅开放一个官方群聊，账号首次进入时会默认加入。群码也可用于重新加入。")
                if (currentCode.isNotBlank()) {
                    Text("当前群码：${currentCode.chunked(4).joinToString("-")}", fontWeight = FontWeight.SemiBold)
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { value ->
                        code = value.uppercase().filter(Char::isLetterOrDigit).take(8)
                    },
                    label = { Text("8 位群码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onJoin(code) }, enabled = !busy && code.length == 8) { Text("加入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun CommunityReportDialog(
    authorName: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    val reasons = listOf("advertising", "abusive", "sexual", "copyright", "privacy", "other")
    var reason by rememberSaveable { mutableStateOf("advertising") }
    var details by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("举报 $authorName 的帖子") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        "提交后，该帖子、作者近 7 天的帖子以及审核期间的新帖会立即对外隐藏，随后由管理员人工复审。请勿恶意举报。",
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                reasons.forEach { item ->
                    FilterChip(
                        selected = reason == item,
                        onClick = { reason = item },
                        label = { Text(communityReportReasonLabel(item)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it.take(500) },
                    label = { Text(if (reason == "other") "补充说明（必填）" else "补充说明（可选）") },
                    minLines = 2,
                    maxLines = 5,
                    supportingText = { Text("${details.length}/500") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(reason, details) },
                enabled = !busy && (reason != "other" || details.trim().length >= 3),
            ) { Text("提交举报") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

@Composable
internal fun CommunityResolveReviewDialog(
    review: CommunityReviewCase,
    confirmViolation: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var note by rememberSaveable(review.id, confirmViolation) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirmViolation) "确认帖子违规？" else "恢复全部帖子？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (confirmViolation) {
                        "被直接举报的帖子将被移除；同一作者其余临时隔离帖子会恢复公开。"
                    } else {
                        "本次举报将标记为未确认，作者所有临时隔离帖子会恢复公开。"
                    },
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    label = { Text("审核备注（可选）") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(note) }, enabled = !busy) {
                Text(if (confirmViolation) "确认违规" else "恢复公开")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

internal fun communityReportReasonLabel(reason: String): String = when (reason) {
    "advertising" -> "广告或引流"
    "abusive" -> "辱骂、骚扰或仇恨"
    "sexual" -> "色情或不适内容"
    "copyright" -> "侵权内容"
    "privacy" -> "泄露隐私"
    else -> "其他违规"
}

internal fun communityStatisticLabel(key: String): String =
    CommunityStatisticMetric.entries.firstOrNull { it.key == key }?.label ?: key

internal fun communityStatisticValue(key: String, value: Double): String = when (key) {
    CommunityStatisticMetric.AVERAGE_RATING.key -> "%.1f / 10".format(value)
    CommunityStatisticMetric.ESTIMATED_MINUTES.key -> {
        val minutes = value.roundToInt()
        if (minutes >= 60) "${minutes / 60} 小时" else "$minutes 分钟"
    }
    CommunityStatisticMetric.STREAK_DAYS.key -> "${value.roundToInt()} 天"
    else -> value.roundToInt().toString()
}

private fun localStatisticValue(metric: CommunityStatisticMetric, state: StatisticsUiState): String = when (metric) {
    CommunityStatisticMetric.TOTAL_ITEMS -> "${state.totalAnime} 项"
    CommunityStatisticMetric.ANIME_COUNT -> "${state.animeCount} 部"
    CommunityStatisticMetric.BOOK_COUNT -> "${state.bookCount} 本"
    CommunityStatisticMetric.WATCHED_EPISODES -> "${state.watchedEpisodes} 集"
    CommunityStatisticMetric.ESTIMATED_MINUTES -> "约 ${state.watchedEpisodes * 24 / 60} 小时"
    CommunityStatisticMetric.AVERAGE_RATING -> state.averageRating?.let { "%.1f / 10".format(it) } ?: "暂无评分"
    CommunityStatisticMetric.STREAK_DAYS -> "${state.currentStreakDays} 天（默认不展示）"
}

internal fun formatCommunityTime(value: String): String {
    val instant = runCatching { Instant.parse(value) }.getOrNull()
    if (instant != null) {
        return DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(instant)
    }
    return value.replace('T', ' ').take(16).ifBlank { "刚刚" }
}
