import 'package:provider/provider.dart';
import 'providers/data_refresh_provider.dart';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'add_anime_page.dart';
import 'ui/neumorphic_style.dart';
import 'settings/server_discovery_page.dart';
import 'settings_manager.dart';
import 'utils/api_config.dart';

class ServerSearchPage extends StatefulWidget {
  final VoidCallback? onAnimeAdded;
  const ServerSearchPage({super.key, this.onAnimeAdded});

  @override
  State<ServerSearchPage> createState() => ServerSearchPageState();
}

class ServerSearchPageState extends State<ServerSearchPage>
    with AutomaticKeepAliveClientMixin {
  final TextEditingController _searchController = TextEditingController();
  final ScrollController _scrollController = ScrollController();

  List<dynamic> _results = [];
  bool _isLoading = false;
  String _currentKeyword = '';

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted)
        context.read<DataRefreshProvider>().addListener(_onGlobalRefresh);
    });
    _scrollController.addListener(_onScroll);
  }

  @override
  void _onGlobalRefresh() {
    if (mounted) refreshData();
  }

  @override
  void dispose() {
    context.read<DataRefreshProvider>().removeListener(_onGlobalRefresh);

    _searchController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _onScroll() {
    // 如果需要分页，可以在此处理，但目前 API 似乎返回全量结果
  }

  Future<void> _performSearch() async {
    final keyword = _searchController.text.trim();
    if (keyword.isEmpty) return;

    setState(() {
      _isLoading = true;
      _currentKeyword = keyword;
    });

    try {
      final url = Uri.parse(
        'http://47.103.83.247:3000/api/search?keyword=${Uri.encodeComponent(keyword)}',
      );
      // 发送请求时带上 Token
      final response = await http.get(
        url,
        headers: {'Authorization': 'Bearer ${ApiConfig.apiToken}'},
      );

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        if (data is Map && data['data'] is List) {
          if (mounted) {
            setState(() {
              _results = data['data'];
            });
          }
        } else if (data is List) {
          if (mounted) {
            setState(() {
              _results = data;
            });
          }
        }
      } else {
        throw Exception('Server error: ${response.statusCode}');
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('搜索失败: $e')));
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
    if (_currentKeyword.isNotEmpty) {
      _performSearch();
    }
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    return Scaffold(
      appBar: AppBar(
        title: const Text('资源库搜索'),
        actions: [
          ValueListenableBuilder<bool>(
            valueListenable: SettingsManager().showServerDiscoveryNotifier,
            builder: (context, show, _) {
              if (!show) return const SizedBox.shrink();
              return IconButton(
                icon: const Icon(Icons.explore_outlined),
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
          preferredSize: const Size.fromHeight(60),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
            child: TextField(
              controller: _searchController,
              decoration: InputDecoration(
                hintText: '搜索服务器资源...',
                prefixIcon: const Icon(Icons.search),
                suffixIcon: IconButton(
                  icon: const Icon(Icons.send),
                  onPressed: _performSearch,
                ),
                filled: true,
                fillColor: Theme.of(
                  context,
                ).colorScheme.surfaceVariant.withValues(alpha: 0.3),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(48),
                  borderSide: BorderSide.none,
                ),
                contentPadding: const EdgeInsets.symmetric(horizontal: 20),
              ),
              onSubmitted: (_) => _performSearch(),
            ),
          ),
        ),
      ),
      body: Column(
        children: [
          Expanded(child: _buildBody()),
          Container(
            padding: const EdgeInsets.symmetric(vertical: 8),
            alignment: Alignment.center,
            child: Text(
              '本次最新数据截止到2026年3月4日',
              style: TextStyle(fontSize: 12, color: Colors.grey[500]),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildBody() {
    if (_isLoading && _results.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }

    if (_results.isEmpty) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.cloud_off, size: 64, color: Colors.grey[400]),
            const SizedBox(height: 16),
            Text(
              _currentKeyword.isEmpty ? '输入关键词开始搜索' : '未找到相关资源',
              style: TextStyle(color: Colors.grey[600]),
            ),
          ],
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: _performSearch,
      child: GridView.builder(
        controller: _scrollController,
        padding: const EdgeInsets.all(8),
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 3,
          childAspectRatio: 0.7,
          crossAxisSpacing: 8,
          mainAxisSpacing: 8,
        ),
        itemCount: _results.length,
        itemBuilder: (context, index) {
          final item = _results[index];
          return _buildResultCard(item);
        },
      ),
    );
  }

  Widget _buildResultCard(dynamic item) {
    // 优先显示中文标题
    final title =
        (item['name'] ?? item['name_cn'] ?? item['name_original'] ?? '未知标题')
            .toString();
    final coverUrl = item['cover_url'] ?? item['image'];
    final airDate = item['air_date'];
    final eps = item['eps'];

    return GestureDetector(
      onTap: () {
        Navigator.push(
          context,
          MaterialPageRoute(
            builder: (context) => AddAnimePage(
              initialSearchQuery: title,
              isDiscoveryMode: true,
              existingAnime: item,
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
        borderRadius: 24,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(
              child: Stack(
                fit: StackFit.expand,
                children: [
                  coverUrl != null && coverUrl.toString().isNotEmpty
                      ? Image.network(
                          coverUrl,
                          fit: BoxFit.cover,
                          errorBuilder: (ctx, err, stack) => Container(
                            color: Colors.grey[200],
                            child: const Icon(
                              Icons.broken_image,
                              color: Colors.grey,
                            ),
                          ),
                        )
                      : Container(
                          color: Colors.grey[200],
                          child: const Icon(
                            Icons.movie,
                            size: 40,
                            color: Colors.grey,
                          ),
                        ),
                  if (eps != null)
                    Positioned(
                      right: 4,
                      bottom: 4,
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 6,
                          vertical: 2,
                        ),
                        decoration: BoxDecoration(
                          color: Colors.black.withValues(alpha: 0.6),
                          borderRadius: BorderRadius.circular(4),
                        ),
                        child: Text(
                          '$eps 集',
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 10,
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(8.0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  if (airDate != null)
                    Text(
                      airDate.toString(),
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
