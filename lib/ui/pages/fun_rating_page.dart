import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';

import '../../models/fun_rating_tier_config.dart';
import '../../services/fun_rating_service.dart';
import '../components/adaptive_content_frame.dart';
import '../components/anime_cover_image.dart';
import '../components/empty_state.dart';
import '../design_tokens.dart';
import 'fun_rating/fun_rating_share_page.dart';
import 'fun_rating/fun_rating_style.dart';

class FunRatingPage extends StatefulWidget {
  final FunRatingService? service;
  final Future<Directory> Function()? documentsDirectoryLoader;

  const FunRatingPage({super.key, this.service, this.documentsDirectoryLoader});

  @override
  State<FunRatingPage> createState() => _FunRatingPageState();
}

class _FunRatingPageState extends State<FunRatingPage> {
  static const String _unratedChoice = '__unrated__';

  late final FunRatingService _service;
  final TextEditingController _searchController = TextEditingController();
  Timer? _searchDebounce;

  List<Map<String, dynamic>> _animes = const [];
  FunRatingTierConfig _tierConfig = FunRatingTierConfig.letters;
  String _boardTitle = FunRatingService.defaultBoardTitle;
  Directory? _appDocDir;
  bool _isLoading = true;
  bool _isMutating = false;
  String? _errorMessage;
  String _query = '';

  @override
  void initState() {
    super.initState();
    _service = widget.service ?? FunRatingService();
    _loadData();
  }

  @override
  void dispose() {
    _searchDebounce?.cancel();
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _loadData() async {
    if (mounted) {
      setState(() {
        _isLoading = true;
        _errorMessage = null;
      });
    }
    try {
      final results = await Future.wait<dynamic>([
        _service.loadAnimeEntries(),
        _service.loadTierConfig(),
        _service.loadBoardTitle(),
        (widget.documentsDirectoryLoader ?? getApplicationDocumentsDirectory)(),
      ]);
      if (!mounted) return;
      setState(() {
        _animes = results[0] as List<Map<String, dynamic>>;
        _tierConfig = results[1] as FunRatingTierConfig;
        _boardTitle = results[2] as String;
        _appDocDir = results[3] as Directory;
        _isLoading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _isLoading = false;
        _errorMessage = error.toString().replaceFirst('Exception: ', '');
      });
    }
  }

  Map<String, List<Map<String, dynamic>>> get _tierEntries {
    final result = {
      for (final code in FunRatingTierConfig.codes)
        code: <Map<String, dynamic>>[],
    };
    for (final anime in _animes) {
      final tier = normalizeFunRatingTier(anime['fun_rating_tier']);
      if (tier != null) result[tier]!.add(anime);
    }
    return result;
  }

  List<Map<String, dynamic>> get _visibleUnrated {
    final query = _query.trim().toLowerCase();
    return _animes
        .where((anime) {
          if (normalizeFunRatingTier(anime['fun_rating_tier']) != null) {
            return false;
          }
          if (query.isEmpty) return true;
          return (anime['title'] ?? '').toString().toLowerCase().contains(
            query,
          );
        })
        .toList(growable: false);
  }

  int get _ratedCount => _animes.where((anime) {
    return normalizeFunRatingTier(anime['fun_rating_tier']) != null;
  }).length;

  void _onSearchChanged(String value) {
    _searchDebounce?.cancel();
    _searchDebounce = Timer(const Duration(milliseconds: 160), () {
      if (mounted) setState(() => _query = value);
    });
  }

  void _clearSearch() {
    _searchDebounce?.cancel();
    _searchController.clear();
    setState(() => _query = '');
  }

  Future<void> _assignTier(
    Map<String, dynamic> anime,
    String? targetTier,
  ) async {
    final id = anime['id'];
    if (id is! int || _isMutating) return;
    final normalizedTarget = normalizeFunRatingTier(targetTier);
    final previousTier = normalizeFunRatingTier(anime['fun_rating_tier']);
    if (previousTier == normalizedTarget) return;

    final index = _animes.indexWhere((entry) => entry['id'] == id);
    if (index < 0) return;
    final previous = _animes[index];
    setState(() {
      _animes = List<Map<String, dynamic>>.from(_animes);
      _animes[index] = {...previous, 'fun_rating_tier': normalizedTarget};
    });

    try {
      await _service.setTier(id, normalizedTarget);
      if (mounted) HapticFeedback.selectionClick();
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _animes = List<Map<String, dynamic>>.from(_animes);
        _animes[index] = previous;
      });
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('移动失败：$error')));
    }
  }

  Future<void> _chooseTier(Map<String, dynamic> anime) async {
    final currentTier = normalizeFunRatingTier(anime['fun_rating_tier']);
    final selected = await showModalBottomSheet<String>(
      context: context,
      useSafeArea: true,
      showDragHandle: true,
      constraints: MediaQuery.sizeOf(context).width >= 720
          ? const BoxConstraints(maxWidth: 520)
          : null,
      builder: (sheetContext) {
        return SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.only(bottom: AppSpacing.md),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                ListTile(
                  title: Text(
                    (anime['title'] ?? '选择档位').toString(),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontWeight: FontWeight.w800),
                  ),
                  subtitle: const Text('点击档位即可完成，无需拖动作品'),
                ),
                for (final code in FunRatingTierConfig.codes)
                  ListTile(
                    key: ValueKey('fun-rating-tier-choice-$code'),
                    leading: _TierCodeBadge(
                      code: code,
                      color: funRatingTierColor(code),
                    ),
                    title: Text(_tierConfig.labelFor(code)),
                    subtitle: _tierConfig.labelFor(code) == code
                        ? null
                        : Text('$code 档'),
                    trailing: currentTier == code
                        ? const Icon(Icons.check_circle_rounded)
                        : null,
                    onTap: () => Navigator.of(sheetContext).pop(code),
                  ),
                if (currentTier != null)
                  ListTile(
                    key: const ValueKey('fun-rating-tier-choice-unrated'),
                    leading: const Icon(Icons.remove_circle_outline_rounded),
                    title: const Text('移回待评级'),
                    onTap: () => Navigator.of(sheetContext).pop(_unratedChoice),
                  ),
              ],
            ),
          ),
        );
      },
    );
    if (selected == null) return;
    await _assignTier(anime, selected == _unratedChoice ? null : selected);
  }

  Future<void> _editBoard() async {
    final result = await showModalBottomSheet<FunRatingBoardSettingsResult>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      constraints: MediaQuery.sizeOf(context).width >= AppBreakpoints.medium
          ? const BoxConstraints(maxWidth: 720)
          : null,
      builder: (_) => FunRatingBoardSettingsSheet(
        initialTitle: _boardTitle,
        initialConfig: _tierConfig,
      ),
    );
    if (result == null) return;

    try {
      await Future.wait([
        _service.saveBoardTitle(result.title),
        _service.saveTierConfig(result.config),
      ]);
      if (!mounted) return;
      setState(() {
        _boardTitle = result.title;
        _tierConfig = result.config;
      });
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('榜单样式已保存')));
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('保存失败：$error')));
      }
    }
  }

  Future<void> _clearBoard() async {
    if (_ratedCount == 0 || _isMutating) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('清空趣味评级？'),
        content: const Text('所有作品都会回到“待评级”，不会删除番剧，也不会影响原有评分。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('清空'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    setState(() => _isMutating = true);
    try {
      await _service.clearAllTiers();
      if (!mounted) return;
      setState(() {
        _animes = _animes
            .map(
              (anime) => <String, dynamic>{...anime, 'fun_rating_tier': null},
            )
            .toList(growable: false);
      });
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('清空失败：$error')));
      }
    } finally {
      if (mounted) setState(() => _isMutating = false);
    }
  }

  void _openSharePreview() {
    if (_ratedCount == 0) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('先给至少一部番剧评级吧')));
      return;
    }
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => FunRatingSharePage(
          boardTitle: _boardTitle,
          tierConfig: _tierConfig,
          tierEntries: {
            for (final entry in _tierEntries.entries)
              entry.key: List<Map<String, dynamic>>.from(entry.value),
          },
          appDocDir: _appDocDir,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('趣味评级'),
        actions: [
          IconButton(
            tooltip: '自定义榜单与档位',
            onPressed: _editBoard,
            icon: const Icon(Icons.tune_rounded),
          ),
          IconButton(
            tooltip: '分享榜单图片',
            onPressed: _ratedCount == 0 ? null : _openSharePreview,
            icon: const Icon(Icons.ios_share_rounded),
          ),
          PopupMenuButton<String>(
            tooltip: '更多',
            onSelected: (value) {
              if (value == 'clear') _clearBoard();
            },
            itemBuilder: (_) => [
              PopupMenuItem(
                value: 'clear',
                enabled: _ratedCount > 0,
                child: const ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(Icons.restart_alt_rounded),
                  title: Text('清空榜单'),
                ),
              ),
            ],
          ),
          const SizedBox(width: AppSpacing.sm),
        ],
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_isLoading) return const Center(child: CircularProgressIndicator());
    if (_errorMessage != null) {
      return EmptyStateWidget(
        icon: Icons.error_outline_rounded,
        message: '趣味评级加载失败',
        description: _errorMessage,
        buttonText: '重试',
        onButtonPressed: _loadData,
      );
    }
    if (_animes.isEmpty) {
      return const EmptyStateWidget(
        icon: Icons.movie_filter_outlined,
        message: '还没有可以评级的番剧',
        description: '先添加几部番剧，再来制作自己的趣味梯队榜。',
      );
    }

    final tierEntries = _tierEntries;
    final ratedCount = tierEntries.values.fold<int>(
      0,
      (total, entries) => total + entries.length,
    );
    final visibleUnrated = _visibleUnrated;
    final totalUnrated = _animes.length - ratedCount;

    return LayoutBuilder(
      builder: (context, constraints) {
        final useDesktopWorkspace =
            constraints.maxWidth >= 980 && constraints.maxHeight >= 680;
        if (useDesktopWorkspace) {
          return _buildDesktopWorkspace(
            constraints: constraints,
            tierEntries: tierEntries,
            visibleUnrated: visibleUnrated,
            totalUnrated: totalUnrated,
            ratedCount: ratedCount,
          );
        }

        return _buildStackedBoard(
          compactScreen: constraints.maxWidth < 720,
          tierEntries: tierEntries,
          visibleUnrated: visibleUnrated,
          totalUnrated: totalUnrated,
          ratedCount: ratedCount,
        );
      },
    );
  }

  Widget _buildStackedBoard({
    required bool compactScreen,
    required Map<String, List<Map<String, dynamic>>> tierEntries,
    required List<Map<String, dynamic>> visibleUnrated,
    required int totalUnrated,
    required int ratedCount,
  }) {
    return RefreshIndicator(
      onRefresh: _loadData,
      child: SingleChildScrollView(
        key: const PageStorageKey('fun-rating-board-scroll'),
        physics: const AlwaysScrollableScrollPhysics(),
        keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
        child: AdaptiveContentFrame(
          maxContentWidth: 1180,
          top: compactScreen ? AppSpacing.md : AppSpacing.lg,
          bottom: AppSpacing.xxl,
          minimumHorizontalPadding: compactScreen
              ? AppSpacing.md
              : AppSpacing.lg,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _buildHero(compactLayout: true, ratedCount: ratedCount),
              const SizedBox(height: AppSpacing.md),
              _buildUnratedPool(
                entries: visibleUnrated,
                totalUnrated: totalUnrated,
                compactLayout: true,
              ),
              const SizedBox(height: AppSpacing.lg),
              _buildTierHeading(),
              const SizedBox(height: AppSpacing.sm),
              for (final code in FunRatingTierConfig.codes) ...[
                _TierBoardSection(
                  code: code,
                  label: _tierConfig.labelFor(code),
                  color: funRatingTierColor(code),
                  entries: tierEntries[code] ?? const [],
                  appDocDir: _appDocDir,
                  compactLayout: true,
                  onTapAnime: _chooseTier,
                ),
                const SizedBox(height: AppSpacing.sm),
              ],
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildDesktopWorkspace({
    required BoxConstraints constraints,
    required Map<String, List<Map<String, dynamic>>> tierEntries,
    required List<Map<String, dynamic>> visibleUnrated,
    required int totalUnrated,
    required int ratedCount,
  }) {
    final sidebarWidth = (constraints.maxWidth * 0.31)
        .clamp(330.0, 390.0)
        .toDouble();
    return AdaptiveContentFrame(
      maxContentWidth: 1180,
      top: AppSpacing.md,
      bottom: AppSpacing.md,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildHero(compactLayout: true, ratedCount: ratedCount),
          const SizedBox(height: AppSpacing.md),
          Expanded(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Expanded(child: _buildTierWorkspace(tierEntries)),
                const SizedBox(width: AppSpacing.md),
                SizedBox(
                  width: sidebarWidth,
                  child: _buildUnratedPool(
                    entries: visibleUnrated,
                    totalUnrated: totalUnrated,
                    compactLayout: true,
                    sidebarLayout: true,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTierWorkspace(
    Map<String, List<Map<String, dynamic>>> tierEntries,
  ) {
    final colorScheme = Theme.of(context).colorScheme;
    return Container(
      key: const ValueKey('fun-rating-tier-panel'),
      decoration: BoxDecoration(
        color: colorScheme.surfaceContainerLowest,
        borderRadius: BorderRadius.circular(AppRadius.md),
        border: Border.all(
          color: colorScheme.outlineVariant.withValues(alpha: 0.45),
        ),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(
              AppSpacing.md,
              AppSpacing.md,
              AppSpacing.md,
              AppSpacing.sm,
            ),
            child: _buildTierHeading(),
          ),
          Expanded(
            child: ListView.separated(
              key: const PageStorageKey('fun-rating-tier-list'),
              primary: false,
              padding: const EdgeInsets.fromLTRB(
                AppSpacing.md,
                AppSpacing.sm,
                AppSpacing.md,
                AppSpacing.md,
              ),
              itemCount: FunRatingTierConfig.codes.length,
              separatorBuilder: (_, _) => const SizedBox(height: AppSpacing.sm),
              itemBuilder: (context, index) {
                final code = FunRatingTierConfig.codes[index];
                return _TierBoardSection(
                  code: code,
                  label: _tierConfig.labelFor(code),
                  color: funRatingTierColor(code),
                  entries: tierEntries[code] ?? const [],
                  appDocDir: _appDocDir,
                  compactLayout: true,
                  onTapAnime: _chooseTier,
                );
              },
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildHero({required bool compactLayout, required int ratedCount}) {
    final colorScheme = Theme.of(context).colorScheme;
    final progress = _animes.isEmpty ? 0.0 : ratedCount / _animes.length;

    if (compactLayout) {
      return Container(
        key: const ValueKey('fun-rating-hero'),
        padding: const EdgeInsets.all(AppSpacing.md),
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              colorScheme.primaryContainer,
              colorScheme.tertiaryContainer,
            ],
          ),
          borderRadius: BorderRadius.circular(AppRadius.md),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    _boardTitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.titleLarge?.copyWith(
                      fontWeight: FontWeight.w900,
                      color: colorScheme.onPrimaryContainer,
                    ),
                  ),
                ),
                const SizedBox(width: AppSpacing.sm),
                Text(
                  '$ratedCount / ${_animes.length}',
                  style: TextStyle(
                    color: colorScheme.onPrimaryContainer,
                    fontWeight: FontWeight.w900,
                  ),
                ),
              ],
            ),
            const SizedBox(height: AppSpacing.xs),
            Text(
              '点击任意封面选择 S～D 档，列表可以自由滑动。',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: colorScheme.onPrimaryContainer.withValues(alpha: 0.76),
                fontSize: 12,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: AppSpacing.sm),
            ClipRRect(
              borderRadius: BorderRadius.circular(AppRadius.full),
              child: LinearProgressIndicator(
                value: progress,
                minHeight: 6,
                backgroundColor: colorScheme.surface.withValues(alpha: 0.45),
              ),
            ),
          ],
        ),
      );
    }

    final info = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          _boardTitle,
          style: Theme.of(context).textTheme.headlineSmall?.copyWith(
            fontWeight: FontWeight.w900,
            color: colorScheme.onPrimaryContainer,
          ),
        ),
        const SizedBox(height: AppSpacing.sm),
        Text(
          '这是独立的趣味梯队榜，不会改变作品详情里的原有评分。',
          style: TextStyle(
            color: colorScheme.onPrimaryContainer.withValues(alpha: 0.76),
            fontWeight: FontWeight.w600,
          ),
        ),
        const SizedBox(height: AppSpacing.lg),
        ClipRRect(
          borderRadius: BorderRadius.circular(AppRadius.full),
          child: LinearProgressIndicator(
            value: progress,
            minHeight: 8,
            backgroundColor: colorScheme.surface.withValues(alpha: 0.45),
          ),
        ),
        const SizedBox(height: AppSpacing.sm),
        Text(
          '已评级 $ratedCount / ${_animes.length}',
          style: TextStyle(
            color: colorScheme.onPrimaryContainer,
            fontWeight: FontWeight.w800,
          ),
        ),
      ],
    );
    final actions = Wrap(
      spacing: AppSpacing.sm,
      runSpacing: AppSpacing.sm,
      children: [
        OutlinedButton.icon(
          onPressed: _editBoard,
          icon: const Icon(Icons.edit_outlined),
          label: const Text('自定义'),
        ),
        FilledButton.icon(
          onPressed: ratedCount == 0 ? null : _openSharePreview,
          icon: const Icon(Icons.ios_share_rounded),
          label: const Text('生成分享图'),
        ),
      ],
    );
    return Container(
      key: const ValueKey('fun-rating-hero'),
      padding: const EdgeInsets.all(AppSpacing.xl),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [colorScheme.primaryContainer, colorScheme.tertiaryContainer],
        ),
        borderRadius: BorderRadius.circular(AppRadius.md),
        boxShadow: AppElevation.card(context),
      ),
      child: Row(
        children: [
          Expanded(child: info),
          const SizedBox(width: AppSpacing.xl),
          actions,
        ],
      ),
    );
  }

  Widget _buildTierHeading() {
    final colorScheme = Theme.of(context).colorScheme;
    return Row(
      children: [
        Icon(Icons.stacked_bar_chart_rounded, color: colorScheme.primary),
        const SizedBox(width: AppSpacing.sm),
        Text(
          '我的梯队',
          style: Theme.of(
            context,
          ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w900),
        ),
        const Spacer(),
        Text(
          '空档位已收起',
          style: Theme.of(context).textTheme.labelMedium?.copyWith(
            color: colorScheme.onSurfaceVariant,
          ),
        ),
      ],
    );
  }

  Widget _buildUnratedPool({
    required List<Map<String, dynamic>> entries,
    required int totalUnrated,
    required bool compactLayout,
    bool sidebarLayout = false,
  }) {
    final colorScheme = Theme.of(context).colorScheme;
    final emptyMessage = _query.isEmpty ? '所有番剧都已经进入榜单啦。' : '没有找到匹配的待评级番剧。';
    return Container(
      key: const ValueKey('fun-rating-unrated-pool'),
      padding: EdgeInsets.all(compactLayout ? AppSpacing.md : AppSpacing.lg),
      decoration: BoxDecoration(
        color: colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(AppRadius.md),
        border: Border.all(
          color: colorScheme.outlineVariant.withValues(alpha: 0.45),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Icon(Icons.inventory_2_outlined, color: colorScheme.primary),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                child: Text(
                  '待评级 · $totalUnrated',
                  style:
                      (compactLayout
                              ? Theme.of(context).textTheme.titleMedium
                              : Theme.of(context).textTheme.titleLarge)
                          ?.copyWith(fontWeight: FontWeight.w900),
                ),
              ),
              Icon(
                Icons.touch_app_rounded,
                size: 20,
                color: colorScheme.primary,
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.sm),
          Text(
            sidebarLayout ? '点击封面选择档位，滚动列表查看更多。' : '点击封面选择档位，横向滑动查看更多。',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              color: colorScheme.onSurfaceVariant,
              fontSize: compactLayout ? 12 : null,
            ),
          ),
          const SizedBox(height: AppSpacing.md),
          TextField(
            controller: _searchController,
            onChanged: _onSearchChanged,
            textInputAction: TextInputAction.search,
            decoration: InputDecoration(
              hintText: '搜索待评级番剧',
              prefixIcon: const Icon(Icons.search_rounded),
              isDense: compactLayout,
              suffixIcon: _query.isEmpty
                  ? null
                  : IconButton(
                      tooltip: '清空搜索',
                      onPressed: _clearSearch,
                      icon: const Icon(Icons.close_rounded),
                    ),
            ),
          ),
          SizedBox(height: compactLayout ? AppSpacing.md : AppSpacing.lg),
          if (sidebarLayout)
            Expanded(
              child: entries.isEmpty
                  ? Center(
                      child: Text(
                        emptyMessage,
                        textAlign: TextAlign.center,
                        style: TextStyle(color: colorScheme.onSurfaceVariant),
                      ),
                    )
                  : GridView.builder(
                      key: const PageStorageKey(
                        'fun-rating-unrated-sidebar-grid',
                      ),
                      primary: false,
                      keyboardDismissBehavior:
                          ScrollViewKeyboardDismissBehavior.onDrag,
                      gridDelegate:
                          const SliverGridDelegateWithMaxCrossAxisExtent(
                            maxCrossAxisExtent: 112,
                            mainAxisSpacing: AppSpacing.md,
                            crossAxisSpacing: AppSpacing.sm,
                            childAspectRatio: 0.62,
                          ),
                      itemCount: entries.length,
                      itemBuilder: (context, index) {
                        final anime = entries[index];
                        return _RatingAnimeCard(
                          anime: anime,
                          appDocDir: _appDocDir,
                          compact: true,
                          onTap: () => _chooseTier(anime),
                        );
                      },
                    ),
            )
          else if (entries.isEmpty)
            Padding(
              padding: EdgeInsets.symmetric(
                vertical: compactLayout ? AppSpacing.lg : AppSpacing.xl,
              ),
              child: Text(
                emptyMessage,
                textAlign: TextAlign.center,
                style: TextStyle(color: colorScheme.onSurfaceVariant),
              ),
            )
          else if (compactLayout)
            SizedBox(
              height: 154,
              child: ListView.separated(
                key: const PageStorageKey('fun-rating-unrated-carousel'),
                scrollDirection: Axis.horizontal,
                keyboardDismissBehavior:
                    ScrollViewKeyboardDismissBehavior.onDrag,
                itemCount: entries.length,
                separatorBuilder: (_, _) =>
                    const SizedBox(width: AppSpacing.sm),
                itemBuilder: (context, index) {
                  final anime = entries[index];
                  return SizedBox(
                    width: 88,
                    child: _RatingAnimeCard(
                      anime: anime,
                      appDocDir: _appDocDir,
                      compact: true,
                      onTap: () => _chooseTier(anime),
                    ),
                  );
                },
              ),
            )
          else
            LayoutBuilder(
              builder: (context, constraints) {
                final columns = (constraints.maxWidth / 124).floor().clamp(
                  1,
                  12,
                );
                final rows = (entries.length + columns - 1) ~/ columns;
                final calculatedHeight = rows * 214.0;
                final gridHeight = calculatedHeight
                    .clamp(214.0, 520.0)
                    .toDouble();
                return SizedBox(
                  height: gridHeight,
                  child: GridView.builder(
                    key: const PageStorageKey('fun-rating-unrated-grid'),
                    primary: false,
                    physics: calculatedHeight > gridHeight
                        ? const ClampingScrollPhysics()
                        : const NeverScrollableScrollPhysics(),
                    gridDelegate:
                        const SliverGridDelegateWithMaxCrossAxisExtent(
                          maxCrossAxisExtent: 124,
                          mainAxisSpacing: AppSpacing.md,
                          crossAxisSpacing: AppSpacing.md,
                          childAspectRatio: 0.58,
                        ),
                    itemCount: entries.length,
                    itemBuilder: (context, index) {
                      final anime = entries[index];
                      return _RatingAnimeCard(
                        anime: anime,
                        appDocDir: _appDocDir,
                        onTap: () => _chooseTier(anime),
                      );
                    },
                  ),
                );
              },
            ),
        ],
      ),
    );
  }
}

class _TierBoardSection extends StatelessWidget {
  final String code;
  final String label;
  final Color color;
  final List<Map<String, dynamic>> entries;
  final Directory? appDocDir;
  final bool compactLayout;
  final ValueChanged<Map<String, dynamic>> onTapAnime;

  const _TierBoardSection({
    required this.code,
    required this.label,
    required this.color,
    required this.entries,
    required this.appDocDir,
    required this.compactLayout,
    required this.onTapAnime,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final showBody = !compactLayout || entries.isNotEmpty;
    return RepaintBoundary(
      key: ValueKey('fun-rating-tier-$code'),
      child: Container(
        decoration: BoxDecoration(
          color: colorScheme.surfaceContainerLow,
          borderRadius: BorderRadius.circular(AppRadius.sm),
          border: Border.all(
            color: colorScheme.outlineVariant.withValues(alpha: 0.38),
          ),
          boxShadow: compactLayout ? const [] : AppElevation.card(context),
        ),
        clipBehavior: Clip.antiAlias,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(
              padding: EdgeInsets.symmetric(
                horizontal: compactLayout ? AppSpacing.md : AppSpacing.lg,
                vertical: compactLayout ? AppSpacing.sm : AppSpacing.md,
              ),
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  colors: [color, color.withValues(alpha: 0.72)],
                ),
              ),
              child: Row(
                children: [
                  _TierCodeBadge(
                    code: code,
                    color: Colors.white24,
                    compact: compactLayout,
                  ),
                  SizedBox(
                    width: compactLayout ? AppSpacing.sm : AppSpacing.md,
                  ),
                  Expanded(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          label,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            color: Colors.white,
                            fontSize: compactLayout ? 16 : 18,
                            fontWeight: FontWeight.w900,
                          ),
                        ),
                        if (compactLayout && entries.isEmpty)
                          const Text(
                            '点击待评级封面后选择此档',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              color: Colors.white70,
                              fontSize: 10,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                      ],
                    ),
                  ),
                  Text(
                    '${entries.length} 部',
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: compactLayout ? 12 : null,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ],
              ),
            ),
            if (showBody)
              SizedBox(
                height: compactLayout ? (entries.isEmpty ? 64 : 134) : 158,
                child: entries.isEmpty
                    ? Center(
                        child: Text(
                          '点击待评级作品，并选择 $label',
                          style: TextStyle(
                            color: colorScheme.onSurfaceVariant,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      )
                    : ListView.separated(
                        scrollDirection: Axis.horizontal,
                        padding: EdgeInsets.all(
                          compactLayout ? AppSpacing.sm : AppSpacing.md,
                        ),
                        itemCount: entries.length,
                        separatorBuilder: (_, _) => SizedBox(
                          width: compactLayout ? AppSpacing.sm : AppSpacing.md,
                        ),
                        itemBuilder: (context, index) {
                          final anime = entries[index];
                          return SizedBox(
                            width: compactLayout ? 72 : 78,
                            child: _RatingAnimeCard(
                              anime: anime,
                              appDocDir: appDocDir,
                              compact: true,
                              onTap: () => onTapAnime(anime),
                            ),
                          );
                        },
                      ),
              ),
          ],
        ),
      ),
    );
  }
}

class _RatingAnimeCard extends StatelessWidget {
  final Map<String, dynamic> anime;
  final Directory? appDocDir;
  final VoidCallback onTap;
  final bool compact;

  const _RatingAnimeCard({
    required this.anime,
    required this.appDocDir,
    required this.onTap,
    this.compact = false,
  });

  @override
  Widget build(BuildContext context) {
    return Tooltip(
      message: '点击选择趣味评级档位',
      child: RepaintBoundary(
        key: ValueKey('fun-rating-anime-${anime['id']}'),
        child: _AnimeTierThumbnail(
          anime: anime,
          appDocDir: appDocDir,
          onTap: onTap,
          compact: compact,
        ),
      ),
    );
  }
}

class _AnimeTierThumbnail extends StatelessWidget {
  final Map<String, dynamic> anime;
  final Directory? appDocDir;
  final VoidCallback onTap;
  final bool compact;

  const _AnimeTierThumbnail({
    required this.anime,
    required this.appDocDir,
    required this.onTap,
    required this.compact,
  });

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(AppRadius.xs),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(
            child: ClipRRect(
              borderRadius: BorderRadius.circular(AppRadius.xs),
              child: AnimeCoverImage(
                url: anime['cover_url']?.toString(),
                appDocDir: appDocDir,
                width: compact ? 88 : 116,
              ),
            ),
          ),
          const SizedBox(height: 5),
          Text(
            (anime['title'] ?? '未命名').toString(),
            maxLines: compact ? 1 : 2,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: compact ? 10 : 11,
              height: 1.15,
              fontWeight: FontWeight.w700,
            ),
          ),
        ],
      ),
    );
  }
}

class _TierCodeBadge extends StatelessWidget {
  final String code;
  final Color color;
  final bool compact;

  const _TierCodeBadge({
    required this.code,
    required this.color,
    this.compact = false,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: compact ? 36 : 42,
      height: compact ? 36 : 42,
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(compact ? 11 : 13),
      ),
      alignment: Alignment.center,
      child: Text(
        code,
        style: TextStyle(
          color: Colors.white,
          fontSize: compact ? 17 : 19,
          fontWeight: FontWeight.w900,
        ),
      ),
    );
  }
}

class FunRatingBoardSettingsResult {
  final String title;
  final FunRatingTierConfig config;

  const FunRatingBoardSettingsResult({
    required this.title,
    required this.config,
  });
}

class FunRatingBoardSettingsSheet extends StatefulWidget {
  final String initialTitle;
  final FunRatingTierConfig initialConfig;

  const FunRatingBoardSettingsSheet({
    super.key,
    required this.initialTitle,
    required this.initialConfig,
  });

  @override
  State<FunRatingBoardSettingsSheet> createState() =>
      _FunRatingBoardSettingsSheetState();
}

class _FunRatingBoardSettingsSheetState
    extends State<FunRatingBoardSettingsSheet> {
  late final TextEditingController _titleController;
  late final Map<String, TextEditingController> _tierControllers;

  @override
  void initState() {
    super.initState();
    _titleController = TextEditingController(text: widget.initialTitle)
      ..addListener(_notifyChanged);
    _tierControllers = {
      for (var index = 0; index < FunRatingTierConfig.codes.length; index++)
        FunRatingTierConfig.codes[index]: TextEditingController(
          text: widget.initialConfig.labels[index],
        )..addListener(_notifyChanged),
    };
  }

  void _notifyChanged() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    _titleController.dispose();
    for (final controller in _tierControllers.values) {
      controller.dispose();
    }
    super.dispose();
  }

  List<String> get _labels => FunRatingTierConfig.codes
      .map((code) => _tierControllers[code]!.text)
      .toList(growable: false);

  String? get _validationError {
    final title = _titleController.text.trim();
    if (title.isEmpty) return '榜单标题不能为空';
    if (title.length > FunRatingService.maxBoardTitleLength) {
      return '榜单标题最多 ${FunRatingService.maxBoardTitleLength} 个字符';
    }
    return FunRatingTierConfig.validationError(_labels);
  }

  void _applyPreset(FunRatingTierConfig config) {
    for (var index = 0; index < FunRatingTierConfig.codes.length; index++) {
      _tierControllers[FunRatingTierConfig.codes[index]]!.text =
          config.labels[index];
    }
  }

  void _submit() {
    if (_validationError != null) return;
    Navigator.of(context).pop(
      FunRatingBoardSettingsResult(
        title: _titleController.text.trim(),
        config: FunRatingTierConfig.fromLabels(_labels),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: FractionallySizedBox(
        heightFactor: 0.9,
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(
            AppSpacing.lg,
            AppSpacing.sm,
            AppSpacing.lg,
            AppSpacing.xl,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                '自定义趣味评级',
                style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                  fontWeight: FontWeight.w900,
                ),
              ),
              const SizedBox(height: AppSpacing.xs),
              Text(
                '档位代码始终是 S / A / B / C / D；修改显示名称不会打乱已评级作品。',
                style: TextStyle(color: colorScheme.onSurfaceVariant),
              ),
              const SizedBox(height: AppSpacing.xl),
              TextField(
                controller: _titleController,
                maxLength: FunRatingService.maxBoardTitleLength,
                decoration: const InputDecoration(
                  labelText: '分享图标题',
                  prefixIcon: Icon(Icons.title_rounded),
                ),
              ),
              const SizedBox(height: AppSpacing.md),
              Text(
                '快速预设',
                style: Theme.of(
                  context,
                ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w900),
              ),
              const SizedBox(height: AppSpacing.sm),
              Wrap(
                spacing: AppSpacing.sm,
                runSpacing: AppSpacing.sm,
                children: [
                  ActionChip(
                    avatar: const Icon(Icons.abc_rounded, size: 18),
                    label: const Text('默认字母'),
                    onPressed: () => _applyPreset(FunRatingTierConfig.letters),
                  ),
                  ActionChip(
                    avatar: const Icon(Icons.translate_rounded, size: 18),
                    label: const Text('中文示例'),
                    onPressed: () => _applyPreset(FunRatingTierConfig.chinese),
                  ),
                  ActionChip(
                    avatar: const Icon(Icons.language_rounded, size: 18),
                    label: const Text('English'),
                    onPressed: () => _applyPreset(FunRatingTierConfig.english),
                  ),
                ],
              ),
              const SizedBox(height: AppSpacing.xl),
              for (final code in FunRatingTierConfig.codes) ...[
                Row(
                  children: [
                    _TierCodeBadge(code: code, color: funRatingTierColor(code)),
                    const SizedBox(width: AppSpacing.md),
                    Expanded(
                      child: TextField(
                        controller: _tierControllers[code],
                        maxLength: FunRatingTierConfig.maxLabelLength,
                        inputFormatters: [
                          LengthLimitingTextInputFormatter(
                            FunRatingTierConfig.maxLabelLength,
                          ),
                        ],
                        textInputAction: code == FunRatingTierConfig.codes.last
                            ? TextInputAction.done
                            : TextInputAction.next,
                        decoration: InputDecoration(
                          labelText: '$code 档显示名称',
                          counterText: '',
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: AppSpacing.md),
              ],
              if (_validationError != null) ...[
                Text(
                  _validationError!,
                  style: TextStyle(
                    color: colorScheme.error,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: AppSpacing.md),
              ],
              FilledButton.icon(
                onPressed: _validationError == null ? _submit : null,
                icon: const Icon(Icons.save_outlined),
                label: const Text('保存榜单设置'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
