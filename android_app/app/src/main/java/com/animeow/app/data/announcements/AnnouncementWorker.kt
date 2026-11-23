package com.animeow.app.data.announcements

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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.animeow.app.AniMeowApplication
import com.animeow.app.MainActivity
import com.animeow.app.R
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class AnnouncementWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val repository = (applicationContext as AniMeowApplication).announcementRepository
        if (repository.state.value.notificationsEnabled) repository.refresh()
        Result.success()
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.success() }
}

object AnnouncementNotifications {
    const val ACTION_OPEN = "com.animeow.app.OPEN_ANNOUNCEMENTS"
    private const val WORK = "app_announcement_notifications"
    private const val CHANNEL = "app_announcements_v1"
    private const val ID = 2_130_001

    fun schedule(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!enabled || !AnnouncementService(context).configured) { work.cancelUniqueWork(WORK); return }
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AnnouncementWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(15, TimeUnit.MINUTES).build())
    }

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    fun cancel(context: Context) { NotificationManagerCompat.from(context).cancel(ID) }

    internal fun show(context: Context, items: List<AppAnnouncement>): Boolean {
        if (!canNotify(context) || items.isEmpty()) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "开发者消息与公告", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "AniMeow 开发者发布的版本通知和消息"
        })
        val pending = PendingIntent.getActivity(context, ID,
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val latest = items.maxBy { it.id }
        return try {
            NotificationManagerCompat.from(context).notify(ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(latest.title)
                .setContentText(if (items.size > 1) "${items.size} 条新的开发者消息与公告" else latest.content.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(latest.content.take(600)))
                .setContentIntent(pending).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_SOCIAL).build())
            true
        } catch (_: SecurityException) { false }
    }
}
