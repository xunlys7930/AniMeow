package com.animeow.app.data.maintenance

import android.content.Context
import coil.imageLoader
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CacheCategory(
    val id: String,
    val label: String,
    val description: String,
    val sizeBytes: Long,
    internal val paths: List<File>,
)

data class CacheSnapshot(
    val categories: List<CacheCategory>,
    val persistentDataBytes: Long,
) {
    val totalCacheBytes: Long get() = categories.sumOf(CacheCategory::sizeBytes)
}

data class CacheClearResult(
    val clearedBytes: Long,
    val failedPaths: Int,
)

@OptIn(coil.annotation.ExperimentalCoilApi::class)
internal class AppCacheManager(context: Context) {
    private val appContext = context.applicationContext

    suspend fun scan(): CacheSnapshot = withContext(Dispatchers.IO) {
        val internalRoot = appContext.cacheDir
        val internal = internalRoot.listFiles().orEmpty().groupBy(::categoryId)
            .map { (id, paths) -> category(id, paths, internalRoot) }
        val externalRoot = appContext.externalCacheDir
        val external = externalRoot?.takeIf(File::exists)?.let { root ->
            listOf(
                CacheCategory(
                    id = EXTERNAL_ID,
                    label = "外部缓存",
                    description = "系统分配的外部临时文件",
                    sizeBytes = safeSize(root, root),
                    paths = listOf(root),
                ),
            )
        }.orEmpty()
        CacheSnapshot(
            categories = (internal + external).sortedByDescending(CacheCategory::sizeBytes),
            persistentDataBytes = persistentDataSize(),
        )
    }

    suspend fun clear(ids: Set<String>): CacheClearResult = withContext(Dispatchers.IO) {
        val before = scan().categories.filter { it.id in ids }.sumOf(CacheCategory::sizeBytes)
        var failed = 0
        if (IMAGE_ID in ids) {
            runCatching { appContext.imageLoader.memoryCache?.clear() }
            runCatching { appContext.imageLoader.diskCache?.clear() }
        }
        scan().categories.filter { it.id in ids }.flatMap(CacheCategory::paths).forEach { path ->
            val allowedRoot = if (path == appContext.externalCacheDir) appContext.externalCacheDir else appContext.cacheDir
            if (allowedRoot == null || !isInside(path, allowedRoot)) {
                failed += 1
            } else if (path == allowedRoot) {
                path.listFiles().orEmpty().forEach { if (!it.deleteRecursively()) failed += 1 }
            } else if (path.exists() && !path.deleteRecursively()) {
                failed += 1
            }
        }
        val after = scan().categories.filter { it.id in ids }.sumOf(CacheCategory::sizeBytes)
        CacheClearResult(clearedBytes = (before - after).coerceAtLeast(0L), failedPaths = failed)
    }

    private fun category(id: String, paths: List<File>, root: File): CacheCategory {
        val (label, description) = when (id) {
            IMAGE_ID -> "图片缓存" to "在线封面与角色图片，可随时重新加载"
            TRANSFER_ID -> "导入与恢复临时文件" to "已完成或中断的数据迁移工作区"
            CLOUD_ID -> "云同步与分享临时文件" to "上传、恢复和分享时生成的临时副本"
            UPDATE_ID -> "更新安装包" to "应用更新下载产生的临时文件"
            else -> "其他缓存" to "网络响应、组件和系统临时文件"
        }
        return CacheCategory(
            id = id,
            label = label,
            description = description,
            sizeBytes = paths.sumOf { safeSize(it, root) },
            paths = paths,
        )
    }

    private fun categoryId(file: File): String {
        val name = file.name.lowercase(java.util.Locale.ROOT)
        return when {
            name.contains("image") || name.contains("coil") -> IMAGE_ID
            name.contains("restore") || name.contains("import") -> TRANSFER_ID
            name == "shared" || name.contains("cloud") || name.contains("share") -> CLOUD_ID
            name.contains("update") || name.endsWith(".apk") -> UPDATE_ID
            else -> OTHER_ID
        }
    }

    private fun persistentDataSize(): Long {
        val covers = File(appContext.filesDir, "covers")
        val database = appContext.getDatabasePath("animeow.db")
        return safeSize(covers, appContext.filesDir) +
            listOf(database, File(database.path + "-wal"), File(database.path + "-shm"))
                .filter(File::exists)
                .sumOf(File::length)
    }

    private fun safeSize(file: File, root: File): Long {
        if (!file.exists() || !isInside(file, root)) return 0L
        if (file.isFile) return file.length()
        return file.listFiles().orEmpty().sumOf { child -> safeSize(child, root) }
    }

    private fun isInside(file: File, root: File): Boolean = runCatching {
        val rootPath = root.canonicalFile.path
        val targetPath = file.canonicalFile.path
        targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
    }.getOrDefault(false)

    private companion object {
        const val IMAGE_ID = "images"
        const val TRANSFER_ID = "transfer"
        const val CLOUD_ID = "cloud"
        const val UPDATE_ID = "updates"
        const val OTHER_ID = "other"
        const val EXTERNAL_ID = "external"
    }
}
