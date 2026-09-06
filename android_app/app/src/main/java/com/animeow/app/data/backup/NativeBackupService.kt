package com.animeow.app.data.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.normalizeSubjectType
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
import com.animeow.app.data.preferences.BrandingPreferences
import com.animeow.app.data.preferences.TrackerPreferences
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
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    internal val databaseJson: JSONObject,
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
)

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
    private val editorPreferences = AnimeEditorPreferences(appContext)
    private val cloudSyncPreferences = CloudSyncPreferences(appContext)
    private val configurationProfiles = ConfigurationProfileRepository(appContext, appearancePreferences)

    suspend fun export(uri: Uri): NativeBackupSummary = withContext(Dispatchers.IO) {
        val snapshot = createSnapshot()
        appContext.contentResolver.openOutputStream(uri, "w")?.use { rawOutput ->
            writeSnapshot(rawOutput, snapshot)
        } ?: error("无法写入所选备份位置")
        snapshot.summary
    }

    suspend fun export(file: File): NativeBackupSummary = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        try {
            val snapshot = createSnapshot()
            FileOutputStream(file).use { writeSnapshot(it, snapshot) }
            snapshot.summary
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    internal suspend fun summarizeCurrent(): NativeBackupSummary = withContext(Dispatchers.IO) {
        createSnapshot().summary
    }

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
            val databaseJson = runCatching { JSONObject(databaseFile.readText()) }.getOrElse { error ->
                throw NativeBackupFormatException("备份资料库无法读取，文件可能已经损坏", error)
            }
            val settingsFile = File(staging, SETTINGS_ENTRY)
            val settingsRoot = if (settingsFile.exists()) JSONObject(settingsFile.readText()) else JSONObject()
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
                ?.let(::trackerSettingsFromJson)
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
            PreparedNativeRestore(
                stagingDirectory = staging,
                databaseJson = databaseJson,
                settings = settings,
                analysisSettings = analysisSettings,
                discoverySettings = discoverySettings,
                calendarSettings = calendarSettings,
                maintenanceSettings = maintenanceSettings,
                brandingSettings = brandingSettings,
                trackerSettings = trackerSettings,
                editorSettings = editorSettings,
                cloudSyncConfiguration = cloudSyncConfiguration,
                configurationProfilesJson = configurationProfilesJson,
                summary = summaryFrom(
                    databaseJson,
                    covers,
                    manifest.optString("createdAt").ifBlank { "未知" },
                    manifest.optString("dataFingerprint").ifBlank {
                        fingerprint(
                            databaseJson = databaseJson,
                            settingsJson = if (settingsFile.exists()) settingsFile.readText() else "{}",
                            configurationProfilesJson = configurationProfilesJson.orEmpty(),
                            covers = File(staging, "covers").takeIf(File::exists)
                                ?.walkTopDown()?.filter(File::isFile)?.toList().orEmpty(),
                            coverRoot = File(staging, "covers"),
                            brandingFiles = File(staging, BRANDING_DIRECTORY).takeIf(File::exists)
                                ?.walkTopDown()?.filter(File::isFile)?.toList().orEmpty(),
                            brandingRoot = File(staging, BRANDING_DIRECTORY),
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
        val destinationRoot = File(
            File(appContext.filesDir, COVERS_DIRECTORY),
            "restored_${System.currentTimeMillis()}",
        )
        val previousSettings = appearancePreferences.snapshot()
        val previousAnalysisSettings = animeAnalysisPreferences.snapshot()
        val previousDiscoverySettings = discoveryPreferences.snapshot()
        val previousCalendarSettings = calendarPreferences.snapshot()
        val previousMaintenanceSettings = maintenancePreferences.snapshot()
        val previousBrandingSettings = brandingPreferences.snapshot()
        val previousTrackerSettings = trackerPreferences.snapshot()
        val previousEditorSettings = editorPreferences.snapshot()
        val previousCloudSyncConfiguration = cloudSyncPreferences.configurationSnapshot()
        val previousConfigurationProfiles = prepared.configurationProfilesJson?.let {
            configurationProfiles.snapshotJson()
        }
        val brandingDestination = File(appContext.filesDir, BRANDING_DIRECTORY)
        val brandingRollback = File(prepared.stagingDirectory, "_branding_before_restore")
        val brandingSource = File(prepared.stagingDirectory, BRANDING_DIRECTORY)
        var settingsRestored = false
        var analysisSettingsRestored = false
        var discoverySettingsRestored = false
        var calendarSettingsRestored = false
        var maintenanceSettingsRestored = false
        var brandingSettingsRestored = false
        var trackerSettingsRestored = false
        var editorSettingsRestored = false
        var cloudSyncConfigurationRestored = false
        var configurationProfilesRestored = false
        var brandingAssetsReplaced = false
        var restoredSummary = prepared.summary
        try {
            val sourceCovers = File(prepared.stagingDirectory, "covers")
            if (selection.libraryAndCharacters && sourceCovers.exists()) {
                sourceCovers.walkTopDown().filter(File::isFile).forEach { source ->
                    val relative = source.relativeTo(sourceCovers).invariantSeparatorsPath
                    val destination = safeChild(destinationRoot, relative)
                    destination.parentFile?.mkdirs()
                    source.copyTo(destination, overwrite = true)
                }
            }
            if (selection.appearanceSettings) {
                brandingRollback.deleteRecursively()
                if (brandingDestination.exists()) {
                    copyDirectoryContents(brandingDestination, brandingRollback)
                }
                brandingDestination.deleteRecursively()
                if (brandingSource.exists()) {
                    copyDirectoryContents(brandingSource, brandingDestination)
                }
                brandingAssetsReplaced = true
            }
            database.withTransaction {
                val selectedTables = buildSet {
                    if (selection.libraryAndCharacters) addAll(CORE_BACKUP_TABLES)
                    if (selection.analysisHistory) add(ANALYSIS_TABLE)
                }
                if (selectedTables.isNotEmpty()) {
                    val restoreJson = if (strategy == NativeRestoreStrategy.MERGE_KEEP_LOCAL) {
                        NativeBackupMerger.merge(
                            // Existing local cover references must stay absolute. Only markers
                            // originating in the remote archive may be rebased to destinationRoot.
                            local = readAllTables(encodeCoverReferences = false),
                            remote = prepared.databaseJson,
                            includeCore = selection.libraryAndCharacters,
                            includeAnalysis = selection.analysisHistory,
                        ).also { merged ->
                            restoredSummary = summaryFrom(
                                json = merged,
                                coverCount = File(appContext.filesDir, COVERS_DIRECTORY)
                                    .takeIf(File::exists)?.walkTopDown()?.count(File::isFile) ?: 0,
                                createdAt = Instant.now().toString(),
                                dataFingerprint = "",
                            )
                        }
                    } else {
                        prepared.databaseJson
                    }
                    replaceDatabase(restoreJson, destinationRoot, selectedTables)
                }
                if (selection.appearanceSettings) {
                    // Keep the Room transaction open until DataStore accepts the restored
                    // preferences. A settings write failure then rolls the database back too.
                    appearancePreferences.restore(prepared.settings)
                    settingsRestored = true
                    animeAnalysisPreferences.save(prepared.analysisSettings)
                    analysisSettingsRestored = true
                    discoveryPreferences.save(prepared.discoverySettings)
                    discoverySettingsRestored = true
                    calendarPreferences.save(prepared.calendarSettings)
                    calendarSettingsRestored = true
                    maintenancePreferences.save(prepared.maintenanceSettings)
                    maintenanceSettingsRestored = true
                    brandingPreferences.save(prepared.brandingSettings)
                    brandingSettingsRestored = true
                    trackerPreferences.save(prepared.trackerSettings)
                    trackerSettingsRestored = true
                    editorPreferences.save(prepared.editorSettings)
                    editorSettingsRestored = true
                    prepared.cloudSyncConfiguration?.let { configuration ->
                        cloudSyncPreferences.restoreConfiguration(configuration)
                        cloudSyncConfigurationRestored = true
                    }
                    prepared.configurationProfilesJson?.let { profilesJson ->
                        configurationProfiles.restoreSnapshotJson(profilesJson)
                        configurationProfilesRestored = true
                    }
                }
            }
            // The database transaction has committed. Rebuilding derived jobs must finish its
            // cleanup even if the caller leaves, and a scheduler failure must not turn a
            // successful restore into a false rollback report.
            withContext(NonCancellable) {
                if (selection.libraryAndCharacters) {
                    runCatching { AnimeReminderScheduler(appContext).syncAll(database) }
                        .onFailure { error -> Log.w(TAG, "提醒重建失败，将在下次资料变更时重试", error) }
                }
                if (cloudSyncConfigurationRestored) {
                    runCatching {
                        CloudSyncScheduler.apply(
                            appContext,
                            prepared.cloudSyncConfiguration!!.toRuntimeSettings(),
                        )
                    }.onFailure { error ->
                        Log.w(TAG, "云同步计划重建失败，将在下次修改同步设置时重试", error)
                    }
                }
            }
            if (strategy == NativeRestoreStrategy.REPLACE && selection.libraryAndCharacters) {
                runCatching { cleanupOldRestoredCoverDirectories(destinationRoot) }
                    .onFailure { error -> Log.w(TAG, "旧恢复封面目录清理失败", error) }
            }
            prepared.stagingDirectory.deleteRecursively()
            restoredSummary
        } catch (error: Throwable) {
            // DataStore and branding files live outside Room's transaction. Cancellation must
            // not interrupt their rollback after the database has already rolled back.
            withContext(NonCancellable) {
                if (configurationProfilesRestored && previousConfigurationProfiles != null) {
                    runCatching { configurationProfiles.restoreSnapshotJson(previousConfigurationProfiles) }
                        .onFailure { rollbackError ->
                            Log.e(TAG, "恢复失败后回滚配置档案失败", rollbackError)
                        }
                }
                if (cloudSyncConfigurationRestored) {
                    runCatching {
                        cloudSyncPreferences.restoreConfiguration(previousCloudSyncConfiguration)
                        CloudSyncScheduler.apply(appContext, previousCloudSyncConfiguration.toRuntimeSettings())
                    }.onFailure { rollbackError ->
                        Log.e(TAG, "恢复失败后回滚云同步配置失败", rollbackError)
                    }
                }
                if (editorSettingsRestored) {
                    runCatching { editorPreferences.save(previousEditorSettings) }
                        .onFailure { rollbackError ->
                            Log.e(TAG, "恢复失败后回滚编辑器设置失败", rollbackError)
                        }
                }
                if (trackerSettingsRestored) {
                    runCatching { trackerPreferences.save(previousTrackerSettings) }
                        .onFailure { rollbackError ->
                            Log.e(TAG, "恢复失败后回滚首页筛选设置失败", rollbackError)
                        }
                }
                if (brandingAssetsReplaced) {
                    runCatching {
                        brandingDestination.deleteRecursively()
                        if (brandingRollback.exists()) {
                            copyDirectoryContents(brandingRollback, brandingDestination)
                        }
                    }.onFailure { rollbackError ->
                        Log.e(TAG, "恢复失败后回滚品牌资源失败", rollbackError)
                    }
                }
                if (brandingSettingsRestored) {
                    runCatching { brandingPreferences.save(previousBrandingSettings) }
                        .onFailure { rollbackError ->
                            Log.e(TAG, "恢复失败后回滚品牌设置失败", rollbackError)
                        }
                }
                if (maintenanceSettingsRestored) {
                    runCatching { maintenancePreferences.save(previousMaintenanceSettings) }
                        .onFailure { rollbackError ->
                            Log.e(TAG, "恢复失败后回滚维护设置失败", rollbackError)
                        }
                }
                if (calendarSettingsRestored) {
                    runCatching { calendarPreferences.save(previousCalendarSettings) }
                        .onFailure { rollbackError ->
                            Log.e(TAG, "恢复失败后回滚日历设置失败", rollbackError)
                        }
                }
                if (discoverySettingsRestored) {
                    runCatching { discoveryPreferences.save(previousDiscoverySettings) }
                        .onFailure { rollbackError ->
                            Log.e(TAG, "恢复失败后回滚数据源设置失败", rollbackError)
                        }
                }
                if (analysisSettingsRestored) {
                    runCatching { animeAnalysisPreferences.save(previousAnalysisSettings) }
                        .onFailure { rollbackError ->
                            Log.e(TAG, "恢复失败后回滚 AI 分析设置失败", rollbackError)
                        }
                }
                if (settingsRestored) {
                    runCatching { appearancePreferences.restore(previousSettings) }
                        .onFailure { rollbackError ->
                            Log.e(TAG, "恢复失败后回滚外观设置失败", rollbackError)
                        }
                }
                destinationRoot.deleteRecursively()
            }
            throw error
        }
    }

    fun discard(prepared: PreparedNativeRestore) {
        prepared.stagingDirectory.deleteRecursively()
    }

    private fun cleanupOldRestoredCoverDirectories(activeDirectory: File) {
        val root = File(appContext.filesDir, COVERS_DIRECTORY)
        root.listFiles()?.filter { candidate ->
            candidate.isDirectory && candidate != activeDirectory && candidate.name.startsWith("restored_")
        }?.forEach(File::deleteRecursively)
    }

    private suspend fun createSnapshot(): BackupSnapshot {
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
        val settingsJson = JSONObject()
            .put("appearance", settingsToJson(settings))
            .put("animeAnalysis", analysisSettingsToJson(analysisSettings))
            .put("discovery", discoverySettingsToJson(discoverySettings))
            .put("calendar", calendarSettingsToJson(calendarSettings))
            .put("maintenance", maintenanceSettingsToJson(maintenanceSettings))
            .put("branding", brandingSettingsToJson(brandingSettings))
            .put("tracker", trackerSettingsToJson(trackerSettings))
            .put("animeEditor", animeEditorSettingsToJson(editorSettings))
            .put("cloudSync", cloudSyncConfigurationToJson(cloudSyncConfiguration))
            .toString()
        val databaseJson = database.withTransaction { readAllTables() }
        val coverRoot = File(appContext.filesDir, COVERS_DIRECTORY)
        val covers = coverRoot.takeIf(File::exists)
            ?.walkTopDown()
            ?.filter(File::isFile)
            ?.sortedBy { it.relativeTo(coverRoot).invariantSeparatorsPath }
            ?.toList()
            .orEmpty()
        val brandingRoot = File(appContext.filesDir, BRANDING_DIRECTORY)
        val brandingFiles = brandingRoot.takeIf(File::exists)
            ?.walkTopDown()
            ?.filter(File::isFile)
            ?.sortedBy { it.relativeTo(brandingRoot).invariantSeparatorsPath }
            ?.toList()
            .orEmpty()
        val createdAt = Instant.now().toString()
        val dataFingerprint = fingerprint(
            databaseJson = databaseJson,
            settingsJson = settingsJson,
            configurationProfilesJson = configurationProfilesJson,
            covers = covers,
            coverRoot = coverRoot,
            brandingFiles = brandingFiles,
            brandingRoot = brandingRoot,
        )
        return BackupSnapshot(
            databaseJson = databaseJson,
            settingsJson = settingsJson,
            configurationProfilesJson = configurationProfilesJson,
            covers = covers,
            coverRoot = coverRoot,
            brandingFiles = brandingFiles,
            brandingRoot = brandingRoot,
            summary = summaryFrom(databaseJson, covers.size, createdAt, dataFingerprint),
        )
    }

    private fun writeSnapshot(output: OutputStream, snapshot: BackupSnapshot) {
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.writeTextEntry(
                MANIFEST_ENTRY,
                JSONObject()
                    .put("type", FORMAT_TYPE)
                    .put("version", FORMAT_VERSION)
                    .put("createdAt", snapshot.summary.createdAt)
                    .put("animeCount", snapshot.summary.animeCount)
                    .put("coverCount", snapshot.summary.coverCount)
                    .put("dataFingerprint", snapshot.summary.dataFingerprint)
                    .toString(),
            )
            zip.writeTextEntry(DATABASE_ENTRY, snapshot.databaseJson.toString())
            zip.writeTextEntry(SETTINGS_ENTRY, snapshot.settingsJson)
            zip.writeTextEntry(CONFIGURATION_PROFILES_ENTRY, snapshot.configurationProfilesJson)
            snapshot.covers.forEach { file ->
                val relative = file.relativeTo(snapshot.coverRoot).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry("covers/$relative"))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
            snapshot.brandingFiles.forEach { file ->
                val relative = file.relativeTo(snapshot.brandingRoot).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry("$BRANDING_DIRECTORY/$relative"))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
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

    private fun encodeCoverReference(value: String): String {
        val root = File(appContext.filesDir, COVERS_DIRECTORY).canonicalFile
        val candidate = when {
            value.startsWith("file:") -> value.toUri().path?.let(::File)
            File(value).isAbsolute -> File(value)
            else -> null
        } ?: return value
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return value
        val prefix = root.path + File.separator
        return if (canonical.path.startsWith(prefix)) {
            COVER_MARKER + canonical.relativeTo(root).invariantSeparatorsPath
        } else {
            value
        }
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
                            values.put(
                                column,
                                if (text.startsWith(COVER_MARKER)) {
                                    safeChild(restoredCoverRoot, text.removePrefix(COVER_MARKER)).toUri().toString()
                                } else {
                                    text
                                },
                            )
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

    private fun summaryFrom(
        json: JSONObject,
        coverCount: Int,
        createdAt: String,
        dataFingerprint: String,
    ): NativeBackupSummary =
        NativeBackupSummary(
            animeCount = json.optJSONArray("animes")?.length() ?: 0,
            tagCount = json.optJSONArray("tags")?.length() ?: 0,
            characterCount = json.optJSONArray("characters")?.length() ?: 0,
            groupCount = json.optJSONArray("character_groups")?.length() ?: 0,
            coverCount = coverCount,
            createdAt = createdAt,
            dataFingerprint = dataFingerprint,
        )

    private fun fingerprint(
        databaseJson: JSONObject,
        settingsJson: String,
        configurationProfilesJson: String,
        covers: List<File>,
        coverRoot: File,
        brandingFiles: List<File>,
        brandingRoot: File,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(databaseJson.toString().toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(settingsJson.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(CONFIGURATION_PROFILES_ENTRY.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(configurationProfilesJson.toByteArray(Charsets.UTF_8))
        covers.sortedBy { runCatching { it.relativeTo(coverRoot).invariantSeparatorsPath }.getOrDefault(it.name) }
            .forEach { file ->
                val relative = runCatching { file.relativeTo(coverRoot).invariantSeparatorsPath }
                    .getOrDefault(file.name)
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

    private fun validatePreparedBranding(
        settings: BrandingSettings,
        stagingDirectory: File,
    ): BrandingSettings {
        val normalized = settings.normalized()
        val image = normalized.splashImagePath?.let { relative ->
            runCatching { safeChild(stagingDirectory, relative) }.getOrNull()
        }
        return if (image?.isFile == true) {
            normalized
        } else {
            normalized.copy(customSplashEnabled = false, splashImagePath = null)
        }
    }

    private fun copyDirectoryContents(source: File, destination: File) {
        destination.mkdirs()
        source.walkTopDown().filter(File::isFile).forEach { file ->
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

    private companion object {
        const val FORMAT_TYPE = "animeow-native"
        const val FORMAT_VERSION = 11
        const val MANIFEST_ENTRY = "manifest.json"
        const val DATABASE_ENTRY = "database.json"
        const val SETTINGS_ENTRY = "settings.json"
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

        data class BackupSnapshot(
            val databaseJson: JSONObject,
            val settingsJson: String,
            val configurationProfilesJson: String,
            val covers: List<File>,
            val coverRoot: File,
            val brandingFiles: List<File>,
            val brandingRoot: File,
            val summary: NativeBackupSummary,
        )

        val BACKUP_TABLES = listOf(
            "watch_statuses", "series", "animes", "tags", "anime_tags", "watch_records",
            "anime_analysis_records", "characters", "anime_characters", "character_relations",
            "character_tags", "character_tag_links", "character_groups",
            "character_group_characters", "character_group_works",
        )
        const val ANALYSIS_TABLE = "anime_analysis_records"
        val CORE_BACKUP_TABLES = BACKUP_TABLES.filterNot { it == ANALYSIS_TABLE }
        val CLEAR_ORDER = listOf(
            "character_group_works", "character_group_characters", "character_groups",
            "character_tag_links", "character_tags", "character_relations", "anime_characters",
            "characters", "anime_analysis_records", "watch_records", "anime_tags", "tags",
            "animes", "series", "watch_statuses",
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
    .put("bangumiApiMode", settings.bangumiApiMode.storageKey)
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
).normalized()

private fun trackerSettingsToJson(settings: TrackerSettings): JSONObject = JSONObject()
    .put("defaultStartStatus", settings.defaultStartStatus)
    .put("lastSelectedStatus", settings.lastSelectedStatus)
    .put("lastSubjectType", settings.lastSubjectType)
    .put("lastSortKey", settings.lastSortKey)
    .put("sortAscending", settings.sortAscending)
    .put("groupSeriesOnHome", settings.groupSeriesOnHome)
    .put("showStandaloneBookshelf", settings.showStandaloneBookshelf)
    .put("homeBottomScope", settings.homeBottomScope.storageKey)
    .put("bentoCollectionLayout", settings.bentoCollectionLayout.storageKey)
    .put("showSubjectType", settings.showSubjectType)
    .put("showSeriesCount", settings.showSeriesCount)
    .put("showUpdateWeekday", settings.showUpdateWeekday)
    .put("showCoverStatus", settings.showCoverStatus)
    .put("showCoverRating", settings.showCoverRating)
    .put("showCoverProgress", settings.showCoverProgress)
    .put("showCoverSubjectType", settings.showCoverSubjectType)
    .put("showCoverSeriesCount", settings.showCoverSeriesCount)
    .put("showCoverUpdateWeekday", settings.showCoverUpdateWeekday)
    .put("autoSyncNetworkCovers", settings.autoSyncNetworkCovers)
    .put("infoScale", settings.infoScale.toDouble())
    .put("infoOpacity", settings.infoOpacity.toDouble())
    .put("infoTextOpacity", settings.infoTextOpacity.toDouble())
    .put("infoDarkBackgroundOpacity", settings.infoDarkBackgroundOpacity.toDouble())
    .put("infoLightBackgroundOpacity", settings.infoLightBackgroundOpacity.toDouble())
    .put("infoCornerDp", settings.infoCornerDp.toDouble())
    .put("coverCornerDp", settings.coverCornerDp.toDouble())
    .put("coverImageOpacity", settings.coverImageOpacity.toDouble())
    .put("coverSaturation", settings.coverSaturation.toDouble())
    .put("coverFitMode", settings.coverFitMode.storageKey)

private fun trackerSettingsFromJson(json: JSONObject): TrackerSettings = TrackerSettings(
    defaultStartStatus = json.optString("defaultStartStatus"),
    lastSelectedStatus = json.optString("lastSelectedStatus"),
    lastSubjectType = json.optString("lastSubjectType"),
    lastSortKey = json.optString("lastSortKey"),
    sortAscending = json.optBoolean("sortAscending", false),
    groupSeriesOnHome = json.optBoolean("groupSeriesOnHome", true),
    showStandaloneBookshelf = json.optBoolean("showStandaloneBookshelf", true),
    homeBottomScope = com.animeow.app.data.preferences.HomeBottomScope.fromStorage(
        json.optString("homeBottomScope"),
    ),
    bentoCollectionLayout = com.animeow.app.data.preferences.BentoCollectionLayout.fromStorage(
        json.optString("bentoCollectionLayout"),
    ),
    showSubjectType = json.optBoolean("showSubjectType", false),
    showSeriesCount = json.optBoolean("showSeriesCount", true),
    showUpdateWeekday = json.optBoolean("showUpdateWeekday", false),
    showCoverStatus = json.optBoolean("showCoverStatus", true),
    showCoverRating = json.optBoolean("showCoverRating", true),
    showCoverProgress = json.optBoolean("showCoverProgress", true),
    showCoverSubjectType = json.optBoolean("showCoverSubjectType", false),
    showCoverSeriesCount = json.optBoolean("showCoverSeriesCount", true),
    showCoverUpdateWeekday = json.optBoolean("showCoverUpdateWeekday", false),
    autoSyncNetworkCovers = json.optBoolean("autoSyncNetworkCovers", false),
    infoScale = json.optDouble("infoScale", 1.0).toFloat(),
    infoOpacity = json.optDouble("infoOpacity", 0.88).toFloat(),
    infoTextOpacity = json.optDouble("infoTextOpacity", 1.0).toFloat(),
    infoDarkBackgroundOpacity = json.optDouble(
        "infoDarkBackgroundOpacity",
        json.optDouble("infoOpacity", 0.88),
    ).toFloat(),
    infoLightBackgroundOpacity = json.optDouble(
        "infoLightBackgroundOpacity",
        json.optDouble("infoOpacity", 0.88),
    ).toFloat(),
    infoCornerDp = json.optDouble("infoCornerDp", 8.0).toFloat(),
    coverCornerDp = json.optDouble("coverCornerDp", 12.0).toFloat(),
    coverImageOpacity = json.optDouble("coverImageOpacity", 1.0).toFloat(),
    coverSaturation = json.optDouble("coverSaturation", 1.0).toFloat(),
    coverFitMode = com.animeow.app.data.preferences.CoverFitMode.fromStorage(json.optString("coverFitMode")),
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
