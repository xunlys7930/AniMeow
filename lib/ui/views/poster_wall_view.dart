import 'dart:ui';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../components/anime_cover_image.dart';
import '../components/empty_state.dart';
import '../components/status_badge.dart';
import '../design_tokens.dart';
import 'home_layout.dart';
import '_shared/anime_poster_card.dart';
import '_shared/rating_icon.dart';
import '../../utils/anime_rating.dart';

/// 沉浸海报墙（封面铺满 + 长按 BlurSheet 信息浮层）
///
/// 长按番剧封面不再直接进入多选，而是弹出半透明 BlurSheet 浮层，
/// 内含：大封面、标题、状态、进度条、评分、studio/年份。
/// 用户从浮层里选「打开详情」或「加入多选」。
class PosterWallView extends StatelessWidget {
  final HomeViewProps props;

  const PosterWallView({super.key, required this.props});

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
            EmptyStateWidget(message: '还没添加番剧', buttonText: '去添加一部吧'),
          ],
        ),
      );
    }

    final cols = props.gridColumns;
    final useTallPoster =
        props.titlePosition == 'below_cover' ||
        props.badgeStyle == BadgeStyle.bottomBar;
    final aspectRatio = useTallPoster ? 0.55 : 0.66;

    return RefreshIndicator(
      onRefresh: () async {
        HapticFeedback.selectionClick();
        await props.onRefresh();
      },
      child: NotificationListener<ScrollNotification>(
        onNotification: (n) {
          final m = n.metrics;
          if (n is ScrollUpdateNotification &&
              m.pixels >= m.maxScrollExtent - 800) {
            props.onLoadMore?.call();
          }
          return false;
        },
        child: GridView.builder(
          keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
          padding: const EdgeInsets.fromLTRB(
            AppSpacing.md,
            AppSpacing.md,
            AppSpacing.md,
            AppSpacing.xxl * 2,
          ),
          physics: const AlwaysScrollableScrollPhysics(
            parent: BouncingScrollPhysics(),
          ),
          gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
            crossAxisCount: cols,
            crossAxisSpacing: AppSpacing.md,
            mainAxisSpacing: AppSpacing.md,
            childAspectRatio: aspectRatio,
          ),
          itemCount: props.items.length + (props.hasMore ? 1 : 0),
          itemBuilder: (context, i) {
            if (i >= props.items.length) {
              return const Center(
                child: SizedBox(
                  width: 24,
                  height: 24,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
              );
            }
            final item = props.items[i];
            return AnimePosterCard(
              item: item,
              props: props,
              titlePos: props.titlePosition,
              enableHero: true,
              // 在多选模式下保留原行为（长按追加选中）；非多选才出 Peek Sheet
              onLongPressOverride: props.isSelectionMode
                  ? null
                  : () {
                      HapticFeedback.mediumImpact();
                      _showPeekSheet(context, item);
                    },
            );
          },
        ),
      ),
    );
  }

  void _showPeekSheet(BuildContext context, Map<String, dynamic> item) {
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: Colors.transparent,
      barrierColor: Colors.black.withValues(alpha: 0.45),
      isScrollControlled: true,
      showDragHandle: false,
      builder: (sheetCtx) => _PeekSheet(item: item, props: props),
    );
  }
}

/// 长按浮层（BlurSheet 风格）
class _PeekSheet extends StatelessWidget {
  final Map<String, dynamic> item;
  final HomeViewProps props;

  const _PeekSheet({required this.item, required this.props});

  bool get _isSeries => item['type'] == 'series';

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
    final rating = animeRatingOf(item);

    final total = (item['total_episodes'] as int?) ?? 0;
    final watched = (item['watched_episodes'] as int?) ?? 0;
    final percent = total > 0 ? (watched / total).clamp(0.0, 1.0) : 0.0;

    return ClipRRect(
      borderRadius: const BorderRadius.vertical(
        top: Radius.circular(AppRadius.lg),
      ),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 24, sigmaY: 24),
        child: Container(
          decoration: BoxDecoration(
            color: colorScheme.surface.withValues(alpha: 0.88),
            borderRadius: const BorderRadius.vertical(
              top: Radius.circular(AppRadius.lg),
            ),
            border: Border(
              top: BorderSide(
                color: colorScheme.outlineVariant.withValues(alpha: 0.4),
                width: 0.5,
              ),
            ),
          ),
          padding: EdgeInsets.only(
            left: AppSpacing.xl,
            right: AppSpacing.xl,
            top: AppSpacing.md,
            bottom: MediaQuery.of(context).padding.bottom + AppSpacing.lg,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // drag handle
              Center(
                child: Container(
                  width: 36,
                  height: 4,
                  margin: const EdgeInsets.only(bottom: AppSpacing.lg),
                  decoration: BoxDecoration(
                    color: colorScheme.onSurfaceVariant.withValues(alpha: 0.3),
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
              ),

              // 内容：左封面 + 右文本
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(
                      props.coverBorderRadius,
                    ),
                    child: SizedBox(
                      width: 100,
                      height: 140,
                      child: AnimeCoverImage(
                        url: item['cover_url'],
                        appDocDir: props.appDocDir,
                      ),
                    ),
                  ),
                  const SizedBox(width: AppSpacing.lg),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          _isSeries
                              ? (item['name'] ?? item['series_name'] ?? '')
                                    .toString()
                              : (item['title'] ?? '').toString(),
                          style: Theme.of(context).textTheme.titleLarge
                              ?.copyWith(
                                fontWeight: FontWeight.w900,
                                letterSpacing: -0.2,
                                height: 1.2,
                              ),
                          maxLines: 3,
                          overflow: TextOverflow.ellipsis,
                        ),
                        const SizedBox(height: 6),
                        if (_buildSubLine(studio, seriesName).isNotEmpty)
                          Text(
                            _buildSubLine(studio, seriesName),
                            style: Theme.of(context).textTheme.bodySmall
                                ?.copyWith(
                                  color: colorScheme.onSurfaceVariant,
                                  fontWeight: FontWeight.w600,
                                ),
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                          ),
                        const SizedBox(height: AppSpacing.md),
                        Wrap(
                          spacing: AppSpacing.sm,
                          runSpacing: 4,
                          children: [
                            if (_isSeries)
                              _PeekChip(
                                icon: Icons.layers,
                                text: '系列 · ${item['anime_count'] ?? 0} 部',
                                color: colorScheme.tertiary,
                              )
                            else ...[
                              if (status.isNotEmpty)
                                StatusBadge(status: status, color: statusColor),
                              _PeekChip(
                                icon: subjectType == 'anime'
                                    ? Icons.movie_outlined
                                    : Icons.menu_book_outlined,
                                text: subjectType == 'anime' ? '番剧' : '漫画',
                                color: colorScheme.onSurfaceVariant,
                              ),
                              if (rating.hasValue)
                                _PeekChip(
                                  leading: const RatingIconWidget(size: 13),
                                  text: rating.label,
                                  color: Colors.amber[800]!,
                                ),
                            ],
                          ],
                        ),
                      ],
                    ),
                  ),
                ],
              ),

              // 进度条
              if (!_isSeries && props.progressTextOf(item).isNotEmpty) ...[
                const SizedBox(height: AppSpacing.lg),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      '观看进度',
                      style: Theme.of(context).textTheme.labelSmall?.copyWith(
                        color: colorScheme.onSurfaceVariant,
                        letterSpacing: 1.0,
                      ),
                    ),
                    Text(
                      props.progressTextOf(item),
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w700,
                        color: statusColor,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                ClipRRect(
                  borderRadius: BorderRadius.circular(4),
                  child: LinearProgressIndicator(
                    value: percent,
                    minHeight: 6,
                    backgroundColor: colorScheme.surfaceContainerHigh,
                    valueColor: AlwaysStoppedAnimation(statusColor),
                  ),
                ),
              ],

              const SizedBox(height: AppSpacing.xl),

              // 主操作
              Row(
                children: [
                  IconButton(
                    onPressed: () {
                      Navigator.pop(context);
                      // 直接进入多选并选中该项
                      props.onItemLongPress(item['id'] as int);
                    },
                    tooltip: '加入多选',
                    icon: const Icon(Icons.check_circle_outline),
                    style: IconButton.styleFrom(
                      backgroundColor: colorScheme.surfaceContainerHigh,
                      foregroundColor: colorScheme.onSurface,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(AppRadius.sm),
                      ),
                      minimumSize: const Size(56, 52),
                    ),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: () {
                        Navigator.pop(context);
                        props.onItemTap(item);
                      },
                      icon: const Icon(Icons.open_in_new_rounded),
                      label: const Text(
                        '打开详情',
                        style: TextStyle(fontWeight: FontWeight.w800),
                      ),
                      style: FilledButton.styleFrom(
                        minimumSize: const Size.fromHeight(52),
                        backgroundColor: statusColor,
                        foregroundColor: Colors.white,
                      ),
                    ),
                  ),
                ],
              ),
            ],
          ),
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

class _PeekChip extends StatelessWidget {
  final IconData? icon;
  final Widget? leading;
  final String text;
  final Color color;

  const _PeekChip({
    this.icon,
    this.leading,
    required this.text,
    required this.color,
  }) : assert(
         icon != null || leading != null,
         'either icon or leading must be provided',
       );

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(AppRadius.xs),
        border: Border.all(color: color.withValues(alpha: 0.3), width: 0.5),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          leading ?? Icon(icon, size: 13, color: color),
          const SizedBox(width: 4),
          Text(
            text,
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
