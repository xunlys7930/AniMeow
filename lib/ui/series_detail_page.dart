import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:path_provider/path_provider.dart';
import 'package:path/path.dart' as path;
import '../db/database_helper.dart';
import 'anime_detail/anime_detail_page.dart';
import '../api/bangumi_service.dart';
import '../api/anilist_service.dart';

part 'series_detail_page_ui.dart';

class SeriesDetailPage extends StatefulWidget {
  final Map<String, dynamic> series;
  final Map<String, Color> statusColors;

  const SeriesDetailPage({
    super.key,
    required this.series,
    this.statusColors = const {},
  });

  @override
  State<SeriesDetailPage> createState() => _SeriesDetailPageState();
}

class _SeriesDetailPageState extends State<SeriesDetailPage> {
  late Map<String, dynamic> _currentSeries;
  List<Map<String, dynamic>> _animes = [];
  bool _isLoading = true;
  bool _isSelectionMode = false;
  final Set<int> _selectedIds = {};
  final TextEditingController _nameController = TextEditingController();
  final TextEditingController _descController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _currentSeries = Map.from(widget.series);
    _nameController.text = _currentSeries['name'] ?? '';
    _descController.text = _currentSeries['description'] ?? '';
    _loadData();
  }

  Future<void> _loadData() async {
    setState(() => _isLoading = true);
    final updatedSeries = await DatabaseHelper().getSeriesById(
      _currentSeries['id'],
    );
    if (updatedSeries != null) {
      _currentSeries = updatedSeries;
      _nameController.text = _currentSeries['name'] ?? '';
      _descController.text = _currentSeries['description'] ?? '';
    }
    final list = await DatabaseHelper().getAnimesInSeries(_currentSeries['id']);
    if (mounted) {
      setState(() {
        _animes = list;
        _isLoading = false;
      });
    }
  }

  void _toggleSelectionMode(bool enable) {
    setState(() {
      _isSelectionMode = enable;
      if (!enable) _selectedIds.clear();
    });
  }

  void _toggleItemSelection(int id) {
    setState(() {
      if (_selectedIds.contains(id)) {
        _selectedIds.remove(id);
        if (_selectedIds.isEmpty) _isSelectionMode = false;
      } else {
        _selectedIds.add(id);
      }
    });
  }

  Future<void> _updateSeriesInfo() async {
    final newName = _nameController.text.trim();
    if (newName.isEmpty) return;

    await DatabaseHelper().updateSeries(_currentSeries['id'], {
      'name': newName,
      'description': _descController.text.trim(),
    });
    _loadData();
  }

  void _showEditInfoDialog() {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('编辑系列信息'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: _nameController,
              decoration: const InputDecoration(
                labelText: '系列名称',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _descController,
              decoration: const InputDecoration(
                labelText: '描述 (可选)',
                border: OutlineInputBorder(),
              ),
              maxLines: 3,
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
              await _updateSeriesInfo();
              if (mounted) Navigator.pop(ctx);
            },
            child: const Text('确认'),
          ),
        ],
      ),
    );
  }

  void _showCoverSelector() async {
    if (_animes.isEmpty) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('系列内暂无番剧，无法选择封面')));
      return;
    }

    final String? selectedCover = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('选择系列封面'),
        content: SizedBox(
          width: double.maxFinite,
          child: GridView.builder(
            shrinkWrap: true,
            gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: 3,
              crossAxisSpacing: 8,
              mainAxisSpacing: 8,
              childAspectRatio: 0.7,
            ),
            itemCount: _animes.length,
            itemBuilder: (context, index) {
              final anime = _animes[index];
              return GestureDetector(
                onTap: () => Navigator.pop(ctx, anime['cover_url']),
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(4),
                  child: _buildCoverImage(anime['cover_url']),
                ),
              );
            },
          ),
        ),
        actions: [
          TextButton(
            onPressed: () async {
              await DatabaseHelper().updateSeries(_currentSeries['id'], {
                'custom_cover_url': null,
              });
              if (mounted) Navigator.pop(ctx, null);
              _loadData();
            },
            child: const Text('重置为默认'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
        ],
      ),
    );

    if (selectedCover != null) {
      await DatabaseHelper().updateSeries(_currentSeries['id'], {
        'custom_cover_url': selectedCover,
      });
      _loadData();
    }
  }

  // --- Batch Operations ---

  void _showBatchStatusDialog() {
    final statusOptions = ['未看', '在看', '看完', '弃坑'];
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('批量修改状态'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: statusOptions
              .map(
                (status) => ListTile(
                  title: Text(status),
                  leading: const Icon(Icons.radio_button_unchecked),
                  onTap: () async {
                    await DatabaseHelper().batchUpdateStatus(
                      _selectedIds.toList(),
                      status,
                    );
                    if (mounted) Navigator.pop(context);
                    _toggleSelectionMode(false);
                    _loadData();
                    if (mounted) {
                      ScaffoldMessenger.of(
                        context,
                      ).showSnackBar(const SnackBar(content: Text('批量修改状态成功')));
                    }
                  },
                ),
              )
              .toList(),
        ),
      ),
    );
  }

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
              onTap: () {
                Navigator.pop(context);
                _showBatchTagSelector(isAdd: true);
              },
            ),
            ListTile(
              leading: const Icon(Icons.remove, color: Colors.red),
              title: const Text('批量移除标签'),
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

  void _showBatchTagSelector({required bool isAdd}) async {
    final tags = await DatabaseHelper().getAllTags();
    if (!mounted) return;

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(isAdd ? '选择要添加的标签' : '选择要移除的标签'),
        content: SizedBox(
          width: double.maxFinite,
          height: 300,
          child: tags.isEmpty
              ? const Center(child: Text("暂无标签"))
              : ListView.builder(
                  shrinkWrap: true,
                  itemCount: tags.length,
                  itemBuilder: (context, index) {
                    final tag = tags[index];
                    return ListTile(
                      leading: const Icon(Icons.label, color: Colors.indigo),
                      title: Text(tag['name']),
                      onTap: () async {
                        Navigator.pop(context);
                        if (isAdd) {
                          await DatabaseHelper().batchAddTagToAnimes(
                            _selectedIds.toList(),
                            tag['id'],
                          );
                        } else {
                          await DatabaseHelper().batchRemoveTagFromAnimes(
                            _selectedIds.toList(),
                            tag['id'],
                          );
                        }
                        _toggleSelectionMode(false);
                        _loadData();
                        if (mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(
                              content: Text(isAdd ? '批量添加标签成功' : '批量移除标签成功'),
                            ),
                          );
                        }
                      },
                    );
                  },
                ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
        ],
      ),
    );
  }

  void _batchRemoveFromSeries() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('移出系列'),
        content: Text('确定要将选中的 ${_selectedIds.length} 部番剧移出当前系列吗？'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消'),
          ),
          TextButton(
            style: TextButton.styleFrom(foregroundColor: Colors.orange),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('确认移出'),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      for (int id in _selectedIds) {
        await DatabaseHelper().updateAnimeSeries(id, null);
      }
      _toggleSelectionMode(false);
      _loadData();
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('已批量移出系列')));
      }
    }
  }

  void _batchDelete() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('确认删除'),
        content: Text('确定要永久删除选中的 ${_selectedIds.length} 部番剧吗？此操作不可恢复。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消'),
          ),
          TextButton(
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('确认删除'),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      for (int id in _selectedIds) {
        await DatabaseHelper().deleteAnime(id);
      }
      _toggleSelectionMode(false);
      _loadData();
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('已批量删除番剧')));
      }
    }
  }

  void _batchAutoMatchInfo() async {
    if (_selectedIds.isEmpty) return;

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
                      '将对选中的 ${_selectedIds.length} 部番剧进行联网匹配。',
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

    // 显示进度弹窗 (由于是私有类，我们直接在当前文件局部实现或使用类似的逻辑)
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (BuildContext context) {
        return _SeriesAutoMatchProgressDialog(
          selectedIds: _selectedIds.toList(),
          animes: _animes,
          enabledFields: selections,
          onCompleted: () {
            Navigator.pop(context);
            _toggleSelectionMode(false);
            _loadData();
            ScaffoldMessenger.of(
              context,
            ).showSnackBar(const SnackBar(content: Text('批量匹配完成！')));
          },
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final coverUrl =
        _currentSeries['custom_cover_url'] ??
        _currentSeries['default_cover_url'];

    return PopScope(
      canPop: !_isSelectionMode,
      onPopInvoked: (didPop) {
        if (didPop) return;
        _toggleSelectionMode(false);
      },
      child: Scaffold(
        body: _isLoading
            ? const Center(child: CircularProgressIndicator())
            : CustomScrollView(
                slivers: [
                  SliverAppBar(
                    expandedHeight: 240,
                    pinned: true,
                    leading: _isSelectionMode
                        ? IconButton(
                            icon: const Icon(Icons.close),
                            onPressed: () => _toggleSelectionMode(false),
                          )
                        : null,
                    title: _isSelectionMode
                        ? Text(
                            "已选 ${_selectedIds.length} 项",
                            style: const TextStyle(
                              color: Colors.white,
                              fontWeight: FontWeight.bold,
                              shadows: [
                                Shadow(blurRadius: 4, color: Colors.black54),
                              ],
                            ),
                          )
                        : null,
                    flexibleSpace: FlexibleSpaceBar(
                      title: _isSelectionMode
                          ? null
                          : Text(
                              _currentSeries['name'],
                              style: const TextStyle(
                                color: Colors.white,
                                fontWeight: FontWeight.bold,
                                shadows: [
                                  Shadow(blurRadius: 4, color: Colors.black54),
                                ],
                              ),
                            ),
                      centerTitle: false,
                      background: Stack(
                        fit: StackFit.expand,
                        children: [
                          Hero(
                            tag: 'series_cover_${_currentSeries['id']}',
                            child: _buildCoverImage(coverUrl),
                          ),
                          const DecoratedBox(
                            decoration: BoxDecoration(
                              gradient: LinearGradient(
                                begin: Alignment.topCenter,
                                end: Alignment.bottomCenter,
                                colors: [Colors.transparent, Colors.black87],
                              ),
                            ),
                          ),
                          if (!_isSelectionMode)
                            Positioned(
                              right: 16,
                              bottom: 60,
                              child: FloatingActionButton.small(
                                onPressed: _showCoverSelector,
                                heroTag: 'change_cover',
                                child: const Icon(Icons.photo_camera),
                              ),
                            ),
                        ],
                      ),
                    ),
                    actions: _isSelectionMode
                        ? [
                            TextButton.icon(
                              icon: Icon(
                                _selectedIds.length == _animes.length
                                    ? Icons.deselect
                                    : Icons.select_all,
                                color: Colors.white,
                                size: 20,
                              ),
                              label: Text(
                                _selectedIds.length == _animes.length
                                    ? "取消全选"
                                    : "全选",
                                style: const TextStyle(color: Colors.white),
                              ),
                              onPressed: () {
                                setState(() {
                                  if (_selectedIds.length == _animes.length) {
                                    _selectedIds.clear();
                                    _isSelectionMode = false;
                                  } else {
                                    _selectedIds.addAll(
                                      _animes.map((e) => e['id'] as int),
                                    );
                                  }
                                });
                              },
                            ),
                          ]
                        : [
                            IconButton(
                              icon: const Icon(
                                Icons.edit_note,
                                color: Colors.white,
                              ),
                              onPressed: _showEditInfoDialog,
                              tooltip: '编辑基本信息',
                            ),
                          ],
                  ),
                  SliverToBoxAdapter(
                    child: Padding(
                      padding: const EdgeInsets.all(16.0),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          if (_currentSeries['description']?.isNotEmpty ==
                              true) ...[
                            Text(
                              _currentSeries['description'],
                              style: TextStyle(
                                color: Colors.grey[700],
                                fontSize: 14,
                              ),
                            ),
                            const SizedBox(height: 16),
                          ],
                          Row(
                            mainAxisAlignment: MainAxisAlignment.spaceBetween,
                            children: [
                              Text(
                                "全系列共 ${_animes.length} 部作品",
                                style: const TextStyle(
                                  fontSize: 16,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                              if (!_isSelectionMode)
                                IconButton(
                                  icon: const Icon(
                                    Icons.add_circle_outline,
                                    color: Colors.blue,
                                  ),
                                  onPressed: _showAddAnimesDialog,
                                  tooltip: '添加已有番剧',
                                ),
                            ],
                          ),
                          const Divider(),
                        ],
                      ),
                    ),
                  ),
                  () {
                    final animeList = _animes
                        .where((e) => (e['subject_type'] ?? 'anime') == 'anime')
                        .toList();
                    final bookList = _animes
                        .where((e) => e['subject_type'] == 'book')
                        .toList();

                    if (_animes.isEmpty) {
                      return const SliverFillRemaining(
                        child: Center(child: Text("系列内无作品")),
                      );
                    }

                    return SliverMainAxisGroup(
                      slivers: [
                        if (animeList.isNotEmpty) ...[
                          SliverToBoxAdapter(
                            child: Padding(
                              padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
                              child: Row(
                                children: [
                                  const Icon(
                                    Icons.movie_outlined,
                                    size: 18,
                                    color: Colors.blue,
                                  ),
                                  const SizedBox(width: 8),
                                  Text(
                                    "动画 (${animeList.length})",
                                    style: const TextStyle(
                                      fontSize: 14,
                                      fontWeight: FontWeight.bold,
                                      color: Colors.blue,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                          SliverPadding(
                            padding: const EdgeInsets.symmetric(horizontal: 16),
                            sliver: SliverGrid(
                              gridDelegate:
                                  const SliverGridDelegateWithFixedCrossAxisCount(
                                    crossAxisCount: 3,
                                    crossAxisSpacing: 12,
                                    mainAxisSpacing: 12,
                                    childAspectRatio: 0.6,
                                  ),
                              delegate: SliverChildBuilderDelegate(
                                (context, index) =>
                                    _buildAnimeCard(animeList[index]),
                                childCount: animeList.length,
                              ),
                            ),
                          ),
                        ],
                        if (animeList.isNotEmpty && bookList.isNotEmpty)
                          const SliverToBoxAdapter(
                            child: Padding(
                              padding: EdgeInsets.symmetric(vertical: 20),
                              child: Divider(
                                indent: 32,
                                endIndent: 32,
                                thickness: 1,
                                color: Color(0xFFEEEEEE),
                              ),
                            ),
                          ),
                        if (bookList.isNotEmpty) ...[
                          SliverToBoxAdapter(
                            child: Padding(
                              padding: const EdgeInsets.fromLTRB(16, 8, 16, 12),
                              child: Row(
                                children: [
                                  const Icon(
                                    Icons.menu_book_outlined,
                                    size: 18,
                                    color: Colors.teal,
                                  ),
                                  const SizedBox(width: 8),
                                  Text(
                                    "漫画 (${bookList.length})",
                                    style: const TextStyle(
                                      fontSize: 14,
                                      fontWeight: FontWeight.bold,
                                      color: Colors.teal,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                          ),
                          SliverPadding(
                            padding: const EdgeInsets.symmetric(horizontal: 16),
                            sliver: SliverGrid(
                              gridDelegate:
                                  const SliverGridDelegateWithFixedCrossAxisCount(
                                    crossAxisCount: 3,
                                    crossAxisSpacing: 12,
                                    mainAxisSpacing: 12,
                                    childAspectRatio: 0.6,
                                  ),
                              delegate: SliverChildBuilderDelegate(
                                (context, index) =>
                                    _buildAnimeCard(bookList[index]),
                                childCount: bookList.length,
                              ),
                            ),
                          ),
                        ],
                      ],
                    );
                  }(),
                  const SliverToBoxAdapter(child: SizedBox(height: 32)),
                ],
              ),
        bottomNavigationBar: _isSelectionMode
            ? _buildSelectionActionBar()
            : null,
      ),
    );
  }

  void _showAddAnimesDialog() async {
    final List<int>? selectedIds = await showDialog<List<int>>(
      context: context,
      builder: (ctx) => AnimeSelectionDialog(statusColors: widget.statusColors),
    );

    if (selectedIds != null && selectedIds.isNotEmpty) {
      await DatabaseHelper().batchUpdateAnimesSeries(
        selectedIds,
        _currentSeries['id'],
      );
      _loadData();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('成功添加 ${selectedIds.length} 部番剧到系列')),
        );
      }
    }
  }
}

class AnimeSelectionDialog extends StatefulWidget {
  final Map<String, Color> statusColors;
  const AnimeSelectionDialog({super.key, required this.statusColors});

  @override
  State<AnimeSelectionDialog> createState() => _AnimeSelectionDialogState();
}

class _AnimeSelectionDialogState extends State<AnimeSelectionDialog> {
  List<Map<String, dynamic>> _candidates = [];
  List<Map<String, dynamic>> _filteredCandidates = [];
  final Set<int> _selectedIds = {};
  bool _isLoading = true;
  final TextEditingController _searchController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _loadCandidates();
    _searchController.addListener(_filterCandidates);
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  void _loadCandidates() async {
    final list = await DatabaseHelper().getAnimesWithoutSeries();
    if (mounted) {
      setState(() {
        _candidates = list;
        _filteredCandidates = list;
        _isLoading = false;
      });
    }
  }

  void _filterCandidates() {
    final query = _searchController.text.toLowerCase();
    setState(() {
      _filteredCandidates = _candidates.where((anime) {
        final title = (anime['title'] ?? '').toString().toLowerCase();
        return title.contains(query);
      }).toList();
    });
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('选择番剧加入系列'),
      content: SizedBox(
        width: double.maxFinite,
        height: 400,
        child: Column(
          children: [
            TextField(
              controller: _searchController,
              decoration: const InputDecoration(
                hintText: '搜索标题...',
                prefixIcon: Icon(Icons.search),
                isDense: true,
              ),
            ),
            const SizedBox(height: 8),
            Expanded(
              child: _isLoading
                  ? const Center(child: CircularProgressIndicator())
                  : _filteredCandidates.isEmpty
                  ? const Center(child: Text('没有找到未归类的番剧'))
                  : ListView.builder(
                      itemCount: _filteredCandidates.length,
                      itemBuilder: (ctx, index) {
                        final anime = _filteredCandidates[index];
                        final id = anime['id'];
                        final isSelected = _selectedIds.contains(id);
                        return CheckboxListTile(
                          value: isSelected,
                          title: Text(
                            anime['title'],
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(fontSize: 14),
                          ),
                          subtitle: Text(
                            anime['status'] ?? '',
                            style: TextStyle(
                              fontSize: 12,
                              color:
                                  widget.statusColors[anime['status']] ??
                                  Colors.grey,
                            ),
                          ),
                          onChanged: (val) {
                            setState(() {
                              if (val == true) {
                                _selectedIds.add(id);
                              } else {
                                _selectedIds.remove(id);
                              }
                            });
                          },
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('取消'),
        ),
        FilledButton(
          onPressed: _selectedIds.isEmpty
              ? null
              : () => Navigator.pop(context, _selectedIds.toList()),
          child: Text('确定 (${_selectedIds.length})'),
        ),
      ],
    );
  }
}

class _StatusBadge extends StatelessWidget {
  final String status;
  final Color color;

  const _StatusBadge({required this.status, required this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(4),
        border: Border.all(color: color.withValues(alpha: 0.5), width: 0.5),
      ),
      child: Text(
        status,
        style: TextStyle(
          color: color,
          fontSize: 10,
          fontWeight: FontWeight.bold,
        ),
      ),
    );
  }
}

class _SeriesAutoMatchProgressDialog extends StatefulWidget {
  final List<int> selectedIds;
  final List<Map<String, dynamic>> animes;
  final Map<String, bool> enabledFields;
  final VoidCallback onCompleted;

  const _SeriesAutoMatchProgressDialog({
    required this.selectedIds,
    required this.animes,
    required this.enabledFields,
    required this.onCompleted,
  });

  @override
  State<_SeriesAutoMatchProgressDialog> createState() =>
      _SeriesAutoMatchProgressDialogState();
}

class _SeriesAutoMatchProgressDialogState
    extends State<_SeriesAutoMatchProgressDialog> {
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
          continue;
        }

        final allResults = [...resultsList[0], ...resultsList[1]];
        if (allResults.isNotEmpty) {
          final bestMatch = allResults.first;
          Map<String, dynamic> updateData = {'id': animeId};

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
          if (widget.enabledFields['rating'] == true &&
              bestMatch.score != null &&
              bestMatch.score! > 0) {
            updateData['rating'] = bestMatch.score;
          }

          if (bestMatch.source == 'bangumi') {
            final detail = await BangumiService.getAnimeDetail(bestMatch.id);
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
            if (widget.enabledFields['rating'] == true &&
                detail['score'] != null) {
              updateData['rating'] = detail['score'];
            }
            if (widget.enabledFields['summary'] == true) {
              String existingReview = anime['review'] ?? '';
              String newSummary = detail['summary'] ?? '';
              if (newSummary.isNotEmpty && existingReview.isEmpty) {
                updateData['review'] = newSummary;
              }
            }
          } else {
            if (widget.enabledFields['airDate'] == true &&
                bestMatch.airDate != null) {
              updateData['air_date'] = bestMatch.airDate;
            }
            if (widget.enabledFields['studio'] == true &&
                bestMatch.studio != null) {
              updateData['studio'] = bestMatch.studio;
            }
            if (widget.enabledFields['summary'] == true) {
              String existingReview = anime['review'] ?? '';
              if (bestMatch.summary != null &&
                  bestMatch.summary!.isNotEmpty &&
                  existingReview.isEmpty) {
                updateData['review'] = bestMatch.summary;
              }
            }
          }

          await DatabaseHelper().updateAnime(updateData);
          _successCount++;
        }
      } catch (e) {
        debugPrint("Auto match error: $e");
      }
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
    return [[], []];
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
