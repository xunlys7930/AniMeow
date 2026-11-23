package com.animeow.app.data.importer

import com.animeow.app.data.local.AnimeAnalysisRecordEntity
import com.animeow.app.data.local.AnimeCharacterEntity
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AnimeTagEntity
import com.animeow.app.data.local.CharacterEntity
import com.animeow.app.data.local.CharacterGroupCharacterEntity
import com.animeow.app.data.local.CharacterGroupEntity
import com.animeow.app.data.local.CharacterGroupWorkEntity
import com.animeow.app.data.local.CharacterRelationEntity
import com.animeow.app.data.local.CharacterTagEntity
import com.animeow.app.data.local.CharacterTagLinkEntity
import com.animeow.app.data.local.SeriesEntity
import com.animeow.app.data.local.TagEntity
import com.animeow.app.data.local.WatchRecordEntity
import com.animeow.app.data.local.WatchStatusEntity

data class LegacyLibrarySnapshot(
    val sourceSchemaVersion: Int,
    val animes: List<AnimeEntity>,
    val tags: List<TagEntity>,
    val animeTags: List<AnimeTagEntity>,
    val series: List<SeriesEntity>,
    val watchStatuses: List<WatchStatusEntity>,
    val watchRecords: List<WatchRecordEntity>,
    val animeAnalysisRecords: List<AnimeAnalysisRecordEntity>,
    val characters: List<CharacterEntity>,
    val animeCharacters: List<AnimeCharacterEntity>,
    val characterRelations: List<CharacterRelationEntity>,
    val characterTags: List<CharacterTagEntity>,
    val characterTagLinks: List<CharacterTagLinkEntity>,
    val characterGroups: List<CharacterGroupEntity>,
    val characterGroupCharacters: List<CharacterGroupCharacterEntity>,
    val characterGroupWorks: List<CharacterGroupWorkEntity>,
)

data class LegacyImportSummary(
    val sourceName: String,
    val sourceSchemaVersion: Int,
    val animeCount: Int,
    val seriesCount: Int,
    val tagCount: Int,
    val watchRecordCount: Int,
    val characterCount: Int,
    val characterGroupCount: Int,
    val coverCount: Int,
    val warnings: List<String>,
) {
    val primaryRecordCount: Int
        get() = animeCount + characterCount
}

data class LegacyImportResult(
    val summary: LegacyImportSummary,
    val copiedCoverCount: Int,
    val restorePointCreated: Boolean,
    val reminderSyncSucceeded: Boolean = true,
)

internal data class PreparedLegacyImport(
    val stagingDirectory: java.io.File,
    val coversDirectory: java.io.File?,
    val snapshot: LegacyLibrarySnapshot,
    val summary: LegacyImportSummary,
    var restorePointCreated: Boolean = false,
)

sealed interface LegacyImportUiState {
    data object Idle : LegacyImportUiState

    data class Inspecting(
        val sourceName: String,
    ) : LegacyImportUiState

    data class Ready(
        val summary: LegacyImportSummary,
        val message: String? = null,
    ) : LegacyImportUiState

    data class Importing(
        val summary: LegacyImportSummary,
    ) : LegacyImportUiState

    data class Success(
        val result: LegacyImportResult,
    ) : LegacyImportUiState

    data class Error(
        val message: String,
    ) : LegacyImportUiState
}
