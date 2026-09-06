package com.animeow.app.data.local

import androidx.room.Embedded

data class CharacterListItem(
    @Embedded val character: CharacterEntity,
    val workCount: Int,
    val relationCount: Int,
    val tagCount: Int,
)

data class CharacterTagSummary(
    @Embedded val tag: CharacterTagEntity,
    val characterCount: Int,
)

data class CharacterWithRole(
    @Embedded val character: CharacterEntity,
    val roleName: String?,
    val memberSortOrder: Int,
)

data class CharacterWorkItem(
    @Embedded val anime: AnimeEntity,
    val roleName: String?,
    val memberSortOrder: Int,
)

data class CharacterRelationItem(
    @Embedded val relation: CharacterRelationEntity,
    val relatedCharacterId: Long,
    val relatedBgmId: Long?,
    val relatedName: String,
    val relatedNameCn: String?,
    val relatedImageUrl: String?,
    val relatedGender: String?,
    val relatedSummary: String?,
    val relatedRating: Int?,
) {
    val displayName: String
        get() = relatedNameCn?.takeIf(String::isNotBlank) ?: relatedName
}

data class CharacterGroupSummary(
    @Embedded val group: CharacterGroupEntity,
    val characterCount: Int,
    val workCount: Int,
)

data class CharacterGroupMember(
    @Embedded val character: CharacterEntity,
    val roleName: String?,
    val memberSortOrder: Int,
)

data class CharacterGroupWork(
    @Embedded val anime: AnimeEntity,
    val memberSortOrder: Int,
)
