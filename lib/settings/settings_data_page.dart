import 'dart:io';
import 'package:flutter/material.dart';
import '../db/database_helper.dart';
import '../ui/pages/bangumi_import_page.dart';
import '../ui/pages/excel_import_page.dart';
import '../backup_service.dart';
import '../ui/pages/status_management_page.dart';
import '../ui/pages/series_management_page.dart';
import '../ui/pages/tag_management_page.dart';
import 'package:file_picker/file_picker.dart';
import 'package:excel/excel.dart' as excel_pkg;
import 'dart:typed_data';
import 'package:flutter_cache_manager/flutter_cache_manager.dart';

class SettingsDataPage extends StatelessWidget {
  final VoidCallback? onDatabaseRefresh;

  const SettingsDataPage({super.key, this.onDatabaseRefresh});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.grey[100],
      appBar: AppBar(title: const Text('数据与管理'), centerTitle: true),
      body: ListView(
        padding: const EdgeInsets.symmetric(vertical: 16),
        children: [
          _buildGroupTitle("个性化管理"),
          _buildDataCard([
            _buildDataTile(
              context,
              icon: Icons.label_outline,
              color: Colors.deepOrange,
              title: "状态管理",
              subtitle: "管理观看状态标签",
              onTap: () async {
                await Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (context) => const StatusManagementPage(),
                  ),
                );
                onDatabaseRefresh?.call();
              },
            ),
            _buildDivider(),
            _buildDataTile(
              context,
              icon: Icons.layers_outlined,
              color: Colors.indigo,
              title: "系列管理",
              subtitle: "管理番剧系列与封面",
              onTap: () async {
                await Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (context) => const SeriesManagementPage(),
                  ),
                );
                onDatabaseRefresh?.call();
              },
            ),
            _buildDivider(),
            _buildDataTile(
              context,
              icon: Icons.label_important_outline,
              color: Colors.deepOrangeAccent,
              title: "标签管理",
              subtitle: "管理番剧标签与使用次数",
              onTap: () async {
                await Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (context) => const TagManagementPage(),
                  ),
                );
                onDatabaseRefresh?.call();
              },
            ),
          ]),
          _buildGroupTitle("导入与导出"),
          _buildDataCard([
            _buildDataTile(
              context,
              icon: Icons.upload_file,
              color: Colors.blue,
              title: "导出备份 (Zip)",
              subtitle: "备份所有数据及本地封面",
              onTap: () => BackupService.exportData(context),
            ),
            _buildDivider(),
            _buildDataTile(
              context,
              icon: Icons.settings_backup_restore,
              color: Colors.green,
              title: "恢复数据 (Zip)",
              subtitle: "从备份文件还原数据",
              onTap: () =>
                  BackupService.importData(context, onDatabaseRefresh ?? () {}),
            ),
            _buildDivider(),
            _buildDataTile(
              context,
              icon: Icons.cloud_download_outlined,
              color: Colors.pink,
              title: "Bangumi 导入",
              subtitle: "从公开收藏批量导入番剧",
              onTap: () async {
                final result = await Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (context) => const BangumiImportPage(),
                  ),
                );
                if (result == true) onDatabaseRefresh?.call();
              },
            ),
            _buildDivider(),
            _buildDataTile(
              context,
              icon: Icons.table_view,
              color: Colors.indigo,
              title: "Excel 导入",
              subtitle: "从 Excel 批量导入番剧",
              onTap: () async {
                final result = await Navigator.push(
                  context,
                  MaterialPageRoute(
                    builder: (context) => const ExcelImportPage(),
                  ),
                );
                if (result == true) onDatabaseRefresh?.call();
              },
            ),
            _buildDivider(),
            _buildDataTile(
              context,
              icon: Icons.table_chart_outlined,
              color: Colors.deepPurple,
              title: "Excel 导出",
              subtitle: "导出数据为表格进行编辑",
              onTap: () => _exportToExcel(context),
            ),
          ]),
          _buildGroupTitle("存储管理"),
          _buildDataCard([
            _buildDataTile(
              context,
              icon: Icons.cleaning_services,
              color: Colors.orange,
              title: "清理缓存",
              subtitle: "释放图片占用的空间",
              onTap: () => _showClearCacheDialog(context),
            ),
          ]),
        ],
      ),
    );
  }

  Widget _buildGroupTitle(String title) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(24, 16, 16, 8),
      child: Text(
        title,
        style: const TextStyle(
          fontSize: 13,
          fontWeight: FontWeight.bold,
          color: Colors.grey,
        ),
      ),
    );
  }

  Widget _buildDataCard(List<Widget> children) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Column(children: children),
    );
  }

  Widget _buildDivider() {
    return const Divider(
      height: 1,
      indent: 64,
      endIndent: 16,
      color: Color(0xFFF5F5F5),
    );
  }

  Widget _buildDataTile(
    BuildContext context, {
    required IconData icon,
    required Color color,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
  }) {
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      leading: Container(
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(14),
        ),
        child: Icon(icon, color: color, size: 24),
      ),
      title: Text(
        title,
        style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15),
      ),
      subtitle: Text(
        subtitle,
        style: TextStyle(color: Colors.grey[500], fontSize: 12),
      ),
      trailing: const Icon(
        Icons.arrow_forward_ios,
        size: 14,
        color: Colors.grey,
      ),
      onTap: onTap,
    );
  }

  // --- 逻辑方法 (从 SettingsPage 抽离) ---

  Future<void> _exportToExcel(BuildContext context) async {
    // 逻辑内容同 SettingsPage._exportToExcel
    // 为了简化，这里先调用 SettingsPage 的方法是不可能的，因为那是私有的。
    // 我们需要把业务逻辑也抽离到 Service 或者在这里重写。
    // 为了当前演示简约性，我们暂时保持逻辑在 DataPage。
    try {
      final animes = await DatabaseHelper().queryAllAnimes();
      if (animes.isEmpty) {
        if (context.mounted) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(const SnackBar(content: Text('没有番剧数据可以导出')));
        }
        return;
      }

      var excel = excel_pkg.Excel.createExcel();
      var sheet = excel['Sheet1'];

      sheet.appendRow([
        excel_pkg.TextCellValue('番剧标题'),
        excel_pkg.TextCellValue('状态'),
        excel_pkg.TextCellValue('已看集数'),
        excel_pkg.TextCellValue('总集数'),
        excel_pkg.TextCellValue('评价/备注'),
        excel_pkg.TextCellValue('标签'),
        excel_pkg.TextCellValue('制作公司'),
      ]);

      for (var anime in animes) {
        final tags = await DatabaseHelper().getTagsByAnimeId(anime['id']);
        String tagString = tags.map((t) => t['name']).join(', ');

        sheet.appendRow([
          excel_pkg.TextCellValue(anime['title']?.toString() ?? ""),
          excel_pkg.TextCellValue(anime['status']?.toString() ?? "未看"),
          excel_pkg.IntCellValue(anime['watched_episodes'] ?? 0),
          excel_pkg.IntCellValue(anime['total_episodes'] ?? 0),
          excel_pkg.TextCellValue(anime['review']?.toString() ?? ""),
          excel_pkg.TextCellValue(tagString),
          excel_pkg.TextCellValue(anime['studio']?.toString() ?? ""),
        ]);
      }

      var fileBytes = excel.save();
      if (fileBytes == null) throw '生成文件字节失败';

      String fileName =
          "追番喵导出数据_${DateTime.now().toString().split('.')[0].replaceAll(':', '-')}.xlsx";

      String? outputPath;
      if (Platform.isAndroid || Platform.isIOS) {
        outputPath = await FilePicker.platform.saveFile(
          dialogTitle: '选择保存位置',
          fileName: fileName,
          bytes: Uint8List.fromList(fileBytes),
        );
      } else {
        outputPath = await FilePicker.platform.saveFile(
          dialogTitle: '保存 Excel 文件',
          fileName: fileName,
          type: FileType.custom,
          allowedExtensions: ['xlsx'],
          bytes: Uint8List.fromList(fileBytes),
        );
      }

      if (outputPath != null) {
        await File(outputPath).writeAsBytes(fileBytes);
        if (context.mounted) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(SnackBar(content: Text('已成功导出到: $outputPath')));
        }
      }
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('导出失败: $e')));
      }
    }
  }

  void _showClearCacheDialog(BuildContext context) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('清理缓存'),
        content: const Text('确定要删除所有缓存的封面图吗？这样会释放空间，但下次查看时需要重新加载。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () async {
              Navigator.pop(ctx);
              await DefaultCacheManager().emptyCache();
              PaintingBinding.instance.imageCache.clear();
              PaintingBinding.instance.imageCache.clearLiveImages();
              if (context.mounted) {
                ScaffoldMessenger.of(
                  context,
                ).showSnackBar(const SnackBar(content: Text('图片缓存已清理')));
              }
            },
            child: const Text('立即清理'),
          ),
        ],
      ),
    );
  }
}
