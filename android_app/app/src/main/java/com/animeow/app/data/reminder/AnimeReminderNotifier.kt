package com.animeow.app.data.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.animeow.app.MainActivity
import com.animeow.app.R
import com.animeow.app.data.local.AnimeEntity

object AnimeReminderNotifier {
    fun show(context: Context, anime: AnimeEntity): Boolean {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        createChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_ANIME
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ANIME_ID, anime.id)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            anime.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("该追番啦 · ${anime.title}")
            .setContentText(
                if (anime.totalEpisodes > 0) {
                    "当前 ${anime.watchedEpisodes}/${anime.totalEpisodes} 集，点此打开详情"
                } else {
                    "当前已看 ${anime.watchedEpisodes} 集，点此打开详情"
                },
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        return notifySafely(context, anime.id.toInt(), notification)
    }

    fun showTest(context: Context): Boolean {
        if (!canPostNotifications(context)) return false
        createChannel(context)
        val pendingIntent = PendingIntent.getActivity(
            context,
            TEST_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AniMeow 提醒测试")
            .setContentText("通知服务工作正常，之后会按每部作品的更新时间提醒你。")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        return notifySafely(context, TEST_NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context, animeId: Long) {
        NotificationManagerCompat.from(context).cancel(animeId.toInt())
    }

    fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun notifySafely(
        context: Context,
        notificationId: Int,
        notification: android.app.Notification,
    ): Boolean {
        if (!canPostNotifications(context)) return false
        return try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            true
        } catch (_: SecurityException) {
            // Permission can be revoked between the check and the framework call.
            false
        }
    }

    private fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "追番更新提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "按作品设置的每周更新日提醒观看"
            },
        )
    }

    const val EXTRA_ANIME_ID = "anime_id"
    const val ACTION_OPEN_ANIME = "com.animeow.app.action.OPEN_ANIME"
    private const val CHANNEL_ID = "anime_updates"
    private const val TEST_NOTIFICATION_ID = 0x4D454F57
}
