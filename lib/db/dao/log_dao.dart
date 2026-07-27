import 'package:sqflite/sqflite.dart';

import '../diagnostic_log_database.dart';

class LogDao {
  final Future<Database> Function() _databaseProvider;

  LogDao({Future<Database> Function()? databaseProvider})
    : _databaseProvider =
          databaseProvider ?? (() => DiagnosticLogDatabase.instance.database);

  Future<Database> get _db => _databaseProvider();

  Future<int> insertLog(String message, String? stackTrace) async {
    final db = await _db;
    return db.insert('app_logs', {
      'message': message,
      'stack_trace': stackTrace,
      'timestamp': DateTime.now().toIso8601String(),
    });
  }

  /// 批量写入日志，供可选诊断日志服务在后台合并落盘。
  Future<void> insertLogs(List<Map<String, String?>> logs) async {
    if (logs.isEmpty) return;
    final db = await _db;
    await db.transaction((txn) async {
      final batch = txn.batch();
      for (final log in logs) {
        batch.insert('app_logs', {
          'message': log['message'],
          'stack_trace': log['stack_trace'],
          'timestamp': log['timestamp'],
        });
      }
      await batch.commit(noResult: true);
    });
  }

  Future<List<Map<String, dynamic>>> getLogs({int limit = 500}) async {
    final db = await _db;
    return db.query(
      'app_logs',
      orderBy: 'timestamp DESC, id DESC',
      limit: limit,
    );
  }

  Future<int> clearLogs() async {
    final db = await _db;
    return db.delete('app_logs');
  }

  Future<int> pruneToLimit(int limit) async {
    final db = await _db;
    return db.rawDelete(
      '''
      DELETE FROM app_logs
      WHERE id NOT IN (
        SELECT id FROM app_logs
        ORDER BY timestamp DESC, id DESC
        LIMIT ?
      )
      ''',
      [limit],
    );
  }

  Future<int> clearOldLogs() async {
    final db = await _db;
    final cutoff = DateTime.now().subtract(const Duration(days: 14));
    return db.delete(
      'app_logs',
      where: 'timestamp < ?',
      whereArgs: [cutoff.toIso8601String()],
    );
  }
}
