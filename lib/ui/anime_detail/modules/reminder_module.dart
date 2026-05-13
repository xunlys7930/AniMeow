import 'package:flutter/material.dart';

import '../detail_props.dart';
import 'module_section.dart';

/// 追番提醒展示（无提醒时返回空）
class ReminderModule extends StatelessWidget {
  final DetailViewProps props;
  const ReminderModule({super.key, required this.props});

  @override
  Widget build(BuildContext context) {
    if (!props.hasReminder) return const SizedBox.shrink();
    final cs = Theme.of(context).colorScheme;
    final color = cs.tertiary;

    return ModuleSection(
      icon: Icons.notifications_active_outlined,
      iconColor: color,
      title: '追番提醒',
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: color.withValues(alpha: 0.2)),
        ),
        child: Row(
          children: [
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                color: color.withValues(alpha: 0.18),
                shape: BoxShape.circle,
              ),
              child: Icon(Icons.alarm, color: color, size: 22),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _dayLabel(props.reminderDay),
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    props.reminderTime ?? '',
                    style: TextStyle(
                      fontSize: 13,
                      color: cs.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
            Icon(Icons.notifications_active, color: color, size: 24),
          ],
        ),
      ),
    );
  }

  static String _dayLabel(int? day) {
    const labels = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
    if (day == null || day < 1 || day > 7) return '每周提醒';
    return labels[day - 1];
  }
}
