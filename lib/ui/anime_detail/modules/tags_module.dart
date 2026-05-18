import 'package:flutter/material.dart';

import '../detail_props.dart';
import 'module_section.dart';

/// 标签 chip 列表（无标签时返回空 widget，layout 应跳过）
class TagsModule extends StatelessWidget {
  final DetailViewProps props;
  const TagsModule({super.key, required this.props});

  @override
  Widget build(BuildContext context) {
    if (props.tags.isEmpty) return const SizedBox.shrink();

    return ModuleSection(
      icon: Icons.label_outline,
      iconColor: Theme.of(context).colorScheme.primary,
      title: '标签',
      trailing: Text(
        '${props.tags.length} 个',
        style: TextStyle(
          fontSize: 12,
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        ),
      ),
      child: Wrap(
        spacing: 8,
        runSpacing: 8,
        children: props.tags.map((tag) {
          final colorValue = tag['color'];
          final color = colorValue is int
              ? Color(colorValue)
              : Theme.of(context).colorScheme.primary;
          return Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            decoration: BoxDecoration(
              color: color.withValues(alpha: 0.12),
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: color.withValues(alpha: 0.4)),
            ),
            child: Text(
              (tag['name'] ?? '').toString(),
              style: TextStyle(
                color: color,
                fontSize: 12,
                fontWeight: FontWeight.w700,
              ),
            ),
          );
        }).toList(),
      ),
    );
  }
}
