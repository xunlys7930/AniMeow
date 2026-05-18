import 'package:flutter/material.dart';

import '../../components/anime_cover_image.dart';
import '../detail_props.dart';
import '../modules/header_module.dart';
import '../modules/module_factory.dart';

/// 杂志 Hero 布局：SliverAppBar 大封面 + 模块平铺
class MagazineLayout extends StatelessWidget {
  final DetailViewProps props;
  const MagazineLayout({super.key, required this.props});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    return CustomScrollView(
      slivers: [
        SliverAppBar(
          expandedHeight: 360,
          pinned: true,
          automaticallyImplyLeading: false,
          backgroundColor: cs.surface,
          surfaceTintColor: Colors.transparent,
          leading: IconButton(
            icon: const Icon(Icons.arrow_back_ios_new, color: Colors.white),
            tooltip: '返回',
            onPressed: props.onBackTap,
          ),
          actions: [
            IconButton(
              icon: const Icon(Icons.edit_outlined, color: Colors.white),
              tooltip: '编辑',
              onPressed: props.onEditTap,
            ),
          ],
          flexibleSpace: LayoutBuilder(
            builder: (context, constraints) {
              return FlexibleSpaceBar(
                background: Stack(
                  fit: StackFit.expand,
                  children: [
                    Hero(
                      tag: 'cover_${props.anime['id'] ?? props.title}',
                      child: AnimeCoverImage(
                        url: props.coverUrl,
                        appDocDir: props.appDocDir,
                      ),
                    ),
                    const DecoratedBox(
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          begin: Alignment.topCenter,
                          end: Alignment.bottomCenter,
                          colors: [
                            Colors.black54,
                            Colors.transparent,
                            Colors.black87,
                          ],
                          stops: [0.0, 0.4, 1.0],
                        ),
                      ),
                    ),
                    Positioned(
                      left: 20,
                      right: 20,
                      bottom: 22,
                      child: _HeroOverlay(props: props),
                    ),
                  ],
                ),
              );
            },
          ),
        ),
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(20, 20, 20, 48),
          sliver: SliverList(
            delegate: SliverChildBuilderDelegate(
              (context, index) {
                if (index >= props.orderedVisibleModules.length) return null;
                final module = props.orderedVisibleModules[index];
                return Padding(
                  padding: const EdgeInsets.only(bottom: 28),
                  child: buildDetailModule(module, props),
                );
              },
              childCount: props.orderedVisibleModules.length,
            ),
          ),
        ),
      ],
    );
  }
}

class _HeroOverlay extends StatelessWidget {
  final DetailViewProps props;
  const _HeroOverlay({required this.props});

  @override
  Widget build(BuildContext context) {
    final typeIcon = props.isAnime
        ? Icons.movie_filter_outlined
        : Icons.menu_book_rounded;
    final typeLabel = props.isAnime ? '番剧' : '漫画 / 小说';
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 6,
          runSpacing: 4,
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
              decoration: BoxDecoration(
                color: Colors.white.withValues(alpha: 0.25),
                borderRadius: BorderRadius.circular(6),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(typeIcon, size: 12, color: Colors.white),
                  const SizedBox(width: 4),
                  Text(
                    typeLabel,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 11,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ],
              ),
            ),
            if (props.seriesName != null && props.seriesName!.isNotEmpty)
              Material(
                color: Colors.transparent,
                child: InkWell(
                  onTap: props.onSeriesTap,
                  borderRadius: BorderRadius.circular(6),
                  child: Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                    decoration: BoxDecoration(
                      color: Colors.white.withValues(alpha: 0.2),
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Text(
                      props.seriesName!,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 11,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                ),
              ),
          ],
        ),
        const SizedBox(height: 8),
        Text(
          props.title,
          maxLines: 3,
          overflow: TextOverflow.ellipsis,
          style: const TextStyle(
            color: Colors.white,
            fontSize: 26,
            fontWeight: FontWeight.w900,
            height: 1.2,
            shadows: [
              Shadow(blurRadius: 8, color: Colors.black87),
            ],
          ),
        ),
        const SizedBox(height: 10),
        Row(
          children: [
            if (props.status.isNotEmpty)
              Container(
                padding:
                    const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                decoration: BoxDecoration(
                  color: props.statusColor.withValues(alpha: 0.9),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(
                  props.status,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 11,
                    fontWeight: FontWeight.w800,
                  ),
                ),
              ),
            if (props.airDate.isNotEmpty) ...[
              const SizedBox(width: 8),
              Flexible(
                child: Text(
                  props.airDate,
                  style: const TextStyle(
                    color: Colors.white70,
                    fontSize: 12,
                    fontWeight: FontWeight.w600,
                  ),
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
            const Spacer(),
            if (props.rating > 0)
              RatingDisplay(
                rating: props.rating,
                color: Colors.amber,
                iconSize: 18,
                textSize: 16,
              ),
          ],
        ),
      ],
    );
  }
}
