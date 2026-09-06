package com.animeow.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.animeow.app.data.LibraryRepository
import com.animeow.app.data.CharacterRepository
import com.animeow.app.data.importer.LegacyBackupImporter
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.remote.DiscoveryRepository
import com.animeow.app.data.reminder.AnimeReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.animeow.app.data.backup.NativeBackupService
import com.animeow.app.data.diagnostics.OperationLogService
import com.animeow.app.data.diagnostics.CrashRecoveryHandler
import com.animeow.app.data.cloud.CloudSyncScheduler
import com.animeow.app.data.branding.LauncherIconManager
import com.animeow.app.data.preferences.BrandingPreferences
import com.animeow.app.data.media.PersistentRemoteImageInterceptor

class AniMeowApplication : Application(), ImageLoaderFactory {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile
    var isInForeground: Boolean = false
        private set
    private var startedActivityCount = 0
    val database: AniMeowDatabase by lazy {
        AniMeowDatabase.getInstance(this)
    }

    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(database, operationLog)
    }

    val characterRepository: CharacterRepository by lazy {
        CharacterRepository(database, this)
    }

    val legacyBackupImporter: LegacyBackupImporter by lazy {
        LegacyBackupImporter(this, database)
    }

    val discoveryRepository: DiscoveryRepository by lazy {
        DiscoveryRepository(this)
    }

    val reminderScheduler: AnimeReminderScheduler by lazy {
        AnimeReminderScheduler(this)
    }

    val nativeBackupService: NativeBackupService by lazy {
        NativeBackupService(this, database)
    }

    val operationLog: OperationLogService by lazy {
        OperationLogService(this, database)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(true)
        .components {
            add(PersistentRemoteImageInterceptor(this@AniMeowApplication))
        }
        .diskCache {
            DiskCache.Builder()
                .directory(applicationContext.cacheDir.resolve("image_cache"))
                .maxSizeBytes(512L * 1024 * 1024) // 512MB
                .build()
        }
        .memoryCache {
            MemoryCache.Builder(this@AniMeowApplication)
                .maxSizePercent(0.25) // 25% of available memory
                .build()
        }
        .build()

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    startedActivityCount += 1
                    isInForeground = startedActivityCount > 0
                }

                override fun onActivityStopped(activity: Activity) {
                    startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                    isInForeground = startedActivityCount > 0
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
        val crashRecovery = CrashRecoveryHandler.install(this)
        applicationScope.launch {
            try {
                crashRecovery.recoverInto(operationLog)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                operationLog.recordError(error, "应用启动 · 崩溃恢复")
            }
            try {
                reminderScheduler.syncAll(database)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                operationLog.recordError(error, "应用启动 · 提醒同步")
            }
            try {
                CloudSyncScheduler.refresh(this@AniMeowApplication)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                operationLog.recordError(error, "应用启动 · 云同步调度")
            }
            try {
                val branding = BrandingPreferences(this@AniMeowApplication).snapshot()
                LauncherIconManager(this@AniMeowApplication).reconcile(branding.launcherIcon)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                operationLog.recordError(error, "应用启动 · 启动图标同步")
            }
        }
    }
}
