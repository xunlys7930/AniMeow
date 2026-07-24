import 'package:flutter/material.dart';

import '../../components/adaptive_content_frame.dart';
import '../../customization/page_display_config.dart';
import '../../design_tokens.dart';
import 'calendar_display_config.dart';

class CalendarDisplaySheet extends StatelessWidget {
  final PageDisplayController controller;

  const CalendarDisplaySheet({super.key, required this.controller});

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
                      child: AdaptiveContentFrame(
                        maxContentWidth: 720,
                        top: AppSpacing.sm,
                        bottom: AppSpacing.xl,
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            _PresetSection(
                              config: config,
                              onSelect: (preset) => controller.setValue(
                                calendarPresetConfig(preset),
                              ),
                            ),
                            const SizedBox(height: AppSpacing.xl),
                            _DensitySection(
                              density: config.density,
                              onChanged: (density) => controller.setValue(
                                markCalendarCustom(
                                  config.copyWith(density: density),
                                ),
                              ),
                            ),
                            const SizedBox(height: AppSpacing.xl),
                            _MarkerSection(
                              config: config,
                              onChanged: (style) {
                                final options = Map<String, String>.from(
                                  config.options,
                                )..['marker_style'] = style.persistKey;
                                controller.setValue(
                                  markCalendarCustom(
                                    config.copyWith(options: options),
                                  ),
                                );
                              },
                            ),
                            const SizedBox(height: AppSpacing.xl),
                            _ContentSection(
                              config: config,
                              onOptionChanged: (key, value) {
                                final options = Map<String, String>.from(
                                  config.options,
                                )..[key] = value.toString();
                                controller.setValue(
                                  markCalendarCustom(
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
                                  markCalendarCustom(
                                    config.copyWith(moduleOrder: order),
                                  ),
                                );
                              },
                              onToggle: (moduleKey, visible) {
                                final hidden = Set<String>.from(
                                  config.hiddenModules,
                                );
                                final visibleCount =
                                    calendarModuleKeys.length - hidden.length;
                                if (!visible && visibleCount <= 1) {
                                  ScaffoldMessenger.of(context).showSnackBar(
                                    const SnackBar(
                                      content: Text('日历页至少需要保留一个模块'),
                                    ),
                                  );
                                  return;
                                }
                                if (visible) {
                                  hidden.remove(moduleKey);
                                } else {
                                  hidden.add(moduleKey);
                                }
                                controller.setValue(
                                  markCalendarCustom(
                                    config.copyWith(hiddenModules: hidden),
                                  ),
                                );
                              },
                            ),
                            const SizedBox(height: AppSpacing.md),
                            Text(
                              '设置会自动保存，并只影响日历页面。',
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
                  '定制追随日历',
                  style: Theme.of(
                    context,
                  ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w900),
                ),
                const SizedBox(height: 2),
                Text(
                  '调整模块顺序、事件标记与信息密度',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
          IconButton(
            tooltip: '恢复日历默认设置',
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
  final ValueChanged<CalendarPreset> onSelect;

  const _PresetSection({required this.config, required this.onSelect});

  @override
  Widget build(BuildContext context) {
    final selected = selectedCalendarPreset(config);
    return _SettingsSection(
      title: '布局预设',
      icon: Icons.auto_awesome_outlined,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: AppSpacing.sm,
            runSpacing: AppSpacing.sm,
            children: CalendarPreset.values.map((preset) {
              return ChoiceChip(
                selected: selected == preset,
                avatar: Icon(
                  selected == preset
                      ? Icons.check_rounded
                      : Icons.calendar_view_month_rounded,
                  size: 18,
                ),
                label: Text(preset.label),
                onSelected: (_) => onSelect(preset),
              );
            }).toList(),
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

class _MarkerSection extends StatelessWidget {
  final PageDisplayConfig config;
  final ValueChanged<CalendarMarkerStyle> onChanged;

  const _MarkerSection({required this.config, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    final selected = calendarMarkerStyle(config);
    return _SettingsSection(
      title: '日期事件标记',
      icon: Icons.event_available_outlined,
      child: Wrap(
        spacing: AppSpacing.sm,
        runSpacing: AppSpacing.sm,
        children: CalendarMarkerStyle.values.map((style) {
          return ChoiceChip(
            selected: selected == style,
            label: Text(style.label),
            onSelected: (_) => onChanged(style),
          );
        }).toList(),
      ),
    );
  }
}

class _ContentSection extends StatelessWidget {
  final PageDisplayConfig config;
  final void Function(String key, bool value) onOptionChanged;

  const _ContentSection({required this.config, required this.onOptionChanged});

  @override
  Widget build(BuildContext context) {
    return _SettingsSection(
      title: '内容显示',
      icon: Icons.visibility_outlined,
      child: Column(
        children: [
          SwitchListTile.adaptive(
            contentPadding: EdgeInsets.zero,
            title: const Text('显示作品封面'),
            subtitle: const Text('关闭后日程会更紧凑，适合小屏幕'),
            value: calendarShowCovers(config),
            onChanged: (value) => onOptionChanged('show_covers', value),
          ),
          SwitchListTile.adaptive(
            contentPadding: EdgeInsets.zero,
            title: const Text('显示事件颜色图例'),
            subtitle: const Text('解释开播、开始、完成与观看记录的颜色'),
            value: calendarShowLegend(config),
            onChanged: (value) => onOptionChanged('show_legend', value),
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
        .map(CalendarModule.fromPersistKey)
        .whereType<CalendarModule>()
        .toList(growable: false);

    return _SettingsSection(
      title: '模块顺序与显隐',
      icon: Icons.view_agenda_outlined,
      child: SizedBox(
        height: modules.length * 76.0,
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
