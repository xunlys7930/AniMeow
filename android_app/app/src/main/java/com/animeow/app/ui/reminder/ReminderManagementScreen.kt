package com.animeow.app.ui.reminder

import com.animeow.app.ui.icons.AppIcons

import android.Manifest
import android.content.Context
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import com.animeow.app.ui.icons.AppIcon as Icon
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private enum class PermissionFollowUp { SYNC, TEST, SCHEDULED_TEST }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReminderManagementScreen(
    onBack: () -> Unit,
    onEditReminder: (Long) -> Unit,
    viewModel: ReminderManagementViewModel = viewModel(),
) {
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val reminderSettings by viewModel.settings.collectAsStateWithLifecycle()
    var confirmPause by remember { mutableStateOf(false) }
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
    val batteryOptimized = permissionEpoch.let { !viewModel.isIgnoringBatteryOptimizations() }

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
                PermissionFollowUp.SCHEDULED_TEST -> runAction { viewModel.scheduleTest() }
            }
        }
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.isNotEmpty() && grants.values.all { it }) {
            runAction(viewModel::syncToCalendar)
        } else {
            scope.launch { snackbar.showSnackbar("需要日历权限才能把提醒写入系统日历") }
        }
    }

    fun syncToCalendar() {
        val missing = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            .filter {
                androidx.core.content.ContextCompat.checkSelfPermission(context, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        if (missing.isEmpty()) {
            runAction(viewModel::syncToCalendar)
        } else {
            calendarPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    fun requireNotificationPermission(followUp: PermissionFollowUp) {
        permissionFollowUp = followUp
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!AnimeReminderNotifier.canPostNotifications(context)) {
            context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, AnimeReminderNotifier.CHANNEL_ID))
        } else {
            when (followUp) {
                PermissionFollowUp.SYNC -> runAction(viewModel::synchronize)
                PermissionFollowUp.TEST -> runAction { viewModel.sendTestNotification() }
                PermissionFollowUp.SCHEDULED_TEST -> runAction { viewModel.scheduleTest() }
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
                        Icon(AppIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { permissionEpoch += 1 }, enabled = !busy) {
                        Icon(AppIcons.Refresh, contentDescription = "刷新系统状态")
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
                    batteryOptimized = batteryOptimized,
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
                    onOpenBatteryOptimization = {
                        // 尝试跳转到电池优化白名单设置
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                        runCatching { context.startActivity(intent) }
                            .onFailure {
                                // 如果不支持直接跳转，则打开应用详情页
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                    },
                    onSynchronize = { runAction(viewModel::synchronize) },
                    onSyncCalendar = { syncToCalendar() },
                    onTest = { requireNotificationPermission(PermissionFollowUp.TEST) },
                    onScheduledTest = { requireNotificationPermission(PermissionFollowUp.SCHEDULED_TEST) },
                    diagnostics = permissionEpoch.let { viewModel.diagnostics() },
                    onSystemSettings = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    },
                )
            }

            item {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("暂停全部提醒", fontWeight = FontWeight.SemiBold)
                                Text("保留每部作品原有的星期和时间", style = MaterialTheme.typography.bodySmall)
                            }
                            androidx.compose.material3.Switch(reminderSettings.paused,
                                onCheckedChange = { paused -> if (paused) confirmPause = true else runAction { viewModel.updateSettings(paused = false) } }, enabled = !busy)
                        }
                        Text("提前通知 · ${reminderSettings.leadMinutes} 分钟", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0, 5, 15, 30, 60, 120).forEach { minutes ->
                                androidx.compose.material3.FilterChip(reminderSettings.leadMinutes == minutes,
                                    { runAction { viewModel.updateSettings(leadMinutes = minutes) } }, enabled = !busy,
                                    label = { Text(if (minutes == 0) "准时" else "$minutes 分钟") })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("看完或弃番时自动停用", fontWeight = FontWeight.SemiBold)
                                Text("将作品标记为看完/弃番时，自动停止其后台提醒", style = MaterialTheme.typography.bodySmall)
                            }
                            androidx.compose.material3.Switch(
                                checked = reminderSettings.autoStopWhenCompleted,
                                onCheckedChange = { autoStop -> runAction { viewModel.updateSettings(autoStopWhenCompleted = autoStop) } },
                                enabled = !busy,
                            )
                        }
                        Text("仅影响此设备的通知时间，不会修改作品排期。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
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
                        paused = reminderSettings.paused,
                        leadMinutes = reminderSettings.leadMinutes,
                        limit = viewModel.getReminderLimit(anime.id),
                        onEdit = { onEditReminder(anime.id) },
                        onCancel = { cancelTarget = anime },
                    )
                }
            }
        }
    }

    if (confirmPause) AlertDialog(onDismissRequest = { confirmPause = false }, title = { Text("暂停所有追番提醒？") },
        text = { Text("暂停期间不会发送追番通知。星期和时间将被保留，可以随时恢复。") },
        confirmButton = { TextButton(onClick = { confirmPause = false; runAction { viewModel.updateSettings(paused = true) } }) { Text("暂停提醒") } },
        dismissButton = { TextButton(onClick = { confirmPause = false }) { Text("返回") } })
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
internal fun ReminderServiceCard(
    configuredCount: Int,
    notificationsGranted: Boolean,
    exactAlarmsEnabled: Boolean,
    batteryOptimized: Boolean,
    busy: Boolean,
    onRequestPermission: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenBatteryOptimization: () -> Unit,
    onSynchronize: () -> Unit,
    onSyncCalendar: () -> Unit,
    onTest: () -> Unit,
    onScheduledTest: () -> Unit,
    diagnostics: String,
    onSystemSettings: () -> Unit,
) {
    val ready = notificationsGranted
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    val accent = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val hasIssue = !ready || !exactAlarmsEnabled || batteryOptimized
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
                        if (ready) AppIcons.Notification else AppIcons.NotificationOff,
                        contentDescription = null,
                        tint = accent,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (ready && exactAlarmsEnabled && !batteryOptimized) "追番提醒已就绪"
                        else if (!ready) "开启通知，不错过更新"
                        else if (batteryOptimized) "电池优化可能影响提醒送达"
                        else "建议检查提醒设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when {
                            !ready -> "需要通知权限才能显示追番提醒"
                            !exactAlarmsEnabled -> "省电模式下可能稍晚送达，建议开启准时提醒"
                            batteryOptimized -> "系统电池优化可能延迟提醒，建议加入白名单"
                            else -> "将在你设定的时间提醒，状态栏会显示闹钟图标"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReminderInfoPill(AppIcons.Clock, "${ZoneId.systemDefault().id}")
                ReminderInfoPill(AppIcons.Alarm, "$configuredCount 条")
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
                        Icon(AppIcons.Unlock, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("授予权限")
                    }
                }
                if (!exactAlarmsEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    OutlinedButton(onClick = onOpenExactAlarmSettings, enabled = !busy) {
                        Text("开启准时提醒")
                    }
                }
                if (batteryOptimized) {
                    OutlinedButton(onClick = onOpenBatteryOptimization, enabled = !busy) {
                        Text("加入电池白名单")
                    }
                }
                OutlinedButton(onClick = onSyncCalendar, enabled = !busy) {
                    Icon(AppIcons.Calendar, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("同步到日历")
                }
            }
            TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                Text(if (showDiagnostics) "收起提醒检查" else "提醒没有按时到达？")
                Spacer(Modifier.width(6.dp))
                Icon(if (showDiagnostics) AppIcons.Up else AppIcons.Down, null, Modifier.size(18.dp))
            }
            if (showDiagnostics) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedButton(onClick = onSynchronize, enabled = !busy) {
                        Icon(AppIcons.Sync, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("同步全部")
                    }
                    TextButton(onClick = onTest, enabled = !busy) { Text("发送测试通知") }
                    TextButton(onClick = onScheduledTest, enabled = !busy) { Text("1 分钟后提醒") }
                    TextButton(onClick = onSystemSettings) { Text("应用与电池设置") }
                }
                Text(diagnostics, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "可先返回桌面进行定时测试。若系统限制后台活动，请在电池设置中允许后台运行；强行停止应用后需重新打开。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 引导卡片不再只藏在诊断区里：默认就展示，用户不必先点开"提醒没有按时到达？"。
            RomSpecificGuide()
        }
    }
}

@Composable
private fun ReminderInfoPill(icon: com.animeow.app.ui.icons.IconGlyph, text: String) {
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
internal fun ReminderAnimeCard(
    anime: AnimeEntity,
    paused: Boolean,
    leadMinutes: Int,
    limit: Pair<Int?, String?> = Pair(null, null),
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
                val limitSuffix = when {
                    limit.first == 0 -> " · 已完成设定提醒"
                    limit.first != null -> " · 剩余 ${limit.first} 次"
                    !limit.second.isNullOrBlank() && isReminderDateExpired(limit.second) -> " · 已于 ${limit.second} 到期"
                    !limit.second.isNullOrBlank() -> " · 截至 ${limit.second}"
                    else -> ""
                }
                Text(
                    "${weekdayLabel(anime.reminderDay)} ${anime.reminderTime.orEmpty()}$limitSuffix",
                    color = MaterialTheme.colorScheme.primary,
                )
                (if (paused) "提醒已暂停" else nextOccurrenceLabel(anime, leadMinutes))?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Clock, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            IconButton(onClick = onCancel) { Icon(AppIcons.NotificationOff, contentDescription = "取消《${anime.title}》的提醒") }
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
            Icon(AppIcons.NotificationOff, contentDescription = null, modifier = Modifier.size(42.dp))
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

/** 截止日期是否已经过去：过期后提醒会停止，但用户设置的日期要留在界面上说明原因。 */
private fun isReminderDateExpired(untilDate: String?): Boolean {
    val date = untilDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
    return date.isBefore(LocalDate.now())
}

private fun nextOccurrenceLabel(anime: AnimeEntity, leadMinutes: Int): String? {
    val day = anime.reminderDay ?: return null
    val time = anime.reminderTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return null
    val now = LocalDateTime.now(ZoneId.systemDefault())
    val next = now.plus(nextReminderDelay(day, time, now, leadMinutes))
    return "下次 ${next.format(DateTimeFormatter.ofPattern("MM-dd E HH:mm", Locale.CHINA))}"
}

@Composable
private fun RomSpecificGuide() {
    val context = LocalContext.current
    val rom = com.animeow.app.util.RomDetector.brand
    val guideText = com.animeow.app.util.RomDetector.getSettingGuide()
    val settingTargets = com.animeow.app.util.RomDetector.getBatterySettingIntents()

    if (!com.animeow.app.util.RomDetector.hasBackgroundGuide) return

    val motion = com.animeow.app.ui.theme.LocalMotionLevel.current
    val guideReveal = remember(motion) {
        androidx.compose.animation.core.MutableTransitionState(
            motion == com.animeow.app.ui.theme.MotionLevel.NONE,
        ).apply { targetState = true }
    }
    androidx.compose.animation.AnimatedVisibility(
        visibleState = guideReveal,
        enter = com.animeow.app.ui.components.motionExpandIn(220),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        AppIcons.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${rom.name.lowercase().replaceFirstChar { it.uppercase() }} 手机额外设置",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                Text(
                    guideText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                if (settingTargets.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        settingTargets.forEach { target ->
                            TextButton(
                                onClick = { openRomSetting(context, target) },
                                modifier = Modifier.height(28.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text(target.label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Text(
                        "部分机型会随系统更新更换设置页；打不开时会退到应用信息页，可按上面的说明手动设置。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }
    }
}

/**
 * 依次尝试显式组件、厂商包限定的 action、裸 action，最后打开本应用信息页。
 *
 * 旧实现用 `Intent(action).setPackage(context.packageName)` 启动厂商设置页，
 * 等于把目标限定在本应用包内，第一次尝试必然失败；而列表里又混着 Activity 全名，
 * 当 action 用也永远匹配不到，所以按钮实际上只会打开"应用信息"页。
 */
private fun openRomSetting(context: Context, target: com.animeow.app.util.RomSettingTarget): Boolean {
    val attempts = buildList {
        target.component?.let { (packageName, className) -> add(Intent().setClassName(packageName, className)) }
        target.action?.let { action ->
            add(if (target.actionPackage != null) Intent(action).setPackage(target.actionPackage) else Intent(action))
        }
    }
    for (intent in attempts) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return true
    }
    return runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess
}
