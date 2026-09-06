package com.animeow.app.data.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.animeow.app.AniMeowApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class CloudSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as? AniMeowApplication ?: return Result.failure()
        val preferences = CloudSyncPreferences(applicationContext)
        val settings = preferences.snapshot()
        if (!settings.autoSyncEnabled) return Result.success()
        if (application.isInForeground) return Result.retry()
        val service = CloudAccountService(
            context = applicationContext,
            nativeBackupService = application.nativeBackupService,
            legacyBackupImporter = application.legacyBackupImporter,
        )
        val session = service.loadSession() ?: return Result.success()
        return try {
            CloudSyncCoordinator(
                service = service,
                preferences = preferences,
                operationLog = application.operationLog,
                canApplyBackgroundRestore = { !application.isInForeground },
            )
                .synchronize(session = session, background = true)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: CloudSyncDeferredException) {
            Result.retry()
        } catch (error: Throwable) {
            application.operationLog.recordError(error, "后台云同步")
            when {
                error is CloudSessionExpiredException -> Result.success()
                runAttemptCount < MAX_RETRY_COUNT -> Result.retry()
                else -> Result.failure()
            }
        }
    }

    private companion object {
        const val MAX_RETRY_COUNT = 3
    }
}

object CloudSyncScheduler {
    private const val UNIQUE_WORK_NAME = "animeow_periodic_cloud_sync"

    suspend fun refresh(context: Context) {
        apply(context, CloudSyncPreferences(context).snapshot())
    }

    fun apply(context: Context, settings: CloudSyncSettings) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!settings.autoSyncEnabled) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(
            settings.intervalHours.toLong(),
            TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .addTag(UNIQUE_WORK_NAME)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
