import 'package:flutter/material.dart';
import '../api/bangumi_service.dart';
import '../ui/neumorphic_style.dart';
import '../ui/pages/add_anime_page.dart';
import 'server_discovery_season.dart';
import 'package:cached_network_image/cached_network_image.dart';

class ServerDiscoveryPage extends StatefulWidget {
  const ServerDiscoveryPage({super.key});

  @override
  State<ServerDiscoveryPage> createState() => _ServerDiscoveryPageState();
}

class _ServerDiscoveryPageState extends State<ServerDiscoveryPage> {
  final TextEditingController _searchController = TextEditingController();
  List<BangumiSearchResult> _animes = [];
  bool _isLoading = false;
  String? _selectedTag;
  String? _selectedYear;
  String? _selectedMonth;
  String? _selectedType;

  // 预定义的标签和年份（也可以从服务器获取，但这里先硬编码常用的）
  final List<String> _tags = [
    "全部",
    "原创",
    "漫画改",
    "小说改",
    "游戏改",
    "热血",
    "致郁",
    "恋爱",
    "校园",
    "日常",
  ];
  late final List<String> _years = [
    "全部",
    for (var year = DateTime.now().year; year >= 2018; year--) "$year",
  ];
  final List<String> _months = ["全部", "1", "4", "7", "10"];
  final List<String> _types = ["全部", "TV", "OVA", "剧场版", "Web", "SP"];

  @override
  void initState() {
    super.initState();
    _fetchData();
  }

  Future<void> _fetchData() async {
    setState(() => _isLoading = true);
    final selectedYear = _selectedYear == "全部" ? null : _selectedYear;
    final selectedSeasonMonth = _selectedMonth == "全部"
        ? null
        : int.tryParse(_selectedMonth ?? '');
    final queryYears = _queryYearsForSeason(selectedYear, selectedSeasonMonth);
    final resultGroups = await Future.wait(
      queryYears.map(
        (year) => BangumiService.getServerAnimes(
          keyword: _searchController.text,
          tag: _selectedServerTagQuery(),
          year: year,
        ),
      ),
    );
    final results = _filterResultsBySeason(
      _deduplicateResults(resultGroups.expand((group) => group)),
      selectedSeasonMonth: selectedSeasonMonth,
      selectedYear: selectedYear,
    );
    if (mounted) {
      setState(() {
        _animes = results;
        _isLoading = false;
      });
    }
  }

  String? _selectedServerTagQuery() {
    if (_selectedTag == "全部") return null;
    // 如果选中了类型，则合并标签和类型进行查询
    if (_selectedType != null &&
        _selectedType != "全部" &&
        _selectedTag != null &&
        _selectedTag != "全部") {
      return "$_selectedTag,$_selectedType";
    }
    return _selectedTag;
  }

  List<String?> _queryYearsForSeason(String? selectedYear, int? seasonMonth) {
    if (selectedYear == null || selectedYear.isEmpty) return [null];
    if (seasonMonth != 1) return [selectedYear];

    final parsedYear = int.tryParse(selectedYear);
    if (parsedYear == null) return [selectedYear];
    return [selectedYear, '${parsedYear - 1}'];
  }

  List<BangumiSearchResult> _deduplicateResults(
    Iterable<BangumiSearchResult> results,
  ) {
    final seen = <String>{};
    final deduplicated = <BangumiSearchResult>[];

    for (final result in results) {
      final key = '${result.source}:${result.id}:${result.nameCn}';
      if (seen.add(key)) deduplicated.add(result);
    }

    return deduplicated;
  }

  List<BangumiSearchResult> _filterResultsBySeason(
    List<BangumiSearchResult> results, {
    required int? selectedSeasonMonth,
    required String? selectedYear,
  }) {
    if (selectedSeasonMonth == null) return results;

    final seasonYear = selectedYear == null ? null : int.tryParse(selectedYear);
    return results
        .where(
          (anime) => isAirDateInServerDiscoverySeason(
            anime.airDate,
            seasonMonth: selectedSeasonMonth,
            seasonYear: seasonYear,
          ),
        )
        .toList();
  }

  @override
  Widget build(BuildContext context) {
    final themeColor = Theme.of(context).primaryColor;

    return Scaffold(
      backgroundColor: const Color(0xFFF0F2F5),
      appBar: AppBar(
        title: const Text(
          '云端资源发现',
          style: TextStyle(fontWeight: FontWeight.bold),
        ),
        centerTitle: true,
        backgroundColor: Colors.transparent,
        elevation: 0,
      ),
      body: Column(
        children: [
          _buildSearchBar(themeColor),
          _buildFilterScroll(themeColor),
          Expanded(
            child: _isLoading
                ? const Center(child: CircularProgressIndicator())
                : _animes.isEmpty
                ? _buildEmptyState()
                : _buildGrid(),
          ),
        ],
      ),
    );
  }

  Widget _buildSearchBar(Color themeColor) {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: NeumorphicContainer(
        borderRadius: 48,
        padding: const EdgeInsets.symmetric(horizontal: 20),
        child: TextField(
          controller: _searchController,
          decoration: InputDecoration(
            hintText: '搜索云端番剧...',
            border: InputBorder.none,
            icon: Icon(Icons.search, color: themeColor),
            suffixIcon: _searchController.text.isNotEmpty
                ? IconButton(
                    icon: const Icon(Icons.clear),
                    onPressed: () {
                      _searchController.clear();
                      _fetchData();
                    },
                  )
                : null,
          ),
          onSubmitted: (_) => _fetchData(),
        ),
      ),
    );
  }

  Widget _buildFilterScroll(Color themeColor) {
    return Column(
      children: [
        _buildSingleFilterScroll(
          label: "分类",
          items: _tags,
          selectedItem: _selectedTag ?? "全部",
          onSelected: (val) {
            setState(() => _selectedTag = val);
            _fetchData();
          },
          themeColor: themeColor,
        ),
        _buildSingleFilterScroll(
          label: "格式",
          items: _types,
          selectedItem: _selectedType ?? "全部",
          onSelected: (val) {
            setState(() => _selectedType = val);
            _fetchData();
          },
          themeColor: themeColor,
        ),
        _buildSingleFilterScroll(
          label: "年份",
          items: _years,
          selectedItem: _selectedYear ?? "全部",
          onSelected: (val) {
            setState(() => _selectedYear = val);
            _fetchData();
          },
          themeColor: themeColor,
        ),
        _buildSingleFilterScroll(
          label: "月份",
          items: _months,
          selectedItem: _selectedMonth ?? "全部",
          onSelected: (val) {
            setState(() => _selectedMonth = val);
            _fetchData();
          },
          themeColor: themeColor,
        ),
      ],
    );
  }

  Widget _buildSingleFilterScroll({
    required String label,
    required List<String> items,
    required String selectedItem,
    required Function(String) onSelected,
    required Color themeColor,
  }) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Padding(
            padding: const EdgeInsets.only(left: 16, right: 8),
            child: Text(
              "$label:",
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.bold,
                color: Colors.grey[600],
              ),
            ),
          ),
          Expanded(
            child: SizedBox(
              height: 40,
              child: ListView.builder(
                scrollDirection: Axis.horizontal,
                itemCount: items.length,
                itemBuilder: (context, index) {
                  final item = items[index];
                  final isSelected = item == selectedItem;
                  return Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 4,
                      vertical: 4,
                    ),
                    child: InkWell(
                      onTap: () => onSelected(item),
                      borderRadius: BorderRadius.circular(20),
                      child: NeumorphicContainer(
                        borderRadius: 20,
                        padding: const EdgeInsets.symmetric(horizontal: 12),
                        isPressed: isSelected,
                        color: isSelected
                            ? themeColor.withValues(alpha: 0.1)
                            : null,
                        child: Center(
                          child: Text(
                            item,
                            style: TextStyle(
                              fontSize: 11,
                              fontWeight: isSelected
                                  ? FontWeight.bold
                                  : FontWeight.normal,
                              color: isSelected ? themeColor : Colors.black87,
                            ),
                          ),
                        ),
                      ),
                    ),
                  );
                },
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildGrid() {
    return GridView.builder(
      padding: const EdgeInsets.all(12),
      gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
        crossAxisCount: 5,
        childAspectRatio: 0.6,
        crossAxisSpacing: 8,
        mainAxisSpacing: 12,
      ),
      itemCount: _animes.length,
      itemBuilder: (context, index) {
        final anime = _animes[index];
        return _buildAnimeCard(anime);
      },
    );
  }

  Widget _buildAnimeCard(BangumiSearchResult anime) {
    return InkWell(
      onTap: () {
        Navigator.push(
          context,
          MaterialPageRoute(
            builder: (context) => AddAnimePage(
              existingAnime: {
                'api_id': anime.id,
                'title': anime.nameCn,
                'name_cn': anime.nameCn,
                'name': anime.nameOriginal,
                'image': anime.coverUrl,
                'cover_url': anime.coverUrl,
                'air_date': anime.airDate,
                'total_eps': anime.eps,
                'summary': anime.summary,
                'studio': anime.studio,
                'score': anime.score,
                'rating': anime.score,
                'source': anime.source,
              },
              isDiscoveryMode: true,
            ),
          ),
        );
      },
      borderRadius: BorderRadius.circular(16),
      child: NeumorphicContainer(
        borderRadius: 16,
        padding: const EdgeInsets.all(4),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: ClipRRect(
                borderRadius: BorderRadius.circular(12),
                child: Hero(
                  tag: 'server_anime_${anime.id}',
                  child: CachedNetworkImage(
                    imageUrl: anime.coverUrl ?? '',
                    fit: BoxFit.cover,
                    width: double.infinity,
                    placeholder: (context, url) =>
                        Container(color: Colors.grey[200]),
                    errorWidget: (context, url, error) =>
                        const Icon(Icons.broken_image, size: 20),
                  ),
                ),
              ),
            ),
            const SizedBox(height: 4),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 2),
              child: Text(
                anime.nameCn,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontWeight: FontWeight.bold,
                  fontSize: 10,
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 2, vertical: 1),
              child: Row(
                children: [
                  if (anime.score != null) ...[
                    const Icon(Icons.star, color: Colors.orange, size: 8),
                    const SizedBox(width: 1),
                    Text(
                      anime.score!.toStringAsFixed(1),
                      style: const TextStyle(
                        fontSize: 8,
                        color: Colors.orange,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const Spacer(),
                  ],
                  Text(
                    anime.airDate?.split('-')[0] ?? '',
                    style: TextStyle(fontSize: 8, color: Colors.grey[500]),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildEmptyState() {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.search_off, size: 64, color: Colors.grey[300]),
          const SizedBox(height: 16),
          Text('没找到相关番剧呢', style: TextStyle(color: Colors.grey[400])),
        ],
      ),
    );
  }
}
