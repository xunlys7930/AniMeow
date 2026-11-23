package com.animeow.app.ui.overlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.animeow.app.data.overlay.ProgressOverlayService
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.icons.AppIcon as Icon

@Composable
fun rememberProgressOverlayLauncher(animeId: Long, onStarted: () -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val startedCallback by rememberUpdatedState(onStarted)
    var explain by remember { mutableStateOf(false) }
    fun start() {
        try {
            val result = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (resultCode == ProgressOverlayService.RESULT_STARTED) {
                        Toast.makeText(context, "已开启桌面悬浮打卡", Toast.LENGTH_SHORT).show()
                        startedCallback()
                    }
                }
            }
            ContextCompat.startForegroundService(context, ProgressOverlayService.intent(context, animeId, result))
        } catch (_: Exception) {
            Toast.makeText(context, "暂时无法开启悬浮记录，请返回应用后重试", Toast.LENGTH_LONG).show()
        }
    }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && ProgressOverlayService.canPostNotifications(context)) start()
        else Toast.makeText(context, "请开启系统通知及悬浮记录通知渠道后重试", Toast.LENGTH_LONG).show()
    }
    fun startWithNotification() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notification.launch(Manifest.permission.POST_NOTIFICATIONS)
        else if (!ProgressOverlayService.canPostNotifications(context)) {
            Toast.makeText(context, "请开启悬浮记录通知，方便随时关闭悬浮窗；开启后请返回重试", Toast.LENGTH_LONG).show()
            runCatching {
                context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, ProgressOverlayService.CHANNEL))
            }.onFailure { Toast.makeText(context, "请在系统设置中开启 AniMeow 的通知", Toast.LENGTH_LONG).show() }
        } else start()
    }
    val overlay = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(context)) startWithNotification()
        else Toast.makeText(context, "未开启悬浮窗权限，应用内记录仍可使用", Toast.LENGTH_LONG).show()
    }
    if (explain) AlertDialog(
        onDismissRequest = { explain = false },
        title = { Text("开启桌面悬浮打卡") },
        text = { Text("允许在其他应用上层显示后，可在屏幕侧边常驻悬浮按钮，支持快捷 +1 集、撤销和随时关闭。只用于你主动开启的作品，不读取其他应用内容，锁屏时自动隐藏。") },
        confirmButton = {
            TextButton(onClick = {
                explain = false
                runCatching { overlay.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())) }
                    .onFailure { Toast.makeText(context, "请在系统设置中允许悬浮窗后重试", Toast.LENGTH_LONG).show() }
            }) { Text("前往授权") }
        },
        dismissButton = { TextButton(onClick = { explain = false }) { Text("取消") } },
    )
    return {
        if (Settings.canDrawOverlays(context)) startWithNotification() else explain = true
    }
}

@Composable
fun ProgressOverlayEntry(animeId: Long, onStarted: () -> Unit = {}) {
    val launchOverlay = rememberProgressOverlayLauncher(animeId, onStarted)
    ListItem(
        headlineContent = { Text("开启桌面悬浮打卡") },
        supportingContent = { Text("在其他应用上层显示快捷 +1 与撤销按钮") },
        leadingContent = { Icon(AppIcons.Widgets, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = launchOverlay),
    )
}

@Composable
fun ProgressOverlayIconButton(
    animeId: Long,
    modifier: Modifier = Modifier,
    onStarted: () -> Unit = {},
) {
    val launchOverlay = rememberProgressOverlayLauncher(animeId, onStarted)
    IconButton(onClick = launchOverlay, modifier = modifier) {
        Icon(AppIcons.Widgets, contentDescription = "桌面悬浮打卡")
    }
}
