import 'package:flutter/material.dart';
import '../../components/anime_cover_image.dart';
import '../../components/status_badge.dart';
import '../../design_tokens.dart';
import '../home_layout.dart';
import 'rating_icon.dart';
import '../../../utils/anime_rating.dart';

/// 横向布局的番剧卡片（左封面 + 右信息）
///
/// 共享给：
/// - Bento 首页的"在看"Hero 横滑（[size] = [AnimeListCardSize.hero]）
/// - CardFeed 视图的主列表（[size] = [AnimeListCardSize.regular]）
class AnimeListCard extends StatelessWidget {
  final Map<String, dynamic> item;
  final HomeViewProps props;
  final AnimeListCardSize size;
  final double? fixedWidth;

  const AnimeListCard({
    super.key,
    required this.item,
    required this.props,
    this.size = AnimeListCardSize.regular,
    this.fixedWidth,
  });

  bool get _isSeries => item['type'] == 'series';
  int get _id => item['id'] as int;
  bool get _isSelected => props.selectedIds.contains(_id);

  String get _title => _isSeries
      ? (item['name'] ?? item['series_name'] ?? '').toString()
      : (item['title'] ?? '').toString();

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final dim = _CardDimensions.fromSize(size);
    final coverRadius = props.coverBorderRadius;
    final status = (item['status'] ?? '').toString();
    final statusColor = props.statusColors[status] ?? Colors.grey;
    final rating = animeRatingOf(item);

    return GestureDetector(
      onTap: () => props.onItemTap(item),
      onLongPress: () => props.onItemLongPress(_id),
      child: Container(
        width: fixedWidth,
        height: dim.height,
        decoration: BoxDecoration(
          color: props.isSelectionMode && _isSelected
              ? colorScheme.primaryContainer.withValues(alpha: 0.4)
              : colorScheme.surfaceContainerLow,
          borderRadius: BorderRadius.circular(coverRadius),
          border: props.isSelectionMode && _isSelected
              ? Border.all(color: colorScheme.primary, width: 2)
              : null,
          boxShadow: AppElevation.card(context),
        ),
        clipBehavior: Clip.antiAlias,
        child: Row(
          children: [
            SizedBox(
              width: dim.coverWidth,
              height: dim.height,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  AnimeCoverImage(
                    url: item['cover_url'],
                    appDocDir: props.appDocDir,
                  ),
                  if (_isSeries && props.showStatus)
                    Positioned(
                      top: AppSpacing.sm,
                      left: AppSpacing.sm,
                      child: _SeriesChip(count: item['anime_count'] ?? 0),
                    ),
                ],
              ),
            ),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.all(AppSpacing.md),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    if (props.showTitle)
                      Text(
                        _title,
                        maxLines: dim.titleLines,
                        overflow: TextOverflow.ellipsis,
                        style: dim.titleStyle(context),
                      ),
                    const SizedBox(height: AppSpacing.sm),
                    Wrap(
                      spacing: AppSpacing.sm,
                      runSpacing: 4,
                      children: [
                        if (_isSeries)
                          _MiniChip(
                            icon: Icons.collections_bookmark_rounded,
                            text: '系列 · ${item['anime_count'] ?? 0} 部',
                            color: colorScheme.tertiary,
                          )
                        else ...[
                          if (props.showStatus && status.isNotEmpty)
                            StatusBadge(
                              status: status,
                              color: statusColor,
                              isCompact: true,
                            ),
                          if (props.showSubjectType)
                            _MiniChip(
                              icon: (item['subject_type'] ?? 'anime') == 'anime'
                                  ? Icons.movie_outlined
                                  : Icons.menu_book_outlined,
                              text: (item['subject_type'] ?? 'anime') == 'anime'
                                  ? '番剧'
                                  : '漫画',
                              color: colorScheme.onSurfaceVariant,
                            ),
                          if (props.showRating && rating.hasValue)
                            _RatingChip(label: rating.label),
                        ],
                      ],
                    ),
                    const Spacer(),
                    if (!_isSeries && props.showProgress)
                      _ProgressLine(
                        item: item,
                        progressText: props.progressTextOf(item),
                        accent: statusColor,
                      ),
                  ],
                ),
              ),
            ),
            if (props.isSelectionMode)
              Padding(
                padding: const EdgeInsets.only(right: AppSpacing.md),
                child: Icon(
                  _isSelected
                      ? Icons.check_circle_rounded
                      : Icons.radio_button_unchecked,
                  color: _isSelected
                      ? colorScheme.primary
                      : colorScheme.onSurfaceVariant,
                  size: 24,
                ),
              ),
          ],
        ),
      ),
    );
  }
}

enum AnimeListCardSize { regular, hero }

class _CardDimensions {
  final double height;
  final double coverWidth;
  final int titleLines;

  const _CardDimensions._({
    required this.height,
    required this.coverWidth,
    required this.titleLines,
  });

  factory _CardDimensions.fromSize(AnimeListCardSize size) {
    switch (size) {
      case AnimeListCardSize.hero:
        return const _CardDimensions._(
          height: 160,
          coverWidth: 112,
          titleLines: 2,
        );
      case AnimeListCardSize.regular:
        return const _CardDimensions._(
          height: 140,
          coverWidth: 100,
          titleLines: 2,
        );
    }
  }

  TextStyle titleStyle(BuildContext context) {
    final t = Theme.of(context).textTheme;
    switch (titleLines) {
      case 2:
        return (t.titleMedium ?? const TextStyle()).copyWith(
          fontWeight: FontWeight.w800,
        );
      default:
        return (t.titleSmall ?? const TextStyle()).copyWith(
          fontWeight: FontWeight.w700,
        );
    }
  }
}

class _MiniChip extends StatelessWidget {
  final IconData icon;
  final String text;
  final Color color;
  const _MiniChip({
    required this.icon,
    required this.text,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(AppRadius.xs),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 11, color: color),
          const SizedBox(width: 4),
          Text(
            text,
            style: TextStyle(
              fontSize: 11,
              color: color,
              fontWeight: FontWeight.w700,
            ),
          ),
        ],
      ),
    );
  }
}

class _RatingChip extends StatelessWidget {
  final String label;
  const _RatingChip({required this.label});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: Colors.amber.withValues(alpha: 0.16),
        borderRadius: BorderRadius.circular(AppRadius.xs),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const RatingIconWidget(size: 12),
          const SizedBox(width: 2),
          Text(
            label,
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w800,
              color: Colors.amber[900],
            ),
          ),
        ],
      ),
    );
  }
}

class _SeriesChip extends StatelessWidget {
  final int count;
  const _SeriesChip({required this.count});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.6),
        borderRadius: BorderRadius.circular(AppRadius.xs),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.layers, color: Colors.white, size: 12),
          const SizedBox(width: 4),
          Text(
            '$count',
            style: const TextStyle(
              color: Colors.white,
              fontSize: 10,
              fontWeight: FontWeight.w800,
            ),
          ),
        ],
      ),
    );
  }
}

class _ProgressLine extends StatelessWidget {
  final Map<String, dynamic> item;
  final String progressText;
  final Color accent;

  const _ProgressLine({
    required this.item,
    required this.progressText,
    required this.accent,
  });

  @override
  Widget build(BuildContext context) {
    final total = (item['total_episodes'] as int?) ?? 0;
    final watched = (item['watched_episodes'] as int?) ?? 0;
    final percent = total > 0 ? (watched / total).clamp(0.0, 1.0) : 0.0;
    final colorScheme = Theme.of(context).colorScheme;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (progressText.isNotEmpty)
          Text(
            progressText,
            style: TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w700,
              color: colorScheme.onSurfaceVariant,
            ),
          ),
        if (total > 0) ...[
          const SizedBox(height: 4),
          ClipRRect(
            borderRadius: BorderRadius.circular(2),
            child: LinearProgressIndicator(
              value: percent,
              minHeight: 4,
              backgroundColor: colorScheme.surfaceContainerHigh,
              valueColor: AlwaysStoppedAnimation(accent),
            ),
          ),
        ],
      ],
    );
  }
}
