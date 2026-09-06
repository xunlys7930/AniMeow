package com.animeow.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.LockReset
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.ui.cloud.CloudAccountViewModel
import com.animeow.app.ui.cloud.PasswordResetDialog
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.community.CommunityAvatar
import com.animeow.app.ui.community.CommunityProfileEditorDialog
import com.animeow.app.ui.community.CommunityViewModel
import com.animeow.app.ui.statistics.StatisticsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalCenterScreen(
    onBack: () -> Unit,
    onCloudAccountRequested: () -> Unit,
    onCommunityRequested: () -> Unit,
    modifier: Modifier = Modifier,
    cloudAccountViewModel: CloudAccountViewModel = viewModel(),
    communityViewModel: CommunityViewModel = viewModel(),
    statisticsViewModel: StatisticsViewModel = viewModel(),
) {
    val cloudState by cloudAccountViewModel.state.collectAsStateWithLifecycle()
    val communityState by communityViewModel.state.collectAsStateWithLifecycle()
    val statisticsState by statisticsViewModel.state.collectAsStateWithLifecycle()
    val communityLocalStatistics by communityViewModel.localStatistics.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var showEditProfile by rememberSaveable { mutableStateOf(false) }
    var showChangePassword by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(cloudState.message) {
        cloudState.message?.let {
            snackbarHost.showSnackbar(it)
            cloudAccountViewModel.clearMessage()
        }
    }
    LaunchedEffect(communityState.message) {
        communityState.message?.let {
            snackbarHost.showSnackbar(it)
            communityViewModel.clearMessage()
        }
    }

    val session = cloudState.session
    val currentUser = communityState.currentUser
    val token = communityState.session?.token
    val watchingCount = statisticsState.statusCounts.find { it.name == "在看" }?.count ?: 0
    val completedCount = statisticsState.statusCounts.find { it.name == "看完" }?.count ?: 0

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("个人中心") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (session != null) {
                        IconButton(onClick = communityViewModel::refreshSession, enabled = !communityState.busy) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (session == null) {
            LoggedOutContent(
                onLogin = onCloudAccountRequested,
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 48.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    PersonalProfileCard(
                        username = session.username,
                        profile = currentUser,
                        token = token,
                        totalAnime = statisticsState.totalAnime,
                        watchingCount = watchingCount,
                        completedCount = completedCount,
                        watchHours = statisticsState.watchHours,
                    )
                }
                item {
                    PersonalActionSection(
                        busy = cloudState.busy || communityState.busy,
                        onEditProfile = {
                            communityViewModel.refreshSession()
                            showEditProfile = true
                        },
                        onChangePassword = { showChangePassword = true },
                        onLogout = cloudAccountViewModel::logout,
                    )
                }
                item {
                    PersonalLinkSection(
                        onCloudAccount = onCloudAccountRequested,
                        onCommunity = onCommunityRequested,
                    )
                }
            }
        }
    }

    if (showChangePassword && session != null) {
        PasswordResetDialog(
            username = session.username,
            busy = cloudState.busy,
            onSubmit = { password, inviteCode ->
                showChangePassword = false
                cloudAccountViewModel.resetPassword(session.username, password, inviteCode)
            },
            onDismiss = { showChangePassword = false },
        )
    }

    if (showEditProfile) {
        val profile = currentUser
        if (profile != null) {
            CommunityProfileEditorDialog(
                profile = profile,
                localStatistics = communityLocalStatistics,
                token = token,
                busy = communityState.busy,
                presetImages = communityState.presetImages,
                presetDisclaimer = communityState.presetDisclaimer,
                onDismiss = { showEditProfile = false },
                onSave = { nickname, signature, privateProfile, showStatistics, selected, avatar, removeAvatar, presetAvatar ->
                    communityViewModel.updateProfile(
                        nickname = nickname,
                        signature = signature,
                        privateProfile = privateProfile,
                        showStatistics = showStatistics,
                        selectedStatistics = selected,
                        avatarUri = avatar,
                        removeAvatar = removeAvatar,
                        presetAvatar = presetAvatar,
                    )
                    showEditProfile = false
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { showEditProfile = false },
                title = { Text("正在加载社区资料") },
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator()
                        Text("正在获取你的社区名片…")
                    }
                },
                confirmButton = {},
            )
        }
    }
}

@Composable
private fun LoggedOutContent(
    onLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Surface(
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "喵",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                "尚未登录",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "登录后即可编辑个人资料、同步追番记录和参与社区。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            FilledTonalButton(onClick = onLogin) {
                Icon(Icons.Outlined.CloudDone, contentDescription = null)
                Text("前往登录", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun PersonalProfileCard(
    username: String,
    profile: com.animeow.app.data.community.CommunityProfile?,
    token: String?,
    totalAnime: Int,
    watchingCount: Int,
    completedCount: Int,
    watchHours: Int,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (profile != null) {
                    CommunityAvatar(
                        profile = profile,
                        token = token,
                        modifier = Modifier.size(72.dp),
                    )
                } else {
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                username.first().toString().uppercase(),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        username,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    profile?.nickname
                        ?.takeIf { it.isNotBlank() && it != username }
                        ?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    profile?.signature
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileStatItem("追番", totalAnime)
                VerticalDivider()
                ProfileStatItem("在看", watchingCount)
                VerticalDivider()
                ProfileStatItem("看完", completedCount)
                VerticalDivider()
                ProfileStatItem("时长", watchHours)
            }
        }
    }
}

@Composable
private fun ProfileStatItem(label: String, count: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            count.toString(),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        modifier = Modifier
            .size(width = 1.dp, height = 28.dp)
            .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f))
    )
}

@Composable
private fun PersonalActionSection(
    busy: Boolean,
    onEditProfile: () -> Unit,
    onChangePassword: () -> Unit,
    onLogout: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column {
            PersonalActionRow(
                icon = Icons.Outlined.Edit,
                title = "编辑个人资料",
                subtitle = "头像、昵称、签名与社区名片",
                onClick = onEditProfile,
                enabled = !busy,
            )
            PersonalActionDivider()
            PersonalActionRow(
                icon = Icons.Outlined.LockReset,
                title = "修改密码",
                subtitle = "修改后当前设备会退出登录",
                onClick = onChangePassword,
                enabled = !busy,
            )
            PersonalActionDivider()
            PersonalActionRow(
                icon = Icons.AutoMirrored.Outlined.Logout,
                title = "退出登录",
                subtitle = "退出后本地数据不会丢失",
                onClick = onLogout,
                enabled = !busy,
            )
        }
    }
}

@Composable
private fun PersonalActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .animatedPressClick(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = title,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f),
            tonalElevation = 0.dp,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(9.dp),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Icon(
            Icons.Outlined.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .graphicsLayer { rotationZ = -90f }
                .size(20.dp),
        )
    }
}

@Composable
private fun PersonalActionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 68.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f),
    )
}

@Composable
private fun PersonalLinkSection(
    onCloudAccount: () -> Unit,
    onCommunity: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column {
            PersonalActionRow(
                icon = Icons.Outlined.CloudDone,
                title = "云端备份与同步",
                subtitle = "数据备份、跨设备恢复与同步设置",
                onClick = onCloudAccount,
            )
            PersonalActionDivider()
            PersonalActionRow(
                icon = Icons.Outlined.Groups,
                title = "进入社区",
                subtitle = "群聊、图文交流与个人统计名片",
                onClick = onCommunity,
            )
        }
    }
}
