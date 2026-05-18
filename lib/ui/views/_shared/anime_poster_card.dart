import 'package:flutter/material.dart';
import '../../../settings_manager.dart';
import '../../components/anime_cover_image.dart';
import '../../components/status_badge.dart';
import '../../design_tokens.dart';
import '../home_layout.dart';
import 'rating_icon.dart';

/// 海报式卡片（顶部封面 + 角标信息）
///
/// 共享给 Bento 首页（Grid 区段）和 PosterWall（主视图）。
/// 通过 [titlePos] 控制标题位置：
/// - `'on_cover'`：渐变遮罩 + 标题压在封面底部
/// - `'below_cover'`：标题在封面外侧下方
///
/// PosterWall 用 [onLongPressOverride] 接管长按为"BlurSheet 浮层"，
/// 此时不再触发多选模式入口。
///
/// 关注：series 类型的 item 在角落显示"系列"标签 + 集数。
class AnimePosterCard extends StatelessWidget {
  final Map<String, dynamic> item;
  final HomeViewProps props;
  final String titlePos;
  final double borderRadius;

  /// 是否抠图填满父容器（无 padding，用于 PosterWall）
  final bool flush;

  /// 长按覆盖回调（PosterWall 注入"BlurSheet 浮层"），
  /// 不传则走 [props.onItemLongPress]（默认进多选模式）。
  final VoidCallback? onLongPressOverride;

  /// 是否启用 Hero 共享元素动画（与详情页封面联动）
  ///
  /// 默认 false 因为 Bento 首页可能在多个 section 同时出现同一番剧，
  /// 重复 Hero tag 会让 Flutter 报错。PosterWall 单次只渲染一次，
  /// 可以设为 true 获得 Hero 缩放过渡。
  final bool enableHero;

  const AnimePosterCard({
    super.key,
    required this.item,
    required this.props,
    this.titlePos = 'on_cover',
    this.borderRadius = AppRadius.sm,
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

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final showOverlayText = titlePos == 'on_cover';

    final statusText = (item['status'] ?? '').toString();
    final statusColor = props.statusColors[item['status']] ?? Colors.grey;
    final hasRating =
        !_isSeries && (item['rating'] is num) && (item['rating'] as num) > 0;
    final ratingValue = hasRating ? (item['rating'] as num).toDouble() : 0.0;

    final showStatusBadge = props.showStatus;
    final showRatingBadge =
        !_isSeries && props.showRating && !props.isSelectionMode && hasRating;
    final isBottomBar = props.badgeStyle == BadgeStyle.bottomBar;
    final useBottomBar = isBottomBar && (showStatusBadge || showRatingBadge);
    // 底部信息条高度（用于上推标题，避免重叠）
    const double bottomBarHeight = 22;

    return GestureDetector(
      onTap: () => props.onItemTap(item),
      onLongPress: onLongPressOverride ?? () => props.onItemLongPress(_id),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Expanded(
            child: Container(
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(borderRadius),
                boxShadow: flush ? null : AppElevation.card(context),
                border: props.isSelectionMode && _isSelected
                    ? Border.all(color: colorScheme.primary, width: 3)
                    : null,
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(borderRadius),
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    enableHero
                        ? Hero(
                            tag: _isSeries
                                ? 'series_cover_$_id'
                                : 'cover_$_id',
                            child: AnimeCoverImage(
                              url: item['cover_url'],
                              appDocDir: props.appDocDir,
                            ),
                          )
                        : AnimeCoverImage(
                            url: item['cover_url'],
                            appDocDir: props.appDocDir,
                          ),
                    if (showOverlayText)
                      Positioned.fill(
                        child: DecoratedBox(
                          decoration: const BoxDecoration(
                            gradient: LinearGradient(
                              begin: Alignment.topCenter,
                              end: Alignment.bottomCenter,
                              colors: [
                                Colors.transparent,
                                Colors.transparent,
                                Colors.black87,
                              ],
                              stops: [0.0, 0.55, 1.0],
                            ),
                          ),
                        ),
                      ),
                    if (showStatusBadge && !isBottomBar)
                      _buildStatusCorner(statusText, statusColor),
                    if (!_isSeries && props.showSubjectType)
                      Positioned(
                        bottom: useBottomBar
                            ? bottomBarHeight + AppSpacing.xs
                            : AppSpacing.sm,
                        right: AppSpacing.sm,
                        child: _TypeBadge(
                          icon: (item['subject_type'] ?? 'anime') == 'anime'
                              ? Icons.movie_filter_outlined
                              : Icons.menu_book_outlined,
                        ),
                      ),
                    if (showRatingBadge && !isBottomBar)
                      _buildRatingCorner(ratingValue),
                    if (useBottomBar)
                      Positioned(
                        left: 0,
                        right: 0,
                        bottom: 0,
                        child: _BottomInfoBar(
                          statusText: showStatusBadge ? statusText : '',
                          statusColor: statusColor,
                          rating: showRatingBadge ? ratingValue : null,
                          isSeries: _isSeries,
                          seriesCount: _isSeries
                              ? (item['anime_count'] ?? 0) as int
                              : 0,
                          height: bottomBarHeight,
                        ),
                      ),
                    if (showOverlayText)
                      Positioned(
                        left: AppSpacing.sm,
                        right: AppSpacing.sm,
                        bottom: useBottomBar
                            ? bottomBarHeight + AppSpacing.xs
                            : AppSpacing.sm,
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            if (props.showTitle)
                              Text(
                                _title,
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  color: Colors.white,
                                  fontSize: 12,
                                  fontWeight: FontWeight.w800,
                                  height: 1.2,
                                  shadows: [
                                    Shadow(
                                      blurRadius: 4,
                                      color: Colors.black87,
                                    ),
                                  ],
                                ),
                              ),
                            if (props.showProgress && _progressLine().isNotEmpty)
                              Padding(
                                padding: const EdgeInsets.only(top: 2),
                                child: Text(
                                  _progressLine(),
                                  style: TextStyle(
                                    color: Colors.white.withValues(alpha: 0.85),
                                    fontSize: 10,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                              ),
                          ],
                        ),
                      ),
                    if (props.isSelectionMode)
                      Positioned(
                        top: AppSpacing.sm,
                        right: AppSpacing.sm,
                        child: Container(
                          decoration: BoxDecoration(
                            color: _isSelected
                                ? colorScheme.primary
                                : Colors.black.withValues(alpha: 0.4),
                            shape: BoxShape.circle,
                            border: Border.all(
                              color: Colors.white.withValues(alpha: 0.4),
                              width: 1.5,
                            ),
                          ),
                          padding: const EdgeInsets.all(2),
                          child: Icon(
                            _isSelected
                                ? Icons.check_rounded
                                : Icons.circle_outlined,
                            color: Colors.white,
                            size: 18,
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ),
          if (titlePos == 'below_cover')
            Padding(
              padding: const EdgeInsets.fromLTRB(2, 8, 2, 0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  if (props.showTitle)
                    Text(
                      _title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.labelMedium?.copyWith(
                            fontWeight: FontWeight.w700,
                          ),
                    ),
                  if (props.showProgress && _progressLine().isNotEmpty)
                    Text(
                      _progressLine(),
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: colorScheme.onSurfaceVariant,
                          ),
                    ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  String _progressLine() {
    if (_isSeries) {
      final count = item['anime_count'] ?? 0;
      return count > 0 ? '$count 部作品' : '';
    }
    return props.progressTextOf(item);
  }

  /// 左上角的状态/系列标记，位置和外观随 [BadgeStyle] 变化
  Widget _buildStatusCorner(String statusText, Color statusColor) {
    if (_isSeries) {
      // 系列卡片：四种样式下沿用胶囊，但位置随 flush 调整
      final isFlush = props.badgeStyle == BadgeStyle.flush;
      return Positioned(
        top: isFlush ? 0 : AppSpacing.sm,
        left: isFlush ? 0 : AppSpacing.sm,
        child: _SeriesPill(
          count: item['anime_count'] ?? 0,
          flushCorner: isFlush,
        ),
      );
    }
    switch (props.badgeStyle) {
      case BadgeStyle.floating:
        return Positioned(
          top: AppSpacing.sm,
          left: AppSpacing.sm,
          child: StatusBadge(
            status: statusText,
            color: statusColor,
            isCompact: true,
          ),
        );
      case BadgeStyle.flush:
        return Positioned(
          top: 0,
          left: 0,
          child: _FlushStatusBadge(
            status: statusText,
            color: statusColor,
          ),
        );
      case BadgeStyle.minimal:
        return Positioned(
          top: AppSpacing.xs,
          left: AppSpacing.xs,
          child: _MinimalStatusDot(color: statusColor),
        );
      case BadgeStyle.bottomBar:
        return const SizedBox.shrink();
    }
  }

  /// 右上角的评分标记
  Widget _buildRatingCorner(double rating) {
    switch (props.badgeStyle) {
      case BadgeStyle.floating:
        return Positioned(
          top: AppSpacing.sm,
          right: AppSpacing.sm,
          child: _RatingPill(rating: rating),
        );
      case BadgeStyle.flush:
        return Positioned(
          top: 0,
          right: 0,
          child: _FlushRatingPill(rating: rating),
        );
      case BadgeStyle.minimal:
        return Positioned(
          top: AppSpacing.xs,
          right: AppSpacing.sm,
          child: _MinimalRatingText(rating: rating),
        );
      case BadgeStyle.bottomBar:
        return const SizedBox.shrink();
    }
  }
}

class _SeriesPill extends StatelessWidget {
  final int count;
  final bool flushCorner;
  const _SeriesPill({required this.count, this.flushCorner = false});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.55),
        borderRadius: flushCorner
            ? const BorderRadius.only(bottomRight: Radius.circular(AppRadius.xs))
            : BorderRadius.circular(AppRadius.xs),
        border: Border.all(color: Colors.white24, width: 0.5),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.layers, color: Colors.white, size: 12),
          const SizedBox(width: 4),
          Text(
            '$count 部',
            style: const TextStyle(
              color: Colors.white,
              fontSize: 10,
              fontWeight: FontWeight.w700,
            ),
          ),
        ],
      ),
    );
  }
}

class _TypeBadge extends StatelessWidget {
  final IconData icon;
  const _TypeBadge({required this.icon});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.5),
        borderRadius: BorderRadius.circular(AppRadius.xs),
      ),
      child: Icon(icon, size: 14, color: Colors.white),
    );
  }
}

class _RatingPill extends StatelessWidget {
  final double rating;
  const _RatingPill({required this.rating});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.6),
        borderRadius: BorderRadius.circular(AppRadius.xs),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const RatingIconWidget(size: 11),
          const SizedBox(width: 2),
          Text(
            rating.toStringAsFixed(1),
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

/// 贴边样式的状态徽章：外侧切平，内侧 8px 圆角
class _FlushStatusBadge extends StatelessWidget {
  final String status;
  final Color color;
  const _FlushStatusBadge({required this.status, required this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.92),
        borderRadius: const BorderRadius.only(
          bottomRight: Radius.circular(AppRadius.xs),
        ),
      ),
      child: Text(
        status,
        style: const TextStyle(
          color: Colors.white,
          fontSize: 10,
          fontWeight: FontWeight.bold,
        ),
      ),
    );
  }
}

/// 贴边样式的评分徽章：外侧切平，内侧 8px 圆角
class _FlushRatingPill extends StatelessWidget {
  final double rating;
  const _FlushRatingPill({required this.rating});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.7),
        borderRadius: const BorderRadius.only(
          bottomLeft: Radius.circular(AppRadius.xs),
        ),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const RatingIconWidget(size: 11),
          const SizedBox(width: 2),
          Text(
            rating.toStringAsFixed(1),
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

/// 极简样式的状态：8px 实心圆点 + 白色描边
class _MinimalStatusDot extends StatelessWidget {
  final Color color;
  const _MinimalStatusDot({required this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 10,
      height: 10,
      decoration: BoxDecoration(
        color: color,
        shape: BoxShape.circle,
        border: Border.all(color: Colors.white.withValues(alpha: 0.85), width: 1.2),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.45),
            blurRadius: 2,
          ),
        ],
      ),
    );
  }
}

/// 极简样式的评分：仅数字 + 阴影，无背景
class _MinimalRatingText extends StatelessWidget {
  final double rating;
  const _MinimalRatingText({required this.rating});

  @override
  Widget build(BuildContext context) {
    return Text(
      rating.toStringAsFixed(1),
      style: const TextStyle(
        color: Colors.white,
        fontSize: 12,
        fontWeight: FontWeight.w800,
        shadows: [
          Shadow(blurRadius: 3, color: Colors.black87),
          Shadow(blurRadius: 6, color: Colors.black54),
        ],
      ),
    );
  }
}

/// 底部信息条：左侧状态色块 + 文字、右侧评分
class _BottomInfoBar extends StatelessWidget {
  final String statusText;
  final Color statusColor;
  final double? rating;
  final bool isSeries;
  final int seriesCount;
  final double height;

  const _BottomInfoBar({
    required this.statusText,
    required this.statusColor,
    required this.rating,
    required this.isSeries,
    required this.seriesCount,
    required this.height,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      height: height,
      padding: const EdgeInsets.symmetric(horizontal: 6),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            Colors.black.withValues(alpha: 0.0),
            Colors.black.withValues(alpha: 0.65),
          ],
        ),
      ),
      child: Row(
        children: [
          if (isSeries) ...[
            const Icon(Icons.layers, color: Colors.white, size: 11),
            const SizedBox(width: 4),
            Text(
              '$seriesCount 部',
              style: const TextStyle(
                color: Colors.white,
                fontSize: 10,
                fontWeight: FontWeight.w700,
              ),
            ),
          ] else if (statusText.isNotEmpty) ...[
            Container(
              width: 4,
              height: 12,
              decoration: BoxDecoration(
                color: statusColor,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            const SizedBox(width: 5),
            Flexible(
              child: Text(
                statusText,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 10,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
          ],
          const Spacer(),
          if (rating != null) ...[
            const RatingIconWidget(size: 11),
            const SizedBox(width: 2),
            Text(
              rating!.toStringAsFixed(1),
              style: const TextStyle(
                color: Colors.white,
                fontSize: 10,
                fontWeight: FontWeight.w800,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

// 防止未使用的 import 警告（SettingsManager 在 future 扩展时会用到）
// ignore: unused_element
void _useSettings() => SettingsManager();
