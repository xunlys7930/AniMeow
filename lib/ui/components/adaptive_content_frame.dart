import 'package:flutter/material.dart';

import '../design_tokens.dart';

/// 为普通页面提供统一的响应式水平留白和内容最大宽度。
///
/// 页面仍可在 [child] 内使用 [LayoutBuilder] 决定列数；这里仅负责让内容
/// 在手机上保留安全边距、在桌面上居中，避免每个页面重复计算宽度。
class AdaptiveContentFrame extends StatelessWidget {
  final Widget child;
  final double maxContentWidth;
  final double top;
  final double bottom;
  final double minimumHorizontalPadding;

  const AdaptiveContentFrame({
    super.key,
    required this.child,
    this.maxContentWidth = 1200,
    this.top = AppSpacing.lg,
    this.bottom = AppSpacing.xxl,
    this.minimumHorizontalPadding = AppSpacing.lg,
  });

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final responsiveMinimum = AppBreakpoints.pagePadding(
          constraints.maxWidth,
        );
        final horizontalPadding = AppBreakpoints.centeredPadding(
          constraints.maxWidth,
          maxContentWidth: maxContentWidth,
          minimum: responsiveMinimum > minimumHorizontalPadding
              ? responsiveMinimum
              : minimumHorizontalPadding,
        );

        return Padding(
          padding: EdgeInsets.fromLTRB(
            horizontalPadding,
            top,
            horizontalPadding,
            bottom,
          ),
          child: child,
        );
      },
    );
  }
}
