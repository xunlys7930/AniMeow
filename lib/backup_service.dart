import 'dart:io';
import 'package:archive/archive_io.dart';
import 'package:file_picker/file_picker.dart';
import 'package:path_provider/path_provider.dart';
import 'package:path/path.dart' as path;
import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart'; // 用于 compute
import 'package:share_plus/share_plus.dart';
import 'db/database_helper.dart';
import 'ui/components/restart_widget.dart'; // 修复引用
import 'utils/logger.dart';

// 【后台任务】定义一个顶层函数用于后台解压，必须是静态或顶层函数
Archive _decodeArchive(List<int> bytes) {
  return ZipDecoder().decodeBytes(bytes);
}

/// 备份与恢复服务类
class BackupService {
  /// 导出数据并分享备份文件
  static Future<void> exportData(BuildContext context) async {
    try {
      // --- 显示加载提示 ---
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('正在打包数据，请稍候...')));

      // --- 准备工作 ---
      final encoder = ZipFileEncoder();

      // 使用应用缓存目录
      final tempDir = await getTemporaryDirectory();
      final String fileName =
          'AniMeow_backup_${DateTime.now().year}${DateTime.now().month}${DateTime.now().day}.zip';
      final String zipPath = path.join(tempDir.path, fileName);

      // 创建压缩包
      encoder.create(zipPath);

      // A. 添加数据库文件
      final dbPath = await DatabaseHelper().getDbPath();
      final dbFile = File(dbPath);
      if (await dbFile.exists()) {
        // 关闭数据库以确保数据完整写入
        await DatabaseHelper().closeDatabase();
        encoder.addFile(dbFile, 'anime_tracker_v5.db');
      } else {
        throw "未找到数据库文件";
      }

      // B. 添加封面图片目录
      final appDocDir = await getApplicationDocumentsDirectory();
      final coverDir = Directory(path.join(appDocDir.path, 'covers'));

      if (await coverDir.exists()) {
        await encoder.addDirectory(coverDir, includeDirName: true);
      }

      encoder.close();

      // --- 调用系统分享功能 ---
      final box = context.findRenderObject() as RenderBox?;
      if (box != null) {
        await Share.shareXFiles(
          [XFile(zipPath)],
          text: '这是我的追番喵备份数据',
          subject: fileName,
          sharePositionOrigin: box.localToGlobal(Offset.zero) & box.size,
        );
      } else {
        await Share.shareXFiles(
          [XFile(zipPath)],
          text: '这是我的追番喵备份数据',
          subject: fileName,
        );
      }

      // 重新打开数据库连接
      await DatabaseHelper().database;
    } catch (e) {
      logger.e("导出错误: $e");
      if (context.mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('导出失败: $e')));
      }
      // 尝试恢复数据库连接
      await DatabaseHelper().database;
    }
  }

  /// 从备份文件导入数据
  static Future<bool> importData(
    BuildContext context,
    VoidCallback onSuccess,
  ) async {
    // 捕获稳定引用：避免后续 SettingsDataPage 被异步流程间接 deactivate
    // 后，再用其 context 抛 "deactivated widget's ancestor is unsafe"。
    final navigator = Navigator.of(context);
    final messenger = ScaffoldMessenger.of(context);

    try {
      // 1. 选择备份文件
      FilePickerResult? result = await FilePicker.platform.pickFiles(
        type: FileType.any,
      );

      if (result == null || result.files.single.path == null) {
        return false;
      }

      final File zipFile = File(result.files.single.path!);

      if (!zipFile.path.endsWith('.zip')) {
        messenger.showSnackBar(
          const SnackBar(content: Text('请选择 .zip 格式的备份文件')),
        );
        return false;
      }

      // 2. 确认覆盖 (在此处增加了提示文案)
      if (!context.mounted) return false;
      bool confirm =
          await showDialog(
            context: context,
            builder: (ctx) => AlertDialog(
              title: const Text('⚠️ 警告：覆盖数据'),
              content: const Text(
                '导入备份将【完全覆盖】当前设备上的所有数据和图片！\n'
                '此操作不可撤销。\n\n'
                '⚠️ 注意：若恢复过程中出现黑屏或卡死，请尝试手动重启应用。\n\n'
                '确定要继续吗？',
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(ctx, false),
                  child: const Text('取消'),
                ),
                TextButton(
                  style: TextButton.styleFrom(foregroundColor: Colors.red),
                  onPressed: () => Navigator.pop(ctx, true),
                  child: const Text('确认覆盖'),
                ),
              ],
            ),
          ) ??
          false;

      if (!confirm) return false;

      // 3. 显示加载弹窗 (防止误触)
      if (!context.mounted) return false;
      // 记录 loader 是否已弹出，方便容错关闭
      bool loaderShown = true;
      showDialog(
        context: context,
        barrierDismissible: false,
        builder: (ctx) => const Center(child: CircularProgressIndicator()),
      ).then((_) => loaderShown = false);

      // 4. 关闭数据库连接
      await DatabaseHelper().closeDatabase();

      // 5. 【后台解压】读取并解压 ZIP (使用 compute 防止 UI 卡死)
      final bytes = await zipFile.readAsBytes();
      final archive = await compute(_decodeArchive, bytes);

      // 6. 准备路径
      final dbPath = await DatabaseHelper().getDbPath();
      final appDocDir = await getApplicationDocumentsDirectory();
      final coverDirPath = path.join(appDocDir.path, 'covers');

      // 7. 清空旧图片目录
      final oldCoverDir = Directory(coverDirPath);
      if (await oldCoverDir.exists()) {
        await oldCoverDir.delete(recursive: true);
      }

      // 8. 写入新文件
      for (final file in archive) {
        if (file.isFile) {
          final data = file.content as List<int>;
          // 恢复数据库 (支持旧版本文件名，只要是 .db 后缀)
          if (file.name.endsWith('.db')) {
            final dbFile = File(dbPath);
            await dbFile.writeAsBytes(data, flush: true);
            logger.i("已恢复数据库文件: ${file.name}");
          }
          // 恢复图片
          else if (file.name.startsWith('covers/')) {
            final filename = file.name.replaceAll('covers/', '');
            if (filename.isNotEmpty) {
              final p = path.join(coverDirPath, filename);
              await File(p).create(recursive: true);
              await File(p).writeAsBytes(data);
            }
          }
        }
      }

      // 9. 关闭加载弹窗（只弹 1 次，不要 popUntil—— popUntil 会把
      //    SettingsDataPage 一并弹掉，导致 context 失效）
      if (loaderShown) {
        navigator.pop();
        loaderShown = false;
      }

      // 10. 清理内存缓存 + 提示 + 自动重启
      PaintingBinding.instance.imageCache.clear();
      PaintingBinding.instance.imageCache.clearLiveImages();

      messenger.showSnackBar(
        const SnackBar(
          content: Text('恢复成功，正在重启应用...'),
          duration: Duration(seconds: 2),
        ),
      );

      // 稍作延迟，让用户看到提示
      await Future.delayed(const Duration(milliseconds: 1500));

      if (context.mounted) {
        // 调用 main.dart 中的重启方法
        RestartWidget.restart(context);
      }

      return true;
    } catch (e) {
      logger.e("导入错误: $e");

      // 出错时尽力关闭 loader（用稳定引用，且容错忽略二次异常）
      try {
        navigator.pop();
      } catch (_) {}
      try {
        messenger.showSnackBar(SnackBar(content: Text('导入失败: $e')));
      } catch (_) {}
      // 恢复数据库连接
      await DatabaseHelper().database;
      return false;
    }
  }
}
