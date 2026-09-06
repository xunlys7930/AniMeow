package com.animeow.app.data.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.animeow.app.data.backup.NativeBackupService
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.reminder.AnimeReminderScheduler
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LegacyBackupImporter(
    context: Context,
    private val database: AniMeowDatabase,
) {
    private val appContext = context.applicationContext
    private val reader = LegacyDatabaseReader()
    private val nativeBackupService = NativeBackupService(appContext, database)

    internal suspend fun prepare(uri: Uri): PreparedLegacyImport {
        var completed: PreparedLegacyImport? = null
        return try {
            withContext(Dispatchers.IO) {
                val sourceName = queryDisplayName(uri) ?: "原版备份"
                val stagingDirectory = File(
                    File(appContext.cacheDir, STAGING_ROOT),
                    UUID.randomUUID().toString(),
                )
                require(stagingDirectory.mkdirs()) { "无法创建导入临时目录" }

                try {
                    val sourceFile = File(stagingDirectory, "source.backup")
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(sourceFile).use { output ->
                            copyWithLimit(input, output, MAX_SOURCE_BYTES, "备份文件过大")
                        }
                    } ?: error("无法读取所选备份文件")

                    val extracted = when {
                        sourceFile.hasZipHeader() -> extractZip(sourceFile, stagingDirectory)
                        sourceFile.hasSqliteHeader() -> ExtractedLegacyFiles(
                            databaseFile = sourceFile,
                            coversDirectory = null,
                        )
                        else -> error("文件不是原版 AniMeow ZIP 备份或 SQLite 数据库")
                    }

                    require(extracted.databaseFile.hasSqliteHeader()) {
                        "备份中的数据库文件格式无效"
                    }
                    val snapshot = reader.read(extracted.databaseFile)
                    val coverCount = extracted.coversDirectory?.regularFiles()?.size ?: 0
                    val warnings = buildList {
                        if (snapshot.sourceSchemaVersion > MAX_KNOWN_LEGACY_SCHEMA) {
                            add(
                                "该备份来自较新的原版数据库 v${snapshot.sourceSchemaVersion}，" +
                                    "未知的新字段会被忽略。",
                            )
                        }
                        if (snapshot.sourceSchemaVersion <= 0) {
                            add("未检测到原版数据库版本号，将按最早兼容格式读取。")
                        }
                        if (snapshot.animes.isEmpty()) {
                            add("备份中没有番剧记录。")
                        }
                        add("原版 ZIP 不包含外观与交互偏好，这些设置会保留新版本当前值。")
                        add("原版诊断日志不会迁移。")
                    }
                    val summary = LegacyImportSummary(
                        sourceName = sourceName,
                        sourceSchemaVersion = snapshot.sourceSchemaVersion,
                        animeCount = snapshot.animes.size,
                        seriesCount = snapshot.series.size,
                        tagCount = snapshot.tags.size,
                        watchRecordCount = snapshot.watchRecords.size,
                        characterCount = snapshot.characters.size,
                        characterGroupCount = snapshot.characterGroups.size,
                        coverCount = coverCount,
                        warnings = warnings,
                    )

                    PreparedLegacyImport(
                        stagingDirectory = stagingDirectory,
                        coversDirectory = extracted.coversDirectory,
                        snapshot = snapshot,
                        summary = summary,
                    ).also { completed = it }
                } catch (error: Throwable) {
                    stagingDirectory.deleteRecursively()
                    throw error
                }
            }
        } catch (error: Throwable) {
            completed?.let(::discard)
            throw error
        }
    }

    internal suspend fun importPrepared(
        prepared: PreparedLegacyImport,
    ): LegacyImportResult = withContext(Dispatchers.IO) {
        var migratedCovers: MigratedCovers? = null
        var libraryCommitted = false
        try {
            if (!prepared.restorePointCreated) {
                nativeBackupService.createRestorePoint("before_legacy_import")
                prepared.restorePointCreated = true
            }
            migratedCovers = migrateCovers(prepared.coversDirectory)
            val snapshot = rewriteCoverUrls(
                snapshot = prepared.snapshot,
                migratedCovers = migratedCovers,
            )
            replaceLibrary(snapshot)
            libraryCommitted = true
            val reminderSyncSucceeded = try {
                AnimeReminderScheduler(appContext).syncAll(database)
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "原版数据已提交，但提醒重建失败，将在下次提醒同步时重试", error)
                false
            }
            runCatching {
                cleanupOldImportedCoverDirectories(migratedCovers.destinationDirectory)
            }.onFailure { error ->
                Log.w(TAG, "原版数据已提交，但旧封面目录清理失败", error)
            }

            val result = LegacyImportResult(
                summary = prepared.summary,
                copiedCoverCount = migratedCovers.fileCount,
                restorePointCreated = prepared.restorePointCreated,
                reminderSyncSucceeded = reminderSyncSucceeded,
            )
            discard(prepared)
            result
        } catch (error: Throwable) {
            if (!libraryCommitted) {
                migratedCovers?.destinationDirectory?.deleteRecursively()
            } else {
                Log.w(TAG, "原版数据已提交，取消或后续失败时保留数据库引用的封面", error)
            }
            throw error
        }
    }

    internal fun discard(prepared: PreparedLegacyImport) {
        prepared.stagingDirectory.deleteRecursively()
    }

    fun queryDisplayName(uri: Uri): String? {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0)
            }
        }
        return uri.lastPathSegment
    }

    private fun extractZip(
        sourceFile: File,
        stagingDirectory: File,
    ): ExtractedLegacyFiles {
        val coversDirectory = File(stagingDirectory, "covers")
        var databaseFile: File? = null
        var databaseIsExactMatch = false
        var entryCount = 0
        var extractedCoverBytes = 0L

        ZipInputStream(BufferedInputStream(FileInputStream(sourceFile))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                require(entryCount <= MAX_ZIP_ENTRIES) { "备份条目过多，已停止导入" }

                val normalizedName = entry.name.replace('\\', '/').trimStart('/')
                val baseName = normalizedName.substringAfterLast('/')
                if (!entry.isDirectory && normalizedName.endsWith(".db", ignoreCase = true)) {
                    val exactMatch = baseName.equals(LEGACY_DATABASE_NAME, ignoreCase = true)
                    if (databaseFile == null || exactMatch && !databaseIsExactMatch) {
                        val target = File(stagingDirectory, "legacy.db")
                        FileOutputStream(target).use { output ->
                            copyWithLimit(zip, output, MAX_DATABASE_BYTES, "原版数据库过大")
                        }
                        databaseFile = target
                        databaseIsExactMatch = exactMatch
                    }
                } else if (!entry.isDirectory) {
                    val coversMarker = normalizedName.lowercase(java.util.Locale.ROOT).indexOf("covers/")
                    if (coversMarker >= 0) {
                        val relativeName = normalizedName
                            .substring(coversMarker + "covers/".length)
                            .trimStart('/')
                        if (relativeName.isNotBlank()) {
                            val target = safeChild(coversDirectory, relativeName)
                            target.parentFile?.mkdirs()
                            val copied = FileOutputStream(target).use { output ->
                                copyWithLimit(zip, output, MAX_COVER_BYTES, "单张封面文件过大")
                            }
                            extractedCoverBytes += copied
                            require(extractedCoverBytes <= MAX_ALL_COVERS_BYTES) {
                                "备份中的封面总量过大"
                            }
                        }
                    }
                }
                zip.closeEntry()
            }
        }

        return ExtractedLegacyFiles(
            databaseFile = databaseFile ?: error("ZIP 中没有找到原版 SQLite 数据库"),
            coversDirectory = coversDirectory.takeIf { it.exists() },
        )
    }

    private fun migrateCovers(sourceDirectory: File?): MigratedCovers {
        val sourceFiles = sourceDirectory?.regularFiles().orEmpty()
        if (sourceFiles.isEmpty()) return MigratedCovers.empty()

        val destinationDirectory = File(
            File(appContext.filesDir, IMPORTED_COVERS_ROOT),
            "legacy_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}",
        )
        require(destinationDirectory.mkdirs()) { "无法创建封面迁移目录" }

        return try {
            val exactPaths = linkedMapOf<String, String>()
            val baseNameCandidates = linkedMapOf<String, MutableList<String>>()
            sourceFiles.forEach { source ->
                val relative = source.relativeTo(sourceDirectory!!).invariantSeparatorsPath
                val destination = safeChild(destinationDirectory, relative)
                destination.parentFile?.mkdirs()
                source.inputStream().use { input ->
                    destination.outputStream().use { output ->
                        copyWithLimit(input, output, MAX_COVER_BYTES, "单张封面文件过大")
                    }
                }
                val destinationUri = destination.toUri().toString()
                val normalizedRelative = LegacyCoverPathMapper.normalizedRelativePath(relative)
                exactPaths[normalizedRelative] = destinationUri
                baseNameCandidates
                    .getOrPut(normalizedRelative.substringAfterLast('/')) { mutableListOf() }
                    .add(destinationUri)
            }
            val uniqueBaseNames = baseNameCandidates.mapNotNull { (name, values) ->
                values.singleOrNull()?.let { name to it }
            }.toMap()

            MigratedCovers(
                destinationDirectory = destinationDirectory,
                exactPaths = exactPaths,
                uniqueBaseNames = uniqueBaseNames,
                fileCount = sourceFiles.size,
            )
        } catch (error: Throwable) {
            destinationDirectory.deleteRecursively()
            throw error
        }
    }

    private fun rewriteCoverUrls(
        snapshot: LegacyLibrarySnapshot,
        migratedCovers: MigratedCovers,
    ): LegacyLibrarySnapshot {
        fun rewrite(value: String?): String? = LegacyCoverPathMapper.rewrite(
            original = value,
            exactPaths = migratedCovers.exactPaths,
            uniqueBaseNames = migratedCovers.uniqueBaseNames,
        )

        return snapshot.copy(
            animes = snapshot.animes.map { it.copy(coverUrl = rewrite(it.coverUrl)) },
            series = snapshot.series.map { it.copy(customCoverUrl = rewrite(it.customCoverUrl)) },
            characters = snapshot.characters.map { it.copy(imageUrl = rewrite(it.imageUrl)) },
            characterGroups = snapshot.characterGroups.map { it.copy(coverUrl = rewrite(it.coverUrl)) },
        )
    }

    private suspend fun replaceLibrary(snapshot: LegacyLibrarySnapshot) {
        val dao = database.libraryDao()
        database.withTransaction {
            dao.clearCharacterGroupWorks()
            dao.clearCharacterGroupCharacters()
            dao.clearCharacterGroups()
            dao.clearCharacterTagLinks()
            dao.clearCharacterTags()
            dao.clearCharacterRelations()
            dao.clearAnimeCharacters()
            dao.clearCharacters()
            dao.clearAnimeAnalysisRecords()
            dao.clearWatchRecords()
            dao.clearAnimeTags()
            dao.clearTags()
            dao.clearAnimes()
            dao.clearSeries()
            dao.clearWatchStatuses()

            dao.insertWatchStatuses(snapshot.watchStatuses)
            if (snapshot.series.isNotEmpty()) dao.insertSeries(snapshot.series)
            if (snapshot.animes.isNotEmpty()) dao.insertAnimes(snapshot.animes)
            if (snapshot.tags.isNotEmpty()) dao.insertTags(snapshot.tags)
            if (snapshot.animeTags.isNotEmpty()) dao.insertAnimeTags(snapshot.animeTags)
            if (snapshot.watchRecords.isNotEmpty()) dao.insertWatchRecords(snapshot.watchRecords)
            if (snapshot.animeAnalysisRecords.isNotEmpty()) {
                dao.insertAnimeAnalysisRecords(snapshot.animeAnalysisRecords)
            }
            if (snapshot.characters.isNotEmpty()) dao.insertCharacters(snapshot.characters)
            if (snapshot.animeCharacters.isNotEmpty()) {
                dao.insertAnimeCharacters(snapshot.animeCharacters)
            }
            if (snapshot.characterRelations.isNotEmpty()) {
                dao.insertCharacterRelations(snapshot.characterRelations)
            }
            if (snapshot.characterTags.isNotEmpty()) dao.insertCharacterTags(snapshot.characterTags)
            if (snapshot.characterTagLinks.isNotEmpty()) {
                dao.insertCharacterTagLinks(snapshot.characterTagLinks)
            }
            if (snapshot.characterGroups.isNotEmpty()) {
                dao.insertCharacterGroups(snapshot.characterGroups)
            }
            if (snapshot.characterGroupCharacters.isNotEmpty()) {
                dao.insertCharacterGroupCharacters(snapshot.characterGroupCharacters)
            }
            if (snapshot.characterGroupWorks.isNotEmpty()) {
                dao.insertCharacterGroupWorks(snapshot.characterGroupWorks)
            }
        }
    }

    private fun cleanupOldImportedCoverDirectories(activeDirectory: File?) {
        val root = File(appContext.filesDir, IMPORTED_COVERS_ROOT)
        root.listFiles()?.filter { candidate ->
            candidate.isDirectory && candidate != activeDirectory && candidate.name.startsWith("legacy_")
        }?.forEach(File::deleteRecursively)
    }

    private fun safeChild(root: File, relativePath: String): File {
        val target = File(root, relativePath)
        val rootPath = root.canonicalFile.path + File.separator
        require(target.canonicalFile.path.startsWith(rootPath)) {
            "备份包含不安全的文件路径"
        }
        return target
    }

    private fun File.hasZipHeader(): Boolean {
        val header = readHeader(4)
        return header.size >= 4 &&
            header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            header[2] in listOf(0x03.toByte(), 0x05.toByte(), 0x07.toByte()) &&
            header[3] in listOf(0x04.toByte(), 0x06.toByte(), 0x08.toByte())
    }

    private fun File.hasSqliteHeader(): Boolean =
        readHeader(SQLITE_HEADER.size).contentEquals(SQLITE_HEADER)

    private fun File.readHeader(size: Int): ByteArray = FileInputStream(this).use { input ->
        val buffer = ByteArray(size)
        val count = input.read(buffer)
        if (count <= 0) ByteArray(0) else buffer.copyOf(count)
    }

    private fun File.regularFiles(): List<File> =
        if (!exists()) emptyList() else walkTopDown().filter(File::isFile).toList()

    private fun copyWithLimit(
        input: InputStream,
        output: OutputStream,
        limit: Long,
        errorMessage: String,
    ): Long {
        val bufferedOutput = if (output is BufferedOutputStream) output else BufferedOutputStream(output)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { errorMessage }
            bufferedOutput.write(buffer, 0, read)
        }
        bufferedOutput.flush()
        return total
    }

    private data class ExtractedLegacyFiles(
        val databaseFile: File,
        val coversDirectory: File?,
    )

    private data class MigratedCovers(
        val destinationDirectory: File?,
        val exactPaths: Map<String, String>,
        val uniqueBaseNames: Map<String, String>,
        val fileCount: Int,
    ) {
        companion object {
            fun empty() = MigratedCovers(
                destinationDirectory = null,
                exactPaths = emptyMap(),
                uniqueBaseNames = emptyMap(),
                fileCount = 0,
            )
        }
    }

    private companion object {
        const val STAGING_ROOT = "legacy_import"
        const val IMPORTED_COVERS_ROOT = "covers"
        const val LEGACY_DATABASE_NAME = "anime_tracker_v5.db"
        const val MAX_KNOWN_LEGACY_SCHEMA = 19
        const val MAX_ZIP_ENTRIES = 20_000
        const val MAX_SOURCE_BYTES = 1_073_741_824L
        const val MAX_DATABASE_BYTES = 268_435_456L
        const val MAX_COVER_BYTES = 41_943_040L
        const val MAX_ALL_COVERS_BYTES = 1_073_741_824L
        val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()
        const val TAG = "LegacyBackupImporter"
    }
}
