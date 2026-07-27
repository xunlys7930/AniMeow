import 'package:flutter/material.dart';

import '../../components/anime_cover_image.dart';
import '../../neumorphic_style.dart';
import '../detail_props.dart';
import '../modules/header_module.dart';
import '../modules/module_factory.dart';
import 'classic_layout.dart';

/// 数据看板布局：宽屏两栏，窄屏退化为 classic
class DashboardLayout extends StatelessWidget {
  final DetailViewProps props;
  const DashboardLayout({super.key, required this.props});

  static const double _breakpoint = 720;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        if (constraints.maxWidth < _breakpoint) {
          // 窄屏直接走经典布局，保证可用性
          return ClassicLayout(props: props);
        }
        return _buildTwoColumn(context);
      },
    );
  }

  Widget _buildTwoColumn(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(24, 24, 24, 48),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 左栏：固定封面 + 统计卡
          SizedBox(
            width: 300,
            child: Column(
              children: [
                ClipRRect(
                  borderRadius: BorderRadius.circular(props.coverBorderRadius),
                  child: AspectRatio(
                    aspectRatio: 2 / 3,
                    child: Hero(
                      tag: 'cover_${props.anime['id'] ?? props.title}',
                      child: AnimeCoverImage(
                        url: props.coverUrl,
                        appDocDir: props.appDocDir,
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                NeumorphicContainer(
                  padding: const EdgeInsets.all(16),
                  child: _StatsCard(props: props),
                ),
              ],
            ),
          ),
          const SizedBox(width: 24),
          // 右栏：头部 + 模块列表
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                NeumorphicContainer(
                  padding: const EdgeInsets.all(20),
                  child: HeaderModule(props: props, compact: true),
                ),
                for (final module in props.orderedVisibleModules) ...[
                  const SizedBox(height: 16),
                  NeumorphicContainer(
                    padding: const EdgeInsets.all(20),
                    child: buildDetailModule(module, props),
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _StatsCard extends StatelessWidget {
  final DetailViewProps props;
  const _StatsCard({required this.props});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final watched = props.watchedEpisodes;
    final total = props.totalEpisodes;
    final percent = total > 0 ? (watched / total).clamp(0.0, 1.0) : 0.0;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (props.status.isNotEmpty)
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              color: props.statusColor.withValues(alpha: 0.14),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Text(
              props.status,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: props.statusColor,
                fontSize: 14,
                fontWeight: FontWeight.w800,
              ),
            ),
          ),
        if (props.hasRating) ...[
          const SizedBox(height: 12),
          _StatRow(
            label: '评分',
            value: props.ratingDetailLabel,
            color: cs.primary,
          ),
        ],
        if (total > 0) ...[
          const SizedBox(height: 12),
          _StatRow(label: '进度', value: '$watched / $total', color: cs.tertiary),
          const SizedBox(height: 6),
          ClipRRect(
            borderRadius: BorderRadius.circular(6),
            child: LinearProgressIndicator(
              value: percent,
              minHeight: 6,
              backgroundColor: cs.surfaceContainerHighest,
              valueColor: AlwaysStoppedAnimation(cs.tertiary),
            ),
          ),
        ],
        if (props.watchStartDate.isNotEmpty &&
            props.watchFinishDate.isNotEmpty) ...[
          const SizedBox(height: 12),
          _StatRow(
            label: props.subjectProfile.spanLabel,
            value: '${_watchedDays(props) ?? '-'} 天',
            color: cs.secondary,
          ),
        ],
      ],
    );
  }

  static int? _watchedDays(DetailViewProps p) {
    try {
      final s = DateTime.parse(p.watchStartDate);
      final e = DateTime.parse(p.watchFinishDate);
      final d = e.difference(s).inDays;
      return d >= 0 ? d + 1 : null;
    } catch (_) {
      return null;
    }
  }
}

class _StatRow extends StatelessWidget {
  final String label;
  final String value;
  final Color color;

  const _StatRow({
    required this.label,
    required this.value,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Text(
          label,
          style: TextStyle(
            fontSize: 12,
            color: Theme.of(context).colorScheme.onSurfaceVariant,
          ),
        ),
        const Spacer(),
        Text(
          value,
          style: TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.w800,
            color: color,
          ),
        ),
      ],
    );
  }
}
