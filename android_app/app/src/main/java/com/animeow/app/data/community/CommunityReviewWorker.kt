package com.animeow.app.data.community

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.animeow.app.MainActivity
import com.animeow.app.R
import com.animeow.app.data.cloud.CloudSessionExpiredException
import com.animeow.app.data.cloud.SecureCloudSessionStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CommunityReviewWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.activeNetwork
        if (network != null && connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            CommunityReviewNotifications.check(applicationContext)
        }
        Result.success()
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.success() }
}

object CommunityReviewNotifications {
    const val ACTION_OPEN_REVIEWS = "com.animeow.app.OPEN_COMMUNITY_REVIEWS"
    private const val WORK = "community_review_notifications"
    private const val CHANNEL = "community_reviews_v1"
    private const val NOTIFICATION_ID = 2_121_201
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun preferences(context: Context) = context.getSharedPreferences("community_review_notifications", Context.MODE_PRIVATE)

    fun enabled(context: Context, userId: Long): Boolean = preferences(context).getBoolean("enabled_$userId", true)

    fun cancelForSessionChange(context: Context) {
        preferences(context).edit().remove("moderator_id").apply()
        // Do not load the session here: this also runs while clearing an expired session.
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
            WorkManager.getInstance(context).cancelUniqueWork("${WORK}_now")
        }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID + 1)
    }

    fun updateRole(context: Context, userId: Long, moderator: Boolean) {
        if (SecureCloudSessionStore(context).load()?.userId != userId) return
        preferences(context).edit().putLong("moderator_id", if (moderator) userId else 0).apply()
        reschedule(context)
    }

    fun setEnabled(context: Context, userId: Long, enabled: Boolean) {
        preferences(context).edit().putBoolean("enabled_$userId", enabled).apply()
        reschedule(context)
        if (!enabled) NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun reschedule(context: Context) {
        val session = SecureCloudSessionStore(context).load()
        val work = WorkManager.getInstance(context)
        if (session == null || preferences(context).getLong("moderator_id", 0) != session.userId || !enabled(context, session.userId)) {
            work.cancelUniqueWork(WORK)
            work.cancelUniqueWork("${WORK}_now")
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            return
        }
        // Some networks can reach a self-hosted server while Android's internet probe fails.
        // Check actual connectivity in the worker; do not require Google's probe to validate.
        // Keep the periodic schedule stable across screen refreshes. An immediate and
        // periodic WorkRequest finishing together can invalidate the next platform job.
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CommunityReviewWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(15, TimeUnit.MINUTES).build())
        val appContext = context.applicationContext
        scope.launch {
            try { check(appContext) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* The periodic worker retries transient failures. */ }
        }
    }

    fun canNotify(context: Context): Boolean = (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
        Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    fun diagnostics(context: Context, userId: Long): String {
        val prefs = preferences(context)
        val at = prefs.getLong("checked_$userId", 0)
        val status = prefs.getString("status_$userId", "尚未执行检查")
        return if (at == 0L) status.orEmpty() else "$status · ${java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))}"
    }

    private fun record(context: Context, userId: Long, status: String) {
        preferences(context).edit().putLong("checked_$userId", System.currentTimeMillis()).putString("status_$userId", status).apply()
    }

    fun sendTest(context: Context, userId: Long): String {
        if (SecureCloudSessionStore(context).load()?.userId != userId || preferences(context).getLong("moderator_id", 0) != userId) return "当前账号没有审核权限"
        if (!canNotify(context)) return "通知权限或审核渠道已关闭，请检查系统通知设置"
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "社区审核待办", NotificationManager.IMPORTANCE_DEFAULT))
        val pending = PendingIntent.getActivity(context, NOTIFICATION_ID + 1,
            Intent(context, MainActivity::class.java).apply { action = ACTION_OPEN_REVIEWS; flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID + 1, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("审核通知测试")
                .setContentText("通知显示正常。后台检查约每 15 分钟执行，受系统省电影响。")
                .setContentIntent(pending).setAutoCancel(true).build())
            "测试已发送；不会改变待审记录或已读状态"
        } catch (_: SecurityException) { "通知权限已被关闭" }
    }

    suspend fun check(context: Context) = mutex.withLock {
        val service = CommunityService(context)
        val session = service.loadSession() ?: return@withLock
        val prefs = preferences(context)
        if (prefs.getLong("moderator_id", 0) != session.userId || !enabled(context, session.userId)) return@withLock
        val summary = try { service.reviewSummary(session) }
        catch (_: CommunitySafetyUnavailableException) { record(context, session.userId, "服务器尚不支持审核通知接口"); return@withLock }
        catch (_: CommunityManagementForbiddenException) { updateRole(context, session.userId, false); return@withLock }
        catch (_: CloudSessionExpiredException) { reschedule(context); return@withLock }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { record(context, session.userId, "连接失败，等待重试"); throw error }
        // A response for the previous account must not notify after an account switch.
        if (service.loadSession()?.token != session.token || !enabled(context, session.userId) ||
            prefs.getLong("moderator_id", 0) != session.userId) return@withLock
        record(context, session.userId, if (canNotify(context)) "检查成功：${summary.pendingCount} 项待审" else "检查成功，通知权限或渠道已关闭")
        if (summary.pendingCount == 0) { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID); return@withLock }
        if (!summary.needsNotification(prefs.getLong("notified_${session.userId}", 0)) || !canNotify(context)) return@withLock
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "社区审核待办", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "仅向管理员和开发者提醒新增待审图片与举报"
        })
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return@withLock
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_REVIEWS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(context, NOTIFICATION_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("社区有新的审核待办")
            .setContentText("${summary.imageCount} 项图片审核 · ${summary.reportCount} 项举报，点击处理")
            .setNumber(summary.pendingCount).setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(false).build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            prefs.edit().putLong("notified_${session.userId}", summary.latestEventId).apply()
        } catch (_: SecurityException) { /* Notification permission may be revoked between checks. */ }
    }

    suspend fun markSeen(context: Context, summary: CommunityReviewSummary, expectedToken: String) = mutex.withLock {
        val service = CommunityService(context)
        val session = service.loadSession()?.takeIf { it.token == expectedToken } ?: return@withLock
        service.markReviewsSeen(session, summary.latestEventId)
        if (service.loadSession()?.token != session.token) return@withLock
        val prefs = preferences(context)
        prefs.edit().putLong("notified_${session.userId}", maxOf(summary.latestEventId, prefs.getLong("notified_${session.userId}", 0))).apply()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
