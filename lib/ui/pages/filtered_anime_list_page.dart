import 'dart:io';
import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:path/path.dart' as path;
import 'package:cached_network_image/cached_network_image.dart';
import 'package:anime_tracker/db/database_helper.dart';
import 'package:anime_tracker/ui/anime_detail/anime_detail_page.dart';
import 'package:anime_tracker/ui/views/_shared/rating_icon.dart';
import 'package:anime_tracker/settings_manager.dart';
import 'package:anime_tracker/utils/anime_rating.dart';

/// 独立筛选番剧列表页面
/// 用于在统计页面点击标签或状态时展示对应的番剧列表
class FilteredAnimeListPage extends StatefulWidget {
  final int? tagId;
  final String? tagName;
  final String? status;

  const FilteredAnimeListPage({
    super.key,
    this.tagId,
    this.tagName,
    this.status,
  });

  @override
  State<FilteredAnimeListPage> createState() => _FilteredAnimeListPageState();
}

class _FilteredAnimeListPageState extends State<FilteredAnimeListPage> {
  List<Map<String, dynamic>> _animes = [];
  bool _isLoading = true;
  Directory? _appDocDir;
  Map<String, Color> _statusColors = {};

  @override
  void initState() {
    super.initState();
    _initAppDir();
    _loadData();
  }

  Future<void> _initAppDir() async {
    final dir = await getApplicationDocumentsDirectory();
    if (mounted) {
      setState(() {
        _appDocDir = dir;
      });
    }
  }

  Future<void> _loadData() async {
    setState(() => _isLoading = true);

    // 加载状态颜色
    final dbStatuses = await DatabaseHelper().getAllStatuses();
    if (dbStatuses.isNotEmpty) {
      _statusColors = {
        for (var s in dbStatuses) s['name'] as String: Color(s['color'] as int),
      };
    } else {
      _statusColors = {
        '未看': Colors.orange,
        '在看': Colors.blue,
        '看完': Colors.green,
        '弃坑': Colors.red,
      };
    }

    // 加载番剧数据
    final animes = await DatabaseHelper().searchAnimes(
      tagIds: widget.tagId != null ? [widget.tagId!] : null,
      status: widget.status,
      sortOption: 'a.id DESC',
    );

    if (mounted) {
      setState(() {
        _animes = animes;
        _isLoading = false;
      });
    }
  }

  String get _title {
    if (widget.tagName != null) return '# ${widget.tagName}';
    if (widget.status != null) return widget.status!;
    return '番剧列表';
  }

  @override
  Widget build(BuildContext context) {
    final columns = SettingsManager().gridColumnsNotifier.value;

    return Scaffold(
      appBar: AppBar(
        title: Text(_title),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 16),
            child: Center(
              child: Text(
                '${_animes.length} 部',
                style: TextStyle(
                  color: Theme.of(context).primaryColor,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
          ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _animes.isEmpty
          ? Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.inbox_outlined, size: 64, color: Colors.grey[300]),
                  const SizedBox(height: 16),
                  Text(
                    '暂无番剧',
                    style: TextStyle(fontSize: 16, color: Colors.grey[400]),
                  ),
                ],
              ),
            )
          : GridView.builder(
              padding: const EdgeInsets.all(12),
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: columns,
                crossAxisSpacing: 10,
                mainAxisSpacing: 10,
                childAspectRatio: 0.55,
              ),
              itemCount: _animes.length,
              itemBuilder: (context, index) {
                return _buildGridItem(_animes[index]);
              },
            ),
    );
  }

  Widget _buildGridItem(Map<String, dynamic> anime) {
    final coverBorderRadius = SettingsManager().coverBorderRadiusNotifier.value;

    return GestureDetector(
      onTap: () async {
        final result = await Navigator.push(
          context,
          MaterialPageRoute(
            builder: (context) => AnimeDetailPage(existingAnime: anime),
          ),
        );
        if (result == true) _loadData();
      },
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Container(
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(coverBorderRadius),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.1),
                    blurRadius: 4,
                    offset: const Offset(0, 2),
                  ),
                ],
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(coverBorderRadius),
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    _buildCoverImage(anime['cover_url'], context),
                    // 左上角状态标签
                    ValueListenableBuilder<bool>(
                      valueListenable: SettingsManager().showStatusNotifier,
                      builder: (context, showStatus, _) {
                        if (!showStatus) return const SizedBox();
                        return Positioned(
                          top: 6,
                          left: 6,
                          child: Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 6,
                              vertical: 2,
                            ),
                            decoration: BoxDecoration(
                              color:
                                  (_statusColors[anime['status']] ??
                                          Colors.grey)
                                      .withValues(alpha: 0.85),
                              borderRadius: BorderRadius.circular(4),
                            ),
                            child: Text(
                              anime['status'] ?? '',
                              style: const TextStyle(
                                color: Colors.white,
                                fontSize: 10,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                          ),
                        );
                      },
                    ),
                    // 右上角评分
                    ValueListenableBuilder<bool>(
                      valueListenable: SettingsManager().showRatingNotifier,
                      builder: (context, showRating, _) {
                        final rating = animeRatingOf(anime);
                        if (!showRating || !rating.hasValue) {
                          return const SizedBox();
                        }
                        return Positioned(
                          top: 6,
                          right: 6,
                          child: Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 4,
                              vertical: 2,
                            ),
                            decoration: BoxDecoration(
                              color: Colors.black.withValues(alpha: 0.6),
                              borderRadius: BorderRadius.circular(4),
                            ),
                            child: Row(
                              mainAxisSize: MainAxisSize.min,
                              children: [
                                const RatingIconWidget(size: 10),
                                const SizedBox(width: 2),
                                Text(
                                  rating.label,
                                  style: const TextStyle(
                                    color: Colors.white,
                                    fontSize: 10,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        );
                      },
                    ),
                  ],
                ),
              ),
            ),
          ),
          const SizedBox(height: 6),
          // 标题
          Text(
            anime['title'] ?? '未知标题',
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w500),
          ),
        ],
      ),
    );
  }

  Widget _buildCoverImage(String? url, BuildContext context) {
    final pixelRatio = MediaQuery.of(context).devicePixelRatio;

    if (url == null || url.isEmpty) {
      return Container(
        color: Colors.grey[200],
        child: const Icon(Icons.movie_creation, color: Colors.grey),
      );
    }

    if (url.startsWith('http')) {
      return CachedNetworkImage(
        imageUrl: url,
        memCacheWidth: (300 * pixelRatio).toInt(),
        fit: BoxFit.cover,
        placeholder: (context, url) => Container(
          color: Colors.grey[200],
          child: const Center(
            child: SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
          ),
        ),
        errorWidget: (context, url, error) => Container(
          color: Colors.grey[200],
          child: const Icon(Icons.broken_image, color: Colors.grey),
        ),
        fadeInDuration: const Duration(milliseconds: 300),
      );
    }

    File imageFile;
    if (path.isAbsolute(url)) {
      imageFile = File(url);
    } else {
      if (_appDocDir == null) {
        return Container(
          color: Colors.grey[200],
          child: const Center(
            child: SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
          ),
        );
      }
      imageFile = File(path.join(_appDocDir!.path, url));
    }

    return Image.file(
      imageFile,
      cacheWidth: (300 * pixelRatio).toInt(),
      fit: BoxFit.cover,
      errorBuilder: (_, __, ___) => Container(
        color: Colors.grey[200],
        child: const Icon(Icons.broken_image, color: Colors.grey),
      ),
    );
  }
}
