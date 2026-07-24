import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../db/database_helper.dart';
import '../../providers/data_refresh_provider.dart';
import '../components/adaptive_content_frame.dart';
import '../components/async_content_view.dart';
import '../components/empty_state.dart';
import '../customization/page_display_config.dart';
import '../design_tokens.dart';
import 'filtered_anime_list_page.dart';
import 'statistics/statistics_display_config.dart';
import 'statistics/statistics_display_sheet.dart';
import 'tag_list_page.dart';

/// 数据统计页面。
///
/// 页面内容、加载状态和显示配置彼此独立：数据库刷新不会清空旧内容，
/// 统计模块可以在页面内重新排序、隐藏并选择图表样式。
class StatisticsPage extends StatefulWidget {
  const StatisticsPage({super.key});

  @override
  State<StatisticsPage> createState() => StatisticsPageState();
}

class StatisticsPageState extends State<StatisticsPage> {
  final PageDisplayController _displayController = PageDisplayController(
    pageId: 'statistics',
    defaults: statisticsDefaults(),
    knownModules: statisticsModuleKeys,
    fallbackOrder: statisticsDefaultOrder,
  );

  Map<String, int> _statusCounts = const {};
  List<Map<String, dynamic>> _tagCounts = const [];
  List<Map<String, dynamic>> _allStatuses = const [];
  int _totalAnimes = 0;
  int _animeCount = 0;
  int _bookCount = 0;

  bool _isLoading = true;
  bool _hasLoadedData = false;
  String? _errorMessage;
  int _requestGeneration = 0;
  DataRefreshProvider? _refreshProvider;

  @override
  void initState() {
    super.initState();
    _displayController.load();
    refreshData();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final nextProvider = Provider.of<DataRefreshProvider>(
      context,
      listen: false,
    );
    if (_refreshProvider == nextProvider) return;
    _refreshProvider?.removeListener(_onGlobalRefresh);
    _refreshProvider = nextProvider;
    _refreshProvider?.addListener(_onGlobalRefresh);
  }

  void _onGlobalRefresh() {
    if (mounted) refreshData();
  }

  @override
  void dispose() {
    _refreshProvider?.removeListener(_onGlobalRefresh);
    _displayController.dispose();
    super.dispose();
  }

  Future<void> refreshData() async {
    final generation = ++_requestGeneration;
    if (mounted) {
      setState(() {
        _isLoading = true;
        if (!_hasLoadedData) _errorMessage = null;
      });
    }

    final db = DatabaseHelper();
    final statusFuture = db.getStatusCounts();
    final tagFuture = db.getTagCounts();
    final allStatusesFuture = db.getAllStatuses();
    final typeCountsFuture = db.getSubjectTypeCounts();

    try {
      final statusCounts = await statusFuture;
      final tagCounts = await tagFuture;
      final allStatuses = await allStatusesFuture;
      final typeCounts = await typeCountsFuture;

      if (!mounted || generation != _requestGeneration) return;

      var total = 0;
      for (final count in statusCounts.values) {
        total += count;
      }

      setState(() {
        _statusCounts = Map<String, int>.from(statusCounts);
        _tagCounts = List<Map<String, dynamic>>.from(tagCounts);
        _allStatuses = List<Map<String, dynamic>>.from(allStatuses);
        _totalAnimes = total;
        _animeCount = typeCounts['anime'] ?? 0;
        _bookCount = typeCounts['book'] ?? 0;
        _isLoading = false;
        _hasLoadedData = true;
        _errorMessage = null;
      });
    } catch (error) {
      if (!mounted || generation != _requestGeneration) return;
      setState(() {
        _isLoading = false;
        _errorMessage = _readableError(error);
      });
    }
  }

  Future<void> _openDisplaySettings() async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      constraints: MediaQuery.sizeOf(context).width >= AppBreakpoints.medium
          ? const BoxConstraints(maxWidth: 720)
          : null,
      builder: (_) => StatisticsDisplaySheet(controller: _displayController),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('数据统计'),
            Text(
              selectedStatisticsPreset(_displayController.value)?.label ??
                  '自定义视图',
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
        actions: [
          IconButton(
            tooltip: '定制统计页面',
            onPressed: _openDisplaySettings,
            icon: const Icon(Icons.dashboard_customize_outlined),
          ),
          IconButton(
            tooltip: '刷新数据',
            onPressed: () {
              refreshData();
            },
            icon: const Icon(Icons.refresh_rounded),
          ),
          const SizedBox(width: AppSpacing.sm),
        ],
      ),
      body: AnimatedBuilder(
        animation: _displayController,
        builder: (context, _) {
          return AsyncContentView(
            isLoading: _isLoading,
            hasData: _hasLoadedData,
            isEmpty: _isEmpty,
            errorMessage: _errorMessage,
            onRetry: () {
              refreshData();
            },
            loading: const _StatisticsLoadingState(),
            emptyIcon: Icons.analytics_outlined,
            emptyMessage: '还没有可统计的作品',
            emptyDescription: '添加番剧或小说后，这里会显示你的收藏与观看分布。',
            child: RefreshIndicator(
              onRefresh: refreshData,
              child: SingleChildScrollView(
                physics: const AlwaysScrollableScrollPhysics(),
                child: AdaptiveContentFrame(
                  maxContentWidth: 1200,
                  child: _buildDashboard(_displayController.value),
                ),
              ),
            ),
          );
        },
      ),
    );
  }

  bool get _isEmpty => _totalAnimes == 0 && _tagCounts.isEmpty;

  Widget _buildDashboard(PageDisplayConfig config) {
    final visibleModules = config.moduleOrder
        .where((key) => !config.hiddenModules.contains(key))
        .map(StatisticsModule.fromPersistKey)
        .whereType<StatisticsModule>()
        .toList(growable: false);

    if (visibleModules.isEmpty) {
      return EmptyStateWidget(
        icon: Icons.visibility_off_outlined,
        message: '所有统计模块都已隐藏',
        description: '恢复推荐设置后，可以继续调整模块顺序和显隐。',
        buttonText: '恢复推荐设置',
        onButtonPressed: () {
          _displayController.reset();
        },
      );
    }

    return LayoutBuilder(
      builder: (context, constraints) {
        final gap = _moduleGap(config.density);
        final contentWidth = constraints.maxWidth;
        final chartModules = visibleModules
            .where((module) => module != StatisticsModule.overview)
            .length;
        final useTwoColumns =
            contentWidth >= AppBreakpoints.medium && chartModules > 1;
        final halfWidth = (contentWidth - gap) / 2;

        return Wrap(
          spacing: gap,
          runSpacing: gap,
          children: visibleModules.map((module) {
            final fullWidth =
                module == StatisticsModule.overview || !useTwoColumns;
            return SizedBox(
              width: fullWidth ? contentWidth : halfWidth,
              child: _buildModule(module, config),
            );
          }).toList(),
        );
      },
    );
  }

  Widget _buildModule(StatisticsModule module, PageDisplayConfig config) {
    switch (module) {
      case StatisticsModule.overview:
        return _buildOverviewModule(config);
      case StatisticsModule.status:
        return _buildStatusModule(config);
      case StatisticsModule.tags:
        return _buildTagsModule(config);
    }
  }

  Widget _buildOverviewModule(PageDisplayConfig config) {
    final colorScheme = Theme.of(context).colorScheme;
    final tileData = [
      (
        label: '总收录',
        value: _totalAnimes,
        icon: Icons.library_books_outlined,
        color: colorScheme.onPrimaryContainer,
      ),
      (
        label: '番剧',
        value: _animeCount,
        icon: Icons.movie_filter_outlined,
        color: colorScheme.onSecondaryContainer,
      ),
      (
        label: '小说 / 漫画',
        value: _bookCount,
        icon: Icons.menu_book_outlined,
        color: colorScheme.onTertiaryContainer,
      ),
    ];

    return Card(
      margin: EdgeInsets.zero,
      clipBehavior: Clip.antiAlias,
      child: DecoratedBox(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            colors: [
              colorScheme.primaryContainer,
              colorScheme.secondaryContainer,
            ],
          ),
        ),
        child: Padding(
          padding: EdgeInsets.all(_cardPadding(config.density)),
          child: LayoutBuilder(
            builder: (context, constraints) {
              final compact = constraints.maxWidth < 520;
              final children = tileData
                  .map(
                    (item) => _SummaryTile(
                      label: item.label,
                      value: item.value,
                      icon: item.icon,
                      color: item.color,
                    ),
                  )
                  .toList();
              return Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '收藏概览',
                    style: Theme.of(context).textTheme.titleLarge?.copyWith(
                      fontWeight: FontWeight.w900,
                      color: colorScheme.onPrimaryContainer,
                    ),
                  ),
                  const SizedBox(height: AppSpacing.lg),
                  if (compact)
                    Wrap(
                      spacing: AppSpacing.sm,
                      runSpacing: AppSpacing.sm,
                      children: children
                          .map(
                            (child) => SizedBox(
                              width: (constraints.maxWidth - AppSpacing.sm) / 2,
                              child: child,
                            ),
                          )
                          .toList(),
                    )
                  else
                    Row(
                      children: [
                        for (
                          var index = 0;
                          index < children.length;
                          index++
                        ) ...[
                          if (index > 0)
                            Container(
                              width: 1,
                              height: 64,
                              color: colorScheme.onPrimaryContainer.withValues(
                                alpha: 0.16,
                              ),
                            ),
                          Expanded(child: children[index]),
                        ],
                      ],
                    ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }

  Widget _buildStatusModule(PageDisplayConfig config) {
    final items = _statusItems();
    return _DashboardModuleCard(
      title: '追番状态分布',
      subtitle: '点击任一状态查看对应作品',
      icon: Icons.donut_small_outlined,
      padding: _cardPadding(config.density),
      child: items.isEmpty
          ? const _ModuleEmptyState(message: '暂无状态数据')
          : statisticsStatusChart(config) == StatisticsChartStyle.donut
          ? _buildDonutDistribution(items, isStatus: true)
          : _buildRankedDistribution(items, isStatus: true),
    );
  }

  Widget _buildTagsModule(PageDisplayConfig config) {
    final items = _tagItems();
    return _DashboardModuleCard(
      title: '标签分布',
      subtitle: '最常使用的标签',
      icon: Icons.sell_outlined,
      padding: _cardPadding(config.density),
      action: TextButton.icon(
        onPressed: () {
          Navigator.push(
            context,
            MaterialPageRoute(builder: (_) => const TagListPage()),
          );
        },
        icon: const Icon(Icons.open_in_new_rounded, size: 16),
        label: const Text('查看全部'),
      ),
      child: items.isEmpty
          ? const _ModuleEmptyState(message: '暂无标签数据')
          : statisticsTagChart(config) == StatisticsChartStyle.donut
          ? _buildDonutDistribution(items, isStatus: false)
          : _buildRankedDistribution(items, isStatus: false),
    );
  }

  Widget _buildDonutDistribution(
    List<_DistributionItem> items, {
    required bool isStatus,
  }) {
    final total = items.fold<int>(0, (sum, item) => sum + item.count);
    final chartHeight = isStatus ? 210.0 : 220.0;
    return Column(
      children: [
        SizedBox(
          height: chartHeight,
          child: PieChart(
            PieChartData(
              centerSpaceRadius: 48,
              sectionsSpace: 3,
              borderData: FlBorderData(show: false),
              sections: items.asMap().entries.map((entry) {
                final item = entry.value;
                final percent = total == 0 ? 0.0 : item.count / total * 100;
                return PieChartSectionData(
                  value: item.count.toDouble(),
                  color: item.color,
                  radius: 58,
                  title: percent >= 6 ? '${percent.toStringAsFixed(0)}%' : '',
                  titleStyle: const TextStyle(
                    color: Colors.white,
                    fontSize: 12,
                    fontWeight: FontWeight.w800,
                  ),
                );
              }).toList(),
            ),
          ),
        ),
        const SizedBox(height: AppSpacing.md),
        _buildLegend(items, isStatus: isStatus),
      ],
    );
  }

  Widget _buildRankedDistribution(
    List<_DistributionItem> items, {
    required bool isStatus,
  }) {
    final maxCount = items.fold<int>(0, (max, item) {
      return item.count > max ? item.count : max;
    });
    return Column(
      children: items.map((item) {
        final ratio = maxCount == 0 ? 0.0 : item.count / maxCount;
        return Padding(
          padding: const EdgeInsets.only(bottom: AppSpacing.md),
          child: InkWell(
            borderRadius: BorderRadius.circular(AppRadius.xs),
            onTap: () => _openDistribution(item, isStatus: isStatus),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: AppSpacing.xs),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          item.label,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(fontWeight: FontWeight.w700),
                        ),
                      ),
                      Text(
                        '${item.count}',
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.onSurfaceVariant,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: AppSpacing.xs),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(AppRadius.full),
                    child: LinearProgressIndicator(
                      value: ratio,
                      minHeight: 9,
                      backgroundColor: Theme.of(
                        context,
                      ).colorScheme.surfaceContainerHighest,
                      valueColor: AlwaysStoppedAnimation(item.color),
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      }).toList(),
    );
  }

  Widget _buildLegend(List<_DistributionItem> items, {required bool isStatus}) {
    return Column(
      children: items.map((item) {
        return InkWell(
          borderRadius: BorderRadius.circular(AppRadius.xs),
          onTap: () => _openDistribution(item, isStatus: isStatus),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: AppSpacing.xs),
            child: Row(
              children: [
                Container(
                  width: 10,
                  height: 10,
                  decoration: BoxDecoration(
                    color: item.color,
                    shape: BoxShape.circle,
                  ),
                ),
                const SizedBox(width: AppSpacing.sm),
                Expanded(
                  child: Text(
                    item.label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
                Text(
                  '${item.count}',
                  style: TextStyle(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(width: AppSpacing.xs),
                Icon(
                  Icons.chevron_right_rounded,
                  size: 18,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ],
            ),
          ),
        );
      }).toList(),
    );
  }

  void _openDistribution(_DistributionItem item, {required bool isStatus}) {
    final itemId = item.id;
    if (!isStatus && itemId == null) return;
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (_) => isStatus
            ? FilteredAnimeListPage(status: item.label)
            : FilteredAnimeListPage(tagId: itemId, tagName: item.label),
      ),
    );
  }

  List<_DistributionItem> _statusItems() {
    final result = <_DistributionItem>[];
    final seen = <String>{};
    for (var index = 0; index < _allStatuses.length; index++) {
      final item = _allStatuses[index];
      final name = item['name']?.toString().trim() ?? '';
      if (name.isEmpty || !seen.add(name)) continue;
      final rawColor = item['color'];
      final color = rawColor is int
          ? Color(rawColor)
          : _statusPalette[index % _statusPalette.length];
      result.add(
        _DistributionItem(
          label: name,
          count: _statusCounts[name] ?? 0,
          color: color,
          id: null,
        ),
      );
    }
    for (final entry in _statusCounts.entries) {
      if (entry.key.trim().isEmpty || !seen.add(entry.key)) continue;
      result.add(
        _DistributionItem(
          label: entry.key,
          count: entry.value,
          color: _statusPalette[result.length % _statusPalette.length],
          id: null,
        ),
      );
    }
    return result;
  }

  List<_DistributionItem> _tagItems() {
    final sorted =
        _tagCounts
            .map((item) {
              final count = _asInt(item['count']);
              final name = item['name']?.toString().trim() ?? '';
              final id = _asInt(item['id']);
              if (count == null || count <= 0 || name.isEmpty) return null;
              return (name: name, count: count, id: id);
            })
            .whereType<({String name, int count, int? id})>()
            .toList()
          ..sort((a, b) => b.count.compareTo(a.count));

    const maxShown = 8;
    final result = <_DistributionItem>[];
    for (var index = 0; index < sorted.length && index < maxShown; index++) {
      final item = sorted[index];
      result.add(
        _DistributionItem(
          label: item.name,
          count: item.count,
          color: _tagPalette[index % _tagPalette.length],
          id: item.id,
        ),
      );
    }
    final otherCount = sorted
        .skip(maxShown)
        .fold<int>(0, (sum, item) => sum + item.count);
    if (otherCount > 0) {
      result.add(
        _DistributionItem(
          label: '其他',
          count: otherCount,
          color: _tagPalette.last,
          id: null,
        ),
      );
    }
    return result;
  }

  static int? _asInt(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '');
  }

  static double _moduleGap(DisplayDensity density) {
    switch (density) {
      case DisplayDensity.compact:
        return AppSpacing.md;
      case DisplayDensity.comfortable:
        return AppSpacing.lg;
      case DisplayDensity.relaxed:
        return AppSpacing.xl;
    }
  }

  static double _cardPadding(DisplayDensity density) {
    switch (density) {
      case DisplayDensity.compact:
        return AppSpacing.md;
      case DisplayDensity.comfortable:
        return AppSpacing.lg;
      case DisplayDensity.relaxed:
        return AppSpacing.xl;
    }
  }

  static String _readableError(Object error) {
    final text = error.toString().replaceFirst('Exception: ', '').trim();
    return text.isEmpty ? '统计数据加载失败，请稍后重试。' : text;
  }

  static const _statusPalette = <Color>[
    Color(0xFF5C6BC0),
    Color(0xFF26A69A),
    Color(0xFFFFA726),
    Color(0xFFEF5350),
    Color(0xFF7E57C2),
    Color(0xFF42A5F5),
  ];

  static const _tagPalette = <Color>[
    Color(0xFF5C6BC0),
    Color(0xFF42A5F5),
    Color(0xFF26A69A),
    Color(0xFF66BB6A),
    Color(0xFFFFCA28),
    Color(0xFFFF7043),
    Color(0xFFAB47BC),
    Color(0xFFEF5350),
    Color(0xFF78909C),
  ];
}

class _StatisticsLoadingState extends StatelessWidget {
  const _StatisticsLoadingState();

  @override
  Widget build(BuildContext context) {
    return ListView(
      physics: const AlwaysScrollableScrollPhysics(),
      children: [
        AdaptiveContentFrame(
          maxContentWidth: 1200,
          child: Column(
            children: [
              _LoadingBlock(height: 160),
              const SizedBox(height: AppSpacing.lg),
              Row(
                children: const [
                  Expanded(child: _LoadingBlock(height: 300)),
                  SizedBox(width: AppSpacing.lg),
                  Expanded(child: _LoadingBlock(height: 300)),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _LoadingBlock extends StatelessWidget {
  final double height;

  const _LoadingBlock({required this.height});

  @override
  Widget build(BuildContext context) {
    return Container(
      height: height,
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(AppRadius.md),
      ),
    );
  }
}

class _SummaryTile extends StatelessWidget {
  final String label;
  final int value;
  final IconData icon;
  final Color color;

  const _SummaryTile({
    required this.label,
    required this.value,
    required this.icon,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.xs),
      child: Row(
        children: [
          Icon(icon, color: color, size: 28),
          const SizedBox(width: AppSpacing.md),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                label,
                style: TextStyle(
                  color: color.withValues(alpha: 0.78),
                  fontSize: 12,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                '$value',
                style: TextStyle(
                  color: color,
                  fontSize: 24,
                  fontWeight: FontWeight.w900,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _DashboardModuleCard extends StatelessWidget {
  final String title;
  final String? subtitle;
  final IconData icon;
  final double padding;
  final Widget child;
  final Widget? action;

  const _DashboardModuleCard({
    required this.title,
    required this.icon,
    required this.padding,
    required this.child,
    this.subtitle,
    this.action,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Card(
      margin: EdgeInsets.zero,
      elevation: 0,
      color: colorScheme.surfaceContainerLow,
      child: Padding(
        padding: EdgeInsets.all(padding),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(icon, color: colorScheme.primary),
                const SizedBox(width: AppSpacing.sm),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        title,
                        style: Theme.of(context).textTheme.titleMedium
                            ?.copyWith(fontWeight: FontWeight.w900),
                      ),
                      if (subtitle != null)
                        Text(
                          subtitle!,
                          style: Theme.of(context).textTheme.bodySmall
                              ?.copyWith(color: colorScheme.onSurfaceVariant),
                        ),
                    ],
                  ),
                ),
                ?action,
              ],
            ),
            const SizedBox(height: AppSpacing.lg),
            child,
          ],
        ),
      ),
    );
  }
}

class _ModuleEmptyState extends StatelessWidget {
  final String message;

  const _ModuleEmptyState({required this.message});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 160,
      child: Center(
        child: Text(
          message,
          style: TextStyle(
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
      ),
    );
  }
}

class _DistributionItem {
  final String label;
  final int count;
  final Color color;
  final int? id;

  const _DistributionItem({
    required this.label,
    required this.count,
    required this.color,
    required this.id,
  });
}
