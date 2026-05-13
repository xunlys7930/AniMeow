import 'package:provider/provider.dart';
import 'providers/data_refresh_provider.dart';
import 'dart:io';
import 'dart:async';
import 'package:path_provider/path_provider.dart';
import 'dart:ui';
import 'package:flutter/material.dart';
import 'api/bangumi_service.dart';
import 'api/anilist_service.dart';
import 'db/database_helper.dart';
import 'utils/logger.dart';
import 'settings_manager.dart';
import 'add_anime_page.dart';
import 'ui/anime_detail/anime_detail_page.dart';
import 'ui/series_detail_page.dart';
import 'ui/components/empty_state.dart';
import 'package:lpinyin/lpinyin.dart';
import 'ui/views/home_layout.dart';

class AnimeListPage extends StatefulWidget {
  final VoidCallback onSettingsChanged;
  const AnimeListPage({super.key, required this.onSettingsChanged});

  @override
  // 【重要】State 类名必须为 Public (去掉下划线)，以便 GlobalKey 引用
  State<AnimeListPage> createState() => AnimeListPageState();
}

// 【重要】State 类名改为 Public
class AnimeListPageState extends State<AnimeListPage> {
  Directory? _appDocDir; // 新增：保存 App 文档目录

  // --- 数据源 ---
  List<Map<String, dynamic>> _allAnimesData = []; // 全量数据
  List<Map<String, dynamic>> _animes = []; // 当前显示的分页数据
  List<Map<String, dynamic>> _allTags = [];

  // --- 分页 ---
  static const int _pageSize = 250;
  bool _hasMoreData = false;
  bool _isLoadingMore = false;
  final ScrollController _gridScrollController = ScrollController();
  final ScrollController _cardScrollController = ScrollController();

  // --- 筛选与搜索 ---
  final Set<int> _selectedTagIds = {};
  bool _isAndMode = false;
  String _selectedStatus = '全部';
  String _selectedType = 'all'; // 新增：当前选中的作品类型 (all, anime, book)
  final Set<String> _selectedYears = {}; // 新增：当前选中的年份 (Set 支持多选)
  List<String> _availableYears = []; // 新增：可用的年份列表
  final TextEditingController _searchController = TextEditingController();
  final FocusNode _searchFocusNode = FocusNode();
  Timer? _debounce;
  List<String> _statusOptions = ['全部', '未看', '在看', '看完', '弃坑'];
  Map<String, Color> _statusColors = {};

  // --- 布局与排序 ---
  final Map<String, String> _sortDefinitions = {
    '默认 (最新添加)': 'a.id',
    '播出日期': 'a.air_date',
    '我看完的时间': 'a.watch_finish_date',
    '评分': 'a.rating',
    '拼音': 'a.name',
  };
  // 记录每个键对应的当前排序方向 (DESC 或 ASC)
  final Map<String, String> _sortDirections = {
    '默认 (最新添加)': 'DESC',
    '播出日期': 'DESC',
    '我看完的时间': 'DESC',
    '评分': 'DESC',
    '拼音': 'ASC',
  };
  List<String> _selectedSortKeys = ['默认 (最新添加)'];

  // --- 批量操作 ---
  bool _isSelectionMode = false;
  final Set<int> _selectedAnimeIds = {};

  // --- 搜索框展开状态 ---
  bool _isSearchExpanded = false;

  DataRefreshProvider? _refreshProvider;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _refreshProvider = Provider.of<DataRefreshProvider>(context, listen: false);
  }

  // --- Lifecycle Methods ---
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _refreshProvider?.addListener(_onGlobalRefresh);
    });
    _initAppDir(); // 新增：初始化目录
    // 应用默认启动状态逻辑
    final defaultStart = SettingsManager().defaultStartStatusNotifier.value;
    if (defaultStart == '上次退出前') {
      _selectedStatus = SettingsManager().lastSelectedStatusNotifier.value;
    } else {
      _selectedStatus = defaultStart;
    }
    refreshData();
    _searchController.addListener(_onSearchChanged);
    _searchFocusNode.addListener(() {
      if (!_searchFocusNode.hasFocus && _searchController.text.isEmpty) {
        if (mounted) {
          setState(() {
            _isSearchExpanded = false;
          });
        }
      }
    });
    _gridScrollController.addListener(_onGridScroll);
    _cardScrollController.addListener(_onGridScroll);
  }

  // 新增：初始化获取 App 目录
  Future<void> _initAppDir() async {
    final dir = await getApplicationDocumentsDirectory();
    if (mounted) {
      setState(() {
        _appDocDir = dir;
      });
    }
  }

  void _onGlobalRefresh() {
    if (mounted) refreshData();
  }

  @override
  void dispose() {
    _refreshProvider?.removeListener(_onGlobalRefresh);

    _searchController.dispose();
    _searchFocusNode.dispose();
    _debounce?.cancel();
    _gridScrollController.dispose();
    _cardScrollController.dispose();
    super.dispose();
  }

  /// 监听滚动位置，接近底部时加载更多
  void _onGridScroll() {
    ScrollController? active;
    if (_gridScrollController.hasClients) {
      active = _gridScrollController;
    } else if (_cardScrollController.hasClients) {
      active = _cardScrollController;
    }
    if (active == null) return;
    if (active.position.pixels >= active.position.maxScrollExtent - 800) {
      _loadMoreItems();
    }
  }

  /// 加载更多分页数据
  void _loadMoreItems() {
    if (_isLoadingMore || !_hasMoreData) return;
    _isLoadingMore = true;
    final currentLen = _animes.length;
    final nextEnd = (currentLen + _pageSize).clamp(0, _allAnimesData.length);
    if (nextEnd > currentLen) {
      setState(() {
        _animes = _allAnimesData.sublist(0, nextEnd);
        _hasMoreData = nextEnd < _allAnimesData.length;
      });
    }
    _isLoadingMore = false;
  }

  void _onSearchChanged() {
    if (_debounce?.isActive ?? false) _debounce!.cancel();
    _debounce = Timer(const Duration(milliseconds: 500), () => refreshData());
  }

  // 【重要】方法名改为 Public (去掉下划线)，供外部调用
  void refreshData() async {
    // 0. 加载自定义状态
    final dbStatuses = await DatabaseHelper().getAllStatuses();
    if (dbStatuses.isNotEmpty) {
      _statusOptions = ['全部', ...dbStatuses.map((e) => e['name'] as String)];
      _statusColors = {
        for (var s in dbStatuses) s['name'] as String: Color(s['color'] as int),
      };
    } else {
      // Fallback
      _statusColors = {
        '未看': Colors.orange,
        '在看': Colors.blue,
        '看完': Colors.green,
        '弃坑': Colors.red,
      };
    }

    final tags = await DatabaseHelper().getAllTags();

    String sortSql;
    if (_selectedSortKeys.isEmpty) {
      sortSql = 'a.id DESC';
    } else {
      // 过滤掉不支持直接 SQL 排序的字段（如拼音）
      List<String> sqlParts = _selectedSortKeys.where((key) => key != '拼音').map(
        (key) {
          String field = _sortDefinitions[key]!;
          String dir = _sortDirections[key] ?? 'DESC';
          return '$field $dir';
        },
      ).toList();
      if (!sortSqlContainsId(sqlParts)) sqlParts.add('a.id DESC');
      sortSql = sqlParts.join(', ');
    }

    final animes = await DatabaseHelper().searchAnimes(
      query: _searchController.text.trim(),
      tagIds: _selectedTagIds.toList(),
      status: _selectedStatus,
      subjectType: _selectedType,
      years: _selectedYears.toList(),
      isAndMode: _isAndMode,
      sortOption: sortSql,
    );

    // 提取不重复的年份用于显示（基于原始所有番剧数据）
    final allAnimes = await DatabaseHelper().queryAllAnimes();
    final years = allAnimes
        .map((a) {
          final airDate = (a['air_date'] as String?);
          if (airDate != null && airDate.length >= 4) {
            return airDate.substring(0, 4);
          }
          return null;
        })
        .where((y) => y != null)
        .cast<String>()
        .toSet()
        .toList();
    years.sort((a, b) => b.compareTo(a)); // 倒序排

    // 【新增】系列逻辑
    // 只有在 "所有" 状态且无复杂筛选（关键词、标签、年份）时才显示系列聚合
    final String query = _searchController.text.trim();
    final bool isSearching = query.isNotEmpty;
    // Default Mode: Status=All, Query=Empty, Tags=Empty, Year=All
    final bool showSeries =
        _selectedStatus == '全部' &&
        _selectedType == 'all' &&
        query.isEmpty &&
        _selectedTagIds.isEmpty &&
        (_selectedYears.isEmpty);

    List<Map<String, dynamic>> finalDisplayList = [];

    if (showSeries || isSearching) {
      // 1. 获取需要的系列
      List<Map<String, dynamic>> targetSeries = [];
      if (isSearching) {
        targetSeries = await DatabaseHelper().searchSeries(query);
      } else {
        targetSeries = await DatabaseHelper().getAllSeries();
      }

      // 2. 构造 Series Items
      List<Map<String, dynamic>> seriesItems = [];
      for (var s in targetSeries) {
        final seriesId = s['id'];
        final inSeries = await DatabaseHelper().getAnimesInSeries(seriesId);

        if (inSeries.isNotEmpty) {
          Map<String, dynamic> seriesItem = Map.from(s);
          seriesItem['type'] = 'series';
          seriesItem['anime_count'] = inSeries.length;
          seriesItem['cover_url'] =
              s['custom_cover_url'] ?? inSeries.last['cover_url'];

          // 提取用于排序的聚合数据 (通常取最新播出的或评分最高的)
          seriesItem['max_id'] = inSeries
              .map((a) => a['id'] as int)
              .reduce((a, b) => a > b ? a : b);
          seriesItem['max_air_date'] = _getExtremeDate(
            inSeries,
            'air_date',
            true,
          );
          seriesItem['min_air_date'] = _getExtremeDate(
            inSeries,
            'air_date',
            false,
          );
          seriesItem['max_finish_date'] = _getExtremeDate(
            inSeries,
            'watch_finish_date',
            true,
          );
          seriesItem['max_rating'] = inSeries
              .map((a) => (a['rating'] as num?) ?? 0)
              .reduce((a, b) => a > b ? a : b);
          seriesItem['max_episodes'] = inSeries
              .map((a) => (a['total_episodes'] as num?) ?? 0)
              .reduce((a, b) => a > b ? a : b);

          seriesItems.add(seriesItem);
        } else if (isSearching) {
          Map<String, dynamic> seriesItem = Map.from(s);
          seriesItem['type'] = 'series';
          seriesItem['anime_count'] = 0;
          seriesItem['max_id'] = 0;
          seriesItems.add(seriesItem);
        }
      }

      // 3. 构造 Anime Items
      List<Map<String, dynamic>> animeItems = [];
      Iterable<Map<String, dynamic>> filteredAnimes = isSearching
          ? animes
          : animes.where((a) => a['series_id'] == null);

      animeItems = filteredAnimes.map((a) {
        Map<String, dynamic> item = Map.from(a);
        item['type'] = 'anime';
        // 统一排序属性名
        item['max_id'] = a['id'];
        item['max_air_date'] = a['air_date'];
        item['min_air_date'] = a['air_date'];
        item['max_finish_date'] = a['watch_finish_date'];
        item['max_rating'] = a['rating'] ?? 0;
        item['max_episodes'] = a['total_episodes'] ?? 0;
        return item;
      }).toList();

      finalDisplayList = [...seriesItems, ...animeItems];

      // 4. 重构混合排序逻辑
      final sortKey = _selectedSortKeys.isNotEmpty
          ? _selectedSortKeys.first
          : '默认 (最新添加)';
      final direction = _sortDirections[sortKey] ?? 'DESC';

      finalDisplayList.sort((a, b) {
        int cmp = 0;
        if (sortKey == '播出日期') {
          if (direction == 'DESC') {
            cmp = _compareDates(b['max_air_date'], a['max_air_date']);
          } else {
            cmp = _compareDates(a['min_air_date'], b['min_air_date']);
          }
        } else if (sortKey == '我看完的时间') {
          cmp = (direction == 'DESC')
              ? _compareDates(b['max_finish_date'], a['max_finish_date'])
              : _compareDates(a['max_finish_date'], b['max_finish_date']);
        } else if (sortKey == '评分') {
          num valA = (a['max_rating'] as num?) ?? 0;
          num valB = (b['max_rating'] as num?) ?? 0;
          cmp = (direction == 'DESC')
              ? valB.compareTo(valA)
              : valA.compareTo(valB);
        } else if (sortKey == '拼音') {
          String nameA = (a['name'] ?? '').toString();
          String nameB = (b['name'] ?? '').toString();
          String pinyinA = PinyinHelper.getShortPinyin(nameA).toLowerCase();
          String pinyinB = PinyinHelper.getShortPinyin(nameB).toLowerCase();
          cmp = (direction == 'ASC')
              ? pinyinA.compareTo(pinyinB)
              : pinyinB.compareTo(pinyinA);
        }

        // 如果主要排位相同，则用 max_id 兜底
        if (cmp == 0) {
          int idA = a['max_id'] ?? 0;
          int idB = b['max_id'] ?? 0;
          cmp = (direction == 'DESC') ? idB.compareTo(idA) : idA.compareTo(idB);
        }
        return cmp;
      });
    } else {
      // 筛选模式 (只显示番剧)
      finalDisplayList = animes.map((a) {
        Map<String, dynamic> item = Map.from(a);
        item['type'] = 'anime';
        return item;
      }).toList();

      // 【补全】筛选模式下的拼音内存排序
      final sortKey = _selectedSortKeys.isNotEmpty
          ? _selectedSortKeys.first
          : '默认 (最新添加)';
      if (sortKey == '拼音') {
        final direction = _sortDirections[sortKey] ?? 'ASC';
        finalDisplayList.sort((a, b) {
          String nameA = (a['name'] ?? '').toString();
          String nameB = (b['name'] ?? '').toString();
          String pinyinA = PinyinHelper.getShortPinyin(nameA).toLowerCase();
          String pinyinB = PinyinHelper.getShortPinyin(nameB).toLowerCase();
          int cmp = (direction == 'ASC')
              ? pinyinA.compareTo(pinyinB)
              : pinyinB.compareTo(pinyinA);
          if (cmp == 0) {
            int idA = a['id'] ?? 0;
            int idB = b['id'] ?? 0;
            cmp = idB.compareTo(idA);
          }
          return cmp;
        });
      }
    }

    if (mounted) {
      setState(() {
        _allTags = tags;
        _allAnimesData = finalDisplayList;
        // 分页：只显示前 _pageSize 条
        _animes = finalDisplayList.length > _pageSize
            ? finalDisplayList.sublist(0, _pageSize)
            : List.from(finalDisplayList);
        _hasMoreData = finalDisplayList.length > _pageSize;
        _availableYears = years;
      });
      // 滚动回顶部
      if (_gridScrollController.hasClients) {
        _gridScrollController.jumpTo(0);
      }
      if (_cardScrollController.hasClients) {
        _cardScrollController.jumpTo(0);
      }
    }
  }

  bool sortSqlContainsId(List<String> parts) {
    for (var part in parts) {
      if (part.contains('a.id')) return true;
    }
    return false;
  }

  // --- 辅助排序方法 ---
  String? _getExtremeDate(
    List<Map<String, dynamic>> items,
    String field,
    bool max,
  ) {
    List<String> dates = items
        .map((e) => e[field] as String?)
        .where((d) => d != null && d.isNotEmpty)
        .cast<String>()
        .toList();
    if (dates.isEmpty) return null;
    dates.sort();
    return max ? dates.last : dates.first;
  }

  int _compareDates(dynamic a, dynamic b) {
    if (a == null && b == null) return 0;
    if (a == null) return 1;
    if (b == null) return -1;
    return a.toString().compareTo(b.toString());
  }

  void _toggleSelectionMode(bool enable) {
    setState(() {
      _isSelectionMode = enable;
      _selectedAnimeIds.clear();
    });
  }

  void _toggleItemSelection(int id) {
    setState(() {
      if (_selectedAnimeIds.contains(id)) {
        _selectedAnimeIds.remove(id);
      } else {
        _selectedAnimeIds.add(id);
      }
    });
  }

  void _selectAll() {
    setState(() {
      if (_selectedAnimeIds.length == _animes.length) {
        _selectedAnimeIds.clear();
      } else {
        _selectedAnimeIds.clear();
        for (var anime in _animes) {
          _selectedAnimeIds.add(anime['id']);
        }
      }
    });
  }

  /// 【新增】供外部调用的标签过滤方法
  void filterByTag(int tagId) {
    setState(() {
      _selectedTagIds.clear();
      _selectedTagIds.add(tagId);
      // 清除其他可能的干扰筛选
      _searchController.clear();
      _selectedStatus = '全部';
      _selectedYears.clear();
    });
    refreshData();
  }

  /// 【新增】供外部调用的状态过滤方法
  void filterByStatus(String status) {
    setState(() {
      _selectedStatus = status;
      // 清除其他可能干扰的筛选
      _searchController.clear();
      _selectedTagIds.clear();
      _selectedYears.clear();
    });
    // 记忆状态
    SettingsManager().setLastSelectedStatus(status);
    refreshData();
  }

  void _handleItemTap(Map<String, dynamic> item) {
    _searchFocusNode.unfocus();
    if (_isSelectionMode) {
      _toggleItemSelection(item['id']);
      return;
    }

    if (item['type'] == 'series') {
      Navigator.push(
        context,
        MaterialPageRoute(
          builder: (context) =>
              SeriesDetailPage(series: item, statusColors: _statusColors),
        ),
      ).then((_) => refreshData());
    } else {
      Navigator.push(
        context,
        MaterialPageRoute(
          builder: (context) => AnimeDetailPage(existingAnime: item),
        ),
      ).then((v) {
        if (v == true) refreshData();
      });
    }
  }

  void _handleItemLongPress(int id) {
    if (!_isSelectionMode) {
      _toggleSelectionMode(true);
      _toggleItemSelection(id);
    }
  }

  /// 批量同步封面到服务器 [NEW] - 优化为后台同步
  void _batchSyncCoversToServer() async {
    if (_selectedAnimeIds.isEmpty) return;

    // 1. 显示说明弹窗
    final bool? confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Row(
          children: [
            Icon(Icons.cloud_upload_outlined, color: Colors.teal),
            SizedBox(width: 8),
            Text('同步封面至资源库'),
          ],
        ),
        content: const Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('该功能会将您选中的番剧封面 URL 匿名同步至追番喵公共资源库。'),
            SizedBox(height: 12),
            Text('这有助于完善资源库缺失的图片信息，让其他用户在搜索时也能看到精美的封面。'),
            SizedBox(height: 12),
            Text(
              '感谢您的无私分享与对资源库建设的支持！',
              style: TextStyle(fontWeight: FontWeight.bold, color: Colors.teal),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: FilledButton.styleFrom(backgroundColor: Colors.teal),
            child: const Text('立即同步'),
          ),
        ],
      ),
    );

    if (confirmed != true) return;

    // 2. 立即获取快照并退出多选模式，解除 UI 占用
    final List<int> idsToSync = _selectedAnimeIds.toList();
    _toggleSelectionMode(false);

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text("已开启后台同步，共计 ${idsToSync.length} 个项目..."),
        duration: const Duration(seconds: 2),
      ),
    );

    // 3. 异步执行同步任务
    Future.microtask(() async {
      int syncCount = 0;
      int skipCount = 0;

      for (int id in idsToSync) {
        final anime = await DatabaseHelper().getAnimeById(id);
        if (anime == null) continue;

        final String? coverUrl = anime['cover_url'];
        final String title = anime['title'] ?? "";

        if (coverUrl != null && coverUrl.startsWith('http')) {
          try {
            await BangumiService.updateServerCover(title, coverUrl);
            syncCount++;
          } catch (e) {
            // 静默失败
          }
        } else {
          skipCount++;
        }
      }

      // 4. 任务完成后给出反馈
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text("后台封面同步完成：成功 $syncCount个，跳过 $skipCount个自定义封面。"),
            behavior: SnackBarBehavior.floating,
            backgroundColor: Colors.teal,
            duration: const Duration(seconds: 4),
          ),
        );
      }
    });
  }

  void _batchDelete() {
    if (_selectedAnimeIds.isEmpty) return;
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('确认批量删除'),
        content: Text("确定要删除选中的 ${_selectedAnimeIds.length} 部番剧吗？\n此操作不可恢复。"),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            onPressed: () async {
              Navigator.pop(ctx);
              for (int id in _selectedAnimeIds) {
                await DatabaseHelper().deleteAnime(id);
              }
              _toggleSelectionMode(false);
              refreshData();
              if (mounted) {
                ScaffoldMessenger.of(
                  context,
                ).showSnackBar(const SnackBar(content: Text('删除成功')));
              }
            },
            child: Text('删除(${_selectedAnimeIds.length})'),
          ),
        ],
      ),
    );
  }

  void _navigateToAddPage() async {
    final result = await Navigator.push(
      context,
      MaterialPageRoute(builder: (context) => const AddAnimePage()),
    );
    if (result == true) refreshData();
  }

  void _showSortMenu() {
    final primaryColor = Theme.of(context).primaryColor;

    // 排序项图标映射
    final Map<String, IconData> sortIcons = {
      '默认 (最新添加)': Icons.auto_awesome_outlined,
      '播出日期': Icons.calendar_month_outlined,
      '我看完的时间': Icons.task_alt_outlined,
      '评分': Icons.star_outline_rounded,
      '拼音': Icons.sort_by_alpha_rounded,
    };

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) => StatefulBuilder(
        builder: (context, setModalState) => DraggableScrollableSheet(
          initialChildSize: 0.5,
          minChildSize: 0.35,
          maxChildSize: 0.85,
          expand: false,
          builder: (_, controller) => Container(
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surface,
              borderRadius: const BorderRadius.vertical(
                top: Radius.circular(32),
              ),
              boxShadow: [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.05),
                  blurRadius: 10,
                  offset: const Offset(0, -5),
                ),
              ],
            ),
            child: Column(
              children: [
                // 顶部拖动手柄
                Container(
                  width: 36,
                  height: 4,
                  margin: const EdgeInsets.symmetric(vertical: 12),
                  decoration: BoxDecoration(
                    color: Colors.grey[300],
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),

                // 标题栏
                Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 24,
                    vertical: 8,
                  ),
                  child: Row(
                    children: [
                      Container(
                        padding: const EdgeInsets.all(8),
                        decoration: BoxDecoration(
                          color: primaryColor.withValues(alpha: 0.1),
                          shape: BoxShape.circle,
                        ),
                        child: Icon(
                          Icons.sort_rounded,
                          color: primaryColor,
                          size: 20,
                        ),
                      ),
                      const SizedBox(width: 12),
                      const Text(
                        "排序方式",
                        style: TextStyle(
                          fontSize: 20,
                          fontWeight: FontWeight.w900,
                          letterSpacing: 0.5,
                        ),
                      ),
                      const Spacer(),
                      // 重置按钮
                      Material(
                        color: Colors.transparent,
                        child: InkWell(
                          borderRadius: BorderRadius.circular(12),
                          onTap: () {
                            setModalState(() {
                              _selectedSortKeys = ['默认 (最新添加)'];
                              // 重置所有方向为默认 (DESC)
                              _sortDirections.forEach((key, value) {
                                _sortDirections[key] = 'DESC';
                              });
                            });
                            refreshData();
                            Navigator.pop(context);
                          },
                          child: Padding(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 12,
                              vertical: 8,
                            ),
                            child: Row(
                              children: [
                                Icon(
                                  Icons.refresh_rounded,
                                  size: 16,
                                  color: primaryColor,
                                ),
                                const SizedBox(width: 4),
                                Text(
                                  "重置",
                                  style: TextStyle(
                                    color: primaryColor,
                                    fontWeight: FontWeight.bold,
                                    fontSize: 14,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),

                const Padding(
                  padding: EdgeInsets.symmetric(horizontal: 24),
                  child: Divider(height: 1),
                ),

                // 列表区域
                Expanded(
                  child: ListView.separated(
                    controller: controller,
                    padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
                    itemCount: _sortDefinitions.length,
                    separatorBuilder: (context, index) =>
                        const SizedBox(height: 10),
                    itemBuilder: (context, index) {
                      String key = _sortDefinitions.keys.elementAt(index);
                      bool isSelected = _selectedSortKeys.contains(key);
                      String direction = _sortDirections[key] ?? 'DESC';
                      IconData? icon = sortIcons[key];

                      return AnimatedContainer(
                        duration: const Duration(milliseconds: 200),
                        curve: Curves.easeInOut,
                        child: Material(
                          color: isSelected
                              ? primaryColor.withValues(alpha: 0.08)
                              : Colors.grey[50],
                          borderRadius: BorderRadius.circular(16),
                          child: InkWell(
                            borderRadius: BorderRadius.circular(16),
                            onTap: () {
                              setState(() {
                                if (isSelected) {
                                  // 如果已经选中，点击则切换排序方向
                                  _sortDirections[key] = (direction == 'DESC')
                                      ? 'ASC'
                                      : 'DESC';
                                } else {
                                  // 如果未选中，点击则选择该项
                                  _selectedSortKeys = [key];
                                }
                              });
                              setModalState(() {}); // 同时也给弹窗内部刷一下
                              refreshData();
                            },
                            child: Container(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 16,
                                vertical: 14,
                              ),
                              decoration: BoxDecoration(
                                borderRadius: BorderRadius.circular(16),
                                border: Border.all(
                                  color: isSelected
                                      ? primaryColor.withValues(alpha: 0.5)
                                      : Colors.transparent,
                                  width: 1.5,
                                ),
                              ),
                              child: Row(
                                children: [
                                  // 图标预览
                                  Container(
                                    padding: const EdgeInsets.all(8),
                                    decoration: BoxDecoration(
                                      color: isSelected
                                          ? primaryColor
                                          : Colors.grey[200],
                                      borderRadius: BorderRadius.circular(10),
                                    ),
                                    child: Icon(
                                      icon ?? Icons.label_important_outline,
                                      size: 18,
                                      color: isSelected
                                          ? Colors.white
                                          : Colors.grey[600],
                                    ),
                                  ),
                                  const SizedBox(width: 16),
                                  // 文本
                                  Expanded(
                                    child: Row(
                                      children: [
                                        Text(
                                          key,
                                          style: TextStyle(
                                            fontSize: 15,
                                            color: isSelected
                                                ? primaryColor
                                                : Colors.black87,
                                            fontWeight: isSelected
                                                ? FontWeight.w900
                                                : FontWeight.w500,
                                          ),
                                        ),
                                        if (isSelected) ...[
                                          const SizedBox(width: 8),
                                          Icon(
                                            direction == 'DESC'
                                                ? Icons.arrow_downward_rounded
                                                : Icons.arrow_upward_rounded,
                                            size: 14,
                                            color: primaryColor,
                                          ),
                                        ],
                                      ],
                                    ),
                                  ),
                                  // 选中状态标识
                                  if (isSelected)
                                    Icon(
                                      Icons.check_circle_rounded,
                                      color: primaryColor,
                                      size: 22,
                                    )
                                  else
                                    Icon(
                                      Icons.circle_outlined,
                                      color: Colors.grey[300],
                                      size: 22,
                                    ),
                                ],
                              ),
                            ),
                          ),
                        ),
                      );
                    },
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  // 显示统一筛选面板
  void _showFilterPanel() {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (context) {
        return StatefulBuilder(
          builder: (context, setModalState) {
            final primaryColor = Theme.of(context).primaryColor;
            return DraggableScrollableSheet(
              initialChildSize: 0.85,
              minChildSize: 0.5,
              maxChildSize: 0.95,
              expand: false,
              builder: (_, controller) {
                return Container(
                  padding: const EdgeInsets.symmetric(horizontal: 20),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // 1. 顶部手柄和关闭/完成按钮
                      Center(
                        child: Container(
                          width: 40,
                          height: 4,
                          margin: const EdgeInsets.symmetric(vertical: 12),
                          decoration: BoxDecoration(
                            color: Colors.grey[300],
                            borderRadius: BorderRadius.circular(2),
                          ),
                        ),
                      ),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          const Text(
                            "筛选过滤",
                            style: TextStyle(
                              fontSize: 20,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          TextButton(
                            onPressed: () {
                              setState(() {
                                _selectedYears.clear();
                                _selectedTagIds.clear();
                                _selectedStatus = '全部';
                                _selectedType = 'all';
                                refreshData();
                              });
                              setModalState(() {});
                            },
                            style: TextButton.styleFrom(
                              foregroundColor: primaryColor,
                            ),
                            child: const Text("重置所有"),
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),

                      // 2. 筛选内容滚动区域
                      Expanded(
                        child: ListView(
                          controller: controller,
                          children: [
                            // 2.1 书籍/动画 类型
                            _buildPanelSectionHeader("作品类型"),
                            Wrap(
                              spacing: 8,
                              children: [
                                _buildChoiceChip(
                                  "全部",
                                  _selectedType == 'all',
                                  () {
                                    setState(() => _selectedType = 'all');
                                    setModalState(() {});
                                    refreshData();
                                  },
                                ),
                                _buildChoiceChip(
                                  "动画",
                                  _selectedType == 'anime',
                                  () {
                                    setState(() => _selectedType = 'anime');
                                    setModalState(() {});
                                    refreshData();
                                  },
                                ),
                                _buildChoiceChip(
                                  "小说",
                                  _selectedType == 'book',
                                  () {
                                    setState(() => _selectedType = 'book');
                                    setModalState(() {});
                                    refreshData();
                                  },
                                ),
                              ],
                            ),

                            // 2.2 追番状态
                            _buildPanelSectionHeader("追番状态"),
                            Wrap(
                              spacing: 8,
                              children: _statusOptions.map((s) {
                                return _buildChoiceChip(
                                  s,
                                  _selectedStatus == s,
                                  () {
                                    setState(() => _selectedStatus = s);
                                    setModalState(() {});
                                    refreshData();
                                    SettingsManager().setLastSelectedStatus(s);
                                  },
                                  activeColor: _statusColors[s],
                                );
                              }).toList(),
                            ),

                            // 2.3 播出年份
                            _buildPanelSectionHeader("播出年份"),
                            Wrap(
                              spacing: 8,
                              runSpacing: 8,
                              children: [
                                _buildChoiceChip(
                                  "全部",
                                  _selectedYears.isEmpty,
                                  () {
                                    setState(() => _selectedYears.clear());
                                    setModalState(() {});
                                    refreshData();
                                  },
                                ),
                                ..._availableYears.map((y) {
                                  final isSel = _selectedYears.contains(y);
                                  return _buildChoiceChip(y, isSel, () {
                                    setState(() {
                                      if (isSel) {
                                        _selectedYears.remove(y);
                                      } else {
                                        _selectedYears.add(y);
                                      }
                                      refreshData();
                                    });
                                    setModalState(() {});
                                  });
                                }),
                              ],
                            ),

                            // 2.4 筛选标签
                            Row(
                              mainAxisAlignment: MainAxisAlignment.spaceBetween,
                              children: [
                                _buildPanelSectionHeader("全部标签"),
                                TextButton.icon(
                                  onPressed: () {
                                    setState(() {
                                      _isAndMode = !_isAndMode;
                                      refreshData();
                                    });
                                    setModalState(() {});
                                  },
                                  icon: Icon(
                                    _isAndMode
                                        ? Icons.check_box
                                        : Icons.check_box_outline_blank,
                                    size: 16,
                                  ),
                                  label: Text(
                                    _isAndMode ? "同时满足 (且)" : "满足任一 (或)",
                                    style: const TextStyle(fontSize: 12),
                                  ),
                                ),
                              ],
                            ),
                            Wrap(
                              spacing: 8.0,
                              runSpacing: 8.0,
                              children: _allTags.map((tag) {
                                final isSelected = _selectedTagIds.contains(
                                  tag['id'],
                                );
                                return FilterChip(
                                  label: Text(tag['name']),
                                  selected: isSelected,
                                  selectedColor: primaryColor.withValues(alpha: 0.2),
                                  showCheckmark: false,
                                  onSelected: (selected) {
                                    setState(() {
                                      if (selected) {
                                        _selectedTagIds.add(tag['id']);
                                      } else {
                                        _selectedTagIds.remove(tag['id']);
                                      }
                                      refreshData();
                                    });
                                    setModalState(() {});
                                  },
                                );
                              }).toList(),
                            ),
                            const SizedBox(height: 40),
                          ],
                        ),
                      ),

                      // 3. 底部完成按钮
                      Padding(
                        padding: const EdgeInsets.symmetric(vertical: 16),
                        child: SizedBox(
                          width: double.infinity,
                          height: 50,
                          child: FilledButton(
                            onPressed: () => Navigator.pop(context),
                            child: const Text(
                              "查看结果",
                              style: TextStyle(
                                fontSize: 16,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                );
              },
            );
          },
        );
      },
    );
  }

  Widget _buildPanelSectionHeader(String title) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 16),
      child: Text(
        title,
        style: const TextStyle(
          fontSize: 15,
          fontWeight: FontWeight.bold,
          color: Colors.grey,
        ),
      ),
    );
  }

  Widget _buildChoiceChip(
    String label,
    bool selected,
    VoidCallback onTap, {
    Color? activeColor,
  }) {
    final themeColor = Theme.of(context).primaryColor;
    return ChoiceChip(
      label: Text(label),
      selected: selected,
      onSelected: (_) => onTap(),
      selectedColor: (activeColor ?? themeColor).withValues(alpha: 0.2),
      checkmarkColor: activeColor ?? themeColor,
      labelStyle: TextStyle(
        color: selected ? (activeColor ?? themeColor) : Colors.black87,
        fontWeight: selected ? FontWeight.bold : FontWeight.normal,
      ),
    );
  }

  // --- Helper Methods ---

  String _getProgressText(Map<String, dynamic> anime) {
    int total = anime['total_episodes'] ?? 0;
    int watched = anime['watched_episodes'] ?? 0;
    String status = anime['status'];
    if (status == '看完') {
      return total > 0 ? "$total集全" : "已看完";
    } else if (status == '在看')
      return total > 0 ? "$watched / $total" : "看到 $watched";
    else if (status == '弃坑')
      return "止于第$watched集";
    return "";
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: !_isSelectionMode, // 多选模式下拦截返回
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) return;
        if (_isSelectionMode) {
          _toggleSelectionMode(false); // 退出多选模式
        }
      },
      child: Scaffold(
        appBar: _buildAppBar(),
        body: Column(
          children: [
            _buildUnifiedFilterSection(),
            Expanded(
              child: _animes.isEmpty
                  ? _buildEmptyState()
                  : _buildContentBody(),
            ),
          ],
        ),
        bottomNavigationBar:
            _isSelectionMode ? _buildSelectionActionBar() : null,
        floatingActionButton: _isSelectionMode
            ? null
            : FloatingActionButton.extended(
                onPressed: _navigateToAddPage,
                label: const Text('添加'),
                icon: const Icon(Icons.add),
                elevation: 4,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(48),
                ),
              ),
      ),
    );
  }

  // --- Widget Builders ---

  PreferredSizeWidget _buildAppBar() {
    if (_isSelectionMode) {
      return AppBar(
        leading: IconButton(
          icon: const Icon(Icons.close),
          onPressed: () => _toggleSelectionMode(false),
        ),
        title: Text(
          "已选 ${_selectedAnimeIds.length} 项",
          style: const TextStyle(fontSize: 18),
        ),
        backgroundColor: Theme.of(context).colorScheme.surfaceContainerHighest,
        actions: [
          // 全选/取消全选
          TextButton.icon(
            icon: Icon(
              _selectedAnimeIds.length == _animes.length
                  ? Icons.deselect
                  : Icons.select_all,
              size: 20,
            ),
            label: Text(
              _selectedAnimeIds.length == _animes.length ? "取消全选" : "全选",
            ),
            onPressed: _selectAll,
            style: TextButton.styleFrom(
              foregroundColor: Theme.of(context).primaryColor,
            ),
          ),
          const SizedBox(width: 8),
        ],
      );
    } else {
      return AppBar(
        backgroundColor: Theme.of(context).colorScheme.primary,
        titleSpacing: 0,
        title: Padding(
          padding: const EdgeInsets.only(left: 12, right: 4),
          child: Row(
            children: [
              // 应用名称展示 - 使用 AnimatedSwitcher 处理显隐过渡
              AnimatedSwitcher(
                duration: const Duration(milliseconds: 250),
                transitionBuilder: (Widget child, Animation<double> animation) {
                  return FadeTransition(
                    opacity: animation,
                    child: SizeTransition(
                      sizeFactor: animation,
                      axis: Axis.horizontal,
                      child: child,
                    ),
                  );
                },
                child: !_isSearchExpanded
                    ? Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: RichText(
                          overflow: TextOverflow.clip,
                          softWrap: false,
                          text: TextSpan(
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 18,
                            ),
                            children: [
                              const TextSpan(
                                text: "追番喵",
                                style: TextStyle(fontWeight: FontWeight.bold),
                              ),
                              // 只有非搜索状态才尝试显示副标题
                              TextSpan(
                                text: " AniMeow",
                                style: TextStyle(
                                  fontSize: 13,
                                  fontStyle: FontStyle.italic,
                                  fontWeight: FontWeight.w300,
                                  color: Colors.white.withValues(alpha: 0.8),
                                ),
                              ),
                            ],
                          ),
                        ),
                      )
                    : const SizedBox.shrink(),
              ),
              // 搜索框 - 占据剩余空间并实现展开效果
              Expanded(
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 300),
                  curve: Curves.easeOutCubic,
                  height: 36,
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(48),
                  ),
                  child: TextField(
                    controller: _searchController,
                    focusNode: _searchFocusNode,
                    textAlignVertical: TextAlignVertical.center,
                    style: const TextStyle(color: Colors.white, fontSize: 13),
                    onTap: () {
                      if (!_isSearchExpanded) {
                        setState(() {
                          _isSearchExpanded = true;
                        });
                      }
                    },
                    decoration: InputDecoration(
                      hintText: '搜索...',
                      hintStyle: TextStyle(
                        color: Colors.white.withValues(alpha: 0.5),
                        fontSize: 13,
                      ),
                      prefixIcon: const Icon(
                        Icons.search,
                        color: Colors.white70,
                        size: 16,
                      ),
                      suffixIcon: _isSearchExpanded
                          ? Material(
                              color: Colors.transparent,
                              child: IconButton(
                                padding: EdgeInsets.zero,
                                icon: const Icon(
                                  Icons.cancel_rounded,
                                  color: Colors.white70,
                                  size: 18,
                                ),
                                onPressed: () {
                                  setState(() {
                                    _isSearchExpanded = false;
                                    _searchController.clear();
                                  });
                                  _searchFocusNode.unfocus();
                                  refreshData();
                                },
                              ),
                            )
                          : null,
                      border: InputBorder.none,
                      contentPadding: const EdgeInsets.symmetric(
                        horizontal: 12,
                      ),
                      isCollapsed: true,
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.sort, color: Colors.white),
            tooltip: "排序",
            onPressed: _showSortMenu,
          ),
          const SizedBox(width: 8),
        ],
      );
    }
  }

  // 构建多选模式下的底部操作栏
  Widget _buildSelectionActionBar() {
    final bool hasSelection = _selectedAnimeIds.isNotEmpty;
    final Color disabledColor = Colors.grey.shade400;

    return Container(
      padding: EdgeInsets.only(
        bottom: MediaQuery.of(context).padding.bottom + 12,
        top: 12,
        left: 8,
        right: 8,
      ),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface,
        borderRadius: const BorderRadius.only(
          topLeft: Radius.circular(20),
          topRight: Radius.circular(20),
        ),
        boxShadow: [
          BoxShadow(
            color: Theme.of(context).primaryColor.withValues(alpha: 0.15),
            blurRadius: 15,
            spreadRadius: 2,
            offset: const Offset(0, -3),
          ),
        ],
      ),
      child: Stack(
        children: [
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            physics: const BouncingScrollPhysics(),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.start,
              children: [
                const SizedBox(width: 12),
                _buildActionButton(
                  icon: Icons.edit_attributes,
                  label: "修改状态",
                  onPressed: hasSelection ? _showBatchStatusDialog : null,
                  color: hasSelection ? Colors.blue[700]! : disabledColor,
                ),
                const SizedBox(width: 8),
                _buildActionButton(
                  icon: Icons.label_outline,
                  label: "批量标签",
                  onPressed: hasSelection ? _showBatchTagMenu : null,
                  color: hasSelection ? Colors.orange[700]! : disabledColor,
                ),
                const SizedBox(width: 8),
                _buildActionButton(
                  icon: Icons.auto_fix_high,
                  label: "自动匹配",
                  onPressed: hasSelection ? _batchAutoMatchInfo : null,
                  color: hasSelection ? Colors.purple[700]! : disabledColor,
                ),
                const SizedBox(width: 8),
                _buildActionButton(
                  icon: Icons.cloud_upload_outlined,
                  label: "同步封面",
                  onPressed: hasSelection ? _batchSyncCoversToServer : null,
                  color: hasSelection ? Colors.teal[700]! : disabledColor,
                ),
                const SizedBox(width: 8),
                _buildActionButton(
                  icon: Icons.delete_outline,
                  label: "删除",
                  onPressed: hasSelection ? _batchDelete : null,
                  color: hasSelection ? Colors.red[700]! : disabledColor,
                ),
                const SizedBox(width: 12),
              ],
            ),
          ),
          // 滚动提示渐变遮罩
          Positioned(
            right: 0,
            top: 0,
            bottom: 0,
            child: IgnorePointer(
              child: Container(
                width: 40,
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    begin: Alignment.centerLeft,
                    end: Alignment.centerRight,
                    colors: [
                      Theme.of(context).colorScheme.surface.withValues(alpha: 0.0),
                      Theme.of(context).colorScheme.surface.withValues(alpha: 0.9),
                      Theme.of(context).colorScheme.surface,
                    ],
                  ),
                ),
                alignment: Alignment.centerRight,
                child: Padding(
                  padding: const EdgeInsets.only(right: 4),
                  child: Icon(
                    Icons.chevron_right,
                    size: 18,
                    color: Theme.of(context).primaryColor.withValues(alpha: 0.5),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildActionButton({
    required IconData icon,
    required String label,
    required VoidCallback? onPressed,
    required Color color,
  }) {
    return InkWell(
      onTap: onPressed,
      borderRadius: BorderRadius.circular(16),
      child: Container(
        width: 90,
        padding: const EdgeInsets.symmetric(vertical: 10),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, color: color, size: 30),
            const SizedBox(height: 6),
            Text(
              label,
              style: TextStyle(
                color: color,
                fontSize: 13,
                fontWeight: FontWeight.bold,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildUnifiedFilterSection() {
    final hasYearFilter = _selectedYears.isNotEmpty;
    final hasTagFilter = _selectedTagIds.isNotEmpty;
    final primaryColor = Theme.of(context).primaryColor;
    final isFiltering =
        hasYearFilter ||
        hasTagFilter ||
        _selectedStatus != '全部' ||
        _selectedType != 'all';

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 16),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface,
        border: Border(bottom: BorderSide(color: Colors.grey.withValues(alpha: 0.1))),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              // 整合后的主按钮
              InkWell(
                onTap: _showFilterPanel,
                borderRadius: BorderRadius.circular(12),
                child: Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 8,
                  ),
                  decoration: BoxDecoration(
                    color: isFiltering
                        ? primaryColor.withValues(alpha: 0.1)
                        : Colors.grey[100],
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(
                      color: isFiltering ? primaryColor : Colors.transparent,
                      width: 1.5,
                    ),
                  ),
                  child: Row(
                    children: [
                      Icon(
                        Icons.tune_rounded,
                        size: 20,
                        color: isFiltering ? primaryColor : Colors.black87,
                      ),
                      const SizedBox(width: 8),
                      Text(
                        "筛选",
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.bold,
                          color: isFiltering ? primaryColor : Colors.black87,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(width: 12),

              // 显示当前筛选摘要的横向列表
              Expanded(
                child: SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: Row(
                    children: [
                      if (_selectedType != 'all')
                        _buildFilterSummaryChip(
                          _selectedType == 'anime' ? '动画' : '小说',
                          () => setState(() {
                            _selectedType = 'all';
                            refreshData();
                          }),
                        ),
                      if (_selectedStatus != '全部')
                        _buildFilterSummaryChip(
                          _selectedStatus,
                          () => setState(() {
                            _selectedStatus = '全部';
                            refreshData();
                          }),
                        ),
                      if (hasYearFilter)
                        _buildFilterSummaryChip(
                          _selectedYears.length == 1
                              ? _selectedYears.first
                              : "${_selectedYears.length}年份",
                          () => setState(() {
                            _selectedYears.clear();
                            refreshData();
                          }),
                        ),
                      if (hasTagFilter)
                        _buildFilterSummaryChip(
                          "${_selectedTagIds.length}标签",
                          () => setState(() {
                            _selectedTagIds.clear();
                            refreshData();
                          }),
                        ),

                      // 如果没有任何筛选，显示提示
                      if (!isFiltering)
                        Text(
                          "在此筛选番剧...",
                          style: TextStyle(
                            color: Colors.grey[400],
                            fontSize: 13,
                          ),
                        ),
                    ],
                  ),
                ),
              ),
              // 重置按钮
              if (isFiltering)
                IconButton(
                  onPressed: () {
                    setState(() {
                      _selectedYears.clear();
                      _selectedTagIds.clear();
                      _selectedStatus = '全部';
                      _selectedType = 'all';
                      refreshData();
                    });
                  },
                  icon: Icon(
                    Icons.refresh_rounded,
                    size: 20,
                    color: primaryColor,
                  ),
                  visualDensity: VisualDensity.compact,
                ),
            ],
          ),

          // 如果选中了标签且有多个，显示模式切换快捷键
          if (hasTagFilter && _selectedTagIds.length > 1)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: InkWell(
                onTap: () => setState(() {
                  _isAndMode = !_isAndMode;
                  refreshData();
                }),
                child: Text(
                  _isAndMode ? "当前：同时满足所有标签 (且)" : "当前：满足任一标签 (或)",
                  style: TextStyle(
                    fontSize: 11,
                    color: primaryColor,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildFilterSummaryChip(String label, VoidCallback onDeleted) {
    final color = Theme.of(context).primaryColor;
    return Padding(
      padding: const EdgeInsets.only(right: 8),
      child: Material(
        color: color.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(16),
        child: InkWell(
          onTap: onDeleted,
          borderRadius: BorderRadius.circular(16),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  label,
                  style: TextStyle(
                    fontSize: 12,
                    color: color,
                    fontWeight: FontWeight.w500,
                  ),
                ),
                const SizedBox(width: 4),
                Icon(Icons.close_rounded, size: 14, color: color),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildEmptyState() {
    return EmptyStateWidget(
      message: "暂无番剧",
      buttonText: "去添加一部吧",
      onButtonPressed: _navigateToAddPage,
    );
  }

  Widget _buildContentBody() {
    final settings = SettingsManager();
    return ListenableBuilder(
      listenable: Listenable.merge([
        settings.homeLayoutNotifier,
        settings.titlePositionNotifier,
        settings.gridColumnsNotifier,
        settings.showTitleNotifier,
        settings.showRatingNotifier,
        settings.showProgressNotifier,
        settings.showStatusNotifier,
        settings.showSubjectTypeNotifier,
        settings.badgeStyleNotifier,
      ]),
      builder: (context, _) {
        final layout = settings.homeLayoutNotifier.value;
        final props = HomeViewProps(
          items: _animes,
          statusColors: _statusColors,
          appDocDir: _appDocDir,
          isSelectionMode: _isSelectionMode,
          selectedIds: _selectedAnimeIds,
          onItemTap: _handleItemTap,
          onItemLongPress: _handleItemLongPress,
          onRefresh: () async {
            refreshData();
          },
          onLoadMore: _hasMoreData ? _loadMoreItems : null,
          hasMore: _hasMoreData,
          gridColumns: settings.gridColumnsNotifier.value,
          titlePosition: settings.titlePositionNotifier.value,
          showTitle: settings.showTitleNotifier.value,
          showRating: settings.showRatingNotifier.value,
          showProgress: settings.showProgressNotifier.value,
          showStatus: settings.showStatusNotifier.value,
          showSubjectType: settings.showSubjectTypeNotifier.value,
          badgeStyle: settings.badgeStyleNotifier.value,
          selectedStatus: _selectedStatus,
          isInDefaultMode: _isInDefaultMode(),
          isSortedByPinyin: _selectedSortKeys.isNotEmpty &&
              _selectedSortKeys.first == '拼音',
          progressTextOf: _getProgressText,
        );
        return buildHomeView(layout, props);
      },
    );
  }

  /// 用于判断 Bento 首页是否展开 smart sections
  bool _isInDefaultMode() {
    return _selectedStatus == '全部' &&
        _selectedType == 'all' &&
        _searchController.text.trim().isEmpty &&
        _selectedTagIds.isEmpty &&
        _selectedYears.isEmpty;
  }


  // --- Batch Operations Logic ---

  // 1. 批量修改状态
  void _showBatchStatusDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('批量修改状态'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: _statusOptions.skip(1).map((status) {
            // skip(1) 是跳过 "全部"
            return ListTile(
              title: Text(status),
              leading: const Icon(Icons.radio_button_unchecked),
              onTap: () async {
                await DatabaseHelper().batchUpdateStatus(
                  _selectedAnimeIds.toList(),
                  status,
                );
                if (mounted) Navigator.pop(context);
                _toggleSelectionMode(false);
                refreshData();
                ScaffoldMessenger.of(
                  context,
                ).showSnackBar(const SnackBar(content: Text('批量修改状态成功')));
              },
            );
          }).toList(),
        ),
      ),
    );
  }

  // 2. 批量标签菜单
  void _showBatchTagMenu() {
    showModalBottomSheet(
      context: context,
      builder: (context) => Container(
        padding: const EdgeInsets.symmetric(vertical: 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text(
              "批量标签操作",
              style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16),
            ),
            const Divider(),
            ListTile(
              leading: const Icon(Icons.add, color: Colors.blue),
              title: const Text('批量添加标签'),
              subtitle: const Text('给选中的番剧统一加上某个标签'),
              onTap: () {
                Navigator.pop(context);
                _showBatchTagSelector(isAdd: true);
              },
            ),
            ListTile(
              leading: const Icon(Icons.remove, color: Colors.red),
              title: const Text('批量移除标签'),
              subtitle: const Text('从选中的番剧中统一移除某个标签'),
              onTap: () {
                Navigator.pop(context);
                _showBatchTagSelector(isAdd: false);
              },
            ),
          ],
        ),
      ),
    );
  }

  // 3. 选择具体要操作的标签 (支持新建)
  void _showBatchTagSelector({required bool isAdd}) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(isAdd ? '选择要添加的标签' : '选择要移除的标签'),
        content: SizedBox(
          width: double.maxFinite,
          height: 300,
          child: _allTags.isEmpty
              ? const Center(child: Text("暂无标签，请新建"))
              : ListView.builder(
                  shrinkWrap: true,
                  itemCount: _allTags.length,
                  itemBuilder: (context, index) {
                    final tag = _allTags[index];
                    return ListTile(
                      leading: const Icon(Icons.label, color: Colors.indigo),
                      title: Text(tag['name']),
                      onTap: () async {
                        Navigator.pop(context);

                        if (isAdd) {
                          await DatabaseHelper().batchAddTagToAnimes(
                            _selectedAnimeIds.toList(),
                            tag['id'],
                          );
                        } else {
                          await DatabaseHelper().batchRemoveTagFromAnimes(
                            _selectedAnimeIds.toList(),
                            tag['id'],
                          );
                        }

                        _toggleSelectionMode(false);
                        refreshData();
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(
                            content: Text(isAdd ? '批量添加标签成功' : '批量移除标签成功'),
                          ),
                        );
                      },
                    );
                  },
                ),
        ),
        actions: [
          if (isAdd)
            TextButton.icon(
              icon: const Icon(Icons.add),
              label: const Text("新建并添加"),
              onPressed: () {
                Navigator.pop(context);
                _showBatchCreateTagDialog();
              },
            ),
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
        ],
      ),
    );
  }

  // 4. 批量操作时的“新建标签”对话框
  void _showBatchCreateTagDialog() {
    final textController = TextEditingController();
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('新建标签并批量应用'),
        content: TextField(
          controller: textController,
          decoration: const InputDecoration(
            labelText: "标签名称",
            hintText: "例如：#神作",
            border: OutlineInputBorder(),
          ),
          autofocus: true,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () async {
              if (textController.text.isEmpty) return;

              // 尝试插入新标签
              int newTagId = await DatabaseHelper().insertTag(
                textController.text,
              );

              if (newTagId == -1) {
                // 标签已存在
                if (mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(
                      content: Text('标签已存在！请直接在列表中选择它。'),
                      backgroundColor: Colors.orange,
                    ),
                  );
                  Navigator.pop(context);
                  _showBatchTagSelector(isAdd: true);
                }
              } else {
                // 创建成功，批量应用
                await DatabaseHelper().batchAddTagToAnimes(
                  _selectedAnimeIds.toList(),
                  newTagId,
                );
                if (mounted) {
                  Navigator.pop(context);
                  _toggleSelectionMode(false);
                  refreshData();
                  ScaffoldMessenger.of(context).showSnackBar(
                    SnackBar(
                      content: Text(
                        '已新建标签 "${textController.text}" 并添加到 ${_selectedAnimeIds.length} 部番剧',
                      ),
                    ),
                  );
                }
              }
            },
            child: const Text('新建并添加'),
          ),
        ],
      ),
    );
  }

  // 5. 批量自动匹配信息
  void _batchAutoMatchInfo() async {
    // 弹出同步选项对话框
    final Map<String, bool>? selections = await showDialog<Map<String, bool>>(
      context: context,
      builder: (ctx) {
        Map<String, bool> localSelections = {
          'cover': true,
          'rating': true,
          'episodes': true,
          'studio': true,
          'summary': true,
          'airDate': true,
        };
        return StatefulBuilder(
          builder: (ctx, setDialogState) {
            return AlertDialog(
              title: const Text('批量同步选项'),
              content: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      '将对选中的 ${_selectedAnimeIds.length} 部番剧进行联网匹配。',
                      style: const TextStyle(fontSize: 13, color: Colors.grey),
                    ),
                    const SizedBox(height: 12),
                    CheckboxListTile(
                      title: const Text('封面'),
                      value: localSelections['cover'],
                      onChanged: (v) =>
                          setDialogState(() => localSelections['cover'] = v!),
                    ),
                    CheckboxListTile(
                      title: const Text('评分'),
                      value: localSelections['rating'],
                      onChanged: (v) =>
                          setDialogState(() => localSelections['rating'] = v!),
                    ),
                    CheckboxListTile(
                      title: const Text('集数配置'),
                      value: localSelections['episodes'],
                      onChanged: (v) => setDialogState(
                        () => localSelections['episodes'] = v!,
                      ),
                    ),
                    CheckboxListTile(
                      title: const Text('制作公司'),
                      value: localSelections['studio'],
                      onChanged: (v) =>
                          setDialogState(() => localSelections['studio'] = v!),
                    ),
                    CheckboxListTile(
                      title: const Text('简介'),
                      value: localSelections['summary'],
                      onChanged: (v) =>
                          setDialogState(() => localSelections['summary'] = v!),
                    ),
                    CheckboxListTile(
                      title: const Text('放送日期'),
                      value: localSelections['airDate'],
                      onChanged: (v) =>
                          setDialogState(() => localSelections['airDate'] = v!),
                    ),
                  ],
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(ctx),
                  child: const Text('取消'),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(ctx, localSelections),
                  child: const Text('开始匹配选中项'),
                ),
                FilledButton(
                  onPressed: () {
                    Navigator.pop(ctx, {
                      'cover': true,
                      'rating': true,
                      'episodes': true,
                      'studio': true,
                      'summary': true,
                      'airDate': true,
                    });
                  },
                  child: const Text('全部同步'),
                ),
              ],
            );
          },
        );
      },
    );

    if (selections == null || !mounted) return;

    // 显示进度弹窗 (不可取消)
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (BuildContext context) {
        return StatefulBuilder(
          builder: (context, setState) {
            return _AutoMatchProgressDialog(
              selectedIds: _selectedAnimeIds.toList(),
              animes: _animes,
              enabledFields: selections,
              onCompleted: () {
                Navigator.pop(context);
                _toggleSelectionMode(false);
                refreshData();
                ScaffoldMessenger.of(
                  context,
                ).showSnackBar(const SnackBar(content: Text('批量匹配完成！')));
              },
            );
          },
        );
      },
    );
  }
}

class _AutoMatchProgressDialog extends StatefulWidget {
  final List<int> selectedIds;
  final List<Map<String, dynamic>> animes;
  final Map<String, bool> enabledFields;
  final VoidCallback onCompleted;

  const _AutoMatchProgressDialog({
    required this.selectedIds,
    required this.animes,
    required this.enabledFields,
    required this.onCompleted,
  });

  @override
  State<_AutoMatchProgressDialog> createState() =>
      _AutoMatchProgressDialogState();
}

class _AutoMatchProgressDialogState extends State<_AutoMatchProgressDialog> {
  int _currentCount = 0;
  int _successCount = 0;
  String _currentTitle = "准备中...";
  double _progress = 0.0;
  bool _stopRequested = false;
  bool _skipRequested = false;
  final Stopwatch _stopwatch = Stopwatch();
  Timer? _timer;
  String _durationText = "0.0s";

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  void initState() {
    super.initState();
    _startBatchProcess();
  }

  void _startBatchProcess() async {
    int total = widget.selectedIds.length;
    for (int i = 0; i < total; i++) {
      if (!mounted || _stopRequested) break;
      int animeId = widget.selectedIds[i];
      final anime = widget.animes.firstWhere(
        (element) => element['id'] == animeId,
        orElse: () => {},
      );
      if (anime.isEmpty) continue;

      String title = anime['title'];

      setState(() {
        _currentCount = i + 1;
        _currentTitle = "正在搜索: $title";
        _progress = _currentCount / total;
      });
      try {
        _stopwatch.reset();
        _stopwatch.start();
        _skipRequested = false;
        _timer?.cancel();
        _timer = Timer.periodic(const Duration(milliseconds: 100), (timer) {
          if (mounted) {
            setState(() {
              _durationText =
                  "${(_stopwatch.elapsedMilliseconds / 1000).toStringAsFixed(1)}s";
            });
          }
        });

        // 并行搜索 Bangumi 和 Anilist
        // 为了支持“跳过”，我们将搜索操作与 completer 结合，或者在循环中检查状态
        final resultsList = await Future.any([
          Future.wait([
            BangumiService.searchAnime(title),
            AnilistService.searchAnime(title),
          ]),
          _waitForSkip(),
        ]);

        _stopwatch.stop();
        _timer?.cancel();

        if (_skipRequested) {
          continue; // 跳到下一个
        }

        // 展平结果：Bangumi 在前，Anilist 在后
        final allResults = [...resultsList[0], ...resultsList[1]];
        if (allResults.isNotEmpty) {
          // 默认取第一个结果
          final bestMatch = allResults.first;
          Map<String, dynamic> updateData = {'id': animeId};

          // 1. 通用基础信息 (封面、总集数)
          if (widget.enabledFields['cover'] == true &&
              bestMatch.coverUrl != null &&
              bestMatch.coverUrl!.isNotEmpty) {
            updateData['cover_url'] = bestMatch.coverUrl;
          }
          if (widget.enabledFields['episodes'] == true &&
              bestMatch.eps != null &&
              bestMatch.eps != 0) {
            updateData['total_episodes'] = bestMatch.eps;
          }
          // 评分处理 (Bangumi/Anilist 搜索结果自带)
          if (widget.enabledFields['rating'] == true &&
              bestMatch.score != null &&
              bestMatch.score! > 0) {
            updateData['rating'] = bestMatch.score;
          }

          // 2. 详情处理 (区分来源)
          if (bestMatch.source == 'bangumi') {
            // [Bangumi] 需要二次查询详情
            final detail = await BangumiService.getAnimeDetail(bestMatch.id);
            // 日期处理
            if (widget.enabledFields['airDate'] == true) {
              String? finalAirDate = detail['air_date'];
              if (finalAirDate == null || finalAirDate.isEmpty) {
                finalAirDate = bestMatch.airDate;
              }
              if (finalAirDate != null && finalAirDate.isNotEmpty) {
                updateData['air_date'] = finalAirDate;
              }
            }

            if (widget.enabledFields['studio'] == true &&
                detail['studio'] != null) {
              updateData['studio'] = detail['studio'];
            }
            if (widget.enabledFields['episodes'] == true &&
                detail['eps'] != null &&
                detail['eps'] != 0) {
              updateData['total_episodes'] = detail['eps'];
            }
            // 优先使用详情页评分
            if (widget.enabledFields['rating'] == true &&
                detail['score'] != null) {
              updateData['rating'] = detail['score'];
            }
            // 简介处理
            if (widget.enabledFields['summary'] == true) {
              String existingReview = anime['review'] ?? '';
              String newSummary = detail['summary'] ?? '';
              if (newSummary.isNotEmpty && existingReview.isEmpty) {
                updateData['review'] = newSummary;
              }
            }
          } else {
            // [Anilist] 信息直接使用
            if (widget.enabledFields['airDate'] == true &&
                bestMatch.airDate != null) {
              updateData['air_date'] = bestMatch.airDate;
            }
            if (widget.enabledFields['studio'] == true &&
                bestMatch.studio != null) {
              updateData['studio'] = bestMatch.studio;
            }

            // 简介处理
            if (widget.enabledFields['summary'] == true) {
              String existingReview = anime['review'] ?? '';
              if (bestMatch.summary != null &&
                  bestMatch.summary!.isNotEmpty &&
                  existingReview.isEmpty) {
                updateData['review'] = bestMatch.summary;
              }
            }
          }

          // 3. 写入数据库
          await DatabaseHelper().updateAnime(updateData);
          _successCount++;
        }
      } catch (e) {
        logger.e("Auto match error: $e");
      }

      // 稍微缓冲，避免请求过快
      await Future.delayed(const Duration(milliseconds: 500));
    }

    if (mounted) {
      widget.onCompleted();
    }
  }

  Future<List<dynamic>> _waitForSkip() async {
    while (!_skipRequested && !_stopRequested && mounted) {
      await Future.delayed(const Duration(milliseconds: 200));
    }
    return [[], []]; // 返回空结果以退出 search
  }

  @override
  Widget build(BuildContext context) {
    bool canSkip = _stopwatch.elapsed.inSeconds >= 20;

    return AlertDialog(
      title: const Text("自动匹配进度"),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          LinearProgressIndicator(
            value: _progress,
            backgroundColor: _stopRequested ? Colors.grey[300] : null,
            valueColor: _stopRequested
                ? const AlwaysStoppedAnimation<Color>(Colors.grey)
                : null,
          ),
          const SizedBox(height: 20),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                _stopRequested
                    ? "已停止"
                    : "进度: $_currentCount / ${widget.selectedIds.length}",
              ),
              Text(
                "耗时: $_durationText",
                style: const TextStyle(
                  fontSize: 12,
                  fontFamily: 'monospace',
                  color: Colors.blueGrey,
                ),
              ),
            ],
          ),
          Text("成功匹配: $_successCount"),
          const SizedBox(height: 10),
          Text(
            _stopRequested ? "操作已取消" : _currentTitle,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(color: Colors.grey),
          ),
        ],
      ),
      actions: [
        if (!_stopRequested && _currentCount < widget.selectedIds.length) ...[
          if (canSkip)
            TextButton(
              onPressed: () {
                setState(() {
                  _skipRequested = true;
                });
              },
              child: const Text("跳过此项"),
            ),
          TextButton(
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            onPressed: () {
              setState(() {
                _stopRequested = true;
              });
            },
            child: const Text("停止"),
          ),
        ],
      ],
    );
  }
}
