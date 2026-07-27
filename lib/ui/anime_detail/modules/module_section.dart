import 'package:flutter/material.dart';

/// 详情页模块的标准外壳（章节标题 + 内容）
///
/// 不带背景容器——是否套卡片由各 layout 自行决定。
/// 这样可以让 classic（套卡片）和 magazine（不套）共享同一模块。
class ModuleSection extends StatelessWidget {
  final IconData icon;
  final Color iconColor;
  final String title;
  final Widget? trailing;
  final Widget child;

  const ModuleSection({
    super.key,
    required this.icon,
    required this.iconColor,
    required this.title,
    this.trailing,
    required this.child,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(icon, size: 18, color: iconColor),
            const SizedBox(width: 8),
            Text(
              title,
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w800),
            ),
            if (trailing != null) ...[const Spacer(), trailing!],
          ],
        ),
        const SizedBox(height: 14),
        child,
      ],
    );
  }
}
