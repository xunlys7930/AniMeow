package com.animeow.app.data.importer

import android.content.Context
import androidx.room.withTransaction
import com.animeow.app.data.BangumiImportCandidate
import com.animeow.app.data.BangumiImportConflictStrategy
import com.animeow.app.data.BangumiImportMatchKind
import com.animeow.app.data.BangumiImportSummary
import com.animeow.app.data.buildBangumiImportCandidates
import com.animeow.app.data.local.AnimeTagEntity
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.mergeBangumiCollection
import com.animeow.app.data.remote.BangumiCollectionFetchResult
import com.animeow.app.data.remote.BangumiService
import com.animeow.app.data.toAnimeEntity
import java.time.Instant
import java.util.Locale

data class BangumiCollectionPreview(
    val username: String,
    val candidates: List<BangumiImportCandidate>,
    val reportedTotal: Int,
    val truncated: Boolean,
) {
    val newCount: Int get() = candidates.count { it.matchKind == BangumiImportMatchKind.NEW }
    val duplicateCount: Int get() = candidates.size - newCount
    val trashedCount: Int get() = candidates.count { it.matchKind == BangumiImportMatchKind.TRASHED }
}

/**
 * Imports a public Bangumi collection into the local-first Room database.
 *
 * Network fetching is completed before a preview is returned. The actual write is one Room
 * transaction so a failed import never leaves a partially updated library.
 */
class BangumiCollectionImporter(
    private val database: AniMeowDatabase,
    context: Context? = null,
) {
    private val service = BangumiService(context = context)

    suspend fun preview(username: String): BangumiCollectionPreview {
        val cleanUsername = username.trim()
        val fetched: BangumiCollectionFetchResult = service.fetchUserAnimeCollections(cleanUsername)
        val existing = database.libraryDao().getAllAnimesIncludingDeleted()
        return BangumiCollectionPreview(
            username = cleanUsername,
            candidates = buildBangumiImportCandidates(fetched.items, existing),
            reportedTotal = fetched.reportedTotal,
            truncated = fetched.truncated,
        )
    }

    suspend fun applyImport(
        preview: BangumiCollectionPreview,
        strategy: BangumiImportConflictStrategy,
        importTags: Boolean = true,
    ): BangumiImportSummary = database.withTransaction {
        val dao = database.libraryDao()
        val existingTags = dao.getTags().associateBy { it.name.trim().lowercase(Locale.ROOT) }.toMutableMap()
        var added = 0
        var merged = 0
        var restored = 0
        var skipped = 0
        var createdTags = 0
        val now = Instant.now().toString()

        preview.candidates.forEach { candidate ->
            val animeId = when {
                candidate.matchKind == BangumiImportMatchKind.NEW -> {
                    added += 1
                    dao.insertAnime(candidate.item.toAnimeEntity(now))
                }

                strategy == BangumiImportConflictStrategy.SKIP -> {
                    skipped += 1
                    null
                }

                else -> {
                    val existingId = candidate.existingAnimeId
                    val existing = if (existingId != null) dao.getAnime(existingId) else null
                    if (existing == null) {
                        added += 1
                        dao.insertAnime(candidate.item.toAnimeEntity(now))
                    } else {
                        if (candidate.matchKind == BangumiImportMatchKind.TRASHED) restored += 1
                        else merged += 1
                        dao.updateAnime(mergeBangumiCollection(existing, candidate.item, now))
                        existing.id
                    }
                }
            }

            if (animeId != null && importTags) {
                val tagIds = candidate.item.tags.mapNotNull { rawName ->
                    val name = rawName.trim()
                    if (name.isEmpty()) return@mapNotNull null
                    val key = name.lowercase(Locale.ROOT)
                    existingTags[key]?.id ?: dao.insertTag(TagEntity(name = name)).also { id ->
                        existingTags[key] = TagEntity(id = id, name = name)
                        createdTags += 1
                    }
                }.distinct()
                if (tagIds.isNotEmpty()) {
                    dao.insertAnimeTags(tagIds.map { tagId -> AnimeTagEntity(animeId, tagId) })
                }
            }
        }

        BangumiImportSummary(
            addedCount = added,
            mergedCount = merged,
            restoredCount = restored,
            skippedCount = skipped,
            importedTagCount = createdTags,
        )
    }
}
