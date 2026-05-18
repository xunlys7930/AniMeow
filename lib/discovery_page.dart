import 'package:provider/provider.dart';
import 'providers/data_refresh_provider.dart';
import 'package:flutter/material.dart';
import 'api/bangumi_service.dart';
import 'add_anime_page.dart';
import 'ui/neumorphic_style.dart';
import 'settings/server_discovery_page.dart';
import 'settings_manager.dart';

class DiscoveryPage extends StatefulWidget {
  final VoidCallback? onAnimeAdded;
  const DiscoveryPage({super.key, this.onAnimeAdded});

  @override
  State<DiscoveryPage> createState() => DiscoveryPageState();
}

class DiscoveryPageState extends State<DiscoveryPage>
    with AutomaticKeepAliveClientMixin {
  // 热门标签列表
  // 标签分类数据
  // 标签分类数据
  late Map<String, List<String>> _tagCategories;

  // 当前选中的分类
  String _selectedCategory = '年份';
  // 当前选中的标签
  late String _selectedTag;
  List<BangumiSearchResult> _results = [];
  bool _isLoading = false;
  int _offset = 0;
  bool _hasMore = true;
  final ScrollController _scrollController = ScrollController();

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted)
        context.read<DataRefreshProvider>().addListener(_onGlobalRefresh);
    });
    _initTags();
    _fetchData();
    _scrollController.addListener(_onScroll);
  }

  void _initTags() {
    final currentYear = DateTime.now().year;
    final years = <String>[];
    // 生成从明年到2010年的年份列表
    for (int i = 0; i < 15; i++) {
      years.add((currentYear + 1 - i).toString()); // 明年, 今年, 去年...
    }
    years.addAll(['2000s', '1990s']);

    _tagCategories = {
      '年份': years,
      '季度': ['1月', '4月', '7月', '10月'],
      '类型': ['TV', '剧场版', 'OVA', 'Web', 'SP'],
      '地区': ['日本', '中国', '欧美'],
      '风格': [
        '搞笑',
        '恋爱',
        '科幻',
        '奇幻',
        '战斗',
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
      ],
      '改编': ['小说改', '漫画改', '游戏改', '原创'],
    };

    // 默认选中当前年份
    _selectedTag = currentYear.toString();
  }

  @override
  void _onGlobalRefresh() {
    if (mounted) refreshData();
  }

  @override
  void dispose() {
    context.read<DataRefreshProvider>().removeListener(_onGlobalRefresh);

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
    if (_isLoading) return;

    // 如果是有数据且是静默刷新（通常由外部 globalRefresh 触发），则直接返回，保持现状
    if (!loadMore && isSilentRefresh && _results.isNotEmpty) {
      return;
    }

    setState(() {
      // 只有在切换标签或手动刷新时重置偏移
      if (!loadMore) {
        // 如果不是手动刷新，且不是静默加载，则说明是切换分类/标签，此时清空列表以显示加载态
        if (!isUserRefresh && !isSilentRefresh) {
          _results = [];
        }
        _offset = 0;
        _hasMore = true;
      }
      _isLoading = true;
    });

    try {
      final newResults = await BangumiService.searchByTag(
        _selectedTag,
        offset: _offset,
        limit: 20,
      );

      if (mounted) {
        setState(() {
          if (newResults.isEmpty) {
            _hasMore = false;
          } else {
            if (loadMore) {
              _results.addAll(newResults);
            } else {
              _results = newResults; // 刷新时直接替换
            }
            _offset += newResults.length;
            if (newResults.length < 20) _hasMore = false;
          }
        });
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('加载失败: $e')));
      }
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  void refreshData() {
    _fetchData(isSilentRefresh: true);
  }

  void _onTagSelected(String tag) {
    if (_selectedTag == tag) return;
    setState(() {
      _selectedTag = tag;
    });
    _fetchData();
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('发现番剧'),
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
          const SizedBox(width: 8),
        ],
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(90),
          child: Column(
            children: [
              // 1. 第一行：分类选择
              SizedBox(
                height: 36,
                child: ListView.separated(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  scrollDirection: Axis.horizontal,
                  itemCount: _tagCategories.keys.length,
                  separatorBuilder: (context, index) =>
                      const SizedBox(width: 8),
                  itemBuilder: (context, index) {
                    final category = _tagCategories.keys.elementAt(index);
                    final isSelected = category == _selectedCategory;
                    return InkWell(
                      onTap: () {
                        setState(() {
                          _selectedCategory = category;
                          // 切换分类时，默认选中该分类下的第一个标签
                          _selectedTag = _tagCategories[category]!.first;
                        });
                        _fetchData();
                      },
                      borderRadius: BorderRadius.circular(18),
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 16,
                          vertical: 8,
                        ),
                        decoration: BoxDecoration(
                          color: isSelected
                              ? Theme.of(
                                  context,
                                ).colorScheme.primary.withValues(alpha: 0.1)
                              : Colors.transparent,
                          borderRadius: BorderRadius.circular(18),
                        ),
                        child: Text(
                          category,
                          style: TextStyle(
                            color: isSelected
                                ? Theme.of(context).colorScheme.primary
                                : Colors.grey[700],
                            fontWeight: isSelected
                                ? FontWeight.bold
                                : FontWeight.normal,
                          ),
                        ),
                      ),
                    );
                  },
                ),
              ),
              const SizedBox(height: 8),
              // 2. 第二行：具体标签
              Container(
                height: 40,
                padding: const EdgeInsets.symmetric(horizontal: 8),
                margin: const EdgeInsets.only(bottom: 4),
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  itemCount: _tagCategories[_selectedCategory]?.length ?? 0,
                  separatorBuilder: (context, index) =>
                      const SizedBox(width: 8),
                  itemBuilder: (context, index) {
                    final tag = _tagCategories[_selectedCategory]![index];
                    final isSelected = tag == _selectedTag;
                    return ChoiceChip(
                      label: Text(tag),
                      selected: isSelected,
                      onSelected: (_) => _onTagSelected(tag),
                      selectedColor: Theme.of(
                        context,
                      ).colorScheme.primaryContainer,
                      labelStyle: TextStyle(
                        color: isSelected
                            ? Theme.of(context).colorScheme.onPrimaryContainer
                            : null,
                        fontSize: 12,
                      ),
                      visualDensity: VisualDensity.compact,
                    );
                  },
                ),
              ),
            ],
          ),
        ),
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_results.isEmpty && !_isLoading) {
      return const Center(child: Text('暂无数据'));
    }

    return RefreshIndicator(
      onRefresh: () => _fetchData(isUserRefresh: true),
      child: GridView.builder(
        controller: _scrollController,
        padding: const EdgeInsets.all(8),
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 3,
          childAspectRatio: 0.7,
          crossAxisSpacing: 8,
          mainAxisSpacing: 8,
        ),
        itemCount: _results.length + (_hasMore ? 1 : 0),
        itemBuilder: (context, index) {
          if (index == _results.length) {
            return const Center(child: CircularProgressIndicator());
          }
          final anime = _results[index];
          return _buildAnimeCard(anime);
        },
      ),
    );
  }

  Widget _buildAnimeCard(BangumiSearchResult anime) {
    return GestureDetector(
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
      child: NeumorphicContainer(
        padding: EdgeInsets.zero,
        borderRadius: 24, // 针对网格项稍微减小一点以防过圆
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: anime.coverUrl != null
                  ? Image.network(
                      anime.coverUrl!,
                      fit: BoxFit.cover,
                      errorBuilder: (ctx, err, stack) => Container(
                        color: Colors.grey[200],
                        child: const Icon(Icons.broken_image),
                      ),
                    )
                  : Container(
                      color: Colors.grey[200],
                      child: const Icon(Icons.movie),
                    ),
            ),
            Padding(
              padding: const EdgeInsets.all(4.0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    anime.nameCn.isNotEmpty ? anime.nameCn : anime.nameOriginal,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  if (anime.airDate != null)
                    Text(
                      anime.airDate!,
                      style: TextStyle(fontSize: 10, color: Colors.grey[600]),
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
