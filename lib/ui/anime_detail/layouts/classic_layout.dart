import 'package:flutter/material.dart';

import '../../components/anime_cover_image.dart';
import '../../neumorphic_style.dart';
import '../detail_props.dart';
import '../modules/header_module.dart';
import '../modules/module_factory.dart';

/// 经典卡片式布局：垂直堆叠 NeumorphicContainer
class ClassicLayout extends StatelessWidget {
  final DetailViewProps props;
  const ClassicLayout({super.key, required this.props});

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 40),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // 头部卡片：左封面 + 右标题信息
          NeumorphicContainer(
            padding: const EdgeInsets.all(20),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _CoverThumb(props: props, width: 110, height: 154),
                const SizedBox(width: 18),
                Expanded(child: HeaderModule(props: props)),
              ],
            ),
          ),
          // 其余模块按用户顺序渲染
          for (final module in props.orderedVisibleModules) ...[
            const SizedBox(height: 16),
            NeumorphicContainer(
              padding: const EdgeInsets.all(20),
              child: buildDetailModule(module, props),
            ),
          ],
        ],
      ),
    );
  }
}

class _CoverThumb extends StatelessWidget {
  final DetailViewProps props;
  final double width;
  final double height;

  const _CoverThumb({
    required this.props,
    required this.width,
    required this.height,
  });

  @override
  Widget build(BuildContext context) {
    return Hero(
      tag: 'cover_${props.anime['id'] ?? props.anime['api_id'] ?? props.title}',
      child: ClipRRect(
        borderRadius: BorderRadius.circular(props.coverBorderRadius),
        child: SizedBox(
          width: width,
          height: height,
          child: AnimeCoverImage(
            url: props.coverUrl,
            appDocDir: props.appDocDir,
          ),
        ),
      ),
    );
  }
}
