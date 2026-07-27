import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../settings_manager.dart';
import '../utils/error_logger.dart';
import '../utils/operation_log_service.dart';

/// 展示并复制本地诊断日志（操作轨迹 + 未处理异常）。
class DiagnosticLogPage extends StatefulWidget {
  const DiagnosticLogPage({super.key});

  @override
  State<DiagnosticLogPage> createState() => _DiagnosticLogPageState();
}

class _DiagnosticLogPageState extends State<DiagnosticLogPage> {
  List<Map<String, dynamic>> _persistedLogs = const [];
  bool _isLoading = true;
  String? _loadError;

  @override
  void initState() {
    super.initState();
    _refreshLogs();
  }

  Future<void> _refreshLogs() async {
    setState(() {
      _isLoading = true;
      _loadError = null;
    });
    try {
      final logs = await OperationLogService.instance.loadLogs();
      if (!mounted) return;
      setState(() {
        _persistedLogs = logs;
        _isLoading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _persistedLogs = const [];
        _isLoading = false;
        _loadError = error.toString();
      });
    }
  }

  List<Map<String, dynamic>> get _logs {
    final result = <Map<String, dynamic>>[];
    final persistedMessages = <String>{};
    for (final row in _persistedLogs) {
      final message = row['message']?.toString() ?? '';
      persistedMessages.add(message);
      result.add(<String, dynamic>{
        'time': row['timestamp'],
        'message': message,
        'detail': row['stack_trace'],
        'isError': message.startsWith('[错误]'),
      });
    }
    for (final row in ErrorLogger.instance.logs) {
      final message = '[错误] ${row['error']}';
      if (persistedMessages.contains(message)) continue;
      result.add(<String, dynamic>{
        'time': row['time'],
        'message': message,
        'detail': row['stackTrace'],
        'isError': true,
      });
    }
    result.sort((a, b) => _timeOf(b['time']).compareTo(_timeOf(a['time'])));
    return result;
  }

  DateTime _timeOf(dynamic value) {
    return DateTime.tryParse(value?.toString() ?? '') ??
        DateTime.fromMillisecondsSinceEpoch(0);
  }

  Future<void> _copyAllLogs() async {
    try {
      final text = await OperationLogService.instance.exportLogs(
        memoryErrors: ErrorLogger.instance.logs,
      );
      await Clipboard.setData(ClipboardData(text: text));
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('诊断日志已复制到剪贴板')));
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('复制日志失败：$error')));
    }
  }

  Future<void> _clearLogs() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('清空诊断日志'),
        content: const Text('确定清空本机保存的操作日志和错误日志吗？此操作不可恢复。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(dialogContext, true),
            child: const Text('清空'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    try {
      await OperationLogService.instance.clearLogs();
      ErrorLogger.instance.clearLogs();
      await _refreshLogs();
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('诊断日志已清空')));
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('清空日志失败：$error')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final logs = _logs;
    return Scaffold(
      appBar: AppBar(
        title: const Text('诊断日志'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh_rounded),
            tooltip: '刷新',
            onPressed: _refreshLogs,
          ),
          IconButton(
            icon: const Icon(Icons.copy_all_rounded),
            tooltip: '复制全部日志',
            onPressed: _copyAllLogs,
          ),
          if (logs.isNotEmpty)
            IconButton(
              icon: const Icon(Icons.delete_outline_rounded),
              tooltip: '清空日志',
              onPressed: _clearLogs,
            ),
        ],
      ),
      body: ValueListenableBuilder<bool>(
        valueListenable: SettingsManager().operationLogEnabledNotifier,
        builder: (context, enabled, _) {
          return Column(
            children: [
              _buildStatusBanner(enabled),
              Expanded(
                child: _isLoading
                    ? const Center(child: CircularProgressIndicator())
                    : _buildLogBody(logs),
              ),
            ],
          );
        },
      ),
    );
  }

  Widget _buildStatusBanner(bool enabled) {
    final colorScheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(16, 12, 16, 4),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color:
            (enabled
                    ? colorScheme.primaryContainer
                    : colorScheme.surfaceContainerHighest)
                .withValues(alpha: 0.72),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(
            enabled ? Icons.bug_report_outlined : Icons.lock_outline_rounded,
            size: 20,
            color: enabled
                ? colorScheme.onPrimaryContainer
                : colorScheme.onSurfaceVariant,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              enabled
                  ? '操作日志已开启：会记录最近的页面和关键操作，仅保存在本机。'
                  : '操作日志当前关闭。遇到问题时可在“测试功能”中临时开启，复现后复制日志。',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildLogBody(List<Map<String, dynamic>> logs) {
    if (_loadError != null && logs.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text('日志读取失败：$_loadError'),
        ),
      );
    }
    if (logs.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.check_circle_outline_rounded,
              size: 72,
              color: Theme.of(
                context,
              ).colorScheme.primary.withValues(alpha: 0.6),
            ),
            const SizedBox(height: 12),
            const Text('暂无诊断日志'),
            const SizedBox(height: 6),
            Text(
              '开启操作日志后，复现问题并返回这里复制',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: _refreshLogs,
      child: ListView.separated(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
        itemCount: logs.length,
        separatorBuilder: (_, _) => const SizedBox(height: 10),
        itemBuilder: (context, index) => _buildLogCard(logs[index]),
      ),
    );
  }

  Widget _buildLogCard(Map<String, dynamic> log) {
    final isError = log['isError'] == true;
    final colorScheme = Theme.of(context).colorScheme;
    final accent = isError ? colorScheme.error : colorScheme.primary;
    final detail = log['detail']?.toString() ?? '';
    return Card(
      elevation: 0,
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 12, 14, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  isError
                      ? Icons.error_outline_rounded
                      : Icons.touch_app_outlined,
                  color: accent,
                  size: 20,
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    _timeOf(log['time']).toLocal().toString(),
                    style: Theme.of(context).textTheme.labelMedium,
                  ),
                ),
                Chip(
                  label: Text(isError ? '错误' : '操作'),
                  visualDensity: VisualDensity.compact,
                  labelStyle: TextStyle(color: accent, fontSize: 11),
                  side: BorderSide.none,
                  backgroundColor: accent.withValues(alpha: 0.10),
                ),
              ],
            ),
            const SizedBox(height: 6),
            SelectableText(
              log['message']?.toString() ?? '',
              style: TextStyle(
                color: isError ? colorScheme.error : colorScheme.onSurface,
                fontSize: 13,
              ),
            ),
            if (detail.isNotEmpty)
              ExpansionTile(
                tilePadding: EdgeInsets.zero,
                childrenPadding: const EdgeInsets.only(bottom: 6),
                title: Text(
                  isError ? '查看堆栈详情' : '查看操作上下文',
                  style: const TextStyle(fontSize: 12),
                ),
                children: [
                  Align(
                    alignment: Alignment.centerLeft,
                    child: SelectableText(
                      detail,
                      style: const TextStyle(
                        fontSize: 11,
                        fontFamily: 'monospace',
                      ),
                    ),
                  ),
                ],
              ),
          ],
        ),
      ),
    );
  }
}
