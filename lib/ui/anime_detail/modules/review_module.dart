import 'package:flutter/material.dart';

import '../detail_props.dart';
import 'module_section.dart';

/// 评价 / 备注（无内容时返回空）
class ReviewModule extends StatelessWidget {
  final DetailViewProps props;
  const ReviewModule({super.key, required this.props});

  @override
  Widget build(BuildContext context) {
    final text = props.review.trim();
    if (text.isEmpty) return const SizedBox.shrink();

    return ModuleSection(
      icon: Icons.edit_note_outlined,
      iconColor: Theme.of(context).colorScheme.tertiary,
      title: '评价 / 备注',
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surfaceContainerHigh,
          borderRadius: BorderRadius.circular(12),
        ),
        child: SelectableText(
          text,
          style: TextStyle(
            fontSize: 14,
            height: 1.6,
            color: Theme.of(context).colorScheme.onSurface,
          ),
        ),
      ),
    );
  }
}
