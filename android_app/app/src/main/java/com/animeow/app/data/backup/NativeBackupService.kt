package com.animeow.app.data.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.playback.PlaybackHistory
import com.animeow.app.data.playback.playbackHistoryKey
import com.animeow.app.data.branding.BrandingAssetStore
import com.animeow.app.data.branding.BrandingSettings
import com.animeow.app.data.branding.LauncherIcon
import com.animeow.app.data.branding.SplashBackgroundMode
import com.animeow.app.data.branding.SplashScaleMode
import com.animeow.app.data.preferences.AppearancePreferences
import com.animeow.app.data.preferences.AppearanceSettingsCodec
import com.animeow.app.data.preferences.ConfigurationProfileRepository
import com.animeow.app.data.analysis.AnimeAnalysisPreferences
import com.animeow.app.data.analysis.AnimeAnalysisSettings
import com.animeow.app.data.cloud.CloudConflictPolicy
import com.animeow.app.data.cloud.CloudSyncConfiguration
import com.animeow.app.data.cloud.CloudSyncPreferences
import com.animeow.app.data.cloud.CloudSyncScheduler
import com.animeow.app.data.cloud.CloudSyncSettings
import com.animeow.app.data.preferences.DiscoveryNetworkSettings
import com.animeow.app.data.preferences.DiscoveryPreferences
import com.animeow.app.data.preferences.BangumiApiMode
import com.animeow.app.data.preferences.CalendarContentDensity
import com.animeow.app.data.preferences.CalendarDisplaySettings
import com.animeow.app.data.preferences.CalendarMarkerStyle
import com.animeow.app.data.preferences.CalendarModule
import com.animeow.app.data.preferences.CalendarPagePreset
import com.animeow.app.data.preferences.CalendarPreferences
import com.animeow.app.data.preferences.DEFAULT_CALENDAR_EVENT_TYPES
import com.animeow.app.data.preferences.MaintenancePreferences
import com.animeow.app.data.preferences.MaintenanceSettings
import com.animeow.app.data.maintenance.OrphanCoverCleaner
import com.animeow.app.data.preferences.BrandingPreferences
import com.animeow.app.data.preferences.TrackerPreferences
import com.animeow.app.data.preferences.TrackerSettingsCodec
import com.animeow.app.data.preferences.TrackerSettings
import com.animeow.app.data.preferences.AnimeEditorDensity
import com.animeow.app.data.preferences.AnimeEditorModule
import com.animeow.app.data.preferences.AnimeEditorPreferences
import com.animeow.app.data.preferences.AnimeEditorPreset
import com.animeow.app.data.preferences.AnimeEditorSettings
import com.animeow.app.data.preferences.EditorExitBehavior
import com.animeow.app.data.reminder.AnimeReminderScheduler
import com.animeow.app.ui.theme.AppStyle
import com.animeow.app.ui.theme.AppearanceSettings
import com.animeow.app.ui.theme.ContentDensity
import com.animeow.app.ui.theme.CharacterLayout
import com.animeow.app.ui.theme.DetailLayout
import com.animeow.app.ui.theme.DetailModule
import com.animeow.app.ui.theme.HomeLayout
import com.animeow.app.ui.theme.MotionLevel
import com.animeow.app.ui.theme.StatisticsModule
import com.animeow.app.ui.theme.SwipeAction
import com.animeow.app.ui.theme.ThemeMode
import com.animeow.app.ui.theme.TierListStyle
import com.animeow.app.ui.theme.parseNavigationOrder
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class NativeBackupSummary(
    val animeCount: Int,
    val tagCount: Int,
    val characterCount: Int,
    val groupCount: Int,
    val coverCount: Int,
    val createdAt: String,
    val dataFingerprint: String = "",
    val gameCount: Int = 0,
    val audioCount: Int = 0,
)

class NativeBackupFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

data class NativeRestorePoint(
    val file: File,
    val createdAt: String,
    val reason: String,
) {
    val sizeBytes: Long get() = file.length()
}

data class NativeRestoreSelection(
    val libraryAndCharacters: Boolean = true,
    val analysisHistory: Boolean = true,
    val appearanceSettings: Boolean = true,
) {
    val isEmpty: Boolean get() = !libraryAndCharacters && !analysisHistory && !appearanceSettings
}

enum class NativeRestoreStrategy {
    REPLACE,
    MERGE_KEEP_LOCAL,
}

class PreparedNativeRestore internal constructor(
    internal val stagingDirectory: File,
    internal val databaseFile: File,
    internal val settings: AppearanceSettings,
    internal val analysisSettings: AnimeAnalysisSettings,
    internal val discoverySettings: DiscoveryNetworkSettings,
    internal val calendarSettings: CalendarDisplaySettings,
    internal val maintenanceSettings: MaintenanceSettings,
    internal val brandingSettings: BrandingSettings,
    internal val trackerSettings: TrackerSettings,
    internal val editorSettings: AnimeEditorSettings,
    internal val cloudSyncConfiguration: CloudSyncConfiguration?,
    internal val configurationProfilesJson: String?,
    val summary: NativeBackupSummary,
    internal val fontsIncluded: Boolean = true,
    internal val collectionFilters: org.json.JSONArray? = null,
    internal val playbackHistory: JSONArray? = null,
    internal val hasMobileSettings: Boolean = true,
    internal val desktopStudioSettingsJson: String? = null,
) {
    internal var recoveryRequired: Boolean = false

    /**
     * 整库 JSON 树。只有 [NativeRestoreStrategy.MERGE_KEEP_LOCAL] 的合并算法需要在内存里
     * 同时持有两端数据，才惰性解析；REPLACE 走 [NativeBackupService.streamReplaceDatabase]
     * 的流式路径，不再为预检或替换恢复构建整棵树（大资料库的 OOM 墙在这里）。
     */
    internal val databaseJson: JSONObject by lazy {
        runCatching { JSONObject(databaseFile.readText()) }.getOrElse { error ->
            throw NativeBackupFormatException("备份资料库无法读取，文件可能已经损坏", error)
        }
    }
}

class NativeBackupService(
    context: Context,
    private val database: AniMeowDatabase,
) {
    private val appContext = context.applicationContext
    private val appearancePreferences = AppearancePreferences(appContext)
    private val animeAnalysisPreferences = AnimeAnalysisPreferences(appContext)
    private val discoveryPreferences = DiscoveryPreferences(appContext)
    private val calendarPreferences = CalendarPreferences(appContext)
    private val maintenancePreferences = MaintenancePreferences(appContext)
    private val brandingPreferences = BrandingPreferences(appContext)
    private val trackerPreferences = TrackerPreferences(appContext)
    private val collectionFilters = com.animeow.app.data.preferences.CollectionFilterPreferences(appContext)
    private val editorPreferences = AnimeEditorPreferences(appContext)
    private val cloudSyncPreferences = CloudSyncPreferences(appContext)
    private val configurationProfiles = ConfigurationProfileRepository(appContext, appearancePreferences)
    private val playbackHistory = PlaybackHistory(appContext)
    internal var restoreCheckpoint: suspend (NativeRestoreStage) -> Unit = {}

    suspend fun export(uri: Uri): NativeBackupSummary = withContext(Dispatchers.IO) {
        appContext.contentResolver.openOutputStream(uri, "w")?.use { rawOutput ->
            writeNativeBackup(rawOutput, includeFonts = true, includeCovers = true)
        } ?: error("无法写入所选备份位置")
    }

    suspend fun export(
        file: File,
        includeFonts: Boolean = true,
        includeCovers: Boolean = true,
        onlyReferencedCovers: Boolean = false,
    ): NativeBackupSummary = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        try {
            FileOutputStream(file).use { writeNativeBackup(it, includeFonts, includeCovers, onlyReferencedCovers) }
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    internal suspend fun summarizeCurrent(
        includeFonts: Boolean = true,
        includeCovers: Boolean = true,
        onlyReferencedCovers: Boolean = false,
    ): NativeBackupSummary = summarizeWithoutWriting(includeFonts, includeCovers, onlyReferencedCovers)

    suspend fun createRestorePoint(reason: String): NativeRestorePoint = withContext(Dispatchers.IO) {
        val directory = File(appContext.filesDir, RESTORE_POINTS_DIRECTORY).apply { mkdirs() }
        val cleanReason = reason.trim().ifBlank { "manual" }
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(32)
        val createdAt = Instant.now().toString()
        val file = File(directory, "${System.currentTimeMillis()}_${cleanReason}.animeow.zip")
        export(file)
        directory.listFiles { child -> child.isFile && child.extension.equals("zip", true) }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(MAX_RESTORE_POINTS)
            .forEach(File::delete)
        NativeRestorePoint(file = file, createdAt = createdAt, reason = reason)
    }

    fun listRestorePoints(): List<NativeRestorePoint> =
        File(appContext.filesDir, RESTORE_POINTS_DIRECTORY)
            .listFiles { child -> child.isFile && child.extension.equals("zip", true) }
            .orEmpty()
            .sortedByDescending(File::lastModified)
            .map { file ->
                val reason = file.nameWithoutExtension.substringAfter('_', "自动恢复点")
                    .replace('_', ' ')
                NativeRestorePoint(
                    file = file,
                    createdAt = Instant.ofEpochMilli(file.lastModified()).toString(),
                    reason = reason,
                )
            }

    suspend fun prepareRestore(file: File): PreparedNativeRestore {
        var completed: PreparedNativeRestore? = null
        return try {
            withContext(Dispatchers.IO) {
                FileInputStream(file).use { prepareRestoreFromStream(it) }
                    .also { completed = it }
            }
        } catch (error: Throwable) {
            completed?.let(::discard)
            throw error
        }
    }

    suspend fun prepareRestore(uri: Uri): PreparedNativeRestore {
        var completed: PreparedNativeRestore? = null
        return try {
            withContext(Dispatchers.IO) {
                (
                    appContext.contentResolver.openInputStream(uri)?.use { prepareRestoreFromStream(it) }
                        ?: error("无法读取所选备份")
                    ).also { completed = it }
            }
        } catch (error: Throwable) {
            completed?.let(::discard)
            throw error
        }
    }

    private fun prepareRestoreFromStream(rawInput: InputStream): PreparedNativeRestore {
        val staging = File(
            File(appContext.cacheDir, "native_restore"),
            UUID.randomUUID().toString(),
        )
        require(staging.mkdirs()) { "无法创建恢复临时目录" }
        return try {
            var totalBytes = 0L
            var entryCount = 0
            ZipInputStream(BufferedInputStream(rawInput)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount += 1
                    require(entryCount <= MAX_ENTRIES) { "备份条目过多" }
                    if (!entry.isDirectory) {
                        val target = safeChild(staging, entry.name)
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output ->
                            val copied = zip.copyToLimited(output, MAX_ENTRY_BYTES)
                            totalBytes += copied
                            require(totalBytes <= MAX_TOTAL_BYTES) { "备份总体积过大" }
                        }
                    }
                    zip.closeEntry()
                }
            }

            val manifestFile = File(staging, MANIFEST_ENTRY)
            if (!manifestFile.isFile) {
                throw NativeBackupFormatException("检测到旧版或不完整的备份格式")
            }
            val manifest = runCatching { JSONObject(manifestFile.readText()) }.getOrElse { error ->
                throw NativeBackupFormatException("备份清单无法读取，文件可能来自旧版本或已经损坏", error)
            }
            require(manifest.optString("type") == FORMAT_TYPE) { "不是 AniMeow 原生备份" }
            require(manifest.optInt("version") in 1..FORMAT_VERSION) { "备份版本暂不支持" }
            val databaseFile = File(staging, DATABASE_ENTRY)
            if (!databaseFile.isFile) {
                throw NativeBackupFormatException("备份缺少资料库内容，可能来自旧版本")
            }
            // 流式预检：校验 JSON 结构并统计各表行数，内存 O(1)，不再整树载入。
            val tableRowCounts = runCatching { countBackupTableRows(databaseFile) }.getOrElse { error ->
                throw NativeBackupFormatException("备份资料库无法读取，文件可能已经损坏", error)
            }
            val settingsFile = File(staging, SETTINGS_ENTRY)
            val settingsRoot = if (settingsFile.exists()) JSONObject(settingsFile.readText()) else JSONObject()
            val desktopStudioSettingsJson = settingsRoot.optJSONObject(DESKTOP_SETTINGS_KEY)?.toString()
            val hasMobileSettings = settingsFile.exists() &&
                settingsRoot.keys().asSequence().any { it != DESKTOP_SETTINGS_KEY && it != PLAYBACK_HISTORY_KEY }
            val appearanceJson = settingsRoot.optJSONObject("appearance") ?: settingsRoot
            val settings = if (settingsFile.exists()) {
                settingsFromJson(appearanceJson)
            } else {
                AppearanceSettings()
            }
            val analysisSettings = settingsRoot.optJSONObject("animeAnalysis")
                ?.let(::analysisSettingsFromJson)
                ?: AnimeAnalysisSettings()
            val discoverySettings = settingsRoot.optJSONObject("discovery")
                ?.let(::discoverySettingsFromJson)
                ?: DiscoveryNetworkSettings()
            val calendarSettings = settingsRoot.optJSONObject("calendar")
                ?.let(::calendarSettingsFromJson)
                ?: CalendarDisplaySettings()
            val maintenanceSettings = settingsRoot.optJSONObject("maintenance")
                ?.let(::maintenanceSettingsFromJson)
                ?: MaintenanceSettings()
            val brandingSettings = settingsRoot.optJSONObject("branding")
                ?.let(::brandingSettingsFromJson)
                ?.let { validatePreparedBranding(it, staging) }
                ?: BrandingSettings()
            val trackerSettings = settingsRoot.optJSONObject("tracker")
                ?.let(TrackerSettingsCodec::fromJson)
                ?: TrackerSettings()
            val editorSettings = settingsRoot.optJSONObject("animeEditor")
                ?.let(::animeEditorSettingsFromJson)
                ?: AnimeEditorSettings()
            val cloudSyncConfiguration = settingsRoot.optJSONObject("cloudSync")
                ?.let(::cloudSyncConfigurationFromJson)
            val configurationProfilesJson = File(staging, CONFIGURATION_PROFILES_ENTRY)
                .takeIf(File::isFile)
                ?.readText(Charsets.UTF_8)
                ?.let(ConfigurationProfileRepository::normalizeSnapshotJson)
            val covers = File(staging, "covers").takeIf(File::exists)
                ?.walkTopDown()?.count(File::isFile) ?: 0
            val coverFiles = File(staging, "covers").takeIf(File::exists)
                ?.walkTopDown()?.filter(File::isFile)?.toList().orEmpty()
            val coverRoot = File(staging, "covers")
            val brandingFileList = File(staging, BRANDING_DIRECTORY).takeIf(File::exists)
                ?.walkTopDown()?.filter(File::isFile)?.toList().orEmpty()
            val brandingRoot = File(staging, BRANDING_DIRECTORY)
            PreparedNativeRestore(
                stagingDirectory = staging,
                databaseFile = databaseFile,
                settings = settings,
                analysisSettings = analysisSettings,
                discoverySettings = discoverySettings,
                calendarSettings = calendarSettings,
                maintenanceSettings = maintenanceSettings,
                brandingSettings = brandingSettings,
                trackerSettings = trackerSettings,
                collectionFilters = settingsRoot.optJSONArray("collectionFilters")?.also { com.animeow.app.data.preferences.collectionFilterPlansFromJson(it) },
                playbackHistory = settingsRoot.optJSONArray(PLAYBACK_HISTORY_KEY),
                hasMobileSettings = hasMobileSettings,
                desktopStudioSettingsJson = desktopStudioSettingsJson,
                editorSettings = editorSettings,
                cloudSyncConfiguration = cloudSyncConfiguration,
                configurationProfilesJson = configurationProfilesJson,
                fontsIncluded = manifest.optBoolean("fontsIncluded", true),
                summary = NativeBackupSummary(
                    animeCount = tableRowCounts["animes"] ?: 0,
                    gameCount = tableRowCounts["games"] ?: 0,
                    audioCount = tableRowCounts["audio_works"] ?: 0,
                    tagCount = tableRowCounts["tags"] ?: 0,
                    characterCount = tableRowCounts["characters"] ?: 0,
                    groupCount = tableRowCounts["character_groups"] ?: 0,
                    coverCount = covers,
                    createdAt = manifest.optString("createdAt").ifBlank { "未知" },
                    dataFingerprint = manifest.optString("dataFingerprint").ifBlank {
                        fingerprintFromFiles(
                            databaseFile = databaseFile,
                            settingsJson = if (settingsFile.exists()) settingsFile.readText() else "{}",
                            configurationProfilesJson = configurationProfilesJson.orEmpty(),
                            covers = coverFiles,
                            coverRoot = coverRoot,
                            brandingFiles = brandingFileList,
                            brandingRoot = brandingRoot,
                        )
                    },
                ),
            )
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    suspend fun restore(prepared: PreparedNativeRestore): NativeBackupSummary =
        restore(prepared, NativeRestoreSelection())

    suspend fun restore(
        prepared: PreparedNativeRestore,
        selection: NativeRestoreSelection,
        strategy: NativeRestoreStrategy = NativeRestoreStrategy.REPLACE,
    ): NativeBackupSummary = withContext(Dispatchers.IO) {
        require(!selection.isEmpty) { "至少选择一类需要恢复的数据" }
        check(!prepared.recoveryRequired) { "上次恢复尚未完整撤回，请先核对本机资料，暂不能直接重试" }
        val destinationRoot = File(File(appContext.filesDir, COVERS_DIRECTORY), "restored_${UUID.randomUUID()}")
        val previousSettings = appearancePreferences.snapshot()
        val previousAnalysisSettings = animeAnalysisPreferences.snapshot()
        val previousDiscoverySettings = discoveryPreferences.snapshot()
        val previousCalendarSettings = calendarPreferences.snapshot()
        val previousMaintenanceSettings = maintenancePreferences.snapshot()
        val previousBrandingSettings = brandingPreferences.snapshot()
        val previousTrackerSettings = trackerPreferences.snapshot()
        val previousCollectionFilters = prepared.collectionFilters?.let { collectionFilters.snapshot() }
        val previousEditorSettings = editorPreferences.snapshot()
        val previousCloudSyncConfiguration = cloudSyncPreferences.configurationSnapshot()
        val previousConfigurationProfiles = prepared.configurationProfilesJson?.let { configurationProfiles.snapshotJson() }
        val brandingDestination = File(appContext.filesDir, BRANDING_DIRECTORY)
        val brandingRollback = File(prepared.stagingDirectory, "_branding_before_restore")
        val brandingSource = File(prepared.stagingDirectory, BRANDING_DIRECTORY)
        val rollback = NativeRestoreRollback()
        val callerJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        var committed = false
        var cloudSyncConfigurationRestored = false
        var restoredSummary = prepared.summary
        try {
            // Prepare files before touching Room or preferences; remember undo before the first destructive write.
            val sourceCovers = File(prepared.stagingDirectory, "covers")
            if (selection.libraryAndCharacters && sourceCovers.exists()) {
                rollback.beforeWrite("恢复封面") {
                    check(!destinationRoot.exists() || destinationRoot.deleteRecursively()) { "未能清理本次恢复的封面" }
                }
                copyDirectoryContents(sourceCovers, destinationRoot, NativeRestoreStage.COPYING_COVERS)
                restoreCheckpoint(NativeRestoreStage.COVERS_COPIED)
            }
            if (selection.appearanceSettings) {
                check(!brandingRollback.exists() || brandingRollback.deleteRecursively()) { "无法准备原品牌资源副本" }
                if (brandingDestination.exists()) copyDirectoryContents(brandingDestination, brandingRollback, NativeRestoreStage.COPYING_BRANDING_BACKUP)
                restoreCheckpoint(NativeRestoreStage.BRANDING_BACKED_UP)
                rollback.beforeWrite("品牌资源") {
                    restoreCheckpoint(NativeRestoreStage.BEFORE_BRANDING_ROLLBACK)
                    check(!brandingDestination.exists() || brandingDestination.deleteRecursively()) { "未能撤回品牌资源" }
                    if (brandingRollback.exists()) copyDirectoryContents(brandingRollback, brandingDestination, NativeRestoreStage.COPYING_BRANDING_ROLLBACK)
                }
                check(!brandingDestination.exists() || brandingDestination.deleteRecursively()) { "无法替换品牌资源" }
                restoreCheckpoint(NativeRestoreStage.BRANDING_CLEARED)
                if (brandingSource.exists()) copyDirectoryContents(brandingSource, brandingDestination, NativeRestoreStage.COPYING_BRANDING)
                // Cloud snapshots omit fonts; applying them must never erase fonts on this device.
                if (!prepared.fontsIncluded && File(brandingRollback, "fonts").exists())
                    copyDirectoryContents(File(brandingRollback, "fonts"), File(brandingDestination, "fonts"), NativeRestoreStage.COPYING_BRANDING)
                restoreCheckpoint(NativeRestoreStage.BRANDING_COPIED)
            }
            // Keep commit acknowledgement outside the caller's cancellation window. Work inside Room remains cancellable.
            withContext(NonCancellable) {
                database.withTransaction {
                    withContext(callerJob ?: kotlin.coroutines.EmptyCoroutineContext) {
                        val selectedTables = buildSet {
                            if (selection.libraryAndCharacters) {
                                addAll(CORE_BACKUP_TABLES)
                                // Older snapshots must not erase this device's game library or book reading states.
                                val incomingTables = countBackupTableRows(prepared.databaseFile)
                                if (!incomingTables.containsKey("games")) remove("games")
                                if (!incomingTables.containsKey("audio_works")) remove("audio_works")
                                if (!incomingTables.containsKey("book_reading_states")) remove("book_reading_states")
                                if (!incomingTables.containsKey("book_bookmarks")) remove("book_bookmarks")
                                if (!incomingTables.keys.containsAll(listOf("media_series", "media_series_members"))) {
                                    remove("media_series_members"); remove("media_series")
                                }
                            }
                            if (selection.analysisHistory) add(ANALYSIS_TABLE)
                        }
                        var mappedAnimeIds: Map<Long, Long> = emptyMap()
                        val preserveLocalPlayback = selection.libraryAndCharacters &&
                            strategy == NativeRestoreStrategy.MERGE_KEEP_LOCAL
                        val localPlayback = if (preserveLocalPlayback) capturePlaybackHistory() else null
                        if (selectedTables.isNotEmpty()) {
                            if (strategy == NativeRestoreStrategy.MERGE_KEEP_LOCAL) {
                                val merged = NativeBackupMerger.merge(
                                    // Existing local cover references must stay absolute. Only markers
                                    // originating in the remote archive may be rebased to destinationRoot.
                                    local = readAllTables(encodeCoverReferences = false),
                                    remote = prepared.databaseJson,
                                    includeCore = selection.libraryAndCharacters,
                                    includeAnalysis = selection.analysisHistory,
                                    onAnimeIdsMapped = { mappedAnimeIds = it },
                                ).also { merged ->
                                    restoredSummary = summaryFrom(
                                        json = merged,
                                        coverCount = File(appContext.filesDir, COVERS_DIRECTORY)
                                            .takeIf(File::exists)?.walkTopDown()?.count(File::isFile) ?: 0,
                                        createdAt = Instant.now().toString(),
                                        dataFingerprint = "",
                                    )
                                }
                                replaceDatabase(merged, destinationRoot, selectedTables)
                            } else {
                                streamReplaceDatabase(prepared.databaseFile, destinationRoot, selectedTables)
                            }
                        }
                        restoreCheckpoint(NativeRestoreStage.DATABASE_REPLACED)
                        prepared.desktopStudioSettingsJson?.let { incomingDesktop ->
                            val passthroughFile = desktopSettingsPassthroughFile()
                            val previousDesktop = passthroughFile.takeIf(File::isFile)?.readText(Charsets.UTF_8)
                            rollback.beforeWrite("桌面端设置中转") {
                                if (previousDesktop != null) {
                                    passthroughFile.parentFile?.mkdirs()
                                    passthroughFile.writeText(previousDesktop, Charsets.UTF_8)
                                } else {
                                    passthroughFile.delete()
                                }
                            }
                            passthroughFile.parentFile?.mkdirs()
                            passthroughFile.writeText(incomingDesktop, Charsets.UTF_8)
                        }
                        if (selection.appearanceSettings && prepared.hasMobileSettings) {
                            val restoredAppearance = if (prepared.settings.appFontChoice == com.animeow.app.ui.theme.AppFontChoice.CUSTOM &&
                                com.animeow.app.data.fonts.AppFontStore(appContext).load(prepared.settings.customFontId) == null
                            ) {
                                if (!prepared.fontsIncluded && previousSettings.appFontChoice == com.animeow.app.ui.theme.AppFontChoice.CUSTOM &&
                                    com.animeow.app.data.fonts.AppFontStore(appContext).load(previousSettings.customFontId) != null
                                ) prepared.settings.copy(appFontChoice = previousSettings.appFontChoice, customFontId = previousSettings.customFontId, customFontName = previousSettings.customFontName)
                                else prepared.settings.copy(appFontChoice = com.animeow.app.ui.theme.AppFontChoice.SYSTEM, customFontId = null, customFontName = "")
                            } else prepared.settings
                            rollback.beforeWrite("外观设置") { appearancePreferences.restore(previousSettings) }
                            appearancePreferences.restore(restoredAppearance)
                            restoreCheckpoint(NativeRestoreStage.APPEARANCE_WRITTEN)
                            rollback.beforeWrite("AI 分析设置") { animeAnalysisPreferences.save(previousAnalysisSettings) }
                            animeAnalysisPreferences.save(prepared.analysisSettings)
                            restoreCheckpoint(NativeRestoreStage.ANALYSIS_WRITTEN)
                            rollback.beforeWrite("数据源设置") { discoveryPreferences.save(previousDiscoverySettings) }
                            discoveryPreferences.save(prepared.discoverySettings)
                            restoreCheckpoint(NativeRestoreStage.DISCOVERY_WRITTEN)
                            rollback.beforeWrite("日历设置") { calendarPreferences.save(previousCalendarSettings) }
                            calendarPreferences.save(prepared.calendarSettings)
                            restoreCheckpoint(NativeRestoreStage.CALENDAR_WRITTEN)
                            rollback.beforeWrite("维护设置") { maintenancePreferences.save(previousMaintenanceSettings) }
                            maintenancePreferences.save(prepared.maintenanceSettings)
                            restoreCheckpoint(NativeRestoreStage.MAINTENANCE_WRITTEN)
                            rollback.beforeWrite("品牌设置") { brandingPreferences.save(previousBrandingSettings) }
                            brandingPreferences.save(prepared.brandingSettings)
                            restoreCheckpoint(NativeRestoreStage.BRANDING_WRITTEN)
                            rollback.beforeWrite("收藏显示设置") { trackerPreferences.save(previousTrackerSettings) }
                            trackerPreferences.save(prepared.trackerSettings)
                            restoreCheckpoint(NativeRestoreStage.TRACKER_WRITTEN)
                            prepared.collectionFilters?.let { filters ->
                                rollback.beforeWrite("筛选方案") { previousCollectionFilters?.let { collectionFilters.restore(it) } }
                                collectionFilters.restore(filters, merge = strategy == NativeRestoreStrategy.MERGE_KEEP_LOCAL)
                                restoreCheckpoint(NativeRestoreStage.FILTERS_WRITTEN)
                            }
                            rollback.beforeWrite("编辑器设置") { editorPreferences.save(previousEditorSettings) }
                            editorPreferences.save(prepared.editorSettings)
                            restoreCheckpoint(NativeRestoreStage.EDITOR_WRITTEN)
                            prepared.cloudSyncConfiguration?.let { configuration ->
                                rollback.beforeWrite("云同步配置") {
                                    cloudSyncPreferences.restoreConfiguration(previousCloudSyncConfiguration)
                                    runCatching { CloudSyncScheduler.apply(appContext, previousCloudSyncConfiguration.toRuntimeSettings()) }
                                        .onFailure { Log.w(TAG, "原云同步配置已撤回，计划重建未完成", it) }
                                }
                                cloudSyncPreferences.restoreConfiguration(configuration)
                                cloudSyncConfigurationRestored = true
                                restoreCheckpoint(NativeRestoreStage.CLOUD_WRITTEN)
                            }
                            prepared.configurationProfilesJson?.let { profiles ->
                                rollback.beforeWrite("配置档案") { previousConfigurationProfiles?.let { configurationProfiles.restoreSnapshotJson(it) } }
                                configurationProfiles.restoreSnapshotJson(profiles)
                                restoreCheckpoint(NativeRestoreStage.PROFILES_WRITTEN)
                            }
                        }
                        if (selection.libraryAndCharacters) {
                            if (localPlayback != null) restorePlaybackRows(localPlayback, rollback, verifyIdentity = false) { it }
                            prepared.playbackHistory?.let { history ->
                                restorePlaybackRows(history, rollback, verifyIdentity = strategy == NativeRestoreStrategy.REPLACE) { id ->
                                    if (strategy == NativeRestoreStrategy.MERGE_KEEP_LOCAL) mappedAnimeIds[id] else id
                                }
                            }
                            if (localPlayback != null || prepared.playbackHistory != null) {
                                restoreCheckpoint(NativeRestoreStage.PLAYBACK_HISTORY_WRITTEN)
                            }
                        }
                    }
                }
                committed = true
                rollback.commit()
                // After commit, failures are cleanup/rebuild failures. Never undo committed preferences or remove live covers.
                runCatching { restoreCheckpoint(NativeRestoreStage.DATABASE_COMMITTED) }
                    .onFailure { Log.w(TAG, "提交后的恢复检查未完成，资料已应用", it) }
                if (selection.libraryAndCharacters) {
                    runCatching { restoreCheckpoint(NativeRestoreStage.BEFORE_REMINDER_REBUILD); AnimeReminderScheduler(appContext).syncAll(database) }
                        .onFailure { Log.w(TAG, "提醒重建失败，将在下次资料变更时重试", it) }
                }
                if (cloudSyncConfigurationRestored) {
                    runCatching { restoreCheckpoint(NativeRestoreStage.BEFORE_CLOUD_REBUILD); CloudSyncScheduler.apply(appContext, prepared.cloudSyncConfiguration!!.toRuntimeSettings()) }
                        .onFailure { Log.w(TAG, "云同步计划重建失败，将在下次修改同步设置时重试", it) }
                }
                runCatching { restoreCheckpoint(NativeRestoreStage.DERIVED_TASKS_REBUILT) }
                    .onFailure { Log.w(TAG, "恢复后的任务检查未完成，资料已应用", it) }
                if (strategy == NativeRestoreStrategy.REPLACE && selection.libraryAndCharacters) {
                    runCatching { cleanupOldRestoredCoverDirectories(destinationRoot) }.onFailure { Log.w(TAG, "旧恢复封面目录清理失败", it) }
                    runCatching { OrphanCoverCleaner(appContext, database).cleanup() }.onFailure { Log.w(TAG, "孤儿封面清理失败", it) }
                }
                runCatching { restoreCheckpoint(NativeRestoreStage.BEFORE_STAGING_CLEANUP); check(prepared.stagingDirectory.deleteRecursively()) { "恢复临时目录未能清理" } }
                    .onFailure { Log.w(TAG, "资料恢复成功，临时文件清理未完成", it) }
            }
            restoredSummary
        } catch (error: Throwable) {
            if (committed) throw error
            val complete = rollback.rollback(error) { name, failure -> Log.e(TAG, "恢复失败后撤回${name}失败", failure) }
            if (!complete) {
                prepared.recoveryRequired = true
                throw NativeRestoreRollbackException(error)
            }
            throw error
        }
    }

    fun discard(prepared: PreparedNativeRestore) {
        if (prepared.recoveryRequired) return
        prepared.stagingDirectory.deleteRecursively()
    }

    private suspend fun restorePlaybackRows(
        history: JSONArray,
        rollback: NativeRestoreRollback,
        verifyIdentity: Boolean,
        mapId: (Long) -> Long?,
    ) {
        val remapped = (0 until minOf(history.length(), 500)).mapNotNull { index ->
            val row = history.optJSONObject(index) ?: return@mapNotNull null
            val incomingId = row.optLong("animeId").takeIf { it > 0 } ?: return@mapNotNull null
            val targetId = mapId(incomingId) ?: return@mapNotNull null
            Triple(incomingId, targetId, row.optString("identity"))
        }
        if (remapped.isEmpty()) return
        val animes = database.libraryDao().getAnimes(remapped.map { it.second }.distinct()).associateBy { it.id }
        val targets = remapped.mapNotNull { (incomingId, targetId, identity) ->
            val anime = animes[targetId] ?: return@mapNotNull null
            if (verifyIdentity && identity != playbackHistoryKey(anime)) return@mapNotNull null
            incomingId to anime
        }.toMap()
        playbackHistory.restoreRows(history, targets) { undo ->
            rollback.beforeWrite("播放历史") { undo() }
        }
    }

    private fun capturePlaybackHistory(): JSONArray = database.openHelper.writableDatabase.query(
        "SELECT id, title, createdAt, externalSource, externalId FROM animes WHERE subjectType = 'anime' AND deletedAt IS NULL ORDER BY id",
    ).use { cursor ->
        val animes = generateSequence {
            if (cursor.moveToNext()) AnimeEntity(
                id = cursor.getLong(0), title = cursor.getString(1), createdAt = cursor.getString(2),
                externalSource = cursor.getString(3), externalId = cursor.getString(4),
            ) else null
        }
        playbackHistory.backupRows(animes)
    }

    private fun cleanupOldRestoredCoverDirectories(activeDirectory: File) {
        val root = File(appContext.filesDir, COVERS_DIRECTORY)
        val referencedDirectories = OrphanCoverCleaner(appContext, database).collectReferencedRelativePaths(root)
            .map { it.substringBefore('/') }.toSet()
        root.listFiles()?.filter { candidate ->
            candidate.isDirectory && candidate != activeDirectory && candidate.name.startsWith("restored_") &&
                candidate.name !in referencedDirectories
        }?.forEach(File::deleteRecursively)
    }

    /** 备份里除资料库行数据以外的固定部分（设置快照文本、封面与品牌文件清单）。 */
    private data class BackupParts(
        val settingsJson: String,
        val configurationProfilesJson: String,
        val covers: List<File>,
        val coverRoot: File,
        val brandingFiles: List<File>,
        val brandingRoot: File,
    )

    private suspend fun gatherBackupParts(
        includeFonts: Boolean,
        includeCovers: Boolean,
    ): BackupParts {
        val settings = appearancePreferences.snapshot()
        val analysisSettings = animeAnalysisPreferences.snapshot()
        val discoverySettings = discoveryPreferences.snapshot()
        val calendarSettings = calendarPreferences.snapshot()
        val maintenanceSettings = maintenancePreferences.snapshot()
        val brandingSettings = brandingPreferences.snapshot()
        val trackerSettings = trackerPreferences.snapshot()
        val editorSettings = editorPreferences.snapshot()
        val cloudSyncConfiguration = cloudSyncPreferences.configurationSnapshot()
        val configurationProfilesJson = configurationProfiles.snapshotJson()
        val settingsJsonObj = JSONObject()
            .put("appearance", settingsToJson(settings))
            .put("animeAnalysis", analysisSettingsToJson(analysisSettings))
            .put("discovery", discoverySettingsToJson(discoverySettings))
            .put("calendar", calendarSettingsToJson(calendarSettings))
            .put("maintenance", maintenanceSettingsToJson(maintenanceSettings))
            .put("branding", brandingSettingsToJson(brandingSettings))
            .put("tracker", TrackerSettingsCodec.toJson(trackerSettings))
            .put("collectionFilters", collectionFilters.snapshot())
            .put("animeEditor", animeEditorSettingsToJson(editorSettings))
            .put("cloudSync", cloudSyncConfigurationToJson(cloudSyncConfiguration))
        desktopSettingsPassthroughFile()
            .takeIf(File::isFile)
            ?.let { file -> runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() }
            ?.let { desktopObj -> settingsJsonObj.put(DESKTOP_SETTINGS_KEY, desktopObj) }
        val settingsJson = settingsJsonObj.toString()
        val coverRoot = File(appContext.filesDir, COVERS_DIRECTORY)
        val covers = if (includeCovers) {
            coverRoot.takeIf(File::exists)
                ?.walkTopDown()
                ?.filter(File::isFile)
                ?.sortedBy { it.relativeTo(coverRoot).invariantSeparatorsPath }
                ?.toList()
                .orEmpty()
        } else {
            emptyList()
        }
        val brandingRoot = File(appContext.filesDir, BRANDING_DIRECTORY)
        val brandingFiles = brandingRoot.takeIf(File::exists)
            ?.walkTopDown()
            ?.filter(File::isFile)
            ?.filter { includeFonts || !it.relativeTo(brandingRoot).invariantSeparatorsPath.startsWith("fonts/") }
            ?.sortedBy { it.relativeTo(brandingRoot).invariantSeparatorsPath }
            ?.toList()
            .orEmpty()
        return BackupParts(
            settingsJson = settingsJson,
            configurationProfilesJson = configurationProfilesJson,
            covers = covers,
            coverRoot = coverRoot,
            brandingFiles = brandingFiles,
            brandingRoot = brandingRoot,
        )
    }

    private data class BackupSnapshot(
        val databaseFile: File,
        val parts: BackupParts,
        val summary: NativeBackupSummary,
    )

    // Only the database JSON is captured under Room's transaction. Slow output and ZIP work
    // use private copies, so edits and resource cleanup cannot change an archive in flight.
    private suspend fun <T> withBackupSnapshot(
        includeFonts: Boolean,
        includeCovers: Boolean,
        copyResources: Boolean = true,
        onlyReferencedCovers: Boolean = false,
        block: (BackupSnapshot) -> T,
    ): T {
        val staging = File(appContext.cacheDir, "native_export_${UUID.randomUUID()}")
        check(staging.mkdirs()) { "无法创建备份临时目录，请检查存储空间" }
        try {
            val parts = gatherBackupParts(includeFonts, includeCovers && !onlyReferencedCovers)
            val databaseFile = File(staging, DATABASE_ENTRY)
            val referencedCovers = linkedMapOf<String, File>()
            val (counts, history) = database.withTransaction {
                val counts = databaseFile.bufferedWriter(Charsets.UTF_8).use { writeDatabaseJson(it, referencedCovers) }
                counts to capturePlaybackHistory()
            }
            fun copyFiles(files: List<File>, sourceRoot: File, targetRoot: File): List<File> = files.map { source ->
                val target = safeChild(targetRoot, source.relativeTo(sourceRoot).invariantSeparatorsPath)
                target.parentFile?.mkdirs()
                source.copyTo(target)
            }
            val coverRoot = File(staging, COVERS_DIRECTORY)
            val brandingRoot = File(staging, BRANDING_DIRECTORY)
            val coverEntries = if (includeCovers) {
                val byRelative = linkedMapOf<String, File>()
                referencedCovers.forEach { (rel, file) ->
                    if (file.isFile) byRelative[rel] = file
                }
                if (!onlyReferencedCovers) {
                    parts.covers.forEach { file ->
                        if (file.isFile) {
                            val rel = com.animeow.app.data.media.stripRestoredCoverPrefixes(
                                file.relativeTo(parts.coverRoot).invariantSeparatorsPath,
                            )
                            byRelative.putIfAbsent(rel, file)
                        }
                    }
                }
                byRelative.entries.sortedBy { it.key }.map { it.key to it.value }
            } else {
                emptyList()
            }
            val captured = if (history.length() == 0) parts else parts.copy(
                settingsJson = JSONObject(parts.settingsJson).put(PLAYBACK_HISTORY_KEY, history).toString(),
            )
            val copied = if (copyResources) {
                val copiedCovers = coverEntries.map { (rel, source) ->
                    val target = safeChild(coverRoot, rel)
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = true)
                }
                captured.copy(
                    covers = copiedCovers, coverRoot = coverRoot,
                    brandingFiles = copyFiles(parts.brandingFiles, parts.brandingRoot, brandingRoot), brandingRoot = brandingRoot,
                )
            } else {
                captured.copy(covers = coverEntries.map { it.second })
            }
            val fingerprintCoverEntries = if (copyResources) {
                copied.covers.map { it.relativeTo(copied.coverRoot).invariantSeparatorsPath to it }
            } else {
                coverEntries
            }
            val fingerprint = fingerprintFromCoverEntries(databaseFile, copied.settingsJson, copied.configurationProfilesJson,
                fingerprintCoverEntries, copied.brandingFiles, copied.brandingRoot)
            return block(BackupSnapshot(databaseFile, copied, NativeBackupSummary(
                animeCount = counts["animes"] ?: 0, gameCount = counts["games"] ?: 0,
                audioCount = counts["audio_works"] ?: 0, tagCount = counts["tags"] ?: 0,
                characterCount = counts["characters"] ?: 0, groupCount = counts["character_groups"] ?: 0,
                coverCount = copied.covers.size, createdAt = Instant.now().toString(), dataFingerprint = fingerprint,
            )))
        } finally {
            staging.deleteRecursively()
        }
    }

    internal suspend fun writeNativeBackup(
        output: OutputStream,
        includeFonts: Boolean,
        includeCovers: Boolean,
        onlyReferencedCovers: Boolean = false,
    ): NativeBackupSummary = withContext(Dispatchers.IO) {
        withBackupSnapshot(includeFonts, includeCovers, onlyReferencedCovers = onlyReferencedCovers) { snapshot ->
            val parts = snapshot.parts
            val summary = snapshot.summary
            ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                zip.putNextEntry(ZipEntry(DATABASE_ENTRY))
                snapshot.databaseFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                zip.writeTextEntry(SETTINGS_ENTRY, parts.settingsJson)
                zip.writeTextEntry(CONFIGURATION_PROFILES_ENTRY, parts.configurationProfilesJson)
                parts.covers.forEach { file ->
                    zip.putNextEntry(ZipEntry("covers/${file.relativeTo(parts.coverRoot).invariantSeparatorsPath}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                parts.brandingFiles.forEach { file ->
                    zip.putNextEntry(ZipEntry("$BRANDING_DIRECTORY/${file.relativeTo(parts.brandingRoot).invariantSeparatorsPath}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.writeTextEntry(MANIFEST_ENTRY, JSONObject()
                    .put("type", FORMAT_TYPE).put("version", FORMAT_VERSION).put("createdAt", summary.createdAt)
                    .put("animeCount", summary.animeCount).put("gameCount", summary.gameCount)
                    .put("audioCount", summary.audioCount).put("coverCount", summary.coverCount)
                    .put("dataFingerprint", summary.dataFingerprint).put("fontsIncluded", includeFonts).toString())
            }
            summary
        }
    }

    private suspend fun summarizeWithoutWriting(
        includeFonts: Boolean,
        includeCovers: Boolean,
        onlyReferencedCovers: Boolean = false,
    ): NativeBackupSummary = withContext(Dispatchers.IO) {
        // A local fingerprint has no slow external output; hash resources without duplicating them.
        withBackupSnapshot(includeFonts, includeCovers, copyResources = false, onlyReferencedCovers = onlyReferencedCovers) { it.summary }
    }

    /** 按契约表顺序把全部行写成 database.json 文本，同时统计各表行数；不关闭 writer。 */
    private fun writeDatabaseJson(writer: Writer, referencedCovers: MutableMap<String, File>): Map<String, Int> {
        val sqlite = database.openHelper.writableDatabase
        val counts = mutableMapOf<String, Int>()
        writer.write("{")
        BACKUP_TABLES.forEachIndexed { tableIndex, table ->
            if (tableIndex > 0) writer.write(",")
            writeJsonString(writer, table)
            writer.write(":[")
            var rowCount = 0
            sqlite.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
                val columns = cursor.columnNames
                while (cursor.moveToNext()) {
                    if (rowCount > 0) writer.write(",")
                    writer.write("{")
                    columns.forEachIndexed { columnIndex, column ->
                        if (columnIndex > 0) writer.write(",")
                        writeJsonString(writer, column)
                        writer.write(":")
                        if (table == "book_reading_states" && column == "localFileUri") {
                            writer.write("null")
                        } else {
                            writeJsonValue(writer, cursor, columnIndex, referencedCovers)
                        }
                    }
                    writer.write("}")
                    rowCount++
                }
            }
            counts[table] = rowCount
            writer.write("]")
        }
        writer.write("}")
        return counts
    }

    private fun writeJsonValue(writer: Writer, cursor: Cursor, index: Int, referencedCovers: MutableMap<String, File>) {
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> writer.write("null")
            Cursor.FIELD_TYPE_INTEGER -> writer.write(cursor.getLong(index).toString())
            Cursor.FIELD_TYPE_FLOAT -> {
                val value = cursor.getDouble(index)
                if (value.isNaN() || value.isInfinite()) writer.write("null") else writer.write(value.toString())
            }
            Cursor.FIELD_TYPE_BLOB ->
                writeJsonString(writer, android.util.Base64.encodeToString(cursor.getBlob(index), android.util.Base64.NO_WRAP))
            else -> {
                val raw = cursor.getString(index)
                val mapping = resolveCoverExportMapping(raw)
                val value = if (mapping != null) {
                    if (mapping.second.isFile) referencedCovers.putIfAbsent(mapping.first, mapping.second)
                    COVER_MARKER + mapping.first
                } else {
                    encodeCoverReference(raw)
                }
                writeJsonString(writer, value)
            }
        }
    }

    private fun writeJsonString(writer: Writer, value: String) {
        writer.write(34)
        var start = 0
        for (index in value.indices) {
            val escape = escapeSequence(value[index]) ?: continue
            if (start < index) writer.write(value, start, index - start)
            writer.write(escape)
            start = index + 1
        }
        if (start < value.length) writer.write(value, start, value.length - start)
        writer.write(34)
    }

    private fun escapeSequence(ch: Char): String? = when (ch) {
        '"' -> "\\\""
        '\\' -> "\\\\"
        '\b' -> "\\b"
        '\u000C' -> "\\f"
        '\n' -> "\\n"
        '\r' -> "\\r"
        '\t' -> "\\t"
        else -> if (ch < ' ') "\\u%04x".format(ch.code) else null
    }

    /** 指纹的 settings/封面/品牌段；database 段由调用方先喂入 digest（与旧 fingerprint() 字节口径一致）。 */
    private fun finishFingerprint(
        digest: MessageDigest,
        settingsJson: String,
        configurationProfilesJson: String,
        covers: List<File>,
        coverRoot: File,
        brandingFiles: List<File>,
        brandingRoot: File,
    ): String {
        val coverEntries = covers.map { file ->
            val relative = runCatching { file.relativeTo(coverRoot).invariantSeparatorsPath }
                .getOrDefault(file.name)
            relative to file
        }
        return finishFingerprintEntries(digest, settingsJson, configurationProfilesJson, coverEntries, brandingFiles, brandingRoot)
    }

    private fun finishFingerprintEntries(
        digest: MessageDigest,
        settingsJson: String,
        configurationProfilesJson: String,
        coverEntries: List<Pair<String, File>>,
        brandingFiles: List<File>,
        brandingRoot: File,
    ): String {
        digest.update(0.toByte())
        digest.update(settingsJson.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(CONFIGURATION_PROFILES_ENTRY.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(configurationProfilesJson.toByteArray(Charsets.UTF_8))
        coverEntries.sortedBy { it.first }
            .forEach { (relative, file) ->
                digest.update(0.toByte())
                digest.update(relative.toByteArray(Charsets.UTF_8))
                file.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
        brandingFiles.sortedBy {
            runCatching { it.relativeTo(brandingRoot).invariantSeparatorsPath }.getOrDefault(it.name)
        }.forEach { file ->
            val relative = runCatching { file.relativeTo(brandingRoot).invariantSeparatorsPath }
                .getOrDefault(file.name)
            digest.update(0.toByte())
            digest.update("branding/$relative".toByteArray(Charsets.UTF_8))
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 恢复预检兜底：manifest 缺指纹时，按 database.json 文件原文字节重算（与旧口径一致）。 */
    private fun fingerprintFromFiles(
        databaseFile: File,
        settingsJson: String,
        configurationProfilesJson: String,
        covers: List<File>,
        coverRoot: File,
        brandingFiles: List<File>,
        brandingRoot: File,
    ): String {
        val coverEntries = covers.map { file ->
            val relative = runCatching { file.relativeTo(coverRoot).invariantSeparatorsPath }
                .getOrDefault(file.name)
            relative to file
        }
        return fingerprintFromCoverEntries(databaseFile, settingsJson, configurationProfilesJson, coverEntries, brandingFiles, brandingRoot)
    }

    private fun fingerprintFromCoverEntries(
        databaseFile: File,
        settingsJson: String,
        configurationProfilesJson: String,
        coverEntries: List<Pair<String, File>>,
        brandingFiles: List<File>,
        brandingRoot: File,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        databaseFile.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return finishFingerprintEntries(digest, settingsJson, configurationProfilesJson, coverEntries, brandingFiles, brandingRoot)
    }

    private fun readAllTables(encodeCoverReferences: Boolean = true): JSONObject {
        val sqlite = database.openHelper.writableDatabase
        val result = JSONObject()
        BACKUP_TABLES.forEach { table ->
            val rows = JSONArray()
            sqlite.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
                while (cursor.moveToNext()) {
                    val row = JSONObject()
                    cursor.columnNames.forEachIndexed { index, column ->
                        row.put(column, cursor.jsonValue(index, encodeCoverReferences))
                    }
                    rows.put(row)
                }
            }
            result.put(table, rows)
        }
        return result
    }

    private fun Cursor.jsonValue(index: Int, encodeCoverReferences: Boolean): Any = when (getType(index)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> getLong(index)
        Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
        Cursor.FIELD_TYPE_BLOB -> android.util.Base64.encodeToString(getBlob(index), android.util.Base64.NO_WRAP)
        else -> getString(index).let { value ->
            if (encodeCoverReferences) encodeCoverReference(value) else value
        }
    }

    private fun resolveCoverExportMapping(value: String): Pair<String, File>? {
        val root = File(appContext.filesDir, COVERS_DIRECTORY).canonicalFile
        if (value.startsWith(COVER_MARKER)) {
            val rawRel = value.removePrefix(COVER_MARKER)
            val resolved = com.animeow.app.data.media.resolveExistingLocalCoverFile(root, rawRel) ?: return null
            val canonicalRel = com.animeow.app.data.media.stripRestoredCoverPrefixes(rawRel)
            return canonicalRel to resolved
        }
        val candidate = when {
            value.startsWith("file:") -> value.toUri().path?.let(::File)
            File(value).isAbsolute -> File(value)
            else -> null
        } ?: return null
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        val prefix = root.path + File.separator
        if (!canonical.path.startsWith(prefix)) return null
        val canonicalRel = com.animeow.app.data.media.stripRestoredCoverPrefixes(
            canonical.relativeTo(root).invariantSeparatorsPath,
        )
        return canonicalRel to canonical
    }

    private fun encodeCoverReference(value: String): String {
        if (value.startsWith(COVER_MARKER)) {
            val canonicalRel = com.animeow.app.data.media.stripRestoredCoverPrefixes(value.removePrefix(COVER_MARKER))
            return if (canonicalRel.isNotEmpty()) COVER_MARKER + canonicalRel else value
        }
        val mapping = resolveCoverExportMapping(value) ?: return value
        return COVER_MARKER + mapping.first
    }

    private fun resolveRestoredCoverValue(text: String, restoredCoverRoot: File): String {
        if (!text.startsWith(COVER_MARKER)) return text
        val relative = text.removePrefix(COVER_MARKER)
        val resolved = com.animeow.app.data.media.resolveExistingLocalCoverFile(
            coversRoot = File(appContext.filesDir, COVERS_DIRECTORY),
            markerRelativePath = relative,
            preferredRoot = restoredCoverRoot,
        )
        return resolved?.toUri()?.toString() ?: text
    }

    private fun replaceDatabase(
        json: JSONObject,
        restoredCoverRoot: File,
        selectedTables: Set<String> = BACKUP_TABLES.toSet(),
    ) {
        val sqlite = database.openHelper.writableDatabase
        CLEAR_ORDER.filter(selectedTables::contains).forEach { table -> sqlite.execSQL("DELETE FROM `$table`") }
        INSERT_ORDER.filter(selectedTables::contains).forEach { table ->
            val rows = json.optJSONArray(table) ?: JSONArray()
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val values = ContentValues()
                val rawSubjectType = if (table == "animes") {
                    row.opt("subjectType").takeUnless { it == null || it == JSONObject.NULL }?.toString()
                } else {
                    null
                }
                row.keys().forEach { column ->
                    val value = row.opt(column)
                    when {
                        table == "animes" && column == "subjectType" -> {
                            values.put(column, normalizeSubjectType(rawSubjectType))
                        }
                        value == null || value == JSONObject.NULL -> values.putNull(column)
                        value is Int -> values.put(column, value)
                        value is Long -> values.put(column, value)
                        value is Double -> values.put(column, value)
                        value is Boolean -> values.put(column, if (value) 1 else 0)
                        else -> {
                            val text = value.toString()
                            values.put(column, resolveRestoredCoverValue(text, restoredCoverRoot))
                        }
                    }
                }
                if (table == "animes") {
                    if (!row.has("subjectType")) {
                        values.put("subjectType", normalizeSubjectType(null))
                    }
                }
                if (table == "tags") {
                    // tags.blocked 是 NOT NULL 且无 SQL 默认值；旧备份/远端快照里可能没有该列，
                    // 若照原样写入会触发 “NOT NULL constraint failed: tags.blocked”。
                    // 缺少/为空时兜底为 0（未屏蔽），不影响显式保存的屏蔽状态。
                    if (!row.has("blocked") || row.isNull("blocked")) {
                        values.put("blocked", 0)
                    }
                }
                val result = sqlite.insert(table, SQLiteDatabase.CONFLICT_REPLACE, values)
                check(result != -1L) { "恢复表 $table 时写入失败" }
            }
        }
    }

    /** 预检：流式扫一遍 database.json，校验 JSON 结构并统计各表行数（内存 O(1)）。 */
    private fun countBackupTableRows(file: File): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        file.inputStream().buffered().use { input ->
            JsonReader(input.reader(Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (name in BACKUP_TABLES) {
                        var rows = 0
                        reader.beginArray()
                        while (reader.hasNext()) {
                            // 与恢复端同规则：非对象的坏行跳过不计数，预检不能被单行拖垮
                            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                                reader.skipValue()
                                continue
                            }
                            reader.beginObject()
                            while (reader.hasNext()) {
                                reader.nextName()
                                reader.skipValue()
                            }
                            reader.endObject()
                            rows++
                        }
                        reader.endArray()
                        counts[name] = rows
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }
        }
        return counts
    }

    /**
     * REPLACE 恢复的流式实现（2026-10-01）：按 INSERT_ORDER 逐表扫描 database.json，
     * 边读边写入，不再为整库构建 JSON 树。行级容错按契约「未知列规则」执行：
     * 未知列跳过并记日志、单行失败跳过该行，绝不允许单行问题拖垮整个恢复。
     */
    private fun streamReplaceDatabase(databaseFile: File, restoredCoverRoot: File, selectedTables: Set<String>) {
        val sqlite = database.openHelper.writableDatabase
        val preservedLocalBookUris = mutableMapOf<String, String>()
        if ("book_reading_states" in selectedTables) {
            runCatching {
                sqlite.query("SELECT animeId, fileFingerprint, localFileUri FROM `book_reading_states` WHERE localFileUri IS NOT NULL").use { cursor ->
                    while (cursor.moveToNext()) {
                        val uri = cursor.getString(2)?.takeIf(String::isNotBlank) ?: continue
                        val fp = cursor.getString(1)?.takeIf(String::isNotBlank)
                        if (fp != null) preservedLocalBookUris["fp:$fp"] = uri
                        preservedLocalBookUris["id:${cursor.getLong(0)}"] = uri
                    }
                }
            }
        }
        CLEAR_ORDER.filter(selectedTables::contains).forEach { table -> sqlite.execSQL("DELETE FROM `$table`") }
        val knownColumns = selectedTables.associateWith { table -> sqlite.tableColumns(table) }
        INSERT_ORDER.filter(selectedTables::contains).forEach { table ->
            val columns = knownColumns.getValue(table)
            var skippedRows = 0
            databaseFile.inputStream().buffered().use { input ->
                JsonReader(input.reader(Charsets.UTF_8)).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val key = reader.nextName()
                        if (key != table) {
                            reader.skipValue()
                            continue
                        }
                        reader.beginArray()
                        while (reader.hasNext()) {
                            val values = readRowValues(reader, table, columns, restoredCoverRoot)
                            if (values == null) {
                                skippedRows++
                                continue
                            }
                            if (table == "book_reading_states") {
                                val fp = values.getAsString("fileFingerprint")?.takeIf(String::isNotBlank)
                                val reattached = fp?.let { preservedLocalBookUris["fp:$it"] }
                                if (reattached != null) values.put("localFileUri", reattached) else values.putNull("localFileUri")
                            }
                            try {
                                val result = sqlite.insert(table, SQLiteDatabase.CONFLICT_REPLACE, values)
                                check(result != -1L) { "恢复表 $table 时写入失败" }
                            } catch (error: Exception) {
                                skippedRows++
                            }
                        }
                        reader.endArray()
                    }
                    reader.endObject()
                }
            }
            if (skippedRows > 0) {
                Log.w(TAG, "恢复表 $table 共跳过 $skippedRows 行")
            }
        }
        if ("watch_statuses" in selectedTables) {
            sqlite.execSQL(
                "DELETE FROM watch_statuses WHERE TRIM(name) = '未看' " +
                    "AND EXISTS (SELECT 1 FROM watch_statuses w2 WHERE TRIM(w2.name) = '想看' AND w2.id != watch_statuses.id)",
            )
            sqlite.execSQL("UPDATE watch_statuses SET name = '想看' WHERE TRIM(name) = '未看'")
        }
        if ("animes" in selectedTables) {
            sqlite.execSQL("UPDATE animes SET status = '想看' WHERE TRIM(status) = '未看'")
        }
    }

    /** 读一行到 ContentValues；返回 null 表示该行无法读取（读头已推进到该行结束）。 */
    private fun readRowValues(
        reader: JsonReader,
        table: String,
        columns: Set<String>,
        restoredCoverRoot: File,
    ): ContentValues? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            Log.w(TAG, "备份表 $table 有一行不是对象，已跳过")
            reader.skipValue()
            return null
        }
        val values = ContentValues()
        var completed = false
        try {
            reader.beginObject()
            var rawSubjectType: String? = null
            var hasSubjectType = false
            while (reader.hasNext()) {
                val column = reader.nextName()
                if (!columns.contains(column)) {
                    Log.w(TAG, "备份表 $table 含当前 schema 不认识的列 $column，已跳过")
                    reader.skipValue()
                    continue
                }
                when {
                    table == "animes" && column == "subjectType" -> {
                        hasSubjectType = true
                        rawSubjectType = reader.nextScalarText()
                    }
                    table == "animes" && column in setOf("bookReadVolumes", "bookReadChapters") -> {
                        val value = reader.nextScalarText()?.toIntOrNull()?.takeIf { it in 0..10000 }
                        if (value == null) values.putNull(column) else values.put(column, value)
                    }
                    else -> when (reader.peek()) {
                        JsonToken.NULL -> {
                            reader.nextNull()
                            values.putNull(column)
                        }
                        JsonToken.BOOLEAN -> values.put(column, if (reader.nextBoolean()) 1 else 0)
                        JsonToken.NUMBER -> {
                            val number = reader.nextNumberValue()
                            if (number is Long) values.put(column, number) else values.put(column, number as Double)
                        }
                        JsonToken.STRING -> {
                            val text = reader.nextString()
                            values.put(column, resolveRestoredCoverValue(text, restoredCoverRoot))
                        }
                        else -> reader.skipValue()
                    }
                }
            }
            reader.endObject()
            completed = true
            if (table == "animes") {
                // subjectType 在列循环里被拦截读取，这里统一回填（缺失时 normalize 给默认值）
                values.put("subjectType", normalizeSubjectType(if (hasSubjectType) rawSubjectType else null))
                if (values.getAsString("seriesRole") !in com.animeow.app.data.local.SERIES_ROLES || values.getAsLong("seriesId") == null) values.putNull("seriesRole")
                val normalizedMemo = com.animeow.app.data.local.normalizeAnimeMemo(values.getAsString("memo"))
                if (normalizedMemo == null) values.putNull("memo") else values.put("memo", normalizedMemo)
            }
            if (table == "series") {
                val normalizedLinked = com.animeow.app.data.local.normalizeSeriesLinkedMediaJson(values.getAsString("linkedMediaJson"))
                if (normalizedLinked == null) values.putNull("linkedMediaJson") else values.put("linkedMediaJson", normalizedLinked)
            }
            if (table == "tags" && (!values.containsKey("blocked") || values.get("blocked") == null)) {
                // tags.blocked 是 NOT NULL 且无 SQL 默认值；旧备份/远端快照里可能没有该列，
                // 缺少/为空时兜底为 0（未屏蔽），不影响显式保存的屏蔽状态。
                values.put("blocked", 0)
            }
            if (table == "book_reading_states") {
                values.putNull("localFileUri")
                for (intCol in listOf("chapterIndex", "totalChapters", "blockIndex", "charOffset", "progressPermille")) {
                    if (!values.containsKey(intCol) || values.get(intCol) == null) values.put(intCol, 0)
                }
                if (!values.containsKey("syncProgressToMain") || values.get("syncProgressToMain") == null) {
                    values.put("syncProgressToMain", 1)
                }
                if (!values.containsKey("updatedAt") || values.get("updatedAt") == null) {
                    values.put("updatedAt", "")
                }
            }
            return values
        } catch (error: Exception) {
            Log.w(TAG, "备份表 $table 有一行无法解析，已跳过：${error.message}")
            return null
        } finally {
            if (!completed) skipToObjectEnd(reader)
        }
    }

    /** 标量读成文本（subjectType 等列按旧 opt().toString() 语义消费，可能不是字符串）。 */
    private fun JsonReader.nextScalarText(): String? = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            null
        }
        JsonToken.BOOLEAN -> nextBoolean().toString()
        JsonToken.NUMBER -> nextNumberValue().toString()
        JsonToken.STRING -> nextString()
        else -> {
            skipValue()
            null
        }
    }

    /** NUMBER token 读成 Long/Double：整数保持整数语义，其余转 Double。 */
    private fun JsonReader.nextNumberValue(): Any {
        val text = nextString()
        return text.toLongOrNull() ?: text.toDoubleOrNull() ?: error("不是数字：$text")
    }

    /** 行解析失败后把读头推进到当前行对象的结束（调用时保证正处于行对象内部）。 */
    private fun skipToObjectEnd(reader: JsonReader) {
        var depth = 0
        while (true) {
            when (reader.peek()) {
                JsonToken.END_OBJECT -> {
                    reader.endObject()
                    if (depth == 0) return
                    depth--
                }
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    depth++
                }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    depth++
                }
                JsonToken.END_ARRAY -> {
                    reader.endArray()
                    depth--
                }
                JsonToken.NAME -> reader.nextName()
                JsonToken.END_DOCUMENT -> return
                else -> reader.skipValue()
            }
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.tableColumns(table: String): Set<String> {
        val columns = mutableSetOf<String>()
        query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) columns.add(cursor.getString(nameIndex))
        }
        return columns
    }

    private fun summaryFrom(
        json: JSONObject,
        coverCount: Int,
        createdAt: String,
        dataFingerprint: String,
    ): NativeBackupSummary =
        NativeBackupSummary(
            animeCount = json.optJSONArray("animes")?.length() ?: 0,
            gameCount = json.optJSONArray("games")?.length() ?: 0,
            audioCount = json.optJSONArray("audio_works")?.length() ?: 0,
            tagCount = json.optJSONArray("tags")?.length() ?: 0,
            characterCount = json.optJSONArray("characters")?.length() ?: 0,
            groupCount = json.optJSONArray("character_groups")?.length() ?: 0,
            coverCount = coverCount,
            createdAt = createdAt,
            dataFingerprint = dataFingerprint,
        )

    private fun validatePreparedBranding(
        settings: BrandingSettings,
        stagingDirectory: File,
    ): BrandingSettings {
        val normalized = settings.normalized()
        val image = normalized.splashImagePath?.let { relative ->
            runCatching { safeChild(stagingDirectory, relative) }.getOrNull()
        }
        val icon = normalized.customIconPath?.let { relative ->
            runCatching { safeChild(stagingDirectory, relative) }.getOrNull()
        }
        val withIcon = normalized.copy(customIconPath = normalized.customIconPath.takeIf { icon?.isFile == true })
        return if (image?.isFile == true) {
            withIcon
        } else {
            withIcon.copy(customSplashEnabled = false, splashImagePath = null)
        }
    }

    private suspend fun copyDirectoryContents(source: File, destination: File, stage: NativeRestoreStage) {
        check(destination.isDirectory || destination.mkdirs()) { "无法准备恢复资源目录，请检查存储空间" }
        source.walkTopDown().onFail { _, error -> throw error }.filter(File::isFile).forEach { file ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            restoreCheckpoint(stage)
            val relative = file.relativeTo(source).invariantSeparatorsPath
            val target = safeChild(destination, relative)
            target.parentFile?.mkdirs()
            file.copyTo(target, overwrite = true)
        }
    }

    private fun safeChild(root: File, relative: String): File {
        val target = File(root, relative.replace('\\', '/'))
        val rootPath = root.canonicalFile.path + File.separator
        require(target.canonicalFile.path.startsWith(rootPath)) { "备份包含不安全路径" }
        return target
    }

    private fun ZipOutputStream.writeTextEntry(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipInputStream.copyToLimited(output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "单个备份条目过大" }
            output.write(buffer, 0, read)
        }
        return total
    }

    private fun desktopSettingsPassthroughFile(): File =
        File(appContext.filesDir, DESKTOP_SETTINGS_PASSTHROUGH_PATH)

    private companion object {
        const val FORMAT_TYPE = "animeow-native"
        const val FORMAT_VERSION = 11
        const val MANIFEST_ENTRY = "manifest.json"
        const val DATABASE_ENTRY = "database.json"
        const val SETTINGS_ENTRY = "settings.json"
        const val PLAYBACK_HISTORY_KEY = "playbackHistory"
        const val DESKTOP_SETTINGS_KEY = "desktopStudio"
        const val DESKTOP_SETTINGS_PASSTHROUGH_PATH = "backup_passthrough/desktop_studio_settings.json"
        const val CONFIGURATION_PROFILES_ENTRY = ConfigurationProfileRepository.PROFILE_FILE_NAME
        const val COVERS_DIRECTORY = "covers"
        const val BRANDING_DIRECTORY = BrandingAssetStore.DIRECTORY_NAME
        const val COVER_MARKER = "animeow-cover://"
        const val RESTORE_POINTS_DIRECTORY = "restore_points"
        const val MAX_RESTORE_POINTS = 3
        const val MAX_ENTRIES = 30_000
        const val MAX_ENTRY_BYTES = 300L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
        const val TAG = "NativeBackupService"

        val BACKUP_TABLES = listOf(
            "media_series", "media_series_members", "audio_works", "games", "watch_statuses", "series", "animes", "tags", "anime_tags", "watch_records",
            "episode_details", "book_reading_states", "book_bookmarks",
            "anime_analysis_records", "characters", "anime_characters", "character_relations",
            "character_tags", "character_tag_links", "character_groups",
            "character_group_characters", "character_group_works",
        )
        const val ANALYSIS_TABLE = "anime_analysis_records"
        val CORE_BACKUP_TABLES = BACKUP_TABLES.filterNot { it == ANALYSIS_TABLE }
        val CLEAR_ORDER = listOf(
            "media_series_members", "media_series",
            "character_group_works", "character_group_characters", "character_groups",
            "character_tag_links", "character_tags", "character_relations", "anime_characters",
            "characters", "anime_analysis_records", "watch_records", "book_bookmarks", "book_reading_states", "episode_details", "anime_tags", "tags",
            "audio_works", "games", "animes", "series", "watch_statuses",
        )
        val INSERT_ORDER = BACKUP_TABLES
    }
}

private fun settingsToJson(settings: AppearanceSettings): JSONObject = AppearanceSettingsCodec.toJson(settings)

private fun settingsFromJson(json: JSONObject): AppearanceSettings = AppearanceSettingsCodec.fromJson(json)

private fun analysisSettingsToJson(settings: AnimeAnalysisSettings): JSONObject = JSONObject()
    .put("endpoint", settings.endpoint)
    .put("model", settings.model)
    .put("systemPrompt", settings.systemPrompt)
    .put("promptTemplate", settings.promptTemplate)
    .put("temperature", settings.temperature)

private fun analysisSettingsFromJson(json: JSONObject): AnimeAnalysisSettings = AnimeAnalysisSettings(
    endpoint = json.optString("endpoint").takeIf(String::isNotBlank)
        ?: com.animeow.app.data.analysis.DEFAULT_ENDPOINT,
    model = json.optString("model").takeIf(String::isNotBlank)
        ?: com.animeow.app.data.analysis.DEFAULT_MODEL,
    systemPrompt = json.optString("systemPrompt").takeIf(String::isNotBlank)
        ?: com.animeow.app.data.analysis.DEFAULT_SYSTEM_PROMPT,
    promptTemplate = json.optString("promptTemplate").takeIf(String::isNotBlank)
        ?: com.animeow.app.data.analysis.DEFAULT_PROMPT_TEMPLATE,
    temperature = json.optDouble("temperature", 0.85).toFloat().coerceIn(0f, 2f),
)

private fun discoverySettingsToJson(settings: DiscoveryNetworkSettings): JSONObject = JSONObject()
    .put("publicDataRoute", settings.publicDataRoute.name)
    .put("bangumiApiMode", settings.bangumiApiMode.storageKey)
    .put("metadataLookupOrder", settings.metadataLookupOrder.joinToString(",") { it.storageKey })
    .put("enabledMetadataSources", settings.enabledMetadataSources.joinToString(",") { it.storageKey })
    .put("metadataLookupStrategy", settings.metadataLookupStrategy.storageKey)
    .put("metadataTimeoutSeconds", settings.metadataTimeoutSeconds)
    .put("aniListProxyUrl", settings.aniListProxyUrl)
    .put("allowAniListProxy", settings.allowAniListProxy)
    .put("contributePublicMetadata", settings.contributePublicMetadata)
    .put("bangumiApiProxyBase", settings.bangumiApiProxyBase)
    .put("useBangumiImageProxy", settings.useBangumiImageProxy)
    .put("bangumiImageProxyBase", settings.bangumiImageProxyBase)
    .put("showServerSource", settings.showServerSource)
    .put("includeServerInAllSources", settings.includeServerInAllSources)
    .put(
        "display",
        JSONObject()
            .put("layout", settings.display.layout.storageKey)
            .put("gridColumns", settings.display.gridColumns)
            .put("density", settings.display.density.storageKey)
            .put("showSource", settings.display.showSource)
            .put("showScore", settings.display.showScore)
            .put("showAirDate", settings.display.showAirDate)
            .put("showTags", settings.display.showTags),
    )

private fun discoverySettingsFromJson(json: JSONObject): DiscoveryNetworkSettings {
    val display = json.optJSONObject("display")
    return DiscoveryNetworkSettings(
        bangumiApiMode = BangumiApiMode.fromStorage(json.optString("bangumiApiMode")),
        metadataLookupOrder = com.animeow.app.data.preferences.MetadataLookupStep.fromStorage(json.optString("metadataLookupOrder")),
    enabledMetadataSources = com.animeow.app.data.preferences.MetadataLookupStep.enabledFromStorage(json.optString("enabledMetadataSources").takeIf { json.has("enabledMetadataSources") }),
    publicDataRoute = com.animeow.app.data.preferences.PublicDataRoute.fromStorage(json.optString("publicDataRoute")),
    metadataLookupStrategy = com.animeow.app.data.preferences.MetadataLookupStrategy.fromStorage(json.optString("metadataLookupStrategy")),
    metadataTimeoutSeconds = json.optInt("metadataTimeoutSeconds", 6).coerceIn(3, 12),
        aniListProxyUrl = json.optString("aniListProxyUrl"),
        allowAniListProxy = json.optBoolean("allowAniListProxy", true),
        contributePublicMetadata = json.optBoolean("contributePublicMetadata", true),
        bangumiApiProxyBase = json.optString("bangumiApiProxyBase").takeIf(String::isNotBlank)
            ?: com.animeow.app.data.preferences.DEFAULT_BANGUMI_API_PROXY,
        useBangumiImageProxy = json.optBoolean("useBangumiImageProxy", true),
        bangumiImageProxyBase = json.optString("bangumiImageProxyBase").takeIf(String::isNotBlank)
            ?: com.animeow.app.data.preferences.DEFAULT_BANGUMI_IMAGE_PROXY,
        showServerSource = json.optBoolean("showServerSource", false),
        includeServerInAllSources = json.optBoolean("includeServerInAllSources", false),
        display = com.animeow.app.data.preferences.DiscoveryDisplayConfig(
            layout = com.animeow.app.data.preferences.DiscoveryResultLayout.fromStorage(
                display?.optString("layout"),
            ),
            gridColumns = display?.optInt("gridColumns", 3)?.coerceIn(2, 5) ?: 3,
            density = com.animeow.app.ui.theme.ContentDensity.fromStorage(display?.optString("density")),
            showSource = display?.optBoolean("showSource", true) ?: true,
            showScore = display?.optBoolean("showScore", true) ?: true,
            showAirDate = display?.optBoolean("showAirDate", true) ?: true,
            showTags = display?.optBoolean("showTags", true) ?: true,
        ),
    )
}

private fun calendarSettingsToJson(settings: CalendarDisplaySettings): JSONObject = JSONObject()
    .put("preset", settings.preset.storageKey)
    .put("markerStyle", settings.markerStyle.storageKey)
    .put("density", settings.density.storageKey)
    .put("showCovers", settings.showCovers)
    .put("showLegend", settings.showLegend)
    .put("moduleOrder", JSONArray(settings.moduleOrder.map(CalendarModule::storageKey)))
    .put("hiddenModules", JSONArray(settings.hiddenModules.map(CalendarModule::storageKey)))
    .put("enabledEventTypes", JSONArray(settings.enabledEventTypes.toList()))

private fun calendarSettingsFromJson(json: JSONObject): CalendarDisplaySettings {
    val order = json.optJSONArray("moduleOrder").toStringList()
        .mapNotNull(CalendarModule::fromStorage)
    val hidden = json.optJSONArray("hiddenModules").toStringList()
        .mapNotNull(CalendarModule::fromStorage)
        .toSet()
    val enabled = json.optJSONArray("enabledEventTypes").toStringList()
        .toSet()
        .intersect(DEFAULT_CALENDAR_EVENT_TYPES)
        .ifEmpty { DEFAULT_CALENDAR_EVENT_TYPES }
    return CalendarDisplaySettings(
        preset = CalendarPagePreset.fromStorage(json.optString("preset")),
        markerStyle = CalendarMarkerStyle.fromStorage(json.optString("markerStyle")),
        density = CalendarContentDensity.fromStorage(json.optString("density")),
        showCovers = json.optBoolean("showCovers", true),
        showLegend = json.optBoolean("showLegend", true),
        moduleOrder = order.ifEmpty { CalendarModule.entries },
        hiddenModules = hidden,
        enabledEventTypes = enabled,
    ).normalized()
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }
}

private fun maintenanceSettingsToJson(settings: MaintenanceSettings): JSONObject = JSONObject()
    .put("autoCheckUpdates", settings.autoCheckUpdates)
    .put("ignoredUpdateVersion", settings.ignoredUpdateVersion)

private fun maintenanceSettingsFromJson(json: JSONObject): MaintenanceSettings = MaintenanceSettings(
    autoCheckUpdates = json.optBoolean("autoCheckUpdates", true),
    ignoredUpdateVersion = json.optString("ignoredUpdateVersion"),
)

private fun brandingSettingsToJson(settings: BrandingSettings): JSONObject = JSONObject()
    .put("customSplashEnabled", settings.customSplashEnabled)
    .put("splashImagePath", settings.splashImagePath)
    .put("splashDurationMillis", settings.splashDurationMillis)
    .put("splashScaleMode", settings.splashScaleMode.storageKey)
    .put("splashBackgroundMode", settings.splashBackgroundMode.storageKey)
    .put("splashFocalX", settings.splashFocalX.toDouble())
    .put("splashFocalY", settings.splashFocalY.toDouble())
    .put("tapToSkip", settings.tapToSkip)
    .put("launcherIcon", settings.launcherIcon.storageKey)
    .put("customIconPath", settings.customIconPath)

private fun brandingSettingsFromJson(json: JSONObject): BrandingSettings = BrandingSettings(
    customSplashEnabled = json.optBoolean("customSplashEnabled", false),
    splashImagePath = json.optString("splashImagePath").takeIf(String::isNotBlank),
    splashDurationMillis = json.optInt("splashDurationMillis", 1_000),
    splashScaleMode = SplashScaleMode.fromStorage(json.optString("splashScaleMode")),
    splashBackgroundMode = SplashBackgroundMode.fromStorage(json.optString("splashBackgroundMode")),
    splashFocalX = json.optDouble("splashFocalX", 0.5).toFloat(),
    splashFocalY = json.optDouble("splashFocalY", 0.5).toFloat(),
    tapToSkip = json.optBoolean("tapToSkip", true),
    launcherIcon = LauncherIcon.fromStorage(json.optString("launcherIcon")),
    customIconPath = json.optString("customIconPath").takeIf { !json.isNull("customIconPath") && it.isNotBlank() },
).normalized()

private fun animeEditorSettingsToJson(settings: AnimeEditorSettings): JSONObject = JSONObject()
    .put("preset", settings.preset.storageKey)
    .put("density", settings.density.storageKey)
    .put("exitBehavior", settings.exitBehavior.storageKey)
    .put("moduleOrder", JSONArray(settings.moduleOrder.map(AnimeEditorModule::storageKey)))
    .put("hiddenModules", JSONArray(settings.hiddenModules.map(AnimeEditorModule::storageKey)))

private fun animeEditorSettingsFromJson(json: JSONObject): AnimeEditorSettings = AnimeEditorSettings(
    preset = AnimeEditorPreset.fromStorage(json.optString("preset")),
    density = AnimeEditorDensity.fromStorage(json.optString("density")),
    exitBehavior = EditorExitBehavior.fromStorage(json.optString("exitBehavior")),
    moduleOrder = json.optJSONArray("moduleOrder").toStringList()
        .mapNotNull(AnimeEditorModule::fromStorage)
        .ifEmpty { AnimeEditorModule.entries },
    hiddenModules = json.optJSONArray("hiddenModules").toStringList()
        .mapNotNull(AnimeEditorModule::fromStorage)
        .toSet(),
).normalized()

private fun cloudSyncConfigurationToJson(configuration: CloudSyncConfiguration): JSONObject = JSONObject()
    .put("autoSyncEnabled", configuration.autoSyncEnabled)
    .put("intervalHours", configuration.intervalHours)
    .put("unmeteredOnly", configuration.unmeteredOnly)
    .put("conflictPolicy", configuration.conflictPolicy.storageKey)

private fun cloudSyncConfigurationFromJson(json: JSONObject): CloudSyncConfiguration =
    CloudSyncConfiguration(
        autoSyncEnabled = json.optBoolean("autoSyncEnabled", false),
        intervalHours = json.optInt("intervalHours", 24),
        unmeteredOnly = json.optBoolean("unmeteredOnly", false),
        conflictPolicy = CloudConflictPolicy.fromStorage(json.optString("conflictPolicy")),
    )

private fun CloudSyncConfiguration.toRuntimeSettings(): CloudSyncSettings = CloudSyncSettings(
    autoSyncEnabled = autoSyncEnabled,
    intervalHours = intervalHours,
    unmeteredOnly = unmeteredOnly,
    conflictPolicy = conflictPolicy,
)
