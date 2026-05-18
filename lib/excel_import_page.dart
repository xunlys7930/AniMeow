import 'dart:io';
import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import 'package:spreadsheet_decoder/spreadsheet_decoder.dart';
import 'db/database_helper.dart';
import 'utils/logger.dart';

// --- Excel导入冲突处理策略枚举 ---
enum ConflictAction { ask, replace, keep }

/// Excel导入页面，支持从Excel文件导入动漫数据
class ExcelImportPage extends StatefulWidget {
  const ExcelImportPage({super.key});

  @override
  State<ExcelImportPage> createState() => _ExcelImportPageState();
}

class _ExcelImportPageState extends State<ExcelImportPage> {
  // 1. App需要的字段定义和说明
  final Map<String, String> _appFields = {
    'title': '番剧标题 (必选)',
    'status': '状态 (在看/看完/弃坑)',
    'watched': '已看集数',
    'total': '总集数',
    'review': '评价/备注',
    'tags': '标签 (逗号分隔)',
    'studio': '制作公司',
  };

  // 2. 状态变量
  String? _fileName;
  List<List<dynamic>> _rows = [];
  List<String> _excelHeaders = [];

  // Excel列映射关系
  final Map<String, int> _columnMapping = {};
  bool _isImporting = false;

  // --- 步骤 1: 选择并读取 Excel 文件 ---
  Future<void> _pickExcelFile() async {
    try {
      FilePickerResult? result = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: ['xlsx', 'xls'],
      );

      if (result != null) {
        // 读取Excel文件内容
        final fileBytes = File(result.files.single.path!).readAsBytesSync();
        var decoder = SpreadsheetDecoder.decodeBytes(fileBytes, update: true);

        if (decoder.tables.isNotEmpty) {
          var tableName = decoder.tables.keys.first;
          var table = decoder.tables[tableName];

          if (table != null && table.rows.isNotEmpty) {
            setState(() {
              _fileName = result.files.single.name;
              _rows = table.rows;
              // 提取表头行
              _excelHeaders = _rows.first
                  .map((e) => e?.toString() ?? "未命名列")
                  .toList();
              // 自动匹配列
              _autoMatchColumns();
            });
          }
        }
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text("读取文件失败: $e")));
      }
    }
  }

  /// 自动匹配Excel列与App字段
  void _autoMatchColumns() {
    _columnMapping.clear();

    for (var fieldKey in _appFields.keys) {
      for (int i = 0; i < _excelHeaders.length; i++) {
        if (_excelHeaders[i].contains(_getKeywordForField(fieldKey))) {
          _columnMapping[fieldKey] = i;
          break;
        }
      }
    }
  }

  /// 根据字段获取匹配关键字
  String _getKeywordForField(String key) {
    switch (key) {
      case 'title':
        return '名';
      case 'status':
        return '状';
      case 'watched':
        return '已';
      case 'total':
        return '总';
      case 'tags':
        return '签';
      default:
        return 'X';
    }
  }

  // --- 步骤 2: 执行导入操作 ---
  Future<void> _doImport() async {
    // 检查必填字段
    if (!_columnMapping.containsKey('title')) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('错误：必须指定"番剧标题"对应的列')));
      return;
    }

    setState(() => _isImporting = true);

    // 导入统计
    int successCount = 0;
    int skipCount = 0;
    int replaceCount = 0;
    int errorCount = 0;

    // 冲突处理策略（默认每次都询问）
    ConflictAction currentPolicy = ConflictAction.ask;

    // 从第 2 行开始遍历（跳过表头）
    for (int i = 1; i < _rows.length; i++) {
      if (!mounted) break;

      final row = _rows[i];
      if (row.isEmpty) continue;

      try {
        // --- 1. 数据解析 ---
        int titleIdx = _columnMapping['title']!;
        if (titleIdx >= row.length) continue;

        String title = row[titleIdx]?.toString() ?? "";
        if (title.trim().isEmpty) continue;

        // 提取其他字段值
        String statusRaw = _getValue(row, 'status');
        String status = _normalizeStatus(statusRaw);
        int watched = _parseInt(_getValue(row, 'watched'));
        int total = _parseInt(_getValue(row, 'total'));
        String review = _getValue(row, 'review');
        String studio = _getValue(row, 'studio');
        String tagsRaw = _getValue(row, 'tags');

        // 构建动漫数据
        Map<String, dynamic> animeData = {
          'title': title,
          'status': status,
          'watched_episodes': watched,
          'total_episodes': total,
          'review': review,
          'studio': studio,
          'rating': 0,
        };

        // --- 2. 查重逻辑 ---
        final existing = await DatabaseHelper().getAnimeByTitle(title);

        if (existing != null) {
          // 发现重复，根据当前策略决定如何处理
          ConflictAction action = currentPolicy;

          // 如果需要询问用户，则显示弹窗
          if (action == ConflictAction.ask) {
            final result = await showDialog<Map<String, dynamic>>(
              context: context,
              barrierDismissible: false,
              builder: (ctx) => _ConflictDialog(title: title),
            );

            if (result == null) {
              action = ConflictAction.keep;
            } else {
              action = result['action'];
              bool applyToAll = result['applyToAll'];

              // 如果用户选择"应用全部"，则更新策略
              if (applyToAll) {
                currentPolicy = action;
              }
            }
          }

          // 执行处理决定
          if (action == ConflictAction.keep) {
            skipCount++;
            continue; // 跳过当前行
          } else if (action == ConflictAction.replace) {
            // 执行覆盖操作
            animeData['id'] = existing['id'];
            animeData['created_at'] = existing['created_at'];

            await DatabaseHelper().updateAnime(animeData);

            // 处理标签
            if (tagsRaw.isNotEmpty) {
              await _processTags(existing['id'], tagsRaw);
            }
            replaceCount++;
          }
        } else {
          // 无重复记录，正常插入
          animeData['created_at'] = DateTime.now().toString();
          int newAnimeId = await DatabaseHelper().insertAnime(animeData);

          // 处理标签
          if (tagsRaw.isNotEmpty) {
            await _processTags(newAnimeId, tagsRaw);
          }
          successCount++;
        }
      } catch (e) {
        logger.e("导入错误 (第 ${i + 1} 行): $e");
        errorCount++;
      }
    }

    setState(() => _isImporting = false);

    // 显示导入结果
    if (mounted) {
      showDialog(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('导入完成'),
          content: Text(
            '新增: $successCount\n覆盖: $replaceCount\n跳过(保留原样): $skipCount\n错误: $errorCount',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('确定'),
            ),
          ],
        ),
      ).then((_) => Navigator.pop(context, true));
    }
  }

  /// 解析字符串为整数
  int _parseInt(String val) {
    if (val.isEmpty) return 0;
    double? d = double.tryParse(val);
    if (d != null) return d.toInt();
    return 0;
  }

  /// 从Excel行中获取指定字段的值
  String _getValue(List<dynamic> row, String fieldKey) {
    if (!_columnMapping.containsKey(fieldKey)) return "";
    int idx = _columnMapping[fieldKey]!;
    if (idx >= row.length) return "";
    return row[idx]?.toString() ?? "";
  }

  /// 标准化状态值
  String _normalizeStatus(String input) {
    if (input.contains('看完了') ||
        input.contains('完成') ||
        input.contains('Finished')) {
      return '看完';
    }
    if (input.contains('在看') ||
        input.contains('追') ||
        input.contains('Watching')) {
      return '在看';
    }
    if (input.contains('弃') || input.contains('Drop')) {
      return '弃坑';
    }
    if (input.contains('想') || input.contains('未') || input.contains('Plan')) {
      return '未看';
    }
    return '未看';
  }

  /// 处理标签（创建新标签或关联现有标签）
  Future<void> _processTags(int animeId, String rawTags) async {
    // 清理标签字符串
    List<String> tags = rawTags
        .replaceAll('，', ',')
        .replaceAll(' ', ',')
        .split(',');

    for (String tagName in tags) {
      String cleanName = tagName.trim();
      if (cleanName.isEmpty) continue;

      int tagId;
      final db = await DatabaseHelper().database;

      // 检查标签是否已存在
      final List<Map<String, dynamic>> existing = await db.query(
        'tags',
        where: 'name = ?',
        whereArgs: [cleanName],
      );

      if (existing.isNotEmpty) {
        tagId = existing.first['id'] as int;
      } else {
        // 创建新标签
        tagId = await DatabaseHelper().insertTag(cleanName);

        // 处理可能的重复标签
        if (tagId == -1) {
          final retry = await db.query(
            'tags',
            where: 'name = ?',
            whereArgs: [cleanName],
          );
          if (retry.isNotEmpty) {
            tagId = retry.first['id'] as int;
          } else {
            continue;
          }
        }
      }

      // 关联标签到动漫
      await DatabaseHelper().addTagToAnime(animeId, tagId);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('导入 Excel 数据')),
      body: Column(
        children: [
          // 步骤 1: 文件选择区域
          Container(
            padding: const EdgeInsets.all(16),
            color: Colors.grey[100],
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        "步骤 1: 选择文件",
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                      Text(
                        _fileName ?? "请选择 .xlsx 或 .xls 文件",
                        style: TextStyle(color: Colors.grey[600]),
                      ),
                    ],
                  ),
                ),
                ElevatedButton.icon(
                  onPressed: _pickExcelFile,
                  icon: const Icon(Icons.folder_open),
                  label: const Text("浏览"),
                ),
              ],
            ),
          ),

          // 如果有数据，显示步骤 2: 列映射
          if (_excelHeaders.isNotEmpty) ...[
            const Padding(
              padding: EdgeInsets.fromLTRB(16, 16, 16, 8),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text(
                  "步骤 2: 关联对应列",
                  style: TextStyle(fontWeight: FontWeight.bold),
                ),
              ),
            ),
            const Divider(height: 1),
            Expanded(
              child: ListView(
                children: _appFields.entries.map((entry) {
                  return ListTile(
                    title: Text(entry.value),
                    subtitle: const Text(
                      "Excel 对应列:",
                      style: TextStyle(fontSize: 12, color: Colors.grey),
                    ),
                    trailing: DropdownButton<int>(
                      hint: const Text("请选择"),
                      value: _columnMapping[entry.key],
                      items: List.generate(_excelHeaders.length, (index) {
                        return DropdownMenuItem(
                          value: index,
                          child: SizedBox(
                            width: 120,
                            child: Text(
                              _excelHeaders[index],
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                        );
                      }),
                      onChanged: (val) {
                        setState(() {
                          if (val != null) _columnMapping[entry.key] = val;
                        });
                      },
                    ),
                  );
                }).toList(),
              ),
            ),
            // 导入按钮
            Container(
              padding: const EdgeInsets.all(16),
              width: double.infinity,
              child: FilledButton(
                onPressed: _isImporting ? null : _doImport,
                child: _isImporting
                    ? const Text("正在导入...")
                    : const Text("开始导入"),
              ),
            ),
          ] else
            // 如果没有数据，显示提示
            const Expanded(child: Center(child: Text("请先选择 Excel 文件"))),
        ],
      ),
    );
  }
}

// --- 冲突处理弹窗组件 ---
class _ConflictDialog extends StatefulWidget {
  final String title;
  const _ConflictDialog({required this.title});

  @override
  State<_ConflictDialog> createState() => _ConflictDialogState();
}

class _ConflictDialogState extends State<_ConflictDialog> {
  bool _applyToAll = false;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text("发现同名番剧"),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '番剧 "${widget.title}" 已存在。',
            style: const TextStyle(fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 10),
          const Text("您希望如何处理此条数据？"),
          const SizedBox(height: 20),
          // "应用到全部"复选框
          Row(
            children: [
              Checkbox(
                value: _applyToAll,
                onChanged: (v) => setState(() => _applyToAll = v!),
              ),
              const Flexible(
                child: Text("后续冲突全部应用此操作", style: TextStyle(fontSize: 12)),
              ),
            ],
          ),
        ],
      ),
      actions: [
        // 保留旧数据按钮
        TextButton(
          onPressed: () {
            Navigator.pop(context, {
              'action': ConflictAction.keep,
              'applyToAll': _applyToAll,
            });
          },
          child: const Text("保留旧数据(跳过)"),
        ),
        // 覆盖旧数据按钮
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: Colors.orange),
          onPressed: () {
            Navigator.pop(context, {
              'action': ConflictAction.replace,
              'applyToAll': _applyToAll,
            });
          },
          child: const Text("覆盖旧数据"),
        ),
      ],
    );
  }
}
