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
  /// 生成一份本地 ZIP 备份文件，供分享导出或云端上传复用。
  static Future<File> createBackupZipFile() async {
    final encoder = ZipFileEncoder();
    var encoderOpened = false;

    try {
      final tempDir = await getTemporaryDirectory();
      final now = DateTime.now();
      final String fileName =
          'AniMeow_backup_${_formatBackupTimestamp(now)}.zip';
      final String zipPath = path.join(tempDir.path, fileName);
      final zipFile = File(zipPath);
      if (await zipFile.exists()) {
        await zipFile.delete();
      }

      encoder.create(zipPath);
      encoderOpened = true;

      final dbPath = await DatabaseHelper().getDbPath();
      final dbFile = File(dbPath);
      if (await dbFile.exists()) {
        await DatabaseHelper().closeDatabase();
        encoder.addFile(dbFile, 'anime_tracker_v5.db');
      } else {
        throw "未找到数据库文件";
      }

      final appDocDir = await getApplicationDocumentsDirectory();
      final coverDir = Directory(path.join(appDocDir.path, 'covers'));

      if (await coverDir.exists()) {
        await encoder.addDirectory(coverDir, includeDirName: true);
      }

      encoder.close();
      encoderOpened = false;

      return zipFile;
    } finally {
      if (encoderOpened) {
        try {
          encoder.close();
        } catch (_) {}
      }
      await DatabaseHelper().database;
    }
  }

  static String _formatBackupTimestamp(DateTime value) {
    return [
      value.year.toString().padLeft(4, '0'),
      value.month.toString().padLeft(2, '0'),
      value.day.toString().padLeft(2, '0'),
      value.hour.toString().padLeft(2, '0'),
      value.minute.toString().padLeft(2, '0'),
      value.second.toString().padLeft(2, '0'),
    ].join();
  }

  /// 导出数据并分享备份文件
  static Future<void> exportData(BuildContext context) async {
    try {
      // --- 显示加载提示 ---
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('正在打包数据，请稍候...')));

      final zipFile = await createBackupZipFile();
      final fileName = path.basename(zipFile.path);

      if (!context.mounted) return;

      // --- 调用系统分享功能 ---
      final box = context.findRenderObject() as RenderBox?;
      if (box != null) {
        await Share.shareXFiles(
          [XFile(zipFile.path)],
          text: '这是我的追番喵备份数据',
          subject: fileName,
          sharePositionOrigin: box.localToGlobal(Offset.zero) & box.size,
        );
      } else {
        await Share.shareXFiles(
          [XFile(zipFile.path)],
          text: '这是我的追番喵备份数据',
          subject: fileName,
        );
      }
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
    final messenger = ScaffoldMessenger.of(context);

    try {
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

      if (!context.mounted) return false;
      return restoreFromZipFile(context, zipFile, onSuccess);
    } catch (e) {
      logger.e("导入错误: $e");
      try {
        messenger.showSnackBar(SnackBar(content: Text('导入失败: $e')));
      } catch (_) {}
      await DatabaseHelper().database;
      return false;
    }
  }

  /// 从指定 ZIP 备份文件恢复数据，可用于本地文件恢复和云端备份恢复。
  static Future<bool> restoreFromZipFile(
    BuildContext context,
    File zipFile,
    VoidCallback onSuccess,
  ) async {
    // 捕获稳定引用：避免后续页面被异步流程间接 deactivate。
    final navigator = Navigator.of(context);
    final messenger = ScaffoldMessenger.of(context);

    try {
      if (!context.mounted) return false;
      final confirm =
          await showDialog<bool>(
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

      if (!context.mounted) return false;
      bool loaderShown = true;
      showDialog(
        context: context,
        barrierDismissible: false,
        builder: (ctx) => const Center(child: CircularProgressIndicator()),
      ).then((_) => loaderShown = false);

      await DatabaseHelper().closeDatabase();

      final bytes = await zipFile.readAsBytes();
      final archive = await compute(_decodeArchive, bytes);

      final dbPath = await DatabaseHelper().getDbPath();
      final appDocDir = await getApplicationDocumentsDirectory();
      final coverDirPath = path.join(appDocDir.path, 'covers');

      final oldCoverDir = Directory(coverDirPath);
      if (await oldCoverDir.exists()) {
        await oldCoverDir.delete(recursive: true);
      }

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

      if (loaderShown) {
        navigator.pop();
        loaderShown = false;
      }

      PaintingBinding.instance.imageCache.clear();
      PaintingBinding.instance.imageCache.clearLiveImages();
      onSuccess();

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
