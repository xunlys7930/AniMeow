package com.animeow.app.ui.reminder

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.reminder.AnimeReminderNotifier
import com.animeow.app.data.reminder.nextReminderDelay
import com.animeow.app.util.runCatchingCancellable
import com.animeow.app.ui.components.motionFadeIn
import com.animeow.app.ui.components.motionFadeOut
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private enum class PermissionFollowUp { SYNC, TEST }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderManagementScreen(
    onBack: () -> Unit,
    onEditReminder: (Long) -> Unit,
    viewModel: ReminderManagementViewModel = viewModel(),
) {
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var permissionEpoch by remember { mutableIntStateOf(0) }
    var permissionFollowUp by remember { mutableStateOf(PermissionFollowUp.SYNC) }
    var cancelTarget by remember { mutableStateOf<AnimeEntity?>(null) }

    val notificationsGranted = permissionEpoch.let { AnimeReminderNotifier.canPostNotifications(context) }
    val exactAlarmsEnabled = permissionEpoch.let { viewModel.canScheduleExactAlarms() }

    fun runAction(action: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true
            val result = runCatchingCancellable { action() }
            busy = false
            permissionEpoch += 1
            snackbar.showSnackbar(result.getOrElse { it.message ?: "操作失败" })
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionEpoch += 1
        if (!granted) {
            scope.launch { snackbar.showSnackbar("需要通知权限才能显示追番提醒") }
        } else {
            when (permissionFollowUp) {
                PermissionFollowUp.SYNC -> runAction(viewModel::synchronize)
                PermissionFollowUp.TEST -> runAction { viewModel.sendTestNotification() }
            }
        }
    }

    fun requireNotificationPermission(followUp: PermissionFollowUp) {
        permissionFollowUp = followUp
        if (Build.VERSION.SDK_INT >= 33 && !AnimeReminderNotifier.canPostNotifications(context)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            when (followUp) {
                PermissionFollowUp.SYNC -> runAction(viewModel::synchronize)
                PermissionFollowUp.TEST -> runAction { viewModel.sendTestNotification() }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionEpoch += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("追番提醒") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { permissionEpoch += 1 }, enabled = !busy) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新系统状态")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ReminderServiceCard(
                    configuredCount = reminders.size,
                    notificationsGranted = notificationsGranted,
                    exactAlarmsEnabled = exactAlarmsEnabled,
                    busy = busy,
                    onRequestPermission = { requireNotificationPermission(PermissionFollowUp.SYNC) },
                    onOpenExactAlarmSettings = {
                        val packageUri = Uri.parse("package:${context.packageName}")
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
                        } else {
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
                        }
                        runCatching { context.startActivity(intent) }
                            .onFailure {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
                            }
                    },
                    onSynchronize = { runAction(viewModel::synchronize) },
                    onTest = { requireNotificationPermission(PermissionFollowUp.TEST) },
                )
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("每周提醒", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "共 ${reminders.size} 条，修改后会自动重建系统任务",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (reminders.isEmpty()) {
                item { EmptyReminderCard() }
            } else {
                items(reminders, key = AnimeEntity::id) { anime ->
                    ReminderAnimeCard(
                        anime = anime,
                        onEdit = { onEditReminder(anime.id) },
                        onCancel = { cancelTarget = anime },
                    )
                }
            }
        }
    }

    cancelTarget?.let { anime ->
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            title = { Text("取消提醒？") },
            text = { Text("将关闭《${anime.title}》的每周提醒，作品资料不会改变。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        cancelTarget = null
                        runAction { viewModel.cancelReminder(anime) }
                    },
                ) { Text("取消提醒") }
            },
            dismissButton = { TextButton(onClick = { cancelTarget = null }) { Text("返回") } },
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ReminderServiceCard(
    configuredCount: Int,
    notificationsGranted: Boolean,
    exactAlarmsEnabled: Boolean,
    busy: Boolean,
    onRequestPermission: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onSynchronize: () -> Unit,
    onTest: () -> Unit,
) {
    val ready = notificationsGranted
    val accent = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(44.dp).clip(MaterialTheme.shapes.medium)
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (ready) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsOff,
                        contentDescription = null,
                        tint = accent,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (ready) "通知服务已就绪" else "需要通知权限",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (exactAlarmsEnabled) "准时提醒可用" else "使用省电友好的后台调度，触发时间可能略有延迟",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReminderInfoPill(Icons.Outlined.Schedule, "${ZoneId.systemDefault().id}")
                ReminderInfoPill(Icons.Outlined.Alarm, "$configuredCount 条")
            }
            AnimatedVisibility(
                visible = busy,
                enter = motionFadeIn(),
                exit = motionFadeOut(),
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!notificationsGranted) {
                    FilledTonalButton(onClick = onRequestPermission, enabled = !busy) {
                        Icon(Icons.Outlined.LockOpen, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("授予权限")
                    }
                }
                if (!exactAlarmsEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    OutlinedButton(onClick = onOpenExactAlarmSettings, enabled = !busy) {
                        Text("开启准时提醒")
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onSynchronize, enabled = !busy) {
                    Icon(Icons.Outlined.Sync, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("同步全部")
                }
                TextButton(onClick = onTest, enabled = !busy) { Text("发送测试通知") }
            }
        }
    }
}

@Composable
private fun ReminderInfoPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        modifier = Modifier.clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ReminderAnimeCard(
    anime: AnimeEntity,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(
            role = Role.Button,
            onClickLabel = "编辑《${anime.title}》的提醒",
            onClick = onEdit,
        ),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(width = 52.dp, height = 72.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (!anime.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = anime.coverUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(anime.title.take(2), fontWeight = FontWeight.Bold)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(anime.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${weekdayLabel(anime.reminderDay)} ${anime.reminderTime.orEmpty()}",
                    color = MaterialTheme.colorScheme.primary,
                )
                nextOccurrenceLabel(anime)?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AccessTime, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "编辑提醒") }
            IconButton(onClick = onCancel) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "取消提醒") }
        }
    }
}

@Composable
private fun EmptyReminderCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.NotificationsOff, contentDescription = null, modifier = Modifier.size(42.dp))
            Text("还没有每周提醒", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "在作品编辑页选择星期与时间后，会自动出现在这里。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun weekdayLabel(day: Int?): String = when (day) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    7 -> "周日"
    else -> "星期未知"
}

private fun nextOccurrenceLabel(anime: AnimeEntity): String? {
    val day = anime.reminderDay ?: return null
    val time = anime.reminderTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return null
    val now = LocalDateTime.now(ZoneId.systemDefault())
    val next = now.plus(nextReminderDelay(day, time, now))
    return "下次 ${next.format(DateTimeFormatter.ofPattern("MM-dd E HH:mm", Locale.CHINA))}"
}
