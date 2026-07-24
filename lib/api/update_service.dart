import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../utils/api_config.dart';

class UpdateService {
  static const MethodChannel _installerChannel = MethodChannel(
    'anime_tracker/update_installer',
  );

  /// 检查更新
  static Future<void> checkUpdate(
    BuildContext context, {
    bool showNoUpdate = false,
    bool isAutoCheck = false,
  }) async {
    final updateUri = ApiConfig.cloudUri('/api/check-update');
    if (updateUri == null) {
      debugPrint('检查更新已跳过：未配置 CLOUD_API_BASE');
      if (!isAutoCheck && context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('未配置云端地址（CLOUD_API_BASE），已跳过检查更新')),
        );
      }
      return;
    }

    try {
      // 获取当前版本
      final packageInfo = await PackageInfo.fromPlatform();
      final currentVersion = packageInfo.version;

      // 获取远程版本（设置超时）
      final response = await http
          .get(
            updateUri,
            headers: {'Authorization': 'Bearer ${ApiConfig.apiToken}'},
          )
          .timeout(const Duration(seconds: 10));

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
                    onTap: () => _startInAppDownload(
                      context,
                      ctx,
                      version: version,
                      url: entry.value.toString(),
                      channelName: entry.key,
                    ),
                  ),
                )
              else if (mainDownloadUrl.isNotEmpty)
                _buildChannelLink(
                  ctx,
                  icon: Icons.cloud_download_outlined,
                  label: '默认线路',
                  subtitle: '服务器主线',
                  onTap: () => _startInAppDownload(
                    context,
                    ctx,
                    version: version,
                    url: mainDownloadUrl,
                    channelName: '默认线路',
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
                  Uri.parse('https://github.com/xunlys7930/AniMeow/releases'),
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
              subtitle: 'https://github.com/xunlys7930/AniMeow',
              onTap: () => launchUrl(
                Uri.parse('https://github.com/xunlys7930/AniMeow/releases'),
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

  static void _startInAppDownload(
    BuildContext rootContext,
    BuildContext dialogContext, {
    required String version,
    required String url,
    required String channelName,
  }) {
    final uri = Uri.tryParse(url);
    if (kIsWeb ||
        uri == null ||
        !uri.hasScheme ||
        (uri.scheme != 'http' && uri.scheme != 'https')) {
      launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);
      return;
    }

    Navigator.pop(dialogContext);
    showDialog(
      context: rootContext,
      barrierDismissible: false,
      builder: (_) => _UpdateDownloadDialog(
        url: url,
        version: version,
        channelName: channelName,
      ),
    );
  }

  static Future<void> _openDownloadedUpdate(File file) async {
    if (Platform.isAndroid && p.extension(file.path).toLowerCase() == '.apk') {
      await _installerChannel.invokeMethod('openInstaller', {
        'path': file.path,
      });
      return;
    }

    final opened = await launchUrl(
      Uri.file(file.path, windows: Platform.isWindows),
      mode: LaunchMode.externalApplication,
    );
    if (!opened) {
      throw Exception('无法打开安装包：${file.path}');
    }
  }

  static Map<String, String>? _downloadHeadersFor(String url) {
    if (!ApiConfig.hasToken) return null;

    final uri = Uri.tryParse(url);
    final cloudUri = ApiConfig.cloudUri('/');
    if (uri == null || cloudUri == null) return null;
    if (uri.scheme == cloudUri.scheme &&
        uri.host == cloudUri.host &&
        uri.port == cloudUri.port) {
      return {'Authorization': 'Bearer ${ApiConfig.apiToken}'};
    }
    return null;
  }

  static String _safeUpdateFileName(String url, String version) {
    final uri = Uri.tryParse(url);
    final segments = uri?.pathSegments
        .where((s) => s.trim().isNotEmpty)
        .toList();
    var fileName = segments == null || segments.isEmpty
        ? ''
        : Uri.decodeComponent(segments.last);

    fileName = fileName.replaceAll(RegExp(r'[\\/:*?"<>|]'), '_').trim();
    if (fileName.isEmpty) {
      fileName = 'AniMeow-$version${_defaultInstallerExtension()}';
    } else if (p.extension(fileName).isEmpty) {
      fileName = '$fileName${_defaultInstallerExtension()}';
    }
    return fileName;
  }

  static String _defaultInstallerExtension() {
    if (Platform.isAndroid) return '.apk';
    if (Platform.isWindows) return '.exe';
    if (Platform.isMacOS) return '.dmg';
    if (Platform.isLinux) return '.AppImage';
    return '.bin';
  }

  static String _formatBytes(int bytes) {
    if (bytes <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB'];
    final index = math.min(
      units.length - 1,
      (math.log(bytes) / math.log(1024)).floor(),
    );
    final value = bytes / math.pow(1024, index);
    return '${value.toStringAsFixed(index == 0 ? 0 : 1)} ${units[index]}';
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

class _UpdateDownloadDialog extends StatefulWidget {
  final String url;
  final String version;
  final String channelName;

  const _UpdateDownloadDialog({
    required this.url,
    required this.version,
    required this.channelName,
  });

  @override
  State<_UpdateDownloadDialog> createState() => _UpdateDownloadDialogState();
}

class _UpdateDownloadDialogState extends State<_UpdateDownloadDialog> {
  final CancelToken _cancelToken = CancelToken();
  double? _progress;
  int _receivedBytes = 0;
  int _totalBytes = 0;
  String _statusText = '准备下载...';
  File? _downloadedFile;
  Object? _error;
  bool _isDownloading = true;
  bool _isOpeningInstaller = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _downloadUpdate());
  }

  @override
  void dispose() {
    if (!_cancelToken.isCancelled && _isDownloading) {
      _cancelToken.cancel('用户取消下载');
    }
    super.dispose();
  }

  Future<void> _downloadUpdate() async {
    try {
      final tempDir = await getTemporaryDirectory();
      final updateDir = Directory(
        p.join(tempDir.path, 'anime_tracker_updates'),
      );
      if (!await updateDir.exists()) {
        await updateDir.create(recursive: true);
      }

      final fileName = UpdateService._safeUpdateFileName(
        widget.url,
        widget.version,
      );
      final file = File(p.join(updateDir.path, fileName));
      if (await file.exists()) {
        await file.delete();
      }

      final dio = Dio(
        BaseOptions(
          connectTimeout: const Duration(seconds: 20),
          receiveTimeout: const Duration(minutes: 10),
          followRedirects: true,
        ),
      );

      final response = await dio.download(
        widget.url,
        file.path,
        cancelToken: _cancelToken,
        options: Options(
          headers: UpdateService._downloadHeadersFor(widget.url),
        ),
        onReceiveProgress: (received, total) {
          if (!mounted) return;
          setState(() {
            _receivedBytes = received;
            _totalBytes = total;
            _progress = total > 0 ? received / total : null;
            _statusText = total > 0
                ? '正在从「${widget.channelName}」下载更新'
                : '正在下载更新文件';
          });
        },
      );

      final contentType = response.headers.value(Headers.contentTypeHeader);
      if (contentType != null &&
          contentType.toLowerCase().contains('text/html')) {
        throw Exception('该线路不是安装包直链，请使用浏览器下载');
      }

      if (!mounted) return;
      setState(() {
        _downloadedFile = file;
        _isDownloading = false;
        _progress = 1;
        _statusText = '下载完成，可以开始安装';
      });
      await _openInstaller();
    } catch (e) {
      if (!mounted) return;
      final isCancelled =
          e is DioException && e.type == DioExceptionType.cancel;
      setState(() {
        _isDownloading = false;
        _error = isCancelled ? null : e;
        _statusText = isCancelled ? '下载已取消' : '下载失败，请稍后重试';
      });
    }
  }

  Future<void> _openInstaller() async {
    final file = _downloadedFile;
    if (file == null || _isOpeningInstaller) return;

    setState(() => _isOpeningInstaller = true);
    try {
      await UpdateService._openDownloadedUpdate(file);
      if (mounted) {
        setState(() => _statusText = '已打开安装包，请按系统提示完成更新');
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _error = e;
          _statusText = '安装包打开失败，请重试或使用备用渠道';
        });
      }
    } finally {
      if (mounted) {
        setState(() => _isOpeningInstaller = false);
      }
    }
  }

  Future<void> _openExternalDownload() async {
    await launchUrl(
      Uri.parse(widget.url),
      mode: LaunchMode.externalApplication,
    );
  }

  @override
  Widget build(BuildContext context) {
    final progressText = _totalBytes > 0
        ? '${(_progress ?? 0).clamp(0, 1) * 100 ~/ 1}%  '
              '${UpdateService._formatBytes(_receivedBytes)} / '
              '${UpdateService._formatBytes(_totalBytes)}'
        : UpdateService._formatBytes(_receivedBytes);

    return AlertDialog(
      title: Row(
        children: [
          Icon(
            _downloadedFile == null ? Icons.downloading : Icons.task_alt,
            color: Theme.of(context).colorScheme.primary,
            size: 24,
          ),
          const SizedBox(width: 8),
          const Text('应用内更新'),
        ],
      ),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(_statusText),
          const SizedBox(height: 14),
          LinearProgressIndicator(value: _progress),
          const SizedBox(height: 8),
          Text(
            progressText,
            style: TextStyle(fontSize: 12, color: Colors.grey[600]),
          ),
          if (_error != null) ...[
            const SizedBox(height: 12),
            Text(
              _error.toString(),
              style: const TextStyle(fontSize: 12, color: Colors.redAccent),
            ),
          ],
        ],
      ),
      actions: [
        if (_isDownloading)
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          )
        else ...[
          if (_downloadedFile != null)
            TextButton(
              onPressed: _isOpeningInstaller ? null : _openInstaller,
              child: Text(_isOpeningInstaller ? '打开中...' : '打开安装包'),
            ),
          if (_error != null)
            TextButton(
              onPressed: _openExternalDownload,
              child: const Text('浏览器下载'),
            ),
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('关闭'),
          ),
        ],
      ],
    );
  }
}
