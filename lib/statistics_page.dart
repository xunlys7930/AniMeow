import 'package:provider/provider.dart';
import 'providers/data_refresh_provider.dart';
import 'package:flutter/material.dart';
import 'package:fl_chart/fl_chart.dart';
import 'db/database_helper.dart';
import 'filtered_anime_list_page.dart';
import 'tag_list_page.dart';

/// 数据统计页面，展示动漫观看状态和标签使用情况
class StatisticsPage extends StatefulWidget {
  const StatisticsPage({super.key});

  @override
  // 【重要】保持 State 为公有，以便 GlobalKey 访问
  State<StatisticsPage> createState() => StatisticsPageState();
}

// 【重要】去掉下划线，改为公有类
class StatisticsPageState extends State<StatisticsPage> {
  // 状态统计数据
  Map<String, int> _statusCounts = {'在看': 0, '看完': 0, '未看': 0, '弃坑': 0};
  // 标签使用统计
  List<Map<String, dynamic>> _tagCounts = [];
  // 【新增】所有定义的观看状态列表
  List<Map<String, dynamic>> _allStatuses = [];

  // 加载状态
  bool _isLoading = true;
  // 动漫总数
  int _totalAnimes = 0;
  // 【新增】分类统计
  int _animeCount = 0;
  int _bookCount = 0;

  // 被选中的饼图索引
  int _touchedIndex = -1;
  // 标签饼图被选中的索引
  int _tagTouchedIndex = -1;

  DataRefreshProvider? _refreshProvider;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _refreshProvider = Provider.of<DataRefreshProvider>(context, listen: false);
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _refreshProvider?.addListener(_onGlobalRefresh);
    });
    refreshData();
  }

  void _onGlobalRefresh() {
    if (mounted) refreshData();
  }

  @override
  void dispose() {
    _refreshProvider?.removeListener(_onGlobalRefresh);
    super.dispose();
  }

  /// 从数据库加载统计数据
  void refreshData() async {
    if (mounted) {
      setState(() => _isLoading = true); // 点击刷新时，先转圈
    }

    // 并行获取状态统计、标签统计及所有自定义状态
    final statusCounts = await DatabaseHelper().getStatusCounts();
    final tagData = await DatabaseHelper().getTagCounts();
    final allStatuses = await DatabaseHelper().getAllStatuses();
    final typeCounts = await DatabaseHelper().getSubjectTypeCounts();

    // 计算动漫总数
    int total = 0;
    statusCounts.forEach((key, value) => total += value);

    if (mounted) {
      setState(() {
        _statusCounts = statusCounts;
        _tagCounts = tagData;
        _allStatuses = allStatuses;
        _totalAnimes = total;
        _animeCount = typeCounts['anime'] ?? 0;
        _bookCount = typeCounts['book'] ?? 0;

        _isLoading = false; // 加载完毕，隐藏圈圈
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('数据统计'),
        // 【新增】添加刷新按钮
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: '刷新数据',
            onPressed: refreshData, // 点击调用刷新
          ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : SingleChildScrollView(
              padding: const EdgeInsets.all(16.0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // 1. 总览卡片
                  _buildSummaryCard(),
                  const SizedBox(height: 24),

                  // 2. 状态统计标题
                  const Text(
                    "追番状态分布",
                    style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                  ),
                  const SizedBox(height: 12),

                  // 状态饼图和图例
                  _buildStatusDistribution(),

                  const SizedBox(height: 24),

                  // 3. 标签分布标题 + 查看全部入口
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      const Text(
                        "标签分布",
                        style: TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      GestureDetector(
                        onTap: () {
                          Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (context) => const TagListPage(),
                            ),
                          );
                        },
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Text(
                              '查看全部',
                              style: TextStyle(
                                fontSize: 14,
                                color: Colors.grey[500],
                              ),
                            ),
                            Icon(
                              Icons.chevron_right,
                              size: 18,
                              color: Colors.grey[500],
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 12),

                  // 标签分布饼图
                  _buildTagDistribution(),
                ],
              ),
            ),
    );
  }

  /// 构建总览卡片
  Widget _buildSummaryCard() {
    return Card(
      elevation: 4,
      color: Colors.indigo,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16.0, vertical: 24.0),
        child: IntrinsicHeight(
          child: Row(
            mainAxisAlignment: MainAxisAlignment.spaceEvenly,
            children: [
              // 1. 全部
              _buildSummaryItem(
                "总收录",
                _totalAnimes,
                Icons.bar_chart,
                Colors.white,
              ),
              const VerticalDivider(
                color: Colors.white24,
                thickness: 1,
                indent: 8,
                endIndent: 8,
              ),
              // 2. 动画
              _buildSummaryItem(
                "番剧",
                _animeCount,
                Icons.movie_filter,
                Colors.amberAccent,
              ),
              const VerticalDivider(
                color: Colors.white24,
                thickness: 1,
                indent: 8,
                endIndent: 8,
              ),
              // 3. 小说
              _buildSummaryItem(
                "小说",
                _bookCount,
                Icons.menu_book,
                Colors.lightBlueAccent,
              ),
            ],
          ),
        ),
      ),
    );
  }

  /// 构建单个统计项
  Widget _buildSummaryItem(
    String label,
    int count,
    IconData icon,
    Color iconColor,
  ) {
    return Expanded(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, color: iconColor, size: 28),
          const SizedBox(height: 12),
          Text(
            label,
            style: const TextStyle(color: Colors.white70, fontSize: 12),
          ),
          const SizedBox(height: 4),
          Text(
            "$count",
            style: const TextStyle(
              color: Colors.white,
              fontSize: 22,
              fontWeight: FontWeight.bold,
            ),
          ),
        ],
      ),
    );
  }

  /// 构建状态分布展示（饼图 + 图例）
  Widget _buildStatusDistribution() {
    if (_allStatuses.isEmpty || _totalAnimes == 0) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.symmetric(vertical: 40),
          child: Text("暂无状态数据", style: TextStyle(color: Colors.grey)),
        ),
      );
    }

    return Card(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          children: [
            // 饼图
            SizedBox(
              height: 200,
              child: PieChart(
                PieChartData(
                  pieTouchData: PieTouchData(
                    touchCallback: (FlTouchEvent event, pieTouchResponse) {
                      setState(() {
                        if (!event.isInterestedForInteractions ||
                            pieTouchResponse == null ||
                            pieTouchResponse.touchedSection == null) {
                          _touchedIndex = -1;
                          return;
                        }
                        _touchedIndex = pieTouchResponse
                            .touchedSection!
                            .touchedSectionIndex;
                      });
                    },
                  ),
                  borderData: FlBorderData(show: false),
                  sectionsSpace: 4,
                  centerSpaceRadius: 50,
                  sections: _getSections(),
                ),
              ),
            ),
            const SizedBox(height: 16),
            // 图例 (改为灵活的流式布局)
            Wrap(
              spacing: 16,
              runSpacing: 12,
              alignment: WrapAlignment.center,
              children: _allStatuses.map((statusItem) {
                final name = statusItem['name'] as String;
                final color = Color(statusItem['color'] as int);
                final count = _statusCounts[name] ?? 0;

                return InkWell(
                  onTap: () {
                    Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (context) =>
                            FilteredAnimeListPage(status: name),
                      ),
                    );
                  },
                  borderRadius: BorderRadius.circular(8),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 4,
                      vertical: 2,
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Container(
                          width: 12,
                          height: 12,
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            color: color,
                          ),
                        ),
                        const SizedBox(width: 8),
                        Text(
                          "$name ($count)",
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w500,
                            color: Colors.grey[800],
                          ),
                        ),
                      ],
                    ),
                  ),
                );
              }).toList(),
            ),
          ],
        ),
      ),
    );
  }

  /// 获取饼图分块数据
  List<PieChartSectionData> _getSections() {
    return List.generate(_allStatuses.length, (i) {
      final isTouched = i == _touchedIndex;
      final statusItem = _allStatuses[i];
      final name = statusItem['name'] as String;
      final color = Color(statusItem['color'] as int);
      final count = _statusCounts[name] ?? 0;
      final double percent = (count / _totalAnimes) * 100;

      final fontSize = isTouched ? 16.0 : 12.0;
      final radius = isTouched ? 65.0 : 55.0;

      return PieChartSectionData(
        color: color,
        value: count.toDouble(),
        title: percent > 5 ? '${percent.toStringAsFixed(1)}%' : '',
        radius: radius,
        titleStyle: TextStyle(
          fontSize: fontSize,
          fontWeight: FontWeight.bold,
          color: Colors.white,
          shadows: [const Shadow(color: Colors.black26, blurRadius: 4)],
        ),
      );
    });
  }

  /// 标签饼图的颜色列表
  static const List<Color> _tagChartColors = [
    Color(0xFF5C6BC0), // Indigo
    Color(0xFF42A5F5), // Blue
    Color(0xFF26A69A), // Teal
    Color(0xFF66BB6A), // Green
    Color(0xFFFFCA28), // Amber
    Color(0xFFFF7043), // Deep Orange
    Color(0xFFAB47BC), // Purple
    Color(0xFFEF5350), // Red
    Color(0xFF78909C), // Blue Grey (用于"其他")
  ];

  /// 构建标签分布饼图
  Widget _buildTagDistribution() {
    if (_tagCounts.isEmpty) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.symmetric(vertical: 40),
          child: Text("暂无标签数据", style: TextStyle(color: Colors.grey)),
        ),
      );
    }

    // 取 Top 8 标签，其余归入"其他"
    const maxShown = 8;
    final sortedTags = List<Map<String, dynamic>>.from(_tagCounts);
    final topTags = sortedTags.take(maxShown).toList();
    final otherCount = sortedTags
        .skip(maxShown)
        .fold<int>(0, (sum, tag) => sum + (tag['count'] as int));
    final totalTagUsage = sortedTags.fold<int>(
      0,
      (sum, tag) => sum + (tag['count'] as int),
    );

    // 构建饼图数据
    final List<PieChartSectionData> sections = [];
    final List<_TagLegendItem> legendItems = [];

    for (int i = 0; i < topTags.length; i++) {
      final tag = topTags[i];
      final name = tag['name'] as String;
      final count = tag['count'] as int;
      final color = _tagChartColors[i % _tagChartColors.length];
      final isTouched = i == _tagTouchedIndex;
      final double percent = totalTagUsage > 0
          ? (count / totalTagUsage) * 100
          : 0;

      sections.add(
        PieChartSectionData(
          color: color,
          value: count.toDouble(),
          title: percent > 5 ? '${percent.toStringAsFixed(1)}%' : '',
          radius: isTouched ? 65.0 : 55.0,
          titleStyle: TextStyle(
            fontSize: isTouched ? 16.0 : 12.0,
            fontWeight: FontWeight.bold,
            color: Colors.white,
            shadows: [const Shadow(color: Colors.black26, blurRadius: 4)],
          ),
        ),
      );
      legendItems.add(
        _TagLegendItem(
          name: name,
          count: count,
          color: color,
          tagId: tag['id'] as int,
        ),
      );
    }

    // 添加"其他"
    if (otherCount > 0) {
      final otherIdx = topTags.length;
      final isTouched = otherIdx == _tagTouchedIndex;
      final double percent = totalTagUsage > 0
          ? (otherCount / totalTagUsage) * 100
          : 0;

      sections.add(
        PieChartSectionData(
          color: _tagChartColors.last,
          value: otherCount.toDouble(),
          title: percent > 5 ? '${percent.toStringAsFixed(1)}%' : '',
          radius: isTouched ? 65.0 : 55.0,
          titleStyle: TextStyle(
            fontSize: isTouched ? 16.0 : 12.0,
            fontWeight: FontWeight.bold,
            color: Colors.white,
            shadows: [const Shadow(color: Colors.black26, blurRadius: 4)],
          ),
        ),
      );
      legendItems.add(
        _TagLegendItem(
          name: '其他',
          count: otherCount,
          color: _tagChartColors.last,
          tagId: -1,
        ),
      );
    }

    return Card(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          children: [
            SizedBox(
              height: 200,
              child: PieChart(
                PieChartData(
                  pieTouchData: PieTouchData(
                    touchCallback: (FlTouchEvent event, pieTouchResponse) {
                      setState(() {
                        if (!event.isInterestedForInteractions ||
                            pieTouchResponse == null ||
                            pieTouchResponse.touchedSection == null) {
                          _tagTouchedIndex = -1;
                          return;
                        }
                        _tagTouchedIndex = pieTouchResponse
                            .touchedSection!
                            .touchedSectionIndex;
                      });
                    },
                  ),
                  borderData: FlBorderData(show: false),
                  sectionsSpace: 4,
                  centerSpaceRadius: 50,
                  sections: sections,
                ),
              ),
            ),
            const SizedBox(height: 16),
            Wrap(
              spacing: 16,
              runSpacing: 12,
              alignment: WrapAlignment.center,
              children: legendItems.map((item) {
                return InkWell(
                  onTap: item.tagId > 0
                      ? () {
                          Navigator.push(
                            context,
                            MaterialPageRoute(
                              builder: (context) => FilteredAnimeListPage(
                                tagId: item.tagId,
                                tagName: item.name,
                              ),
                            ),
                          );
                        }
                      : null,
                  borderRadius: BorderRadius.circular(8),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 4,
                      vertical: 2,
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Container(
                          width: 12,
                          height: 12,
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            color: item.color,
                          ),
                        ),
                        const SizedBox(width: 8),
                        Text(
                          '${item.name} (${item.count})',
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w500,
                            color: Colors.grey[800],
                          ),
                        ),
                      ],
                    ),
                  ),
                );
              }).toList(),
            ),
          ],
        ),
      ),
    );
  }
}

/// 标签图例数据模型
class _TagLegendItem {
  final String name;
  final int count;
  final Color color;
  final int tagId;

  const _TagLegendItem({
    required this.name,
    required this.count,
    required this.color,
    required this.tagId,
  });
}
