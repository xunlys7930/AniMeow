import 'dart:io';
import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:path_provider/path_provider.dart';
import 'package:path/path.dart' as path;
import 'package:anime_tracker/db/database_helper.dart';
import 'package:anime_tracker/settings_manager.dart';
import 'package:anime_tracker/ui/neumorphic_style.dart';

class SeriesManagementPage extends StatefulWidget {
  const SeriesManagementPage({super.key});

  @override
  State<SeriesManagementPage> createState() => _SeriesManagementPageState();
}

class _SeriesManagementPageState extends State<SeriesManagementPage> {
  List<Map<String, dynamic>> _series = [];
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _loadSeries();
  }

  Future<void> _loadSeries() async {
    setState(() => _isLoading = true);
    final data = await DatabaseHelper().getAllSeries();
    if (mounted) {
      setState(() {
        _series = data;
        _isLoading = false;
      });
    }
  }

  void _showAddSeriesDialog() {
    final nameController = TextEditingController();
    final descController = TextEditingController();

    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('新建系列'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: nameController,
              decoration: const InputDecoration(labelText: '系列名称'),
              autofocus: true,
            ),
            TextField(
              controller: descController,
              decoration: const InputDecoration(labelText: '描述 (可选)'),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () async {
              if (nameController.text.trim().isNotEmpty) {
                await DatabaseHelper().createSeries(
                  nameController.text.trim(),
                  description: descController.text.trim(),
                );
                Navigator.pop(ctx);
                _loadSeries();
              }
            },
            child: const Text('创建'),
          ),
        ],
      ),
    );
  }

  void _showEditSeriesDialog(Map<String, dynamic> series) {
    final nameController = TextEditingController(text: series['name']);
    final descController = TextEditingController(text: series['description']);

    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('编辑系列'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: nameController,
              decoration: const InputDecoration(labelText: '系列名称'),
            ),
            TextField(
              controller: descController,
              decoration: const InputDecoration(labelText: '描述'),
            ),
          ],
        ),
        actionsAlignment: MainAxisAlignment.spaceBetween,
        actions: [
          TextButton(
            onPressed: () async {
              final confirm = await showDialog<bool>(
                context: context,
                builder: (c) => AlertDialog(
                  title: const Text('确认删除'),
                  content: const Text('删除系列后，其中的番剧将变为独立状态。'),
                  actions: [
                    TextButton(
                      onPressed: () => Navigator.pop(c, false),
                      child: const Text('取消'),
                    ),
                    TextButton(
                      onPressed: () => Navigator.pop(c, true),
                      style: TextButton.styleFrom(foregroundColor: Colors.red),
                      child: const Text('删除系列'),
                    ),
                  ],
                ),
              );
              if (confirm == true) {
                await DatabaseHelper().deleteSeries(series['id']);
                if (mounted) {
                  Navigator.pop(ctx); // Close edit dialog
                  _loadSeries();
                }
              }
            },
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            child: const Text('删除系列'),
          ),
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextButton(
                onPressed: () => Navigator.pop(ctx),
                child: const Text('取消'),
              ),
              FilledButton(
                onPressed: () async {
                  if (nameController.text.trim().isNotEmpty) {
                    await DatabaseHelper().updateSeries(series['id'], {
                      'name': nameController.text.trim(),
                      'description': descController.text.trim(),
                    });
                    Navigator.pop(ctx);
                    _loadSeries();
                  }
                },
                child: const Text('保存'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  void _manageCover(Map<String, dynamic> series) async {
    // Navigate to cover selection page
    await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (context) => SeriesCoverSelectorPage(series: series),
      ),
    );
    _loadSeries();
  }

  Widget _buildCoverImage(String? url) {
    if (url == null || url.isEmpty) {
      return Container(
        width: 60,
        height: 80,
        color: Colors.grey[200],
        child: const Icon(Icons.collections_bookmark, color: Colors.grey),
      );
    }

    if (url.startsWith('http')) {
      return CachedNetworkImage(
        imageUrl: url,
        width: 60,
        height: 80,
        fit: BoxFit.cover,
        errorWidget: (_, __, ___) => Container(color: Colors.grey[200]),
      );
    }

    return FutureBuilder<Directory>(
      future: getApplicationDocumentsDirectory(),
      builder: (context, snapshot) {
        if (!snapshot.hasData) {
          return Container(width: 60, height: 80, color: Colors.grey[200]);
        }
        final file = File(path.join(snapshot.data!.path, url));
        return Image.file(file, width: 60, height: 80, fit: BoxFit.cover);
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('系列管理')),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _series.isEmpty
          ? Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Icon(Icons.layers_clear, size: 64, color: Colors.grey),
                  const SizedBox(height: 16),
                  const Text('还没有创建任何系列', style: TextStyle(color: Colors.grey)),
                  const SizedBox(height: 24),
                  FilledButton.icon(
                    onPressed: _showAddSeriesDialog,
                    icon: const Icon(Icons.add),
                    label: const Text('新建系列'),
                  ),
                ],
              ),
            )
          : ListView.builder(
              padding: const EdgeInsets.all(12),
              itemCount: _series.length,
              itemBuilder: (context, index) {
                final item = _series[index];
                final coverUrl =
                    item['custom_cover_url'] ?? item['default_cover_url'];
                final coverBorderRadius =
                    SettingsManager().coverBorderRadiusNotifier.value;
                return NeumorphicContainer(
                  margin: const EdgeInsets.only(bottom: 12),
                  child: InkWell(
                    onTap: () => _showEditSeriesDialog(item),
                    child: Row(
                      children: [
                        ClipRRect(
                          borderRadius: BorderRadius.only(
                            topLeft: Radius.circular(coverBorderRadius),
                            bottomLeft: Radius.circular(coverBorderRadius),
                          ),
                          child: _buildCoverImage(coverUrl),
                        ),
                        const SizedBox(width: 16),
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                item['name'],
                                style: const TextStyle(
                                  fontWeight: FontWeight.bold,
                                  fontSize: 16,
                                ),
                              ),
                              if (item['description']?.isNotEmpty == true)
                                Text(
                                  item['description'],
                                  style: TextStyle(
                                    color: Colors.grey[600],
                                    fontSize: 12,
                                  ),
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                              Text(
                                '${item['anime_count'] ?? 0} 部番剧',
                                style: TextStyle(
                                  color: Colors.indigo[400],
                                  fontSize: 13,
                                ),
                              ),
                            ],
                          ),
                        ),
                        IconButton(
                          icon: const Icon(Icons.photo_library_outlined),
                          onPressed: () => _manageCover(item),
                          tooltip: '修改封面',
                        ),
                        const Icon(Icons.chevron_right, color: Colors.grey),
                        const SizedBox(width: 8),
                      ],
                    ),
                  ),
                );
              },
            ),
      floatingActionButton: FloatingActionButton(
        onPressed: _showAddSeriesDialog,
        child: const Icon(Icons.add),
      ),
    );
  }
}

class SeriesCoverSelectorPage extends StatefulWidget {
  final Map<String, dynamic> series;
  const SeriesCoverSelectorPage({super.key, required this.series});

  @override
  State<SeriesCoverSelectorPage> createState() =>
      _SeriesCoverSelectorPageState();
}

class _SeriesCoverSelectorPageState extends State<SeriesCoverSelectorPage> {
  List<Map<String, dynamic>> _animes = [];
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _loadAnimes();
  }

  Future<void> _loadAnimes() async {
    final list = await DatabaseHelper().getAnimesInSeries(widget.series['id']);
    if (mounted) {
      setState(() {
        _animes = list;
        _isLoading = false;
      });
    }
  }

  Widget _buildGridItem(Map<String, dynamic> anime) {
    final url = anime['cover_url'];
    final isSelected = widget.series['custom_cover_url'] == url;
    final coverBorderRadius = SettingsManager().coverBorderRadiusNotifier.value;

    return GestureDetector(
      onTap: () async {
        await DatabaseHelper().updateSeries(widget.series['id'], {
          'custom_cover_url': url,
        });
        if (mounted) Navigator.pop(context);
      },
      child: Stack(
        fit: StackFit.expand,
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(coverBorderRadius),
            child: url == null || url.isEmpty
                ? Container(color: Colors.grey[300])
                : url.startsWith('http')
                ? CachedNetworkImage(imageUrl: url, fit: BoxFit.cover)
                : FutureBuilder<Directory>(
                    future: getApplicationDocumentsDirectory(),
                    builder: (context, snapshot) {
                      if (!snapshot.hasData) {
                        return Container(color: Colors.grey[300]);
                      }
                      return Image.file(
                        File(path.join(snapshot.data!.path, url)),
                        fit: BoxFit.cover,
                      );
                    },
                  ),
          ),
          if (isSelected)
            Positioned.fill(
              child: Container(
                decoration: BoxDecoration(
                  color: Colors.indigo.withValues(alpha: 0.3),
                  borderRadius: BorderRadius.circular(coverBorderRadius),
                  border: Border.all(color: Colors.indigo, width: 3),
                ),
                child: const Icon(
                  Icons.check_circle,
                  color: Colors.white,
                  size: 32,
                ),
              ),
            ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('选择系列封面'),
        actions: [
          TextButton(
            onPressed: () async {
              await DatabaseHelper().updateSeries(widget.series['id'], {
                'custom_cover_url': null,
              });
              if (mounted) Navigator.pop(context);
            },
            child: const Text('重置默认', style: TextStyle(color: Colors.white)),
          ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : _animes.isEmpty
          ? const Center(child: Text('系列内侧目前没有番剧'))
          : GridView.builder(
              padding: const EdgeInsets.all(16),
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 3,
                crossAxisSpacing: 12,
                mainAxisSpacing: 12,
                childAspectRatio: 0.7,
              ),
              itemCount: _animes.length,
              itemBuilder: (context, index) => _buildGridItem(_animes[index]),
            ),
    );
  }
}
