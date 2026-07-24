import 'package:flutter/material.dart';

import '../../components/adaptive_content_frame.dart';
import '../../customization/page_display_config.dart';
import '../../design_tokens.dart';
import 'anime_editor_display_config.dart';

class AnimeEditorDisplaySheet extends StatelessWidget {
  final PageDisplayController controller;

  const AnimeEditorDisplaySheet({super.key, required this.controller});

  @override
  Widget build(BuildContext context) {
    return FractionallySizedBox(
      heightFactor: 0.88,
      child: Material(
        color: Theme.of(context).colorScheme.surface,
        child: AnimatedBuilder(
          animation: controller,
          builder: (context, _) {
            final config = controller.value;
            return SafeArea(
              child: Column(
                children: [
                  _Header(
                    onReset: () => controller.reset(),
                    onClose: () => Navigator.of(context).pop(),
                  ),
                  Expanded(
                    child: SingleChildScrollView(
                      child: AdaptiveContentFrame(
                        maxContentWidth: 720,
                        top: AppSpacing.sm,
                        bottom: AppSpacing.xl,
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            _Section(
                              title: '编辑模式',
                              icon: Icons.tune_rounded,
                              child: _PresetSelector(
                                config: config,
                                onChanged: (preset) => controller.setValue(
                                  animeEditorPresetConfig(preset),
                                ),
                              ),
                            ),
                            const SizedBox(height: AppSpacing.xl),
                            _Section(
                              title: '信息密度',
                              icon: Icons.format_line_spacing_rounded,
                              child: SegmentedButton<DisplayDensity>(
                                segments: DisplayDensity.values
                                    .map(
                                      (density) => ButtonSegment(
                                        value: density,
                                        label: Text(density.label),
                                      ),
                                    )
                                    .toList(),
                                selected: {config.density},
                                onSelectionChanged: (selection) {
                                  controller.setValue(
                                    markAnimeEditorCustom(
                                      config.copyWith(density: selection.first),
                                    ),
                                  );
                                },
                              ),
                            ),
                            const SizedBox(height: AppSpacing.xl),
                            _Section(
                              title: '基本资料区',
                              icon: Icons.view_compact_alt_outlined,
                              child: SwitchListTile.adaptive(
                                contentPadding: EdgeInsets.zero,
                                title: const Text('显示放送时间与系列快捷项'),
                                subtitle: const Text('关闭后封面区只保留标题、搜索和评分'),
                                value: animeEditorShowAdvancedHeader(config),
                                onChanged: (value) {
                                  final options = Map<String, String>.from(
                                    config.options,
                                  )..['advanced_header'] = value.toString();
                                  controller.setValue(
                                    markAnimeEditorCustom(
                                      config.copyWith(options: options),
                                    ),
                                  );
                                },
                              ),
                            ),
                            const SizedBox(height: AppSpacing.xl),
                            _Section(
                              title: '模块顺序与显隐',
                              icon: Icons.view_agenda_outlined,
                              child: _ModuleList(
                                config: config,
                                onReorder: (oldIndex, newIndex) {
                                  final order = List<String>.from(
                                    config.moduleOrder,
                                  );
                                  final item = order.removeAt(oldIndex);
                                  order.insert(newIndex, item);
                                  controller.setValue(
                                    markAnimeEditorCustom(
                                      config.copyWith(moduleOrder: order),
                                    ),
                                  );
                                },
                                onToggle: (module, visible) {
                                  if (module == AnimeEditorModule.basic) return;
                                  final hidden = Set<String>.from(
                                    config.hiddenModules,
                                  );
                                  if (visible) {
                                    hidden.remove(module.persistKey);
                                  } else {
                                    hidden.add(module.persistKey);
                                  }
                                  controller.setValue(
                                    markAnimeEditorCustom(
                                      config.copyWith(hiddenModules: hidden),
                                    ),
                                  );
                                },
                              ),
                            ),
                            const SizedBox(height: AppSpacing.md),
                            Text(
                              '隐藏模块不会删除已有数据；设置会自动保存。',
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

class _Header extends StatelessWidget {
  final VoidCallback onReset;
  final VoidCallback onClose;

  const _Header({required this.onReset, required this.onClose});

  @override
  Widget build(BuildContext context) {
    return Padding(
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
                  '定制编辑页面',
                  style: Theme.of(
                    context,
                  ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w900),
                ),
                const SizedBox(height: 2),
                Text(
                  '在快速收录与完整资料维护之间自由切换',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
          IconButton(
            tooltip: '恢复编辑页默认设置',
            onPressed: onReset,
            icon: const Icon(Icons.restart_alt_rounded),
          ),
          IconButton(
            tooltip: '关闭',
            onPressed: onClose,
            icon: const Icon(Icons.close_rounded),
          ),
        ],
      ),
    );
  }
}

class _PresetSelector extends StatelessWidget {
  final PageDisplayConfig config;
  final ValueChanged<AnimeEditorPreset> onChanged;

  const _PresetSelector({required this.config, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    final selected = selectedAnimeEditorPreset(config);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SegmentedButton<AnimeEditorPreset>(
          segments: AnimeEditorPreset.values
              .map(
                (preset) => ButtonSegment(
                  value: preset,
                  label: Text(preset.label),
                  icon: Icon(
                    preset == AnimeEditorPreset.simple
                        ? Icons.bolt_outlined
                        : Icons.inventory_2_outlined,
                  ),
                ),
              )
              .toList(),
          selected: selected == null ? const {} : {selected},
          emptySelectionAllowed: true,
          onSelectionChanged: (selection) {
            if (selection.isNotEmpty) onChanged(selection.first);
          },
        ),
        if (selected != null) ...[
          const SizedBox(height: AppSpacing.md),
          Text(
            selected.description,
            style: Theme.of(context).textTheme.bodySmall?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
        ],
      ],
    );
  }
}

class _ModuleList extends StatelessWidget {
  final PageDisplayConfig config;
  final void Function(int oldIndex, int newIndex) onReorder;
  final void Function(AnimeEditorModule module, bool visible) onToggle;

  const _ModuleList({
    required this.config,
    required this.onReorder,
    required this.onToggle,
  });

  @override
  Widget build(BuildContext context) {
    final modules = config.moduleOrder
        .map(AnimeEditorModule.fromPersistKey)
        .whereType<AnimeEditorModule>()
        .toList(growable: false);
    return SizedBox(
      height: modules.length * 76.0,
      child: ReorderableListView.builder(
        buildDefaultDragHandles: false,
        physics: const NeverScrollableScrollPhysics(),
        itemCount: modules.length,
        onReorderItem: onReorder,
        itemBuilder: (context, index) {
          final module = modules[index];
          final required = module == AnimeEditorModule.basic;
          final hidden = config.hiddenModules.contains(module.persistKey);
          return ListTile(
            key: ValueKey(module.persistKey),
            contentPadding: EdgeInsets.zero,
            leading: ReorderableDragStartListener(
              index: index,
              child: const Icon(Icons.drag_indicator_rounded),
            ),
            title: Text(module.label),
            subtitle: Text(module.description),
            trailing: Switch(
              value: required || !hidden,
              onChanged: required
                  ? null
                  : (visible) => onToggle(module, visible),
            ),
          );
        },
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
