import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../models/character_group_package.dart';
import '../../../repositories/character_repository.dart';
import '../../../services/character_group_community_service.dart';
import '../../../services/service_locator.dart';
import '../../components/adaptive_content_frame.dart';
import '../../components/anime_cover_image.dart';
import '../../components/async_content_view.dart';
import '../../design_tokens.dart';

class CommunityGroupDetailPage extends StatefulWidget {
  final CommunityCharacterGroupInfo? summary;
  final String? shareCode;
  final CharacterGroupPackage? initialPackage;
  final CharacterGroupCommunityService? communityService;
  final CharacterRepository? repository;

  const CommunityGroupDetailPage({
    super.key,
    this.summary,
    this.shareCode,
    this.initialPackage,
    this.communityService,
    this.repository,
  }) : assert(
         summary != null ||
             (shareCode != null && shareCode != '') ||
             initialPackage != null,
       );

  @override
  State<CommunityGroupDetailPage> createState() =>
      _CommunityGroupDetailPageState();
}

class _CommunityGroupDetailPageState extends State<CommunityGroupDetailPage> {
  late final CharacterGroupCommunityService _communityService;
  late final CharacterRepository _repository;

  CharacterGroupPackage? _package;
  CharacterGroupImportResult? _importResult;
  bool _isLoading = true;
  bool _isImporting = false;
  String? _errorText;
  int _loadGeneration = 0;

  bool get _canReload =>
      widget.shareCode?.trim().isNotEmpty == true || widget.summary != null;

  @override
  void initState() {
    super.initState();
    _communityService =
        widget.communityService ?? const CharacterGroupCommunityService();
    _repository = widget.repository ?? getIt<CharacterRepository>();
    final initialPackage = widget.initialPackage;
    if (initialPackage != null) {
      _package = initialPackage;
      _isLoading = false;
    } else {
      _loadPackage();
    }
  }

  Future<void> _loadPackage() async {
    if (!_canReload) {
      setState(() {
        _package = widget.initialPackage;
        _isLoading = false;
        _errorText = null;
      });
      return;
    }
    final generation = ++_loadGeneration;
    setState(() {
      _isLoading = true;
      _errorText = null;
    });

    try {
      final package = widget.shareCode?.trim().isNotEmpty == true
          ? await _communityService.fetchByShareCode(widget.shareCode!.trim())
          : await _communityService.fetchGroup(widget.summary!.id);
      if (!mounted || generation != _loadGeneration) return;
      setState(() {
        _package = package;
        _isLoading = false;
      });
    } catch (error) {
      if (!mounted || generation != _loadGeneration) return;
      setState(() {
        _isLoading = false;
        _errorText = _cleanError(error);
      });
    }
  }

  Future<void> _confirmAndImport() async {
    final package = _package;
    if (package == null || _isImporting) return;
    if (package.characters.length < 2 || package.works.isEmpty) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('角色群组至少需要 2 个角色和 1 部作品')));
      return;
    }

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('导入到本地资料库'),
        content: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                package.name,
                style: Theme.of(
                  dialogContext,
                ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w900),
              ),
              const SizedBox(height: AppSpacing.md),
              Wrap(
                spacing: AppSpacing.sm,
                runSpacing: AppSpacing.sm,
                children: [
                  _CountChip(
                    icon: Icons.people_alt_outlined,
                    label: '${package.characters.length} 个角色',
                  ),
                  _CountChip(
                    icon: Icons.auto_stories_outlined,
                    label: '${package.works.length} 部作品',
                  ),
                ],
              ),
              const SizedBox(height: AppSpacing.lg),
              const _ImportRuleRow(
                icon: Icons.person_search_outlined,
                text: '角色优先按 Bangumi ID 复用，再按中日文名称匹配。',
              ),
              const SizedBox(height: AppSpacing.sm),
              const _ImportRuleRow(
                icon: Icons.library_books_outlined,
                text: '作品按“标题 + 类型”复用；不会覆盖已有观看进度和评价。',
              ),
              const SizedBox(height: AppSpacing.sm),
              _ImportRuleRow(
                icon: Icons.sync_alt_rounded,
                text: package.communityId == null
                    ? '无法识别社区 ID 时会创建一个新的本地群组。'
                    : '同一社区群组再次导入会更新群组成员，并复用已有条目。',
              ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('取消'),
          ),
          FilledButton.icon(
            onPressed: () => Navigator.pop(dialogContext, true),
            icon: const Icon(Icons.download_done_rounded),
            label: const Text('确认导入'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() => _isImporting = true);
    try {
      final result = await _repository.importCharacterGroupPackage(
        package,
        source: widget.shareCode?.trim().isNotEmpty == true
            ? 'share'
            : 'community',
      );
      if (!mounted) return;
      HapticFeedback.mediumImpact();
      setState(() {
        _importResult = result;
        _isImporting = false;
      });
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('角色群组已导入，本地群组列表已更新')));
    } catch (error) {
      if (!mounted) return;
      setState(() => _isImporting = false);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('导入失败：${_cleanError(error)}')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final package = _package;
    final summary = widget.summary;
    final title = package?.name ?? summary?.name ?? '分享码群组';

    return Scaffold(
      appBar: AppBar(
        title: Text(title),
        actions: [
          IconButton(
            tooltip: '刷新群组资料',
            onPressed: !_canReload || _isLoading || _isImporting
                ? null
                : _loadPackage,
            icon: const Icon(Icons.refresh_rounded),
          ),
        ],
      ),
      body: AsyncContentView(
        isLoading: _isLoading,
        hasData: package != null,
        isEmpty: false,
        errorMessage: _errorText,
        onRetry: _loadPackage,
        child: package == null
            ? const SizedBox.shrink()
            : RefreshIndicator(
                onRefresh: _loadPackage,
                child: ListView(
                  physics: const AlwaysScrollableScrollPhysics(),
                  children: [
                    AdaptiveContentFrame(
                      maxContentWidth: 1120,
                      top: AppSpacing.lg,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          if (_importResult != null) ...[
                            _ImportResultCard(result: _importResult!),
                            const SizedBox(height: AppSpacing.lg),
                          ],
                          _GroupHero(package: package, summary: summary),
                          const SizedBox(height: AppSpacing.xl),
                          _SnapshotSection(
                            title: '角色成员',
                            subtitle: '${package.characters.length} 个角色',
                            icon: Icons.people_alt_outlined,
                            children: package.characters
                                .map(
                                  (character) => _CharacterSnapshotCard(
                                    snapshot: character,
                                  ),
                                )
                                .toList(growable: false),
                          ),
                          const SizedBox(height: AppSpacing.xl),
                          _SnapshotSection(
                            title: '关联作品',
                            subtitle: '${package.works.length} 部作品',
                            icon: Icons.auto_stories_outlined,
                            children: package.works
                                .map(
                                  (work) => _WorkSnapshotCard(snapshot: work),
                                )
                                .toList(growable: false),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
      ),
      bottomNavigationBar: package == null
          ? null
          : SafeArea(
              top: false,
              child: Material(
                color: Theme.of(context).colorScheme.surfaceContainerLow,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(
                    AppSpacing.lg,
                    AppSpacing.md,
                    AppSpacing.lg,
                    AppSpacing.md,
                  ),
                  child: Row(
                    children: [
                      Expanded(
                        child: Text(
                          package.characters.length < 2 || package.works.isEmpty
                              ? '群组数据不完整：至少需要 2 个角色和 1 部作品'
                              : _importResult == null
                              ? '导入前会自动识别并复用已有角色与作品'
                              : '已导入，可再次同步该群组的最新成员',
                          maxLines: 2,
                          style: Theme.of(context).textTheme.bodySmall
                              ?.copyWith(
                                color: Theme.of(
                                  context,
                                ).colorScheme.onSurfaceVariant,
                              ),
                        ),
                      ),
                      const SizedBox(width: AppSpacing.lg),
                      FilledButton.icon(
                        onPressed:
                            _isImporting ||
                                package.characters.length < 2 ||
                                package.works.isEmpty
                            ? null
                            : _confirmAndImport,
                        icon: _isImporting
                            ? const SizedBox(
                                width: 18,
                                height: 18,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                ),
                              )
                            : Icon(
                                _importResult == null
                                    ? Icons.download_rounded
                                    : Icons.sync_rounded,
                              ),
                        label: Text(
                          _isImporting
                              ? '正在导入'
                              : _importResult == null
                              ? '导入群组'
                              : '重新同步',
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
    );
  }
}

class _GroupHero extends StatelessWidget {
  final CharacterGroupPackage package;
  final CommunityCharacterGroupInfo? summary;

  const _GroupHero({required this.package, this.summary});

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final description = package.description?.trim();
    return LayoutBuilder(
      builder: (context, constraints) {
        final compact = constraints.maxWidth < AppBreakpoints.compact;
        final cover = SizedBox(
          width: compact ? double.infinity : 220,
          height: compact ? 190 : 250,
          child: ClipRRect(
            borderRadius: BorderRadius.circular(AppRadius.sm),
            child: AnimeCoverImage(
              url: package.coverUrl ?? summary?.coverUrl,
              width: compact ? null : 220,
              height: compact ? null : 250,
            ),
          ),
        );
        final info = Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              package.name,
              style: Theme.of(
                context,
              ).textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.w900),
            ),
            if (description != null && description.isNotEmpty) ...[
              const SizedBox(height: AppSpacing.md),
              Text(
                description,
                style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                  color: colorScheme.onSurfaceVariant,
                  height: 1.5,
                ),
              ),
            ],
            const SizedBox(height: AppSpacing.lg),
            Wrap(
              spacing: AppSpacing.sm,
              runSpacing: AppSpacing.sm,
              children: [
                _CountChip(
                  icon: Icons.people_alt_outlined,
                  label: '${package.characters.length} 个角色',
                ),
                _CountChip(
                  icon: Icons.auto_stories_outlined,
                  label: '${package.works.length} 部作品',
                ),
                if ((summary?.downloadCount ?? 0) > 0)
                  _CountChip(
                    icon: Icons.download_outlined,
                    label: '${summary!.downloadCount} 次导入',
                  ),
                if (package.shareCode?.trim().isNotEmpty == true)
                  _CountChip(
                    icon: Icons.key_outlined,
                    label: '分享码 ${package.shareCode}',
                  ),
              ],
            ),
          ],
        );

        return Card(
          margin: EdgeInsets.zero,
          elevation: 0,
          color: colorScheme.surfaceContainerLow,
          clipBehavior: Clip.antiAlias,
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.lg),
            child: compact
                ? Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      cover,
                      const SizedBox(height: AppSpacing.lg),
                      info,
                    ],
                  )
                : Row(
                    crossAxisAlignment: CrossAxisAlignment.center,
                    children: [
                      cover,
                      const SizedBox(width: AppSpacing.xl),
                      Expanded(child: info),
                    ],
                  ),
          ),
        );
      },
    );
  }
}

class _ImportResultCard extends StatelessWidget {
  final CharacterGroupImportResult result;

  const _ImportResultCard({required this.result});

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final reusedCharacters =
        result.characterCount - result.createdCharacterCount;
    final reusedWorks = result.workCount - result.createdWorkCount;
    return Card(
      margin: EdgeInsets.zero,
      elevation: 0,
      color: colorScheme.primaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(Icons.task_alt_rounded, color: colorScheme.onPrimaryContainer),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '导入完成',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      color: colorScheme.onPrimaryContainer,
                      fontWeight: FontWeight.w900,
                    ),
                  ),
                  const SizedBox(height: AppSpacing.xs),
                  Text(
                    '角色：新增 ${result.createdCharacterCount}、复用 $reusedCharacters；'
                    '作品：新增 ${result.createdWorkCount}、复用 $reusedWorks。'
                    '最终群组包含 ${result.characterCount} 个角色和 ${result.workCount} 部作品。',
                    style: TextStyle(color: colorScheme.onPrimaryContainer),
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

class _SnapshotSection extends StatelessWidget {
  final String title;
  final String subtitle;
  final IconData icon;
  final List<Widget> children;

  const _SnapshotSection({
    required this.title,
    required this.subtitle,
    required this.icon,
    required this.children,
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
            Text(
              subtitle,
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.md),
        if (children.isEmpty)
          Card(
            margin: EdgeInsets.zero,
            elevation: 0,
            child: const Padding(
              padding: EdgeInsets.all(AppSpacing.xl),
              child: Center(child: Text('暂无数据')),
            ),
          )
        else
          LayoutBuilder(
            builder: (context, constraints) {
              final columns = constraints.maxWidth >= 980
                  ? 3
                  : constraints.maxWidth >= 620
                  ? 2
                  : 1;
              return GridView.count(
                crossAxisCount: columns,
                mainAxisSpacing: AppSpacing.md,
                crossAxisSpacing: AppSpacing.md,
                childAspectRatio: columns == 1 ? 3.2 : 2.75,
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                children: children,
              );
            },
          ),
      ],
    );
  }
}

class _CharacterSnapshotCard extends StatelessWidget {
  final Map<String, dynamic> snapshot;

  const _CharacterSnapshotCard({required this.snapshot});

  @override
  Widget build(BuildContext context) {
    final name =
        _firstText([
          snapshot['name_cn'],
          snapshot['nameCn'],
          snapshot['display_name'],
          snapshot['name'],
        ]) ??
        '未命名角色';
    final originalName = _firstText([
      snapshot['name'],
      snapshot['original_name'],
    ]);
    return _SnapshotCard(
      imageUrl: _snapshotImageUrl(snapshot),
      icon: Icons.person_outline_rounded,
      title: name,
      subtitle: originalName == null || originalName == name
          ? '角色资料快照'
          : originalName,
    );
  }
}

class _WorkSnapshotCard extends StatelessWidget {
  final Map<String, dynamic> snapshot;

  const _WorkSnapshotCard({required this.snapshot});

  @override
  Widget build(BuildContext context) {
    final title =
        _firstText([
          snapshot['title'],
          snapshot['name_cn'],
          snapshot['nameCn'],
          snapshot['name'],
        ]) ??
        '未命名作品';
    final type = _workTypeLabel(
      _firstText([
        snapshot['subject_type'],
        snapshot['subjectType'],
        snapshot['type'],
      ]),
    );
    final airDate = _firstText([snapshot['air_date'], snapshot['airDate']]);
    final subtitleParts = <String>[];
    if (type.isNotEmpty) subtitleParts.add(type);
    if (airDate != null) subtitleParts.add(airDate);
    return _SnapshotCard(
      imageUrl: _snapshotImageUrl(snapshot),
      icon: Icons.auto_stories_outlined,
      title: title,
      subtitle: subtitleParts.join(' · '),
    );
  }
}

class _SnapshotCard extends StatelessWidget {
  final String? imageUrl;
  final IconData icon;
  final String title;
  final String subtitle;

  const _SnapshotCard({
    required this.imageUrl,
    required this.icon,
    required this.title,
    required this.subtitle,
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
            width: 86,
            height: double.infinity,
            child: AnimeCoverImage(url: imageUrl),
          ),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(
                0,
                AppSpacing.md,
                AppSpacing.md,
                AppSpacing.md,
              ),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.w900,
                    ),
                  ),
                  const SizedBox(height: AppSpacing.xs),
                  Row(
                    children: [
                      Icon(icon, size: 15, color: colorScheme.onSurfaceVariant),
                      const SizedBox(width: AppSpacing.xs),
                      Expanded(
                        child: Text(
                          subtitle.isEmpty ? '暂无附加资料' : subtitle,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: Theme.of(context).textTheme.bodySmall
                              ?.copyWith(color: colorScheme.onSurfaceVariant),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _CountChip extends StatelessWidget {
  final IconData icon;
  final String label;

  const _CountChip({required this.icon, required this.label});

  @override
  Widget build(BuildContext context) {
    return Chip(
      avatar: Icon(icon, size: 17),
      label: Text(label),
      visualDensity: VisualDensity.compact,
    );
  }
}

class _ImportRuleRow extends StatelessWidget {
  final IconData icon;
  final String text;

  const _ImportRuleRow({required this.icon, required this.text});

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 20, color: colorScheme.primary),
        const SizedBox(width: AppSpacing.sm),
        Expanded(child: Text(text)),
      ],
    );
  }
}

String? _snapshotImageUrl(Map<String, dynamic> snapshot) {
  final direct = _firstText([
    snapshot['image_url'],
    snapshot['imageUrl'],
    snapshot['cover_url'],
    snapshot['coverUrl'],
    snapshot['avatar'],
  ]);
  if (direct != null) return direct;
  final images = snapshot['images'];
  if (images is Map) {
    return _firstText([
      images['large'],
      images['medium'],
      images['common'],
      images['small'],
      images['grid'],
    ]);
  }
  return null;
}

String? _firstText(List<dynamic> values) {
  for (final value in values) {
    final text = value?.toString().trim();
    if (text != null && text.isNotEmpty) return text;
  }
  return null;
}

String _workTypeLabel(String? type) {
  switch (type?.toLowerCase()) {
    case 'anime':
    case 'tv':
      return '动画';
    case 'movie':
      return '剧场版';
    case 'ova':
      return 'OVA';
    case 'special':
      return '特别篇';
    case 'novel':
      return '小说';
    case 'manga':
      return '漫画';
    default:
      return type ?? '';
  }
}

String _cleanError(Object error) {
  return error.toString().replaceFirst(RegExp(r'^Exception:\s*'), '').trim();
}
