import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:anime_tracker/ui/design_tokens.dart';
import '../anime_list_provider.dart';

/// 排序选择面板。点击当前项切换升降序，点击其他项直接切换主排序。
class SortMenuSheet extends StatelessWidget {
  final ScrollController scrollController;

  const SortMenuSheet({super.key, required this.scrollController});

  static const _sortIcons = {
    '默认 (最新添加)': Icons.history_rounded,
    '播出日期': Icons.calendar_month_outlined,
    '我看完的时间': Icons.done_all_rounded,
    '评分': Icons.star_outline_rounded,
    '拼音': Icons.sort_by_alpha_rounded,
  };

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<AnimeListProvider>();
    final colorScheme = Theme.of(context).colorScheme;
    final selectedKey = provider.selectedSortLabel;

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
                      Icons.sort_rounded,
                      color: colorScheme.onPrimaryContainer,
                    ),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '排序方式',
                          style: Theme.of(context).textTheme.titleLarge,
                        ),
                        Text(
                          '再次点击当前方式可切换升降序',
                          style: Theme.of(context).textTheme.bodySmall
                              ?.copyWith(color: colorScheme.onSurfaceVariant),
                        ),
                      ],
                    ),
                  ),
                  TextButton.icon(
                    onPressed:
                        selectedKey == '默认 (最新添加)' &&
                            provider.selectedSortDirection == 'DESC'
                        ? null
                        : provider.resetSort,
                    icon: const Icon(Icons.restart_alt_rounded, size: 18),
                    label: const Text('默认'),
                  ),
                ],
              ),
            ),
            Divider(color: colorScheme.outlineVariant.withValues(alpha: 0.5)),
            Expanded(
              child: ListView.separated(
                controller: scrollController,
                padding: const EdgeInsets.symmetric(vertical: AppSpacing.lg),
                itemCount: provider.sortDefinitions.length,
                separatorBuilder: (_, _) =>
                    const SizedBox(height: AppSpacing.sm),
                itemBuilder: (context, index) {
                  final key = provider.sortDefinitions.keys.elementAt(index);
                  final selected = key == selectedKey;
                  final direction = provider.sortDirections[key] ?? 'DESC';
                  final ascending = direction == 'ASC';

                  return AnimatedContainer(
                    duration: AppMotion.resolve(context, AppMotion.short),
                    curve: AppMotion.emphasized,
                    decoration: BoxDecoration(
                      color: selected
                          ? colorScheme.secondaryContainer
                          : colorScheme.surfaceContainer,
                      borderRadius: BorderRadius.circular(AppRadius.sm),
                      border: Border.all(
                        color: selected
                            ? colorScheme.primary.withValues(alpha: 0.35)
                            : colorScheme.outlineVariant.withValues(
                                alpha: 0.35,
                              ),
                      ),
                    ),
                    child: InkWell(
                      borderRadius: BorderRadius.circular(AppRadius.sm),
                      onTap: () {
                        if (selected) {
                          provider.updateSortDirection(
                            key,
                            ascending ? 'DESC' : 'ASC',
                          );
                        } else {
                          provider.setSortKeys([key]);
                        }
                      },
                      child: Padding(
                        padding: const EdgeInsets.all(AppSpacing.md),
                        child: Row(
                          children: [
                            Container(
                              width: 42,
                              height: 42,
                              decoration: BoxDecoration(
                                color: selected
                                    ? colorScheme.primary
                                    : colorScheme.surfaceContainerHighest,
                                borderRadius: BorderRadius.circular(
                                  AppRadius.xs,
                                ),
                              ),
                              child: Icon(
                                _sortIcons[key] ?? Icons.sort_rounded,
                                color: selected
                                    ? colorScheme.onPrimary
                                    : colorScheme.onSurfaceVariant,
                                size: 21,
                              ),
                            ),
                            const SizedBox(width: AppSpacing.md),
                            Expanded(
                              child: Text(
                                key,
                                style: Theme.of(context).textTheme.titleSmall
                                    ?.copyWith(
                                      color: selected
                                          ? colorScheme.onSecondaryContainer
                                          : colorScheme.onSurface,
                                      fontWeight: selected
                                          ? FontWeight.w900
                                          : FontWeight.w600,
                                    ),
                              ),
                            ),
                            if (selected)
                              Container(
                                padding: const EdgeInsets.symmetric(
                                  horizontal: AppSpacing.md,
                                  vertical: AppSpacing.sm,
                                ),
                                decoration: BoxDecoration(
                                  color: colorScheme.surface.withValues(
                                    alpha: 0.65,
                                  ),
                                  borderRadius: BorderRadius.circular(
                                    AppRadius.full,
                                  ),
                                ),
                                child: Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Icon(
                                      ascending
                                          ? Icons.arrow_upward_rounded
                                          : Icons.arrow_downward_rounded,
                                      size: 16,
                                      color: colorScheme.primary,
                                    ),
                                    const SizedBox(width: AppSpacing.xs),
                                    Text(
                                      ascending ? '升序' : '降序',
                                      style: Theme.of(context)
                                          .textTheme
                                          .labelMedium
                                          ?.copyWith(
                                            color: colorScheme.primary,
                                            fontWeight: FontWeight.w800,
                                          ),
                                    ),
                                  ],
                                ),
                              )
                            else
                              Icon(
                                Icons.chevron_right_rounded,
                                color: colorScheme.onSurfaceVariant,
                              ),
                          ],
                        ),
                      ),
                    ),
                  );
                },
              ),
            ),
            SafeArea(
              top: false,
              minimum: const EdgeInsets.only(bottom: AppSpacing.lg),
              child: FilledButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('完成'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
