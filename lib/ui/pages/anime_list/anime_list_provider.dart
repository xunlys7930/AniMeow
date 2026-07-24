import 'dart:async';
import 'package:flutter/material.dart';
import 'package:lpinyin/lpinyin.dart';
import 'package:anime_tracker/repositories/anime_repository.dart';
import 'package:anime_tracker/repositories/series_repository.dart';
import 'package:anime_tracker/repositories/tag_repository.dart';
import 'package:anime_tracker/repositories/watch_repository.dart';
import 'package:anime_tracker/services/service_locator.dart';
import 'package:anime_tracker/settings_manager.dart';
import 'package:anime_tracker/utils/anime_rating.dart';

class DuplicateAnimeGroup {
  final String key;
  final String subjectType;
  final String title;
  final List<Map<String, dynamic>> items;

  const DuplicateAnimeGroup({
    required this.key,
    required this.subjectType,
    required this.title,
    required this.items,
  });

  String get typeLabel => subjectType == 'book' ? '小说' : '动画';
}

/// 负责主页番剧列表的状态和业务逻辑维护 (ChangeNotifier)
class AnimeListProvider extends ChangeNotifier {
  final AnimeRepository _animeRepo = getIt<AnimeRepository>();
  final TagRepository _tagRepo = getIt<TagRepository>();
  final SeriesRepository _seriesRepo = getIt<SeriesRepository>();
  final WatchRepository _watchRepo = getIt<WatchRepository>();

  // --- 数据源 ---
  List<Map<String, dynamic>> _allAnimesData = []; // 全量混合数据
  List<Map<String, dynamic>> _animes = []; // 分页数据
  List<Map<String, dynamic>> _allTags = []; // 标签列表
  int _totalItemCount = 0;

  // --- 分页 ---
  static const int _pageSize = 250;
  bool _hasMoreData = false;
  bool _isLoadingMore = false;
  bool _isLoading = true;
  bool _isRefreshing = false;
  bool _hasLoadedOnce = false;
  Object? _loadError;
  int _refreshGeneration = 0;

  // --- 筛选与搜索 ---
  final Set<int> _selectedTagIds = {};
  bool _isAndMode = false;
  String _selectedStatus = '全部';
  String _selectedType = 'all'; // all, anime, book
  final Set<String> _selectedYears = {};
  List<String> _availableYears = [];
  String _searchQuery = '';
  Timer? _debounce;
  StreamSubscription<void>? _dataSubscription;

  List<String> _statusOptions = ['全部', '未看', '在看', '看完', '弃坑'];
  Map<String, Color> _statusColors = {};

  // --- 布局与排序 ---
  final Map<String, String> _sortDefinitions = {
    '默认 (最新添加)': 'a.id',
    '播出日期': 'a.air_date',
    '我看完的时间': 'a.watch_finish_date',
    '评分':
        "CASE UPPER(COALESCE(a.rating_grade, '')) "
        "WHEN 'A' THEN 10 WHEN 'B' THEN 8 WHEN 'C' THEN 6 "
        "WHEN 'D' THEN 4 ELSE COALESCE(a.rating, 0) END",
    '拼音': 'a.name',
  };
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

  // --- Getters ---
  List<Map<String, dynamic>> get allAnimesData => _allAnimesData;
  List<Map<String, dynamic>> get animes => _animes;
  int get totalItemCount => _totalItemCount;
  List<Map<String, dynamic>> get allTags => _allTags;
  bool get hasMoreData => _hasMoreData;
  bool get isLoadingMore => _isLoadingMore;
  bool get isLoading => _isLoading;
  bool get isRefreshing => _isRefreshing;
  Object? get loadError => _loadError;

  Set<int> get selectedTagIds => _selectedTagIds;
  bool get isAndMode => _isAndMode;
  String get selectedStatus => _selectedStatus;
  String get selectedType => _selectedType;
  Set<String> get selectedYears => _selectedYears;
  List<String> get availableYears => _availableYears;
  String get searchQuery => _searchQuery;

  List<String> get statusOptions => _statusOptions;
  Map<String, Color> get statusColors => _statusColors;
  List<String> get selectedSortKeys => _selectedSortKeys;
  Map<String, String> get sortDirections => _sortDirections;
  Map<String, String> get sortDefinitions => _sortDefinitions;

  bool get hasActiveFilters =>
      _selectedStatus != '全部' ||
      _selectedType != 'all' ||
      _selectedYears.isNotEmpty ||
      _selectedTagIds.isNotEmpty;

  int get activeFilterCount {
    var count = 0;
    if (_selectedType != 'all') count++;
    if (_selectedStatus != '全部') count++;
    if (_selectedYears.isNotEmpty) count++;
    if (_selectedTagIds.isNotEmpty) count++;
    return count;
  }

  String get selectedSortLabel =>
      _selectedSortKeys.isEmpty ? '默认 (最新添加)' : _selectedSortKeys.first;

  String get selectedSortDirection =>
      _sortDirections[selectedSortLabel] ?? 'DESC';

  bool get isSelectionMode => _isSelectionMode;
  Set<int> get selectedAnimeIds => _selectedAnimeIds;

  // --- 初始化方法 ---
  void init() {
    _isLoading = true;
    final defaultStart = SettingsManager().defaultStartStatusNotifier.value;
    if (defaultStart == '上次退出前') {
      _selectedStatus = SettingsManager().lastSelectedStatusNotifier.value;
    } else {
      _selectedStatus = defaultStart;
    }
    _dataSubscription ??= _animeRepo.watchAllAnimeMaps().skip(1).listen((_) {
      refreshData();
    });
    refreshData();
  }

  // --- 搜索设置及防抖 ---
  void setSearchQuery(String query) {
    _searchQuery = query;
    if (_debounce?.isActive ?? false) _debounce!.cancel();
    _debounce = Timer(const Duration(milliseconds: 300), () => refreshData());
    notifyListeners();
  }

  // --- 状态与筛选设置 ---
  void setStatus(String status) {
    _selectedStatus = status;
    refreshData();
  }

  void setType(String type) {
    _selectedType = type;
    refreshData();
  }

  void toggleAndMode() {
    _isAndMode = !_isAndMode;
    refreshData();
  }

  void toggleTagSelection(int tagId) {
    if (_selectedTagIds.contains(tagId)) {
      _selectedTagIds.remove(tagId);
    } else {
      _selectedTagIds.add(tagId);
    }
    refreshData();
  }

  void clearTags() {
    _selectedTagIds.clear();
    refreshData();
  }

  void toggleYearSelection(String year) {
    if (_selectedYears.contains(year)) {
      _selectedYears.remove(year);
    } else {
      _selectedYears.add(year);
    }
    refreshData();
  }

  void clearYears() {
    _selectedYears.clear();
    refreshData();
  }

  /// 一次性提交筛选条件，避免面板内的每次点选都触发数据库查询。
  void applyFilters({
    required String type,
    required String status,
    required Set<String> years,
    required Set<int> tagIds,
    required bool isAndMode,
  }) {
    final changed =
        _selectedType != type ||
        _selectedStatus != status ||
        _isAndMode != isAndMode ||
        !_sameSet(_selectedYears, years) ||
        !_sameSet(_selectedTagIds, tagIds);
    if (!changed) return;

    _selectedType = type;
    _selectedStatus = status;
    _isAndMode = isAndMode;
    _selectedYears
      ..clear()
      ..addAll(years);
    _selectedTagIds
      ..clear()
      ..addAll(tagIds);
    refreshData();
  }

  /// 原子重置所有筛选，只刷新一次。
  void clearFilters() {
    if (!hasActiveFilters && !_isAndMode) return;
    _selectedType = 'all';
    _selectedStatus = '全部';
    _isAndMode = false;
    _selectedYears.clear();
    _selectedTagIds.clear();
    refreshData();
  }

  void filterByTag(int tagId) {
    _selectedTagIds.clear();
    _selectedTagIds.add(tagId);
    _searchQuery = '';
    _selectedStatus = '全部';
    _selectedType = 'all';
    _selectedYears.clear();
    refreshData();
  }

  // --- 排序管理 ---
  void setSortKeys(List<String> keys) {
    _selectedSortKeys = keys;
    refreshData();
  }

  void updateSortDirection(String key, String direction) {
    _sortDirections[key] = direction;
    refreshData();
  }

  /// 恢复排序默认值，只刷新一次。
  void resetSort() {
    _selectedSortKeys = ['默认 (最新添加)'];
    _sortDirections.updateAll((key, _) => key == '拼音' ? 'ASC' : 'DESC');
    refreshData();
  }

  // --- 批量选择模式管理 ---
  void toggleSelectionMode(bool enable) {
    _isSelectionMode = enable;
    _selectedAnimeIds.clear();
    notifyListeners();
  }

  void toggleItemSelection(int id) {
    if (_selectedAnimeIds.contains(id)) {
      _selectedAnimeIds.remove(id);
    } else {
      _selectedAnimeIds.add(id);
    }
    notifyListeners();
  }

  void toggleSelectAll() {
    final selectableIds = _animes
        .where((item) => item['type'] == 'anime')
        .map((item) => item['id'] as int)
        .toSet();
    if (selectableIds.isNotEmpty &&
        _selectedAnimeIds.length == selectableIds.length &&
        _selectedAnimeIds.containsAll(selectableIds)) {
      _selectedAnimeIds.clear();
    } else {
      _selectedAnimeIds
        ..clear()
        ..addAll(selectableIds);
    }
    notifyListeners();
  }

  // --- 批量数据写入操作（封装复合动作，完成后自动刷新） ---

  /// 批量删除
  Future<void> batchDeleteSelected() async {
    if (_selectedAnimeIds.isEmpty) return;
    for (int id in _selectedAnimeIds) {
      await _animeRepo.deleteAnime(id);
    }
    toggleSelectionMode(false);
    refreshData();
  }

  /// 查找同类型、同名称的重复条目。
  Future<List<DuplicateAnimeGroup>> findDuplicateGroups() async {
    final rows = await _animeRepo.queryAllAnimes();
    final grouped = <String, List<Map<String, dynamic>>>{};

    for (final row in rows) {
      final title = (row['title'] ?? '').toString().trim();
      if (title.isEmpty) continue;

      final subjectType = (row['subject_type'] ?? 'anime').toString();
      final titleKey = _normalizeDuplicateTitle(title);
      if (titleKey.isEmpty) continue;

      final key = '$subjectType|$titleKey';
      grouped.putIfAbsent(key, () => []).add(Map<String, dynamic>.from(row));
    }

    final groups = grouped.entries.where((entry) => entry.value.length > 1).map(
      (entry) {
        final items = entry.value;
        items.sort((a, b) => (b['id'] as int).compareTo(a['id'] as int));
        return DuplicateAnimeGroup(
          key: entry.key,
          subjectType: (items.first['subject_type'] ?? 'anime').toString(),
          title: (items.first['title'] ?? '').toString(),
          items: items,
        );
      },
    ).toList();

    groups.sort((a, b) {
      final typeCompare = a.subjectType.compareTo(b.subjectType);
      if (typeCompare != 0) return typeCompare;
      return a.title.compareTo(b.title);
    });
    return groups;
  }

  Future<int> deleteDuplicateItems(Iterable<int> ids) async {
    final uniqueIds = ids.toSet().toList();
    if (uniqueIds.isEmpty) return 0;

    for (final id in uniqueIds) {
      await _animeRepo.deleteAnime(id);
    }
    refreshData();
    return uniqueIds.length;
  }

  /// 批量修改状态
  Future<void> batchUpdateStatus(String newStatus) async {
    if (_selectedAnimeIds.isEmpty) return;
    await _animeRepo.batchUpdateStatus(_selectedAnimeIds.toList(), newStatus);
    toggleSelectionMode(false);
    refreshData();
  }

  /// 批量同步封面
  Future<Map<String, int>> batchSyncCovers() async {
    if (_selectedAnimeIds.isEmpty) return {'success': 0, 'skip': 0, 'fail': 0};
    final results = await _animeRepo.syncCovers(_selectedAnimeIds.toList());
    toggleSelectionMode(false);
    refreshData();
    return results;
  }

  /// 批量添加标签
  Future<void> batchAddTag(int tagId) async {
    if (_selectedAnimeIds.isEmpty) return;
    await _tagRepo.batchAddTagToAnimes(_selectedAnimeIds.toList(), tagId);
    toggleSelectionMode(false);
    refreshData();
  }

  /// 批量移除标签
  Future<void> batchRemoveTag(int tagId) async {
    if (_selectedAnimeIds.isEmpty) return;
    await _tagRepo.batchRemoveTagFromAnimes(_selectedAnimeIds.toList(), tagId);
    toggleSelectionMode(false);
    refreshData();
  }

  // --- 数据源 ---
  Future<void> refreshData() async {
    final requestId = ++_refreshGeneration;
    final query = _searchQuery.trim();
    final selectedTagIds = Set<int>.from(_selectedTagIds);
    final selectedYears = Set<String>.from(_selectedYears);
    final selectedStatus = _selectedStatus;
    final selectedType = _selectedType;
    final andMode = _isAndMode;
    final sortKeys = List<String>.from(_selectedSortKeys);
    final sortDirections = Map<String, String>.from(_sortDirections);
    final defaultMode =
        selectedStatus == '全部' &&
        selectedType == 'all' &&
        query.isEmpty &&
        selectedTagIds.isEmpty &&
        selectedYears.isEmpty;

    _isLoading = !_hasLoadedOnce;
    _isRefreshing = _hasLoadedOnce;
    _loadError = null;
    notifyListeners();

    try {
      // 1. 获取所有自定义状态并注入
      final dbStatuses = await _watchRepo.getAllStatuses();
      final List<String> statusOptions;
      final Map<String, Color> statusColors;
      if (dbStatuses.isNotEmpty) {
        statusOptions = ['全部', ...dbStatuses.map((e) => e['name'] as String)];
        statusColors = {
          for (var s in dbStatuses)
            s['name'] as String: Color(s['color'] as int),
        };
      } else {
        statusOptions = ['全部', '未看', '在看', '看完', '弃坑'];
        statusColors = {
          '未看': Colors.orange,
          '在看': Colors.blue,
          '看完': Colors.green,
          '弃坑': Colors.red,
        };
      }

      // 2. 标签数据
      final tags = await _tagRepo.getAllTags();

      // 3. 构建 SQL 排序子句
      String sortSql;
      if (sortKeys.isEmpty) {
        sortSql = 'a.id DESC';
      } else {
        List<String> sqlParts = sortKeys.where((key) => key != '拼音').map((key) {
          String field = _sortDefinitions[key]!;
          String dir = sortDirections[key] ?? 'DESC';
          return '$field $dir';
        }).toList();
        if (!_sortSqlContainsId(sqlParts)) sqlParts.add('a.id DESC');
        sortSql = sqlParts.join(', ');
      }

      // 4. 检索符合条件的番剧记录
      final searchResults = await _animeRepo.searchAnimes(
        query: query,
        tagIds: selectedTagIds.toList(),
        status: selectedStatus,
        subjectType: selectedType,
        years: selectedYears.toList(),
        isAndMode: andMode,
        sortOption: sortSql,
      );

      // 5. 提取不重复的年份
      final allAnimes = await _animeRepo.queryAllAnimes();
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
      years.sort((a, b) => b.compareTo(a));

      // 6. 系列聚合逻辑
      final bool isSearching = query.isNotEmpty;
      final bool showSeries = defaultMode;

      List<Map<String, dynamic>> finalDisplayList = [];

      if (showSeries || isSearching) {
        List<Map<String, dynamic>> targetSeries = [];
        if (isSearching) {
          targetSeries = await _seriesRepo.searchSeriesMaps(query);
        } else {
          targetSeries = await _seriesRepo.getAllSeriesMaps();
        }

        List<Map<String, dynamic>> seriesItems = [];
        for (var s in targetSeries) {
          final seriesId = s['id'];
          final inSeries = await _seriesRepo.getAnimeMapsInSeries(seriesId);

          if (inSeries.isNotEmpty) {
            Map<String, dynamic> seriesItem = Map.from(s);
            seriesItem['type'] = 'series';
            seriesItem['anime_count'] = inSeries.length;
            seriesItem['cover_url'] =
                s['custom_cover_url'] ?? inSeries.last['cover_url'];

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
                .map(animeRatingSortValue)
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

        List<Map<String, dynamic>> animeItems = [];
        Iterable<Map<String, dynamic>> filteredAnimes = isSearching
            ? searchResults
            : searchResults.where((a) => a['series_id'] == null);

        animeItems = filteredAnimes.map((a) {
          Map<String, dynamic> item = Map.from(a);
          item['type'] = 'anime';
          item['max_id'] = a['id'];
          item['max_air_date'] = a['air_date'];
          item['min_air_date'] = a['air_date'];
          item['max_finish_date'] = a['watch_finish_date'];
          item['max_rating'] = animeRatingSortValue(a);
          item['max_episodes'] = a['total_episodes'] ?? 0;
          return item;
        }).toList();

        finalDisplayList = [...seriesItems, ...animeItems];

        final sortKey = sortKeys.isNotEmpty ? sortKeys.first : '默认 (最新添加)';
        final direction = sortDirections[sortKey] ?? 'DESC';

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

          if (cmp == 0) {
            int idA = a['max_id'] ?? 0;
            int idB = b['max_id'] ?? 0;
            cmp = (direction == 'DESC')
                ? idB.compareTo(idA)
                : idA.compareTo(idB);
          }
          return cmp;
        });
      } else {
        finalDisplayList = searchResults.map((a) {
          Map<String, dynamic> item = Map.from(a);
          item['type'] = 'anime';
          return item;
        }).toList();

        final sortKey = sortKeys.isNotEmpty ? sortKeys.first : '默认 (最新添加)';
        if (sortKey == '拼音') {
          final direction = sortDirections[sortKey] ?? 'ASC';
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

      if (requestId != _refreshGeneration) return;

      _statusOptions = statusOptions;
      _statusColors = statusColors;
      _allTags = tags;
      _allAnimesData = finalDisplayList;
      _totalItemCount = searchResults.length;
      _animes = finalDisplayList.length > _pageSize
          ? finalDisplayList.sublist(0, _pageSize)
          : List.from(finalDisplayList);
      _hasMoreData = finalDisplayList.length > _pageSize;
      _availableYears = years;
      _hasLoadedOnce = true;
      _isLoading = false;
      _isRefreshing = false;
      _loadError = null;
      notifyListeners();
    } catch (error) {
      if (requestId != _refreshGeneration) return;
      _isLoading = false;
      _isRefreshing = false;
      _loadError = error;
      notifyListeners();
    }
  }

  void loadMoreItems() {
    if (_isLoadingMore || !_hasMoreData) return;
    _isLoadingMore = true;
    notifyListeners();

    final currentLen = _animes.length;
    final nextEnd = (currentLen + _pageSize).clamp(0, _allAnimesData.length);
    if (nextEnd > currentLen) {
      _animes = _allAnimesData.sublist(0, nextEnd);
      _hasMoreData = nextEnd < _allAnimesData.length;
    }
    _isLoadingMore = false;
    notifyListeners();
  }

  // --- 辅助方法 ---
  bool _sameSet<T>(Set<T> left, Set<T> right) {
    return left.length == right.length && left.containsAll(right);
  }

  bool isInDefaultMode() {
    return _selectedStatus == '全部' &&
        _selectedType == 'all' &&
        _searchQuery.trim().isEmpty &&
        _selectedTagIds.isEmpty &&
        _selectedYears.isEmpty;
  }

  String _normalizeDuplicateTitle(String title) {
    return title
        .trim()
        .toLowerCase()
        .replaceAll(RegExp(r'\s+'), '')
        .replaceAll('　', '');
  }

  bool _sortSqlContainsId(List<String> parts) {
    for (var part in parts) {
      if (part.contains('a.id')) return true;
    }
    return false;
  }

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

  @override
  void dispose() {
    _refreshGeneration++;
    _debounce?.cancel();
    _dataSubscription?.cancel();
    super.dispose();
  }
}
