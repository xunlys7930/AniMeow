import 'package:flutter/material.dart';

import '../../components/adaptive_content_frame.dart';
import '../../customization/page_display_config.dart';
import '../../design_tokens.dart';
import 'statistics_display_config.dart';

class StatisticsDisplaySheet extends StatelessWidget {
  final PageDisplayController controller;

  const StatisticsDisplaySheet({super.key, required this.controller});

  @override
  Widget build(BuildContext context) {
    return FractionallySizedBox(
      heightFactor: 0.9,
      child: Material(
        color: Theme.of(context).colorScheme.surface,
        child: AnimatedBuilder(
          animation: controller,
          builder: (context, _) {
            final config = controller.value;
            return SafeArea(
              child: Column(
                children: [
                  _SheetHeader(
                    onReset: () => controller.reset(),
                    onClose: () => Navigator.of(context).pop(),
                  ),
                  Expanded(
                    child: SingleChildScrollView(
                      padding: EdgeInsets.zero,
                      child: AdaptiveContentFrame(
                        maxContentWidth: 720,
                        top: AppSpacing.sm,
                        bottom: AppSpacing.xl,
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            _PresetSection(
                              config: config,
                              onSelect: (preset) {
                                controller.setValue(
                                  statisticsPresetConfig(preset),
                                );
                              },
                            ),
                            const SizedBox(height: AppSpacing.xl),
                            _DensitySection(
                              density: config.density,
                              onChanged: (density) {
                                controller.setValue(
                                  markStatisticsCustom(
                                    config.copyWith(density: density),
                                  ),
                                );
                              },
                            ),
                            const SizedBox(height: AppSpacing.xl),
                            _ChartSection(
                              config: config,
                              onChanged: (key, style) {
                                final options = Map<String, String>.from(
                                  config.options,
                                )..[key] = style.persistKey;
                                controller.setValue(
                                  markStatisticsCustom(
                                    config.copyWith(options: options),
                                  ),
                                );
                              },
                            ),
                            const SizedBox(height: AppSpacing.xl),
                            _ModuleSection(
                              config: config,
                              onReorder: (oldIndex, newIndex) {
                                final order = List<String>.from(
                                  config.moduleOrder,
                                );
                                final item = order.removeAt(oldIndex);
                                order.insert(newIndex, item);
                                controller.setValue(
                                  markStatisticsCustom(
                                    config.copyWith(moduleOrder: order),
                                  ),
                                );
                              },
                              onToggle: (moduleKey, visible) {
                                final hidden = Set<String>.from(
                                  config.hiddenModules,
                                );
                                if (visible) {
                                  hidden.remove(moduleKey);
                                } else {
                                  hidden.add(moduleKey);
                                }
                                controller.setValue(
                                  markStatisticsCustom(
                                    config.copyWith(hiddenModules: hidden),
                                  ),
                                );
                              },
                            ),
                            const SizedBox(height: AppSpacing.md),
                            Text(
                              '更改会自动保存，并只影响统计页面。',
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

class _SheetHeader extends StatelessWidget {
  final VoidCallback onReset;
  final VoidCallback onClose;

  const _SheetHeader({required this.onReset, required this.onClose});

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
                  '定制统计页面',
                  style: Theme.of(
                    context,
                  ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w900),
                ),
                const SizedBox(height: 2),
                Text(
                  '选择预设，或继续调整模块和图表',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
          IconButton(
            tooltip: '恢复统计页默认设置',
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

class _PresetSection extends StatelessWidget {
  final PageDisplayConfig config;
  final ValueChanged<StatisticsPreset> onSelect;

  const _PresetSection({required this.config, required this.onSelect});

  @override
  Widget build(BuildContext context) {
    final selected = selectedStatisticsPreset(config);
    return _SettingsSection(
      title: '布局预设',
      icon: Icons.auto_awesome_outlined,
      child: Wrap(
        spacing: AppSpacing.sm,
        runSpacing: AppSpacing.sm,
        children: StatisticsPreset.values.map((preset) {
          return ChoiceChip(
            selected: selected == preset,
            label: Text(preset.label),
            avatar: Icon(
              selected == preset
                  ? Icons.check_rounded
                  : Icons.dashboard_outlined,
              size: 18,
            ),
            onSelected: (_) => onSelect(preset),
          );
        }).toList(),
      ),
    );
  }
}

class _DensitySection extends StatelessWidget {
  final DisplayDensity density;
  final ValueChanged<DisplayDensity> onChanged;

  const _DensitySection({required this.density, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    return _SettingsSection(
      title: '信息密度',
      icon: Icons.format_line_spacing_rounded,
      child: SegmentedButton<DisplayDensity>(
        segments: DisplayDensity.values
            .map(
              (item) => ButtonSegment<DisplayDensity>(
                value: item,
                label: Text(item.label),
              ),
            )
            .toList(),
        selected: {density},
        onSelectionChanged: (selection) => onChanged(selection.first),
      ),
    );
  }
}

class _ChartSection extends StatelessWidget {
  final PageDisplayConfig config;
  final void Function(String key, StatisticsChartStyle style) onChanged;

  const _ChartSection({required this.config, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    return _SettingsSection(
      title: '图表样式',
      icon: Icons.data_usage_rounded,
      child: Column(
        children: [
          _ChartChoiceTile(
            title: '状态分布',
            value: statisticsStatusChart(config),
            onChanged: (style) => onChanged('status_chart', style),
          ),
          const Divider(height: 1),
          _ChartChoiceTile(
            title: '标签分布',
            value: statisticsTagChart(config),
            onChanged: (style) => onChanged('tag_chart', style),
          ),
        ],
      ),
    );
  }
}

class _ChartChoiceTile extends StatelessWidget {
  final String title;
  final StatisticsChartStyle value;
  final ValueChanged<StatisticsChartStyle> onChanged;

  const _ChartChoiceTile({
    required this.title,
    required this.value,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: AppSpacing.sm),
      child: Row(
        children: [
          Expanded(child: Text(title)),
          DropdownButtonHideUnderline(
            child: DropdownButton<StatisticsChartStyle>(
              value: value,
              items: StatisticsChartStyle.values
                  .map(
                    (style) => DropdownMenuItem(
                      value: style,
                      child: Text(style.label),
                    ),
                  )
                  .toList(),
              onChanged: (style) {
                if (style != null) onChanged(style);
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _ModuleSection extends StatelessWidget {
  final PageDisplayConfig config;
  final void Function(int oldIndex, int newIndex) onReorder;
  final void Function(String moduleKey, bool visible) onToggle;

  const _ModuleSection({
    required this.config,
    required this.onReorder,
    required this.onToggle,
  });

  @override
  Widget build(BuildContext context) {
    final modules = config.moduleOrder
        .map(StatisticsModule.fromPersistKey)
        .whereType<StatisticsModule>()
        .toList(growable: false);

    return _SettingsSection(
      title: '模块顺序与显隐',
      icon: Icons.view_agenda_outlined,
      child: SizedBox(
        height: modules.length * 72.0,
        child: ReorderableListView.builder(
          buildDefaultDragHandles: false,
          physics: const NeverScrollableScrollPhysics(),
          itemCount: modules.length,
          onReorderItem: onReorder,
          itemBuilder: (context, index) {
            final module = modules[index];
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
                value: !hidden,
                onChanged: (visible) => onToggle(module.persistKey, visible),
              ),
            );
          },
        ),
      ),
    );
  }
}

class _SettingsSection extends StatelessWidget {
  final String title;
  final IconData icon;
  final Widget child;

  const _SettingsSection({
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
