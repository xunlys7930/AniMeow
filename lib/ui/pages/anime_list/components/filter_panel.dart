import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:anime_tracker/settings_manager.dart';
import 'package:anime_tracker/ui/design_tokens.dart';
import '../anime_list_provider.dart';

/// 统一筛选面板。
///
/// 面板内先编辑草稿，点击“应用筛选”后再一次性提交。这样连续选择年份、
/// 标签时不会反复查询数据库，关闭面板也不会留下半完成状态。
class FilterPanel extends StatefulWidget {
  final ScrollController scrollController;

  const FilterPanel({super.key, required this.scrollController});

  @override
  State<FilterPanel> createState() => _FilterPanelState();
}

class _FilterPanelState extends State<FilterPanel> {
  bool _initialized = false;
  late String _type;
  late String _status;
  late bool _isAndMode;
  late Set<String> _years;
  late Set<int> _tagIds;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_initialized) return;
    final provider = context.read<AnimeListProvider>();
    _type = provider.selectedType;
    _status = provider.selectedStatus;
    _isAndMode = provider.isAndMode;
    _years = Set<String>.from(provider.selectedYears);
    _tagIds = Set<int>.from(provider.selectedTagIds);
    _initialized = true;
  }

  int get _activeCount {
    var count = 0;
    if (_type != 'all') count++;
    if (_status != '全部') count++;
    if (_years.isNotEmpty) count++;
    if (_tagIds.isNotEmpty) count++;
    return count;
  }

  void _resetDraft() {
    setState(() {
      _type = 'all';
      _status = '全部';
      _isAndMode = false;
      _years.clear();
      _tagIds.clear();
    });
  }

  void _apply(AnimeListProvider provider) {
    provider.applyFilters(
      type: _type,
      status: _status,
      years: _years,
      tagIds: _tagIds,
      isAndMode: _isAndMode,
    );
    SettingsManager().setLastSelectedStatus(_status);
    Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<AnimeListProvider>();
    final colorScheme = Theme.of(context).colorScheme;

    return Material(
      color: colorScheme.surfaceContainerHigh,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xl),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.only(
                top: AppSpacing.sm,
                bottom: AppSpacing.md,
              ),
              child: Row(
                children: [
                  Container(
                    width: 42,
                    height: 42,
                    decoration: BoxDecoration(
                      color: colorScheme.primaryContainer,
                      borderRadius: BorderRadius.circular(AppRadius.xs),
                    ),
                    child: Icon(
                      Icons.tune_rounded,
                      color: colorScheme.onPrimaryContainer,
                    ),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '筛选作品',
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        Text(
                          _activeCount == 0
                              ? '选择条件后一次性应用'
                              : '已选择 $_activeCount 组条件',
                          style: Theme.of(context).textTheme.bodySmall
                              ?.copyWith(color: colorScheme.onSurfaceVariant),
                        ),
                      ],
                    ),
                  ),
                  TextButton.icon(
                    onPressed: _activeCount == 0 ? null : _resetDraft,
                    icon: const Icon(Icons.restart_alt_rounded, size: 18),
                    label: const Text('重置'),
                  ),
                ],
              ),
            ),
            Divider(color: colorScheme.outlineVariant.withValues(alpha: 0.5)),
            Expanded(
              child: ListView(
                controller: widget.scrollController,
                keyboardDismissBehavior:
                    ScrollViewKeyboardDismissBehavior.onDrag,
                padding: const EdgeInsets.only(bottom: AppSpacing.xl),
                children: [
                  const _SectionHeader(title: '作品类型', subtitle: '限定动画、小说或全部作品'),
                  SegmentedButton<String>(
                    segments: const [
                      ButtonSegment(
                        value: 'all',
                        label: Text('全部'),
                        icon: Icon(Icons.dashboard_outlined),
                      ),
                      ButtonSegment(
                        value: 'anime',
                        label: Text('动画'),
                        icon: Icon(Icons.ondemand_video_outlined),
                      ),
                      ButtonSegment(
                        value: 'book',
                        label: Text('小说'),
                        icon: Icon(Icons.menu_book_outlined),
                      ),
                    ],
                    selected: {_type},
                    showSelectedIcon: false,
                    onSelectionChanged: (selection) {
                      setState(() => _type = selection.first);
                    },
                  ),
                  const _SectionHeader(title: '追番状态', subtitle: '按当前观看阶段筛选'),
                  Wrap(
                    spacing: AppSpacing.sm,
                    runSpacing: AppSpacing.sm,
                    children: provider.statusOptions.map((status) {
                      final selected = _status == status;
                      final statusColor = provider.statusColors[status];
                      return ChoiceChip(
                        label: Text(status),
                        selected: selected,
                        showCheckmark: false,
                        selectedColor: (statusColor ?? colorScheme.primary)
                            .withValues(alpha: 0.18),
                        labelStyle: TextStyle(
                          color: selected
                              ? statusColor ?? colorScheme.primary
                              : colorScheme.onSurfaceVariant,
                          fontWeight: selected
                              ? FontWeight.w800
                              : FontWeight.w600,
                        ),
                        onSelected: (_) => setState(() => _status = status),
                      );
                    }).toList(),
                  ),
                  const _SectionHeader(title: '播出年份', subtitle: '可同时选择多个年份'),
                  Wrap(
                    spacing: AppSpacing.sm,
                    runSpacing: AppSpacing.sm,
                    children: [
                      FilterChip(
                        label: const Text('不限年份'),
                        selected: _years.isEmpty,
                        showCheckmark: false,
                        onSelected: (_) => setState(_years.clear),
                      ),
                      ...provider.availableYears.map((year) {
                        final selected = _years.contains(year);
                        return FilterChip(
                          label: Text(year),
                          selected: selected,
                          showCheckmark: false,
                          onSelected: (_) {
                            setState(() {
                              selected ? _years.remove(year) : _years.add(year);
                            });
                          },
                        );
                      }),
                    ],
                  ),
                  _SectionHeader(
                    title: '标签',
                    subtitle: _tagIds.length > 1
                        ? (_isAndMode ? '需同时满足全部标签' : '满足任一标签即可')
                        : '可组合多个标签',
                    trailing: FilterChip(
                      label: Text(_isAndMode ? '全部满足（且）' : '任一满足（或）'),
                      avatar: Icon(
                        _isAndMode
                            ? Icons.rule_rounded
                            : Icons.join_inner_rounded,
                        size: 17,
                      ),
                      selected: _isAndMode,
                      onSelected: _tagIds.length > 1
                          ? (value) => setState(() => _isAndMode = value)
                          : null,
                    ),
                  ),
                  if (provider.allTags.isEmpty)
                    Container(
                      padding: const EdgeInsets.all(AppSpacing.lg),
                      decoration: BoxDecoration(
                        color: colorScheme.surfaceContainer,
                        borderRadius: BorderRadius.circular(AppRadius.sm),
                      ),
                      child: Text(
                        '还没有可用标签',
                        style: TextStyle(color: colorScheme.onSurfaceVariant),
                      ),
                    )
                  else
                    Wrap(
                      spacing: AppSpacing.sm,
                      runSpacing: AppSpacing.sm,
                      children: provider.allTags.map((tag) {
                        final id = tag['id'] as int;
                        final selected = _tagIds.contains(id);
                        return FilterChip(
                          label: Text((tag['name'] ?? '').toString()),
                          selected: selected,
                          onSelected: (_) {
                            setState(() {
                              selected ? _tagIds.remove(id) : _tagIds.add(id);
                              if (_tagIds.length < 2) _isAndMode = false;
                            });
                          },
                        );
                      }).toList(),
                    ),
                ],
              ),
            ),
            SafeArea(
              top: false,
              minimum: const EdgeInsets.only(
                top: AppSpacing.md,
                bottom: AppSpacing.lg,
              ),
              child: Row(
                children: [
                  OutlinedButton(
                    onPressed: () => Navigator.pop(context),
                    child: const Text('取消'),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: () => _apply(provider),
                      icon: const Icon(Icons.check_rounded),
                      label: Text(
                        _activeCount == 0 ? '显示全部作品' : '应用 $_activeCount 组筛选',
                      ),
                    ),
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

class _SectionHeader extends StatelessWidget {
  final String title;
  final String subtitle;
  final Widget? trailing;

  const _SectionHeader({
    required this.title,
    required this.subtitle,
    this.trailing,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(top: AppSpacing.xl, bottom: AppSpacing.md),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title, style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 2),
                Text(
                  subtitle,
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
          if (trailing != null) ...[
            const SizedBox(width: AppSpacing.md),
            trailing!,
          ],
        ],
      ),
    );
  }
}
