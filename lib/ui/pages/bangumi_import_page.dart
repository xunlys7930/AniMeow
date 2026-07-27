import 'package:flutter/material.dart';
import 'package:anime_tracker/api/bangumi_service.dart';
import 'package:anime_tracker/db/database_helper.dart';
import 'package:anime_tracker/ui/components/anime_cover_image.dart';
import 'package:anime_tracker/utils/logger.dart';

enum _ImportConflictAction { ask, replace, keep }

class BangumiImportPage extends StatefulWidget {
  const BangumiImportPage({super.key});

  @override
  State<BangumiImportPage> createState() => _BangumiImportPageState();
}

class _BangumiImportPageState extends State<BangumiImportPage> {
  final TextEditingController _usernameController = TextEditingController();
  final Set<int> _selectedSubjectIds = {};

  List<BangumiUserCollectionItem> _items = const [];
  bool _isLoading = false;
  bool _isImporting = false;
  bool _importTags = true;

  List<BangumiUserCollectionItem> get _selectedItems => _items
      .where((item) => _selectedSubjectIds.contains(item.subjectId))
      .toList();

  @override
  void dispose() {
    _usernameController.dispose();
    super.dispose();
  }

  Future<void> _loadCollections() async {
    final username = _usernameController.text.trim();
    if (username.isEmpty) {
      _showSnackBar('请先输入 Bangumi 用户名或 UID');
      return;
    }

    FocusScope.of(context).unfocus();
    setState(() {
      _isLoading = true;
      _items = const [];
      _selectedSubjectIds.clear();
    });

    try {
      final items = await BangumiService.fetchUserAnimeCollections(username);
      if (!mounted) return;

      setState(() {
        _items = items;
        _selectedSubjectIds.addAll(items.map((item) => item.subjectId));
      });

      if (items.isEmpty) {
        _showSnackBar('没有拉取到公开动画收藏');
      }
    } catch (e) {
      logger.e('Bangumi 导入拉取失败: $e');
      if (mounted) {
        _showSnackBar('拉取失败: $e');
      }
    } finally {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  Future<void> _importSelected() async {
    final selectedItems = _selectedItems;
    if (selectedItems.isEmpty) {
      _showSnackBar('请选择要导入的收藏');
      return;
    }

    setState(() => _isImporting = true);

    int successCount = 0;
    int replaceCount = 0;
    int skipCount = 0;
    int errorCount = 0;
    var currentPolicy = _ImportConflictAction.ask;

    for (final item in selectedItems) {
      if (!mounted) break;

      try {
        final animeData = item.toAnimeMap();
        final existing = await DatabaseHelper().getAnimeByTitle(item.title);
        if (!mounted) break;

        if (existing != null) {
          var action = currentPolicy;

          if (action == _ImportConflictAction.ask) {
            final result = await showDialog<Map<String, dynamic>>(
              context: context,
              barrierDismissible: false,
              builder: (ctx) => _ConflictDialog(title: item.title),
            );

            if (result == null) {
              action = _ImportConflictAction.keep;
            } else {
              action = result['action'] as _ImportConflictAction;
              final applyToAll = result['applyToAll'] as bool;
              if (applyToAll) {
                currentPolicy = action;
              }
            }
          }

          if (action == _ImportConflictAction.keep) {
            skipCount++;
            continue;
          }

          animeData['id'] = existing['id'];
          animeData['created_at'] = existing['created_at'];
          await DatabaseHelper().updateAnime(animeData);
          if (_importTags) {
            await _processTags(existing['id'] as int, item.tags);
          }
          replaceCount++;
        } else {
          animeData['created_at'] = DateTime.now().toString();
          final newAnimeId = await DatabaseHelper().insertAnime(animeData);
          if (_importTags) {
            await _processTags(newAnimeId, item.tags);
          }
          successCount++;
        }
      } catch (e) {
        logger.e('Bangumi 导入错误 (${item.title}): $e');
        errorCount++;
      }
    }

    if (!mounted) return;
    setState(() => _isImporting = false);

    final hasChanges = successCount > 0 || replaceCount > 0;
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('导入完成'),
        content: Text(
          '新增: $successCount\n覆盖: $replaceCount\n跳过: $skipCount\n错误: $errorCount',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('确定'),
          ),
        ],
      ),
    );

    if (mounted) {
      Navigator.pop(context, hasChanges);
    }
  }

  Future<void> _processTags(int animeId, List<String> tags) async {
    final cleanTags = tags
        .map((tag) => tag.trim())
        .where((tag) => tag.isNotEmpty)
        .toSet();
    if (cleanTags.isEmpty) return;

    final db = await DatabaseHelper().database;
    for (final tagName in cleanTags) {
      final existing = await db.query(
        'tags',
        where: 'name = ?',
        whereArgs: [tagName],
        limit: 1,
      );

      int tagId;
      if (existing.isNotEmpty) {
        tagId = existing.first['id'] as int;
      } else {
        tagId = await DatabaseHelper().insertTag(tagName);
        if (tagId == -1) {
          final retry = await db.query(
            'tags',
            where: 'name = ?',
            whereArgs: [tagName],
            limit: 1,
          );
          if (retry.isEmpty) continue;
          tagId = retry.first['id'] as int;
        }
      }

      await DatabaseHelper().addTagToAnime(animeId, tagId);
    }
  }

  void _toggleSelectAll() {
    setState(() {
      if (_selectedSubjectIds.length == _items.length) {
        _selectedSubjectIds.clear();
      } else {
        _selectedSubjectIds
          ..clear()
          ..addAll(_items.map((item) => item.subjectId));
      }
    });
  }

  void _showSnackBar(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    final selectedCount = _selectedSubjectIds.length;

    return Scaffold(
      appBar: AppBar(title: const Text('Bangumi 导入')),
      body: Column(
        children: [
          _buildFetchPanel(),
          if (_items.isNotEmpty) _buildSummaryBar(selectedCount),
          Expanded(child: _buildBody()),
          if (_items.isNotEmpty) _buildBottomBar(selectedCount),
        ],
      ),
    );
  }

  Widget _buildFetchPanel() {
    return Container(
      padding: const EdgeInsets.all(16),
      color: Colors.grey[100],
      child: Column(
        children: [
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _usernameController,
                  enabled: !_isLoading && !_isImporting,
                  textInputAction: TextInputAction.search,
                  onSubmitted: (_) => _loadCollections(),
                  decoration: const InputDecoration(
                    labelText: 'Bangumi 用户名 / UID',
                    prefixIcon: Icon(Icons.person_search_outlined),
                    border: OutlineInputBorder(),
                  ),
                ),
              ),
              const SizedBox(width: 12),
              FilledButton.icon(
                onPressed: _isLoading || _isImporting ? null : _loadCollections,
                icon: _isLoading
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.cloud_download_outlined),
                label: const Text('拉取'),
              ),
            ],
          ),
          if (_items.isNotEmpty)
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              value: _importTags,
              onChanged: _isImporting
                  ? null
                  : (value) => setState(() => _importTags = value),
              title: const Text('导入 Bangumi 标签'),
            ),
        ],
      ),
    );
  }

  Widget _buildSummaryBar(int selectedCount) {
    final allSelected = selectedCount == _items.length;

    return Container(
      padding: const EdgeInsets.fromLTRB(16, 10, 8, 10),
      decoration: const BoxDecoration(
        border: Border(bottom: BorderSide(color: Color(0xFFEFEFEF))),
      ),
      child: Row(
        children: [
          Expanded(
            child: Text(
              '共 ${_items.length} 条 · 已选 $selectedCount 条',
              style: const TextStyle(fontWeight: FontWeight.w600),
            ),
          ),
          TextButton(
            onPressed: _isImporting ? null : _toggleSelectAll,
            child: Text(allSelected ? '取消全选' : '全选'),
          ),
        ],
      ),
    );
  }

  Widget _buildBody() {
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator());
    }

    if (_items.isEmpty) {
      return const Center(child: Text('输入 Bangumi 用户名后拉取公开动画收藏'));
    }

    return ListView.separated(
      itemCount: _items.length,
      separatorBuilder: (_, _) => const Divider(height: 1, indent: 88),
      itemBuilder: (context, index) {
        final item = _items[index];
        final selected = _selectedSubjectIds.contains(item.subjectId);

        return CheckboxListTile(
          value: selected,
          enabled: !_isImporting,
          controlAffinity: ListTileControlAffinity.trailing,
          secondary: ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: SizedBox(
              width: 48,
              height: 68,
              child: AnimeCoverImage(url: item.coverUrl),
            ),
          ),
          title: Text(
            item.title,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontWeight: FontWeight.w600),
          ),
          subtitle: _CollectionSubtitle(item: item),
          onChanged: (value) {
            setState(() {
              if (value == true) {
                _selectedSubjectIds.add(item.subjectId);
              } else {
                _selectedSubjectIds.remove(item.subjectId);
              }
            });
          },
        );
      },
    );
  }

  Widget _buildBottomBar(int selectedCount) {
    return SafeArea(
      top: false,
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: const BoxDecoration(
          color: Colors.white,
          border: Border(top: BorderSide(color: Color(0xFFEFEFEF))),
        ),
        child: Row(
          children: [
            Expanded(child: Text('已选 $selectedCount 条')),
            FilledButton.icon(
              onPressed: _isImporting || selectedCount == 0
                  ? null
                  : _importSelected,
              icon: _isImporting
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.playlist_add_check_outlined),
              label: Text(_isImporting ? '正在导入' : '开始导入'),
            ),
          ],
        ),
      ),
    );
  }
}

class _CollectionSubtitle extends StatelessWidget {
  final BangumiUserCollectionItem item;

  const _CollectionSubtitle({required this.item});

  @override
  Widget build(BuildContext context) {
    final tags = item.tags.take(4).join(' / ');

    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: 8,
            runSpacing: 4,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              _StatusChip(status: item.status),
              Text(
                item.progressText,
                style: TextStyle(color: Colors.grey[700], fontSize: 12),
              ),
              if (item.airDate != null)
                Text(
                  item.airDate!,
                  style: TextStyle(color: Colors.grey[600], fontSize: 12),
                ),
            ],
          ),
          if (tags.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                tags,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(color: Colors.grey[500], fontSize: 12),
              ),
            ),
        ],
      ),
    );
  }
}

class _StatusChip extends StatelessWidget {
  final String status;

  const _StatusChip({required this.status});

  @override
  Widget build(BuildContext context) {
    final color = switch (status) {
      '在看' => Colors.blue,
      '看完' => Colors.green,
      '弃坑' => Colors.red,
      _ => Colors.orange,
    };

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        status,
        style: TextStyle(
          color: color,
          fontSize: 12,
          fontWeight: FontWeight.w700,
        ),
      ),
    );
  }
}

class _ConflictDialog extends StatefulWidget {
  final String title;

  const _ConflictDialog({required this.title});

  @override
  State<_ConflictDialog> createState() => _ConflictDialogState();
}

class _ConflictDialogState extends State<_ConflictDialog> {
  bool _applyToAll = false;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('发现同名番剧'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '番剧 "${widget.title}" 已存在。',
            style: const TextStyle(fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 12),
          const Text('请选择本次导入的处理方式。'),
          const SizedBox(height: 16),
          CheckboxListTile(
            contentPadding: EdgeInsets.zero,
            value: _applyToAll,
            onChanged: (value) => setState(() => _applyToAll = value ?? false),
            controlAffinity: ListTileControlAffinity.leading,
            title: const Text('后续冲突全部应用此操作'),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () {
            Navigator.pop(context, {
              'action': _ImportConflictAction.keep,
              'applyToAll': _applyToAll,
            });
          },
          child: const Text('跳过'),
        ),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: Colors.orange),
          onPressed: () {
            Navigator.pop(context, {
              'action': _ImportConflictAction.replace,
              'applyToAll': _applyToAll,
            });
          },
          child: const Text('覆盖'),
        ),
      ],
    );
  }
}
