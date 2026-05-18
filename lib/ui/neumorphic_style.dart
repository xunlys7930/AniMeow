import 'package:flutter/material.dart';
import 'design_tokens.dart';

/// 通用卡片容器（历史命名 NeumorphicContainer，现使用 M3 surfaceContainerLow + 柔和阴影）
///
/// 仅保留作为兼容入口供 13 个现存调用点使用，视觉已重写为 Material You / Expressive
/// 规范。后续清理可重命名为 `AppCard` 并批量替换。
///
/// 字段说明：
/// - [color]: 不传则使用主题的 `surfaceContainerLow`
/// - [borderRadius]: 默认 [AppRadius.lg]（32）保持与旧 API 一致
/// - [isPressed]: 凹陷态（移除阴影）
class NeumorphicContainer extends StatelessWidget {
  final Widget child;
  final double borderRadius;
  final EdgeInsetsGeometry? padding;
  final EdgeInsetsGeometry? margin;
  final Color? color;
  final bool isPressed;

  const NeumorphicContainer({
    super.key,
    required this.child,
    this.borderRadius = AppRadius.lg,
    this.padding,
    this.margin,
    this.color,
    this.isPressed = false,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Container(
      margin: margin,
      padding: padding,
      decoration: BoxDecoration(
        color: color ?? colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(borderRadius),
        border: isPressed
            ? Border.all(
                color: colorScheme.outlineVariant.withValues(alpha: 0.4),
                width: 0.5,
              )
            : null,
        boxShadow: isPressed ? null : AppElevation.card(context),
      ),
      child: child,
    );
  }
}
