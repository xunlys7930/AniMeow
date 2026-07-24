import 'dart:convert';

import 'package:flutter/material.dart';

import 'package:anime_tracker/api/bangumi_service.dart';
import 'package:anime_tracker/models/bangumi_character.dart';
import 'package:anime_tracker/repositories/character_repository.dart';
import 'package:anime_tracker/services/service_locator.dart';

import '../detail_props.dart';
import 'module_section.dart';

enum _CharacterRemovalAction { unlink, delete }

class CharactersModule extends StatelessWidget {
  final DetailViewProps props;
  const CharactersModule({super.key, required this.props});

  @override
  Widget build(BuildContext context) {
    final animeId = props.anime['id'];
    if (animeId is! int) return const SizedBox.shrink();

    return ModuleSection(
      icon: Icons.groups_2_outlined,
      iconColor: Theme.of(context).colorScheme.primary,
      title: '角色',
      trailing: IconButton.filledTonal(
        onPressed: () => _openSearchDialog(context, animeId),
        icon: const Icon(Icons.person_add_alt_1_outlined, size: 18),
        tooltip: '添加角色',
        visualDensity: VisualDensity.compact,
      ),
      child: props.characters.isEmpty
          ? _EmptyCharacters(onAdd: () => _openSearchDialog(context, animeId))
          : SizedBox(
              height: 206,
              child: ListView.separated(
                scrollDirection: Axis.horizontal,
                itemCount: props.characters.length,
                separatorBuilder: (_, _) => const SizedBox(width: 12),
                itemBuilder: (context, index) => _CharacterCard(
                  item: props.characters[index],
                  animeId: animeId,
                  onChanged: props.onCharactersChanged,
                ),
              ),
            ),
    );
  }

  Future<void> _openSearchDialog(BuildContext context, int animeId) async {
    final changed = await showDialog<bool>(
      context: context,
      builder: (_) => _CharacterSearchDialog(animeId: animeId),
    );
    if (changed == true) props.onCharactersChanged();
  }
}

class _EmptyCharacters extends StatelessWidget {
  final VoidCallback onAdd;
  const _EmptyCharacters({required this.onAdd});

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onAdd,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 18),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerHighest,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          children: [
            Icon(
              Icons.person_search_outlined,
              color: Theme.of(context).colorScheme.primary,
            ),
            const SizedBox(width: 12),
            const Expanded(child: Text('搜索角色并添加到这部作品')),
          ],
        ),
      ),
    );
  }
}

class _CharacterCard extends StatelessWidget {
  final Map<String, dynamic> item;
  final int animeId;
  final VoidCallback onChanged;

  const _CharacterCard({
    required this.item,
    required this.animeId,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final imageUrl = _asText(item['image_url']) ?? '';
    final name = _displayName(item);
    final originalName = _asText(item['name']) ?? '';

    return SizedBox(
      width: 124,
      child: Tooltip(
        message: '查看角色详情',
        child: Material(
          color: Colors.transparent,
          child: InkWell(
            borderRadius: BorderRadius.circular(12),
            onTap: () => _openDetail(context),
            child: Padding(
              padding: const EdgeInsets.all(2),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Stack(
                    children: [
                      _CharacterImage(
                        imageUrl: imageUrl,
                        width: 120,
                        height: 150,
                        borderRadius: 10,
                      ),
                      Positioned(
                        right: 4,
                        top: 4,
                        child: Material(
                          color: Colors.black54,
                          borderRadius: BorderRadius.circular(16),
                          child: InkWell(
                            borderRadius: BorderRadius.circular(16),
                            onTap: () => _remove(context),
                            child: const Padding(
                              padding: EdgeInsets.all(4),
                              child: Icon(
                                Icons.close,
                                color: Colors.white,
                                size: 14,
                              ),
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Text(
                    name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  if (originalName.isNotEmpty && originalName != name)
                    Text(
                      originalName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 11,
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> _openDetail(BuildContext context) {
    return showDialog<void>(
      context: context,
      builder: (_) => _CharacterDetailDialog(item: item),
    );
  }

  Future<void> _remove(BuildContext context) async {
    final characterId = item['id'];
    if (characterId is! int) return;

    final name = _displayName(item);
    final action = await showDialog<_CharacterRemovalAction>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('移除角色「$name」'),
        content: const Text('你可以只解除该角色与当前作品的关联，或者从角色库中永久删除。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, _CharacterRemovalAction.unlink),
            child: const Text('仅从本作移除'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, _CharacterRemovalAction.delete),
            style: FilledButton.styleFrom(backgroundColor: Colors.red),
            child: const Text('永久删除'),
          ),
        ],
      ),
    );
    if (action == null) return;

    final repo = getIt<CharacterRepository>();
    if (action == _CharacterRemovalAction.delete) {
      await repo.deleteCharacter(characterId);
    } else {
      await repo.removeCharacterFromAnime(
        animeId: animeId,
        characterId: characterId,
      );
    }
    onChanged();
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          action == _CharacterRemovalAction.delete
              ? '已永久删除角色「$name」'
              : '已从当前作品移除角色「$name」',
        ),
      ),
    );
  }

  static String _displayName(Map<String, dynamic> item) {
    final nameCn = (_asText(item['name_cn']) ?? '').trim();
    if (nameCn.isNotEmpty) return nameCn;
    return _asText(item['name']) ?? '';
  }
}

class _CharacterDetailDialog extends StatelessWidget {
  final Map<String, dynamic> item;

  const _CharacterDetailDialog({required this.item});

  @override
  Widget build(BuildContext context) {
    final dialogWidth = (MediaQuery.sizeOf(context).width - 64)
        .clamp(320.0, 560.0)
        .toDouble();
    final theme = Theme.of(context);
    final name = _CharacterCard._displayName(item);
    final summary = _asText(item['summary']);
    final infoboxRows = _infoboxRows(item);

    return AlertDialog(
      title: Text(name.isEmpty ? '角色详情' : name),
      content: SizedBox(
        width: dialogWidth,
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxHeight: 620),
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                _CharacterDetailHeader(item: item),
                if (summary != null) ...[
                  const SizedBox(height: 20),
                  Text(
                    '简介',
                    style: theme.textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(summary, style: theme.textTheme.bodyMedium),
                ],
                if (infoboxRows.isNotEmpty) ...[
                  const SizedBox(height: 20),
                  Text(
                    '资料',
                    style: theme.textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: 8),
                  ...infoboxRows.map((row) => _InfoRow(row: row)),
                ],
              ],
            ),
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(context),
          child: const Text('关闭'),
        ),
      ],
    );
  }
}

class _CharacterDetailHeader extends StatelessWidget {
  final Map<String, dynamic> item;

  const _CharacterDetailHeader({required this.item});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final imageUrl = _asText(item['image_url']) ?? '';
    final name = _CharacterCard._displayName(item);
    final originalName = _asText(item['name']) ?? '';
    final infoRows = [
      if (_genderText(item['gender']) != null)
        _InfoLine('性别', _genderText(item['gender'])!),
      if (_birthdayText(item) != null) _InfoLine('生日', _birthdayText(item)!),
      if (_asText(item['blood_type']) != null)
        _InfoLine('血型', _asText(item['blood_type'])!),
      if (_asText(item['role_name']) != null)
        _InfoLine('出演', _asText(item['role_name'])!),
    ];

    final textColumn = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (originalName.isNotEmpty && originalName != name) ...[
          Text(
            originalName,
            style: theme.textTheme.titleMedium?.copyWith(
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 10),
        ],
        if (infoRows.isEmpty)
          Text(
            '暂无基础资料',
            style: TextStyle(color: theme.colorScheme.onSurfaceVariant),
          )
        else
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: infoRows.map((row) => _InfoChip(row: row)).toList(),
          ),
      ],
    );

    return LayoutBuilder(
      builder: (context, constraints) {
        final image = _CharacterImage(
          imageUrl: imageUrl,
          width: constraints.maxWidth < 430 ? 180 : 150,
          height: constraints.maxWidth < 430 ? 240 : 210,
          borderRadius: 12,
        );

        if (constraints.maxWidth < 430) {
          return Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Center(child: image),
              const SizedBox(height: 16),
              textColumn,
            ],
          );
        }

        return Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            image,
            const SizedBox(width: 18),
            Expanded(child: textColumn),
          ],
        );
      },
    );
  }
}

class _CharacterImage extends StatelessWidget {
  final String imageUrl;
  final double width;
  final double height;
  final double borderRadius;

  const _CharacterImage({
    required this.imageUrl,
    required this.width,
    required this.height,
    required this.borderRadius,
  });

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(borderRadius),
      child: Container(
        width: width,
        height: height,
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        child: imageUrl.isEmpty
            ? const Icon(Icons.person_outline, size: 42)
            : Image.network(
                imageUrl,
                fit: BoxFit.contain,
                alignment: Alignment.center,
                filterQuality: FilterQuality.medium,
                loadingBuilder: (context, child, loadingProgress) {
                  if (loadingProgress == null) return child;
                  return const Center(
                    child: SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                  );
                },
                errorBuilder: (context, error, stackTrace) =>
                    const Icon(Icons.broken_image_outlined, size: 36),
              ),
      ),
    );
  }
}

class _InfoChip extends StatelessWidget {
  final _InfoLine row;

  const _InfoChip({required this.row});

  @override
  Widget build(BuildContext context) {
    return Chip(
      label: Text('${row.label}: ${row.value}'),
      visualDensity: VisualDensity.compact,
      side: BorderSide.none,
      backgroundColor: Theme.of(context).colorScheme.surfaceContainerHighest,
    );
  }
}

class _InfoRow extends StatelessWidget {
  final _InfoLine row;

  const _InfoRow({required this.row});

  @override
  Widget build(BuildContext context) {
    final color = Theme.of(context).colorScheme.onSurfaceVariant;
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 88,
            child: Text(row.label, style: TextStyle(color: color)),
          ),
          Expanded(child: Text(row.value)),
        ],
      ),
    );
  }
}

class _InfoLine {
  final String label;
  final String value;

  const _InfoLine(this.label, this.value);
}

class _CharacterSearchDialog extends StatefulWidget {
  final int animeId;
  const _CharacterSearchDialog({required this.animeId});

  @override
  State<_CharacterSearchDialog> createState() => _CharacterSearchDialogState();
}

class _CharacterSearchDialogState extends State<_CharacterSearchDialog> {
  final TextEditingController _controller = TextEditingController();
  List<BangumiCharacter> _results = const [];
  bool _isSearching = false;
  bool _isAdding = false;
  String? _errorText;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _search() async {
    final query = _controller.text.trim();
    if (query.isEmpty || _isSearching) return;

    setState(() {
      _isSearching = true;
      _errorText = null;
    });
    try {
      final results = await BangumiService.searchCharacters(query);
      if (!mounted) return;
      setState(() => _results = results);
    } catch (e) {
      if (!mounted) return;
      setState(() => _errorText = e.toString());
    } finally {
      if (mounted) setState(() => _isSearching = false);
    }
  }

  Future<void> _addCharacter(BangumiCharacter result) async {
    if (_isAdding) return;
    setState(() {
      _isAdding = true;
      _errorText = null;
    });
    try {
      final detail = await BangumiService.getCharacter(result.id) ?? result;
      await getIt<CharacterRepository>().addBangumiCharacterToAnime(
        animeId: widget.animeId,
        character: detail,
      );
      if (!mounted) return;
      Navigator.pop(context, true);
    } catch (e) {
      if (!mounted) return;
      setState(() => _errorText = e.toString());
    } finally {
      if (mounted) setState(() => _isAdding = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('添加角色'),
      content: SizedBox(
        width: double.maxFinite,
        height: 420,
        child: Column(
          children: [
            TextField(
              controller: _controller,
              autofocus: true,
              textInputAction: TextInputAction.search,
              decoration: InputDecoration(
                hintText: '输入角色名，例如 鲁路修',
                suffixIcon: IconButton(
                  onPressed: _isSearching ? null : _search,
                  icon: const Icon(Icons.search),
                ),
              ),
              onSubmitted: (_) => _search(),
            ),
            if (_errorText != null) ...[
              const SizedBox(height: 8),
              Text(
                _errorText!,
                style: const TextStyle(fontSize: 12, color: Colors.redAccent),
              ),
            ],
            const SizedBox(height: 12),
            Expanded(
              child: _isSearching
                  ? const Center(child: CircularProgressIndicator())
                  : _results.isEmpty
                  ? const Center(child: Text('搜索后选择要录入的角色'))
                  : ListView.builder(
                      itemCount: _results.length,
                      itemBuilder: (context, index) {
                        final item = _results[index];
                        return ListTile(
                          leading: _CharacterImage(
                            imageUrl: item.imageUrl ?? '',
                            width: 44,
                            height: 56,
                            borderRadius: 8,
                          ),
                          title: Text(item.displayName),
                          subtitle: item.originalName == item.displayName
                              ? null
                              : Text(item.originalName),
                          trailing: _isAdding
                              ? const SizedBox(
                                  width: 18,
                                  height: 18,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : const Icon(Icons.add),
                          onTap: _isAdding ? null : () => _addCharacter(item),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: _isAdding ? null : () => Navigator.pop(context, false),
          child: const Text('关闭'),
        ),
      ],
    );
  }
}

String? _asText(dynamic value) {
  final text = value?.toString().trim();
  if (text == null || text.isEmpty || text == 'null') return null;
  return text;
}

int? _asInt(dynamic value) {
  if (value == null) return null;
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value.toString());
}

String? _genderText(dynamic value) {
  final text = _asText(value);
  if (text == null) return null;
  switch (text.toLowerCase()) {
    case 'male':
      return '男';
    case 'female':
      return '女';
    default:
      return text;
  }
}

String? _birthdayText(Map<String, dynamic> item) {
  final year = _asInt(item['birth_year']);
  final month = _asInt(item['birth_mon']);
  final day = _asInt(item['birth_day']);
  if (year == null && month == null && day == null) return null;

  final buffer = StringBuffer();
  if (year != null) buffer.write('$year年');
  if (month != null) buffer.write('$month月');
  if (day != null) buffer.write('$day日');
  return buffer.toString();
}

List<_InfoLine> _infoboxRows(Map<String, dynamic> item) {
  final raw = _asText(item['infobox_json']);
  if (raw == null) return const [];

  try {
    final decoded = jsonDecode(raw);
    if (decoded is! List) return const [];

    const hiddenKeys = {'简体中文名', '性别', '生日', '血型'};
    final rows = <_InfoLine>[];
    for (final entry in decoded) {
      if (entry is! Map) continue;
      final key = _asText(entry['key']);
      final value = _formatInfoboxValue(entry['value']);
      if (key == null || value == null || hiddenKeys.contains(key)) continue;
      rows.add(_InfoLine(key, value));
    }
    return rows;
  } catch (_) {
    return const [];
  }
}

String? _formatInfoboxValue(dynamic value) {
  if (value is List) {
    final parts = value
        .map(_formatInfoboxValue)
        .whereType<String>()
        .where((item) => item.isNotEmpty)
        .toList();
    return parts.isEmpty ? null : parts.join(' / ');
  }

  if (value is Map) {
    final label = _asText(value['k']);
    final text = _asText(value['v']) ?? _asText(value['value']);
    if (label != null && text != null) return '$label: $text';
    if (text != null) return text;

    final parts = value.entries
        .map((entry) {
          final entryValue = _formatInfoboxValue(entry.value);
          if (entryValue == null) return null;
          return '${entry.key}: $entryValue';
        })
        .whereType<String>()
        .toList();
    return parts.isEmpty ? null : parts.join(' / ');
  }

  return _asText(value);
}
