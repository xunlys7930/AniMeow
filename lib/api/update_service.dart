import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../utils/api_config.dart';

class UpdateService {
  static const String updateUrl = 'http://47.103.83.247:3000/api/check-update';

  /// 检查更新
  static Future<void> checkUpdate(
    BuildContext context, {
    bool showNoUpdate = false,
    bool isAutoCheck = false,
  }) async {
    try {
      // 获取当前版本
      final packageInfo = await PackageInfo.fromPlatform();
      final currentVersion = packageInfo.version;

      // 获取远程版本（设置超时）
      final response = await http.get(
        Uri.parse(updateUrl),
        headers: {'Authorization': 'Bearer ${ApiConfig.apiToken}'},
      ).timeout(const Duration(seconds: 10));

      if (response.statusCode == 200) {
        final Map<String, dynamic> rootData = json.decode(
          utf8.decode(response.bodyBytes),
        );
        if (rootData['status'] == 'success' && rootData['data'] != null) {
          final Map<String, dynamic> data = rootData['data'];

          final latestVersion = data['versionName'] as String? ?? '';
          final changelog = data['updateLog'] ?? '没有提供更新内容';
          final isForce = data['isForceUpdate'] as bool? ?? false;

          // 解析多线路下载地址
          final Map<String, dynamic> downloadUrls = Map<String, dynamic>.from(
            data['downloadUrls'] ?? {},
          );
          final String mainDownloadUrl = data['downloadUrl'] as String? ?? '';

          if (latestVersion.isNotEmpty &&
              _isNewVersion(currentVersion, latestVersion)) {
            if (context.mounted) {
              _showUpdateDialog(
                context,
                latestVersion,
                changelog.toString(),
                mainDownloadUrl,
                downloadUrls,
                isForce,
              );
            }
          } else {
            // 如果是自动检查，且没有新版本，则静默处理
            if (!isAutoCheck && showNoUpdate && context.mounted) {
              _showLatestVersionDialog(context, currentVersion);
            }
          }
        }
      } else {
        // 非静默模式下提示错误
        if (!isAutoCheck && context.mounted) {
          _showErrorDialog(context, '服务器状态异常: ${response.statusCode}');
        }
      }
    } catch (e) {
      debugPrint('检查更新出错: $e');
      if (context.mounted) {
        // 只有在非静默模式，或者明确检测到网络断开时才提示
        if (!isAutoCheck) {
          String errorMessage = e.toString();
          if (errorMessage.contains('SocketException') ||
              errorMessage.contains('Connection failed')) {
            errorMessage = '网络连接失败，请检查网络设置或使用下方备用渠道';
          }
          _showErrorDialog(context, errorMessage);
        } else if (e.toString().contains('SocketException') ||
            e.toString().contains('Connection failed')) {
          // 自动检查时仅通过 SnackBar 弱提示
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(const SnackBar(content: Text('网络未连接，无法检查更新')));
        }
      }
    }
  }

  /// 版本对比
  static bool _isNewVersion(String current, String latest) {
    try {
      // 清理版本号后缀（如 "+11", "-beta" 等），确保可以无障碍转换为 int
      final cleanCurrent = current.split('+')[0].split('-')[0].trim();
      final cleanLatest = latest.split('+')[0].split('-')[0].trim();

      List<int> currentParts = cleanCurrent.split('.').map(int.parse).toList();
      List<int> latestParts = cleanLatest.split('.').map(int.parse).toList();

      for (int i = 0; i < latestParts.length; i++) {
        if (i >= currentParts.length) return true;
        if (latestParts[i] > currentParts[i]) return true;
        if (latestParts[i] < currentParts[i]) return false;
      }
    } catch (e) {
      return latest != current;
    }
    return false;
  }

  /// 显示更新弹窗
  static void _showUpdateDialog(
    BuildContext context,
    String version,
    String changelog,
    String mainDownloadUrl,
    Map<String, dynamic> downloadUrls,
    bool force,
  ) {
    showDialog(
      context: context,
      barrierDismissible: !force,
      builder: (ctx) => AlertDialog(
        title: Text('发现新版本 v$version'),
        content: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                '更新内容：',
                style: TextStyle(fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 8),
              Text(changelog),
              const SizedBox(height: 20),
              const Divider(),
              const SizedBox(height: 12),
              const Text(
                '推荐下载线路：',
                style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
              ),
              const SizedBox(height: 8),
              // 动态生成服务器提供的线路
              if (downloadUrls.isNotEmpty)
                ...downloadUrls.entries.map(
                  (entry) => _buildChannelLink(
                    ctx,
                    icon: Icons.speed_outlined,
                    label: entry.key,
                    subtitle: '点击下载本线路',
                    onTap: () => launchUrl(
                      Uri.parse(entry.value.toString()),
                      mode: LaunchMode.externalApplication,
                    ),
                  ),
                )
              else if (mainDownloadUrl.isNotEmpty)
                _buildChannelLink(
                  ctx,
                  icon: Icons.cloud_download_outlined,
                  label: '默认线路',
                  subtitle: '服务器主线',
                  onTap: () => launchUrl(
                    Uri.parse(mainDownloadUrl),
                    mode: LaunchMode.externalApplication,
                  ),
                ),
              const SizedBox(height: 8),
              const Text(
                '备用下载渠道：',
                style: TextStyle(
                  fontWeight: FontWeight.bold,
                  fontSize: 13,
                  color: Colors.grey,
                ),
              ),
              const SizedBox(height: 8),
              _buildChannelLink(
                ctx,
                icon: Icons.group_add,
                label: 'QQ 交流群',
                subtitle: '1073623448',
                onTap: () {
                  Clipboard.setData(const ClipboardData(text: '1073623448'));
                  ScaffoldMessenger.of(
                    ctx,
                  ).showSnackBar(const SnackBar(content: Text('群号已复制')));
                },
              ),
              _buildChannelLink(
                ctx,
                icon: Icons.cloud_download,
                label: '蓝奏云下载',
                subtitle: '密码: 8k6c',
                onTap: () => launchUrl(
                  Uri.parse('https://wwbik.lanzoup.com/b018806eod'),
                  mode: LaunchMode.externalApplication,
                ),
              ),
              _buildChannelLink(
                ctx,
                icon: Icons.code_rounded,
                label: 'GitHub Release',
                subtitle: '全球分流 (备用线路)',
                onTap: () => launchUrl(
                  Uri.parse('https://github.com/tanpeng343-ui/-/releases'),
                  mode: LaunchMode.externalApplication,
                ),
              ),
            ],
          ),
        ),
        actions: [
          if (!force)
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('以后再说'),
            ),
        ],
      ),
    );
  }

  /// 显示“已是最新版本”对话框
  static void _showLatestVersionDialog(BuildContext context, String version) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Row(
          children: [
            Icon(Icons.check_circle, color: Colors.green, size: 24),
            SizedBox(width: 8),
            Text('已是最新版本'),
          ],
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('当前版本：v$version'),
            const SizedBox(height: 16),
            const Divider(),
            const SizedBox(height: 12),
            const Text(
              '备用下载渠道：',
              style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
            ),
            const SizedBox(height: 12),
            _buildChannelLink(
              ctx,
              icon: Icons.group_add,
              label: 'QQ 交流群',
              subtitle: '1073623448',
              onTap: () {
                Clipboard.setData(const ClipboardData(text: '1073623448'));
                ScaffoldMessenger.of(
                  ctx,
                ).showSnackBar(const SnackBar(content: Text('群号已复制')));
              },
            ),
            _buildChannelLink(
              ctx,
              icon: Icons.downloading,
              label: 'GitHub 更新地址',
              subtitle: 'https://github.com/tanpeng343-ui/-',
              onTap: () => launchUrl(
                Uri.parse('https://github.com/tanpeng343-ui/-/releases'),
                mode: LaunchMode.externalApplication,
              ),
            ),
            _buildChannelLink(
              ctx,
              icon: Icons.cloud_download,
              label: '蓝奏云网盘',
              subtitle: '密码: 8k6c',
              onTap: () => launchUrl(
                Uri.parse('https://wwbik.lanzoup.com/b018806eod'),
                mode: LaunchMode.externalApplication,
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('了解'),
          ),
        ],
      ),
    );
  }

  /// 显示“检查更新失败”对话框
  static void _showErrorDialog(BuildContext context, String error) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Row(
          children: [
            Icon(Icons.error_outline, color: Colors.redAccent, size: 24),
            SizedBox(width: 8),
            Text('检查更新失败'),
          ],
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              error,
              style: const TextStyle(fontSize: 13, color: Colors.grey),
            ),
            const SizedBox(height: 16),
            const Divider(),
            const SizedBox(height: 12),
            const Text(
              '你可以通过以下备用渠道获取更新：',
              style: TextStyle(fontWeight: FontWeight.bold, fontSize: 13),
            ),
            const SizedBox(height: 12),
            _buildChannelLink(
              ctx,
              icon: Icons.group_add,
              label: 'QQ 交流群',
              subtitle: '1073623448',
              onTap: () {
                Clipboard.setData(const ClipboardData(text: '1073623448'));
                ScaffoldMessenger.of(
                  ctx,
                ).showSnackBar(const SnackBar(content: Text('群号已复制')));
              },
            ),
            _buildChannelLink(
              ctx,
              icon: Icons.cloud_download,
              label: '蓝奏云下载',
              subtitle: '密码: 8k6c',
              onTap: () => launchUrl(
                Uri.parse('https://wwbik.lanzoup.com/b018806eod'),
                mode: LaunchMode.externalApplication,
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('关闭'),
          ),
        ],
      ),
    );
  }

  /// 构建渠道链接项
  static Widget _buildChannelLink(
    BuildContext context, {
    required IconData icon,
    required String label,
    required String subtitle,
    required VoidCallback onTap,
  }) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: Icon(icon, color: Theme.of(context).primaryColor, size: 20),
      title: Text(label, style: const TextStyle(fontSize: 14)),
      subtitle: Text(
        subtitle,
        style: TextStyle(fontSize: 12, color: Colors.grey[600]),
      ),
      trailing: const Icon(Icons.open_in_new, size: 14, color: Colors.grey),
      onTap: onTap,
    );
  }
}
