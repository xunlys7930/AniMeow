import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../components/anime_cover_image.dart';
import '../components/empty_state.dart';
import '../components/status_badge.dart';
import '../design_tokens.dart';
import 'home_layout.dart';
import '_shared/anime_list_card.dart';
import '_shared/anime_swipe_actions.dart';
import '_shared/rating_icon.dart';

/// 精致卡片流（Letterboxd 风格单列大卡片）
///
/// 左侧 140×180 大封面 + 右侧富文本信息：标题 / studio·年份 / 状态胶囊 / 评分 /
/// 类型 / 进度条。背景按状态色淡淡渐变。
///
/// Series items 委托给共享 [AnimeListCard]（保留原本的横向 row 风格）。
class CardFeedView extends StatelessWidget {
  final HomeViewProps props;

  const CardFeedView({super.key, required this.props});

  @override
  Widget build(BuildContext context) {
    if (props.items.isEmpty) {
      return RefreshIndicator(
        onRefresh: () async {
          HapticFeedback.selectionClick();
          await props.onRefresh();
        },
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          children: const [
            SizedBox(height: 200),
            EmptyStateWidget(
              message: '还没添加番剧',
              buttonText: '去添加一部吧',
            ),
          ],
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: () async {
        HapticFeedback.selectionClick();
        await props.onRefresh();
      },
      child: NotificationListener<ScrollNotification>(
        onNotification: (notification) {
          final m = notification.metrics;
          if (notification is ScrollUpdateNotification &&
              m.pixels >= m.maxScrollExtent - 800) {
            props.onLoadMore?.call();
          }
          return false;
        },
        child: ListView.separated(
          padding: const EdgeInsets.fromLTRB(
            AppSpacing.lg,
            AppSpacing.md,
            AppSpacing.lg,
            AppSpacing.xxl * 2,
          ),
          physics: const AlwaysScrollableScrollPhysics(
            parent: BouncingScrollPhysics(),
          ),
          itemCount: props.items.length + (props.hasMore ? 1 : 0),
          separatorBuilder: (_, _) => const SizedBox(height: AppSpacing.md),
          itemBuilder: (context, i) {
            if (i >= props.items.length) {
              return const Padding(
                padding: EdgeInsets.all(AppSpacing.lg),
                child: Center(
                  child: SizedBox(
                    width: 24,
                    height: 24,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                ),
              );
            }
            final item = props.items[i];
            // 系列保留共享卡片样式（横向 row），与 anime 区分
            if (item['type'] == 'series') {
              return AnimeListCard(
                item: item,
                props: props,
                size: AnimeListCardSize.regular,
              );
            }
            // 多选模式下不允许滑动，避免误触
            if (props.isSelectionMode) {
              return _CardFeedItem(item: item, props: props);
            }
            return SwipeActionTile(
              dismissKey: ValueKey('cf_${item['id']}'),
              onSwipeRight: () => incrementEpisode(
                item,
                context: context,
                onRefresh: props.onRefresh,
              ),
              onSwipeLeft: () => cycleStatus(
                item,
                context: context,
                onRefresh: props.onRefresh,
              ),
              child: _CardFeedItem(item: item, props: props),
            );
          },
        ),
      ),
    );
  }
}

/// 单条 CardFeed 番剧卡片
class _CardFeedItem extends StatelessWidget {
  final Map<String, dynamic> item;
  final HomeViewProps props;

  const _CardFeedItem({required this.item, required this.props});

  int get _id => item['id'] as int;
  bool get _isSelected => props.selectedIds.contains(_id);

  String? get _year {
    final airDate = item['air_date'] as String?;
    if (airDate == null || airDate.length < 4) return null;
    return airDate.substring(0, 4);
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final status = (item['status'] ?? '').toString();
    final statusColor = props.statusColors[status] ?? colorScheme.outline;
    final studio = (item['studio'] ?? '').toString().trim();
    final seriesName = (item['series_name'] ?? '').toString().trim();
    final subjectType = (item['subject_type'] ?? 'anime').toString();

    final total = (item['total_episodes'] as int?) ?? 0;
    final watched = (item['watched_episodes'] as int?) ?? 0;
    final percent = total > 0 ? (watched / total).clamp(0.0, 1.0) : 0.0;

    return GestureDetector(
      onTap: () => props.onItemTap(item),
      onLongPress: () => props.onItemLongPress(_id),
      child: Container(
        height: 184,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(AppRadius.md),
          gradient: LinearGradient(
            begin: Alignment.centerLeft,
            end: Alignment.centerRight,
            colors: [
              statusColor.withValues(alpha: 0.10),
              colorScheme.surfaceContainerLow,
            ],
            stops: const [0.0, 0.55],
          ),
          border: props.isSelectionMode && _isSelected
              ? Border.all(color: colorScheme.primary, width: 2)
              : null,
          boxShadow: AppElevation.card(context),
        ),
        clipBehavior: Clip.antiAlias,
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // ===== 左：大封面 =====
            SizedBox(
              width: 124,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  Hero(
                    tag: 'cover_$_id',
                    child: AnimeCoverImage(
                      url: item['cover_url'],
                      appDocDir: props.appDocDir,
                    ),
                  ),
                  // 状态色描边渐变 (右侧融入卡片背景)
                  Positioned.fill(
                    child: DecoratedBox(
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          begin: Alignment.centerLeft,
                          end: Alignment.centerRight,
                          colors: [
                            Colors.transparent,
                            statusColor.withValues(alpha: 0.0),
                          ],
                        ),
                      ),
                    ),
                  ),
                  if (props.showRating &&
                      !props.isSelectionMode &&
                      (item['rating'] is num) &&
                      (item['rating'] as num) > 0)
                    Positioned(
                      top: AppSpacing.sm,
                      right: AppSpacing.sm,
                      child: _RatingPill(
                          rating: (item['rating'] as num).toDouble()),
                    ),
                ],
              ),
            ),

            // ===== 右：信息区 =====
            Expanded(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(
                  AppSpacing.md,
                  AppSpacing.md,
                  AppSpacing.md,
                  AppSpacing.sm,
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    // 标题
                    if (props.showTitle)
                      Text(
                        (item['title'] ?? '').toString(),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.titleMedium?.copyWith(
                              fontWeight: FontWeight.w900,
                              letterSpacing: -0.2,
                              height: 1.2,
                            ),
                      ),

                    // 副信息：studio · 年份 · 系列
                    if (_buildSubLine(studio, seriesName).isNotEmpty) ...[
                      const SizedBox(height: 4),
                      Text(
                        _buildSubLine(studio, seriesName),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                              color: colorScheme.onSurfaceVariant,
                              fontWeight: FontWeight.w600,
                            ),
                      ),
                    ],

                    const SizedBox(height: AppSpacing.sm),

                    // 胶囊行：状态 · 类型
                    Wrap(
                      spacing: AppSpacing.sm,
                      runSpacing: 4,
                      children: [
                        if (props.showStatus && status.isNotEmpty)
                          StatusBadge(
                            status: status,
                            color: statusColor,
                            isCompact: true,
                          ),
                        if (props.showSubjectType)
                          _MetaChip(
                            icon: subjectType == 'anime'
                                ? Icons.movie_outlined
                                : Icons.menu_book_outlined,
                            text: subjectType == 'anime' ? '番剧' : '漫画',
                            color: colorScheme.onSurfaceVariant,
                          ),
                      ],
                    ),

                    const Spacer(),

                    // 进度条 + 文案
                    if (props.showProgress)
                      _ProgressBlock(
                        progressText: props.progressTextOf(item),
                        percent: percent,
                        total: total,
                        accent: statusColor,
                      ),
                  ],
                ),
              ),
            ),

            // ===== 选中态指示 =====
            if (props.isSelectionMode)
              Padding(
                padding: const EdgeInsets.only(right: AppSpacing.md),
                child: Icon(
                  _isSelected
                      ? Icons.check_circle_rounded
                      : Icons.radio_button_unchecked,
                  size: 24,
                  color: _isSelected
                      ? colorScheme.primary
                      : colorScheme.onSurfaceVariant,
                ),
              ),
          ],
        ),
      ),
    );
  }

  String _buildSubLine(String studio, String seriesName) {
    final parts = <String>[];
    if (studio.isNotEmpty) parts.add(studio);
    if (_year != null) parts.add(_year!);
    if (seriesName.isNotEmpty) parts.add('《$seriesName》');
    return parts.join(' · ');
  }
}

class _MetaChip extends StatelessWidget {
  final IconData icon;
  final String text;
  final Color color;

  const _MetaChip({
    required this.icon,
    required this.text,
    required this.color,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.10),
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

class _RatingPill extends StatelessWidget {
  final double rating;
  const _RatingPill({required this.rating});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.6),
        borderRadius: BorderRadius.circular(AppRadius.xs),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const RatingIconWidget(size: 12),
          const SizedBox(width: 2),
          Text(
            rating.toStringAsFixed(1),
            style: const TextStyle(
              color: Colors.white,
              fontSize: 11,
              fontWeight: FontWeight.w900,
            ),
          ),
        ],
      ),
    );
  }
}

class _ProgressBlock extends StatelessWidget {
  final String progressText;
  final double percent;
  final int total;
  final Color accent;

  const _ProgressBlock({
    required this.progressText,
    required this.percent,
    required this.total,
    required this.accent,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        if (progressText.isNotEmpty)
          Text(
            progressText,
            style: TextStyle(
              fontSize: 12,
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
