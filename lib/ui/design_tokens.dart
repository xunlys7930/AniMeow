import 'package:flutter/material.dart';

/// 全局设计令牌（Design Tokens）
///
/// 集中管理 4 种布局共用的圆角、间距、动效曲线、阴影、字号刻度，
/// 避免散落硬编码。所有数值在 Material You / M3 Expressive 规范基础上微调。
///
/// 使用方式：
/// ```dart
/// borderRadius: BorderRadius.circular(AppRadius.md),
/// padding: EdgeInsets.all(AppSpacing.lg),
/// ```

/// 圆角刻度（4 档）
class AppRadius {
  AppRadius._();

  static const double xs = 12;  // 小芯片、tag
  static const double sm = 20;  // 中型卡片、按钮
  static const double md = 28;  // 大卡片、底部 Sheet
  static const double lg = 32;  // Hero 卡片、对话框
}

/// 间距刻度（6 档，4 的倍数）
class AppSpacing {
  AppSpacing._();

  static const double xs = 4;
  static const double sm = 8;
  static const double md = 12;
  static const double lg = 16;
  static const double xl = 24;
  static const double xxl = 32;
}

/// 阴影层级（M3 elevation 0~5 的映射）
class AppElevation {
  AppElevation._();

  /// 卡片基础阴影（替代 NeumorphicStyle）
  static List<BoxShadow> card(BuildContext context) {
    final shadowColor = Theme.of(context).colorScheme.shadow;
    return [
      BoxShadow(
        color: shadowColor.withValues(alpha: 0.06),
        blurRadius: 12,
        offset: const Offset(0, 4),
      ),
    ];
  }

  /// 浮起按钮 / FAB 阴影
  static List<BoxShadow> floating(BuildContext context) {
    final shadowColor = Theme.of(context).colorScheme.shadow;
    return [
      BoxShadow(
        color: shadowColor.withValues(alpha: 0.12),
        blurRadius: 24,
        offset: const Offset(0, 8),
      ),
    ];
  }

  /// 大封面 / Hero 区域阴影
  static List<BoxShadow> hero(BuildContext context) {
    final shadowColor = Theme.of(context).colorScheme.shadow;
    return [
      BoxShadow(
        color: shadowColor.withValues(alpha: 0.16),
        blurRadius: 32,
        offset: const Offset(0, 12),
      ),
    ];
  }
}

/// 动效曲线 & 时长
class AppMotion {
  AppMotion._();

  static const Duration short = Duration(milliseconds: 180);
  static const Duration medium = Duration(milliseconds: 300);
  static const Duration long = Duration(milliseconds: 500);

  /// M3 Expressive 推荐曲线：emphasized
  static const Curve emphasized = Cubic(0.2, 0.0, 0, 1.0);

  /// 退出 / 折叠用
  static const Curve emphasizedAccelerate = Cubic(0.3, 0.0, 0.8, 0.15);

  /// 进入 / 展开用
  static const Curve emphasizedDecelerate = Cubic(0.05, 0.7, 0.1, 1.0);
}

/// 番剧封面常用宽高比
class AppCoverRatio {
  AppCoverRatio._();

  /// 标准海报比例 (2:3)，与 Bangumi 封面一致
  static const double poster = 2 / 3;

  /// 紧凑模式缩略图 (3:4)，更方正
  static const double compact = 3 / 4;
}
