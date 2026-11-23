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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import com.animeow.app.data.backup.NativeBackupService
import com.animeow.app.data.diagnostics.OperationLogService
import com.animeow.app.data.diagnostics.CrashRecoveryHandler
import com.animeow.app.data.cloud.CloudSyncScheduler
import com.animeow.app.data.branding.LauncherIconManager
import com.animeow.app.data.preferences.BrandingPreferences
import com.animeow.app.data.preferences.AppearancePreferences
import com.animeow.app.util.runCatchingCancellable
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

    val bangumiSyncQueue: com.animeow.app.data.bangumi.BangumiSyncQueue by lazy {
        com.animeow.app.data.bangumi.BangumiSyncQueue(database, this)
    }

    val libraryRepository: LibraryRepository by lazy {
        LibraryRepository(database, operationLog, bangumiSyncQueue) { remote ->
            com.animeow.app.data.remote.CatalogContributionWorker.enqueue(this, remote)
        }
    }

    val characterRepository: CharacterRepository by lazy {
        CharacterRepository(database, this)
    }

    val announcementRepository: com.animeow.app.data.announcements.AnnouncementRepository by lazy {
        com.animeow.app.data.announcements.AnnouncementRepository(this)
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
            add(com.animeow.app.data.media.AnimeowCoverMapper(this@AniMeowApplication))
            add(com.animeow.app.data.community.CachedOwnAvatarInterceptor(this@AniMeowApplication))
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
        com.animeow.app.data.announcements.AnnouncementNotifications.schedule(this, announcementRepository.state.value.notificationsEnabled)
        applicationScope.launch {
            val community = com.animeow.app.data.community.CommunityService(this@AniMeowApplication)
            community.loadSession()?.let { community.prefetchCachedAvatar(it) }
        }
        applicationScope.launch {
            libraryRepository.observeAnimes().collectLatest {
                try { com.animeow.app.data.widget.TrackerWidget.updateAll(this@AniMeowApplication) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* A launcher refresh must not interrupt library work. */ }
            }
        }
        startCommunityStatisticsAutoSync()
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
                com.animeow.app.data.community.CommunityReviewNotifications.reschedule(this@AniMeowApplication)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                operationLog.recordError(error, "应用启动 · 云同步调度")
            }
            try {
                // 有待上报的 Bangumi 改动时启动即补排一次，并保持低频定期排空
                com.animeow.app.data.bangumi.BangumiSyncScheduler.onAppStart(this@AniMeowApplication)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                operationLog.recordError(error, "应用启动 · Bangumi 同步调度")
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

    @OptIn(FlowPreview::class)
    private fun startCommunityStatisticsAutoSync() {
        val community = com.animeow.app.data.community.CommunityService(this)
        if (!community.isConfigured) return
        val tracker = com.animeow.app.data.community.CommunityStatisticsAutoSyncTracker()
        val libraryStatistics = combine(
            combine(
                libraryRepository.observeAnimes(),
                libraryRepository.observeEpisodeDetailsWithDuration(),
            ) { animes, details -> animes to details },
            libraryRepository.observeTags(),
            libraryRepository.observeAnimeTags(),
            libraryRepository.observeWatchStatuses(),
            libraryRepository.observeWatchRecords(),
        ) { library, tags, links, statuses, records ->
            withContext(Dispatchers.Default) {
                com.animeow.app.ui.statistics.calculateStatistics(
                    animes = library.first,
                    tags = tags,
                    links = links,
                    records = records,
                    statuses = statuses,
                    today = LocalDate.now(),
                    episodeDetails = library.second,
                )
            }
        }
        applicationScope.launch {
            combine(
                libraryStatistics,
                com.animeow.app.data.cloud.CloudSessionSignals.sessionVersion,
            ) { statistics, _ -> statistics }
                .debounce(2_500L)
                .collectLatest { statistics ->
                    val session = community.loadSession()
                    if (session == null) {
                        tracker.reset()
                        return@collectLatest
                    }
                    try {
                        val profile = community.cachedProfile(session)
                            ?: community.bootstrap(session).currentUser
                            ?: return@collectLatest
                        if (profile.userId != session.userId) return@collectLatest
                        val selectedKeys = com.animeow.app.data.community.resolvedCommunityStatisticKeys(profile)
                        val payload = com.animeow.app.data.community.normalizeCommunityStatisticPayload(
                            statistics = statistics,
                            selectedKeys = selectedKeys,
                        )
                        if (!tracker.needsSync(session, profile, payload)) return@collectLatest
                        val updated = community.syncProfileStatistics(session, payload)
                        if (community.loadSession()?.token == session.token) {
                            tracker.markSynced(session, payload)
                            com.animeow.app.data.community.resolvedCommunityStatisticKeys(updated)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Quietly defer until the next library or session change when offline or rate-limited.
                    }
                }
        }
    }
}
