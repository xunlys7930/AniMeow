import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../components/empty_state.dart';
import '../design_tokens.dart';
import 'home_layout.dart';
import '_shared/anime_list_card.dart';
import '_shared/anime_poster_card.dart';

/// 智能 Bento 首页（默认布局）
///
/// 当用户没有施加任何过滤（[HomeViewProps.isInDefaultMode] = true）时，
/// 会展示多个聚合 sliver 区段：
/// - **正在追**（在看 Hero 横滑）
/// - **最近添加**（横滑封面）
/// - **全部**（3 列 Grid）
///
/// 当用户处于过滤模式（有搜索/标签/年份/状态/类型筛选）时，
/// 直接退化为单一 Grid，避免重复展示。
class BentoHomeView extends StatelessWidget {
  final HomeViewProps props;

  const BentoHomeView({super.key, required this.props});

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

    // 抽出各类 Section（仅在默认模式下显示 smart sections）
    final List<Map<String, dynamic>> watching = props.isInDefaultMode
        ? props.items
              .where(
                (i) =>
                    i['type'] != 'series' &&
                    (i['status'] ?? '').toString() == '在看',
              )
              .toList()
        : const [];

    final List<Map<String, dynamic>> recentlyAdded = props.isInDefaultMode
        ? props.items.where((i) => i['type'] != 'series').take(10).toList()
        : const [];

    return RefreshIndicator(
      onRefresh: () async {
        HapticFeedback.selectionClick();
        await props.onRefresh();
      },
      child: NotificationListener<ScrollNotification>(
        onNotification: (notification) {
          if (notification is ScrollUpdateNotification) {
            final metrics = notification.metrics;
            if (metrics.pixels >= metrics.maxScrollExtent - 800) {
              props.onLoadMore?.call();
            }
          }
          return false;
        },
        child: CustomScrollView(
          keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
          physics: const AlwaysScrollableScrollPhysics(
            parent: BouncingScrollPhysics(),
          ),
          slivers: [
            if (props.isInDefaultMode) ...[
              if (watching.isNotEmpty) ...[
                _BentoSectionHeader(
                  title: '正在追',
                  subtitle: '${watching.length} 部进行中',
                  icon: Icons.play_circle_rounded,
                ),
                _HeroStrip(items: watching, props: props),
                const SliverToBoxAdapter(
                  child: SizedBox(height: AppSpacing.lg),
                ),
              ],
              if (recentlyAdded.isNotEmpty) ...[
                _BentoSectionHeader(
                  title: '最近添加',
                  subtitle: '快速复看',
                  icon: Icons.history_rounded,
                ),
                _PosterStrip(items: recentlyAdded, props: props),
                const SliverToBoxAdapter(
                  child: SizedBox(height: AppSpacing.lg),
                ),
              ],
              _BentoSectionHeader(
                title: '全部 (${props.totalItemCount})',
                subtitle: null,
                icon: Icons.grid_view_rounded,
              ),
            ],
            _AllGrid(props: props),
            if (props.hasMore)
              const SliverToBoxAdapter(
                child: Padding(
                  padding: EdgeInsets.all(AppSpacing.lg),
                  child: Center(
                    child: SizedBox(
                      width: 24,
                      height: 24,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                  ),
                ),
              ),
            const SliverToBoxAdapter(
              child: SizedBox(height: AppSpacing.xxl * 2),
            ),
          ],
        ),
      ),
    );
  }
}

class _BentoSectionHeader extends StatelessWidget {
  final String title;
  final String? subtitle;
  final IconData icon;

  const _BentoSectionHeader({
    required this.title,
    required this.subtitle,
    required this.icon,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return SliverToBoxAdapter(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(
          AppSpacing.lg,
          AppSpacing.lg,
          AppSpacing.lg,
          AppSpacing.md,
        ),
        child: Row(
          children: [
            Container(
              width: 36,
              height: 36,
              decoration: BoxDecoration(
                color: colorScheme.primaryContainer,
                borderRadius: BorderRadius.circular(AppRadius.xs),
              ),
              child: Icon(
                icon,
                color: colorScheme.onPrimaryContainer,
                size: 20,
              ),
            ),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: Theme.of(context).textTheme.titleLarge?.copyWith(
                      fontWeight: FontWeight.w900,
                      letterSpacing: -0.2,
                    ),
                  ),
                  if (subtitle != null)
                    Text(
                      subtitle!,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: colorScheme.onSurfaceVariant,
                      ),
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _HeroStrip extends StatelessWidget {
  final List<Map<String, dynamic>> items;
  final HomeViewProps props;

  const _HeroStrip({required this.items, required this.props});

  @override
  Widget build(BuildContext context) {
    return SliverToBoxAdapter(
      child: LayoutBuilder(
        builder: (context, constraints) {
          // 响应式：窄屏 1 张主卡 + 旁边露一截预览；桌面/平板封顶 360
          // 360dp 屏：~281dp  ·  414dp 屏：~323dp  ·  800+dp：360dp
          final cardWidth = (constraints.maxWidth * 0.78).clamp(240.0, 360.0);
          return SizedBox(
            height: 168,
            child: ListView.separated(
              padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
              scrollDirection: Axis.horizontal,
              physics: const BouncingScrollPhysics(),
              itemCount: items.length,
              separatorBuilder: (_, _) => const SizedBox(width: AppSpacing.md),
              itemBuilder: (context, i) {
                return AnimeListCard(
                  item: items[i],
                  props: props,
                  size: AnimeListCardSize.hero,
                  fixedWidth: cardWidth,
                );
              },
            ),
          );
        },
      ),
    );
  }
}

class _PosterStrip extends StatelessWidget {
  final List<Map<String, dynamic>> items;
  final HomeViewProps props;

  const _PosterStrip({required this.items, required this.props});

  @override
  Widget build(BuildContext context) {
    return SliverToBoxAdapter(
      child: LayoutBuilder(
        builder: (context, constraints) {
          // 响应式：手机一屏约 2.6 张可视；窄屏放大封面提升可读性
          final posterWidth = (constraints.maxWidth * 0.32).clamp(120.0, 156.0);
          final stripHeight = posterWidth / AppCoverRatio.poster + 36; // 标题+计数
          return SizedBox(
            height: stripHeight,
            child: ListView.separated(
              padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
              scrollDirection: Axis.horizontal,
              physics: const BouncingScrollPhysics(),
              itemCount: items.length,
              separatorBuilder: (_, _) => const SizedBox(width: AppSpacing.md),
              itemBuilder: (context, i) {
                return SizedBox(
                  width: posterWidth,
                  child: AnimePosterCard(
                    item: items[i],
                    props: props,
                    titlePos: 'below_cover',
                    borderRadius: props.coverBorderRadius,
                  ),
                );
              },
            ),
          );
        },
      ),
    );
  }
}

class _AllGrid extends StatelessWidget {
  final HomeViewProps props;
  const _AllGrid({required this.props});

  @override
  Widget build(BuildContext context) {
    return SliverLayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.crossAxisExtent;
        final maxColumns = switch (width) {
          < 380 => 2,
          < 620 => 3,
          < 900 => 4,
          < 1180 => 5,
          _ => 6,
        };
        final columns = props.gridColumns.clamp(2, maxColumns).toInt();
        final horizontalPadding = AppBreakpoints.pagePadding(width);
        final spacing = width < AppBreakpoints.compact
            ? AppSpacing.md
            : AppSpacing.lg;
        final itemWidth =
            (width - horizontalPadding * 2 - spacing * (columns - 1)) / columns;
        final externalInfoHeight = props.titlePosition == 'below_cover'
            ? _externalInfoHeight(itemWidth)
            : 0.0;
        final childAspectRatio =
            itemWidth / (itemWidth / AppCoverRatio.poster + externalInfoHeight);

        return SliverPadding(
          padding: EdgeInsets.fromLTRB(
            horizontalPadding,
            AppSpacing.sm,
            horizontalPadding,
            AppSpacing.lg,
          ),
          sliver: SliverGrid(
            gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
              crossAxisCount: columns,
              mainAxisSpacing: spacing,
              crossAxisSpacing: spacing,
              childAspectRatio: childAspectRatio,
            ),
            delegate: SliverChildBuilderDelegate(
              (context, i) => AnimePosterCard(
                item: props.items[i],
                props: props,
                titlePos: props.titlePosition,
              ),
              childCount: props.items.length,
            ),
          ),
        );
      },
    );
  }

  double _externalInfoHeight(double width) {
    final showProgress = props.showCoverProgress && props.showProgress;
    if (!props.showTitle && !showProgress) return 0;
    if (props.showTitle && showProgress) {
      if (width < 104) return 48;
      if (width < 132) return 52;
      return 56;
    }
    if (props.showTitle) {
      if (width < 104) return 34;
      if (width < 132) return 38;
      return 42;
    }
    return 24;
  }
}
