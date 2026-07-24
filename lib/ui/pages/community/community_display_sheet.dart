import 'package:flutter/material.dart';

import '../../components/adaptive_content_frame.dart';
import '../../customization/page_display_config.dart';
import '../../design_tokens.dart';
import 'community_display_config.dart';

class CommunityDisplaySheet extends StatelessWidget {
  final PageDisplayController controller;

  const CommunityDisplaySheet({super.key, required this.controller});

  @override
  Widget build(BuildContext context) {
    return FractionallySizedBox(
      heightFactor: 0.72,
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
                                '定制群组社区',
                                style: Theme.of(context).textTheme.titleLarge
                                    ?.copyWith(fontWeight: FontWeight.w900),
                              ),
                              Text(
                                '社区发现与本地群组共用这套显示偏好',
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
                          tooltip: '恢复默认设置',
                          onPressed: controller.reset,
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
                              title: '群组排列',
                              icon: Icons.view_comfy_alt_outlined,
                              child: SegmentedButton<CommunityGroupView>(
                                segments: CommunityGroupView.values
                                    .map(
                                      (view) =>
                                          ButtonSegment<CommunityGroupView>(
                                            value: view,
                                            label: Text(view.label),
                                            icon: Icon(
                                              view == CommunityGroupView.grid
                                                  ? Icons.grid_view_rounded
                                                  : Icons.view_list_rounded,
                                            ),
                                          ),
                                    )
                                    .toList(),
                                selected: {communityGroupView(config)},
                                onSelectionChanged: (selection) {
                                  final options = Map<String, String>.from(
                                    config.options,
                                  )..['view'] = selection.first.persistKey;
                                  controller.setValue(
                                    markCommunityCustom(
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
                                    markCommunityCustom(
                                      config.copyWith(density: selection.first),
                                    ),
                                  );
                                },
                              ),
                            ),
                            const SizedBox(height: AppSpacing.xl),
                            _Section(
                              title: '卡片信息',
                              icon: Icons.info_outline_rounded,
                              child: Column(
                                children: [
                                  SwitchListTile.adaptive(
                                    contentPadding: EdgeInsets.zero,
                                    title: const Text('显示群组简介'),
                                    subtitle: const Text('关闭后卡片更紧凑，详情页仍保留简介'),
                                    value: communityShowDescription(config),
                                    onChanged: (visible) => _setModuleVisible(
                                      controller,
                                      config,
                                      'description',
                                      visible,
                                    ),
                                  ),
                                  SwitchListTile.adaptive(
                                    contentPadding: EdgeInsets.zero,
                                    title: const Text('显示社区导入次数'),
                                    value: communityShowDownloads(config),
                                    onChanged: (visible) => _setModuleVisible(
                                      controller,
                                      config,
                                      'downloads',
                                      visible,
                                    ),
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

  void _setModuleVisible(
    PageDisplayController controller,
    PageDisplayConfig config,
    String module,
    bool visible,
  ) {
    final hidden = Set<String>.from(config.hiddenModules);
    if (visible) {
      hidden.remove(module);
    } else {
      hidden.add(module);
    }
    controller.setValue(
      markCommunityCustom(config.copyWith(hiddenModules: hidden)),
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
