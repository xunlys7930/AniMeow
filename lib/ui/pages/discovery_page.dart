import 'package:provider/provider.dart';
import 'package:anime_tracker/providers/data_refresh_provider.dart';
import 'package:flutter/material.dart';
import 'package:anime_tracker/api/bangumi_service.dart';
import 'add_anime_page.dart';
import 'package:anime_tracker/ui/components/empty_state.dart';
import 'package:anime_tracker/ui/design_tokens.dart';
import 'package:anime_tracker/settings/server_discovery_page.dart';
import 'package:anime_tracker/settings/server_discovery_season.dart';
import 'package:anime_tracker/settings_manager.dart';
import 'package:anime_tracker/utils/api_config.dart';

class DiscoveryPage extends StatefulWidget {
  final VoidCallback? onAnimeAdded;
  const DiscoveryPage({super.key, this.onAnimeAdded});

  @override
  State<DiscoveryPage> createState() => DiscoveryPageState();
}

class DiscoveryPageState extends State<DiscoveryPage>
    with AutomaticKeepAliveClientMixin {
  static const List<String> _formats = ['TV', '剧场版', 'OVA', 'Web', 'SP'];
  static const List<String> _regions = ['日本', '中国', '欧美'];
  static const List<String> _styles = [
    '搞笑',
    '恋爱',
    '科幻',
    '奇幻',
    '战斗',
    '校园',
    '日常',
    '治愈',
    '致郁',
    '悬疑',
    '推理',
    '美食',
    '职场',
    '音乐',
    '偶像',
    '运动',
    '机战',
  ];
  static const List<String> _adaptations = ['原创', '漫画改', '小说改', '游戏改'];

  late final List<String> _years;
  late _DiscoveryFilters _filters;
  List<BangumiSearchResult> _results = [];
  bool _isLoading = false;
  int _offset = 0;
  bool _hasMore = true;
  int _fetchRequestId = 0;
  DataRefreshProvider? _refreshProvider;
  final ScrollController _scrollController = ScrollController();

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        _refreshProvider = context.read<DataRefreshProvider>();
        _refreshProvider!.addListener(_onGlobalRefresh);
      }
    });
    _initTags();
    _fetchData();
    _scrollController.addListener(_onScroll);
  }

  void _initTags() {
    final currentYear = DateTime.now().year;
    final years = <String>['全部'];
    // 生成从今年到过去的年份列表，避免默认显示今年但分类首项是明年。
    for (int i = 0; i < 15; i++) {
      years.add((currentYear - i).toString());
    }
    years.addAll(['2000s', '1990s']);
    _years = years;
    // 默认仍展示今年，用户可在筛选抽屉中切到“全部”。
    _filters = _DiscoveryFilters.defaults(currentYear);
  }

  void _onGlobalRefresh() {
    if (mounted) refreshData();
  }

  @override
  void dispose() {
    _refreshProvider?.removeListener(_onGlobalRefresh);

    _scrollController.dispose();
    super.dispose();
  }

  void _onScroll() {
    if (_scrollController.position.pixels >=
            _scrollController.position.maxScrollExtent - 200 &&
        !_isLoading &&
        _hasMore) {
      _fetchData(loadMore: true);
    }
  }

  Future<void> _fetchData({
    bool loadMore = false,
    bool isUserRefresh = false,
    bool isSilentRefresh = false,
  }) async {
    if (_isLoading && loadMore) return;

    // 如果是有数据且是静默刷新（通常由外部 globalRefresh 触发），则直接返回，保持现状
    if (!loadMore && isSilentRefresh && _results.isNotEmpty) {
      return;
    }

    final requestId = ++_fetchRequestId;
    final requestFilters = _filters;
    final requestSignature = requestFilters.signature;
    final requestOffset = loadMore ? _offset : 0;

    setState(() {
      if (!loadMore) {
        _offset = 0;
        _hasMore = true;
      }
      _isLoading = true;
    });

    try {
      final newResults = await _fetchDiscoveryResults(
        filters: requestFilters,
        offset: requestOffset,
        limit: 24,
      );

      if (mounted &&
          requestId == _fetchRequestId &&
          requestSignature == _filters.signature) {
        setState(() {
          if (newResults.isEmpty) {
            _hasMore = false;
          } else {
            if (loadMore) {
              _results.addAll(newResults);
            } else {
              _results = newResults; // 刷新时直接替换
            }
            _offset = requestOffset + newResults.length;
            if (newResults.length < 24) _hasMore = false;
          }
        });
      }
    } catch (e) {
      if (mounted && requestId == _fetchRequestId) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('加载失败: $e')));
      }
    } finally {
      if (mounted && requestId == _fetchRequestId) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  Future<List<BangumiSearchResult>> _fetchDiscoveryResults({
    required _DiscoveryFilters filters,
    required int offset,
    required int limit,
  }) async {
    if (ApiConfig.hasCloudBase) {
      final tagFilters = filters.tagFilters;
      final serverResults = await BangumiService.getServerAnimes(
        tag: tagFilters.isEmpty ? null : tagFilters.first,
        tags: tagFilters,
        year: filters.serverYear,
        month: filters.seasonMonth?.toString(),
        format: filters.format,
        sort: filters.sort.serverKey,
      );
      final filtered = _sortResults(
        _filterResults(serverResults, filters),
        filters.sort,
      );
      final page = filtered.skip(offset).take(limit).toList();
      if (page.isNotEmpty || filters.hasServerOnlyConditions) return page;
    }

    return _fetchBangumiFallback(
      filters: filters,
      offset: offset,
      limit: limit,
    );
  }

  Future<List<BangumiSearchResult>> _fetchBangumiFallback({
    required _DiscoveryFilters filters,
    required int offset,
    required int limit,
  }) async {
    final primaryTag = filters.primaryBangumiTag;
    if (primaryTag != null) {
      final results = await BangumiService.searchByTag(
        primaryTag,
        offset: offset,
        limit: limit,
      );
      return _sortResults(_filterResults(results, filters), filters.sort);
    }

    final yearRange = _rangeForFilters(filters);
    if (yearRange != null) {
      return BangumiService.searchAnimeByAirDateRange(
        startDate: yearRange.startDate,
        endDate: yearRange.endDate,
        offset: offset,
        limit: limit,
      );
    }

    final results = await BangumiService.searchByTag(
      'TV',
      offset: offset,
      limit: limit,
    );
    return _sortResults(_filterResults(results, filters), filters.sort);
  }

  _DiscoveryYearRange? _rangeForFilters(_DiscoveryFilters filters) {
    final normalizedYear = filters.normalizedYear;
    if (normalizedYear != null && filters.seasonMonth != null) {
      final year = int.tryParse(normalizedYear);
      if (year == null) return null;
      final start = serverDiscoverySeasonStart(year, filters.seasonMonth!);
      final end = serverDiscoveryNextSeasonStart(year, filters.seasonMonth!);
      return _DiscoveryYearRange(_dateKey(start), _dateKey(end));
    }

    if (normalizedYear != null) {
      return _yearRangeForTag(normalizedYear);
    }

    return null;
  }

  _DiscoveryYearRange? _yearRangeForTag(String tag) {
    final year = int.tryParse(tag);
    if (year != null) {
      return _DiscoveryYearRange('$year-01-01', '${year + 1}-01-01');
    }

    if (tag == '2000s') {
      return const _DiscoveryYearRange('2000-01-01', '2010-01-01');
    }
    if (tag == '1990s') {
      return const _DiscoveryYearRange('1990-01-01', '2000-01-01');
    }

    return null;
  }

  String _dateKey(DateTime date) {
    final year = date.year.toString().padLeft(4, '0');
    final month = date.month.toString().padLeft(2, '0');
    final day = date.day.toString().padLeft(2, '0');
    return '$year-$month-$day';
  }

  List<BangumiSearchResult> _filterResults(
    Iterable<BangumiSearchResult> results,
    _DiscoveryFilters filters,
  ) {
    final seasonYear = filters.normalizedYear == null
        ? null
        : int.tryParse(filters.normalizedYear!);

    return results.where((anime) {
      if (filters.seasonMonth != null) {
        if (!isAirDateInServerDiscoverySeason(
          anime.airDate,
          seasonMonth: filters.seasonMonth!,
          seasonYear: seasonYear,
        )) {
          return false;
        }
      } else if (filters.normalizedYear != null) {
        final range = _yearRangeForTag(filters.normalizedYear!);
        final airKey = _airDateKey(anime.airDate);
        if (range != null &&
            (airKey.isEmpty ||
                airKey.compareTo(range.startDate) < 0 ||
                airKey.compareTo(range.endDate) >= 0)) {
          return false;
        }
      }

      final haystack = _filterHaystack(anime);
      if (filters.format != null &&
          !haystack.contains(filters.format!.toLowerCase())) {
        return false;
      }
      for (final tag in filters.tagFilters) {
        if (!haystack.contains(tag.toLowerCase())) return false;
      }
      return true;
    }).toList();
  }

  List<BangumiSearchResult> _sortResults(
    List<BangumiSearchResult> results,
    _DiscoverySort sort,
  ) {
    final sorted = [...results];
    switch (sort) {
      case _DiscoverySort.latest:
        sorted.sort(
          (a, b) => (_airDateKey(b.airDate)).compareTo(_airDateKey(a.airDate)),
        );
        break;
      case _DiscoverySort.score:
        sorted.sort(
          (a, b) => ((b.score ?? 0).compareTo(a.score ?? 0)) != 0
              ? (b.score ?? 0).compareTo(a.score ?? 0)
              : _airDateKey(b.airDate).compareTo(_airDateKey(a.airDate)),
        );
        break;
      case _DiscoverySort.title:
        sorted.sort((a, b) => _displayTitle(a).compareTo(_displayTitle(b)));
        break;
    }
    return sorted;
  }

  String _filterHaystack(BangumiSearchResult anime) {
    return [
      anime.nameCn,
      anime.nameOriginal,
      anime.summary ?? '',
      anime.studio ?? '',
      anime.format ?? '',
      ...anime.tags,
    ].join(' ').toLowerCase();
  }

  String _airDateKey(String? airDate) {
    final value = airDate?.trim();
    if (value == null || value.isEmpty) return '';
    return value;
  }

  String _displayTitle(BangumiSearchResult anime) {
    return anime.nameCn.isNotEmpty ? anime.nameCn : anime.nameOriginal;
  }

  void refreshData() {
    _fetchData(isSilentRefresh: true);
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('发现'),
            Text(
              _results.isEmpty ? '探索值得加入的作品' : '已载入 ${_results.length} 部作品',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
        actions: [
          ValueListenableBuilder<bool>(
            valueListenable: SettingsManager().showServerDiscoveryNotifier,
            builder: (context, show, _) {
              if (!show) return const SizedBox.shrink();
              return IconButton(
                icon: const Icon(Icons.cloud_outlined),
                tooltip: '云端资源发现',
                onPressed: () {
                  Navigator.push(
                    context,
                    MaterialPageRoute(
                      builder: (context) => const ServerDiscoveryPage(),
                    ),
                  );
                },
              );
            },
          ),
          const SizedBox(width: AppSpacing.sm),
        ],
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(68),
          child: _buildFilterSummaryBar(),
        ),
      ),
      body: Column(
        children: [
          AnimatedSize(
            duration: AppMotion.resolve(context, AppMotion.short),
            child: _isLoading && _results.isNotEmpty
                ? const LinearProgressIndicator(minHeight: 2)
                : const SizedBox(height: 0),
          ),
          Expanded(child: _buildBody()),
        ],
      ),
    );
  }

  Widget _buildFilterSummaryBar() {
    final labels = _filters.activeLabels;
    final activeCount = _filters.activeCount;
    final colorScheme = Theme.of(context).colorScheme;

    return Container(
      height: 68,
      padding: const EdgeInsets.fromLTRB(
        AppSpacing.lg,
        AppSpacing.sm,
        AppSpacing.lg,
        AppSpacing.md,
      ),
      alignment: Alignment.center,
      child: Row(
        children: [
          Badge(
            isLabelVisible: activeCount > 0,
            label: Text('$activeCount'),
            child: FilledButton.tonalIcon(
              onPressed: _openFilterSheet,
              icon: const Icon(Icons.tune_rounded),
              label: const Text('筛选'),
            ),
          ),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: labels.isEmpty
                ? Text(
                    '全部番剧',
                    style: TextStyle(
                      color: colorScheme.onSurfaceVariant,
                      fontWeight: FontWeight.w700,
                    ),
                  )
                : ListView.separated(
                    scrollDirection: Axis.horizontal,
                    physics: const BouncingScrollPhysics(),
                    itemCount: labels.length,
                    separatorBuilder: (_, _) =>
                        const SizedBox(width: AppSpacing.sm),
                    itemBuilder: (context, index) {
                      return Chip(
                        label: Text(labels[index]),
                        visualDensity: VisualDensity.compact,
                        side: BorderSide.none,
                        backgroundColor: colorScheme.primaryContainer
                            .withValues(alpha: 0.68),
                        labelStyle: TextStyle(
                          color: colorScheme.onPrimaryContainer,
                          fontSize: 12,
                          fontWeight: FontWeight.w700,
                        ),
                      );
                    },
                  ),
          ),
          if (activeCount > 0) ...[
            const SizedBox(width: AppSpacing.xs),
            IconButton(
              tooltip: '重置筛选',
              onPressed: () {
                setState(() => _filters = const _DiscoveryFilters());
                _fetchData();
              },
              icon: const Icon(Icons.restart_alt_rounded),
            ),
          ],
        ],
      ),
    );
  }

  Future<void> _openFilterSheet() async {
    final result = await showModalBottomSheet<_DiscoveryFilters>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      constraints: AppBreakpoints.isMedium(MediaQuery.sizeOf(context).width)
          ? const BoxConstraints(maxWidth: 720)
          : null,
      builder: (context) => _DiscoveryFilterSheet(
        initial: _filters,
        years: _years,
        formats: _formats,
        regions: _regions,
        styles: _styles,
        adaptations: _adaptations,
      ),
    );

    if (result == null || result.signature == _filters.signature) return;
    setState(() => _filters = result);
    _fetchData();
  }

  Widget _buildBody() {
    if (_results.isEmpty && _isLoading) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const CircularProgressIndicator(),
            const SizedBox(height: AppSpacing.lg),
            Text(
              '正在为你整理本季作品…',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      );
    }
    if (_results.isEmpty) {
      return EmptyStateWidget(
        icon: Icons.explore_off_rounded,
        message: '没有找到符合条件的作品',
        description: '可以放宽年份、类型或标签条件后再试一次',
        buttonText: _filters.activeCount > 0 ? '重置筛选' : '重新加载',
        onButtonPressed: () {
          if (_filters.activeCount > 0) {
            setState(() => _filters = const _DiscoveryFilters());
          }
          _fetchData();
        },
      );
    }

    return RefreshIndicator(
      onRefresh: () => _fetchData(isUserRefresh: true),
      child: LayoutBuilder(
        builder: (context, constraints) {
          final horizontalPadding = AppBreakpoints.pagePadding(
            constraints.maxWidth,
          );
          return GridView.builder(
            controller: _scrollController,
            keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
            padding: EdgeInsets.fromLTRB(
              horizontalPadding,
              AppSpacing.md,
              horizontalPadding,
              AppSpacing.xxl * 2,
            ),
            gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(
              maxCrossAxisExtent: 190,
              childAspectRatio: 0.62,
              crossAxisSpacing: AppSpacing.md,
              mainAxisSpacing: AppSpacing.lg,
            ),
            itemCount: _results.length + (_hasMore ? 1 : 0),
            itemBuilder: (context, index) {
              if (index == _results.length) {
                return const Center(child: CircularProgressIndicator());
              }
              final anime = _results[index];
              return _buildAnimeCard(anime);
            },
          );
        },
      ),
    );
  }

  Widget _buildAnimeCard(BangumiSearchResult anime) {
    final colorScheme = Theme.of(context).colorScheme;
    return Card(
      clipBehavior: Clip.antiAlias,
      color: colorScheme.surfaceContainerLow,
      child: InkWell(
        onTap: () {
          // 跳转到添加页面，并预填信息
          Navigator.push(
            context,
            MaterialPageRoute(
              builder: (context) => AddAnimePage(
                initialSearchQuery: anime.nameCn.isNotEmpty
                    ? anime.nameCn
                    : anime.nameOriginal,
                isDiscoveryMode: true,
              ),
            ),
          ).then((value) {
            if (value == true) {
              widget.onAnimeAdded?.call();
            }
          });
        },
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: Stack(
                fit: StackFit.expand,
                children: [
                  Hero(
                    tag: 'discovery-${anime.source}-${anime.id}',
                    child: anime.coverUrl != null
                        ? Image.network(
                            anime.coverUrl!,
                            fit: BoxFit.cover,
                            errorBuilder: (ctx, err, stack) => Container(
                              color: colorScheme.surfaceContainerHighest,
                              child: Icon(
                                Icons.broken_image_outlined,
                                color: colorScheme.onSurfaceVariant,
                              ),
                            ),
                          )
                        : Container(
                            color: colorScheme.surfaceContainerHighest,
                            child: Icon(
                              Icons.movie_outlined,
                              color: colorScheme.onSurfaceVariant,
                            ),
                          ),
                  ),
                  if (anime.score != null && anime.score! > 0)
                    Positioned(
                      top: AppSpacing.sm,
                      right: AppSpacing.sm,
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: AppSpacing.sm,
                          vertical: AppSpacing.xs,
                        ),
                        decoration: BoxDecoration(
                          color: Colors.black.withValues(alpha: 0.72),
                          borderRadius: BorderRadius.circular(AppRadius.full),
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Icon(
                              Icons.star_rounded,
                              color: Colors.amber,
                              size: 14,
                            ),
                            const SizedBox(width: 2),
                            Text(
                              anime.score!.toStringAsFixed(1),
                              style: const TextStyle(
                                color: Colors.white,
                                fontSize: 11,
                                fontWeight: FontWeight.w800,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(AppSpacing.md),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _displayTitle(anime),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.w800,
                      height: 1.25,
                    ),
                  ),
                  const SizedBox(height: AppSpacing.xs),
                  Row(
                    children: [
                      if (anime.format != null && anime.format!.isNotEmpty)
                        Flexible(
                          child: Text(
                            anime.format!,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: Theme.of(context).textTheme.labelSmall
                                ?.copyWith(color: colorScheme.primary),
                          ),
                        ),
                      if (anime.format != null &&
                          anime.format!.isNotEmpty &&
                          anime.airDate != null)
                        Text(
                          ' · ',
                          style: TextStyle(color: colorScheme.outline),
                        ),
                      if (anime.airDate != null)
                        Flexible(
                          child: Text(
                            anime.airDate!,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: Theme.of(context).textTheme.labelSmall
                                ?.copyWith(color: colorScheme.onSurfaceVariant),
                          ),
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

class _DiscoveryYearRange {
  final String startDate;
  final String endDate;

  const _DiscoveryYearRange(this.startDate, this.endDate);
}

enum _DiscoverySort { latest, score, title }

extension _DiscoverySortLabel on _DiscoverySort {
  String get label {
    switch (this) {
      case _DiscoverySort.latest:
        return '最新';
      case _DiscoverySort.score:
        return '评分';
      case _DiscoverySort.title:
        return '标题';
    }
  }

  String get serverKey {
    switch (this) {
      case _DiscoverySort.latest:
        return 'air_date';
      case _DiscoverySort.score:
        return 'score';
      case _DiscoverySort.title:
        return 'title';
    }
  }
}

class _DiscoveryFilters {
  final String? year;
  final int? seasonMonth;
  final String? format;
  final Set<String> regions;
  final Set<String> styles;
  final Set<String> adaptations;
  final _DiscoverySort sort;

  const _DiscoveryFilters({
    this.year,
    this.seasonMonth,
    this.format,
    this.regions = const <String>{},
    this.styles = const <String>{},
    this.adaptations = const <String>{},
    this.sort = _DiscoverySort.latest,
  });

  factory _DiscoveryFilters.defaults(int currentYear) {
    return _DiscoveryFilters(year: currentYear.toString());
  }

  String? get normalizedYear {
    if (year == null || year == '全部') return null;
    return year;
  }

  String? get serverYear {
    final value = normalizedYear;
    if (value == null) return null;
    return int.tryParse(value) == null ? null : value;
  }

  List<String> get tagFilters => [...regions, ...styles, ...adaptations];

  String? get primaryBangumiTag {
    if (tagFilters.isNotEmpty) return tagFilters.first;
    return format;
  }

  bool get hasServerOnlyConditions {
    return tagFilters.length > 1 || format != null || seasonMonth != null;
  }

  int get activeCount {
    return [
      if (normalizedYear != null) normalizedYear,
      if (seasonMonth != null) seasonMonth,
      if (format != null) format,
      ...tagFilters,
      if (sort != _DiscoverySort.latest) sort,
    ].length;
  }

  List<String> get activeLabels {
    return [
      ?normalizedYear,
      if (seasonMonth != null) '$seasonMonth月',
      ?format,
      ...tagFilters,
      if (sort != _DiscoverySort.latest) '按${sort.label}',
    ];
  }

  String get signature {
    final tags = [...tagFilters]..sort();
    return [
      normalizedYear ?? '',
      seasonMonth?.toString() ?? '',
      format ?? '',
      sort.name,
      ...tags,
    ].join('|');
  }

  _DiscoveryFilters copyWith({
    String? year,
    bool clearYear = false,
    int? seasonMonth,
    bool clearSeasonMonth = false,
    String? format,
    bool clearFormat = false,
    Set<String>? regions,
    Set<String>? styles,
    Set<String>? adaptations,
    _DiscoverySort? sort,
  }) {
    return _DiscoveryFilters(
      year: clearYear ? null : year ?? this.year,
      seasonMonth: clearSeasonMonth ? null : seasonMonth ?? this.seasonMonth,
      format: clearFormat ? null : format ?? this.format,
      regions: regions ?? this.regions,
      styles: styles ?? this.styles,
      adaptations: adaptations ?? this.adaptations,
      sort: sort ?? this.sort,
    );
  }

  _DiscoveryFilters toggleRegion(String value) {
    return copyWith(regions: _toggled(regions, value));
  }

  _DiscoveryFilters toggleStyle(String value) {
    return copyWith(styles: _toggled(styles, value));
  }

  _DiscoveryFilters toggleAdaptation(String value) {
    return copyWith(adaptations: _toggled(adaptations, value));
  }

  Set<String> _toggled(Set<String> source, String value) {
    final next = {...source};
    if (!next.add(value)) next.remove(value);
    return next;
  }
}

class _DiscoveryFilterSheet extends StatefulWidget {
  final _DiscoveryFilters initial;
  final List<String> years;
  final List<String> formats;
  final List<String> regions;
  final List<String> styles;
  final List<String> adaptations;

  const _DiscoveryFilterSheet({
    required this.initial,
    required this.years,
    required this.formats,
    required this.regions,
    required this.styles,
    required this.adaptations,
  });

  @override
  State<_DiscoveryFilterSheet> createState() => _DiscoveryFilterSheetState();
}

class _DiscoveryFilterSheetState extends State<_DiscoveryFilterSheet> {
  late _DiscoveryFilters _draft = widget.initial;

  static const Map<int, String> _seasonLabels = {
    1: '1月',
    4: '4月',
    7: '7月',
    10: '10月',
  };

  @override
  Widget build(BuildContext context) {
    return FractionallySizedBox(
      heightFactor: 0.88,
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 12),
            child: Row(
              children: [
                const Expanded(
                  child: Text(
                    '筛选番剧',
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900),
                  ),
                ),
                TextButton(
                  onPressed: () {
                    setState(() => _draft = const _DiscoveryFilters());
                  },
                  child: const Text('重置'),
                ),
              ],
            ),
          ),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
              children: [
                _buildSingleChoiceSection<String>(
                  title: '年份',
                  options: widget.years,
                  selected: _draft.year ?? '全部',
                  labelOf: (value) => value,
                  onSelected: (value) {
                    setState(() {
                      _draft = _draft.copyWith(
                        year: value == '全部' ? null : value,
                        clearYear: value == '全部',
                      );
                    });
                  },
                ),
                _buildSingleChoiceSection<int?>(
                  title: '季度',
                  options: const [null, 1, 4, 7, 10],
                  selected: _draft.seasonMonth,
                  labelOf: (value) =>
                      value == null ? '全部' : _seasonLabels[value] ?? '$value月',
                  onSelected: (value) {
                    setState(() {
                      _draft = _draft.copyWith(
                        seasonMonth: value,
                        clearSeasonMonth: value == null,
                      );
                    });
                  },
                ),
                _buildSingleChoiceSection<String?>(
                  title: '格式',
                  options: [null, ...widget.formats],
                  selected: _draft.format,
                  labelOf: (value) => value ?? '全部',
                  onSelected: (value) {
                    setState(() {
                      _draft = _draft.copyWith(
                        format: value,
                        clearFormat: value == null,
                      );
                    });
                  },
                ),
                _buildMultiChoiceSection(
                  title: '地区',
                  options: widget.regions,
                  selected: _draft.regions,
                  onToggle: (value) {
                    setState(() => _draft = _draft.toggleRegion(value));
                  },
                ),
                _buildMultiChoiceSection(
                  title: '风格',
                  options: widget.styles,
                  selected: _draft.styles,
                  onToggle: (value) {
                    setState(() => _draft = _draft.toggleStyle(value));
                  },
                ),
                _buildMultiChoiceSection(
                  title: '改编',
                  options: widget.adaptations,
                  selected: _draft.adaptations,
                  onToggle: (value) {
                    setState(() => _draft = _draft.toggleAdaptation(value));
                  },
                ),
                _buildSingleChoiceSection<_DiscoverySort>(
                  title: '排序',
                  options: _DiscoverySort.values,
                  selected: _draft.sort,
                  labelOf: (value) => value.label,
                  onSelected: (value) {
                    setState(() => _draft = _draft.copyWith(sort: value));
                  },
                ),
              ],
            ),
          ),
          const Divider(height: 1),
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 12, 20, 12),
              child: Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: () =>
                          Navigator.pop(context, const _DiscoveryFilters()),
                      child: const Text('清空条件'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: () => Navigator.pop(context, _draft),
                      icon: const Icon(Icons.check_rounded),
                      label: const Text('查看结果'),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSingleChoiceSection<T>({
    required String title,
    required List<T> options,
    required T selected,
    required String Function(T value) labelOf,
    required ValueChanged<T> onSelected,
  }) {
    return _FilterSection(
      title: title,
      child: Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          for (final option in options)
            ChoiceChip(
              label: Text(labelOf(option)),
              selected: option == selected,
              onSelected: (_) => onSelected(option),
              visualDensity: VisualDensity.compact,
            ),
        ],
      ),
    );
  }

  Widget _buildMultiChoiceSection({
    required String title,
    required List<String> options,
    required Set<String> selected,
    required ValueChanged<String> onToggle,
  }) {
    return _FilterSection(
      title: title,
      child: Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          for (final option in options)
            FilterChip(
              label: Text(option),
              selected: selected.contains(option),
              onSelected: (_) => onToggle(option),
              visualDensity: VisualDensity.compact,
            ),
        ],
      ),
    );
  }
}

class _FilterSection extends StatelessWidget {
  final String title;
  final Widget child;

  const _FilterSection({required this.title, required this.child});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w900),
          ),
          const SizedBox(height: 10),
          child,
        ],
      ),
    );
  }
}
