import 'package:flutter/material.dart';

import '../../components/anime_cover_image.dart';
import '../detail_props.dart';
import 'module_section.dart';

/// 同系列其他作品横滑卡片（无系列或仅自身一部时返回空）
class SiblingsModule extends StatelessWidget {
  final DetailViewProps props;
  const SiblingsModule({super.key, required this.props});

  @override
  Widget build(BuildContext context) {
    if (props.siblings.isEmpty) return const SizedBox.shrink();
    final cs = Theme.of(context).colorScheme;

    return ModuleSection(
      icon: Icons.auto_awesome_motion_outlined,
      iconColor: cs.secondary,
      title: '同系列作品',
      trailing: Text(
        '${props.siblings.length} 部',
        style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
      ),
      child: SizedBox(
        height: 100,
        child: ListView.separated(
          scrollDirection: Axis.horizontal,
          itemCount: props.siblings.length,
          separatorBuilder: (_, __) => const SizedBox(width: 10),
          itemBuilder: (context, index) {
            final sibling = props.siblings[index];
            final siblingColor =
                props.statusColors[sibling['status']] ?? Colors.grey;
            return GestureDetector(
              onTap: () => props.onSiblingTap(sibling),
              child: Container(
                width: 210,
                padding: const EdgeInsets.all(8),
                decoration: BoxDecoration(
                  color: cs.surfaceContainerHigh,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Row(
                  children: [
                    ClipRRect(
                      borderRadius: BorderRadius.circular(
                        props.coverBorderRadius,
                      ),
                      child: SizedBox(
                        width: 56,
                        height: 80,
                        child: AnimeCoverImage(
                          url: (sibling['cover_url'] ?? '') as String?,
                          appDocDir: props.appDocDir,
                        ),
                      ),
                    ),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Column(
                        mainAxisAlignment: MainAxisAlignment.center,
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            (sibling['title'] ?? '').toString(),
                            style: const TextStyle(
                              fontSize: 13,
                              fontWeight: FontWeight.w700,
                            ),
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                          ),
                          const SizedBox(height: 6),
                          Container(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 6,
                              vertical: 2,
                            ),
                            decoration: BoxDecoration(
                              color: siblingColor.withValues(alpha: 0.14),
                              borderRadius: BorderRadius.circular(4),
                            ),
                            child: Text(
                              (sibling['status'] ?? '未知').toString(),
                              style: TextStyle(
                                fontSize: 10,
                                color: siblingColor,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}
