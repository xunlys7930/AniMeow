import 'package:flutter/material.dart';

import '../detail_props.dart';
import 'module_section.dart';

/// 详细元数据：放送/出版信息与观看/阅读起止日期
///
/// 自动跳过空字段；若全部为空则整个模块返回空。
class DetailMetaModule extends StatelessWidget {
  final DetailViewProps props;
  const DetailMetaModule({super.key, required this.props});

  @override
  Widget build(BuildContext context) {
    final rows = <_MetaRow>[];

    if (props.airDate.isNotEmpty) {
      rows.add(
        _MetaRow(
          icon: Icons.calendar_today_outlined,
          label: props.subjectProfile.dateLabel,
          value: props.airDate,
        ),
      );
    }
    if (props.studio.isNotEmpty) {
      rows.add(
        _MetaRow(
          icon: props.isAnime
              ? Icons.business_outlined
              : Icons.history_edu_outlined,
          label: props.subjectProfile.providerLabel,
          value: props.studio,
        ),
      );
    }
    if (props.watchStartDate.isNotEmpty) {
      rows.add(
        _MetaRow(
          icon: Icons.play_arrow_outlined,
          label: props.subjectProfile.startLabel,
          value: props.watchStartDate,
        ),
      );
    }
    if (props.watchFinishDate.isNotEmpty) {
      rows.add(
        _MetaRow(
          icon: Icons.done_all_outlined,
          label: props.subjectProfile.finishLabel,
          value: props.watchFinishDate,
        ),
      );
    }
    // 已观看/阅读天数（若起止齐全）
    final span = _watchedDays(props);
    if (span != null) {
      rows.add(
        _MetaRow(
          icon: Icons.access_time_outlined,
          label: props.subjectProfile.spanLabel,
          value: '$span 天',
        ),
      );
    }

    if (rows.isEmpty) return const SizedBox.shrink();

    return ModuleSection(
      icon: Icons.info_outline,
      iconColor: Theme.of(context).colorScheme.primary,
      title: '详细信息',
      child: Column(
        children: [
          for (var i = 0; i < rows.length; i++) ...[
            rows[i],
            if (i != rows.length - 1)
              Divider(
                height: 1,
                color: Theme.of(context).dividerColor.withValues(alpha: 0.4),
              ),
          ],
        ],
      ),
    );
  }

  int? _watchedDays(DetailViewProps p) {
    if (p.watchStartDate.isEmpty || p.watchFinishDate.isEmpty) return null;
    try {
      final start = DateTime.parse(p.watchStartDate);
      final end = DateTime.parse(p.watchFinishDate);
      final diff = end.difference(start).inDays;
      return diff >= 0 ? diff + 1 : null;
    } catch (_) {
      return null;
    }
  }
}

class _MetaRow extends StatelessWidget {
  final IconData icon;
  final String label;
  final String value;

  const _MetaRow({
    required this.icon,
    required this.label,
    required this.value,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Row(
        children: [
          Icon(icon, size: 18, color: cs.onSurfaceVariant),
          const SizedBox(width: 12),
          Text(
            label,
            style: TextStyle(fontSize: 13, color: cs.onSurfaceVariant),
          ),
          const Spacer(),
          Flexible(
            child: Text(
              value,
              textAlign: TextAlign.right,
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
    );
  }
}
