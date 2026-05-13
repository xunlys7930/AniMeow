import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../../../db/database_helper.dart';
import '../../../settings_manager.dart';
import '../../design_tokens.dart';

/// 包装 Dismissible 实现"假删除"模式的滑动操作：
/// - 右滑（startToEnd）：触发 [onSwipeRight]，回弹（不真删除）
/// - 左滑（endToStart）：触发 [onSwipeLeft]，回弹（不真删除）
///
/// 用于番剧列表项的快捷"+1 集" / "改状态"操作。视觉上滑动时露出
/// 状态色背景 + 操作图标，松手后自动归位。
class SwipeActionTile extends StatelessWidget {
  final Widget child;
  final Key dismissKey;

  /// 右滑（→）操作：典型是 +1 集。null 表示不允许此方向。
  final Future<void> Function()? onSwipeRight;

  /// 右滑显示的图标 + 颜色 + 文字
  final IconData rightIcon;
  final Color rightColor;
  final String rightLabel;

  /// 左滑（←）操作：典型是循环状态。null 表示不允许此方向。
  final Future<void> Function()? onSwipeLeft;

  /// 左滑显示的图标 + 颜色 + 文字
  final IconData leftIcon;
  final Color leftColor;
  final String leftLabel;

  const SwipeActionTile({
    super.key,
    required this.child,
    required this.dismissKey,
    this.onSwipeRight,
    this.rightIcon = Icons.add_circle_rounded,
    this.rightColor = const Color(0xFF4CAF50),
    this.rightLabel = '+1 集',
    this.onSwipeLeft,
    this.leftIcon = Icons.swap_horiz_rounded,
    this.leftColor = const Color(0xFF2196F3),
    this.leftLabel = '改状态',
  });

  DismissDirection get _direction {
    if (onSwipeRight != null && onSwipeLeft != null) {
      return DismissDirection.horizontal;
    }
    if (onSwipeRight != null) return DismissDirection.startToEnd;
    if (onSwipeLeft != null) return DismissDirection.endToStart;
    return DismissDirection.none;
  }

  @override
  Widget build(BuildContext context) {
    if (_direction == DismissDirection.none) {
      return child;
    }
    return Dismissible(
      key: dismissKey,
      direction: _direction,
      // 高 threshold 让"假删除"更需要刻意滑动，避免误触
      dismissThresholds: const {
        DismissDirection.startToEnd: 0.32,
        DismissDirection.endToStart: 0.32,
      },
      background: _buildBackground(
        alignment: Alignment.centerLeft,
        color: rightColor,
        icon: rightIcon,
        label: rightLabel,
      ),
      secondaryBackground: _buildBackground(
        alignment: Alignment.centerRight,
        color: leftColor,
        icon: leftIcon,
        label: leftLabel,
      ),
      // 关键：confirmDismiss 返回 false 实现"假删除"——视觉上滑出去，
      // 但实际不从列表移除，只触发副作用，然后自动回弹。
      confirmDismiss: (direction) async {
        HapticFeedback.mediumImpact();
        if (direction == DismissDirection.startToEnd) {
          await onSwipeRight?.call();
        } else if (direction == DismissDirection.endToStart) {
          await onSwipeLeft?.call();
        }
        return false; // 阻止真删除
      },
      child: child,
    );
  }

  Widget _buildBackground({
    required Alignment alignment,
    required Color color,
    required IconData icon,
    required String label,
  }) {
    return Container(
      alignment: alignment,
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.xl),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.18),
        borderRadius: BorderRadius.circular(AppRadius.md),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, color: color, size: 28),
          const SizedBox(width: AppSpacing.sm),
          Text(
            label,
            style: TextStyle(
              fontSize: 14,
              fontWeight: FontWeight.w900,
              color: color,
              letterSpacing: 0.3,
            ),
          ),
        ],
      ),
    );
  }
}

/// 番剧"+1 集"业务逻辑
///
/// 行为：
/// - 仅对 `type == 'anime'` 的番剧生效；series 直接跳过
/// - 已经"看完"或"弃坑"的不允许 +1（返回 false）
/// - 普通 +1 集后若达到 total_episodes 且开启了自动归纳：状态变成完成态
/// - 落库后调用 [onRefresh] 刷新 UI
///
/// 返回 true 表示成功更新；false 表示因业务规则跳过（不弹消息）。
Future<bool> incrementEpisode(
  Map<String, dynamic> item, {
  required BuildContext context,
  required Future<void> Function() onRefresh,
}) async {
  if (item['type'] == 'series') return false;
  final int id = item['id'] as int;
  final int total = (item['total_episodes'] as int?) ?? 0;
  final int currentWatched = (item['watched_episodes'] as int?) ?? 0;
  final String currentStatus = (item['status'] ?? '').toString();

  if (currentStatus == '看完' || currentStatus == '弃坑') {
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('「${item['title']}」已经$currentStatus了'),
          duration: const Duration(seconds: 1),
        ),
      );
    }
    return false;
  }

  // 计算新进度
  final int newWatched =
      total > 0 ? (currentWatched + 1).clamp(0, total) : currentWatched + 1;

  // 自动归纳完成态
  final bool autoTransition =
      SettingsManager().autoStatusTransitionNotifier.value;
  final String finishStatus =
      SettingsManager().completionStatusNotifier.value;
  final bool reachedEnd = total > 0 && newWatched >= total;
  final String newStatus = (autoTransition && reachedEnd) ? finishStatus : currentStatus;

  final Map<String, dynamic> patch = {
    'id': id,
    'watched_episodes': newWatched,
    'status': newStatus,
  };
  if (autoTransition && reachedEnd) {
    patch['watch_finish_date'] = DateTime.now().toIso8601String().split('T')[0];
  }

  await DatabaseHelper().updateAnime(patch);
  await onRefresh();

  if (context.mounted) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          reachedEnd && autoTransition
              ? '「${item['title']}」追完啦 🎉'
              : '「${item['title']}」 +1 集（${newWatched}${total > 0 ? '/$total' : ''}）',
        ),
        duration: const Duration(milliseconds: 1500),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }
  return true;
}

/// 番剧状态循环切换
///
/// 在数据库中读取所有自定义状态的顺序，循环到下一个。
/// 落库后调用 [onRefresh]。
Future<bool> cycleStatus(
  Map<String, dynamic> item, {
  required BuildContext context,
  required Future<void> Function() onRefresh,
}) async {
  if (item['type'] == 'series') return false;
  final int id = item['id'] as int;
  final String currentStatus = (item['status'] ?? '').toString();

  final statuses = await DatabaseHelper().getAllStatuses();
  if (statuses.isEmpty) return false;

  final names =
      statuses.map((s) => (s['name'] as String?) ?? '').toList(growable: false);
  int idx = names.indexOf(currentStatus);
  if (idx < 0) idx = -1;
  final String next = names[(idx + 1) % names.length];

  await DatabaseHelper().updateAnime({
    'id': id,
    'status': next,
  });
  await onRefresh();

  if (context.mounted) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('「${item['title']}」状态：$currentStatus → $next'),
        duration: const Duration(milliseconds: 1500),
        behavior: SnackBarBehavior.floating,
      ),
    );
  }
  return true;
}
