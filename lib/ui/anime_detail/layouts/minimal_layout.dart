import 'package:flutter/material.dart';

import '../../components/anime_cover_image.dart';
import '../detail_props.dart';
import '../modules/header_module.dart';
import '../modules/module_factory.dart';

/// 极简布局：封面 + 标题 + 状态评分 + 「展开全部」
class MinimalLayout extends StatefulWidget {
  final DetailViewProps props;
  const MinimalLayout({super.key, required this.props});

  @override
  State<MinimalLayout> createState() => _MinimalLayoutState();
}

class _MinimalLayoutState extends State<MinimalLayout> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final p = widget.props;
    final cs = Theme.of(context).colorScheme;

    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(24, 16, 24, 48),
      child: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 480),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.center,
            children: [
              // 封面
              ClipRRect(
                borderRadius: BorderRadius.circular(p.coverBorderRadius),
                child: SizedBox(
                  width: 200,
                  height: 280,
                  child: Hero(
                    tag: 'cover_${p.anime['id'] ?? p.title}',
                    child: AnimeCoverImage(
                      url: p.coverUrl,
                      appDocDir: p.appDocDir,
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 24),
              Text(
                p.title,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.w900,
                  height: 1.25,
                ),
              ),
              if (p.airDate.isNotEmpty) ...[
                const SizedBox(height: 6),
                Text(
                  p.airDate,
                  style: TextStyle(fontSize: 13, color: cs.onSurfaceVariant),
                ),
              ],
              const SizedBox(height: 16),
              // 状态 + 评分一行
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  if (p.status.isNotEmpty)
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 14,
                        vertical: 6,
                      ),
                      decoration: BoxDecoration(
                        color: p.statusColor.withValues(alpha: 0.14),
                        borderRadius: BorderRadius.circular(20),
                        border: Border.all(
                          color: p.statusColor.withValues(alpha: 0.4),
                        ),
                      ),
                      child: Text(
                        p.status,
                        style: TextStyle(
                          color: p.statusColor,
                          fontSize: 13,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                    ),
                  if (p.status.isNotEmpty && p.hasRating)
                    const SizedBox(width: 12),
                  if (p.hasRating)
                    RatingDisplay(
                      rating: p.rating,
                      grade: p.ratingGrade,
                      color: cs.primary,
                      iconSize: 18,
                      textSize: 16,
                    ),
                ],
              ),
              const SizedBox(height: 24),
              if (p.orderedVisibleModules.isNotEmpty)
                TextButton.icon(
                  onPressed: () => setState(() => _expanded = !_expanded),
                  icon: Icon(
                    _expanded
                        ? Icons.unfold_less_rounded
                        : Icons.unfold_more_rounded,
                  ),
                  label: Text(_expanded ? '收起详细信息' : '展开详细信息'),
                ),
              if (_expanded) ...[
                const SizedBox(height: 16),
                for (final module in p.orderedVisibleModules) ...[
                  buildDetailModule(module, p),
                  const SizedBox(height: 28),
                ],
              ],
            ],
          ),
        ),
      ),
    );
  }
}
