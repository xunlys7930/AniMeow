import 'package:flutter/material.dart';

import '../../components/adaptive_content_frame.dart';
import '../../customization/page_display_config.dart';
import '../../design_tokens.dart';
import 'character_display_config.dart';

class CharacterDisplaySheet extends StatelessWidget {
  final PageDisplayController controller;

  const CharacterDisplaySheet({super.key, required this.controller});

  @override
  Widget build(BuildContext context) {
    return FractionallySizedBox(
      heightFactor: 0.68,
      child: Material(
        color: Theme.of(context).colorScheme.surface,
        child: AnimatedBuilder(
          animation: controller,
          builder: (context, _) {
            final config = controller.value;
            return SafeArea(
              child: Column(
                children: [
                  Padding(
                    padding: const EdgeInsets.fromLTRB(
                      AppSpacing.lg,
                      AppSpacing.md,
                      AppSpacing.sm,
                      AppSpacing.sm,
                    ),
                    child: Row(
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                '定制角色管理',
                                style: Theme.of(context).textTheme.titleLarge
                                    ?.copyWith(fontWeight: FontWeight.w900),
                              ),
                              Text(
                                '调整角色列表的阅读方式和信息量',
                                style: Theme.of(context).textTheme.bodySmall
                                    ?.copyWith(
                                      color: Theme.of(
                                        context,
                                      ).colorScheme.onSurfaceVariant,
                                    ),
                              ),
                            ],
                          ),
                        ),
                        IconButton(
                          tooltip: '恢复角色管理默认设置',
                          onPressed: () => controller.reset(),
                          icon: const Icon(Icons.restart_alt_rounded),
                        ),
                        IconButton(
                          tooltip: '关闭',
                          onPressed: () => Navigator.of(context).pop(),
                          icon: const Icon(Icons.close_rounded),
                        ),
                      ],
                    ),
                  ),
                  Expanded(
                    child: SingleChildScrollView(
                      child: AdaptiveContentFrame(
                        maxContentWidth: 640,
                        top: AppSpacing.sm,
                        bottom: AppSpacing.xl,
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            _Section(
                              title: '角色列表样式',
                              icon: Icons.view_comfy_alt_outlined,
                              child: SegmentedButton<CharacterListView>(
                                segments: CharacterListView.values
                                    .map(
                                      (view) =>
                                          ButtonSegment<CharacterListView>(
                                            value: view,
                                            label: Text(view.label),
                                            icon: Icon(
                                              view == CharacterListView.list
                                                  ? Icons.view_list_rounded
                                                  : Icons.grid_view_rounded,
                                            ),
                                          ),
                                    )
                                    .toList(),
                                selected: {characterListView(config)},
                                onSelectionChanged: (selection) {
                                  final options = Map<String, String>.from(
                                    config.options,
                                  )..['view'] = selection.first.persistKey;
                                  controller.setValue(
                                    markCharacterCustom(
                                      config.copyWith(options: options),
                                    ),
                                  );
                                },
                              ),
                            ),
                            const SizedBox(height: AppSpacing.xl),
                            _Section(
                              title: '信息密度',
                              icon: Icons.format_line_spacing_rounded,
                              child: SegmentedButton<DisplayDensity>(
                                segments: DisplayDensity.values
                                    .map(
                                      (density) =>
                                          ButtonSegment<DisplayDensity>(
                                            value: density,
                                            label: Text(density.label),
                                          ),
                                    )
                                    .toList(),
                                selected: {config.density},
                                onSelectionChanged: (selection) {
                                  controller.setValue(
                                    markCharacterCustom(
                                      config.copyWith(density: selection.first),
                                    ),
                                  );
                                },
                              ),
                            ),
                            const SizedBox(height: AppSpacing.xl),
                            _Section(
                              title: '列表信息',
                              icon: Icons.info_outline_rounded,
                              child: Column(
                                children: [
                                  SwitchListTile.adaptive(
                                    contentPadding: EdgeInsets.zero,
                                    title: const Text('显示作品、关系和标签数量'),
                                    subtitle: const Text('关闭后只保留角色姓名和头像'),
                                    value: characterShowMetadata(config),
                                    onChanged: (visible) {
                                      final hidden = Set<String>.from(
                                        config.hiddenModules,
                                      );
                                      if (visible) {
                                        hidden.remove('metadata');
                                      } else {
                                        hidden.add('metadata');
                                      }
                                      controller.setValue(
                                        markCharacterCustom(
                                          config.copyWith(
                                            hiddenModules: hidden,
                                          ),
                                        ),
                                      );
                                    },
                                  ),
                                  SwitchListTile.adaptive(
                                    contentPadding: EdgeInsets.zero,
                                    title: const Text('显示评分'),
                                    value: characterShowRating(config),
                                    onChanged: (visible) {
                                      final hidden = Set<String>.from(
                                        config.hiddenModules,
                                      );
                                      if (visible) {
                                        hidden.remove('rating');
                                      } else {
                                        hidden.add('rating');
                                      }
                                      controller.setValue(
                                        markCharacterCustom(
                                          config.copyWith(
                                            hiddenModules: hidden,
                                          ),
                                        ),
                                      );
                                    },
                                  ),
                                ],
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            );
          },
        ),
      ),
    );
  }
}

class _Section extends StatelessWidget {
  final String title;
  final IconData icon;
  final Widget child;

  const _Section({
    required this.title,
    required this.icon,
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(icon, size: 20, color: colorScheme.primary),
            const SizedBox(width: AppSpacing.sm),
            Text(
              title,
              style: Theme.of(
                context,
              ).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w900),
            ),
          ],
        ),
        const SizedBox(height: AppSpacing.md),
        Card(
          margin: EdgeInsets.zero,
          elevation: 0,
          color: colorScheme.surfaceContainerLow,
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.md),
            child: child,
          ),
        ),
      ],
    );
  }
}
