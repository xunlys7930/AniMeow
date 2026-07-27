import 'dart:async';

import 'package:flutter/material.dart';
import 'anime_style_analysis_page.dart';
import 'diagnostic_log_page.dart';
import 'server_discovery_page.dart';
import '../settings_manager.dart';

class TestingFeaturesPage extends StatelessWidget {
  const TestingFeaturesPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text("测试功能")),
      body: ListView(
        padding: const EdgeInsets.all(16.0),
        children: [
          _buildOperationLogSetting(context),
          _buildFeatureCard(
            context: context,
            icon: Icons.error_outline,
            color: Colors.redAccent,
            title: "诊断日志",
            subtitle: "查看并复制操作轨迹与错误记录",
            onTap: () {
              Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (context) => const DiagnosticLogPage(),
                ),
              );
            },
          ),
          _buildFeatureCard(
            context: context,
            icon: Icons.cloud_download_outlined,
            color: Colors.blueAccent,
            title: "云端资源发现",
            subtitle: "从服务器数据库浏览并筛选番剧",
            onTap: () {
              Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (context) => const ServerDiscoveryPage(),
                ),
              );
            },
          ),
          _buildFeatureCard(
            context: context,
            icon: Icons.auto_awesome,
            color: Colors.pinkAccent,
            title: "AI 看番风格分析",
            subtitle: "注册用户每天一次，让云端分析你的看番习惯",
            onTap: () {
              Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (context) => const AnimeStyleAnalysisPage(),
                ),
              );
            },
          ),
          // 未来可以在这里添加更多测试功能
        ],
      ),
    );
  }

  Widget _buildOperationLogSetting(BuildContext context) {
    return ValueListenableBuilder<bool>(
      valueListenable: SettingsManager().operationLogEnabledNotifier,
      builder: (context, enabled, _) {
        return Card(
          elevation: 0,
          margin: const EdgeInsets.only(bottom: 12),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
            side: BorderSide(
              color: Theme.of(
                context,
              ).colorScheme.outlineVariant.withValues(alpha: 0.5),
            ),
          ),
          child: SwitchListTile(
            value: enabled,
            onChanged: (value) {
              unawaited(_setOperationLogEnabled(context, value));
            },
            secondary: Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: Colors.deepPurple.withValues(alpha: 0.1),
                shape: BoxShape.circle,
              ),
              child: const Icon(
                Icons.bug_report_outlined,
                color: Colors.deepPurple,
                size: 24,
              ),
            ),
            title: const Text(
              '操作日志',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
            ),
            subtitle: Text(
              enabled ? '已开启：记录最近操作，仅保存在本机' : '默认关闭，遇到问题时临时开启即可',
              style: TextStyle(fontSize: 13, color: Colors.grey[600]),
            ),
          ),
        );
      },
    );
  }

  Future<void> _setOperationLogEnabled(BuildContext context, bool value) async {
    if (value) {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: const Text('开启操作日志？'),
          content: const Text(
            '开启后会在本机记录最近的页面和关键操作，用于定位问题。日志不会自动上传，\n'
            '可随时在诊断日志页面复制或清空。',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialogContext, false),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(dialogContext, true),
              child: const Text('开启'),
            ),
          ],
        ),
      );
      if (confirmed != true || !context.mounted) return;
    }

    await SettingsManager().setOperationLogEnabled(value);
    if (!context.mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(value ? '操作日志已开启' : '操作日志已关闭')));
  }

  Widget _buildFeatureCard({
    required BuildContext context,
    required IconData icon,
    required Color color,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
  }) {
    return Card(
      elevation: 0,
      margin: const EdgeInsets.only(bottom: 12),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: BorderSide(
          color: Theme.of(
            context,
          ).colorScheme.outlineVariant.withValues(alpha: 0.5),
        ),
      ),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 20),
          child: Row(
            children: [
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: color.withValues(alpha: 0.1),
                  shape: BoxShape.circle,
                ),
                child: Icon(icon, color: color, size: 24),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      subtitle,
                      style: TextStyle(fontSize: 13, color: Colors.grey[600]),
                    ),
                  ],
                ),
              ),
              Icon(Icons.chevron_right, color: Colors.grey[400]),
            ],
          ),
        ),
      ),
    );
  }
}
