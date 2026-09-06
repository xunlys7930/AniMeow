package com.animeow.app.ui.character

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.animeow.app.AniMeowApplication
import com.animeow.app.data.CharacterGroupImportPreview
import com.animeow.app.data.CharacterWorkLink
import com.animeow.app.data.local.AnimeEntity
import com.animeow.app.data.local.CharacterEntity
import com.animeow.app.data.local.CharacterGroupEntity
import com.animeow.app.data.local.CharacterGroupMember
import com.animeow.app.data.local.CharacterGroupSummary
import com.animeow.app.data.local.CharacterGroupWork
import com.animeow.app.data.local.CharacterListItem
import com.animeow.app.data.local.CharacterRelationItem
import com.animeow.app.data.local.CharacterTagEntity
import com.animeow.app.data.local.CharacterTagLinkEntity
import com.animeow.app.data.local.CharacterWorkItem
import com.animeow.app.data.remote.RemoteCharacter
import com.animeow.app.data.remote.CommunityCharacterGroup
import com.animeow.app.data.remote.CommunityCharacterGroupPackage
import com.animeow.app.data.remote.normalizeCharacterGroupShareCode
import com.animeow.app.data.cloud.CloudSession
import com.animeow.app.data.cloud.CloudSessionExpiredException
import com.animeow.app.data.cloud.SecureCloudSessionStore
import com.animeow.app.util.runCatchingCancellable
import java.time.Instant
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RemoteCharacterSearchState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<RemoteCharacter> = emptyList(),
    val error: String? = null,
)

data class CharacterToolsState(
    val isScanningDuplicates: Boolean = false,
    val duplicateGroups: List<List<CharacterListItem>> = emptyList(),
    val linkableWorks: List<AnimeEntity> = emptyList(),
    val isLoadingWorks: Boolean = false,
)

data class CharacterCommunityState(
    val configured: Boolean = false,
    val query: String = "",
    val isLoading: Boolean = false,
    val groups: List<CommunityCharacterGroup> = emptyList(),
    val selected: CommunityCharacterGroupPackage? = null,
    val importPreview: CharacterGroupImportPreview? = null,
    val isImporting: Boolean = false,
    val error: String? = null,
)

data class CharacterGroupPublishingState(
    val session: CloudSession? = null,
    val busyGroupId: Long? = null,
    val ownedCommunityIds: Set<String> = emptySet(),
    val ownershipLoading: Boolean = false,
)

data class RelationWorkSyncState(
    val sourceCharacterId: Long,
    val targetCharacterId: Long,
    val targetWorksMissingFromSource: List<CharacterWorkItem>,
    val sourceWorksMissingFromTarget: List<CharacterWorkItem>,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CharacterManagementViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as AniMeowApplication).characterRepository
    private val cloudSessionStore = SecureCloudSessionStore(application)
    private val query = MutableStateFlow("")
    private val selectedId = MutableStateFlow<Long?>(null)
    private val selectedGroupId = MutableStateFlow<Long?>(null)
    val allCharacterItems = repository.observeCharacters().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    private val allTags = repository.observeCharacterTags().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )
    private val allTagLinks = repository.observeCharacterTagLinks().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val characters: StateFlow<List<CharacterListItem>> = combine(
        allCharacterItems,
        allTags,
        allTagLinks,
        query,
    ) { characters, tags, links, rawQuery ->
        filterCharacters(characters, tags, links, rawQuery)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tags: StateFlow<List<CharacterTagEntity>> = allTags
    val selectedCharacterId: StateFlow<Long?> = selectedId

    val selectedCharacter: StateFlow<CharacterListItem?> = selectedId
        .flatMapLatest { id -> id?.let(repository::observeCharacter) ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val selectedTags: StateFlow<List<CharacterTagEntity>> = selectedId
        .flatMapLatest { id -> id?.let(repository::observeTagsForCharacter) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedWorks: StateFlow<List<CharacterWorkItem>> = selectedId
        .flatMapLatest { id -> id?.let(repository::observeWorksForCharacter) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedRelations: StateFlow<List<CharacterRelationItem>> = selectedId
        .flatMapLatest { id -> id?.let(repository::observeRelationsForCharacter) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val groups: StateFlow<List<CharacterGroupSummary>> = repository.observeGroups().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val allAnimes: StateFlow<List<AnimeEntity>> = repository.observeAnimes().stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    val selectedGroup: StateFlow<CharacterGroupEntity?> = selectedGroupId
        .flatMapLatest { id -> id?.let(repository::observeGroup) ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val selectedGroupMembers: StateFlow<List<CharacterGroupMember>> = selectedGroupId
        .flatMapLatest { id -> id?.let(repository::observeGroupMembers) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedGroupWorks: StateFlow<List<CharacterGroupWork>> = selectedGroupId
        .flatMapLatest { id -> id?.let(repository::observeGroupWorks) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _remoteSearch = MutableStateFlow(RemoteCharacterSearchState())
    val remoteSearch: StateFlow<RemoteCharacterSearchState> = _remoteSearch

    private val _tools = MutableStateFlow(CharacterToolsState())
    val tools: StateFlow<CharacterToolsState> = _tools

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: Flow<String> = _events.asSharedFlow()

    private val _community = MutableStateFlow(
        CharacterCommunityState(configured = repository.isCommunityConfigured),
    )
    val community: StateFlow<CharacterCommunityState> = _community

    private val _groupPublishing = MutableStateFlow(
        CharacterGroupPublishingState(session = cloudSessionStore.load()),
    )
    val groupPublishing: StateFlow<CharacterGroupPublishingState> = _groupPublishing

    private val _relationWorkSync = MutableStateFlow<RelationWorkSyncState?>(null)
    val relationWorkSync: StateFlow<RelationWorkSyncState?> = _relationWorkSync
    private var mutationInFlight = false

    init {
        viewModelScope.launch {
            allCharacterItems.collect { items ->
                val current = selectedId.value
                if (current == null || items.none { it.character.id == current }) {
                    selectedId.value = items.firstOrNull()?.character?.id
                }
            }
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun selectCharacter(characterId: Long?) {
        selectedId.value = characterId
    }

    fun selectGroup(groupId: Long?) {
        selectedGroupId.value = groupId
    }

    fun refreshCloudSession() {
        val session = cloudSessionStore.load()
        _groupPublishing.value = _groupPublishing.value.copy(
            session = session,
            ownedCommunityIds = if (session == null) emptySet() else _groupPublishing.value.ownedCommunityIds,
            ownershipLoading = session != null,
        )
        if (session == null) return
        viewModelScope.launch {
            runCatchingCancellable { repository.listMyPublishedGroups(session).mapTo(linkedSetOf(), CommunityCharacterGroup::id) }
                .onSuccess { ownedIds ->
                    _groupPublishing.value = _groupPublishing.value.copy(
                        session = session,
                        ownedCommunityIds = ownedIds,
                        ownershipLoading = false,
                    )
                }
                .onFailure { error ->
                    _groupPublishing.value = _groupPublishing.value.copy(ownershipLoading = false)
                    reportPublishingFailure(error, "无法确认角色组发布权限")
                }
        }
    }

    fun searchRemote(value: String) {
        val clean = value.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            _remoteSearch.value = RemoteCharacterSearchState(query = clean, isSearching = true)
            runCatchingCancellable { repository.searchBangumi(clean) }
                .onSuccess { results ->
                    _remoteSearch.value = RemoteCharacterSearchState(query = clean, results = results)
                }
                .onFailure { error ->
                    _remoteSearch.value = RemoteCharacterSearchState(
                        query = clean,
                        error = error.message ?: "Bangumi 角色搜索失败",
                    )
                }
        }
    }

    fun clearRemoteSearch() {
        _remoteSearch.value = RemoteCharacterSearchState()
    }

    fun importRemote(character: RemoteCharacter) = launchOperation("已添加角色：${character.displayName}") {
        val id = repository.importBangumi(character)
        selectedId.value = id
        clearRemoteSearch()
    }

    fun saveManual(character: CharacterEntity) = launchOperation("角色资料已保存") {
        selectedId.value = repository.saveManualCharacter(character)
    }

    fun updateReview(rating: Int?, review: String?) {
        val id = selectedId.value ?: return
        launchOperation("角色评价已保存") {
            repository.updatePersonalReview(id, rating, review)
        }
    }

    fun setTags(tagIds: Set<Long>) {
        val id = selectedId.value ?: return
        launchOperation("角色标签已保存") { repository.setCharacterTags(id, tagIds) }
    }

    fun createAndAttachTag(name: String, currentTagIds: Set<Long>) {
        val id = selectedId.value ?: return
        launchOperation("已创建并添加标签") {
            repository.createAndAttachTag(id, name, currentTagIds)
        }
    }

    fun prepareLinkWorks() {
        val id = selectedId.value ?: return
        viewModelScope.launch {
            _tools.value = _tools.value.copy(isLoadingWorks = true)
            runCatchingCancellable { repository.getLinkableWorks(id) }
                .onSuccess { works ->
                    _tools.value = _tools.value.copy(linkableWorks = works, isLoadingWorks = false)
                }
                .onFailure { error ->
                    _tools.value = _tools.value.copy(isLoadingWorks = false)
                    _events.emit(error.message ?: "可关联作品加载失败")
                }
        }
    }

    fun linkWorks(animeIds: Set<Long>, roleName: String?) {
        val id = selectedId.value ?: return
        launchOperation("已关联 ${animeIds.size} 部作品") {
            repository.linkWorks(id, animeIds.map { animeId -> CharacterWorkLink(animeId, roleName) })
            _tools.value = _tools.value.copy(linkableWorks = emptyList())
        }
    }

    fun unlinkWork(animeId: Long) {
        val id = selectedId.value ?: return
        launchOperation("已移除作品关联") { repository.unlinkWork(id, animeId) }
    }

    fun saveRelation(targetCharacterId: Long, type: String, note: String?, strength: Int) {
        val sourceId = selectedId.value ?: return
        launchOperation("角色关系已保存") {
            val diff = repository.upsertRelationAndFindWorkDiff(
                sourceCharacterId = sourceId,
                targetCharacterId = targetCharacterId,
                relationType = type,
                note = note,
                strength = strength,
            )
            if (diff.targetWorksMissingFromSource.isNotEmpty() || diff.sourceWorksMissingFromTarget.isNotEmpty()) {
                _relationWorkSync.value = RelationWorkSyncState(
                    sourceCharacterId = sourceId,
                    targetCharacterId = targetCharacterId,
                    targetWorksMissingFromSource = diff.targetWorksMissingFromSource,
                    sourceWorksMissingFromTarget = diff.sourceWorksMissingFromTarget,
                )
            }
        }
    }

    fun syncRelationWorks(
        targetWorksToSource: Set<Long>,
        sourceWorksToTarget: Set<Long>,
    ) {
        val state = _relationWorkSync.value ?: return
        launchOperation("关系作品关联已同步") {
            repository.syncRelationWorks(
                sourceCharacterId = state.sourceCharacterId,
                targetCharacterId = state.targetCharacterId,
                targetWorksToSource = state.targetWorksMissingFromSource
                    .filter { it.anime.id in targetWorksToSource }
                    .map { CharacterWorkLink(it.anime.id, it.roleName) },
                sourceWorksToTarget = state.sourceWorksMissingFromTarget
                    .filter { it.anime.id in sourceWorksToTarget }
                    .map { CharacterWorkLink(it.anime.id, it.roleName) },
            )
            _relationWorkSync.value = null
        }
    }

    fun dismissRelationWorkSync() {
        _relationWorkSync.value = null
    }

    fun deleteRelation(relationId: Long) = launchOperation("角色关系已删除") {
        repository.deleteRelation(relationId)
    }

    fun scanDuplicates() {
        viewModelScope.launch {
            _tools.value = _tools.value.copy(isScanningDuplicates = true)
            runCatchingCancellable { repository.findDuplicates() }
                .onSuccess { groups ->
                    _tools.value = _tools.value.copy(
                        isScanningDuplicates = false,
                        duplicateGroups = groups,
                    )
                    if (groups.isEmpty()) _events.emit("没有发现重复角色")
                }
                .onFailure { error ->
                    _tools.value = _tools.value.copy(isScanningDuplicates = false)
                    _events.emit(error.message ?: "角色查重失败")
                }
        }
    }

    fun dismissDuplicates() {
        _tools.value = _tools.value.copy(duplicateGroups = emptyList())
    }

    fun mergeDuplicateGroup(keepId: Long, sourceIds: List<Long>) {
        launchOperation("重复角色已合并") {
            repository.mergeDuplicates(keepId, sourceIds)
            selectedId.value = keepId
            _tools.value = _tools.value.copy(duplicateGroups = emptyList())
            scanDuplicates()
        }
    }

    fun deleteCharacter(characterId: Long? = selectedId.value) {
        val targetId = characterId ?: return
        launchOperation("角色已永久删除") {
            repository.deleteCharacter(targetId)
            if (selectedId.value == targetId) selectedId.value = null
            scanDuplicates()
        }
    }

    fun saveGroup(
        existing: CharacterGroupEntity?,
        name: String,
        description: String?,
        characterIds: List<Long>,
        animeIds: List<Long>,
    ) = launchOperation("角色组已保存") {
        repository.saveGroup(
            group = (existing ?: CharacterGroupEntity(
                name = name,
                createdAt = Instant.now().toString(),
            )).copy(
                name = name,
                description = description,
            ),
            characterIds = characterIds,
            animeIds = animeIds,
        )
    }

    fun deleteGroup(groupId: Long) = launchOperation("角色组已删除") {
        repository.deleteGroup(groupId)
    }

    fun publishGroup(groupId: Long, isPublic: Boolean) {
        val session = cloudSessionStore.load()
        _groupPublishing.value = _groupPublishing.value.copy(session = session)
        if (session == null) {
            _events.tryEmit("请先在“云账号与设备备份”中登录")
            return
        }
        viewModelScope.launch {
            if (_groupPublishing.value.busyGroupId != null) return@launch
            _groupPublishing.value = _groupPublishing.value.copy(busyGroupId = groupId)
            runCatchingCancellable { repository.publishOrUpdateGroup(groupId, session, isPublic) }
                .onSuccess { published ->
                    _groupPublishing.value = _groupPublishing.value.copy(
                        ownedCommunityIds = _groupPublishing.value.ownedCommunityIds + published.id,
                    )
                    _events.emit(
                        if (published.isPublic) {
                            "角色组已发布到社区，分享码 ${published.shareCode.orEmpty()}"
                        } else {
                            "角色组已更新为仅分享码可见"
                        },
                    )
                }
                .onFailure { error -> reportPublishingFailure(error, "角色组发布失败") }
            _groupPublishing.value = _groupPublishing.value.copy(busyGroupId = null)
        }
    }

    fun deletePublishedGroup(groupId: Long) {
        val session = cloudSessionStore.load()
        _groupPublishing.value = _groupPublishing.value.copy(session = session)
        if (session == null) {
            _events.tryEmit("请先登录发布该角色组的云账号")
            return
        }
        viewModelScope.launch {
            if (_groupPublishing.value.busyGroupId != null) return@launch
            _groupPublishing.value = _groupPublishing.value.copy(busyGroupId = groupId)
            val removedCommunityId = selectedGroup.value?.takeIf { it.id == groupId }?.communityId
            runCatchingCancellable { repository.deletePublishedGroup(groupId, session) }
                .onSuccess {
                    _groupPublishing.value = _groupPublishing.value.copy(
                        ownedCommunityIds = removedCommunityId?.let { _groupPublishing.value.ownedCommunityIds - it }
                            ?: _groupPublishing.value.ownedCommunityIds,
                    )
                    _events.emit("云端角色组已删除，本机收藏仍保留")
                }
                .onFailure { error -> reportPublishingFailure(error, "删除云端角色组失败") }
            _groupPublishing.value = _groupPublishing.value.copy(busyGroupId = null)
        }
    }

    fun exportGroup(uri: Uri, groupId: Long) = launchOperation("角色组收藏包已导出") {
        val json = repository.exportGroupPackage(groupId)
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "w")?.use { output ->
                output.writer(Charsets.UTF_8).use { it.write(json) }
            } ?: error("无法写入所选位置")
        }
    }

    fun importGroup(uri: Uri) = launchMutation {
        val result = runCatchingCancellable {
            val json = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_GROUP_PACKAGE_BYTES) { "角色组收藏包过大" }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray().toString(Charsets.UTF_8)
                } ?: error("无法读取所选文件")
            }
            repository.importGroupPackage(json)
        }.getOrThrow()
        selectedGroupId.value = result.groupId
        "已导入 ${result.characterCount} 个角色、${result.workCount} 部作品" +
            "（新建 ${result.createdCharacterCount} 个角色、${result.createdWorkCount} 部作品）"
    }

    fun loadCommunity(query: String = _community.value.query) {
        if (!_community.value.configured) {
            _community.value = _community.value.copy(
                query = query,
                isLoading = false,
                error = "当前构建尚未配置角色组社区服务",
            )
            return
        }
        viewModelScope.launch {
            _community.value = _community.value.copy(query = query, isLoading = true, error = null)
            runCatchingCancellable { repository.listCommunityGroups(query) }
                .onSuccess { groups ->
                    _community.value = _community.value.copy(groups = groups, isLoading = false)
                }
                .onFailure { error ->
                    _community.value = _community.value.copy(
                        isLoading = false,
                        error = error.message ?: "社区角色组加载失败",
                    )
                }
        }
    }

    fun openCommunityGroup(id: String) {
        viewModelScope.launch {
            _community.value = _community.value.copy(isLoading = true, error = null)
            runCatchingCancellable {
                val detail = repository.fetchCommunityGroup(id)
                detail to repository.previewGroupPackage(detail.payloadJson)
            }
                .onSuccess { (detail, preview) ->
                    _community.value = _community.value.copy(
                        selected = detail,
                        importPreview = preview,
                        isLoading = false,
                    )
                }
                .onFailure { error ->
                    _community.value = _community.value.copy(
                        isLoading = false,
                        error = error.message ?: "社区角色组详情加载失败",
                    )
                }
        }
    }

    fun closeCommunityGroup() {
        if (_community.value.isImporting) return
        _community.value = _community.value.copy(selected = null, importPreview = null, error = null)
    }

    fun importSelectedCommunityGroup() {
        val detail = _community.value.selected ?: return
        if (_community.value.isImporting) return
        viewModelScope.launch {
            _community.value = _community.value.copy(isImporting = true, error = null)
            runCatchingCancellable { repository.importGroupPackage(detail.payloadJson) }
                .onSuccess { result ->
                    selectedGroupId.value = result.groupId
                    _community.value = _community.value.copy(
                        selected = null,
                        importPreview = null,
                        isImporting = false,
                    )
                    _events.emit(
                        "已事务导入 ${result.characterCount} 个角色、${result.workCount} 部作品" +
                            "（新建 ${result.createdCharacterCount} 个角色、${result.createdWorkCount} 部作品）",
                    )
                }
                .onFailure { error ->
                    _community.value = _community.value.copy(isImporting = false)
                    _events.emit(error.message ?: "社区角色组导入失败，本地数据未改变")
                }
        }
    }

    fun previewCommunityShareCode(rawCode: String) {
        val code = normalizeCharacterGroupShareCode(rawCode)
        if (code == null) {
            _events.tryEmit("请输入有效的 8 位角色组分享码")
            return
        }
        viewModelScope.launch {
            _community.value = _community.value.copy(isLoading = true, error = null)
            runCatchingCancellable {
                val detail = repository.fetchCommunityGroupByShareCode(code)
                detail to repository.previewGroupPackage(detail.payloadJson)
            }
                .onSuccess { (detail, preview) ->
                    _community.value = _community.value.copy(
                        selected = detail,
                        importPreview = preview,
                        isLoading = false,
                    )
                }
                .onFailure { error ->
                    _community.value = _community.value.copy(
                        isLoading = false,
                        error = error.message ?: "分享码角色组加载失败",
                    )
                }
        }
    }

    private fun launchOperation(success: String, block: suspend () -> Unit) {
        launchMutation {
            block()
            success
        }
    }

    private fun launchMutation(block: suspend () -> String) {
        if (mutationInFlight) {
            _events.tryEmit("请等待当前角色操作完成")
            return
        }
        mutationInFlight = true
        viewModelScope.launch {
            try {
                _events.emit(block())
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                _events.emit(error.message ?: "操作失败")
            } finally {
                mutationInFlight = false
            }
        }
    }

    private suspend fun reportPublishingFailure(error: Throwable, fallback: String) {
        if (error is CloudSessionExpiredException) {
            cloudSessionStore.clear()
            _groupPublishing.value = _groupPublishing.value.copy(
                session = null,
                busyGroupId = null,
                ownedCommunityIds = emptySet(),
                ownershipLoading = false,
            )
        }
        _events.emit(error.message ?: fallback)
    }

    private companion object {
        const val MAX_GROUP_PACKAGE_BYTES = 10 * 1024 * 1024
    }
}

internal fun filterCharacters(
    characters: List<CharacterListItem>,
    tags: List<CharacterTagEntity>,
    links: List<CharacterTagLinkEntity>,
    rawQuery: String,
): List<CharacterListItem> {
    val query = rawQuery.trim().lowercase(java.util.Locale.ROOT)
    if (query.isEmpty()) return characters
    val tagNames = tags.associate { it.id to it.name.lowercase(java.util.Locale.ROOT) }
    val tagsByCharacter = links.groupBy(CharacterTagLinkEntity::characterId)
    return characters.filter { item ->
        val character = item.character
        character.name.lowercase(java.util.Locale.ROOT).contains(query) ||
            character.nameCn.orEmpty().lowercase(java.util.Locale.ROOT).contains(query) ||
            tagsByCharacter[character.id].orEmpty().any { link ->
                tagNames[link.tagId].orEmpty().contains(query)
            }
    }
}
