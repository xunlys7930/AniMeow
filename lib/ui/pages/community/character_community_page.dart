import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../models/character_group_package.dart';
import '../../../repositories/character_repository.dart';
import '../../../services/character_group_community_service.dart';
import '../../../services/service_locator.dart';
import '../../components/adaptive_content_frame.dart';
import '../../components/anime_cover_image.dart';
import '../../components/async_content_view.dart';
import '../../customization/page_display_config.dart';
import '../../design_tokens.dart';
import 'community_display_config.dart';
import 'community_display_sheet.dart';
import 'community_group_detail_page.dart';

class CharacterCommunityPage extends StatefulWidget {
  final CharacterGroupCommunityService? communityService;
  final CharacterRepository? repository;
  final int initialTab;

  const CharacterCommunityPage({
    super.key,
    this.communityService,
    this.repository,
    this.initialTab = 0,
  });

  @override
  State<CharacterCommunityPage> createState() => _CharacterCommunityPageState();
}

class _CharacterCommunityPageState extends State<CharacterCommunityPage>
    with SingleTickerProviderStateMixin {
  late final CharacterGroupCommunityService _communityService;
  late final CharacterRepository _repository;
  late final TabController _tabController;
  late final PageDisplayController _displayController;

  final TextEditingController _communityQueryController =
      TextEditingController();
  final TextEditingController _localQueryController = TextEditingController();

  List<CommunityCharacterGroupInfo> _communityGroups = const [];
  List<Map<String, dynamic>> _localGroups = const [];
  bool _isCommunityLoading = false;
  bool _isLocalLoading = false;
  bool _communityLoaded = false;
  bool _localLoaded = false;
  String? _communityError;
  String? _localError;
  int _communityGeneration = 0;
  int _localGeneration = 0;

  @override
  void initState() {
    super.initState();
    _communityService =
        widget.communityService ?? const CharacterGroupCommunityService();
    _repository = widget.repository ?? getIt<CharacterRepository>();
    _tabController = TabController(
      length: 2,
      vsync: this,
      initialIndex: widget.initialTab.clamp(0, 1),
    )..addListener(_handleTabChange);
    _displayController = PageDisplayController(
      pageId: 'character_community',
      defaults: communityDefaults(),
      knownModules: communityModuleKeys,
      fallbackOrder: communityDefaultOrder,
    )..load();
    _loadCommunityGroups();
    _loadLocalGroups();
  }

  @override
  void dispose() {
    _communityGeneration++;
    _localGeneration++;
    _tabController
      ..removeListener(_handleTabChange)
      ..dispose();
    _displayController.dispose();
    _communityQueryController.dispose();
    _localQueryController.dispose();
    super.dispose();
  }

  void _handleTabChange() {
    if (!_tabController.indexIsChanging && mounted) setState(() {});
  }

  Future<void> _loadCommunityGroups() async {
    final generation = ++_communityGeneration;
    setState(() {
      _isCommunityLoading = true;
      _communityError = null;
    });
    try {
      final groups = await _communityService.listGroups(
        query: _communityQueryController.text.trim(),
      );
      if (!mounted || generation != _communityGeneration) return;
      setState(() {
        _communityGroups = groups;
        _isCommunityLoading = false;
        _communityLoaded = true;
      });
    } catch (error) {
      if (!mounted || generation != _communityGeneration) return;
      setState(() {
        _isCommunityLoading = false;
        _communityError = _cleanCommunityError(error);
      });
    }
  }

  Future<void> _loadLocalGroups() async {
    final generation = ++_localGeneration;
    setState(() {
      _isLocalLoading = true;
      _localError = null;
    });
    try {
      final groups = await _repository.getCharacterGroups(
        query: _localQueryController.text.trim(),
      );
      if (!mounted || generation != _localGeneration) return;
      setState(() {
        _localGroups = groups;
        _isLocalLoading = false;
        _localLoaded = true;
      });
    } catch (error) {
      if (!mounted || generation != _localGeneration) return;
      setState(() {
        _isLocalLoading = false;
        _localError = _cleanCommunityError(error);
      });
    }
  }

  Future<void> _openCommunityDetail(CommunityCharacterGroupInfo group) async {
    if (group.id.trim().isEmpty) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('该社区群组缺少有效 ID')));
      return;
    }
    HapticFeedback.selectionClick();
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => CommunityGroupDetailPage(
          summary: group,
          communityService: _communityService,
          repository: _repository,
        ),
      ),
    );
    if (mounted) await _loadLocalGroups();
  }

  Future<void> _openShareCode() async {
    final code = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      builder: (_) => const _ShareCodeSheet(),
    );
    if (!mounted || code == null || code.trim().isEmpty) return;

    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => CommunityGroupDetailPage(
          shareCode: code.trim(),
          communityService: _communityService,
          repository: _repository,
        ),
      ),
    );
    if (mounted) await _loadLocalGroups();
  }

  Future<void> _openLocalDetail(Map<String, dynamic> group) async {
    await Navigator.of(context).push<void>(
      MaterialPageRoute(
        builder: (_) => LocalCharacterGroupDetailPage(
          group: group,
          repository: _repository,
        ),
      ),
    );
  }

  Future<void> _openDisplaySettings() async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      builder: (_) => CommunityDisplaySheet(controller: _displayController),
    );
  }

  Future<void> _refreshActiveTab() {
    return _tabController.index == 0
        ? _loadCommunityGroups()
        : _loadLocalGroups();
  }

  @override
  Widget build(BuildContext context) {
    final refreshing = _tabController.index == 0
        ? _isCommunityLoading
        : _isLocalLoading;
    return Scaffold(
      appBar: AppBar(
        title: const Text('角色群组社区'),
        actions: [
          IconButton(
            tooltip: '使用分享码',
            onPressed: _openShareCode,
            icon: const Icon(Icons.key_outlined),
          ),
          IconButton(
            tooltip: '定制群组显示',
            onPressed: _openDisplaySettings,
            icon: const Icon(Icons.dashboard_customize_outlined),
          ),
          IconButton(
            tooltip: '刷新',
            onPressed: refreshing ? null : _refreshActiveTab,
            icon: const Icon(Icons.refresh_rounded),
          ),
          const SizedBox(width: AppSpacing.sm),
        ],
        bottom: TabBar(
          controller: _tabController,
          tabs: const [
            Tab(icon: Icon(Icons.travel_explore_rounded), text: '社区发现'),
            Tab(icon: Icon(Icons.folder_copy_outlined), text: '本地群组'),
          ],
        ),
      ),
      body: AnimatedBuilder(
        animation: _displayController,
        builder: (context, _) {
          final config = _displayController.value;
          return TabBarView(
            controller: _tabController,
            children: [
              _CommunityTab(
                queryController: _communityQueryController,
                groups: _communityGroups,
                isLoading: _isCommunityLoading,
                isLoaded: _communityLoaded,
                errorText: _communityError,
                config: config,
                onSearch: _loadCommunityGroups,
                onRefresh: _loadCommunityGroups,
                onOpenShareCode: _openShareCode,
                onOpenGroup: _openCommunityDetail,
              ),
              _LocalGroupsTab(
                queryController: _localQueryController,
                groups: _localGroups,
                isLoading: _isLocalLoading,
                isLoaded: _localLoaded,
                errorText: _localError,
                config: config,
                onSearch: _loadLocalGroups,
                onRefresh: _loadLocalGroups,
                onOpenGroup: _openLocalDetail,
              ),
            ],
          );
        },
      ),
    );
  }
}

class _CommunityTab extends StatelessWidget {
  final TextEditingController queryController;
  final List<CommunityCharacterGroupInfo> groups;
  final bool isLoading;
  final bool isLoaded;
  final String? errorText;
  final PageDisplayConfig config;
  final Future<void> Function() onSearch;
  final Future<void> Function() onRefresh;
  final VoidCallback onOpenShareCode;
  final ValueChanged<CommunityCharacterGroupInfo> onOpenGroup;

  const _CommunityTab({
    required this.queryController,
    required this.groups,
    required this.isLoading,
    required this.isLoaded,
    required this.errorText,
    required this.config,
    required this.onSearch,
    required this.onRefresh,
    required this.onOpenShareCode,
    required this.onOpenGroup,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        AdaptiveContentFrame(
          maxContentWidth: 1200,
          top: AppSpacing.md,
          bottom: AppSpacing.sm,
          child: _SearchHeader(
            controller: queryController,
            title: '发现可复用的角色收藏',
            subtitle: '先查看完整成员与作品，再决定是否导入；本地已有条目会自动复用。',
            hintText: '搜索群组名称或简介',
            onSearch: onSearch,
            trailing: FilledButton.tonalIcon(
              onPressed: onOpenShareCode,
              icon: const Icon(Icons.key_outlined),
              label: const Text('分享码'),
            ),
          ),
        ),
        Expanded(
          child: AsyncContentView(
            isLoading: isLoading,
            hasData: isLoaded,
            isEmpty: groups.isEmpty,
            errorMessage: errorText,
            onRetry: onRefresh,
            emptyIcon: Icons.groups_2_outlined,
            emptyMessage: queryController.text.trim().isEmpty
                ? '社区里还没有公开群组'
                : '没有找到匹配的群组',
            emptyDescription: queryController.text.trim().isEmpty
                ? '你仍可使用分享码打开非公开群组。'
                : '换一个关键词，或清空搜索后重试。',
            child: _CommunityGroupsView(
              groups: groups,
              config: config,
              onRefresh: onRefresh,
              onOpenGroup: onOpenGroup,
            ),
          ),
        ),
      ],
    );
  }
}

class _LocalGroupsTab extends StatelessWidget {
  final TextEditingController queryController;
  final List<Map<String, dynamic>> groups;
  final bool isLoading;
  final bool isLoaded;
  final String? errorText;
  final PageDisplayConfig config;
  final Future<void> Function() onSearch;
  final Future<void> Function() onRefresh;
  final ValueChanged<Map<String, dynamic>> onOpenGroup;

  const _LocalGroupsTab({
    required this.queryController,
    required this.groups,
    required this.isLoading,
    required this.isLoaded,
    required this.errorText,
    required this.config,
    required this.onSearch,
    required this.onRefresh,
    required this.onOpenGroup,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        AdaptiveContentFrame(
          maxContentWidth: 1200,
          top: AppSpacing.md,
          bottom: AppSpacing.sm,
          child: _SearchHeader(
            controller: queryController,
            title: '本地角色群组',
            subtitle: '集中查看手动创建、文件导入、分享码和社区同步的群组。',
            hintText: '搜索本地群组',
            onSearch: onSearch,
          ),
        ),
        Expanded(
          child: AsyncContentView(
            isLoading: isLoading,
            hasData: isLoaded,
            isEmpty: groups.isEmpty,
            errorMessage: errorText,
            onRetry: onRefresh,
            emptyIcon: Icons.folder_copy_outlined,
            emptyMessage: queryController.text.trim().isEmpty
                ? '还没有本地角色群组'
                : '没有找到匹配的本地群组',
            emptyDescription: queryController.text.trim().isEmpty
                ? '可以从社区导入，也可以在角色管理中手动创建。'
                : '换一个关键词，或清空搜索后重试。',
            child: _LocalGroupsView(
              groups: groups,
              config: config,
              onRefresh: onRefresh,
              onOpenGroup: onOpenGroup,
            ),
          ),
        ),
      ],
    );
  }
}

class _SearchHeader extends StatelessWidget {
  final TextEditingController controller;
  final String title;
  final String subtitle;
  final String hintText;
  final Future<void> Function() onSearch;
  final Widget? trailing;

  const _SearchHeader({
    required this.controller,
    required this.title,
    required this.subtitle,
    required this.hintText,
    required this.onSearch,
    this.trailing,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Card(
      margin: EdgeInsets.zero,
      elevation: 0,
      color: colorScheme.surfaceContainerLow,
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: LayoutBuilder(
          builder: (context, constraints) {
            final compact = constraints.maxWidth < AppBreakpoints.compact;
            final heading = Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: Theme.of(
                    context,
                  ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w900),
                ),
                const SizedBox(height: AppSpacing.xs),
                Text(
                  subtitle,
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            );
            final field = TextField(
              controller: controller,
              textInputAction: TextInputAction.search,
              onSubmitted: (_) => onSearch(),
              decoration: InputDecoration(
                hintText: hintText,
                prefixIcon: const Icon(Icons.search_rounded),
                suffixIcon: ValueListenableBuilder<TextEditingValue>(
                  valueListenable: controller,
                  builder: (context, value, _) {
                    if (value.text.isEmpty) {
                      return IconButton(
                        tooltip: '搜索',
                        onPressed: onSearch,
                        icon: const Icon(Icons.arrow_forward_rounded),
                      );
                    }
                    return IconButton(
                      tooltip: '清空并刷新',
                      onPressed: () {
                        controller.clear();
                        onSearch();
                      },
                      icon: const Icon(Icons.close_rounded),
                    );
                  },
                ),
              ),
            );

            if (compact) {
              return Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  heading,
                  const SizedBox(height: AppSpacing.md),
                  field,
                  if (trailing != null) ...[
                    const SizedBox(height: AppSpacing.sm),
                    trailing!,
                  ],
                ],
              );
            }

            return Row(
              children: [
                Expanded(flex: 5, child: heading),
                const SizedBox(width: AppSpacing.xl),
                Expanded(flex: 4, child: field),
                if (trailing != null) ...[
                  const SizedBox(width: AppSpacing.sm),
                  trailing!,
                ],
              ],
            );
          },
        ),
      ),
    );
  }
}

class _CommunityGroupsView extends StatelessWidget {
  final List<CommunityCharacterGroupInfo> groups;
  final PageDisplayConfig config;
  final Future<void> Function() onRefresh;
  final ValueChanged<CommunityCharacterGroupInfo> onOpenGroup;

  const _CommunityGroupsView({
    required this.groups,
    required this.config,
    required this.onRefresh,
    required this.onOpenGroup,
  });

  @override
  Widget build(BuildContext context) {
    final view = communityGroupView(config);
    final padding = _centeredListPadding(context);
    if (view == CommunityGroupView.list) {
      return RefreshIndicator(
        onRefresh: onRefresh,
        child: ListView.separated(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: padding,
          itemCount: groups.length,
          separatorBuilder: (_, _) =>
              SizedBox(height: _densityGap(config.density)),
          itemBuilder: (context, index) => _CommunityGroupCard(
            group: groups[index],
            config: config,
            isGrid: false,
            onTap: () => onOpenGroup(groups[index]),
          ),
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: onRefresh,
      child: GridView.builder(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: padding,
        gridDelegate: SliverGridDelegateWithMaxCrossAxisExtent(
          maxCrossAxisExtent: _gridExtent(config.density),
          mainAxisExtent: _communityCardHeight(config),
          crossAxisSpacing: _densityGap(config.density),
          mainAxisSpacing: _densityGap(config.density),
        ),
        itemCount: groups.length,
        itemBuilder: (context, index) => _CommunityGroupCard(
          group: groups[index],
          config: config,
          isGrid: true,
          onTap: () => onOpenGroup(groups[index]),
        ),
      ),
    );
  }
}

class _LocalGroupsView extends StatelessWidget {
  final List<Map<String, dynamic>> groups;
  final PageDisplayConfig config;
  final Future<void> Function() onRefresh;
  final ValueChanged<Map<String, dynamic>> onOpenGroup;

  const _LocalGroupsView({
    required this.groups,
    required this.config,
    required this.onRefresh,
    required this.onOpenGroup,
  });

  @override
  Widget build(BuildContext context) {
    final view = communityGroupView(config);
    final padding = _centeredListPadding(context);
    if (view == CommunityGroupView.list) {
      return RefreshIndicator(
        onRefresh: onRefresh,
        child: ListView.separated(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: padding,
          itemCount: groups.length,
          separatorBuilder: (_, _) =>
              SizedBox(height: _densityGap(config.density)),
          itemBuilder: (context, index) => _LocalGroupCard(
            group: groups[index],
            config: config,
            isGrid: false,
            onTap: () => onOpenGroup(groups[index]),
          ),
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: onRefresh,
      child: GridView.builder(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: padding,
        gridDelegate: SliverGridDelegateWithMaxCrossAxisExtent(
          maxCrossAxisExtent: _gridExtent(config.density),
          mainAxisExtent: _localCardHeight(config),
          crossAxisSpacing: _densityGap(config.density),
          mainAxisSpacing: _densityGap(config.density),
        ),
        itemCount: groups.length,
        itemBuilder: (context, index) => _LocalGroupCard(
          group: groups[index],
          config: config,
          isGrid: true,
          onTap: () => onOpenGroup(groups[index]),
        ),
      ),
    );
  }
}

class _CommunityGroupCard extends StatelessWidget {
  final CommunityCharacterGroupInfo group;
  final PageDisplayConfig config;
  final bool isGrid;
  final VoidCallback onTap;

  const _CommunityGroupCard({
    required this.group,
    required this.config,
    required this.isGrid,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final description = group.description?.trim();
    final showDescription =
        communityShowDescription(config) && description?.isNotEmpty == true;
    final meta = Wrap(
      spacing: AppSpacing.sm,
      runSpacing: AppSpacing.xs,
      children: [
        _MetaLabel(
          icon: Icons.people_alt_outlined,
          text: '${group.characterCount} 角色',
        ),
        _MetaLabel(
          icon: Icons.auto_stories_outlined,
          text: '${group.workCount} 作品',
        ),
        if (communityShowDownloads(config))
          _MetaLabel(
            icon: Icons.download_outlined,
            text: '${group.downloadCount} 次导入',
          ),
      ],
    );
    return _GroupCardShell(
      imageUrl: group.coverUrl,
      title: group.name,
      description: showDescription ? description : null,
      badge: '社区',
      meta: meta,
      isGrid: isGrid,
      density: config.density,
      onTap: onTap,
    );
  }
}

class _LocalGroupCard extends StatelessWidget {
  final Map<String, dynamic> group;
  final PageDisplayConfig config;
  final bool isGrid;
  final VoidCallback onTap;

  const _LocalGroupCard({
    required this.group,
    required this.config,
    required this.isGrid,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final description = _communityText(group['description']);
    final showDescription =
        communityShowDescription(config) && description != null;
    final source = _communityText(group['source']);
    final isSynced = _communityText(group['community_id']) != null;
    final meta = Wrap(
      spacing: AppSpacing.sm,
      runSpacing: AppSpacing.xs,
      children: [
        _MetaLabel(
          icon: Icons.people_alt_outlined,
          text: '${_communityInt(group['character_count']) ?? 0} 角色',
        ),
        _MetaLabel(
          icon: Icons.auto_stories_outlined,
          text: '${_communityInt(group['work_count']) ?? 0} 作品',
        ),
        if (isSynced)
          const _MetaLabel(icon: Icons.cloud_done_outlined, text: '社区同步'),
      ],
    );
    return _GroupCardShell(
      imageUrl: _communityText(group['cover_url']),
      title: _communityText(group['name']) ?? '未命名角色群组',
      description: showDescription ? description : null,
      badge: _sourceLabel(source),
      meta: meta,
      isGrid: isGrid,
      density: config.density,
      onTap: onTap,
    );
  }
}

class _GroupCardShell extends StatelessWidget {
  final String? imageUrl;
  final String title;
  final String? description;
  final String badge;
  final Widget meta;
  final bool isGrid;
  final DisplayDensity density;
  final VoidCallback onTap;

  const _GroupCardShell({
    required this.imageUrl,
    required this.title,
    required this.description,
    required this.badge,
    required this.meta,
    required this.isGrid,
    required this.density,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final contentPadding = switch (density) {
      DisplayDensity.compact => AppSpacing.md,
      DisplayDensity.comfortable => AppSpacing.lg,
      DisplayDensity.relaxed => AppSpacing.xl,
    };
    final text = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Text(
                title,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(
                  context,
                ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w900),
              ),
            ),
            const SizedBox(width: AppSpacing.sm),
            Container(
              padding: const EdgeInsets.symmetric(
                horizontal: AppSpacing.sm,
                vertical: AppSpacing.xs,
              ),
              decoration: BoxDecoration(
                color: colorScheme.secondaryContainer,
                borderRadius: BorderRadius.circular(AppRadius.full),
              ),
              child: Text(
                badge,
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                  color: colorScheme.onSecondaryContainer,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ),
          ],
        ),
        if (description != null) ...[
          const SizedBox(height: AppSpacing.sm),
          Text(
            description!,
            maxLines: isGrid ? 2 : 1,
            overflow: TextOverflow.ellipsis,
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: colorScheme.onSurfaceVariant,
              height: 1.35,
            ),
          ),
        ],
        const Spacer(),
        meta,
      ],
    );

    return Card(
      margin: EdgeInsets.zero,
      elevation: 0,
      color: colorScheme.surfaceContainerLow,
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: isGrid
            ? Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  SizedBox(
                    height: density == DisplayDensity.compact ? 104 : 128,
                    child: AnimeCoverImage(url: imageUrl),
                  ),
                  Expanded(
                    child: Padding(
                      padding: EdgeInsets.all(contentPadding),
                      child: text,
                    ),
                  ),
                ],
              )
            : Padding(
                padding: EdgeInsets.all(contentPadding),
                child: Row(
                  children: [
                    ClipRRect(
                      borderRadius: BorderRadius.circular(AppRadius.xs),
                      child: AnimeCoverImage(
                        url: imageUrl,
                        width: density == DisplayDensity.compact ? 72 : 88,
                        height: density == DisplayDensity.compact ? 72 : 88,
                      ),
                    ),
                    SizedBox(width: contentPadding),
                    Expanded(child: SizedBox(height: 88, child: text)),
                    const SizedBox(width: AppSpacing.sm),
                    Icon(
                      Icons.chevron_right_rounded,
                      color: colorScheme.onSurfaceVariant,
                    ),
                  ],
                ),
              ),
      ),
    );
  }
}

class _MetaLabel extends StatelessWidget {
  final IconData icon;
  final String text;

  const _MetaLabel({required this.icon, required this.text});

  @override
  Widget build(BuildContext context) {
    final color = Theme.of(context).colorScheme.onSurfaceVariant;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, size: 15, color: color),
        const SizedBox(width: AppSpacing.xs),
        Text(
          text,
          style: Theme.of(
            context,
          ).textTheme.labelMedium?.copyWith(color: color),
        ),
      ],
    );
  }
}

class _ShareCodeSheet extends StatefulWidget {
  const _ShareCodeSheet();

  @override
  State<_ShareCodeSheet> createState() => _ShareCodeSheetState();
}

class _ShareCodeSheetState extends State<_ShareCodeSheet> {
  final TextEditingController _controller = TextEditingController();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _submit() {
    final code = _controller.text.trim().toUpperCase();
    if (code.isEmpty) return;
    Navigator.of(context).pop(code);
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.fromLTRB(
        AppSpacing.xl,
        AppSpacing.sm,
        AppSpacing.xl,
        MediaQuery.viewInsetsOf(context).bottom + AppSpacing.xl,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            '使用分享码打开群组',
            style: Theme.of(
              context,
            ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w900),
          ),
          const SizedBox(height: AppSpacing.xs),
          Text(
            '分享码只负责定位群组。加载后仍会进入完整详情页，不会直接写入本地资料库。',
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: AppSpacing.lg),
          TextField(
            controller: _controller,
            autofocus: true,
            textCapitalization: TextCapitalization.characters,
            textInputAction: TextInputAction.done,
            onSubmitted: (_) => _submit(),
            decoration: const InputDecoration(
              labelText: '分享码',
              hintText: '例如 AB12CD34',
              prefixIcon: Icon(Icons.key_outlined),
            ),
          ),
          const SizedBox(height: AppSpacing.lg),
          ValueListenableBuilder<TextEditingValue>(
            valueListenable: _controller,
            builder: (context, value, _) => FilledButton.icon(
              onPressed: value.text.trim().isEmpty ? null : _submit,
              icon: const Icon(Icons.arrow_forward_rounded),
              label: const Text('查看群组'),
            ),
          ),
        ],
      ),
    );
  }
}

class LocalCharacterGroupDetailPage extends StatefulWidget {
  final Map<String, dynamic> group;
  final CharacterRepository? repository;

  const LocalCharacterGroupDetailPage({
    super.key,
    required this.group,
    this.repository,
  });

  @override
  State<LocalCharacterGroupDetailPage> createState() =>
      _LocalCharacterGroupDetailPageState();
}

class _LocalCharacterGroupDetailPageState
    extends State<LocalCharacterGroupDetailPage> {
  late final CharacterRepository _repository;
  List<Map<String, dynamic>> _characters = const [];
  List<Map<String, dynamic>> _works = const [];
  bool _isLoading = true;
  bool _isLoaded = false;
  String? _errorText;
  int _generation = 0;

  int? get _groupId => _communityInt(widget.group['id']);

  @override
  void initState() {
    super.initState();
    _repository = widget.repository ?? getIt<CharacterRepository>();
    _load();
  }

  @override
  void dispose() {
    _generation++;
    super.dispose();
  }

  Future<void> _load() async {
    final groupId = _groupId;
    if (groupId == null) {
      setState(() {
        _isLoading = false;
        _errorText = '本地群组缺少有效 ID';
      });
      return;
    }
    final generation = ++_generation;
    setState(() {
      _isLoading = true;
      _errorText = null;
    });
    try {
      final result = await Future.wait([
        _repository.getCharacterGroupCharacters(groupId),
        _repository.getCharacterGroupWorks(groupId),
      ]);
      if (!mounted || generation != _generation) return;
      setState(() {
        _characters = result[0];
        _works = result[1];
        _isLoading = false;
        _isLoaded = true;
      });
    } catch (error) {
      if (!mounted || generation != _generation) return;
      setState(() {
        _isLoading = false;
        _errorText = _cleanCommunityError(error);
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final title = _communityText(widget.group['name']) ?? '未命名角色群组';
    return Scaffold(
      appBar: AppBar(
        title: Text(title),
        actions: [
          IconButton(
            tooltip: '刷新',
            onPressed: _isLoading ? null : _load,
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      body: AsyncContentView(
        isLoading: _isLoading,
        hasData: _isLoaded,
        isEmpty: false,
        errorMessage: _errorText,
        onRetry: _load,
        child: RefreshIndicator(
          onRefresh: _load,
          child: ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            children: [
              AdaptiveContentFrame(
                maxContentWidth: 1120,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    _LocalGroupHero(group: widget.group),
                    const SizedBox(height: AppSpacing.xl),
                    _LocalDetailSection(
                      title: '角色成员',
                      icon: Icons.people_alt_outlined,
                      items: _characters,
                      itemBuilder: (item) => _LocalMemberTile(
                        imageUrl: _communityText(item['image_url']),
                        title:
                            _communityText(item['name_cn']) ??
                            _communityText(item['name']) ??
                            '未命名角色',
                        subtitle: _communityText(item['role_name']) ?? '角色资料',
                        icon: Icons.person_outline_rounded,
                      ),
                    ),
                    const SizedBox(height: AppSpacing.xl),
                    _LocalDetailSection(
                      title: '关联作品',
                      icon: Icons.auto_stories_outlined,
                      items: _works,
                      itemBuilder: (item) => _LocalMemberTile(
                        imageUrl: _communityText(item['cover_url']),
                        title: _communityText(item['title']) ?? '未命名作品',
                        subtitle: [
                          _sourceLabel(_communityText(item['subject_type'])),
                          if (_communityText(item['status']) != null)
                            _communityText(item['status'])!,
                        ].join(' · '),
                        icon: Icons.auto_stories_outlined,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _LocalGroupHero extends StatelessWidget {
  final Map<String, dynamic> group;

  const _LocalGroupHero({required this.group});

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final description = _communityText(group['description']);
    final communityId = _communityText(group['community_id']);
    return Card(
      margin: EdgeInsets.zero,
      elevation: 0,
      color: colorScheme.surfaceContainerLow,
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.xl),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ClipRRect(
              borderRadius: BorderRadius.circular(AppRadius.sm),
              child: AnimeCoverImage(
                url: _communityText(group['cover_url']),
                width: 120,
                height: 150,
              ),
            ),
            const SizedBox(width: AppSpacing.xl),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _communityText(group['name']) ?? '未命名角色群组',
                    style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                      fontWeight: FontWeight.w900,
                    ),
                  ),
                  if (description != null) ...[
                    const SizedBox(height: AppSpacing.sm),
                    Text(
                      description,
                      style: TextStyle(
                        color: colorScheme.onSurfaceVariant,
                        height: 1.5,
                      ),
                    ),
                  ],
                  const SizedBox(height: AppSpacing.md),
                  Wrap(
                    spacing: AppSpacing.sm,
                    runSpacing: AppSpacing.sm,
                    children: [
                      Chip(
                        avatar: const Icon(Icons.people_alt_outlined, size: 17),
                        label: Text(
                          '${_communityInt(group['character_count']) ?? 0} 个角色',
                        ),
                      ),
                      Chip(
                        avatar: const Icon(
                          Icons.auto_stories_outlined,
                          size: 17,
                        ),
                        label: Text(
                          '${_communityInt(group['work_count']) ?? 0} 部作品',
                        ),
                      ),
                      Chip(
                        avatar: Icon(
                          communityId == null
                              ? Icons.folder_outlined
                              : Icons.cloud_done_outlined,
                          size: 17,
                        ),
                        label: Text(communityId == null ? '仅本地' : '关联社区群组'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _LocalDetailSection extends StatelessWidget {
  final String title;
  final IconData icon;
  final List<Map<String, dynamic>> items;
  final Widget Function(Map<String, dynamic> item) itemBuilder;

  const _LocalDetailSection({
    required this.title,
    required this.icon,
    required this.items,
    required this.itemBuilder,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(icon, color: Theme.of(context).colorScheme.primary),
            const SizedBox(width: AppSpacing.sm),
            Expanded(
              child: Text(
                title,
                style: Theme.of(
                  context,
                ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w900),
              ),
            ),
            Text('${items.length} 项'),
          ],
        ),
        const SizedBox(height: AppSpacing.md),
        if (items.isEmpty)
          const Card(
            margin: EdgeInsets.zero,
            elevation: 0,
            child: Padding(
              padding: EdgeInsets.all(AppSpacing.xl),
              child: Center(child: Text('暂无数据')),
            ),
          )
        else
          LayoutBuilder(
            builder: (context, constraints) {
              final columns = constraints.maxWidth >= 900 ? 2 : 1;
              return GridView.builder(
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: columns,
                  mainAxisExtent: 92,
                  crossAxisSpacing: AppSpacing.md,
                  mainAxisSpacing: AppSpacing.md,
                ),
                itemCount: items.length,
                itemBuilder: (context, index) => itemBuilder(items[index]),
              );
            },
          ),
      ],
    );
  }
}

class _LocalMemberTile extends StatelessWidget {
  final String? imageUrl;
  final String title;
  final String subtitle;
  final IconData icon;

  const _LocalMemberTile({
    required this.imageUrl,
    required this.title,
    required this.subtitle,
    required this.icon,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Card(
      margin: EdgeInsets.zero,
      elevation: 0,
      color: colorScheme.surfaceContainerLow,
      clipBehavior: Clip.antiAlias,
      child: Row(
        children: [
          SizedBox(
            width: 72,
            height: double.infinity,
            child: AnimeCoverImage(url: imageUrl),
          ),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontWeight: FontWeight.w900),
                ),
                const SizedBox(height: AppSpacing.xs),
                Row(
                  children: [
                    Icon(icon, size: 15, color: colorScheme.onSurfaceVariant),
                    const SizedBox(width: AppSpacing.xs),
                    Expanded(
                      child: Text(
                        subtitle,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(width: AppSpacing.md),
        ],
      ),
    );
  }
}

EdgeInsets _centeredListPadding(BuildContext context) {
  final width = MediaQuery.sizeOf(context).width;
  final horizontal = AppBreakpoints.centeredPadding(
    width,
    maxContentWidth: 1200,
    minimum: AppBreakpoints.pagePadding(width),
  );
  return EdgeInsets.fromLTRB(
    horizontal,
    AppSpacing.sm,
    horizontal,
    AppSpacing.xxl,
  );
}

double _densityGap(DisplayDensity density) {
  return switch (density) {
    DisplayDensity.compact => AppSpacing.sm,
    DisplayDensity.comfortable => AppSpacing.md,
    DisplayDensity.relaxed => AppSpacing.lg,
  };
}

double _gridExtent(DisplayDensity density) {
  return switch (density) {
    DisplayDensity.compact => 280,
    DisplayDensity.comfortable => 340,
    DisplayDensity.relaxed => 400,
  };
}

double _communityCardHeight(PageDisplayConfig config) {
  final base = switch (config.density) {
    DisplayDensity.compact => 276.0,
    DisplayDensity.comfortable => 324.0,
    DisplayDensity.relaxed => 356.0,
  };
  return communityShowDescription(config) ? base : base - 34;
}

double _localCardHeight(PageDisplayConfig config) {
  final base = switch (config.density) {
    DisplayDensity.compact => 258.0,
    DisplayDensity.comfortable => 306.0,
    DisplayDensity.relaxed => 338.0,
  };
  return communityShowDescription(config) ? base : base - 34;
}

String _sourceLabel(String? source) {
  switch (source?.toLowerCase()) {
    case 'community':
      return '社区';
    case 'share':
    case 'share_code':
      return '分享码';
    case 'local_import':
      return '文件导入';
    case 'novel':
      return '小说';
    case 'manga':
      return '漫画';
    case 'movie':
      return '剧场版';
    case 'tv':
    case 'anime':
      return '动画';
    case null:
    case '':
      return '本地';
    default:
      return source!;
  }
}

String? _communityText(dynamic value) {
  final text = value?.toString().trim();
  return text == null || text.isEmpty ? null : text;
}

int? _communityInt(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '');
}

String _cleanCommunityError(Object error) {
  return error.toString().replaceFirst(RegExp(r'^Exception:\s*'), '').trim();
}
