import 'package:sqflite/sqflite.dart';

import '../database_helper.dart';

class LogDao {
  Future<Database> get _db => DatabaseHelper().database;

  Future<int> insertLog(String message, String? stackTrace) async {
    final db = await _db;
    return db.insert('app_logs', {
      'message': message,
      'stack_trace': stackTrace,
      'timestamp': DateTime.now().toIso8601String(),
    });
  }

  Future<List<Map<String, dynamic>>> getLogs() async {
    final db = await _db;
    return db.query('app_logs', orderBy: 'timestamp DESC');
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
