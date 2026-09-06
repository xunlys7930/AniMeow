package com.animeow.app.ui.cloud

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LockReset
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animeow.app.data.cloud.CloudBackupFormat
import com.animeow.app.data.cloud.CloudBackupInfo
import com.animeow.app.data.backup.NativeRestorePoint
import com.animeow.app.data.backup.NativeRestoreSelection
import com.animeow.app.data.cloud.CloudConflictPolicy
import com.animeow.app.data.cloud.CloudSyncSettings
import com.animeow.app.ui.backup.NativeRestoreSelectionContent
import com.animeow.app.ui.community.CommunityProfileEditorDialog
import com.animeow.app.ui.community.CommunityViewModel
import com.animeow.app.ui.components.motionAnimateContentSize
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut

internal enum class AuthMode(val label: String) {
    LOGIN("登录"),
    REGISTER("注册"),
    RESET("改密"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudAccountScreen(
    onBack: () -> Unit,
    onFeedbackPoolRequested: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CloudAccountViewModel = viewModel(),
    communityViewModel: CommunityViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val communityState by communityViewModel.state.collectAsStateWithLifecycle()
    val communityLocalStatistics by communityViewModel.localStatistics.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var showSelectiveConflict by rememberSaveable { mutableStateOf(false) }
    var showChangePassword by rememberSaveable { mutableStateOf(false) }
    var showEditProfile by rememberSaveable { mutableStateOf(false) }
    var backupPendingDeletion by remember { mutableStateOf<CloudBackupInfo?>(null) }
    val contentEnter = motionFadeIn()
    val contentExit = motionFadeOut()
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHost.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(communityState.message) {
        communityState.message?.let {
            snackbarHost.showSnackbar(it)
            communityViewModel.clearMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text("云账号与设备备份") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.session != null) {
                        IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.configured) {
                item {
                    InfoCard(
                        title = "当前构建未配置云端服务",
                        body = "本地资料库、备份和导入仍可完整使用。配置 CLOUD_API_BASE 后即可启用账号与云备份。",
                    )
                }
            } else {
                item {
                    AnimatedContent(
                        targetState = state.session,
                        transitionSpec = { contentEnter togetherWith contentExit },
                        label = "cloud-account",
                    ) { session ->
                        if (session == null) AuthCard(state.busy, viewModel)
                        else AccountCard(
                            username = session.username,
                            profileNickname = communityState.currentUser?.nickname,
                            busy = state.busy,
                            onChangePassword = { showChangePassword = true },
                            onEditProfile = {
                                communityViewModel.refreshSession()
                                showEditProfile = true
                            },
                            onLogout = viewModel::logout,
                        )
                    }
                }
                if (state.session != null) {
                    item { SyncSection(state.syncSettings, state.busy, viewModel) }
                    item {
                        BackupSection(
                            backups = state.backups,
                            busy = state.busy,
                            viewModel = viewModel,
                            onDelete = { backupPendingDeletion = it },
                        )
                    }
                    if (state.restorePoints.isNotEmpty()) {
                        item { RestorePointSection(state.restorePoints, state.busy, viewModel::prepareRestorePoint) }
                    }
                    item { FeedbackEntrySection(onFeedbackPoolRequested) }
                }
            }
            if (state.busy) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                        Text("正在安全处理…", modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }

    state.preparedRestore?.let { prepared ->
        var selection by remember(prepared.backup.id) { mutableStateOf(NativeRestoreSelection()) }
        val isLegacy = prepared.format == CloudBackupFormat.LEGACY
        AlertDialog(
            onDismissRequest = viewModel::cancelRestore,
            title = { Text(if (isLegacy) "迁移旧版云备份？" else "从云端恢复这份备份？") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(prepared.backup.fileName, fontWeight = FontWeight.SemiBold)
                    Text("${prepared.preview.animeCount} 部作品 · ${prepared.preview.characterCount} 个角色 · ${prepared.preview.coverCount} 张封面")
                    if (isLegacy) {
                        Text(
                            "已识别为 1.3.9 旧版格式。迁移会替换当前资料库，并导入作品、书籍、系列、标签、观看记录、角色与本地封面。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "当前外观、交互、云账号和 2.0 专属设置会保留；操作前会自动创建完整恢复点。",
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text("恢复前会自动生成本机恢复点，只替换你选择的范围。", color = MaterialTheme.colorScheme.error)
                        NativeRestoreSelectionContent(
                            selection = selection,
                            onSelectionChange = { selection = it },
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.confirmRestore(
                            if (isLegacy) {
                                NativeRestoreSelection(
                                    libraryAndCharacters = true,
                                    analysisHistory = true,
                                    appearanceSettings = false,
                                )
                            } else {
                                selection
                            },
                        )
                    },
                    enabled = !state.busy && (isLegacy || !selection.isEmpty),
                ) { Text(if (isLegacy) "确认迁移" else "确认恢复") }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelRestore) { Text("取消") } },
        )
    }

    state.preparedRestorePoint?.let { prepared ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRestorePoint,
            title = { Text("回退到这个恢复点？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(prepared.point.createdAt.replace('T', ' ').take(19), fontWeight = FontWeight.SemiBold)
                    Text("${prepared.prepared.summary.animeCount} 部作品 · ${prepared.prepared.summary.characterCount} 个角色")
                    Text("回退后会重新检测与云端的同步关系。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { Button(onClick = viewModel::confirmRestorePoint) { Text("确认回退") } },
            dismissButton = { TextButton(onClick = viewModel::cancelRestorePoint) { Text("取消") } },
        )
    }

    state.conflictBackup?.let { backup ->
        AlertDialog(
            onDismissRequest = viewModel::dismissConflict,
            title = { Text("发现设备间冲突") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("本机与云端都在上次同步后发生了变化。任何云端覆盖操作都会先创建本机恢复点。")
                    Text(
                        "云端：${backup.createdAt.ifBlank { backup.uploadDate }} · ${formatBytes(backup.payloadSize)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(
                        onClick = viewModel::resolveConflictWithCloud,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("采用云端全部内容") }
                    OutlinedButton(
                        onClick = { showSelectiveConflict = true },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("按范围安全合并") }
                }
            },
            confirmButton = {
                Button(onClick = viewModel::resolveConflictWithLocal, enabled = !state.busy) {
                    Text("保留本机并上传")
                }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissConflict) { Text("稍后处理") } },
        )
    }

    if (showSelectiveConflict && state.conflictBackup != null) {
        SelectiveConflictDialog(
            busy = state.busy,
            onDismiss = { showSelectiveConflict = false },
            onConfirm = { selection ->
                showSelectiveConflict = false
                viewModel.resolveConflictSelectively(selection)
            },
        )
    }
    state.session?.let { session ->
        if (showChangePassword) {
            PasswordResetDialog(
                username = session.username,
                busy = state.busy,
                onSubmit = { password, inviteCode ->
                    showChangePassword = false
                    viewModel.resetPassword(session.username, password, inviteCode)
                },
                onDismiss = { showChangePassword = false },
            )
        }
    }

    if (showEditProfile) {
        val profile = communityState.currentUser
        if (profile != null) {
            CommunityProfileEditorDialog(
                profile = profile,
                localStatistics = communityLocalStatistics,
                token = communityState.session?.token,
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

    backupPendingDeletion?.let { backup ->
        AlertDialog(
            onDismissRequest = { backupPendingDeletion = null },
            title = { Text("删除这份云端备份？") },
            text = { Text("${backup.fileName}\n删除后无法从服务器找回，本机资料不会变化。") },
            confirmButton = {
                Button(onClick = {
                    backupPendingDeletion = null
                    viewModel.deleteBackup(backup)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { backupPendingDeletion = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun AuthCard(busy: Boolean, viewModel: CloudAccountViewModel) {
    var mode by rememberSaveable { mutableStateOf(AuthMode.LOGIN) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var confirmPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }
    ElevatedCard(modifier = Modifier.fillMaxWidth().motionAnimateContentSize()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("连接我的 AniMeow", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthMode.entries.forEach { item ->
                    FilterChip(
                        selected = mode == item,
                        onClick = {
                            mode = item
                            confirmPassword = ""
                            validationError = null
                        },
                        label = { Text(item.label) },
                    )
                }
            }
            OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(), label = { Text("用户名") }, singleLine = true)
            CloudPasswordField(
                value = password,
                onValueChange = {
                    password = it
                    validationError = null
                },
                label = if (mode == AuthMode.RESET) "新密码" else "密码",
                visible = passwordVisible,
                onVisibilityChanged = { passwordVisible = it },
            )
            if (mode != AuthMode.LOGIN) {
                CloudPasswordField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        validationError = null
                    },
                    label = "确认密码",
                    visible = confirmPasswordVisible,
                    onVisibilityChanged = { confirmPasswordVisible = it },
                )
                if (mode == AuthMode.REGISTER) {
                    Text(
                        "每台设备只能创建一个账号；创建后，该账号仍可在其他手机或平板正常登录。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    OutlinedTextField(
                        inviteCode,
                        { inviteCode = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("旧版邀请码（可选）") },
                        supportingText = { Text("仅原邀请码注册账号在非注册设备改密时需要") },
                        singleLine = true,
                    )
                }
            }
            validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    val error = validateCloudAuthForm(mode, username, password, confirmPassword, inviteCode)
                    if (error != null) {
                        validationError = error
                    } else {
                        when (mode) {
                            AuthMode.LOGIN -> viewModel.login(username, password)
                            AuthMode.REGISTER -> viewModel.register(username, password)
                            AuthMode.RESET -> viewModel.resetPassword(username, password, inviteCode)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) { Text(mode.label) }
        }
    }
}

@Composable
private fun AccountCard(
    username: String,
    profileNickname: String?,
    busy: Boolean,
    onChangePassword: () -> Unit,
    onEditProfile: () -> Unit,
    onLogout: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(username, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        profileNickname
                            ?.takeIf { it.isNotBlank() && it != username }
                            ?.let { "社区昵称：$it" }
                            ?: "本地优先 · 同步策略由你决定",
                    )
                }
                IconButton(onClick = onChangePassword, enabled = !busy) {
                    Icon(Icons.Outlined.LockReset, contentDescription = "修改密码")
                }
                IconButton(onClick = onLogout, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = "退出")
                }
            }
            OutlinedButton(onClick = onEditProfile, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Edit, contentDescription = null)
                Text("编辑个人资料", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
internal fun PasswordResetDialog(
    username: String,
    busy: Boolean,
    onSubmit: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable(username) { mutableStateOf("") }
    var confirmPassword by rememberSaveable(username) { mutableStateOf("") }
    var inviteCode by rememberSaveable(username) { mutableStateOf("") }
    var passwordVisible by rememberSaveable(username) { mutableStateOf(false) }
    var confirmPasswordVisible by rememberSaveable(username) { mutableStateOf(false) }
    var validationError by rememberSaveable(username) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改密码") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("账号：$username", fontWeight = FontWeight.SemiBold)
                Text("新账号会验证注册设备；原邀请码账号仍可填写旧邀请码。修改后当前设备会退出登录。")
                CloudPasswordField(
                    value = password,
                    onValueChange = { password = it; validationError = null },
                    label = "新密码",
                    visible = passwordVisible,
                    onVisibilityChanged = { passwordVisible = it },
                )
                CloudPasswordField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; validationError = null },
                    label = "确认新密码",
                    visible = confirmPasswordVisible,
                    onVisibilityChanged = { confirmPasswordVisible = it },
                )
                OutlinedTextField(
                    value = inviteCode,
                    onValueChange = { inviteCode = it; validationError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("旧版邀请码（可选）") },
                    supportingText = { Text("仅原邀请码注册账号在其他设备改密时需要") },
                    singleLine = true,
                )
                validationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy,
                onClick = {
                    val error = validateCloudAuthForm(
                        AuthMode.RESET,
                        username,
                        password,
                        confirmPassword,
                        inviteCode,
                    )
                    if (error == null) onSubmit(password, inviteCode) else validationError = error
                },
            ) { Text("修改并退出") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun CloudPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onVisibilityChanged: (Boolean) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { onVisibilityChanged(!visible) }) {
                Icon(
                    if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (visible) "隐藏密码" else "显示密码",
                )
            }
        },
        singleLine = true,
    )
}

internal fun validateCloudAuthForm(
    mode: AuthMode,
    username: String,
    password: String,
    confirmPassword: String,
    inviteCode: String,
): String? = when {
    username.trim().isEmpty() -> "请填写用户名"
    password.length !in 6..72 -> "密码需为 6–72 位"
    mode != AuthMode.LOGIN && password != confirmPassword -> "两次输入的密码不一致"
    else -> null
}

@Composable
private fun SyncSection(
    settings: CloudSyncSettings,
    busy: Boolean,
    viewModel: CloudAccountViewModel,
) {
    fun save(
        enabled: Boolean = settings.autoSyncEnabled,
        interval: Int = settings.intervalHours,
        unmetered: Boolean = settings.unmeteredOnly,
        policy: CloudConflictPolicy = settings.conflictPolicy,
    ) = viewModel.updateSyncConfiguration(enabled, interval, unmetered, policy)

    ElevatedCard(modifier = Modifier.fillMaxWidth().motionAnimateContentSize()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("设备同步", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        settings.lastSyncMessage ?: "本地修改优先保存，冲突绝不静默覆盖",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = viewModel::syncNow, enabled = !busy) {
                    Icon(Icons.Outlined.Sync, contentDescription = null)
                    Text("同步", Modifier.padding(start = 5.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("后台自动同步", fontWeight = FontWeight.SemiBold)
                    Text("仅在有网络且电量充足时运行", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = settings.autoSyncEnabled,
                    onCheckedChange = { save(enabled = it) },
                    enabled = !busy,
                )
            }
            if (settings.autoSyncEnabled) {
                Text("同步间隔", style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf(6, 12, 24, 72, 168)) { hours ->
                        FilterChip(
                            selected = settings.intervalHours == hours,
                            onClick = { save(interval = hours) },
                            label = { Text(if (hours < 24) "${hours} 小时" else "${hours / 24} 天") },
                            enabled = !busy,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("仅不限流量网络")
                        Text("适合封面较多的资料库", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = settings.unmeteredOnly,
                        onCheckedChange = { save(unmetered = it) },
                        enabled = !busy,
                    )
                }
            }
            Text("冲突处理", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(CloudConflictPolicy.entries) { policy ->
                    FilterChip(
                        selected = settings.conflictPolicy == policy,
                        onClick = { save(policy = policy) },
                        label = { Text(policy.label) },
                        enabled = !busy,
                    )
                }
            }
            settings.lastSyncAt?.let {
                Text(
                    "最近处理：${it.replace('T', ' ').take(19)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BackupSection(
    backups: List<CloudBackupInfo>,
    busy: Boolean,
    viewModel: CloudAccountViewModel,
    onDelete: (CloudBackupInfo) -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("云端数据存储", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("服务器保留最近 3 份；上传前自动生成完整原生备份", style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton(onClick = viewModel::uploadBackup, enabled = !busy) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                    Text("上传", modifier = Modifier.padding(start = 5.dp))
                }
            }
            if (backups.isEmpty()) Text("还没有云端备份", color = MaterialTheme.colorScheme.onSurfaceVariant)
            backups.forEach { backup ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(backup.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text("${formatBytes(backup.payloadSize)} · ${backup.createdAt.ifBlank { backup.uploadDate }}", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { viewModel.prepareRestore(backup) }, enabled = !busy) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = "恢复")
                    }
                    IconButton(onClick = { onDelete(backup) }, enabled = !busy) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除云端备份")
                    }
                }
            }
        }
    }
}

@Composable
private fun RestorePointSection(
    points: List<NativeRestorePoint>,
    busy: Boolean,
    onRestore: (NativeRestorePoint) -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("安全恢复点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("云端覆盖前自动保留，最多 3 份。", style = MaterialTheme.typography.bodySmall)
            points.forEach { point ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(point.createdAt.replace('T', ' ').take(19), fontWeight = FontWeight.SemiBold)
                        Text(
                            "${formatBytes(point.sizeBytes)} · ${point.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { onRestore(point) }, enabled = !busy) { Text("回退") }
                }
            }
        }
    }
}

@Composable
private fun FeedbackEntrySection(onOpen: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.RateReview, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("开放反馈池", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "公开浏览建议与处理状态，也可以提交问题或功能想法。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = onOpen) { Text("打开反馈池") }
            }
        }
    }
}

@Composable
private fun SelectiveConflictDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (NativeRestoreSelection) -> Unit,
) {
    var library by rememberSaveable { mutableStateOf(true) }
    var analysis by rememberSaveable { mutableStateOf(false) }
    var appearance by rememberSaveable { mutableStateOf(false) }
    val selection = NativeRestoreSelection(
        libraryAndCharacters = library,
        analysisHistory = analysis,
        appearanceSettings = appearance,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按范围合并云端内容") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("资料记录按条目去重合并，冲突字段保留本机值；设置范围采用云端值。合并前会创建完整恢复点。")
                NativeRestoreSelectionContent(selection) { updated ->
                    library = updated.libraryAndCharacters
                    analysis = updated.analysisHistory
                    appearance = updated.appearanceSettings
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selection) }, enabled = !busy && !selection.isEmpty) {
                Text("安全合并")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun InfoCard(title: String, body: String) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
}
