import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../components/empty_state.dart';
import '../design_tokens.dart';
import '_shared/anime_poster_card.dart';
import 'home_layout.dart';

/// 推荐宫格：首页多列封面流。
///
/// 只负责更紧凑的宫格排布；封面角标样式仍由 [BadgeStyle] 控制。
class RecommendGridView extends StatelessWidget {
  final HomeViewProps props;

  const RecommendGridView({super.key, required this.props});

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

    return RefreshIndicator(
      onRefresh: () async {
        HapticFeedback.selectionClick();
        await props.onRefresh();
      },
      child: NotificationListener<ScrollNotification>(
        onNotification: (notification) {
          final metrics = notification.metrics;
          if (notification is ScrollUpdateNotification &&
              metrics.pixels >= metrics.maxScrollExtent - 800) {
            props.onLoadMore?.call();
          }
          return false;
        },
        child: LayoutBuilder(
          builder: (context, constraints) {
            final width = constraints.maxWidth;
            final columns = _columnCountFor(width);
            final horizontalPadding = width < 360
                ? AppSpacing.md
                : AppSpacing.lg;
            final spacing = width < 360 ? AppSpacing.sm : AppSpacing.md;
            final usableWidth =
                width - horizontalPadding * 2 - spacing * (columns - 1);
            final itemWidth = usableWidth / columns;
            final titlePos = _titlePositionFor();
            final itemHeight =
                itemWidth / AppCoverRatio.poster +
                _externalInfoHeightFor(itemWidth, titlePos);

            return GridView.builder(
              keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
              padding: EdgeInsets.fromLTRB(
                horizontalPadding,
                AppSpacing.md,
                horizontalPadding,
                AppSpacing.xxl * 2,
              ),
              physics: const AlwaysScrollableScrollPhysics(
                parent: BouncingScrollPhysics(),
              ),
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: columns,
                crossAxisSpacing: spacing,
                mainAxisSpacing: AppSpacing.xl,
                childAspectRatio: itemWidth / itemHeight,
              ),
              itemCount: props.items.length + (props.hasMore ? 1 : 0),
              itemBuilder: (context, index) {
                if (index >= props.items.length) {
                  return const Center(
                    child: SizedBox(
                      width: 24,
                      height: 24,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                  );
                }

                return AnimePosterCard(
                  item: props.items[index],
                  props: props,
                  titlePos: titlePos,
                  borderRadius: props.coverBorderRadius,
                );
              },
            );
          },
        ),
      ),
    );
  }

  int _columnCountFor(double width) {
    final maxColumns = width >= 1000
        ? 6
        : width >= 760
        ? 5
        : width >= 560
        ? 4
        : 3;
    return props.gridColumns.clamp(2, maxColumns).toInt();
  }

  String _titlePositionFor() {
    return props.titlePosition;
  }

  double _externalInfoHeightFor(double width, String titlePos) {
    if (titlePos != 'below_cover') {
      return 0;
    }

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
