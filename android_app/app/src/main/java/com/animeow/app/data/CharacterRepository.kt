package com.animeow.app.data

import android.content.Context
import androidx.room.withTransaction
import com.animeow.app.data.local.AnimeCharacterEntity
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.AniMeowDatabase
import com.animeow.app.data.local.CharacterEntity
import com.animeow.app.data.local.CharacterGroupCharacterEntity
import com.animeow.app.data.local.CharacterGroupEntity
import com.animeow.app.data.local.CharacterGroupMember
import com.animeow.app.data.local.CharacterGroupSummary
import com.animeow.app.data.local.CharacterGroupWork
import com.animeow.app.data.local.CharacterGroupWorkEntity
import com.animeow.app.data.local.CharacterListItem
import com.animeow.app.data.local.CharacterRelationEntity
import com.animeow.app.data.local.CharacterRelationItem
import com.animeow.app.data.local.CharacterTagEntity
import com.animeow.app.data.local.CharacterTagLinkEntity
import com.animeow.app.data.local.CharacterTagSummary
import com.animeow.app.data.local.CharacterWithRole
import com.animeow.app.data.local.CharacterWorkItem
import com.animeow.app.data.local.normalizeSubjectType
import com.animeow.app.data.remote.BangumiService
import com.animeow.app.data.remote.RemoteCharacter
import com.animeow.app.data.remote.CharacterGroupCommunityService
import com.animeow.app.data.remote.CommunityCharacterGroup
import com.animeow.app.data.remote.CommunityCharacterGroupPackage
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.util.runCatchingCancellable
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

data class CharacterGroupImportResult(
    val groupId: Long,
    val characterCount: Int,
    val workCount: Int,
    val createdCharacterCount: Int,
    val createdWorkCount: Int,
)

data class CharacterGroupImportPreview(
    val characterCount: Int,
    val workCount: Int,
    val createdCharacterCount: Int,
    val reusedCharacterCount: Int,
    val createdWorkCount: Int,
    val reusedWorkCount: Int,
    val updatesExistingGroup: Boolean,
)

data class CharacterWorkLink(
    val animeId: Long,
    val roleName: String? = null,
)

data class CharacterRelationWorkDiff(
    val targetWorksMissingFromSource: List<CharacterWorkItem>,
    val sourceWorksMissingFromTarget: List<CharacterWorkItem>,
)

class CharacterRepository(
    private val database: AniMeowDatabase,
    context: Context? = null,
) {
    private val dao = database.libraryDao()
    private val bangumi = BangumiService(context = context)
    private val community = CharacterGroupCommunityService()

    val isCommunityConfigured: Boolean get() = community.isConfigured

    fun observeCharacters(): Flow<List<CharacterListItem>> = dao.observeCharacterItems()

    fun observeAnimes(): Flow<List<AnimeEntity>> = dao.observeAnimes()

    fun observeCharacter(characterId: Long): Flow<CharacterListItem?> =
        dao.observeCharacterItem(characterId)

    fun observeCharacterTags(): Flow<List<CharacterTagEntity>> = dao.observeCharacterTags()

    fun observeCharacterTagLinks(): Flow<List<CharacterTagLinkEntity>> =
        dao.observeCharacterTagLinks()

    fun observeCharacterTagSummaries(): Flow<List<CharacterTagSummary>> =
        dao.observeCharacterTagSummaries()

    fun observeTagsForCharacter(characterId: Long): Flow<List<CharacterTagEntity>> =
        dao.observeTagsForCharacter(characterId)

    fun observeCharactersForAnime(animeId: Long): Flow<List<CharacterWithRole>> =
        dao.observeCharactersForAnime(animeId)

    fun observeWorksForCharacter(characterId: Long): Flow<List<CharacterWorkItem>> =
        dao.observeWorksForCharacter(characterId)

    fun observeRelationsForCharacter(characterId: Long): Flow<List<CharacterRelationItem>> =
        dao.observeRelationsForCharacter(characterId)

    fun observeGroups(): Flow<List<CharacterGroupSummary>> =
        dao.observeCharacterGroupSummaries()

    fun observeGroup(groupId: Long): Flow<CharacterGroupEntity?> = dao.observeCharacterGroup(groupId)

    fun observeGroupMembers(groupId: Long): Flow<List<CharacterGroupMember>> =
        dao.observeCharacterGroupMembers(groupId)

    fun observeGroupWorks(groupId: Long): Flow<List<CharacterGroupWork>> =
        dao.observeCharacterGroupWorks(groupId)

    suspend fun searchBangumi(query: String): List<RemoteCharacter> =
        bangumi.searchCharacters(query)

    suspend fun importBangumi(character: RemoteCharacter): Long {
        val detailed = runCatchingCancellable { bangumi.character(character.id) }.getOrNull() ?: character
        return upsertRemoteCharacter(detailed)
    }

    suspend fun upsertRemoteCharacter(remote: RemoteCharacter): Long = database.withTransaction {
        val now = Instant.now().toString()
        val existing = dao.getCharacterByBgmId(remote.id)
        if (existing == null) {
            dao.insertCharacter(
                CharacterEntity(
                    bgmId = remote.id,
                    name = remote.name,
                    nameCn = remote.nameCn,
                    imageUrl = remote.imageUrl,
                    summary = remote.summary,
                    gender = remote.gender,
                    birthYear = remote.birthYear,
                    birthMonth = remote.birthMonth,
                    birthDay = remote.birthDay,
                    bloodType = remote.bloodType,
                    infoboxJson = remote.infoboxJson,
                    updatedAt = now,
                ),
            )
        } else {
            dao.updateCharacter(
                existing.copy(
                    name = remote.name.ifBlank { existing.name },
                    nameCn = remote.nameCn ?: existing.nameCn,
                    imageUrl = remote.imageUrl ?: existing.imageUrl,
                    summary = remote.summary ?: existing.summary,
                    gender = remote.gender ?: existing.gender,
                    birthYear = remote.birthYear ?: existing.birthYear,
                    birthMonth = remote.birthMonth ?: existing.birthMonth,
                    birthDay = remote.birthDay ?: existing.birthDay,
                    bloodType = remote.bloodType ?: existing.bloodType,
                    infoboxJson = remote.infoboxJson.takeUnless { it == "[]" } ?: existing.infoboxJson,
                    updatedAt = now,
                ),
            )
            existing.id
        }
    }

    suspend fun saveManualCharacter(character: CharacterEntity): Long = database.withTransaction {
        val normalized = character.copy(
            name = character.name.trim().ifBlank { "未命名角色" },
            nameCn = character.nameCn?.trim()?.takeIf(String::isNotBlank),
            summary = character.summary?.trim()?.takeIf(String::isNotBlank),
            review = character.review?.trim()?.takeIf(String::isNotBlank),
            rating = character.rating?.coerceIn(1, 10),
            updatedAt = Instant.now().toString(),
        )
        if (normalized.id == 0L) dao.insertCharacter(normalized)
        else {
            dao.updateCharacter(normalized)
            normalized.id
        }
    }

    suspend fun updatePersonalReview(characterId: Long, rating: Int?, review: String?) {
        val item = dao.getCharacter(characterId) ?: return
        dao.updateCharacter(
            item.copy(
                rating = rating?.coerceIn(1, 10),
                review = review?.trim()?.takeIf(String::isNotBlank),
                updatedAt = Instant.now().toString(),
            ),
        )
    }

    suspend fun deleteCharacter(characterId: Long) = database.withTransaction {
        dao.clearAnimeCharactersForCharacter(characterId)
        dao.clearCharacterTagLinksForCharacter(characterId)
        dao.clearCharacterRelationsForCharacter(characterId)
        dao.clearCharacterGroupLinksForCharacter(characterId)
        dao.deleteCharacter(characterId)
    }

    suspend fun createTag(name: String): Long = database.withTransaction {
        createTagUnchecked(name)
    }

    suspend fun createAndAttachTag(
        characterId: Long,
        name: String,
        currentTagIds: Set<Long>,
    ): Long = database.withTransaction {
        val tagId = createTagUnchecked(name)
        replaceCharacterTags(characterId, currentTagIds + tagId)
        tagId
    }

    private suspend fun createTagUnchecked(name: String): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "标签名称不能为空" }
        dao.getCharacterTagByName(clean)?.let { return it.id }
        return dao.insertCharacterTag(
            CharacterTagEntity(
                name = clean,
                color = DEFAULT_CHARACTER_TAG_COLOR,
                createdAt = Instant.now().toString(),
            ),
        )
    }

    suspend fun setCharacterTags(characterId: Long, tagIds: Set<Long>) = database.withTransaction {
        replaceCharacterTags(characterId, tagIds)
    }

    private suspend fun replaceCharacterTags(characterId: Long, tagIds: Set<Long>) {
        dao.clearTagsForCharacter(characterId)
        tagIds.forEach { tagId ->
            dao.insertCharacterTagLink(CharacterTagLinkEntity(characterId, tagId))
        }
    }

    suspend fun deleteTag(tagId: Long) = database.withTransaction {
        dao.clearCharacterTagLinksForTag(tagId)
        dao.deleteCharacterTag(tagId)
    }

    suspend fun getLinkableWorks(characterId: Long): List<AnimeEntity> =
        dao.getLinkableWorks(characterId)

    suspend fun getWorksMissingFromCharacter(
        fromCharacterId: Long,
        missingFromCharacterId: Long,
    ): List<CharacterWorkItem> = dao.getWorksMissingFromCharacter(fromCharacterId, missingFromCharacterId)

    suspend fun linkWork(characterId: Long, animeId: Long, roleName: String?) =
        linkWorks(characterId, listOf(CharacterWorkLink(animeId, roleName)))

    suspend fun linkWorks(characterId: Long, works: Collection<CharacterWorkLink>) = database.withTransaction {
        insertWorkLinks(characterId, works)
    }

    suspend fun syncRelationWorks(
        sourceCharacterId: Long,
        targetCharacterId: Long,
        targetWorksToSource: Collection<CharacterWorkLink>,
        sourceWorksToTarget: Collection<CharacterWorkLink>,
    ) = database.withTransaction {
        insertWorkLinks(sourceCharacterId, targetWorksToSource)
        insertWorkLinks(targetCharacterId, sourceWorksToTarget)
    }

    suspend fun unlinkWork(characterId: Long, animeId: Long) {
        dao.deleteAnimeCharacter(animeId, characterId)
    }

    private suspend fun insertWorkLinks(characterId: Long, works: Collection<CharacterWorkLink>) {
        val normalized = works.distinctBy(CharacterWorkLink::animeId)
        if (normalized.isEmpty()) return
        val baseOrder = stableSortOrder().toLong()
        dao.insertAnimeCharacters(
            normalized.mapIndexed { index, work ->
                AnimeCharacterEntity(
                    animeId = work.animeId,
                    characterId = characterId,
                    roleName = work.roleName?.trim()?.takeIf(String::isNotBlank),
                    sortOrder = (baseOrder + index).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
            },
        )
    }

    suspend fun upsertRelation(
        sourceCharacterId: Long,
        targetCharacterId: Long,
        relationType: String,
        note: String?,
        strength: Int,
    ): Long = database.withTransaction {
        upsertRelationUnchecked(sourceCharacterId, targetCharacterId, relationType, note, strength)
    }

    suspend fun upsertRelationAndFindWorkDiff(
        sourceCharacterId: Long,
        targetCharacterId: Long,
        relationType: String,
        note: String?,
        strength: Int,
    ): CharacterRelationWorkDiff = database.withTransaction {
        upsertRelationUnchecked(sourceCharacterId, targetCharacterId, relationType, note, strength)
        CharacterRelationWorkDiff(
            targetWorksMissingFromSource = dao.getWorksMissingFromCharacter(
                targetCharacterId,
                sourceCharacterId,
            ),
            sourceWorksMissingFromTarget = dao.getWorksMissingFromCharacter(
                sourceCharacterId,
                targetCharacterId,
            ),
        )
    }

    private suspend fun upsertRelationUnchecked(
        sourceCharacterId: Long,
        targetCharacterId: Long,
        relationType: String,
        note: String?,
        strength: Int,
    ): Long {
        require(sourceCharacterId != targetCharacterId) { "不能关联角色自身" }
        val firstId = minOf(sourceCharacterId, targetCharacterId)
        val secondId = maxOf(sourceCharacterId, targetCharacterId)
        val now = Instant.now().toString()
        val existing = dao.getCharacterRelation(firstId, secondId)
        val normalizedType = relationType.trim().ifBlank { "关联" }
        return if (existing == null) {
            dao.insertCharacterRelation(
                CharacterRelationEntity(
                    sourceCharacterId = firstId,
                    targetCharacterId = secondId,
                    relationType = normalizedType,
                    note = note?.trim()?.takeIf(String::isNotBlank),
                    strength = strength.coerceIn(1, 5),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        } else {
            dao.updateCharacterRelation(
                existing.copy(
                    relationType = normalizedType,
                    note = note?.trim()?.takeIf(String::isNotBlank),
                    strength = strength.coerceIn(1, 5),
                    updatedAt = now,
                ),
            )
            existing.id
        }
    }

    suspend fun deleteRelation(relationId: Long) {
        dao.deleteCharacterRelation(relationId)
    }

    suspend fun findDuplicates(): List<List<CharacterListItem>> =
        findDuplicateCharacterGroups(dao.getCharacterItems())

    suspend fun mergeDuplicates(keepId: Long, sourceIds: List<Long>) = database.withTransaction {
        val normalizedSources = sourceIds.distinct().filter { it != keepId }
        if (normalizedSources.isEmpty()) return@withTransaction
        val target = dao.getCharacter(keepId) ?: error("要保留的角色不存在")
        val sources = dao.getCharactersByIds(normalizedSources)
        require(sources.isNotEmpty()) { "没有可合并的重复角色" }

        dao.copyWorksToCharacter(keepId, normalizedSources)
        dao.copyTagsToCharacter(keepId, normalizedSources)
        dao.copyGroupsToCharacter(keepId, normalizedSources)

        val relations = dao.getRelationsForCharacters(normalizedSources)
        relations.forEach { relation ->
            val sourceWasMerged = relation.sourceCharacterId in normalizedSources
            val targetWasMerged = relation.targetCharacterId in normalizedSources
            val otherId = when {
                sourceWasMerged && !targetWasMerged -> relation.targetCharacterId
                targetWasMerged && !sourceWasMerged -> relation.sourceCharacterId
                else -> null
            }
            if (otherId != null && otherId != keepId) {
                upsertRelation(
                    sourceCharacterId = keepId,
                    targetCharacterId = otherId,
                    relationType = relation.relationType,
                    note = relation.note,
                    strength = relation.strength,
                )
            }
        }

        normalizedSources.forEach { sourceId ->
            dao.clearAnimeCharactersForCharacter(sourceId)
            dao.clearCharacterTagLinksForCharacter(sourceId)
            dao.clearCharacterRelationsForCharacter(sourceId)
            dao.clearCharacterGroupLinksForCharacter(sourceId)
            dao.deleteCharacter(sourceId)
        }
        dao.updateCharacter(mergeCharacterMetadata(target, sources))
    }

    suspend fun saveGroup(
        group: CharacterGroupEntity,
        characterIds: List<Long>,
        animeIds: List<Long>,
    ): Long = database.withTransaction {
        val now = Instant.now().toString()
        val normalized = group.copy(
            name = group.name.trim().ifBlank { "未命名角色组" },
            description = group.description?.trim()?.takeIf(String::isNotBlank),
            updatedAt = now,
            createdAt = group.createdAt ?: now,
        )
        val groupId = if (normalized.id == 0L) dao.insertCharacterGroup(normalized)
        else {
            dao.updateCharacterGroup(normalized)
            normalized.id
        }
        dao.clearCharactersForGroup(groupId)
        dao.clearWorksForGroup(groupId)
        characterIds.distinct().forEachIndexed { index, characterId ->
            dao.insertCharacterGroupCharacter(
                CharacterGroupCharacterEntity(groupId, characterId, sortOrder = index),
            )
        }
        animeIds.distinct().forEachIndexed { index, animeId ->
            dao.insertCharacterGroupWork(
                CharacterGroupWorkEntity(groupId, animeId, sortOrder = index),
            )
        }
        groupId
    }

    suspend fun deleteGroup(groupId: Long) = database.withTransaction {
        dao.clearCharactersForGroup(groupId)
        dao.clearWorksForGroup(groupId)
        dao.deleteCharacterGroup(groupId)
    }

    suspend fun exportGroupPackage(groupId: Long): String = database.withTransaction {
        val group = dao.getCharacterGroup(groupId) ?: error("角色组不存在")
        val members = dao.getCharacterGroupMembers(groupId)
        val works = dao.getCharacterGroupWorks(groupId)
        JSONObject()
            .put("schema", CHARACTER_GROUP_SCHEMA)
            .put(
                "group",
                JSONObject()
                    .put("name", group.name)
                    .putOptional("description", group.description)
                    .putOptional("cover_url", group.coverUrl)
                    .putOptional("community_id", group.communityId)
                    .putOptional("share_code", group.shareCode)
                    .put("source", group.source),
            )
            .put(
                "characters",
                JSONArray().apply {
                    members.forEach { member ->
                        val character = member.character
                        put(
                            JSONObject()
                                .putOptional("bgm_id", character.bgmId)
                                .put("name", character.name)
                                .putOptional("name_cn", character.nameCn)
                                .putOptional("image_url", character.imageUrl)
                                .putOptional("summary", character.summary)
                                .putOptional("gender", character.gender)
                                .putOptional("birth_year", character.birthYear)
                                .putOptional("birth_mon", character.birthMonth)
                                .putOptional("birth_day", character.birthDay)
                                .putOptional("blood_type", character.bloodType)
                                .putOptional("infobox_json", character.infoboxJson)
                                .putOptional("role_name", member.roleName)
                                .put("sort_order", member.memberSortOrder),
                        )
                    }
                },
            )
            .put(
                "works",
                JSONArray().apply {
                    works.forEach { member ->
                        val anime = member.anime
                        put(
                            JSONObject()
                                .put("title", anime.title)
                                .putOptional("cover_url", anime.coverUrl)
                                .putOptional("original_title", anime.originalTitle)
                                .putOptional("summary", anime.synopsis)
                                .put("subject_type", anime.subjectType)
                                .putOptional("external_source", anime.externalSource)
                                .putOptional("external_id", anime.externalId)
                                .putOptional("external_url", anime.externalUrl)
                                .putOptional("media_format", anime.mediaFormat)
                                .put("sort_order", member.memberSortOrder),
                        )
                    }
                },
            )
            .toString(2)
    }

    suspend fun importGroupPackage(rawJson: String): CharacterGroupImportResult = database.withTransaction {
        val root = JSONObject(rawJson)
        val payload = root.optJSONObject("payload") ?: root
        val groupJson = payload.optJSONObject("group") ?: payload
        val schema = payload.optText("schema") ?: CHARACTER_GROUP_SCHEMA
        require(schema == CHARACTER_GROUP_SCHEMA) { "不支持的角色组格式：$schema" }
        val characterRows = payload.optArray("characters", "character_snapshots")
        val workRows = payload.optArray("works", "work_snapshots", "subjects")
        require(characterRows.length() > 0 || workRows.length() > 0) { "角色组包没有成员数据" }

        val knownCharacters = dao.getCharacterItems().map(CharacterListItem::character).toMutableList()
        val importedCharacterIds = mutableListOf<Pair<Long, JSONObject>>()
        var createdCharacters = 0
        for (index in 0 until characterRows.length()) {
            val row = characterRows.optJSONObject(index) ?: continue
            val bgmId = row.optNullableLong("bgm_id", "bgmId")
            val name = row.optText("name", "original_name") ?: continue
            val nameCn = row.optText("name_cn", "nameCn")
            val snapshot = CharacterEntity(
                bgmId = bgmId,
                name = name,
                nameCn = nameCn,
                imageUrl = row.optText("image_url", "imageUrl"),
                summary = row.optText("summary"),
                gender = row.optText("gender"),
                birthYear = row.optNullableInt("birth_year", "birthYear"),
                birthMonth = row.optNullableInt("birth_mon", "birthMonth"),
                birthDay = row.optNullableInt("birth_day", "birthDay"),
                bloodType = row.optText("blood_type", "bloodType"),
                infoboxJson = row.optText("infobox_json", "infoboxJson"),
                updatedAt = Instant.now().toString(),
            )
            val existing = knownCharacters.firstOrNull { character ->
                (bgmId != null && character.bgmId == bgmId) ||
                    (!nameCn.isNullOrBlank() && normalizeCharacterName(character.nameCn) == normalizeCharacterName(nameCn)) ||
                    normalizeCharacterName(character.name) == normalizeCharacterName(name)
            }
            val characterId = if (existing == null) {
                createdCharacters += 1
                dao.insertCharacter(snapshot).also { knownCharacters += snapshot.copy(id = it) }
            } else {
                val merged = mergeCharacterMetadata(existing, listOf(snapshot))
                dao.updateCharacter(merged)
                knownCharacters.replaceAll { if (it.id == existing.id) merged else it }
                existing.id
            }
            importedCharacterIds += characterId to row
        }

        val knownWorks = dao.getActiveAnimes().toMutableList()
        val importedWorkIds = mutableListOf<Pair<Long, JSONObject>>()
        var createdWorks = 0
        for (index in 0 until workRows.length()) {
            val row = workRows.optJSONObject(index) ?: continue
            val title = row.optText(
                "title", "name_cn", "nameCn", "name", "name_original", "original_name",
            ) ?: continue
            val source = row.optText("external_source", "externalSource")
            val externalId = row.optText("external_id", "externalId", "subject_id", "subjectId")
            val rawSubjectType = row.optText("subject_type", "subjectType")
            val subjectType = normalizeSubjectType(rawSubjectType)
            val externalMatch = if (source != null && externalId != null) {
                dao.getAnimeByExternalIdIncludingDeleted(source, externalId)
            } else null
            val existing = externalMatch ?: knownWorks.firstOrNull { anime ->
                normalizeSubjectType(anime.subjectType) == subjectType &&
                    normalizedPackageTitle(anime.title) == normalizedPackageTitle(title)
            }
            val totalEpisodes = row.optNullableInt("total_episodes", "totalEpisodes", "eps") ?: 0
            val snapshot = AnimeEntity(
                title = title,
                coverUrl = row.optText("cover_url", "coverUrl", "image_url", "imageUrl"),
                status = "未看",
                createdAt = row.optText("created_at", "createdAt") ?: Instant.now().toString(),
                airDate = row.optText("air_date", "airDate", "date"),
                studio = row.optText("studio"),
                totalEpisodes = totalEpisodes.coerceAtLeast(0),
                tvEpisodes = (row.optNullableInt("tv_episodes", "tvEpisodes") ?: totalEpisodes).coerceAtLeast(0),
                spEpisodes = (row.optNullableInt("sp_episodes", "spEpisodes") ?: 0).coerceAtLeast(0),
                originalTitle = row.optText("original_title", "originalTitle"),
                synopsis = row.optText("summary", "synopsis"),
                subjectType = subjectType,
                externalSource = source,
                externalId = externalId,
                externalUrl = row.optText("external_url", "externalUrl"),
                mediaFormat = row.optText("media_format", "mediaFormat"),
            )
            val animeId = if (existing == null) {
                createdWorks += 1
                dao.insertAnime(snapshot).also { knownWorks += snapshot.copy(id = it) }
            } else {
                val merged = mergeAnimeMetadata(existing, listOf(snapshot)).copy(deletedAt = null)
                dao.updateAnime(merged)
                knownWorks.replaceAll { if (it.id == existing.id) merged else it }
                existing.id
            }
            importedWorkIds += animeId to row
        }

        val now = Instant.now().toString()
        val communityId = groupJson.optText("community_id", "communityId")
        val existingGroup = communityId?.let { dao.getCharacterGroupByCommunityId(it) }
        val groupSnapshot = (existingGroup ?: CharacterGroupEntity(
            name = groupJson.optText("name", "title") ?: "未命名角色组",
        )).copy(
            name = groupJson.optText("name", "title") ?: existingGroup?.name ?: "未命名角色组",
            description = groupJson.optText("description"),
            coverUrl = groupJson.optText("cover_url", "coverUrl"),
            communityId = communityId,
            shareCode = groupJson.optText("share_code", "shareCode"),
            source = groupJson.optText("source") ?: "import",
            isPublic = (groupJson.optText("source") ?: "import") == "community",
            extraJson = JSONObject().put("schema", schema).toString(),
            createdAt = existingGroup?.createdAt ?: now,
            updatedAt = now,
        )
        val groupId = if (existingGroup == null) dao.insertCharacterGroup(groupSnapshot)
        else {
            dao.updateCharacterGroup(groupSnapshot)
            existingGroup.id
        }
        dao.clearCharactersForGroup(groupId)
        dao.clearWorksForGroup(groupId)
        importedCharacterIds.distinctBy(Pair<Long, JSONObject>::first).forEachIndexed { index, (id, row) ->
            dao.insertCharacterGroupCharacter(
                CharacterGroupCharacterEntity(
                    groupId = groupId,
                    characterId = id,
                    roleName = row.optText("role_name", "roleName"),
                    sortOrder = row.optNullableInt("sort_order", "sortOrder") ?: index,
                ),
            )
        }
        importedWorkIds.distinctBy(Pair<Long, JSONObject>::first).forEachIndexed { index, (id, row) ->
            dao.insertCharacterGroupWork(
                CharacterGroupWorkEntity(
                    groupId = groupId,
                    animeId = id,
                    sortOrder = row.optNullableInt("sort_order", "sortOrder") ?: index,
                ),
            )
        }
        CharacterGroupImportResult(
            groupId = groupId,
            characterCount = importedCharacterIds.distinctBy(Pair<Long, JSONObject>::first).size,
            workCount = importedWorkIds.distinctBy(Pair<Long, JSONObject>::first).size,
            createdCharacterCount = createdCharacters,
            createdWorkCount = createdWorks,
        )
    }

    suspend fun previewGroupPackage(rawJson: String): CharacterGroupImportPreview = database.withTransaction {
        val root = JSONObject(rawJson)
        val payload = root.optJSONObject("payload") ?: root
        val groupJson = payload.optJSONObject("group") ?: payload
        val schema = payload.optText("schema") ?: CHARACTER_GROUP_SCHEMA
        require(schema == CHARACTER_GROUP_SCHEMA) { "不支持的角色组格式：$schema" }
        val characterRows = payload.optArray("characters", "character_snapshots")
        val workRows = payload.optArray("works", "work_snapshots", "subjects")
        require(characterRows.length() > 0 || workRows.length() > 0) { "角色组包没有成员数据" }

        val knownCharacters = dao.getCharacterItems().map(CharacterListItem::character).toMutableList()
        val previewCharacterIds = linkedSetOf<Long>()
        var nextCharacterId = -1L
        for (index in 0 until characterRows.length()) {
            val row = characterRows.optJSONObject(index) ?: continue
            val bgmId = row.optNullableLong("bgm_id", "bgmId")
            val name = row.optText("name", "original_name") ?: continue
            val nameCn = row.optText("name_cn", "nameCn")
            val existing = knownCharacters.firstOrNull { character ->
                (bgmId != null && character.bgmId == bgmId) ||
                    (!nameCn.isNullOrBlank() && normalizeCharacterName(character.nameCn) == normalizeCharacterName(nameCn)) ||
                    normalizeCharacterName(character.name) == normalizeCharacterName(name)
            }
            val id = existing?.id ?: (nextCharacterId--).also {
                knownCharacters += CharacterEntity(id = it, bgmId = bgmId, name = name, nameCn = nameCn)
            }
            previewCharacterIds += id
        }

        val knownWorks = dao.getActiveAnimes().toMutableList()
        val previewWorkIds = linkedSetOf<Long>()
        var nextWorkId = -1L
        for (index in 0 until workRows.length()) {
            val row = workRows.optJSONObject(index) ?: continue
            val title = row.optText(
                "title", "name_cn", "nameCn", "name", "name_original", "original_name",
            ) ?: continue
            val source = row.optText("external_source", "externalSource")
            val externalId = row.optText("external_id", "externalId", "subject_id", "subjectId")
            val subjectType = normalizeSubjectType(row.optText("subject_type", "subjectType"))
            val externalMatch = if (source != null && externalId != null) {
                dao.getAnimeByExternalIdIncludingDeleted(source, externalId)
            } else null
            val existing = externalMatch ?: knownWorks.firstOrNull { anime ->
                normalizeSubjectType(anime.subjectType) == subjectType &&
                    normalizedPackageTitle(anime.title) == normalizedPackageTitle(title)
            }
            val id = existing?.id ?: (nextWorkId--).also {
                knownWorks += AnimeEntity(
                    id = it,
                    title = title,
                    subjectType = subjectType,
                    externalSource = source,
                    externalId = externalId,
                )
            }
            previewWorkIds += id
        }

        val communityId = groupJson.optText("community_id", "communityId")
        CharacterGroupImportPreview(
            characterCount = previewCharacterIds.size,
            workCount = previewWorkIds.size,
            createdCharacterCount = previewCharacterIds.count { it < 0 },
            reusedCharacterCount = previewCharacterIds.count { it > 0 },
            createdWorkCount = previewWorkIds.count { it < 0 },
            reusedWorkCount = previewWorkIds.count { it > 0 },
            updatesExistingGroup = communityId?.let { dao.getCharacterGroupByCommunityId(it) } != null,
        )
    }

    suspend fun listCommunityGroups(query: String? = null): List<CommunityCharacterGroup> =
        community.list(query)

    suspend fun fetchCommunityGroup(id: String): CommunityCharacterGroupPackage =
        community.fetch(id)

    suspend fun fetchCommunityGroupByShareCode(code: String): CommunityCharacterGroupPackage =
        community.fetchByShareCode(code)

    suspend fun importCommunityGroup(id: String): CharacterGroupImportResult =
        importGroupPackage(community.fetch(id).payloadJson)

    suspend fun importCommunityShareCode(code: String): CharacterGroupImportResult =
        importGroupPackage(community.fetchByShareCode(code).payloadJson)

    suspend fun listMyPublishedGroups(session: CloudSession): List<CommunityCharacterGroup> =
        community.listMine(session)

    suspend fun publishOrUpdateGroup(
        groupId: Long,
        session: CloudSession,
        isPublic: Boolean,
    ): CommunityCharacterGroup {
        val current = dao.getCharacterGroup(groupId) ?: error("角色组不存在")
        val payload = exportGroupPackage(groupId)
        val currentCommunityId = current.communityId?.takeIf(String::isNotBlank)
        val ownedCommunityIds = community.listMine(session).mapTo(hashSetOf(), CommunityCharacterGroup::id)
        val published = if (currentCommunityId != null && currentCommunityId in ownedCommunityIds) {
            community.update(session, currentCommunityId, payload, isPublic)
        } else {
            community.publish(session, payload, isPublic)
        }
        database.withTransaction {
            val latest = dao.getCharacterGroup(groupId) ?: error("角色组已被删除")
            dao.updateCharacterGroup(
                latest.copy(
                    communityId = published.id,
                    shareCode = published.shareCode,
                    source = "owned_community",
                    isPublic = published.isPublic,
                    updatedAt = Instant.now().toString(),
                ),
            )
        }
        return published
    }

    suspend fun deletePublishedGroup(groupId: Long, session: CloudSession) {
        val current = dao.getCharacterGroup(groupId) ?: error("角色组不存在")
        val communityId = current.communityId?.takeIf(String::isNotBlank)
            ?: error("这个角色组尚未发布")
        require(community.listMine(session).any { it.id == communityId }) {
            "当前账号不是这个云端角色组的发布者；你可以保留本机收藏，或发布为自己的副本"
        }
        community.delete(session, communityId)
        database.withTransaction {
            val latest = dao.getCharacterGroup(groupId) ?: return@withTransaction
            dao.updateCharacterGroup(
                latest.copy(
                    communityId = null,
                    shareCode = null,
                    source = "local",
                    isPublic = false,
                    updatedAt = Instant.now().toString(),
                ),
            )
        }
    }

    private companion object {
        const val DEFAULT_CHARACTER_TAG_COLOR = 0xFF6750A4L
        const val CHARACTER_GROUP_SCHEMA = "anime_tracker.character_group.v1"

        fun stableSortOrder(): Int = (System.currentTimeMillis() / 1_000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }
}

internal fun findDuplicateCharacterGroups(
    items: List<CharacterListItem>,
): List<List<CharacterListItem>> = items
    .groupBy { item ->
        val character = item.character
        when {
            character.bgmId != null -> "bgm:${character.bgmId}"
            !character.nameCn.isNullOrBlank() -> "cn:${normalizeCharacterName(character.nameCn)}"
            else -> "name:${normalizeCharacterName(character.name)}"
        }
    }
    .values
    .filter { it.size > 1 }
    .sortedBy { group -> group.first().character.nameCn ?: group.first().character.name }

private fun normalizeCharacterName(value: String?): String = value.orEmpty()
    .trim()
    .lowercase(java.util.Locale.ROOT)
    .replace(Regex("[\\s　]+"), "")

internal fun mergeCharacterMetadata(
    target: CharacterEntity,
    sources: List<CharacterEntity>,
): CharacterEntity {
    val all = listOf(target) + sources
    fun firstText(selector: (CharacterEntity) -> String?): String? =
        all.asSequence().mapNotNull(selector).firstOrNull { it.isNotBlank() }
    fun longestText(selector: (CharacterEntity) -> String?): String? =
        all.mapNotNull(selector).filter(String::isNotBlank).maxByOrNull(String::length)
    return target.copy(
        bgmId = target.bgmId ?: all.firstNotNullOfOrNull(CharacterEntity::bgmId),
        nameCn = firstText(CharacterEntity::nameCn),
        imageUrl = firstText(CharacterEntity::imageUrl),
        summary = longestText(CharacterEntity::summary),
        gender = firstText(CharacterEntity::gender),
        birthYear = target.birthYear ?: all.firstNotNullOfOrNull(CharacterEntity::birthYear),
        birthMonth = target.birthMonth ?: all.firstNotNullOfOrNull(CharacterEntity::birthMonth),
        birthDay = target.birthDay ?: all.firstNotNullOfOrNull(CharacterEntity::birthDay),
        bloodType = firstText(CharacterEntity::bloodType),
        infoboxJson = all.mapNotNull(CharacterEntity::infoboxJson)
            .firstOrNull { it.isNotBlank() && it != "[]" },
        rating = target.rating ?: all.mapNotNull(CharacterEntity::rating).maxOrNull(),
        review = longestText(CharacterEntity::review),
        updatedAt = Instant.now().toString(),
    )
}

private fun JSONObject.putOptional(key: String, value: Any?): JSONObject = apply {
    if (value != null) put(key, value)
}

private fun JSONObject.optText(vararg keys: String): String? {
    keys.forEach { key ->
        val value = opt(key)
        if (value != null && value != JSONObject.NULL) {
            value.toString().trim().takeIf(String::isNotEmpty)?.let { return it }
        }
    }
    return null
}

private fun JSONObject.optNullableLong(vararg keys: String): Long? =
    optText(*keys)?.toLongOrNull()?.takeIf { it > 0 }

private fun JSONObject.optNullableInt(vararg keys: String): Int? =
    optText(*keys)?.toIntOrNull()

private fun JSONObject.optArray(vararg keys: String): JSONArray {
    keys.forEach { key -> optJSONArray(key)?.let { return it } }
    return JSONArray()
}

private fun normalizedPackageTitle(value: String): String = value
    .trim()
    .lowercase(java.util.Locale.ROOT)
    .replace(Regex("[\\s　]+"), "")
