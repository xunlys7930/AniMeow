import 'dart:async';
import 'dart:convert';

import '../db/dao/log_dao.dart';

/// 可选的本地诊断日志服务。
///
/// 日志默认关闭。开启后，操作先进入内存队列，再按批次异步写入 SQLite，
/// 避免在用户点击操作的同步路径上等待磁盘 I/O。日志只保留最近一段时间
/// 和有限条数，导出前也会再次清理队列。
class OperationLogService {
  OperationLogService._();

  static final OperationLogService instance = OperationLogService._();

  static const int maxPersistedEntries = 500;
  static const int maxPendingEntries = 120;
  static const int flushBatchSize = 12;
  static const Duration flushDelay = Duration(milliseconds: 800);
  static const Duration pruneInterval = Duration(minutes: 10);

  final List<_PendingLog> _pending = <_PendingLog>[];
  final LogDao _logDao = LogDao();
  Timer? _flushTimer;
  bool _enabled = false;
  bool _isFlushing = false;
  bool _flushRequested = false;
  Completer<void>? _flushCompleter;
  DateTime? _lastPrunedAt;

  bool get enabled => _enabled;

  /// 启动时同步设置状态，不触发数据库访问。
  void configure(bool enabled) {
    _enabled = enabled;
    if (!enabled) {
      _flushTimer?.cancel();
      _flushTimer = null;
    } else if (_pending.isNotEmpty) {
      _scheduleFlush();
    }
  }

  /// 更新开关。关闭前会尽力把已排队日志写入数据库，方便用户随后复制。
  Future<void> setEnabled(bool enabled) async {
    if (_enabled == enabled) return;

    if (!enabled) {
      await flush();
      _enabled = false;
      _flushTimer?.cancel();
      _flushTimer = null;
      return;
    }

    _enabled = true;
    if (_pending.isNotEmpty) {
      unawaited(flush());
    }
  }

  /// 记录一条用户操作。调用方应避免传入密码、令牌等敏感信息。
  void record(
    String action, {
    String? screen,
    Map<String, dynamic> details = const <String, dynamic>{},
  }) {
    if (!_enabled) return;

    final safeAction = _truncate(action.trim(), 240);
    if (safeAction.isEmpty) return;

    final context = screen == null || screen.trim().isEmpty
        ? ''
        : '[${_truncate(screen.trim(), 80)}] ';
    final detail = details.isEmpty ? null : _encodeDetails(details);
    _enqueue(
      _PendingLog(
        message: '[操作] $context$safeAction',
        detail: detail,
        timestamp: DateTime.now().toIso8601String(),
      ),
    );
  }

  /// 记录一个未处理异常，并保留堆栈用于开发者定位。
  void recordError(dynamic error, dynamic stackTrace, {String? screen}) {
    if (!_enabled) return;

    final context = screen == null || screen.trim().isEmpty
        ? ''
        : '[${_truncate(screen.trim(), 80)}] ';
    _enqueue(
      _PendingLog(
        message: '[错误] $context${_truncate(error.toString(), 1200)}',
        detail: _truncate(stackTrace?.toString() ?? '无堆栈信息', 8000),
        timestamp: DateTime.now().toIso8601String(),
      ),
    );
    // 错误通常需要尽快保留下来，避免进程随后退出时只剩内存队列。
    // flush() 仍在异步路径执行，不阻塞错误处理回调。
    unawaited(flush());
  }

  void _enqueue(_PendingLog entry) {
    _pending.add(entry);
    if (_pending.length > maxPendingEntries) {
      _pending.removeRange(0, _pending.length - maxPendingEntries);
    }

    if (_pending.length >= flushBatchSize) {
      unawaited(flush());
    } else {
      _scheduleFlush();
    }
  }

  void _scheduleFlush() {
    if (!_enabled || _flushTimer != null || _pending.isEmpty) return;
    _flushTimer = Timer(flushDelay, () {
      _flushTimer = null;
      unawaited(flush());
    });
  }

  /// 将待写入日志批量落盘。该方法不会向 UI 抛出异常。
  Future<void> flush() async {
    _flushTimer?.cancel();
    _flushTimer = null;

    if (_isFlushing) {
      _flushRequested = true;
      return _flushCompleter?.future ?? Future<void>.value();
    }
    if (_pending.isEmpty) return;

    _isFlushing = true;
    final flushCompleter = Completer<void>();
    _flushCompleter = flushCompleter;
    final batch = List<_PendingLog>.from(_pending);
    _pending.removeRange(0, batch.length);
    var writeFailed = false;

    try {
      await _logDao.insertLogs(
        batch
            .map(
              (entry) => <String, String?>{
                'message': entry.message,
                'stack_trace': entry.detail,
                'timestamp': entry.timestamp,
              },
            )
            .toList(),
      );

      // 维护操作失败时不重试整批插入，避免已经成功落盘的日志被重复写入。
      try {
        final now = DateTime.now();
        if (_lastPrunedAt == null ||
            now.difference(_lastPrunedAt!) >= pruneInterval) {
          await _logDao.pruneToLimit(maxPersistedEntries);
          await _logDao.clearOldLogs();
          _lastPrunedAt = now;
        }
      } catch (_) {
        // 下一次批量写入时会再次尝试清理；不影响本批日志的保存。
      }
    } catch (_) {
      writeFailed = true;
      // 数据库暂时不可用时保留有限的内存队列，下一次开启/记录时重试。
      _pending.insertAll(0, batch);
      if (_pending.length > maxPendingEntries) {
        _pending.removeRange(0, _pending.length - maxPendingEntries);
      }
    } finally {
      _isFlushing = false;
      _flushCompleter = null;
      if (!flushCompleter.isCompleted) {
        flushCompleter.complete();
      }
      final shouldFlushAgain = _flushRequested;
      _flushRequested = false;
      if (_pending.isNotEmpty && !writeFailed) {
        if (shouldFlushAgain || _pending.length >= flushBatchSize) {
          unawaited(flush());
        } else if (_enabled) {
          _scheduleFlush();
        }
      }
    }
  }

  Future<List<Map<String, dynamic>>> loadLogs() async {
    await flush();
    List<Map<String, dynamic>> persisted;
    try {
      persisted = await _logDao.getLogs(limit: maxPersistedEntries);
    } catch (_) {
      persisted = const [];
    }

    final combined = <Map<String, dynamic>>[
      ..._pending.map(
        (entry) => <String, dynamic>{
          'message': entry.message,
          'stack_trace': entry.detail,
          'timestamp': entry.timestamp,
        },
      ),
      ...persisted,
    ];
    combined.sort(
      (a, b) => (b['timestamp']?.toString() ?? '').compareTo(
        a['timestamp']?.toString() ?? '',
      ),
    );
    return combined.take(maxPersistedEntries).toList();
  }

  /// 导出持久化日志，并可附加当前进程内尚未持久化的错误日志。
  Future<String> exportLogs({
    List<Map<String, dynamic>> memoryErrors = const <Map<String, dynamic>>[],
  }) async {
    final rows = await loadLogs();
    final buffer = StringBuffer()
      ..writeln('# AniMeow 诊断日志')
      ..writeln('# 生成时间: ${DateTime.now().toIso8601String()}')
      ..writeln('# 操作日志: ${_enabled ? '开启' : '关闭'}')
      ..writeln();

    var count = 0;
    for (final row in rows) {
      _writeRow(buffer, row['timestamp'], row['message'], row['stack_trace']);
      count++;
    }
    final persistedMessages = rows
        .map((row) => row['message']?.toString())
        .whereType<String>()
        .toSet();
    for (final row in memoryErrors) {
      final message = '[错误] ${row['error']}';
      if (persistedMessages.contains(message)) continue;
      _writeRow(buffer, row['time'], message, row['stackTrace']);
      count++;
    }

    if (count == 0) {
      buffer.writeln('暂无诊断日志。');
    }
    return buffer.toString();
  }

  void _writeRow(
    StringBuffer buffer,
    dynamic timestamp,
    dynamic message,
    dynamic detail,
  ) {
    final safeMessage = _truncate(message?.toString() ?? '', 1200);
    final safeDetail = detail == null ? '' : _truncate(detail.toString(), 8000);
    buffer
      ..writeln('[$timestamp] $safeMessage')
      ..writeln(safeDetail)
      ..writeln('---');
  }

  Future<void> clearLogs() async {
    await flush();
    _flushTimer?.cancel();
    _flushTimer = null;
    _pending.clear();
    await _logDao.clearLogs();
  }

  String? _encodeDetails(Map<String, dynamic> details) {
    try {
      final sanitized = _sanitize(details, depth: 0);
      return _truncate(jsonEncode(sanitized), 4000);
    } catch (_) {
      return null;
    }
  }

  dynamic _sanitize(dynamic value, {required int depth}) {
    if (depth > 2) return '<nested>';
    if (value == null || value is num || value is bool) return value;
    if (value is String) return _truncate(value, 500);
    if (value is Iterable) {
      return value
          .take(12)
          .map((item) => _sanitize(item, depth: depth + 1))
          .toList();
    }
    if (value is Map) {
      final result = <String, dynamic>{};
      var count = 0;
      for (final entry in value.entries) {
        if (count++ >= 20) break;
        final key = _truncate(entry.key.toString(), 80);
        result[key] = _isSensitiveKey(key)
            ? '<redacted>'
            : _sanitize(entry.value, depth: depth + 1);
      }
      return result;
    }
    return _truncate(value.toString(), 500);
  }

  String _truncate(String value, int maxLength) {
    final redacted = _redact(value);
    if (redacted.length <= maxLength) return redacted;
    return '${redacted.substring(0, maxLength)}…';
  }

  String _redact(String value) {
    // 先处理带空格的 Bearer 凭据，避免键值遮盖只吃掉 "Bearer" 而留下后面的令牌。
    var redacted = value.replaceAllMapped(
      RegExp(r'Bearer\s+[A-Za-z0-9._~+/=-]+', caseSensitive: false),
      (_) => 'Bearer <redacted>',
    );
    redacted = redacted.replaceAllMapped(
      RegExp(
        r'''((?:password|passwd|token|access[_-]?token|refresh[_-]?token|api[_-]?key|client[_-]?secret|private[_-]?key|secret|authorization)\s*["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,;}&]+)''',
        caseSensitive: false,
      ),
      (match) {
        final prefix = match.group(1) ?? '';
        final rawValue = match.group(0)?.substring(prefix.length) ?? '';
        if (rawValue.length >= 2 &&
            ((rawValue.startsWith('"') && rawValue.endsWith('"')) ||
                (rawValue.startsWith("'") && rawValue.endsWith("'")))) {
          return '$prefix${rawValue[0]}<redacted>${rawValue[rawValue.length - 1]}';
        }
        return '$prefix<redacted>';
      },
    );
    return redacted;
  }

  bool _isSensitiveKey(String key) {
    return RegExp(
      r'^(password|passwd|token|access[_-]?token|refresh[_-]?token|api[_-]?key|client[_-]?secret|private[_-]?key|secret|authorization)$',
      caseSensitive: false,
    ).hasMatch(key.trim());
  }
}

class _PendingLog {
  final String message;
  final String? detail;
  final String timestamp;

  const _PendingLog({
    required this.message,
    required this.detail,
    required this.timestamp,
  });
}
