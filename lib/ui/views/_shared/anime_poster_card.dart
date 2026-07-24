import 'package:flutter/material.dart';

import '../../../utils/anime_rating.dart';
import '../../components/anime_cover_image.dart';
import '../../design_tokens.dart';
import '../home_layout.dart';
import 'rating_icon.dart';

/// 统一的海报卡片。
///
/// 旧实现把六套角标皮肤、标题条和评分条分别拼在卡片内部，导致每种布局
/// 都有一套不同的遮挡规则。现在卡片只负责封面和交互，信息层统一由
/// [_CoverMetaLayer] 渲染；样式与信息显隐彻底解耦。
class AnimePosterCard extends StatelessWidget {
  final Map<String, dynamic> item;
  final HomeViewProps props;
  final String titlePos;
  final double? borderRadius;

  /// 是否抠图填满父容器（海报墙可以关闭阴影）。
  final bool flush;

  /// 长按覆盖回调（海报墙用于 Peek Sheet）。
  final VoidCallback? onLongPressOverride;

  /// 是否启用 Hero 共享元素动画。
  final bool enableHero;

  const AnimePosterCard({
    super.key,
    required this.item,
    required this.props,
    this.titlePos = 'on_cover',
    this.borderRadius,
    this.flush = false,
    this.onLongPressOverride,
    this.enableHero = false,
  });

  bool get _isSeries => item['type'] == 'series';

  int get _id => item['id'] as int;

  String get _title {
    if (_isSeries) {
      return (item['name'] ?? item['series_name'] ?? '').toString();
    }
    return (item['title'] ?? '').toString();
  }

  bool get _isSelected => props.selectedIds.contains(_id);

  String _progressLine() {
    if (_isSeries) {
      final count = _intValue('anime_count');
      return count > 0 ? '$count 部作品' : '';
    }
    return props.progressTextOf(item);
  }

  int _intValue(String key) {
    final value = item[key];
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '') ?? 0;
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final coverRadius = borderRadius ?? props.coverBorderRadius;
    final status = (item['status'] ?? '').toString().trim();
    final statusColor = props.statusColors[status] ?? colorScheme.primary;
    final rating = animeRatingOf(item);
    final progress = _progressLine();
    final titleOnCover = titlePos != 'below_cover';

    // 封面信息使用独立开关，并再次尊重全局数据项开关。这样用户既可以
    // 保持列表信息完整，也可以把封面调整成极简模式。
    final showStatus =
        !_isSeries &&
        props.showCoverStatus &&
        props.showStatus &&
        status.isNotEmpty;
    final showRating =
        !_isSeries &&
        props.showCoverRating &&
        props.showRating &&
        !props.isSelectionMode &&
        rating.hasValue;
    final showProgress =
        !_isSeries &&
        props.showCoverProgress &&
        props.showProgress &&
        progress.isNotEmpty;
    final showType = !_isSeries && props.showCoverType && props.showSubjectType;
    final showSeriesCount =
        _isSeries && props.showCoverSeriesCount && _intValue('anime_count') > 0;

    final posterRadius = BorderRadius.circular(coverRadius);

    return GestureDetector(
      onTap: () => props.onItemTap(item),
      onLongPress: onLongPressOverride ?? () => props.onItemLongPress(_id),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Container(
              decoration: BoxDecoration(
                borderRadius: posterRadius,
                boxShadow: flush ? null : AppElevation.card(context),
                border: props.isSelectionMode && _isSelected
                    ? Border.all(color: colorScheme.primary, width: 3)
                    : null,
              ),
              child: ClipRRect(
                borderRadius: posterRadius,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    _buildCoverImage(),
                    _CoverMetaLayer(
                      style: props.badgeStyle,
                      title: _title,
                      titleOnCover: titleOnCover,
                      showTitle: props.showTitle,
                      showStatus: showStatus,
                      showRating: showRating,
                      showProgress: showProgress,
                      showType: showType,
                      showSeriesCount: showSeriesCount,
                      isAnime: (item['subject_type'] ?? 'anime') == 'anime',
                      status: status,
                      statusColor: statusColor,
                      rating: rating.hasValue ? rating.label : '',
                      progress: progress,
                      isSeries: _isSeries,
                      seriesCount: _intValue('anime_count'),
                      scale: props.badgeScale,
                      opacity: props.badgeOpacity,
                      radius: props.badgeRadius,
                    ),
                    if (props.isSelectionMode)
                      Positioned(
                        top: AppSpacing.sm,
                        right: AppSpacing.sm,
                        child: _SelectionMarker(isSelected: _isSelected),
                      ),
                  ],
                ),
              ),
            ),
          ),
          if (!titleOnCover && (props.showTitle || showProgress))
            _ExternalTitle(
              title: _title,
              progress: progress,
              showTitle: props.showTitle,
              showProgress: showProgress,
              showType: showType,
              isAnime: (item['subject_type'] ?? 'anime') == 'anime',
              scale: props.badgeScale,
            ),
        ],
      ),
    );
  }

  Widget _buildCoverImage() {
    final image = AnimeCoverImage(
      url: item['cover_url'],
      appDocDir: props.appDocDir,
    );
    if (!enableHero) return image;
    return Hero(
      tag: _isSeries ? 'series_cover_$_id' : 'cover_$_id',
      child: image,
    );
  }
}

/// 封面信息层。它只接受已经计算好的显示项，避免在不同布局里重复判断。
class _CoverMetaLayer extends StatelessWidget {
  final BadgeStyle style;
  final String title;
  final bool titleOnCover;
  final bool showTitle;
  final bool showStatus;
  final bool showRating;
  final bool showProgress;
  final bool showType;
  final bool showSeriesCount;
  final bool isAnime;
  final String status;
  final Color statusColor;
  final String rating;
  final String progress;
  final bool isSeries;
  final int seriesCount;
  final double scale;
  final double opacity;
  final double radius;

  const _CoverMetaLayer({
    required this.style,
    required this.title,
    required this.titleOnCover,
    required this.showTitle,
    required this.showStatus,
    required this.showRating,
    required this.showProgress,
    required this.showType,
    required this.showSeriesCount,
    required this.isAnime,
    required this.status,
    required this.statusColor,
    required this.rating,
    required this.progress,
    required this.isSeries,
    required this.seriesCount,
    required this.scale,
    required this.opacity,
    required this.radius,
  });

  bool get _hasInfo =>
      showStatus || showRating || showProgress || showType || showSeriesCount;

  @override
  Widget build(BuildContext context) {
    switch (style) {
      case BadgeStyle.overlay:
        return _buildOverlay();
      case BadgeStyle.corners:
        return _buildCorners();
      case BadgeStyle.bottomBar:
        return _buildBottomBar();
      case BadgeStyle.minimal:
        return _buildMinimal();
    }
  }

  Widget _buildOverlay() {
    if (!_hasInfo && !(titleOnCover && showTitle)) {
      return const SizedBox.shrink();
    }
    return Positioned(
      left: AppSpacing.sm,
      right: AppSpacing.sm,
      bottom: AppSpacing.sm,
      child: _InfoSurface(
        radius: radius,
        color: Colors.black.withValues(alpha: opacity),
        child: Padding(
          padding: EdgeInsets.fromLTRB(
            9 * scale,
            8 * scale,
            9 * scale,
            7 * scale,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (titleOnCover && showTitle)
                _TitleText(title: title, scale: scale),
              if (titleOnCover && showTitle && _hasInfo)
                SizedBox(height: 5 * scale),
              if (_hasInfo)
                _InfoRow(
                  showStatus: showStatus,
                  showRating: showRating,
                  showProgress: showProgress,
                  showType: showType,
                  showSeriesCount: showSeriesCount,
                  isAnime: isAnime,
                  status: status,
                  statusColor: statusColor,
                  rating: rating,
                  progress: progress,
                  isSeries: isSeries,
                  seriesCount: seriesCount,
                  scale: scale,
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildCorners() {
    return Positioned.fill(
      child: Stack(
        children: [
          if (showStatus || showSeriesCount)
            Positioned(
              top: AppSpacing.sm,
              left: AppSpacing.sm,
              child: _CornerBadge(
                text: isSeries ? '$seriesCount 部' : status,
                color: isSeries ? Colors.black : statusColor,
                icon: isSeries ? Icons.layers_outlined : null,
                scale: scale,
                opacity: opacity,
                radius: radius,
              ),
            ),
          if (showRating)
            Positioned(
              top: AppSpacing.sm,
              right: AppSpacing.sm,
              child: _CornerBadge(
                text: rating,
                color: Colors.black,
                icon: Icons.star_rounded,
                scale: scale,
                opacity: opacity,
                radius: radius,
                isRating: true,
              ),
            ),
          if (titleOnCover && (showTitle || showProgress))
            Positioned(
              left: 0,
              right: 0,
              bottom: 0,
              child: _GradientTitleBlock(
                title: title,
                progress: progress,
                showTitle: showTitle,
                showProgress: showProgress,
                scale: scale,
                showType: showType,
                isAnime: isAnime,
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildBottomBar() {
    if (!_hasInfo && !(titleOnCover && showTitle)) {
      return const SizedBox.shrink();
    }
    return Positioned(
      left: 0,
      right: 0,
      bottom: 0,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (titleOnCover && (showTitle || showProgress))
            _GradientTitleBlock(
              title: title,
              progress: progress,
              showTitle: showTitle,
              showProgress: showProgress,
              scale: scale,
              compact: true,
              showType: showType,
              isAnime: isAnime,
            ),
          if (_hasInfo)
            _InfoSurface(
              radius: 0,
              color: Colors.black.withValues(
                alpha: (opacity + 0.08).clamp(0.0, 1.0),
              ),
              child: Padding(
                padding: EdgeInsets.symmetric(
                  horizontal: 9 * scale,
                  vertical: 7 * scale,
                ),
                child: _InfoRow(
                  showStatus: showStatus,
                  showRating: showRating,
                  showProgress: !titleOnCover && showProgress,
                  showType: showType,
                  showSeriesCount: showSeriesCount,
                  isAnime: isAnime,
                  status: status,
                  statusColor: statusColor,
                  rating: rating,
                  progress: progress,
                  isSeries: isSeries,
                  seriesCount: seriesCount,
                  scale: scale,
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildMinimal() {
    return Positioned.fill(
      child: Stack(
        children: [
          if (showStatus || showSeriesCount)
            Positioned(
              top: AppSpacing.sm,
              left: AppSpacing.sm,
              child: _MinimalStatus(
                color: isSeries ? Colors.white : statusColor,
                text: isSeries && showSeriesCount ? '$seriesCount 部' : null,
                scale: scale,
                opacity: opacity,
              ),
            ),
          if (showRating)
            Positioned(
              top: AppSpacing.sm,
              right: AppSpacing.sm,
              child: _MinimalRating(rating: rating, scale: scale),
            ),
          if (titleOnCover && (showTitle || showProgress))
            Positioned(
              left: 0,
              right: 0,
              bottom: 0,
              child: _GradientTitleBlock(
                title: title,
                progress: progress,
                showTitle: showTitle,
                showProgress: showProgress,
                scale: scale,
                compact: true,
                showType: showType,
                isAnime: isAnime,
              ),
            ),
        ],
      ),
    );
  }
}

class _InfoSurface extends StatelessWidget {
  final double radius;
  final Color color;
  final Widget child;

  const _InfoSurface({
    required this.radius,
    required this.color,
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: Colors.white.withValues(alpha: 0.14)),
      ),
      child: child,
    );
  }
}

class _InfoRow extends StatelessWidget {
  final bool showStatus;
  final bool showRating;
  final bool showProgress;
  final bool showType;
  final bool showSeriesCount;
  final bool isAnime;
  final String status;
  final Color statusColor;
  final String rating;
  final String progress;
  final bool isSeries;
  final int seriesCount;
  final double scale;

  const _InfoRow({
    required this.showStatus,
    required this.showRating,
    required this.showProgress,
    required this.showType,
    required this.showSeriesCount,
    required this.isAnime,
    required this.status,
    required this.statusColor,
    required this.rating,
    required this.progress,
    required this.isSeries,
    required this.seriesCount,
    required this.scale,
  });

  @override
  Widget build(BuildContext context) {
    final metadata = <Widget>[];
    if (isSeries && showSeriesCount) {
      metadata.add(
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.layers_outlined, size: 12 * scale, color: Colors.white),
            SizedBox(width: 4 * scale),
            Text('$seriesCount 部'),
          ],
        ),
      );
    } else if (showStatus) {
      metadata.add(
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 6 * scale,
              height: 6 * scale,
              decoration: BoxDecoration(
                color: statusColor,
                shape: BoxShape.circle,
              ),
            ),
            SizedBox(width: 5 * scale),
            ConstrainedBox(
              constraints: BoxConstraints(maxWidth: 72 * scale),
              child: Text(status, maxLines: 1, overflow: TextOverflow.ellipsis),
            ),
          ],
        ),
      );
    }

    if (showType && !isSeries) {
      metadata.add(
        Icon(
          isAnime ? Icons.movie_outlined : Icons.menu_book_outlined,
          size: 12 * scale,
          color: Colors.white.withValues(alpha: 0.78),
        ),
      );
    }

    if (showProgress && !isSeries && progress.isNotEmpty) {
      metadata.add(
        Text(
          progress,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: TextStyle(color: Colors.white.withValues(alpha: 0.78)),
        ),
      );
    }

    if (showRating) {
      metadata.add(_RatingMark(rating: rating, scale: scale));
    }

    return DefaultTextStyle(
      style: TextStyle(
        color: Colors.white,
        fontSize: 10 * scale,
        fontWeight: FontWeight.w700,
        height: 1.1,
      ),
      child: Wrap(
        spacing: 7 * scale,
        runSpacing: 3 * scale,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: metadata,
      ),
    );
  }
}

class _TitleText extends StatelessWidget {
  final String title;
  final double scale;

  const _TitleText({required this.title, required this.scale});

  @override
  Widget build(BuildContext context) {
    return Text(
      title,
      maxLines: 2,
      overflow: TextOverflow.ellipsis,
      style: TextStyle(
        color: Colors.white,
        fontSize: 12 * scale,
        fontWeight: FontWeight.w800,
        height: 1.18,
        shadows: const [Shadow(blurRadius: 4, color: Colors.black87)],
      ),
    );
  }
}

class _GradientTitleBlock extends StatelessWidget {
  final String title;
  final String progress;
  final bool showTitle;
  final bool showProgress;
  final double scale;
  final bool compact;
  final bool showType;
  final bool isAnime;

  const _GradientTitleBlock({
    required this.title,
    required this.progress,
    required this.showTitle,
    required this.showProgress,
    required this.scale,
    this.compact = false,
    this.showType = false,
    this.isAnime = true,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: EdgeInsets.fromLTRB(
        9 * scale,
        compact ? 22 * scale : 30 * scale,
        9 * scale,
        8 * scale,
      ),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Colors.transparent, Colors.black87],
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          if (showTitle) _TitleText(title: title, scale: scale),
          if (showProgress && progress.isNotEmpty) ...[
            SizedBox(height: 3 * scale),
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (showType) ...[
                  Icon(
                    isAnime ? Icons.movie_outlined : Icons.menu_book_outlined,
                    size: 11 * scale,
                    color: Colors.white.withValues(alpha: 0.78),
                  ),
                  SizedBox(width: 4 * scale),
                ],
                Flexible(
                  child: Text(
                    progress,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: Colors.white.withValues(alpha: 0.78),
                      fontSize: 10 * scale,
                      fontWeight: FontWeight.w600,
                      shadows: const [
                        Shadow(blurRadius: 3, color: Colors.black87),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ] else if (showType)
            Align(
              alignment: Alignment.centerLeft,
              child: Icon(
                isAnime ? Icons.movie_outlined : Icons.menu_book_outlined,
                size: 11 * scale,
                color: Colors.white.withValues(alpha: 0.78),
              ),
            ),
        ],
      ),
    );
  }
}

class _CornerBadge extends StatelessWidget {
  final String text;
  final Color color;
  final IconData? icon;
  final double scale;
  final double opacity;
  final double radius;
  final bool isRating;

  const _CornerBadge({
    required this.text,
    required this.color,
    required this.icon,
    required this.scale,
    required this.opacity,
    required this.radius,
    this.isRating = false,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      constraints: BoxConstraints(maxWidth: 110 * scale),
      padding: EdgeInsets.symmetric(horizontal: 7 * scale, vertical: 4 * scale),
      decoration: BoxDecoration(
        color: color.withValues(
          alpha: (isRating ? opacity * 0.88 : opacity).clamp(0.0, 1.0),
        ),
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: Colors.white.withValues(alpha: 0.16)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 11 * scale, color: Colors.white),
            SizedBox(width: 3 * scale),
          ],
          Text(
            text,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              color: Colors.white,
              fontSize: 10 * scale,
              fontWeight: FontWeight.w800,
            ),
          ),
        ],
      ),
    );
  }
}

class _RatingMark extends StatelessWidget {
  final String rating;
  final double scale;

  const _RatingMark({required this.rating, required this.scale});

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        RatingIconWidget(size: 11 * scale),
        SizedBox(width: 2 * scale),
        Text(
          rating,
          style: TextStyle(
            color: Colors.white,
            fontSize: 10 * scale,
            fontWeight: FontWeight.w800,
          ),
        ),
      ],
    );
  }
}

class _MinimalStatus extends StatelessWidget {
  final Color color;
  final String? text;
  final double scale;
  final double opacity;

  const _MinimalStatus({
    required this.color,
    required this.text,
    required this.scale,
    required this.opacity,
  });

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: opacity * 0.65),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Padding(
        padding: EdgeInsets.all(4 * scale),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 8 * scale,
              height: 8 * scale,
              decoration: BoxDecoration(
                color: color,
                shape: BoxShape.circle,
                border: Border.all(color: Colors.white.withValues(alpha: 0.8)),
              ),
            ),
            if (text != null) ...[
              SizedBox(width: 4 * scale),
              Text(
                text!,
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 9 * scale,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _MinimalRating extends StatelessWidget {
  final String rating;
  final double scale;

  const _MinimalRating({required this.rating, required this.scale});

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.52),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Padding(
        padding: EdgeInsets.symmetric(
          horizontal: 6 * scale,
          vertical: 4 * scale,
        ),
        child: _RatingMark(rating: rating, scale: scale),
      ),
    );
  }
}

class _SelectionMarker extends StatelessWidget {
  final bool isSelected;

  const _SelectionMarker({required this.isSelected});

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Container(
      decoration: BoxDecoration(
        color: isSelected
            ? colorScheme.primary
            : Colors.black.withValues(alpha: 0.45),
        shape: BoxShape.circle,
        border: Border.all(color: Colors.white.withValues(alpha: 0.5)),
      ),
      padding: const EdgeInsets.all(3),
      child: Icon(
        isSelected ? Icons.check_rounded : Icons.circle_outlined,
        color: Colors.white,
        size: 18,
      ),
    );
  }
}

class _ExternalTitle extends StatelessWidget {
  final String title;
  final String progress;
  final bool showTitle;
  final bool showProgress;
  final bool showType;
  final bool isAnime;
  final double scale;

  const _ExternalTitle({
    required this.title,
    required this.progress,
    required this.showTitle,
    required this.showProgress,
    required this.showType,
    required this.isAnime,
    required this.scale,
  });

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.fromLTRB(2, 8, 2, 0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (showTitle)
            Text(
              title,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: textTheme.labelMedium?.copyWith(
                fontWeight: FontWeight.w700,
                height: 1.15,
                fontSize: (textTheme.labelMedium?.fontSize ?? 12) * scale,
              ),
            ),
          if (showProgress && progress.isNotEmpty)
            Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (showType) ...[
                  Icon(
                    isAnime ? Icons.movie_outlined : Icons.menu_book_outlined,
                    size: 11 * scale,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                  const SizedBox(width: 4),
                ],
                Flexible(
                  child: Text(
                    progress,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: textTheme.bodySmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                      fontSize: (textTheme.bodySmall?.fontSize ?? 12) * scale,
                    ),
                  ),
                ),
              ],
            )
          else if (showType)
            Icon(
              isAnime ? Icons.movie_outlined : Icons.menu_book_outlined,
              size: 11 * scale,
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
        ],
      ),
    );
  }
}
