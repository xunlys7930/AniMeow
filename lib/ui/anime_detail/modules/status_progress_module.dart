import 'package:flutter/material.dart';

import '../detail_props.dart';
import 'module_section.dart';

/// 状态徽章 + watched/total + 进度条
class StatusProgressModule extends StatelessWidget {
  final DetailViewProps props;
  const StatusProgressModule({super.key, required this.props});

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final color = props.statusColor;
    final watched = props.watchedEpisodes;
    final total = props.totalEpisodes;
    final percent = total > 0 ? (watched / total).clamp(0.0, 1.0) : 0.0;

    return ModuleSection(
      icon: Icons.timeline_rounded,
      iconColor: color,
      title: props.subjectProfile.progressTitle,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 6,
                ),
                decoration: BoxDecoration(
                  color: color.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(20),
                  border: Border.all(color: color.withValues(alpha: 0.5)),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.flag_outlined, color: color, size: 16),
                    const SizedBox(width: 6),
                    Text(
                      props.status.isEmpty ? '未设置' : props.status,
                      style: TextStyle(
                        color: color,
                        fontWeight: FontWeight.w700,
                        fontSize: 13,
                      ),
                    ),
                  ],
                ),
              ),
              const Spacer(),
              if (total > 0)
                Text(
                  '$watched / $total',
                  style: TextStyle(
                    fontSize: 24,
                    fontWeight: FontWeight.w800,
                    color: color,
                    letterSpacing: -0.5,
                  ),
                )
              else
                Text(
                  '$watched ${props.subjectProfile.progressUnit}',
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w700,
                    color: colorScheme.onSurfaceVariant,
                  ),
                ),
            ],
          ),
          if (total > 0) ...[
            const SizedBox(height: 12),
            ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: LinearProgressIndicator(
                value: percent,
                minHeight: 8,
                backgroundColor: color.withValues(alpha: 0.12),
                valueColor: AlwaysStoppedAnimation(color),
              ),
            ),
            const SizedBox(height: 4),
            Text(
              '${(percent * 100).round()}% ${props.subjectProfile.progressCompletionLabel}',
              style: TextStyle(
                fontSize: 11,
                color: colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ],
      ),
    );
  }
}
