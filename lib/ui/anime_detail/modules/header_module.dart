import 'package:flutter/material.dart';

import '../detail_props.dart';

/// 详情页顶部头部块（封面外，由 layout 单独画封面）
///
/// 用于 classic / dashboard / minimal 这种"非 Hero"布局——把标题、放送、
/// 系列芯片、评分排成一组紧凑信息，配合左侧封面构成头部行。
/// Magazine 布局自己实现 Hero 头部，不用这个。
class HeaderModule extends StatelessWidget {
  final DetailViewProps props;

  /// 是否在 dashboard 模式下使用更紧凑的字号
  final bool compact;

  const HeaderModule({
    super.key,
    required this.props,
    this.compact = false,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final titleSize = compact ? 18.0 : 22.0;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          props.title,
          style: TextStyle(
            fontSize: titleSize,
            fontWeight: FontWeight.w800,
            height: 1.25,
          ),
        ),
        const SizedBox(height: 8),
        if (props.airDate.isNotEmpty)
          Row(
            children: [
              Icon(
                Icons.calendar_today_outlined,
                size: 14,
                color: cs.onSurfaceVariant,
              ),
              const SizedBox(width: 4),
              Flexible(
                child: Text(
                  props.airDate,
                  style: TextStyle(
                    fontSize: 12,
                    color: cs.onSurfaceVariant,
                    fontWeight: FontWeight.w600,
                  ),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ),
        const SizedBox(height: 8),
        // 类型 + 系列芯片
        Wrap(
          spacing: 6,
          runSpacing: 6,
          children: [
            _SubjectTypeChip(isAnime: props.isAnime),
            if (props.seriesName != null && props.seriesName!.isNotEmpty)
              Material(
                color: Colors.transparent,
                child: InkWell(
                  onTap: props.onSeriesTap,
                  borderRadius: BorderRadius.circular(8),
                  child: Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                    decoration: BoxDecoration(
                      color: cs.secondaryContainer,
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          Icons.collections_bookmark_outlined,
                          size: 13,
                          color: cs.onSecondaryContainer,
                        ),
                        const SizedBox(width: 4),
                        Flexible(
                          child: Text(
                            props.seriesName!,
                            style: TextStyle(
                              fontSize: 12,
                              color: cs.onSecondaryContainer,
                              fontWeight: FontWeight.w700,
                            ),
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
          ],
        ),
        if (props.rating > 0) ...[
          const SizedBox(height: 12),
          RatingDisplay(rating: props.rating, color: cs.primary),
        ],
      ],
    );
  }
}

class _SubjectTypeChip extends StatelessWidget {
  final bool isAnime;
  const _SubjectTypeChip({required this.isAnime});

  @override
  Widget build(BuildContext context) {
    final color = isAnime ? Colors.blue : Colors.teal;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: color.withValues(alpha: 0.35), width: 0.5),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            isAnime ? Icons.movie_filter_outlined : Icons.menu_book_rounded,
            size: 13,
            color: color,
          ),
          const SizedBox(width: 4),
          Text(
            isAnime ? '番剧' : '漫画 / 小说',
            style: TextStyle(
              fontSize: 12,
              color: color,
              fontWeight: FontWeight.w700,
            ),
          ),
        ],
      ),
    );
  }
}

/// 评分展示（5 颗星 + 数值）
class RatingDisplay extends StatelessWidget {
  final double rating; // 0~10
  final Color color;
  final double iconSize;
  final double textSize;

  const RatingDisplay({
    super.key,
    required this.rating,
    required this.color,
    this.iconSize = 18,
    this.textSize = 16,
  });

  @override
  Widget build(BuildContext context) {
    // 0~10 → 0~5 颗星
    final stars5 = rating / 2;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (var i = 0; i < 5; i++) _star(i, stars5),
        const SizedBox(width: 8),
        Text(
          rating.toStringAsFixed(1),
          style: TextStyle(
            color: color,
            fontWeight: FontWeight.w800,
            fontSize: textSize,
          ),
        ),
      ],
    );
  }

  Widget _star(int index, double stars5) {
    final filled = stars5 - index;
    IconData icon;
    if (filled >= 0.75) {
      icon = Icons.star_rounded;
    } else if (filled >= 0.25) {
      icon = Icons.star_half_rounded;
    } else {
      icon = Icons.star_outline_rounded;
    }
    return Icon(icon, color: color, size: iconSize);
  }
}
