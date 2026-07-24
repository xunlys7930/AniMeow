import 'package:flutter/material.dart';

import '../settings_manager.dart';
import '../ui/anime_detail/detail_layout.dart';
import '../ui/design_tokens.dart';

/// 详情页模块管理页：拖拽重排 + 显隐切换
///
/// 模块由 [DetailModule] 枚举定义；顺序持久化在
/// [SettingsManager.detailModuleOrderNotifier]，隐藏集合在
/// [SettingsManager.detailHiddenModulesNotifier]。
class DetailModuleManagementPage extends StatefulWidget {
  const DetailModuleManagementPage({super.key});

  @override
  State<DetailModuleManagementPage> createState() =>
      _DetailModuleManagementPageState();
}

class _DetailModuleManagementPageState
    extends State<DetailModuleManagementPage> {
  late List<DetailModule> _order;
  late Set<DetailModule> _hidden;

  @override
  void initState() {
    super.initState();
    _order = List.of(SettingsManager().detailModuleOrderNotifier.value);
    _hidden = Set.of(SettingsManager().detailHiddenModulesNotifier.value);
  }

  Future<void> _resetToDefault() async {
    setState(() {
      _order = List.of(DetailModule.defaultOrder);
      _hidden = <DetailModule>{};
    });
    await SettingsManager().setDetailModuleOrder(_order);
    await SettingsManager().setDetailHiddenModules(_hidden);
  }

  Future<void> _onReorder(int oldIndex, int newIndex) async {
    setState(() {
      final m = _order.removeAt(oldIndex);
      _order.insert(newIndex, m);
    });
    await SettingsManager().setDetailModuleOrder(_order);
  }

  Future<void> _toggleHidden(DetailModule module, bool visible) async {
    setState(() {
      if (visible) {
        _hidden.remove(module);
      } else {
        _hidden.add(module);
      }
    });
    await SettingsManager().setDetailHiddenModules(_hidden);
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(
        title: const Text('详情页模块管理'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back_ios_new, size: 20),
          onPressed: () => Navigator.pop(context),
        ),
        actions: [
          TextButton.icon(
            onPressed: _resetToDefault,
            icon: const Icon(Icons.restart_alt, size: 18),
            label: const Text('重置'),
          ),
        ],
      ),
      body: Column(
        children: [
          Container(
            margin: const EdgeInsets.fromLTRB(
              AppSpacing.lg,
              AppSpacing.md,
              AppSpacing.lg,
              AppSpacing.sm,
            ),
            padding: const EdgeInsets.all(AppSpacing.md),
            decoration: BoxDecoration(
              color: cs.surfaceContainerHigh,
              borderRadius: BorderRadius.circular(AppRadius.sm),
            ),
            child: Row(
              children: [
                Icon(Icons.info_outline, size: 18, color: cs.primary),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    '长按拖动重新排序，开关控制是否显示。封面 / 标题 / 评分 始终保留在顶部。',
                    style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
                  ),
                ),
              ],
            ),
          ),
          Expanded(
            child: ReorderableListView.builder(
              padding: const EdgeInsets.fromLTRB(
                AppSpacing.lg,
                AppSpacing.sm,
                AppSpacing.lg,
                AppSpacing.xl,
              ),
              itemCount: _order.length,
              onReorderItem: _onReorder,
              buildDefaultDragHandles: false,
              proxyDecorator: (child, _, _) => Material(
                color: Colors.transparent,
                elevation: 6,
                borderRadius: BorderRadius.circular(AppRadius.sm),
                child: child,
              ),
              itemBuilder: (context, index) {
                final module = _order[index];
                final hidden = _hidden.contains(module);
                return _ModuleRow(
                  key: ValueKey(module.persistKey),
                  module: module,
                  hidden: hidden,
                  index: index,
                  onToggle: (v) => _toggleHidden(module, v),
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}

class _ModuleRow extends StatelessWidget {
  final DetailModule module;
  final bool hidden;
  final int index;
  final ValueChanged<bool> onToggle;

  const _ModuleRow({
    super.key,
    required this.module,
    required this.hidden,
    required this.index,
    required this.onToggle,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: Container(
        decoration: BoxDecoration(
          color: cs.surfaceContainerLow,
          borderRadius: BorderRadius.circular(AppRadius.sm),
        ),
        padding: const EdgeInsets.symmetric(
          horizontal: AppSpacing.md,
          vertical: AppSpacing.sm,
        ),
        child: Row(
          children: [
            ReorderableDragStartListener(
              index: index,
              child: const Padding(
                padding: EdgeInsets.symmetric(horizontal: 4),
                child: Icon(Icons.drag_indicator, color: Colors.grey),
              ),
            ),
            const SizedBox(width: 8),
            Container(
              width: 36,
              height: 36,
              decoration: BoxDecoration(
                color: cs.primaryContainer,
                borderRadius: BorderRadius.circular(AppRadius.xs),
              ),
              child: Icon(module.icon, size: 20, color: cs.onPrimaryContainer),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    module.label,
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  Text(
                    module.description,
                    style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
                  ),
                ],
              ),
            ),
            Switch(value: !hidden, onChanged: onToggle),
          ],
        ),
      ),
    );
  }
}
