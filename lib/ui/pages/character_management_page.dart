import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:file_picker/file_picker.dart';

import 'package:anime_tracker/api/bangumi_service.dart';
import 'package:anime_tracker/models/bangumi_character.dart';
import 'package:anime_tracker/models/character_group_package.dart';
import 'package:anime_tracker/repositories/character_repository.dart';
import 'package:anime_tracker/services/character_group_community_service.dart';
import 'package:anime_tracker/services/cloud_account_service.dart';
import 'package:anime_tracker/services/service_locator.dart';
import 'package:anime_tracker/ui/components/anime_cover_image.dart';
import '../customization/page_display_config.dart';
import '../design_tokens.dart';
import 'character/character_display_config.dart';
import 'character/character_display_sheet.dart';
import 'community/character_community_page.dart';

class CharacterManagementPage extends StatefulWidget {
  const CharacterManagementPage({super.key});

  @override
  State<CharacterManagementPage> createState() =>
      _CharacterManagementPageState();
}

enum _CharacterToolAction { duplicates, groups, community, refresh }

class _CharacterManagementPageState extends State<CharacterManagementPage> {
  final CharacterRepository _repo = getIt<CharacterRepository>();
  final TextEditingController _searchController = TextEditingController();
  final PageDisplayController _displayController = PageDisplayController(
    pageId: 'characters',
    defaults: characterDefaults(),
    knownModules: characterModuleKeys,
    fallbackOrder: characterDefaultOrder,
  );

  List<Map<String, dynamic>> _characters = const [];
  List<Map<String, dynamic>> _works = const [];
  List<Map<String, dynamic>> _relations = const [];
  List<Map<String, dynamic>> _tags = const [];
  Map<String, dynamic>? _selected;
  bool _isLoading = true;
  bool _isDetailLoading = false;
  bool _showCompactDetail = false;

  @override
  void initState() {
    super.initState();
    _displayController.load();
    _loadCharacters();
  }

  @override
  void dispose() {
    _searchController.dispose();
    _displayController.dispose();
    super.dispose();
  }

  Future<void> _openDisplaySettings() async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      constraints: MediaQuery.sizeOf(context).width >= 840
          ? const BoxConstraints(maxWidth: 680)
          : null,
      builder: (_) => CharacterDisplaySheet(controller: _displayController),
    );
  }

  Future<void> _openCharacterCommunity() async {
    await Navigator.of(context).push<void>(
      MaterialPageRoute(builder: (_) => const CharacterCommunityPage()),
    );
  }

  Future<void> _loadCharacters({int? selectId}) async {
    setState(() => _isLoading = true);
    try {
      final rows = await _repo.getAllCharacters(
        query: _searchController.text.trim(),
      );
      final wantedId = selectId ?? _asInt(_selected?['id']);
      Map<String, dynamic>? selected;
      if (rows.isNotEmpty) {
        selected = rows.firstWhere(
          (row) => _asInt(row['id']) == wantedId,
          orElse: () => rows.first,
        );
      }

      if (!mounted) return;
      setState(() {
        _characters = rows;
        _selected = selected;
        _isLoading = false;
      });
      if (selected != null) await _loadCharacterDetails(selected);
    } catch (e) {
      if (!mounted) return;
      setState(() => _isLoading = false);
      _showSnack('角色列表加载失败：$e');
    }
  }

  Future<void> _openCharacterDetails(Map<String, dynamic> character) async {
    await _loadCharacterDetails(character);
    if (!mounted) return;
    setState(() => _showCompactDetail = true);
  }

  Future<void> _loadCharacterDetails(Map<String, dynamic> character) async {
    final id = _asInt(character['id']);
    if (id == null) return;

    setState(() {
      _selected = character;
      _isDetailLoading = true;
    });

    try {
      final latest = await _repo.getCharacterById(id) ?? character;
      final works = await _repo.getWorksByCharacterId(id);
      final relations = await _repo.getCharacterRelations(id);
      final tags = await _repo.getCharacterTags(id);
      if (!mounted) return;
      setState(() {
        _selected = latest;
        _works = works;
        _relations = relations;
        _tags = tags;
        _isDetailLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _isDetailLoading = false);
      _showSnack('角色详情加载失败：$e');
    }
  }

  Future<void> _addCharacterFromBangumi() async {
    final result = await showDialog<BangumiCharacter>(
      context: context,
      builder: (_) => const _AddCharacterDialog(),
    );
    if (result == null) return;

    try {
      final detail = await BangumiService.getCharacter(result.id) ?? result;
      final characterId = await _repo.upsertBangumiCharacter(detail);
      await _loadCharacters(selectId: characterId);
      _showSnack('已添加角色：${detail.displayName}');
    } catch (e) {
      _showSnack('添加角色失败：$e');
    }
  }

  Future<void> _findDuplicateCharacters() async {
    setState(() => _isLoading = true);
    try {
      final duplicates = await _repo.findDuplicateCharacters();
      if (!mounted) return;
      setState(() => _isLoading = false);

      if (duplicates.isEmpty) {
        _showSnack('没有找到重复的角色');
        return;
      }

      await showDialog<void>(
        context: context,
        builder: (_) => _DuplicateCharactersDialog(
          duplicateGroups: duplicates,
          onDelete: _handleDeleteDuplicate,
        ),
      );
      await _loadCharacters(selectId: _asInt(_selected?['id']));
    } catch (e) {
      if (!mounted) return;
      setState(() => _isLoading = false);
      _showSnack('查找重复角色失败：$e');
    }
  }

  Future<void> _handleDeleteDuplicate(int characterId) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('确认删除'),
        content: const Text('删除角色后，其关联的作品、关系、标签等数据也会被删除，此操作不可撤销。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            child: const Text('确认删除'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    try {
      await _repo.deleteCharacter(characterId);
      _showSnack('已删除角色');
    } catch (e) {
      _showSnack('删除角色失败：$e');
    }
  }

  Future<void> _deleteSelectedCharacter() async {
    final character = _selected;
    final characterId = _asInt(character?['id']);
    if (character == null || characterId == null) return;

    final name = _characterDisplayName(character);
    final workCount = _asInt(character['work_count']) ?? _works.length;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('永久删除角色？'),
        content: Text(
          '确定要删除「$name」吗？\n\n'
          '该角色将从 $workCount 部关联作品、角色关系、标签和角色组中一并移除，此操作不可撤销。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消'),
          ),
          FilledButton.icon(
            onPressed: () => Navigator.pop(ctx, true),
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            icon: const Icon(Icons.delete_forever_outlined),
            label: const Text('永久删除'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    try {
      await _repo.deleteCharacter(characterId);
      if (!mounted) return;
      setState(() {
        _selected = null;
        _works = const [];
        _relations = const [];
        _tags = const [];
        _showCompactDetail = false;
      });
      await _loadCharacters();
      _showSnack('已永久删除角色「$name」');
    } catch (e) {
      _showSnack('删除角色失败：$e');
    }
  }

  Future<void> _linkWork() async {
    final characterId = _asInt(_selected?['id']);
    if (characterId == null) return;

    final draft = await showDialog<_WorkLinkDraft>(
      context: context,
      builder: (_) => _LinkWorkDialog(characterId: characterId),
    );
    if (draft == null || draft.works.isEmpty) return;

    var linkedCount = 0;
    for (final work in draft.works) {
      final workId = _asInt(work['id']);
      if (workId == null) continue;
      await _repo.addCharacterToWork(
        workId: workId,
        characterId: characterId,
        roleName: draft.roleName,
      );
      linkedCount++;
    }
    if (linkedCount == 0) return;

    await _loadCharacters(selectId: characterId);
    _showSnack('已关联 $linkedCount 部作品');
  }

  Future<void> _removeWork(Map<String, dynamic> work) async {
    final characterId = _asInt(_selected?['id']);
    final workId = _asInt(work['id']);
    if (characterId == null || workId == null) return;

    await _repo.removeCharacterFromWork(
      workId: workId,
      characterId: characterId,
    );
    await _loadCharacters(selectId: characterId);
  }

  Future<void> _addRelation() async {
    final characterId = _asInt(_selected?['id']);
    if (characterId == null) return;

    final draft = await showDialog<_RelationDraft>(
      context: context,
      builder: (_) => _RelationDialog(sourceCharacterId: characterId),
    );
    if (draft == null) return;

    await _repo.upsertCharacterRelation(
      sourceCharacterId: characterId,
      targetCharacterId: draft.targetCharacterId,
      relationType: draft.relationType,
      note: draft.note,
      strength: draft.strength,
    );
    final syncedCount = await _syncRelationWorksIfNeeded(
      sourceCharacterId: characterId,
      targetCharacterId: draft.targetCharacterId,
    );
    await _loadCharacters(selectId: characterId);
    _showSnack(syncedCount > 0 ? '已保存角色关系，并补充 $syncedCount 个作品关联' : '已保存角色关系');
  }

  Future<int> _syncRelationWorksIfNeeded({
    required int sourceCharacterId,
    required int targetCharacterId,
  }) async {
    final sourceCharacter =
        _selected ?? await _repo.getCharacterById(sourceCharacterId);
    final targetCharacter = await _repo.getCharacterById(targetCharacterId);
    final targetWorksMissingFromSource = await _repo
        .getWorksLinkedToCharacterMissingFrom(
          fromCharacterId: targetCharacterId,
          missingFromCharacterId: sourceCharacterId,
        );
    final sourceWorksMissingFromTarget = await _repo
        .getWorksLinkedToCharacterMissingFrom(
          fromCharacterId: sourceCharacterId,
          missingFromCharacterId: targetCharacterId,
        );

    if (targetWorksMissingFromSource.isEmpty &&
        sourceWorksMissingFromTarget.isEmpty) {
      return 0;
    }
    if (!mounted) return 0;

    final draft = await showDialog<_RelationWorkSyncDraft>(
      context: context,
      builder: (_) => _RelationWorkSyncDialog(
        sourceName: sourceCharacter == null
            ? '当前角色'
            : _characterDisplayName(sourceCharacter),
        targetName: targetCharacter == null
            ? '目标角色'
            : _characterDisplayName(targetCharacter),
        targetWorksMissingFromSource: targetWorksMissingFromSource,
        sourceWorksMissingFromTarget: sourceWorksMissingFromTarget,
      ),
    );
    if (draft == null) return 0;

    var linkedCount = 0;
    for (final work in draft.targetWorksToSource) {
      final workId = _asInt(work['id']);
      if (workId == null) continue;
      await _repo.addCharacterToWork(
        workId: workId,
        characterId: sourceCharacterId,
      );
      linkedCount++;
    }
    for (final work in draft.sourceWorksToTarget) {
      final workId = _asInt(work['id']);
      if (workId == null) continue;
      await _repo.addCharacterToWork(
        workId: workId,
        characterId: targetCharacterId,
      );
      linkedCount++;
    }
    return linkedCount;
  }

  Future<void> _deleteRelation(Map<String, dynamic> relation) async {
    final characterId = _asInt(_selected?['id']);
    final relationId = _asInt(relation['id']);
    if (characterId == null || relationId == null) return;

    await _repo.deleteCharacterRelation(relationId);
    await _loadCharacters(selectId: characterId);
  }

  Future<void> _editCharacterTags() async {
    final character = _selected;
    final characterId = _asInt(character?['id']);
    if (character == null || characterId == null) return;

    final selectedTagIds = await showDialog<Set<int>>(
      context: context,
      builder: (_) => _CharacterTagsDialog(
        characterName: _characterDisplayName(character),
        selectedTagIds: _tags
            .map((tag) => _asInt(tag['id']))
            .whereType<int>()
            .toSet(),
      ),
    );
    if (selectedTagIds == null) return;

    try {
      await _repo.updateCharacterTags(
        characterId: characterId,
        tagIds: selectedTagIds,
      );
      await _loadCharacters(selectId: characterId);
      _showSnack('已保存角色标签');
    } catch (e) {
      _showSnack('保存角色标签失败：$e');
    }
  }

  Future<void> _editCharacterReview() async {
    final character = _selected;
    final characterId = _asInt(character?['id']);
    if (character == null || characterId == null) return;

    final draft = await showDialog<_CharacterReviewDraft>(
      context: context,
      builder: (_) => _CharacterReviewDialog(character: character),
    );
    if (draft == null) return;

    try {
      await _repo.updateCharacterPersonalReview(
        characterId: characterId,
        rating: draft.rating,
        review: draft.review,
      );
      await _loadCharacters(selectId: characterId);
      _showSnack('已保存角色评价');
    } catch (e) {
      _showSnack('保存角色评价失败：$e');
    }
  }

  Future<void> _openCharacterGroups() async {
    final changed = await showDialog<bool>(
      context: context,
      builder: (_) => const _CharacterGroupsDialog(),
    );
    if (changed == true) {
      await _loadCharacters(selectId: _asInt(_selected?['id']));
    }
  }

  void _showSnack(String text) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _displayController,
      builder: (context, _) {
        final displayConfig = _displayController.value;
        final isCompact = MediaQuery.sizeOf(context).width < 840;
        final showingCompactDetail =
            isCompact && _showCompactDetail && _selected != null;

        final list = _CharacterList(
          characters: _characters,
          selectedId: _asInt(_selected?['id']),
          isLoading: _isLoading,
          displayConfig: displayConfig,
          onTap: isCompact ? _openCharacterDetails : _loadCharacterDetails,
        );
        final detail = _CharacterDetailPane(
          character: _selected,
          works: _works,
          relations: _relations,
          tags: _tags,
          isLoading: _isDetailLoading,
          onAddWork: _linkWork,
          onRemoveWork: _removeWork,
          onAddRelation: _addRelation,
          onDeleteRelation: _deleteRelation,
          onEditReview: _editCharacterReview,
          onEditTags: _editCharacterTags,
          onDeleteCharacter: _deleteSelectedCharacter,
        );

        return PopScope(
          canPop: !showingCompactDetail,
          onPopInvokedWithResult: (didPop, _) {
            if (didPop || !showingCompactDetail) return;
            setState(() => _showCompactDetail = false);
          },
          child: Scaffold(
            appBar: AppBar(
              leading: showingCompactDetail
                  ? IconButton(
                      tooltip: '返回角色列表',
                      onPressed: () =>
                          setState(() => _showCompactDetail = false),
                      icon: const Icon(Icons.arrow_back),
                    )
                  : null,
              title: Text(
                showingCompactDetail
                    ? _characterDisplayName(_selected!)
                    : '角色管理',
              ),
              actions: [
                if (!showingCompactDetail)
                  IconButton(
                    tooltip: '定制角色管理',
                    onPressed: _openDisplaySettings,
                    icon: const Icon(Icons.dashboard_customize_outlined),
                  ),
                if (!showingCompactDetail)
                  PopupMenuButton<_CharacterToolAction>(
                    tooltip: '角色工具',
                    onSelected: (action) {
                      switch (action) {
                        case _CharacterToolAction.duplicates:
                          _findDuplicateCharacters();
                        case _CharacterToolAction.groups:
                          _openCharacterGroups();
                        case _CharacterToolAction.community:
                          _openCharacterCommunity();
                        case _CharacterToolAction.refresh:
                          _loadCharacters();
                      }
                    },
                    itemBuilder: (_) => const [
                      PopupMenuItem(
                        value: _CharacterToolAction.duplicates,
                        child: ListTile(
                          leading: Icon(Icons.find_replace_outlined),
                          title: Text('查找重复角色'),
                          contentPadding: EdgeInsets.zero,
                        ),
                      ),
                      PopupMenuItem(
                        value: _CharacterToolAction.groups,
                        child: ListTile(
                          leading: Icon(Icons.groups_2_outlined),
                          title: Text('角色群组'),
                          contentPadding: EdgeInsets.zero,
                        ),
                      ),
                      PopupMenuItem(
                        value: _CharacterToolAction.community,
                        child: ListTile(
                          leading: Icon(Icons.travel_explore_outlined),
                          title: Text('角色群组社区'),
                          contentPadding: EdgeInsets.zero,
                        ),
                      ),
                      PopupMenuItem(
                        value: _CharacterToolAction.refresh,
                        child: ListTile(
                          leading: Icon(Icons.refresh_rounded),
                          title: Text('刷新角色列表'),
                          contentPadding: EdgeInsets.zero,
                        ),
                      ),
                    ],
                  ),
                if (!showingCompactDetail)
                  IconButton.filledTonal(
                    tooltip: '添加角色',
                    onPressed: _addCharacterFromBangumi,
                    icon: const Icon(Icons.person_add_alt_1_outlined),
                  ),
                const SizedBox(width: 8),
              ],
            ),
            body: isCompact
                ? (showingCompactDetail
                      ? detail
                      : _CharacterListScaffold(
                          searchController: _searchController,
                          onSearch: _loadCharacters,
                          onClear: () {
                            _searchController.clear();
                            _loadCharacters();
                          },
                          onTextChanged: () => setState(() {}),
                          child: list,
                        ))
                : Column(
                    children: [
                      _CharacterSearchBar(
                        controller: _searchController,
                        onSearch: _loadCharacters,
                        onClear: () {
                          _searchController.clear();
                          _loadCharacters();
                        },
                        onTextChanged: () => setState(() {}),
                      ),
                      Expanded(
                        child: Row(
                          children: [
                            SizedBox(width: 360, child: list),
                            const VerticalDivider(width: 1),
                            Expanded(child: detail),
                          ],
                        ),
                      ),
                    ],
                  ),
          ),
        );
      },
    );
  }
}

class _CharacterListScaffold extends StatelessWidget {
  final TextEditingController searchController;
  final VoidCallback onSearch;
  final VoidCallback onClear;
  final VoidCallback onTextChanged;
  final Widget child;

  const _CharacterListScaffold({
    required this.searchController,
    required this.onSearch,
    required this.onClear,
    required this.onTextChanged,
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        _CharacterSearchBar(
          controller: searchController,
          onSearch: onSearch,
          onClear: onClear,
          onTextChanged: onTextChanged,
        ),
        Expanded(child: child),
      ],
    );
  }
}

class _CharacterSearchBar extends StatelessWidget {
  final TextEditingController controller;
  final VoidCallback onSearch;
  final VoidCallback onClear;
  final VoidCallback onTextChanged;

  const _CharacterSearchBar({
    required this.controller,
    required this.onSearch,
    required this.onClear,
    required this.onTextChanged,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
      child: TextField(
        controller: controller,
        textInputAction: TextInputAction.search,
        decoration: InputDecoration(
          prefixIcon: const Icon(Icons.search),
          hintText: '\u641c\u7d22\u89d2\u8272',
          suffixIcon: controller.text.isEmpty
              ? null
              : IconButton(
                  tooltip: '\u6e05\u7a7a',
                  onPressed: onClear,
                  icon: const Icon(Icons.close),
                ),
        ),
        onChanged: (_) => onTextChanged(),
        onSubmitted: (_) => onSearch(),
      ),
    );
  }
}

class _CharacterList extends StatelessWidget {
  final List<Map<String, dynamic>> characters;
  final int? selectedId;
  final bool isLoading;
  final PageDisplayConfig displayConfig;
  final ValueChanged<Map<String, dynamic>> onTap;

  const _CharacterList({
    required this.characters,
    required this.selectedId,
    required this.isLoading,
    required this.displayConfig,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    if (isLoading) return const Center(child: CircularProgressIndicator());
    if (characters.isEmpty) return const Center(child: Text('暂无角色'));

    if (characterListView(displayConfig) == CharacterListView.grid) {
      return _buildGrid(context);
    }

    return _buildList(context);
  }

  Widget _buildList(BuildContext context) {
    final compact = displayConfig.density == DisplayDensity.compact;
    final relaxed = displayConfig.density == DisplayDensity.relaxed;
    final showMetadata = characterShowMetadata(displayConfig);
    final showRating = characterShowRating(displayConfig);

    return ListView.separated(
      padding: EdgeInsets.fromLTRB(
        AppSpacing.md,
        0,
        AppSpacing.md,
        AppSpacing.lg,
      ),
      itemCount: characters.length,
      separatorBuilder: (_, _) =>
          SizedBox(height: compact ? AppSpacing.xs : AppSpacing.sm),
      itemBuilder: (context, index) {
        final item = characters[index];
        final selected = _asInt(item['id']) == selectedId;
        final rating = _normalizedRating(item['rating']);
        return Material(
          color: selected
              ? Theme.of(context).colorScheme.primaryContainer
              : Theme.of(context).colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(AppRadius.xs),
          child: ListTile(
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(AppRadius.xs),
            ),
            visualDensity: compact
                ? VisualDensity.compact
                : relaxed
                ? const VisualDensity(vertical: 2)
                : VisualDensity.standard,
            leading: _CharacterPortrait(
              url: _asText(item['image_url']),
              width: compact ? 40 : 46,
              height: compact ? 52 : 60,
              radius: 8,
            ),
            title: Text(
              _characterDisplayName(item),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontWeight: FontWeight.w800),
            ),
            subtitle: showMetadata
                ? Text(
                    _characterListSubtitle(item),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  )
                : null,
            trailing: !showRating || rating == null
                ? null
                : _RatingBadge(rating: rating),
            onTap: () => onTap(item),
          ),
        );
      },
    );
  }

  Widget _buildGrid(BuildContext context) {
    final compact = displayConfig.density == DisplayDensity.compact;
    final relaxed = displayConfig.density == DisplayDensity.relaxed;
    final showMetadata = characterShowMetadata(displayConfig);
    final showRating = characterShowRating(displayConfig);

    return GridView.builder(
      padding: const EdgeInsets.fromLTRB(
        AppSpacing.md,
        0,
        AppSpacing.md,
        AppSpacing.lg,
      ),
      gridDelegate: SliverGridDelegateWithMaxCrossAxisExtent(
        maxCrossAxisExtent: compact ? 150 : 190,
        crossAxisSpacing: compact ? AppSpacing.sm : AppSpacing.md,
        mainAxisSpacing: compact ? AppSpacing.sm : AppSpacing.md,
        childAspectRatio: relaxed ? 0.66 : 0.7,
      ),
      itemCount: characters.length,
      itemBuilder: (context, index) {
        final item = characters[index];
        final selected = _asInt(item['id']) == selectedId;
        return _CharacterGridCard(
          item: item,
          selected: selected,
          showMetadata: showMetadata,
          showRating: showRating,
          onTap: () => onTap(item),
        );
      },
    );
  }
}

class _CharacterGridCard extends StatelessWidget {
  final Map<String, dynamic> item;
  final bool selected;
  final bool showMetadata;
  final bool showRating;
  final VoidCallback onTap;

  const _CharacterGridCard({
    required this.item,
    required this.selected,
    required this.showMetadata,
    required this.showRating,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final rating = _normalizedRating(item['rating']);
    return Material(
      color: selected
          ? colorScheme.primaryContainer
          : colorScheme.surfaceContainerLow,
      borderRadius: BorderRadius.circular(AppRadius.sm),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: LayoutBuilder(
                builder: (context, constraints) {
                  return _CharacterPortrait(
                    url: _asText(item['image_url']),
                    width: constraints.maxWidth,
                    height: constraints.maxHeight,
                    radius: 0,
                    fit: BoxFit.cover,
                  );
                },
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(AppSpacing.sm),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _characterDisplayName(item),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.w900),
                  ),
                  if (showMetadata) ...[
                    const SizedBox(height: AppSpacing.xs),
                    Text(
                      _characterListSubtitle(item),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.labelSmall?.copyWith(
                        color: colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                  if (showRating && rating != null) ...[
                    const SizedBox(height: AppSpacing.xs),
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          Icons.star_rounded,
                          size: 15,
                          color: colorScheme.primary,
                        ),
                        const SizedBox(width: 2),
                        Text(
                          '$rating/10',
                          style: TextStyle(
                            color: colorScheme.primary,
                            fontSize: 12,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                      ],
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _CharacterDetailPane extends StatelessWidget {
  final Map<String, dynamic>? character;
  final List<Map<String, dynamic>> works;
  final List<Map<String, dynamic>> relations;
  final List<Map<String, dynamic>> tags;
  final bool isLoading;
  final VoidCallback onAddWork;
  final ValueChanged<Map<String, dynamic>> onRemoveWork;
  final VoidCallback onAddRelation;
  final ValueChanged<Map<String, dynamic>> onDeleteRelation;
  final VoidCallback onEditReview;
  final VoidCallback onEditTags;
  final VoidCallback onDeleteCharacter;

  const _CharacterDetailPane({
    required this.character,
    required this.works,
    required this.relations,
    required this.tags,
    required this.isLoading,
    required this.onAddWork,
    required this.onRemoveWork,
    required this.onAddRelation,
    required this.onDeleteRelation,
    required this.onEditReview,
    required this.onEditTags,
    required this.onDeleteCharacter,
  });

  @override
  Widget build(BuildContext context) {
    final item = character;
    if (item == null) return const Center(child: Text('选择一个角色查看详情'));

    return DefaultTabController(
      length: 3,
      child: Column(
        children: [
          _CharacterHeader(character: item, onDelete: onDeleteCharacter),
          const TabBar(
            tabs: [
              Tab(icon: Icon(Icons.badge_outlined), text: '资料'),
              Tab(icon: Icon(Icons.movie_filter_outlined), text: '作品'),
              Tab(icon: Icon(Icons.hub_outlined), text: '关系星图'),
            ],
          ),
          Expanded(
            child: isLoading
                ? const Center(child: CircularProgressIndicator())
                : TabBarView(
                    children: [
                      _ProfileTab(
                        character: item,
                        tags: tags,
                        onEditReview: onEditReview,
                        onEditTags: onEditTags,
                      ),
                      _WorksTab(
                        works: works,
                        onAddWork: onAddWork,
                        onRemoveWork: onRemoveWork,
                      ),
                      _RelationsTab(
                        character: item,
                        relations: relations,
                        onAddRelation: onAddRelation,
                        onDeleteRelation: onDeleteRelation,
                      ),
                    ],
                  ),
          ),
        ],
      ),
    );
  }
}

class _CharacterHeader extends StatelessWidget {
  final Map<String, dynamic> character;
  final VoidCallback onDelete;

  const _CharacterHeader({required this.character, required this.onDelete});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final name = _characterDisplayName(character);
    final originalName = _asText(character['name']);
    final summary = _asText(character['summary']);
    final rating = _normalizedRating(character['rating']);

    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 12),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _CharacterPortrait(
            url: _asText(character['image_url']),
            width: 92,
            height: 126,
            radius: 14,
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  name,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: theme.textTheme.titleLarge?.copyWith(
                    fontWeight: FontWeight.w900,
                  ),
                ),
                if (originalName != null && originalName != name) ...[
                  const SizedBox(height: 4),
                  Text(
                    originalName,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(color: theme.colorScheme.onSurfaceVariant),
                  ),
                ],
                const SizedBox(height: 10),
                Wrap(
                  spacing: 8,
                  runSpacing: 6,
                  children: [
                    if (_genderText(character['gender']) != null)
                      _MetaChip(text: _genderText(character['gender'])!),
                    if (_birthdayText(character) != null)
                      _MetaChip(text: _birthdayText(character)!),
                    if (_asText(character['blood_type']) != null)
                      _MetaChip(text: '${_asText(character['blood_type'])}型'),
                    if (rating != null) _MetaChip(text: '$rating分'),
                  ],
                ),
                if (summary != null) ...[
                  const SizedBox(height: 10),
                  Text(
                    summary,
                    maxLines: 3,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.bodySmall,
                  ),
                ],
              ],
            ),
          ),
          IconButton.filledTonal(
            tooltip: '永久删除角色',
            onPressed: onDelete,
            style: IconButton.styleFrom(
              foregroundColor: Theme.of(context).colorScheme.error,
            ),
            icon: const Icon(Icons.delete_forever_outlined),
          ),
        ],
      ),
    );
  }
}

class _ProfileTab extends StatelessWidget {
  final Map<String, dynamic> character;
  final List<Map<String, dynamic>> tags;
  final VoidCallback onEditReview;
  final VoidCallback onEditTags;

  const _ProfileTab({
    required this.character,
    required this.tags,
    required this.onEditReview,
    required this.onEditTags,
  });

  @override
  Widget build(BuildContext context) {
    final rows = [
      _InfoLine('中文名', _asText(character['name_cn'])),
      _InfoLine('原名', _asText(character['name'])),
      _InfoLine('性别', _genderText(character['gender'])),
      _InfoLine('生日', _birthdayText(character)),
      _InfoLine('血型', _asText(character['blood_type'])),
      _InfoLine('角色 ID', _asText(character['bgm_id'])),
    ].where((row) => row.value != null).toList();
    final summary = _asText(character['summary']);
    final rating = _normalizedRating(character['rating']);
    final review = _asText(character['review']);

    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        _PersonalReviewCard(
          rating: rating,
          review: review,
          onEdit: onEditReview,
        ),
        const SizedBox(height: 14),
        _CharacterTagsCard(tags: tags, onEdit: onEditTags),
        if (rows.isNotEmpty) ...[
          const SizedBox(height: 14),
          Card(
            elevation: 0,
            color: Theme.of(context).colorScheme.surfaceContainerHighest,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                children: rows.map((row) => _InfoRow(row: row)).toList(),
              ),
            ),
          ),
        ],
        if (summary != null) ...[
          const SizedBox(height: 14),
          Text(
            '简介',
            style: Theme.of(
              context,
            ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800),
          ),
          const SizedBox(height: 8),
          Text(summary),
        ],
      ],
    );
  }
}

class _PersonalReviewCard extends StatelessWidget {
  final int? rating;
  final String? review;
  final VoidCallback onEdit;

  const _PersonalReviewCard({
    required this.rating,
    required this.review,
    required this.onEdit,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final hasContent = rating != null || review != null;

    return Card(
      elevation: 0,
      color: theme.colorScheme.secondaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  Icons.star_rate_rounded,
                  color: theme.colorScheme.onSecondaryContainer,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    '我的评价',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w800,
                      color: theme.colorScheme.onSecondaryContainer,
                    ),
                  ),
                ),
                IconButton.filledTonal(
                  tooltip: '编辑评价',
                  onPressed: onEdit,
                  icon: const Icon(Icons.edit_outlined),
                ),
              ],
            ),
            const SizedBox(height: 10),
            if (!hasContent)
              Text(
                '还没有评分或评价',
                style: TextStyle(color: theme.colorScheme.onSecondaryContainer),
              )
            else ...[
              if (rating != null) _RatingBadge(rating: rating!),
              if (rating != null && review != null) const SizedBox(height: 10),
              if (review != null)
                Text(
                  review!,
                  style: TextStyle(
                    color: theme.colorScheme.onSecondaryContainer,
                    height: 1.45,
                  ),
                ),
            ],
          ],
        ),
      ),
    );
  }
}

class _CharacterTagsCard extends StatelessWidget {
  final List<Map<String, dynamic>> tags;
  final VoidCallback onEdit;

  const _CharacterTagsCard({required this.tags, required this.onEdit});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Card(
      elevation: 0,
      color: theme.colorScheme.surfaceContainerHighest,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(Icons.sell_outlined, color: theme.colorScheme.primary),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    '角色标签',
                    style: theme.textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ),
                IconButton.filledTonal(
                  tooltip: '编辑标签',
                  onPressed: onEdit,
                  icon: const Icon(Icons.edit_outlined),
                ),
              ],
            ),
            const SizedBox(height: 10),
            if (tags.isEmpty)
              Text(
                '还没有标签',
                style: TextStyle(color: theme.colorScheme.onSurfaceVariant),
              )
            else
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: tags
                    .map((tag) => _CharacterTagChip(tag: tag))
                    .toList(growable: false),
              ),
          ],
        ),
      ),
    );
  }
}

class _CharacterTagChip extends StatelessWidget {
  final Map<String, dynamic> tag;

  const _CharacterTagChip({required this.tag});

  @override
  Widget build(BuildContext context) {
    return Chip(
      avatar: const Icon(Icons.sell_outlined, size: 16),
      label: Text(_asText(tag['name']) ?? '未命名标签'),
      visualDensity: VisualDensity.compact,
    );
  }
}

class _RatingBadge extends StatelessWidget {
  final int rating;

  const _RatingBadge({required this.rating});

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Tooltip(
      message: '$rating / 10',
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: colorScheme.primaryContainer,
          borderRadius: BorderRadius.circular(999),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.star_rounded,
                size: 16,
                color: colorScheme.onPrimaryContainer,
              ),
              const SizedBox(width: 3),
              Text(
                '$rating/10',
                style: TextStyle(
                  color: colorScheme.onPrimaryContainer,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _WorksTab extends StatelessWidget {
  final List<Map<String, dynamic>> works;
  final VoidCallback onAddWork;
  final ValueChanged<Map<String, dynamic>> onRemoveWork;

  const _WorksTab({
    required this.works,
    required this.onAddWork,
    required this.onRemoveWork,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
          child: Row(
            children: [
              Text(
                '关联作品',
                style: Theme.of(
                  context,
                ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800),
              ),
              const Spacer(),
              FilledButton.icon(
                onPressed: onAddWork,
                icon: const Icon(Icons.add_link),
                label: const Text('关联'),
              ),
            ],
          ),
        ),
        Expanded(
          child: works.isEmpty
              ? const Center(child: Text('还没有关联动画或小说'))
              : ListView.separated(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
                  itemCount: works.length,
                  separatorBuilder: (_, _) => const SizedBox(height: 8),
                  itemBuilder: (context, index) {
                    final work = works[index];
                    return Card(
                      elevation: 0,
                      color: Theme.of(
                        context,
                      ).colorScheme.surfaceContainerHighest,
                      child: ListTile(
                        leading: ClipRRect(
                          borderRadius: BorderRadius.circular(8),
                          child: AnimeCoverImage(
                            url: _asText(work['cover_url']),
                            width: 44,
                            height: 58,
                            fit: BoxFit.cover,
                          ),
                        ),
                        title: Text(
                          _asText(work['title']) ?? '未命名作品',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        subtitle: Text(_workSubtitle(work)),
                        trailing: IconButton(
                          tooltip: '移除关联',
                          onPressed: () => onRemoveWork(work),
                          icon: const Icon(Icons.link_off_outlined),
                        ),
                      ),
                    );
                  },
                ),
        ),
      ],
    );
  }
}

class _RelationsTab extends StatelessWidget {
  final Map<String, dynamic> character;
  final List<Map<String, dynamic>> relations;
  final VoidCallback onAddRelation;
  final ValueChanged<Map<String, dynamic>> onDeleteRelation;
  const _RelationsTab({
    required this.character,
    required this.relations,
    required this.onAddRelation,
    required this.onDeleteRelation,
  });

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Row(
          children: [
            Text(
              '角色关系星图',
              style: Theme.of(
                context,
              ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800),
            ),
            const Spacer(),
            FilledButton.icon(
              onPressed: onAddRelation,
              icon: const Icon(Icons.hub_outlined),
              label: const Text('添加关系'),
            ),
          ],
        ),
        const SizedBox(height: 12),
        SizedBox(
          height: 320,
          child: _RelationshipGraph(character: character, relations: relations),
        ),
        const SizedBox(height: 12),
        if (relations.isEmpty)
          const Center(
            child: Padding(padding: EdgeInsets.all(24), child: Text('还没有角色关系')),
          )
        else
          ...relations.map(
            (relation) => Card(
              elevation: 0,
              color: Theme.of(context).colorScheme.surfaceContainerHighest,
              child: ListTile(
                leading: _CharacterPortrait(
                  url: _asText(relation['related_image_url']),
                  width: 44,
                  height: 56,
                  radius: 8,
                ),
                title: Text(_relatedCharacterName(relation)),
                subtitle: Text(_relationSubtitle(relation)),
                trailing: IconButton(
                  tooltip: '删除关系',
                  onPressed: () => onDeleteRelation(relation),
                  icon: const Icon(Icons.delete_outline),
                ),
              ),
            ),
          ),
      ],
    );
  }
}

class _RelationshipGraph extends StatelessWidget {
  final Map<String, dynamic> character;
  final List<Map<String, dynamic>> relations;

  const _RelationshipGraph({required this.character, required this.relations});

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(16),
      ),
      child: CustomPaint(
        painter: _RelationshipGraphPainter(
          character: character,
          relations: relations,
          colorScheme: Theme.of(context).colorScheme,
        ),
        child: const SizedBox.expand(),
      ),
    );
  }
}

class _RelationshipGraphPainter extends CustomPainter {
  final Map<String, dynamic> character;
  final List<Map<String, dynamic>> relations;
  final ColorScheme colorScheme;

  const _RelationshipGraphPainter({
    required this.character,
    required this.relations,
    required this.colorScheme,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final primaryPaint = Paint()..color = colorScheme.primary;
    final nodePaint = Paint()..color = colorScheme.tertiaryContainer;
    final linePaint = Paint()
      ..color = colorScheme.primary.withValues(alpha: 0.55)
      ..strokeCap = StrokeCap.round;

    _drawNode(
      canvas,
      center,
      34,
      colorScheme.primaryContainer,
      colorScheme.onPrimaryContainer,
      _shortName(_characterDisplayName(character)),
    );

    if (relations.isEmpty) {
      _drawText(
        canvas,
        '暂无关系',
        center.translate(0, 58),
        colorScheme.onSurfaceVariant,
        13,
        align: TextAlign.center,
      );
      return;
    }

    final radius = math.min(size.width, size.height) / 2 - 54;
    for (var i = 0; i < relations.length; i++) {
      final relation = relations[i];
      final angle = -math.pi / 2 + 2 * math.pi * i / relations.length;
      final point = center + Offset(math.cos(angle), math.sin(angle)) * radius;
      final strength = ((_asInt(relation['strength']) ?? 3).clamp(
        1,
        5,
      )).toDouble();
      linePaint.strokeWidth = 1.5 + strength * 0.6;

      canvas.drawLine(center, point, linePaint);
      _drawText(
        canvas,
        _asText(relation['relation_type']) ?? '关联',
        Offset.lerp(center, point, 0.55)!,
        colorScheme.onSurfaceVariant,
        11,
        align: TextAlign.center,
      );
      _drawNode(
        canvas,
        point,
        27,
        nodePaint.color,
        colorScheme.onTertiaryContainer,
        _shortName(_relatedCharacterName(relation)),
      );
    }

    canvas.drawCircle(center, 38, primaryPaint..style = PaintingStyle.stroke);
  }

  void _drawNode(
    Canvas canvas,
    Offset center,
    double radius,
    Color fill,
    Color textColor,
    String label,
  ) {
    canvas.drawCircle(center, radius, Paint()..color = fill);
    _drawText(canvas, label, center, textColor, 12, align: TextAlign.center);
  }

  void _drawText(
    Canvas canvas,
    String text,
    Offset center,
    Color color,
    double size, {
    TextAlign align = TextAlign.left,
  }) {
    final painter = TextPainter(
      text: TextSpan(
        text: text,
        style: TextStyle(
          color: color,
          fontSize: size,
          fontWeight: FontWeight.w800,
        ),
      ),
      maxLines: 2,
      textAlign: align,
      textDirection: TextDirection.ltr,
    )..layout(maxWidth: 74);
    painter.paint(
      canvas,
      Offset(center.dx - painter.width / 2, center.dy - painter.height / 2),
    );
  }

  @override
  bool shouldRepaint(covariant _RelationshipGraphPainter oldDelegate) =>
      oldDelegate.character != character || oldDelegate.relations != relations;
}

class _CharacterPortrait extends StatelessWidget {
  final String? url;
  final double width;
  final double height;
  final double radius;
  final BoxFit fit;

  const _CharacterPortrait({
    required this.url,
    required this.width,
    required this.height,
    required this.radius,
    this.fit = BoxFit.contain,
  });

  @override
  Widget build(BuildContext context) {
    final imageUrl = _asText(url);
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: Container(
        width: width,
        height: height,
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        child: imageUrl == null
            ? const Icon(Icons.person_outline)
            : Image.network(
                imageUrl,
                fit: fit,
                alignment: Alignment.center,
                errorBuilder: (_, _, _) => const Icon(Icons.broken_image),
              ),
      ),
    );
  }
}

enum _CharacterGroupImportMode { local, shareCode, community }

enum _CharacterGroupEditorPanel { characters, works }

class _CharacterGroupsDialog extends StatefulWidget {
  const _CharacterGroupsDialog();

  @override
  State<_CharacterGroupsDialog> createState() => _CharacterGroupsDialogState();
}

class _CharacterGroupsDialogState extends State<_CharacterGroupsDialog> {
  final CharacterRepository _repo = getIt<CharacterRepository>();
  final CharacterGroupCommunityService _communityService =
      const CharacterGroupCommunityService();
  final TextEditingController _queryController = TextEditingController();
  List<Map<String, dynamic>> _groups = const [];
  bool _isLoading = true;
  bool _isImporting = false;
  bool _changed = false;

  @override
  void initState() {
    super.initState();
    _loadGroups();
  }

  @override
  void dispose() {
    _queryController.dispose();
    super.dispose();
  }

  Future<void> _loadGroups() async {
    setState(() => _isLoading = true);
    final groups = await _repo.getCharacterGroups(
      query: _queryController.text.trim(),
    );
    if (!mounted) return;
    setState(() {
      _groups = groups;
      _isLoading = false;
    });
  }

  Future<void> _openEditor([Map<String, dynamic>? group]) async {
    final draft = await showDialog<_CharacterGroupDraft>(
      context: context,
      builder: (_) => _CharacterGroupEditorDialog(group: group),
    );
    if (draft == null) return;

    await _repo.upsertCharacterGroup(
      groupId: draft.groupId,
      name: draft.name,
      description: draft.description,
      characterIds: draft.characterIds,
      workIds: draft.workIds,
    );
    _changed = true;
    await _loadGroups();
  }

  Future<void> _exportGroupToLocal(Map<String, dynamic> group) async {
    final groupId = _asInt(group['id']);
    if (groupId == null) return;

    try {
      final package = await _repo.exportCharacterGroupPackage(groupId);
      final jsonString = const JsonEncoder.withIndent(
        '  ',
      ).convert(package.toJson());
      final bytes = utf8.encode(jsonString);

      final fileName =
          '${_asText(group['name']) ?? '角色群组'}_${DateTime.now().toString().split('.')[0].replaceAll(':', '-')}.json';

      String? outputPath;
      if (Platform.isAndroid || Platform.isIOS) {
        outputPath = await FilePicker.platform.saveFile(
          dialogTitle: '选择保存位置',
          fileName: fileName,
          bytes: Uint8List.fromList(bytes),
        );
      } else {
        outputPath = await FilePicker.platform.saveFile(
          dialogTitle: '保存角色群组文件',
          fileName: fileName,
          type: FileType.custom,
          allowedExtensions: const ['json'],
          bytes: Uint8List.fromList(bytes),
        );
      }

      if (outputPath != null) {
        await File(outputPath).writeAsBytes(bytes);
        if (mounted) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(SnackBar(content: Text('已导出到: $outputPath')));
        }
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('导出失败：$e')));
      }
    }
  }

  Future<void> _deleteGroup(Map<String, dynamic> group) async {
    final groupId = _asInt(group['id']);
    if (groupId == null) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('删除群组'),
        content: Text('确认删除「${_asText(group['name']) ?? '未命名群组'}」吗？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    await _repo.deleteCharacterGroup(groupId);
    _changed = true;
    await _loadGroups();
  }

  Future<void> _openDetails(Map<String, dynamic> group) async {
    final result = await showDialog<bool>(
      context: context,
      builder: (_) => _CharacterGroupDetailsDialog(group: group),
    );
    // 如果对话框返回 true（表示分享状态有变更），刷新群组列表
    if (result == true) {
      await _loadGroups();
    }
  }

  Future<void> _handleImportMode(_CharacterGroupImportMode mode) async {
    switch (mode) {
      case _CharacterGroupImportMode.local:
        await _importFromLocalFile();
        break;
      case _CharacterGroupImportMode.shareCode:
        await _importFromShareCode();
        break;
      case _CharacterGroupImportMode.community:
        await _importFromCommunity();
        break;
    }
  }

  Future<void> _importFromLocalFile() async {
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: const ['json'],
        withData: true,
      );
      if (result == null || result.files.isEmpty) return;

      final picked = result.files.single;
      final bytes =
          picked.bytes ??
          (picked.path == null ? null : await File(picked.path!).readAsBytes());
      if (bytes == null) throw const FormatException('无法读取本地文件');

      final decoded = jsonDecode(utf8.decode(bytes));
      if (decoded is! Map) throw const FormatException('导入文件必须是 JSON 对象');
      final package = CharacterGroupPackage.fromJson(
        Map<String, dynamic>.from(decoded),
      );
      await _importPackage(package, source: 'local_import');
    } catch (e) {
      _showSnack('本地导入失败：$e');
    }
  }

  Future<void> _importFromShareCode() async {
    final code = await showDialog<String>(
      context: context,
      builder: (_) => const _ShareCodeImportDialog(),
    );
    if (code == null || code.trim().isEmpty) return;

    setState(() => _isImporting = true);
    try {
      final package = await _communityService.fetchByShareCode(code);
      if (!mounted) return;
      setState(() => _isImporting = false);
      await _importPackage(package, source: 'share');
    } catch (e) {
      if (!mounted) return;
      setState(() => _isImporting = false);
      _showSnack('分享码导入失败：$e');
    }
  }

  Future<void> _importFromCommunity() async {
    final package = await showDialog<CharacterGroupPackage>(
      context: context,
      builder: (_) => _CommunityGroupImportDialog(service: _communityService),
    );
    if (package == null) return;
    await _importPackage(package, source: 'community');
  }

  Future<void> _importPackage(
    CharacterGroupPackage package, {
    required String source,
  }) async {
    if (package.characters.length < 2 || package.works.isEmpty) {
      _showSnack('角色群组至少需要 2 个角色和 1 部作品');
      return;
    }

    final confirmed = await _confirmImportPackage(package, source: source);
    if (confirmed != true) return;

    setState(() => _isImporting = true);
    try {
      final result = await _repo.importCharacterGroupPackage(
        package,
        source: source,
      );
      _changed = true;
      await _loadGroups();
      _showSnack('已导入 ${result.characterCount} 个角色、${result.workCount} 部作品');
    } catch (e) {
      _showSnack('导入失败：$e');
    } finally {
      if (mounted) setState(() => _isImporting = false);
    }
  }

  Future<bool?> _confirmImportPackage(
    CharacterGroupPackage package, {
    required String source,
  }) {
    final description = package.description?.trim();
    final sourceLabel = switch (source) {
      'local_import' => '本地导入',
      'share' => '分享码导入',
      'community' => '社区导入',
      _ => '导入',
    };
    return showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('导入角色群组'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              package.name,
              style: const TextStyle(fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                _MetaChip(text: sourceLabel),
                _MetaChip(text: '${package.characters.length} 个角色'),
                _MetaChip(text: '${package.works.length} 部作品'),
                if (package.shareCode != null)
                  _MetaChip(text: '分享码 ${package.shareCode!}'),
              ],
            ),
            if (description != null && description.isNotEmpty) ...[
              const SizedBox(height: 12),
              Text(description),
            ],
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton.icon(
            onPressed: () => Navigator.pop(context, true),
            icon: const Icon(Icons.download_done_outlined),
            label: const Text('导入'),
          ),
        ],
      ),
    );
  }

  void _showSnack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  Widget _buildSearchField() {
    return TextField(
      controller: _queryController,
      textInputAction: TextInputAction.search,
      decoration: InputDecoration(
        prefixIcon: const Icon(Icons.search),
        hintText: '搜索群组',
        suffixIcon: IconButton(
          tooltip: '搜索',
          onPressed: _loadGroups,
          icon: const Icon(Icons.search),
        ),
      ),
      onSubmitted: (_) => _loadGroups(),
    );
  }

  Widget _buildImportButton() {
    return PopupMenuButton<_CharacterGroupImportMode>(
      enabled: !_isImporting,
      tooltip: '导入',
      icon: _isImporting
          ? const SizedBox(
              width: 22,
              height: 22,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          : const Icon(Icons.download_outlined),
      onSelected: _handleImportMode,
      itemBuilder: (_) => const [
        PopupMenuItem(
          value: _CharacterGroupImportMode.local,
          child: ListTile(
            dense: true,
            leading: Icon(Icons.folder_open_outlined),
            title: Text('本地导入'),
          ),
        ),
        PopupMenuItem(
          value: _CharacterGroupImportMode.shareCode,
          child: ListTile(
            dense: true,
            leading: Icon(Icons.key_outlined),
            title: Text('分享码导入'),
          ),
        ),
        PopupMenuItem(
          value: _CharacterGroupImportMode.community,
          child: ListTile(
            dense: true,
            leading: Icon(Icons.public_outlined),
            title: Text('社区导入'),
          ),
        ),
      ],
    );
  }

  Widget _buildCreateButton({bool stretch = false}) {
    final button = FilledButton.icon(
      onPressed: _isImporting ? null : () => _openEditor(),
      icon: const Icon(Icons.add),
      label: const Text('新建'),
    );
    if (!stretch) return button;
    return SizedBox(height: 48, child: button);
  }

  Widget _buildToolbar(bool compact) {
    if (!compact) {
      return Row(
        children: [
          Expanded(child: _buildSearchField()),
          const SizedBox(width: 8),
          _buildImportButton(),
          const SizedBox(width: 4),
          _buildCreateButton(),
        ],
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _buildSearchField(),
        const SizedBox(height: 8),
        Row(
          children: [
            SizedBox(width: 48, height: 48, child: _buildImportButton()),
            const SizedBox(width: 8),
            Expanded(child: _buildCreateButton(stretch: true)),
          ],
        ),
      ],
    );
  }

  List<Widget> _buildGroupActions(Map<String, dynamic> group) {
    return [
      IconButton(
        tooltip: '导出',
        visualDensity: VisualDensity.compact,
        onPressed: () => _exportGroupToLocal(group),
        icon: const Icon(Icons.download_outlined),
      ),
      IconButton(
        tooltip: '编辑',
        visualDensity: VisualDensity.compact,
        onPressed: () => _openEditor(group),
        icon: const Icon(Icons.edit_outlined),
      ),
      IconButton(
        tooltip: '删除',
        visualDensity: VisualDensity.compact,
        onPressed: () => _deleteGroup(group),
        icon: const Icon(Icons.delete_outline),
      ),
    ];
  }

  Widget _buildGroupCard(Map<String, dynamic> group, bool compact) {
    final theme = Theme.of(context);
    final colorScheme = theme.colorScheme;
    final count = _asInt(group['character_count']) ?? 0;
    final name = _asText(group['name']) ?? '未命名群组';
    final subtitle = _characterGroupSubtitle(group);
    final textBlock = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          name,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 2),
        Text(
          subtitle,
          maxLines: compact ? 2 : 1,
          overflow: TextOverflow.ellipsis,
          style: theme.textTheme.bodySmall?.copyWith(
            color: colorScheme.onSurfaceVariant,
          ),
        ),
      ],
    );

    final countBadge = CircleAvatar(
      radius: compact ? 22 : 20,
      backgroundColor: colorScheme.primaryContainer,
      foregroundColor: colorScheme.onPrimaryContainer,
      child: Text(
        '$count',
        style: const TextStyle(fontWeight: FontWeight.w800),
      ),
    );

    return Card(
      elevation: 0,
      color: colorScheme.surfaceContainerHighest,
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => _openDetails(group),
        child: Padding(
          padding: EdgeInsets.fromLTRB(
            compact ? 12 : 16,
            compact ? 12 : 10,
            compact ? 8 : 12,
            compact ? 8 : 10,
          ),
          child: compact
              ? Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Row(
                      children: [
                        countBadge,
                        const SizedBox(width: 12),
                        Expanded(child: textBlock),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Align(
                      alignment: Alignment.centerRight,
                      child: Wrap(
                        spacing: 2,
                        children: _buildGroupActions(group),
                      ),
                    ),
                  ],
                )
              : Row(
                  children: [
                    countBadge,
                    const SizedBox(width: 12),
                    Expanded(child: textBlock),
                    const SizedBox(width: 8),
                    Wrap(spacing: 2, children: _buildGroupActions(group)),
                  ],
                ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.sizeOf(context);
    final isCompactScreen = size.width < 600;
    final horizontalInset = size.width < 360
        ? 12.0
        : isCompactScreen
        ? 16.0
        : 40.0;
    final verticalInset = isCompactScreen ? 24.0 : 40.0;
    final dialogWidth = math.min(
      math.max(260.0, size.width - horizontalInset * 2),
      760.0,
    );
    final maxContentHeight = math.max(
      260.0,
      size.height - verticalInset * 2 - (isCompactScreen ? 150.0 : 140.0),
    );
    final dialogHeight = math.min(
      maxContentHeight,
      isCompactScreen ? 560.0 : 620.0,
    );
    final horizontalPadding = isCompactScreen ? 16.0 : 24.0;

    return AlertDialog(
      insetPadding: EdgeInsets.symmetric(
        horizontal: horizontalInset,
        vertical: verticalInset,
      ),
      titlePadding: EdgeInsets.fromLTRB(
        horizontalPadding,
        isCompactScreen ? 22 : 24,
        horizontalPadding,
        8,
      ),
      contentPadding: EdgeInsets.fromLTRB(
        horizontalPadding,
        0,
        horizontalPadding,
        0,
      ),
      actionsPadding: EdgeInsets.fromLTRB(
        horizontalPadding,
        8,
        horizontalPadding,
        isCompactScreen ? 12 : 16,
      ),
      title: const Text('角色群组'),
      content: SizedBox(
        width: dialogWidth,
        height: dialogHeight,
        child: LayoutBuilder(
          builder: (context, constraints) {
            final compact = constraints.maxWidth < 520;
            return Column(
              children: [
                _buildToolbar(compact),
                const SizedBox(height: 12),
                Expanded(
                  child: _isLoading
                      ? const Center(child: CircularProgressIndicator())
                      : _groups.isEmpty
                      ? const Center(child: Text('还没有角色群组'))
                      : ListView.separated(
                          itemCount: _groups.length,
                          separatorBuilder: (_, _) => const SizedBox(height: 8),
                          itemBuilder: (context, index) {
                            final group = _groups[index];
                            return _buildGroupCard(group, compact);
                          },
                        ),
                ),
              ],
            );
          },
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context, _changed),
          child: const Text('关闭'),
        ),
      ],
    );
  }
}

class _CharacterGroupEditorDialog extends StatefulWidget {
  final Map<String, dynamic>? group;

  const _CharacterGroupEditorDialog({this.group});

  @override
  State<_CharacterGroupEditorDialog> createState() =>
      _CharacterGroupEditorDialogState();
}

class _CharacterGroupEditorDialogState
    extends State<_CharacterGroupEditorDialog> {
  final CharacterRepository _repo = getIt<CharacterRepository>();
  late final TextEditingController _nameController;
  late final TextEditingController _descriptionController;
  final TextEditingController _characterQueryController =
      TextEditingController();
  final TextEditingController _workQueryController = TextEditingController();
  final Map<int, Map<String, dynamic>> _selectedCharacters = {};
  final Map<int, Map<String, dynamic>> _selectedWorks = {};
  List<Map<String, dynamic>> _characters = const [];
  List<Map<String, dynamic>> _works = const [];

  /// 已选角色关联的作品 ID 集合，用于在作品列表中优先显示
  Set<int> _relatedWorkIds = const {};
  String _subjectType = 'all';
  _CharacterGroupEditorPanel _narrowPanel =
      _CharacterGroupEditorPanel.characters;
  bool _isLoading = true;
  bool _isWorkLoading = false;

  @override
  void initState() {
    super.initState();
    _nameController = TextEditingController(
      text: _asText(widget.group?['name']) ?? '',
    );
    _descriptionController = TextEditingController(
      text: _asText(widget.group?['description']) ?? '',
    );
    _loadInitialData();
  }

  @override
  void dispose() {
    _nameController.dispose();
    _descriptionController.dispose();
    _characterQueryController.dispose();
    _workQueryController.dispose();
    super.dispose();
  }

  Future<void> _loadInitialData() async {
    setState(() => _isLoading = true);
    final groupId = _asInt(widget.group?['id']);
    final characters = await _repo.getAllCharacters();
    final works = await _repo.searchGroupableWorks(limit: 160);
    final selectedCharacters = groupId == null
        ? <Map<String, dynamic>>[]
        : await _repo.getCharacterGroupCharacters(groupId);
    final selectedWorks = groupId == null
        ? <Map<String, dynamic>>[]
        : await _repo.getCharacterGroupWorks(groupId);

    // 查询已选角色关联的作品 ID
    final selectedCharIds = selectedCharacters
        .map((c) => _asInt(c['id']))
        .whereType<int>()
        .toList();
    final relatedWorkIds = await _repo.getAnimeIdsByCharacterIds(
      selectedCharIds,
    );

    if (!mounted) return;
    setState(() {
      _characters = _mergeSelectedRows(characters, selectedCharacters);
      _works = _sortWorksByRelation(_mergeSelectedRows(works, selectedWorks));
      _selectedCharacters
        ..clear()
        ..addAll(_indexRows(selectedCharacters));
      _selectedWorks
        ..clear()
        ..addAll(_indexRows(selectedWorks));
      _relatedWorkIds = relatedWorkIds;
      _isLoading = false;
    });
  }

  Map<int, Map<String, dynamic>> _indexRows(List<Map<String, dynamic>> rows) {
    final indexed = <int, Map<String, dynamic>>{};
    for (final row in rows) {
      final id = _asInt(row['id']);
      if (id != null) indexed[id] = row;
    }
    return indexed;
  }

  List<Map<String, dynamic>> _mergeSelectedRows(
    List<Map<String, dynamic>> rows,
    List<Map<String, dynamic>> selectedRows,
  ) {
    final merged = <Map<String, dynamic>>[];
    final seen = <int>{};
    for (final row in selectedRows.followedBy(rows)) {
      final id = _asInt(row['id']);
      if (id != null && seen.add(id)) merged.add(row);
    }
    return merged;
  }

  /// 将作品列表排序：已选的在前，已选角色关联的次之，其余在后
  List<Map<String, dynamic>> _sortWorksByRelation(
    List<Map<String, dynamic>> works,
  ) {
    final selected = _selectedWorks.keys.toSet();
    final related = _relatedWorkIds;
    final sorted = List<Map<String, dynamic>>.of(works);
    sorted.sort((a, b) {
      final aId = _asInt(a['id']) ?? 0;
      final bId = _asInt(b['id']) ?? 0;
      final aSelected = selected.contains(aId) ? 0 : 1;
      final bSelected = selected.contains(bId) ? 0 : 1;
      if (aSelected != bSelected) return aSelected - bSelected;
      final aRelated = related.contains(aId) ? 0 : 1;
      final bRelated = related.contains(bId) ? 0 : 1;
      if (aRelated != bRelated) return aRelated - bRelated;
      return 0;
    });
    return sorted;
  }

  List<Map<String, dynamic>> get _visibleCharacters {
    final keyword = _asText(_characterQueryController.text)?.toLowerCase();
    if (keyword == null) return _characters;
    return _characters
        .where((character) {
          final name = _characterDisplayName(character).toLowerCase();
          final originalName = (_asText(character['name']) ?? '').toLowerCase();
          return name.contains(keyword) || originalName.contains(keyword);
        })
        .toList(growable: false);
  }

  Future<void> _searchWorks() async {
    setState(() => _isWorkLoading = true);
    final rows = await _repo.searchGroupableWorks(
      query: _workQueryController.text.trim(),
      subjectType: _subjectType,
      limit: 160,
    );
    if (!mounted) return;
    setState(() {
      _works = _sortWorksByRelation(
        _mergeSelectedRows(rows, _selectedWorks.values.toList()),
      );
      _isWorkLoading = false;
    });
  }

  void _toggleCharacter(Map<String, dynamic> character, bool selected) {
    final id = _asInt(character['id']);
    if (id == null) return;
    setState(() {
      if (selected) {
        _selectedCharacters[id] = character;
      } else {
        _selectedCharacters.remove(id);
      }
    });
    // 异步更新已选角色关联的作品 ID
    _refreshRelatedWorkIds();
  }

  Future<void> _refreshRelatedWorkIds() async {
    final charIds = _selectedCharacters.keys.toList();
    final relatedIds = await _repo.getAnimeIdsByCharacterIds(charIds);
    if (!mounted) return;
    setState(() => _relatedWorkIds = relatedIds);
  }

  void _toggleWork(Map<String, dynamic> work, bool selected) {
    final id = _asInt(work['id']);
    if (id == null) return;
    setState(() {
      if (selected) {
        _selectedWorks[id] = work;
      } else {
        _selectedWorks.remove(id);
      }
    });
  }

  bool get _canSave =>
      _asText(_nameController.text) != null &&
      _selectedCharacters.length >= 2 &&
      _selectedWorks.isNotEmpty;

  void _submit() {
    final name = _asText(_nameController.text);
    if (name == null || !_canSave) return;
    Navigator.pop(
      context,
      _CharacterGroupDraft(
        groupId: _asInt(widget.group?['id']),
        name: name,
        description: _asText(_descriptionController.text),
        characterIds: _selectedCharacters.keys.toList(growable: false),
        workIds: _selectedWorks.keys.toList(growable: false),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.sizeOf(context);
    final dialogWidth = math.min(math.max(320.0, size.width - 48), 820.0);
    final dialogHeight = math.min(math.max(500.0, size.height - 180), 680.0);
    final isNarrow = dialogWidth < 680;

    return AlertDialog(
      title: Text(widget.group == null ? '新建角色群组' : '编辑角色群组'),
      content: SizedBox(
        width: dialogWidth,
        height: dialogHeight,
        child: _isLoading
            ? const Center(child: CircularProgressIndicator())
            : Column(
                children: [
                  TextField(
                    controller: _nameController,
                    decoration: const InputDecoration(
                      prefixIcon: Icon(Icons.groups_2_outlined),
                      labelText: '群组名称',
                    ),
                    onChanged: (_) => setState(() {}),
                  ),
                  const SizedBox(height: 10),
                  TextField(
                    controller: _descriptionController,
                    minLines: 1,
                    maxLines: 2,
                    decoration: const InputDecoration(
                      prefixIcon: Icon(Icons.notes_outlined),
                      labelText: '简介',
                    ),
                  ),
                  const SizedBox(height: 12),
                  Expanded(
                    child: isNarrow
                        ? _buildNarrowSelector(context)
                        : Row(
                            children: [
                              Expanded(child: _buildCharacterSelector(context)),
                              const SizedBox(width: 12),
                              Expanded(child: _buildWorkSelector(context)),
                            ],
                          ),
                  ),
                ],
              ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消'),
        ),
        FilledButton.icon(
          onPressed: _canSave ? _submit : null,
          icon: const Icon(Icons.check),
          label: Text(
            '保存 ${_selectedCharacters.length} 角色 · ${_selectedWorks.length} 作品',
          ),
        ),
      ],
    );
  }

  Widget _buildNarrowSelector(BuildContext context) {
    return Column(
      children: [
        SizedBox(
          width: double.infinity,
          child: SegmentedButton<_CharacterGroupEditorPanel>(
            segments: [
              ButtonSegment(
                value: _CharacterGroupEditorPanel.characters,
                icon: const Icon(Icons.people_alt_outlined),
                label: Text('角色 ${_selectedCharacters.length}'),
              ),
              ButtonSegment(
                value: _CharacterGroupEditorPanel.works,
                icon: const Icon(Icons.video_library_outlined),
                label: Text('作品 ${_selectedWorks.length}'),
              ),
            ],
            selected: {_narrowPanel},
            onSelectionChanged: (value) {
              setState(() => _narrowPanel = value.first);
            },
          ),
        ),
        const SizedBox(height: 12),
        Expanded(
          child: _narrowPanel == _CharacterGroupEditorPanel.characters
              ? _buildCharacterSelector(context)
              : _buildWorkSelector(context),
        ),
      ],
    );
  }

  Widget _buildCharacterSelector(BuildContext context) {
    final visibleCharacters = _visibleCharacters;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '角色（${_selectedCharacters.length}）',
          style: Theme.of(
            context,
          ).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: _characterQueryController,
          decoration: const InputDecoration(
            prefixIcon: Icon(Icons.search),
            hintText: '搜索角色',
          ),
          onChanged: (_) => setState(() {}),
        ),
        const SizedBox(height: 8),
        Expanded(
          child: visibleCharacters.isEmpty
              ? const Center(child: Text('没有可选角色'))
              : ListView.builder(
                  itemCount: visibleCharacters.length,
                  itemBuilder: (context, index) {
                    final character = visibleCharacters[index];
                    final id = _asInt(character['id']);
                    final selected =
                        id != null && _selectedCharacters.containsKey(id);
                    return CheckboxListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      secondary: _CharacterPortrait(
                        url: _asText(character['image_url']),
                        width: 36,
                        height: 46,
                        radius: 7,
                      ),
                      value: selected,
                      onChanged: id == null
                          ? null
                          : (value) =>
                                _toggleCharacter(character, value ?? false),
                      title: Text(
                        _characterDisplayName(character),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      subtitle: Text(
                        _characterListSubtitle(character),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    );
                  },
                ),
        ),
      ],
    );
  }

  Widget _buildWorkSelector(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '作品（${_selectedWorks.length}）',
          style: Theme.of(
            context,
          ).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 8),
        TextField(
          controller: _workQueryController,
          textInputAction: TextInputAction.search,
          decoration: InputDecoration(
            prefixIcon: const Icon(Icons.search),
            hintText: '搜索动画或小说',
            suffixIcon: IconButton(
              tooltip: '搜索',
              onPressed: _searchWorks,
              icon: const Icon(Icons.search),
            ),
          ),
          onSubmitted: (_) => _searchWorks(),
        ),
        const SizedBox(height: 8),
        SegmentedButton<String>(
          segments: const [
            ButtonSegment(value: 'all', label: Text('全部')),
            ButtonSegment(value: 'anime', label: Text('动画')),
            ButtonSegment(value: 'book', label: Text('小说')),
          ],
          selected: {_subjectType},
          onSelectionChanged: (value) {
            setState(() => _subjectType = value.first);
            _searchWorks();
          },
        ),
        const SizedBox(height: 8),
        Expanded(
          child: _isWorkLoading
              ? const Center(child: CircularProgressIndicator())
              : _works.isEmpty
              ? const Center(child: Text('没有可选作品'))
              : ListView.builder(
                  itemCount: _works.length,
                  itemBuilder: (context, index) {
                    final work = _works[index];
                    final id = _asInt(work['id']);
                    final selected =
                        id != null && _selectedWorks.containsKey(id);
                    final isRelated =
                        id != null && _relatedWorkIds.contains(id);
                    return CheckboxListTile(
                      dense: true,
                      contentPadding: EdgeInsets.zero,
                      secondary: ClipRRect(
                        borderRadius: BorderRadius.circular(7),
                        child: AnimeCoverImage(
                          url: _asText(work['cover_url']),
                          width: 36,
                          height: 48,
                          fit: BoxFit.cover,
                        ),
                      ),
                      value: selected,
                      onChanged: id == null
                          ? null
                          : (value) => _toggleWork(work, value ?? false),
                      title: Row(
                        children: [
                          if (isRelated)
                            const Padding(
                              padding: EdgeInsets.only(right: 4),
                              child: Icon(
                                Icons.star,
                                size: 14,
                                color: Colors.amber,
                              ),
                            ),
                          Expanded(
                            child: Text(
                              _asText(work['title']) ?? '未命名作品',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        ],
                      ),
                      subtitle: Text(_workTypeLabel(work['subject_type'])),
                    );
                  },
                ),
        ),
      ],
    );
  }
}

class _CharacterGroupDetailsDialog extends StatefulWidget {
  final Map<String, dynamic> group;

  const _CharacterGroupDetailsDialog({required this.group});

  @override
  State<_CharacterGroupDetailsDialog> createState() =>
      _CharacterGroupDetailsDialogState();
}

class _CharacterGroupDetailsDialogState
    extends State<_CharacterGroupDetailsDialog> {
  final CharacterRepository _repo = getIt<CharacterRepository>();
  final CharacterGroupCommunityService _communityService =
      const CharacterGroupCommunityService();
  List<Map<String, dynamic>> _characters = const [];
  List<Map<String, dynamic>> _works = const [];
  bool _isLoading = true;
  bool _isLoggedIn = false;
  bool _isSharing = false;
  bool _isUpdating = false;
  bool _isDeleting = false;
  String? _communityId;
  String? _shareCode;

  @override
  void initState() {
    super.initState();
    _loadDetails();
    _checkLoginStatus();
  }

  Future<void> _checkLoginStatus() async {
    final session = await CloudAccountService.loadSession();
    if (!mounted) return;
    setState(() => _isLoggedIn = session != null);
  }

  Future<void> _loadDetails() async {
    final groupId = _asInt(widget.group['id']);
    if (groupId == null) return;
    final characters = await _repo.getCharacterGroupCharacters(groupId);
    final works = await _repo.getCharacterGroupWorks(groupId);
    if (!mounted) return;
    setState(() {
      _characters = characters;
      _works = works;
      _isLoading = false;
      _communityId = _asText(widget.group['community_id']);
      _shareCode = _asText(widget.group['share_code']);
    });
  }

  Future<void> _shareToCommunity() async {
    final groupId = _asInt(widget.group['id']);
    if (groupId == null) return;

    final session = await CloudAccountService.loadSession();
    if (session == null) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('请先登录账号才能分享到社区')));
      return;
    }

    setState(() => _isSharing = true);
    try {
      final package = await _repo.exportCharacterGroupPackage(groupId);
      final existingCommunityId =
          _communityId ?? _asText(widget.group['community_id']);

      CommunityCharacterGroupInfo result;
      if (existingCommunityId != null && existingCommunityId.isNotEmpty) {
        result = await _communityService.updateGroup(
          session: session,
          communityId: existingCommunityId,
          package: package,
          isPublic: true,
        );
      } else {
        result = await _communityService.uploadGroup(
          session: session,
          package: package,
          isPublic: true,
        );
      }

      await _repo.updateCharacterGroupCommunityInfo(
        groupId: groupId,
        communityId: result.id,
        shareCode: result.shareCode,
      );

      if (!mounted) return;
      setState(() {
        _communityId = result.id;
        _shareCode = result.shareCode;
      });
      final message = existingCommunityId != null
          ? '已更新分享内容'
          : '已分享到社区，分享码：${result.shareCode}';
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(message)));
      // 通知父页面刷新群组列表
      Navigator.pop(context, true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('分享失败：$e')));
    } finally {
      if (mounted) setState(() => _isSharing = false);
    }
  }

  Future<void> _copyShareCode() async {
    if (_shareCode == null) return;
    await Clipboard.setData(ClipboardData(text: _shareCode!));
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('分享码已复制')));
  }

  Future<void> _updateShare() async {
    final groupId = _asInt(widget.group['id']);
    if (groupId == null || _communityId == null) return;

    final session = await CloudAccountService.loadSession();
    if (session == null) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('请先登录账号才能更新分享')));
      return;
    }

    setState(() => _isUpdating = true);
    try {
      final package = await _repo.exportCharacterGroupPackage(groupId);
      final result = await _communityService.updateGroup(
        session: session,
        communityId: _communityId!,
        package: package,
        isPublic: true,
      );
      if (!mounted) return;
      setState(() => _shareCode = result.shareCode);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('已更新分享内容')));
      // 通知父页面刷新群组列表
      Navigator.pop(context, true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('更新失败：$e')));
    } finally {
      if (mounted) setState(() => _isUpdating = false);
    }
  }

  Future<void> _deleteShare() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('确认删除分享'),
        content: const Text('删除后，其他用户将无法通过分享码或社区搜索找到此群组。此操作不可撤销。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            child: const Text('确认删除'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    if (_communityId == null) return;

    final session = await CloudAccountService.loadSession();
    if (session == null) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('请先登录账号才能删除分享')));
      return;
    }

    setState(() => _isDeleting = true);
    try {
      await _communityService.deleteGroup(
        session: session,
        communityId: _communityId!,
      );
      // 同步清除数据库中的分享信息
      final groupId = _asInt(widget.group['id']);
      if (groupId != null) {
        await _repo.updateCharacterGroupCommunityInfo(
          groupId: groupId,
          communityId: null,
          shareCode: null,
        );
      }
      if (!mounted) return;
      setState(() {
        _communityId = null;
        _shareCode = null;
      });
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('已删除分享')));
      // 通知父页面刷新群组列表
      Navigator.pop(context, true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('删除失败：$e')));
    } finally {
      if (mounted) setState(() => _isDeleting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.sizeOf(context);
    final dialogWidth = math.min(math.max(320.0, size.width - 48), 660.0);
    final dialogHeight = math.min(math.max(420.0, size.height - 180), 560.0);
    final groupName = _asText(widget.group['name']) ?? '未命名群组';

    return AlertDialog(
      title: Text(groupName),
      content: SizedBox(
        width: dialogWidth,
        height: dialogHeight,
        child: _isLoading
            ? const Center(child: CircularProgressIndicator())
            : Column(
                children: [
                  _buildSectionTitle(context, '群组信息'),
                  Row(
                    children: [
                      Chip(label: Text('${_characters.length} 个角色')),
                      const SizedBox(width: 8),
                      Chip(label: Text('${_works.length} 部作品')),
                      if (_communityId != null) ...[
                        const SizedBox(width: 8),
                        Chip(
                          label: const Text('已分享'),
                          backgroundColor: const Color(0xFFD9F9D9),
                        ),
                      ],
                    ],
                  ),
                  if (_shareCode != null) ...[
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        const Text(
                          '分享码：',
                          style: TextStyle(fontWeight: FontWeight.w500),
                        ),
                        SelectableText(
                          _shareCode!,
                          style: const TextStyle(color: Colors.blue),
                        ),
                        const SizedBox(width: 4),
                        InkWell(
                          onTap: _copyShareCode,
                          borderRadius: BorderRadius.circular(4),
                          child: const Padding(
                            padding: EdgeInsets.all(4),
                            child: Icon(
                              Icons.copy,
                              size: 16,
                              color: Colors.blue,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ],
                  const SizedBox(height: 16),
                  _buildSectionTitle(context, '角色'),
                  Expanded(
                    child: ListView.builder(
                      shrinkWrap: true,
                      itemCount: _characters.length,
                      itemBuilder: (context, index) {
                        final character = _characters[index];
                        return ListTile(
                          leading: _CharacterPortrait(
                            url: _asText(character['image_url']),
                            width: 36,
                            height: 48,
                            radius: 8,
                          ),
                          title: Text(
                            _asText(character['name_cn']) ??
                                _asText(character['name']) ??
                                '未知',
                          ),
                          subtitle: Text(_characterListSubtitle(character)),
                        );
                      },
                    ),
                  ),
                  const SizedBox(height: 16),
                  _buildSectionTitle(context, '作品'),
                  Flexible(
                    child: ListView.builder(
                      scrollDirection: Axis.horizontal,
                      itemCount: _works.length,
                      itemBuilder: (context, index) {
                        final work = _works[index];
                        return Padding(
                          padding: const EdgeInsets.only(right: 8),
                          child: Column(
                            children: [
                              AnimeCoverImage(
                                url: _asText(work['cover_url']),
                                width: 72,
                                height: 96,
                              ),
                              const SizedBox(height: 4),
                              SizedBox(
                                width: 72,
                                child: Text(
                                  _asText(work['title']) ?? '未知',
                                  textAlign: TextAlign.center,
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                  style: const TextStyle(fontSize: 12),
                                ),
                              ),
                            ],
                          ),
                        );
                      },
                    ),
                  ),
                ],
              ),
      ),
      actions: [
        if (_isLoggedIn) ...[
          if (_communityId != null) ...[
            TextButton.icon(
              onPressed: _isUpdating ? null : _updateShare,
              icon: _isUpdating
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.refresh_outlined),
              label: const Text('更新分享'),
            ),
            TextButton.icon(
              onPressed: _isDeleting ? null : _deleteShare,
              icon: _isDeleting
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.delete_outline, color: Colors.red),
              label: const Text('删除分享', style: TextStyle(color: Colors.red)),
            ),
          ] else
            TextButton.icon(
              onPressed: _isSharing ? null : _shareToCommunity,
              icon: _isSharing
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.share_outlined),
              label: const Text('分享到社区'),
            ),
        ],
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('关闭'),
        ),
      ],
    );
  }

  Widget _buildSectionTitle(BuildContext context, String title) {
    return Text(
      title,
      style: Theme.of(
        context,
      ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w800),
    );
  }
}

class _ShareCodeImportDialog extends StatefulWidget {
  const _ShareCodeImportDialog();

  @override
  State<_ShareCodeImportDialog> createState() => _ShareCodeImportDialogState();
}

class _ShareCodeImportDialogState extends State<_ShareCodeImportDialog> {
  final TextEditingController _codeController = TextEditingController();
  final CharacterGroupCommunityService _communityService =
      const CharacterGroupCommunityService();
  final CharacterRepository _repo = getIt<CharacterRepository>();
  bool _isLoading = false;

  @override
  void dispose() {
    _codeController.dispose();
    super.dispose();
  }

  Future<void> _handleImport() async {
    final code = _codeController.text.trim().toUpperCase();
    if (code.isEmpty) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('请输入分享码')));
      return;
    }

    setState(() => _isLoading = true);
    try {
      final package = await _communityService.fetchByShareCode(code);
      final result = await _repo.importCharacterGroupPackage(
        package,
        source: 'share_code',
      );
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            '导入成功！新增 ${result.createdCharacterCount} 个角色，${result.createdWorkCount} 部作品',
          ),
        ),
      );
      Navigator.pop(context, true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('导入失败：$e')));
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('导入角色群组'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Text('请输入分享码'),
          const SizedBox(height: 12),
          TextField(
            controller: _codeController,
            textCapitalization: TextCapitalization.characters,
            decoration: const InputDecoration(
              hintText: '输入8位分享码',
              border: OutlineInputBorder(),
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消'),
        ),
        TextButton(
          onPressed: _isLoading ? null : _handleImport,
          child: _isLoading
              ? const SizedBox(
                  width: 20,
                  height: 20,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Text('导入'),
        ),
      ],
    );
  }
}

class _CommunityImportDialog extends StatefulWidget {
  const _CommunityImportDialog();

  @override
  State<_CommunityImportDialog> createState() => _CommunityImportDialogState();
}

class _CommunityImportDialogState extends State<_CommunityImportDialog> {
  final TextEditingController _searchController = TextEditingController();
  final CharacterGroupCommunityService _communityService =
      const CharacterGroupCommunityService();
  final CharacterRepository _repo = getIt<CharacterRepository>();
  List<CommunityCharacterGroupInfo> _groups = const [];
  bool _isLoading = false;
  bool _isImporting = false;

  @override
  void initState() {
    super.initState();
    _loadGroups();
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _loadGroups({String? query}) async {
    setState(() => _isLoading = true);
    try {
      _groups = await _communityService.listGroups(query: query);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('加载社区群组失败：$e')));
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _handleImport(CommunityCharacterGroupInfo group) async {
    setState(() => _isImporting = true);
    try {
      final package = await _communityService.fetchGroup(group.id);
      final result = await _repo.importCharacterGroupPackage(
        package,
        source: 'community',
      );
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            '导入成功！新增 ${result.createdCharacterCount} 个角色，${result.createdWorkCount} 部作品',
          ),
        ),
      );
      Navigator.pop(context, true);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('导入失败：$e')));
    } finally {
      if (mounted) setState(() => _isImporting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.sizeOf(context);
    final dialogWidth = math.min(math.max(320.0, size.width - 48), 560.0);
    final dialogHeight = math.min(math.max(420.0, size.height - 180), 560.0);

    return AlertDialog(
      title: const Text('从社区导入'),
      content: SizedBox(
        width: dialogWidth,
        height: dialogHeight,
        child: Column(
          children: [
            TextField(
              controller: _searchController,
              decoration: InputDecoration(
                hintText: '搜索群组',
                suffixIcon: IconButton(
                  icon: const Icon(Icons.search),
                  onPressed: () =>
                      _loadGroups(query: _searchController.text.trim()),
                ),
              ),
              onSubmitted: (value) => _loadGroups(query: value.trim()),
            ),
            const SizedBox(height: 12),
            Expanded(
              child: _isLoading
                  ? const Center(child: CircularProgressIndicator())
                  : _groups.isEmpty
                  ? const Center(child: Text('暂无社区群组'))
                  : ListView.builder(
                      itemCount: _groups.length,
                      itemBuilder: (context, index) {
                        final group = _groups[index];
                        return ListTile(
                          leading: group.coverUrl != null
                              ? AnimeCoverImage(
                                  url: group.coverUrl!,
                                  width: 48,
                                  height: 48,
                                )
                              : const Icon(Icons.group, size: 48),
                          title: Text(group.name),
                          subtitle: Text(
                            '${group.characterCount} 角色 · ${group.workCount} 作品 · ${group.downloadCount} 下载',
                          ),
                          trailing: ElevatedButton(
                            onPressed: _isImporting
                                ? null
                                : () => _handleImport(group),
                            child: _isImporting
                                ? const SizedBox(
                                    width: 20,
                                    height: 20,
                                    child: CircularProgressIndicator(
                                      strokeWidth: 2,
                                    ),
                                  )
                                : const Text('导入'),
                          ),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('关闭'),
        ),
      ],
    );
  }
}

String? _asText(dynamic value) {
  final text = value?.toString().trim();
  return text == null || text.isEmpty ? null : text;
}

int? _asInt(dynamic value) {
  if (value == null) return null;
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value.toString());
}

String _characterListSubtitle(Map<String, dynamic> item) {
  final workCount = _asInt(item['work_count']) ?? 0;
  final relationCount = _asInt(item['relation_count']) ?? 0;
  final tagCount = _asInt(item['tag_count']) ?? 0;
  final parts = <String>[];
  if (workCount > 0) parts.add('$workCount 部作品');
  if (relationCount > 0) parts.add('$relationCount 条关系');
  if (tagCount > 0) parts.add('$tagCount 个标签');
  return parts.isEmpty ? '暂无数据' : parts.join(' · ');
}

String _workTypeLabel(String? type) {
  switch (type) {
    case 'tv':
      return '动画';
    case 'movie':
      return '剧场版';
    case 'ova':
      return 'OVA';
    case 'special':
      return '特别篇';
    case 'music':
      return '音乐';
    case 'novel':
      return '小说';
    case 'manga':
      return '漫画';
    default:
      return type ?? '';
  }
}

String _characterDisplayName(Map<String, dynamic> item) {
  final nameCn = _asText(item['name_cn']);
  if (nameCn != null) return nameCn;
  return _asText(item['name']) ?? '未命名角色';
}

String _relatedCharacterName(Map<String, dynamic> item) {
  final nameCn = _asText(item['related_name_cn']);
  if (nameCn != null) return nameCn;
  return _asText(item['related_name']) ?? '未命名角色';
}

String _shortName(String value) {
  final text = value.trim();
  if (text.length <= 4) return text;
  return text.substring(0, 4);
}

String _workSubtitle(Map<String, dynamic> work) {
  final parts = <String>[_workTypeLabel(work['subject_type'])];
  final role = _asText(work['role_name']);
  if (role != null) parts.add(role);
  final status = _asText(work['status']);
  if (status != null) parts.add(status);
  return parts.join(' · ');
}

String _relationSubtitle(Map<String, dynamic> relation) {
  final parts = <String>[
    _asText(relation['relation_type']) ?? '关联',
    '强度 ${_asInt(relation['strength']) ?? 3}',
  ];
  final note = _asText(relation['note']);
  if (note != null) parts.add(note);
  return parts.join(' · ');
}

String? _genderText(dynamic value) {
  final text = _asText(value);
  if (text == null) return null;
  switch (text.toLowerCase()) {
    case 'male':
      return '男';
    case 'female':
      return '女';
    default:
      return text;
  }
}

String? _birthdayText(Map<String, dynamic> item) {
  final year = _asInt(item['birth_year']);
  final month = _asInt(item['birth_mon']);
  final day = _asInt(item['birth_day']);
  if (year == null && month == null && day == null) return null;

  final buffer = StringBuffer();
  if (year != null) buffer.write('$year年');
  if (month != null) buffer.write('$month月');
  if (day != null) buffer.write('$day日');
  return buffer.toString();
}

int? _normalizedRating(dynamic value) {
  final rating = _asInt(value);
  if (rating == null) return null;
  return rating.clamp(1, 10).toInt();
}

int _compareRelationCandidates(
  Map<String, dynamic> source,
  Map<String, dynamic> first,
  Map<String, dynamic> second,
) {
  final sharedCompare = (_asInt(second['shared_work_count']) ?? 0).compareTo(
    _asInt(first['shared_work_count']) ?? 0,
  );
  if (sharedCompare != 0) return sharedCompare;

  final firstNames = <String>{};
  final firstCn = _asText(first['name_cn']);
  final firstN = _asText(first['name']);
  if (firstCn != null) firstNames.add(firstCn);
  if (firstN != null) firstNames.add(firstN);

  final secondNames = <String>{};
  final secondCn = _asText(second['name_cn']);
  final secondN = _asText(second['name']);
  if (secondCn != null) secondNames.add(secondCn);
  if (secondN != null) secondNames.add(secondN);

  final sourceNames = <String>{};
  final sourceCn = _asText(source['name_cn']);
  final sourceN = _asText(source['name']);
  if (sourceCn != null) sourceNames.add(sourceCn);
  if (sourceN != null) sourceNames.add(sourceN);

  double sourceSimilarity(Map<String, dynamic> candidate) {
    final candidateNames = <String>{};
    final cn = _asText(candidate['name_cn']);
    final n = _asText(candidate['name']);
    if (cn != null) candidateNames.add(cn.toLowerCase());
    if (n != null) candidateNames.add(n.toLowerCase());

    var best = 0.0;
    for (final sourceName in sourceNames.map((e) => e.toLowerCase())) {
      for (final candidateName in candidateNames) {
        final shorter = sourceName.length < candidateName.length
            ? sourceName
            : candidateName;
        final longer = sourceName.length >= candidateName.length
            ? sourceName
            : candidateName;
        if (longer.contains(shorter)) {
          best = math.max(best, 0.85 + 0.15 * shorter.length / longer.length);
        } else {
          var common = 0;
          for (final c in shorter.runes) {
            if (longer.runes.contains(c)) common++;
          }
          best = math.max(best, common / longer.runes.length);
        }
      }
    }
    return best;
  }

  final similarityCompare = sourceSimilarity(
    second,
  ).compareTo(sourceSimilarity(first));
  if (similarityCompare != 0) return similarityCompare;

  final workCountCompare = (_asInt(second['work_count']) ?? 0).compareTo(
    _asInt(first['work_count']) ?? 0,
  );
  if (workCountCompare != 0) return workCountCompare;

  return _characterDisplayName(first).compareTo(_characterDisplayName(second));
}

String _relationCandidateSubtitle(
  Map<String, dynamic> item,
  Map<String, dynamic>? source,
) {
  final parts = <String>[];
  final sharedCount = _asInt(item['shared_work_count']) ?? 0;
  final workCount = _asInt(item['work_count']) ?? 0;
  if (sharedCount > 0) parts.add('同作品 $sharedCount 部');
  parts.add('$workCount 部作品');
  if (source != null) {
    final sourceNames = <String>{};
    final sourceCn = _asText(source['name_cn']);
    final sourceN = _asText(source['name']);
    if (sourceCn != null) sourceNames.add(sourceCn.toLowerCase());
    if (sourceN != null) sourceNames.add(sourceN.toLowerCase());

    final itemNames = <String>{};
    final itemCn = _asText(item['name_cn']);
    final itemN = _asText(item['name']);
    if (itemCn != null) itemNames.add(itemCn.toLowerCase());
    if (itemN != null) itemNames.add(itemN.toLowerCase());

    var bestSimilarity = 0.0;
    for (final sourceName in sourceNames) {
      for (final itemName in itemNames) {
        final shorter = sourceName.length < itemName.length
            ? sourceName
            : itemName;
        final longer = sourceName.length >= itemName.length
            ? sourceName
            : itemName;
        if (longer.contains(shorter)) {
          bestSimilarity = math.max(
            bestSimilarity,
            0.85 + 0.15 * shorter.length / longer.length,
          );
        }
      }
    }
    if (bestSimilarity >= 0.58) parts.add('姓名相近');
  }
  return parts.join(' · ');
}

class _CommunityGroupImportDialog extends StatefulWidget {
  final CharacterGroupCommunityService service;

  const _CommunityGroupImportDialog({required this.service});

  @override
  State<_CommunityGroupImportDialog> createState() =>
      _CommunityGroupImportDialogState();
}

class _CommunityGroupImportDialogState
    extends State<_CommunityGroupImportDialog> {
  final TextEditingController _queryController = TextEditingController();
  List<CommunityCharacterGroupInfo> _groups = const [];
  bool _isLoading = true;
  bool _isFetching = false;
  String? _errorText;

  @override
  void initState() {
    super.initState();
    _loadGroups();
  }

  @override
  void dispose() {
    _queryController.dispose();
    super.dispose();
  }

  Future<void> _loadGroups() async {
    setState(() {
      _isLoading = true;
      _errorText = null;
    });
    try {
      final groups = await widget.service.listGroups(
        query: _queryController.text.trim(),
      );
      if (!mounted) return;
      setState(() {
        _groups = groups;
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _errorText = e.toString();
        _isLoading = false;
      });
    }
  }

  Future<void> _selectGroup(CommunityCharacterGroupInfo group) async {
    if (group.id.isEmpty || _isFetching) return;
    setState(() {
      _isFetching = true;
      _errorText = null;
    });
    try {
      final package = await widget.service.fetchGroup(group.id);
      if (!mounted) return;
      Navigator.pop(context, package);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _errorText = e.toString();
        _isFetching = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.sizeOf(context);
    final dialogWidth = math.min(math.max(320.0, size.width - 48), 620.0);
    final dialogHeight = math.min(math.max(420.0, size.height - 180), 560.0);

    return AlertDialog(
      title: const Text('社区导入'),
      content: SizedBox(
        width: dialogWidth,
        height: dialogHeight,
        child: Column(
          children: [
            TextField(
              controller: _queryController,
              textInputAction: TextInputAction.search,
              decoration: InputDecoration(
                prefixIcon: const Icon(Icons.search),
                hintText: '搜索社区角色群组',
                suffixIcon: IconButton(
                  tooltip: '搜索',
                  onPressed: _loadGroups,
                  icon: const Icon(Icons.search),
                ),
              ),
              onSubmitted: (_) => _loadGroups(),
            ),
            if (_errorText != null) ...[
              const SizedBox(height: 8),
              Text(
                _errorText!,
                style: const TextStyle(color: Colors.redAccent, fontSize: 12),
              ),
            ],
            const SizedBox(height: 12),
            Expanded(
              child: _isLoading
                  ? const Center(child: CircularProgressIndicator())
                  : _groups.isEmpty
                  ? const Center(child: Text('还没有可导入的社区群组'))
                  : ListView.separated(
                      itemCount: _groups.length,
                      separatorBuilder: (_, _) => const SizedBox(height: 8),
                      itemBuilder: (context, index) {
                        final group = _groups[index];
                        return ListTile(
                          enabled: !_isFetching,
                          leading: CircleAvatar(
                            child: Text(group.characterCount.toString()),
                          ),
                          title: Text(
                            group.name,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                          subtitle: Text(
                            '${group.characterCount} 个角色 · ${group.workCount} 部作品 · 导入 ${group.downloadCount} 次',
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                          ),
                          trailing: _isFetching
                              ? const SizedBox(
                                  width: 20,
                                  height: 20,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : const Icon(Icons.chevron_right),
                          onTap: () => _selectGroup(group),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消'),
        ),
      ],
    );
  }
}

class _CharacterGroupDraft {
  final int? groupId;
  final String name;
  final String? description;
  final List<int> characterIds;
  final List<int> workIds;

  const _CharacterGroupDraft({
    this.groupId,
    required this.name,
    this.description,
    required this.characterIds,
    required this.workIds,
  });
}

class _AddCharacterDialog extends StatefulWidget {
  const _AddCharacterDialog();

  @override
  State<_AddCharacterDialog> createState() => _AddCharacterDialogState();
}

class _AddCharacterDialogState extends State<_AddCharacterDialog> {
  final TextEditingController _controller = TextEditingController();
  List<BangumiCharacter> _results = const [];
  bool _isSearching = false;
  String? _errorText;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _search() async {
    final query = _controller.text.trim();
    if (query.isEmpty || _isSearching) return;

    setState(() {
      _isSearching = true;
      _errorText = null;
    });

    try {
      final results = await BangumiService.searchCharacters(query);
      if (!mounted) return;
      setState(() => _results = results);
    } catch (e) {
      if (!mounted) return;
      setState(() => _errorText = e.toString());
    } finally {
      if (mounted) setState(() => _isSearching = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('添加角色'),
      content: SizedBox(
        width: 520,
        height: 440,
        child: Column(
          children: [
            TextField(
              controller: _controller,
              autofocus: true,
              textInputAction: TextInputAction.search,
              decoration: InputDecoration(
                hintText: '输入角色名，例如 鲁路修',
                suffixIcon: IconButton(
                  onPressed: _isSearching ? null : _search,
                  icon: const Icon(Icons.search),
                ),
              ),
              onSubmitted: (_) => _search(),
            ),
            if (_errorText != null) ...[
              const SizedBox(height: 8),
              Text(
                _errorText!,
                style: const TextStyle(color: Colors.redAccent, fontSize: 12),
              ),
            ],
            const SizedBox(height: 12),
            Expanded(
              child: _isSearching
                  ? const Center(child: CircularProgressIndicator())
                  : _results.isEmpty
                  ? const Center(child: Text('搜索后选择要加入角色库的角色'))
                  : ListView.builder(
                      itemCount: _results.length,
                      itemBuilder: (context, index) {
                        final item = _results[index];
                        return ListTile(
                          leading: _CharacterPortrait(
                            url: item.imageUrl,
                            width: 44,
                            height: 56,
                            radius: 8,
                          ),
                          title: Text(item.displayName),
                          subtitle: item.originalName == item.displayName
                              ? null
                              : Text(item.originalName),
                          trailing: const Icon(Icons.add),
                          onTap: () => Navigator.pop(context, item),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('关闭'),
        ),
      ],
    );
  }
}

class _LinkWorkDialog extends StatefulWidget {
  final int characterId;

  const _LinkWorkDialog({required this.characterId});

  @override
  State<_LinkWorkDialog> createState() => _LinkWorkDialogState();
}

class _LinkWorkDialogState extends State<_LinkWorkDialog> {
  final CharacterRepository _repo = getIt<CharacterRepository>();
  final TextEditingController _queryController = TextEditingController();
  final TextEditingController _roleController = TextEditingController();
  final Map<int, Map<String, dynamic>> _selectedWorks = {};
  List<Map<String, dynamic>> _works = const [];
  String _subjectType = 'all';
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _search();
  }

  @override
  void dispose() {
    _queryController.dispose();
    _roleController.dispose();
    super.dispose();
  }

  Future<void> _search() async {
    setState(() => _isLoading = true);
    final rows = await _repo.searchLinkableWorks(
      characterId: widget.characterId,
      query: _queryController.text.trim(),
      subjectType: _subjectType,
    );
    if (!mounted) return;
    setState(() {
      _works = rows;
      _isLoading = false;
    });
  }

  void _toggleWork(Map<String, dynamic> work) {
    final id = _asInt(work['id']);
    if (id == null) return;
    setState(() {
      if (_selectedWorks.containsKey(id)) {
        _selectedWorks.remove(id);
      } else {
        _selectedWorks[id] = work;
      }
    });
  }

  void _selectVisibleWorks() {
    setState(() {
      for (final work in _works) {
        final id = _asInt(work['id']);
        if (id != null) _selectedWorks[id] = work;
      }
    });
  }

  void _clearSelection() {
    setState(_selectedWorks.clear);
  }

  void _submitSelection() {
    if (_selectedWorks.isEmpty) return;
    Navigator.pop(
      context,
      _WorkLinkDraft(
        works: _selectedWorks.values.toList(growable: false),
        roleName: _asText(_roleController.text),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final selectedCount = _selectedWorks.length;

    return AlertDialog(
      title: const Text('关联作品'),
      content: SizedBox(
        width: 560,
        height: 560,
        child: Column(
          children: [
            TextField(
              controller: _queryController,
              textInputAction: TextInputAction.search,
              decoration: InputDecoration(
                prefixIcon: const Icon(Icons.search),
                hintText: '搜索本地动画或小说',
                suffixIcon: IconButton(
                  onPressed: _search,
                  icon: const Icon(Icons.search),
                ),
              ),
              onSubmitted: (_) => _search(),
            ),
            const SizedBox(height: 10),
            SegmentedButton<String>(
              segments: const [
                ButtonSegment(value: 'all', label: Text('全部')),
                ButtonSegment(value: 'anime', label: Text('动画')),
                ButtonSegment(value: 'book', label: Text('小说')),
              ],
              selected: {_subjectType},
              onSelectionChanged: (value) {
                setState(() => _subjectType = value.first);
                _search();
              },
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _roleController,
              decoration: const InputDecoration(
                prefixIcon: Icon(Icons.badge_outlined),
                hintText: '角色定位，可选，例如 主角、配角',
              ),
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                Text('已选择 $selectedCount 部作品'),
                const Spacer(),
                TextButton.icon(
                  onPressed: _works.isEmpty ? null : _selectVisibleWorks,
                  icon: const Icon(Icons.done_all_outlined),
                  label: const Text('全选当前'),
                ),
                TextButton.icon(
                  onPressed: selectedCount == 0 ? null : _clearSelection,
                  icon: const Icon(Icons.clear_all_outlined),
                  label: const Text('清空'),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Expanded(
              child: _isLoading
                  ? const Center(child: CircularProgressIndicator())
                  : _works.isEmpty
                  ? const Center(child: Text('没有可关联的作品'))
                  : ListView.builder(
                      itemCount: _works.length,
                      itemBuilder: (context, index) {
                        final work = _works[index];
                        final id = _asInt(work['id']);
                        final selected =
                            id != null && _selectedWorks.containsKey(id);
                        return ListTile(
                          leading: ClipRRect(
                            borderRadius: BorderRadius.circular(8),
                            child: AnimeCoverImage(
                              url: _asText(work['cover_url']),
                              width: 42,
                              height: 56,
                              fit: BoxFit.cover,
                            ),
                          ),
                          title: Text(
                            _asText(work['title']) ?? '未命名作品',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                          subtitle: Text(_workTypeLabel(work['subject_type'])),
                          trailing: Checkbox(
                            value: selected,
                            onChanged: id == null
                                ? null
                                : (_) => _toggleWork(work),
                          ),
                          onTap: id == null ? null : () => _toggleWork(work),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('关闭'),
        ),
        FilledButton.icon(
          onPressed: selectedCount == 0 ? null : _submitSelection,
          icon: const Icon(Icons.add_link),
          label: Text(selectedCount == 0 ? '关联' : '关联 $selectedCount 部'),
        ),
      ],
    );
  }
}

class _RelationDialog extends StatefulWidget {
  final int sourceCharacterId;

  const _RelationDialog({required this.sourceCharacterId});

  @override
  State<_RelationDialog> createState() => _RelationDialogState();
}

class _RelationDialogState extends State<_RelationDialog> {
  final CharacterRepository _repo = getIt<CharacterRepository>();
  final TextEditingController _queryController = TextEditingController();
  final TextEditingController _typeController = TextEditingController(
    text: '关联',
  );
  final TextEditingController _noteController = TextEditingController();
  List<Map<String, dynamic>> _characters = const [];
  Map<String, dynamic>? _sourceCharacter;
  int? _targetCharacterId;
  double _strength = 3;
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _loadCharacters();
  }

  @override
  void dispose() {
    _queryController.dispose();
    _typeController.dispose();
    _noteController.dispose();
    super.dispose();
  }

  Future<void> _loadCharacters() async {
    setState(() => _isLoading = true);
    final source =
        _sourceCharacter ??
        await _repo.getCharacterById(widget.sourceCharacterId);
    final rows = await _repo.getRelationCandidates(
      sourceCharacterId: widget.sourceCharacterId,
      query: _queryController.text.trim(),
    );
    final characters = rows.toList();
    if (source != null) {
      characters.sort((a, b) => _compareRelationCandidates(source, a, b));
    }
    if (!mounted) return;
    setState(() {
      _sourceCharacter = source;
      _characters = characters;
      _isLoading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('添加角色关系'),
      content: SizedBox(
        width: 560,
        height: 560,
        child: Column(
          children: [
            TextField(
              controller: _queryController,
              textInputAction: TextInputAction.search,
              decoration: InputDecoration(
                prefixIcon: const Icon(Icons.search),
                hintText: '搜索目标角色',
                suffixIcon: IconButton(
                  onPressed: _loadCharacters,
                  icon: const Icon(Icons.search),
                ),
              ),
              onSubmitted: (_) => _loadCharacters(),
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _typeController,
                    decoration: const InputDecoration(
                      prefixIcon: Icon(Icons.label_outline),
                      hintText: '关系，例如 朋友、家人、对手',
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                SizedBox(
                  width: 170,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('强度 ${_strength.round()}'),
                      Slider(
                        min: 1,
                        max: 5,
                        divisions: 4,
                        value: _strength,
                        onChanged: (value) => setState(() {
                          _strength = value;
                        }),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 10),
            TextField(
              controller: _noteController,
              minLines: 1,
              maxLines: 2,
              decoration: const InputDecoration(
                prefixIcon: Icon(Icons.notes_outlined),
                hintText: '备注，可选',
              ),
            ),
            const SizedBox(height: 12),
            Expanded(
              child: _isLoading
                  ? const Center(child: CircularProgressIndicator())
                  : _characters.isEmpty
                  ? const Center(child: Text('没有可关联的角色'))
                  : ListView.builder(
                      itemCount: _characters.length,
                      itemBuilder: (context, index) {
                        final item = _characters[index];
                        final id = _asInt(item['id']);
                        final selected = id == _targetCharacterId;
                        return ListTile(
                          leading: _CharacterPortrait(
                            url: _asText(item['image_url']),
                            width: 42,
                            height: 54,
                            radius: 8,
                          ),
                          title: Text(_characterDisplayName(item)),
                          subtitle: Text(
                            _relationCandidateSubtitle(item, _sourceCharacter),
                          ),
                          trailing: Icon(
                            selected
                                ? Icons.radio_button_checked
                                : Icons.radio_button_unchecked,
                          ),
                          onTap: id == null
                              ? null
                              : () => setState(() {
                                  _targetCharacterId = id;
                                }),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: _targetCharacterId == null
              ? null
              : () => Navigator.pop(
                  context,
                  _RelationDraft(
                    targetCharacterId: _targetCharacterId!,
                    relationType: _asText(_typeController.text) ?? '关联',
                    note: _asText(_noteController.text),
                    strength: _strength.round(),
                  ),
                ),
          child: const Text('保存'),
        ),
      ],
    );
  }
}

class _RelationWorkSyncDialog extends StatefulWidget {
  final String sourceName;
  final String targetName;
  final List<Map<String, dynamic>> targetWorksMissingFromSource;
  final List<Map<String, dynamic>> sourceWorksMissingFromTarget;

  const _RelationWorkSyncDialog({
    required this.sourceName,
    required this.targetName,
    required this.targetWorksMissingFromSource,
    required this.sourceWorksMissingFromTarget,
  });

  @override
  State<_RelationWorkSyncDialog> createState() =>
      _RelationWorkSyncDialogState();
}

class _RelationWorkSyncDialogState extends State<_RelationWorkSyncDialog> {
  late final Map<int, Map<String, dynamic>> _targetWorksToSource;
  late final Map<int, Map<String, dynamic>> _sourceWorksToTarget;

  @override
  void initState() {
    super.initState();
    _targetWorksToSource = _indexWorks(widget.targetWorksMissingFromSource);
    _sourceWorksToTarget = _indexWorks(widget.sourceWorksMissingFromTarget);
  }

  int get _selectedCount =>
      _targetWorksToSource.length + _sourceWorksToTarget.length;

  Map<int, Map<String, dynamic>> _indexWorks(List<Map<String, dynamic>> works) {
    final indexedWorks = <int, Map<String, dynamic>>{};
    for (final work in works) {
      final id = _asInt(work['id']);
      if (id != null) indexedWorks[id] = work;
    }
    return indexedWorks;
  }

  void _toggleWork(
    Map<int, Map<String, dynamic>> selectedWorks,
    Map<String, dynamic> work,
    bool selected,
  ) {
    final id = _asInt(work['id']);
    if (id == null) return;
    setState(() {
      if (selected) {
        selectedWorks[id] = work;
      } else {
        selectedWorks.remove(id);
      }
    });
  }

  void _setSectionSelection(
    Map<int, Map<String, dynamic>> selectedWorks,
    List<Map<String, dynamic>> works,
    bool selected,
  ) {
    setState(() {
      selectedWorks.clear();
      if (!selected) return;
      selectedWorks.addAll(_indexWorks(works));
    });
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('补齐作品关联'),
      content: SizedBox(
        width: 560,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '发现两个角色的作品关联不一致，可以取消不想补齐的作品。',
                style: Theme.of(context).textTheme.bodyMedium,
              ),
              const SizedBox(height: 12),
              if (widget.targetWorksMissingFromSource.isNotEmpty)
                _buildSection(
                  title: '给「${widget.sourceName}」关联作品',
                  works: widget.targetWorksMissingFromSource,
                  selectedWorks: _targetWorksToSource,
                ),
              if (widget.sourceWorksMissingFromTarget.isNotEmpty)
                _buildSection(
                  title: '给「${widget.targetName}」关联作品',
                  works: widget.sourceWorksMissingFromTarget,
                  selectedWorks: _sourceWorksToTarget,
                ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('跳过'),
        ),
        FilledButton.icon(
          onPressed: _selectedCount == 0
              ? null
              : () => Navigator.pop(
                  context,
                  _RelationWorkSyncDraft(
                    targetWorksToSource: _targetWorksToSource.values.toList(
                      growable: false,
                    ),
                    sourceWorksToTarget: _sourceWorksToTarget.values.toList(
                      growable: false,
                    ),
                  ),
                ),
          icon: const Icon(Icons.add_link),
          label: Text(_selectedCount == 0 ? '补齐' : '补齐 $_selectedCount 部'),
        ),
      ],
    );
  }

  Widget _buildSection({
    required String title,
    required List<Map<String, dynamic>> works,
    required Map<int, Map<String, dynamic>> selectedWorks,
  }) {
    final allSelected = selectedWorks.length == works.length;

    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  '$title（${selectedWorks.length}/${works.length}）',
                  style: Theme.of(
                    context,
                  ).textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
                ),
              ),
              TextButton(
                onPressed: () =>
                    _setSectionSelection(selectedWorks, works, !allSelected),
                child: Text(allSelected ? '清空' : '全选'),
              ),
            ],
          ),
          const SizedBox(height: 4),
          for (final work in works)
            CheckboxListTile(
              dense: true,
              contentPadding: EdgeInsets.zero,
              controlAffinity: ListTileControlAffinity.leading,
              value: selectedWorks.containsKey(_asInt(work['id'])),
              onChanged: (value) =>
                  _toggleWork(selectedWorks, work, value ?? false),
              title: Text(
                _asText(work['title']) ?? '未命名作品',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              subtitle: Text(_workTypeLabel(work['subject_type'])),
            ),
        ],
      ),
    );
  }
}

class _CharacterTagsDialog extends StatefulWidget {
  final String characterName;
  final Set<int> selectedTagIds;

  const _CharacterTagsDialog({
    required this.characterName,
    required this.selectedTagIds,
  });

  @override
  State<_CharacterTagsDialog> createState() => _CharacterTagsDialogState();
}

class _CharacterTagsDialogState extends State<_CharacterTagsDialog> {
  final CharacterRepository _repo = getIt<CharacterRepository>();
  final TextEditingController _tagController = TextEditingController();
  late final Set<int> _selectedTagIds;
  List<Map<String, dynamic>> _tags = const [];
  bool _isLoading = true;
  bool _isCreating = false;

  @override
  void initState() {
    super.initState();
    _selectedTagIds = {...widget.selectedTagIds};
    _loadTags();
  }

  @override
  void dispose() {
    _tagController.dispose();
    super.dispose();
  }

  Future<void> _loadTags() async {
    setState(() => _isLoading = true);
    final tags = await _repo.getAllCharacterTags();
    if (!mounted) return;
    setState(() {
      _tags = tags;
      _isLoading = false;
    });
  }

  Future<void> _createTag() async {
    final tagName = _asText(_tagController.text);
    if (tagName == null || _isCreating) return;

    setState(() => _isCreating = true);
    try {
      final tagId = await _repo.createCharacterTag(tagName);
      final tags = await _repo.getAllCharacterTags();
      if (!mounted) return;
      setState(() {
        _tags = tags;
        if (tagId > 0) _selectedTagIds.add(tagId);
        _tagController.clear();
        _isCreating = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _isCreating = false);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('创建角色标签失败：$e')));
    }
  }

  void _toggleTag(int tagId, bool selected) {
    setState(() {
      if (selected) {
        _selectedTagIds.add(tagId);
      } else {
        _selectedTagIds.remove(tagId);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('编辑${widget.characterName}的标签'),
      content: SizedBox(
        width: 520,
        height: 520,
        child: Column(
          children: [
            TextField(
              controller: _tagController,
              textInputAction: TextInputAction.done,
              decoration: InputDecoration(
                prefixIcon: const Icon(Icons.sell_outlined),
                hintText: '新建标签，例如 推、主角、反派',
                suffixIcon: IconButton(
                  tooltip: '添加标签',
                  onPressed: _isCreating ? null : _createTag,
                  icon: _isCreating
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.add),
                ),
              ),
              onChanged: (_) => setState(() {}),
              onSubmitted: (_) => _createTag(),
            ),
            const SizedBox(height: 12),
            Expanded(
              child: _isLoading
                  ? const Center(child: CircularProgressIndicator())
                  : _tags.isEmpty
                  ? const Center(child: Text('还没有角色标签'))
                  : ListView.builder(
                      itemCount: _tags.length,
                      itemBuilder: (context, index) {
                        final tag = _tags[index];
                        final tagId = _asInt(tag['id']);
                        final selected =
                            tagId != null && _selectedTagIds.contains(tagId);
                        return CheckboxListTile(
                          value: selected,
                          onChanged: tagId == null
                              ? null
                              : (value) => _toggleTag(tagId, value ?? false),
                          title: Text(_asText(tag['name']) ?? '未命名标签'),
                          subtitle: Text('${_asInt(tag['count']) ?? 0} 个角色'),
                          controlAffinity: ListTileControlAffinity.leading,
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消'),
        ),
        FilledButton.icon(
          onPressed: () => Navigator.pop(context, _selectedTagIds),
          icon: const Icon(Icons.check),
          label: Text('保存 ${_selectedTagIds.length} 个'),
        ),
      ],
    );
  }
}

class _CharacterReviewDialog extends StatefulWidget {
  final Map<String, dynamic> character;

  const _CharacterReviewDialog({required this.character});

  @override
  State<_CharacterReviewDialog> createState() => _CharacterReviewDialogState();
}

class _CharacterReviewDialogState extends State<_CharacterReviewDialog> {
  late final TextEditingController _reviewController;
  int? _rating;

  @override
  void initState() {
    super.initState();
    _rating = _normalizedRating(widget.character['rating']);
    _reviewController = TextEditingController(
      text: _asText(widget.character['review']) ?? '',
    );
  }

  @override
  void dispose() {
    _reviewController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: Text('评价${_characterDisplayName(widget.character)}'),
      content: SizedBox(
        width: 520,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('记录评分'),
                subtitle: Text(_rating == null ? '未评分' : '$_rating / 10'),
                value: _rating != null,
                onChanged: (enabled) => setState(() {
                  _rating = enabled ? (_rating ?? 8) : null;
                }),
              ),
              if (_rating != null)
                Row(
                  children: [
                    const Icon(Icons.star_rounded),
                    Expanded(
                      child: Slider(
                        min: 1,
                        max: 10,
                        divisions: 9,
                        label: '${_rating!}分',
                        value: _rating!.toDouble(),
                        onChanged: (value) => setState(() {
                          _rating = value.round();
                        }),
                      ),
                    ),
                    SizedBox(
                      width: 48,
                      child: Text(
                        '${_rating!}分',
                        textAlign: TextAlign.end,
                        style: const TextStyle(fontWeight: FontWeight.w800),
                      ),
                    ),
                  ],
                ),
              const SizedBox(height: 12),
              TextField(
                controller: _reviewController,
                minLines: 4,
                maxLines: 7,
                maxLength: 600,
                decoration: const InputDecoration(
                  prefixIcon: Icon(Icons.rate_review_outlined),
                  labelText: '个人评价',
                  alignLabelWithHint: true,
                  border: OutlineInputBorder(),
                ),
              ),
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消'),
        ),
        TextButton(
          onPressed: () =>
              Navigator.pop(context, const _CharacterReviewDraft()),
          child: const Text('清空'),
        ),
        FilledButton(
          onPressed: () => Navigator.pop(
            context,
            _CharacterReviewDraft(
              rating: _rating,
              review: _asText(_reviewController.text),
            ),
          ),
          child: const Text('保存'),
        ),
      ],
    );
  }
}

class _CharacterReviewDraft {
  final int? rating;
  final String? review;

  const _CharacterReviewDraft({this.rating, this.review});
}

class _WorkLinkDraft {
  final List<Map<String, dynamic>> works;
  final String? roleName;

  const _WorkLinkDraft({required this.works, this.roleName});
}

class _RelationDraft {
  final int targetCharacterId;
  final String relationType;
  final String? note;
  final int strength;

  const _RelationDraft({
    required this.targetCharacterId,
    required this.relationType,
    this.note,
    required this.strength,
  });
}

class _RelationWorkSyncDraft {
  final List<Map<String, dynamic>> targetWorksToSource;
  final List<Map<String, dynamic>> sourceWorksToTarget;

  const _RelationWorkSyncDraft({
    required this.targetWorksToSource,
    required this.sourceWorksToTarget,
  });
}

class _MetaChip extends StatelessWidget {
  final String text;

  const _MetaChip({required this.text});

  @override
  Widget build(BuildContext context) {
    return Chip(
      label: Text(text),
      visualDensity: VisualDensity.compact,
      side: BorderSide.none,
      backgroundColor: Theme.of(context).colorScheme.surfaceContainerHighest,
    );
  }
}

class _InfoRow extends StatelessWidget {
  final _InfoLine row;

  const _InfoRow({required this.row});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 92,
            child: Text(
              row.label,
              style: TextStyle(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          Expanded(child: Text(row.value ?? '')),
        ],
      ),
    );
  }
}

class _InfoLine {
  final String label;
  final String? value;

  const _InfoLine(this.label, this.value);
}

String _characterGroupSubtitle(Map<String, dynamic> group) {
  final parts = <String>[
    '${_asInt(group['character_count']) ?? 0} 个角色',
    '${_asInt(group['work_count']) ?? 0} 部作品',
  ];
  final description = _asText(group['description']);
  if (description != null) parts.add(description);
  return parts.join(' · ');
}

class _DuplicateCharactersDialog extends StatelessWidget {
  final List<List<Map<String, dynamic>>> duplicateGroups;
  final ValueChanged<int> onDelete;

  const _DuplicateCharactersDialog({
    required this.duplicateGroups,
    required this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final totalDuplicates = duplicateGroups.fold(
      0,
      (sum, group) => sum + group.length,
    );

    return AlertDialog(
      title: Row(
        children: [
          const Icon(Icons.warning_amber_outlined),
          const SizedBox(width: 12),
          Text('发现 $totalDuplicates 个重复角色'),
        ],
      ),
      content: SizedBox(
        width: 600,
        height: 500,
        child: ListView.builder(
          padding: const EdgeInsets.all(8),
          itemCount: duplicateGroups.length,
          itemBuilder: (context, groupIndex) {
            final group = duplicateGroups[groupIndex];
            return Padding(
              padding: const EdgeInsets.only(bottom: 16),
              child: Card(
                elevation: 2,
                child: Padding(
                  padding: const EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Padding(
                        padding: const EdgeInsets.only(bottom: 8),
                        child: Text(
                          '重复组 ${groupIndex + 1}（${_characterDisplayName(group.first)}）',
                          style: theme.textTheme.titleSmall?.copyWith(
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ),
                      ...group.map(
                        (character) => _DuplicateCharacterItem(
                          character: character,
                          onDelete: onDelete,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            );
          },
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('关闭'),
        ),
      ],
    );
  }
}

class _DuplicateCharacterItem extends StatelessWidget {
  final Map<String, dynamic> character;
  final ValueChanged<int> onDelete;

  const _DuplicateCharacterItem({
    required this.character,
    required this.onDelete,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final id = _asInt(character['id']) ?? 0;
    final bgmId = _asInt(character['bgm_id']);
    final workCount = _asInt(character['work_count']) ?? 0;
    final relationCount = _asInt(character['relation_count']) ?? 0;
    final tagCount = _asInt(character['tag_count']) ?? 0;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          _CharacterPortrait(
            url: _asText(character['image_url']),
            width: 40,
            height: 52,
            radius: 6,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  _characterDisplayName(character),
                  style: const TextStyle(fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 2),
                Row(
                  children: [
                    if (bgmId != null)
                      Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: Text(
                          'BGM:$bgmId',
                          style: TextStyle(
                            fontSize: 12,
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ),
                    if (workCount > 0)
                      Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: Text(
                          '$workCount 作品',
                          style: TextStyle(
                            fontSize: 12,
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ),
                    if (relationCount > 0)
                      Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: Text(
                          '$relationCount 关系',
                          style: TextStyle(
                            fontSize: 12,
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ),
                    if (tagCount > 0)
                      Text(
                        '$tagCount 标签',
                        style: TextStyle(
                          fontSize: 12,
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                  ],
                ),
              ],
            ),
          ),
          IconButton(
            tooltip: '删除此角色',
            onPressed: () => onDelete(id),
            icon: const Icon(Icons.delete_outline, color: Colors.red),
          ),
        ],
      ),
    );
  }
}
