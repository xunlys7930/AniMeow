package com.animeow.app.data

import com.animeow.app.util.runCatchingCancellable
import com.animeow.app.data.remote.ServerCatalogService
import com.animeow.app.data.diagnostics.OperationLogService
import com.animeow.app.data.local.LibraryDao

internal class PublicCoverSync(
    private val dao: LibraryDao,
    private val operationLog: OperationLogService?,
) {
    private val serverCatalog = ServerCatalogService()

    suspend fun syncCoverUrlToServer(title: String, coverUrl: String): Boolean {
        val normalizedTitle = title.trim()
        val normalizedUrl = coverUrl.trim()
        if (normalizedTitle.isEmpty() ||
            (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) ||
            !serverCatalog.isConfigured
        ) {
            return false
        }
        return runCatchingCancellable { serverCatalog.updateCover(normalizedTitle, normalizedUrl) }
            .onSuccess {
                operationLog?.record(
                    "自动同步封面至公共资料库",
                    "作品编辑",
                    mapOf("title" to normalizedTitle),
                )
            }
            .isSuccess
    }

    suspend fun syncCoverUrlsToServer(animeIds: Set<Long>): CoverSyncSummary {
        if (animeIds.isEmpty()) return CoverSyncSummary(0, 0, 0)
        var success = 0
        var skipped = 0
        var failed = 0
        dao.getAnimes(animeIds.toList()).forEach { anime ->
            val coverUrl = anime.coverUrl?.trim().orEmpty()
            if (!coverUrl.startsWith("http://") && !coverUrl.startsWith("https://")) {
                skipped += 1
            } else {
                runCatchingCancellable { serverCatalog.updateCover(anime.title, coverUrl) }
                    .onSuccess { success += 1 }
                    .onFailure { failed += 1 }
            }
        }
        operationLog?.record(
            "批量同步封面至公共资料库",
            "首页",
            mapOf("count" to animeIds.size, "success" to success, "skipped" to skipped, "failed" to failed),
        )
        return CoverSyncSummary(success, skipped, failed)
    }

}
