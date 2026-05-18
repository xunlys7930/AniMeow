import 'package:flutter/material.dart';

import '../../../settings_manager.dart';

/// 评分前置图标的可选预设（emoji 渲染，跨平台一致）
enum RatingIcon {
  catPaw('🐾', '猫爪'),
  catFace('😺', '猫脸'),
  star('⭐', '星星'),
  sparkleStar('🌟', '亮闪星'),
  heart('❤️', '爱心'),
  fire('🔥', '火焰'),
  diamond('💎', '钻石'),
  sparkle('✨', '闪光'),
  thumb('👍', '点赞'),
  clover('🍀', '四叶草'),
  sakura('🌸', '樱花'),
  trophy('🏆', '奖杯'),
  none('', '不显示');

  final String emoji;
  final String label;
  const RatingIcon(this.emoji, this.label);

  String get persistKey => name;

  static RatingIcon fromPersistKey(String? key) {
    if (key == null) return RatingIcon.catPaw;
    return values.firstWhere(
      (e) => e.persistKey == key,
      orElse: () => RatingIcon.catPaw,
    );
  }
}

/// 评分前置图标 widget；自动跟随用户设置（[SettingsManager.ratingIconNotifier]）
///
/// 使用：直接放在 Row 里、紧挨着评分数字。当用户选「不显示」时返回
/// [SizedBox.shrink]，调用方无需再判空。
class RatingIconWidget extends StatelessWidget {
  /// emoji 字号；大约和邻接的评分数字字号一致或稍大
  final double size;

  /// 可选：覆盖全局设置（用于设置页里的预览）
  final RatingIcon? forced;

  const RatingIconWidget({super.key, this.size = 12, this.forced});

  @override
  Widget build(BuildContext context) {
    if (forced != null) return _render(forced!);
    return ValueListenableBuilder<RatingIcon>(
      valueListenable: SettingsManager().ratingIconNotifier,
      builder: (context, icon, _) => _render(icon),
    );
  }

  Widget _render(RatingIcon icon) {
    if (icon == RatingIcon.none || icon.emoji.isEmpty) {
      return const SizedBox.shrink();
    }
    return Text(
      icon.emoji,
      style: TextStyle(fontSize: size, height: 1.0),
    );
  }
}
