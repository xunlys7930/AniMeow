package com.animeow.app.data.bangumi

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.cloud.CloudSyncPreferences
import com.animeow.app.data.cloud.CloudSyncSettings
import java.util.concurrent.TimeUnit

/**
 * 后台排空 [BangumiSyncQueue]，实际逻辑在 [BangumiSyncRunner]（与「立即同步」共用）。
 *
 * 只有临时故障（网络、服务故障、限流）才请求 WorkManager 退避重试；授权过期、未登录、
 * 服务器暂未开通中转时直接结束，改动留在队列里，等应用启动、定期任务或下一次改动时再排空。
 */
class BangumiSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? AniMeowApplication ?: return Result.failure()
        var gameRetryable = false
        try {
            com.animeow.app.data.games.GameRepository(application, application.database).drain()
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            gameRetryable = error is java.io.IOException || error is BangumiRelayException && (error.httpStatus >= 500 || error.httpStatus == 429)
            // Queued games survive failure; animation reporting still proceeds.
        }
        val runner = BangumiSyncRunner(application)
        runner.drain(manual = false)
        return if ((gameRetryable || runner.lastRetryable) && runAttemptCount < MAX_RUNS) Result.retry() else Result.success()
    }

    private companion object {
        /** 一次排空调度的 WorkManager 重试上限，超出后留待定期任务 / 下次入队。改动不会因此丢弃。 */
        const val MAX_RUNS = 3
    }
}

object BangumiSyncScheduler {
    private const val UNIQUE_WORK_NAME = "animeow_bangumi_sync"
    private const val PERIODIC_WORK_NAME = "animeow_bangumi_sync_periodic"
    private const val DEBOUNCE_SECONDS = 3L
    private const val PERIODIC_HOURS = 6L

    /** 3 秒防抖排空队列（同一时刻多次入队合并为一次上报）。 */
    suspend fun schedule(context: Context) {
        val settings = CloudSyncPreferences(context).snapshot()
        if (!settings.bangumiProgressSync && !settings.bangumiRatingSync) return
        schedule(context, settings.bangumiSyncWifiOnly)
    }

    fun schedule(context: Context, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<BangumiSyncWorker>()
            .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setConstraints(networkConstraints(wifiOnly))
            .addTag(UNIQUE_WORK_NAME)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** 开关变化后调用：全部关闭则撤下排空与定期任务，任一开启则补一次并保持低频定期排空。 */
    suspend fun apply(context: Context, settings: CloudSyncSettings) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!settings.bangumiProgressSync && !settings.bangumiRatingSync) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }
        schedule(context, settings.bangumiSyncWifiOnly)
        schedulePeriodic(context, settings.bangumiSyncWifiOnly)
    }

    /**
     * 应用启动时调用：同步开启就保持定期排空；有待上报改动就立刻补一次。
     * 失败只影响后台上报时机，调用方自行记录，不影响启动。
     */
    suspend fun onAppStart(application: AniMeowApplication) {
        val settings = CloudSyncPreferences(application).snapshot()
        if (!settings.bangumiProgressSync && !settings.bangumiRatingSync) {
            WorkManager.getInstance(application).cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }
        schedulePeriodic(application, settings.bangumiSyncWifiOnly)
        if (application.database.libraryDao().bangumiPendingSyncCount() > 0 || application.database.gameDao().pendingCount() > 0) {
            schedule(application, settings.bangumiSyncWifiOnly)
        }
    }

    private fun schedulePeriodic(context: Context, wifiOnly: Boolean) {
        val request = PeriodicWorkRequestBuilder<BangumiSyncWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
            .setConstraints(networkConstraints(wifiOnly))
            .addTag(PERIODIC_WORK_NAME)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun networkConstraints(wifiOnly: Boolean): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()
}
