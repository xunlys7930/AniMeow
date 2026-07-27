import 'package:sqflite/sqflite.dart';

import '../../utils/logger.dart';
import '../database_helper.dart';

class WatchStatusDao {
  Future<Database> get _db => DatabaseHelper().database;

  Future<List<Map<String, dynamic>>> getAllStatuses() async {
    final db = await _db;
    return db.query('watch_statuses', orderBy: 'sort_order ASC');
  }

  Future<int> insertStatus(String name, int color) async {
    final db = await _db;
    try {
      final result = await db.rawQuery(
        'SELECT MAX(sort_order) as max_order FROM watch_statuses',
      );
      final maxOrder = (result.first['max_order'] as int?) ?? -1;

      return db.insert('watch_statuses', {
        'name': name,
        'color': color,
        'sort_order': maxOrder + 1,
      });
    } catch (e) {
      logger.e('Insert status error: $e');
      return -1;
    }
  }

  Future<int> updateStatus(int id, String name, int color) async {
    final db = await _db;
    final oldResult = await db.query(
      'watch_statuses',
      where: 'id = ?',
      whereArgs: [id],
    );
    if (oldResult.isEmpty) return 0;
    final oldName = oldResult.first['name'] as String;

    return db.transaction((txn) async {
      final count = await txn.update(
        'watch_statuses',
        {'name': name, 'color': color},
        where: 'id = ?',
        whereArgs: [id],
      );
      if (oldName != name) {
        await txn.update(
          'animes',
          {'status': name},
          where: 'status = ?',
          whereArgs: [oldName],
        );
      }
      return count;
    });
  }

  Future<int> deleteStatus(int id) async {
    final db = await _db;
    final statusResult = await db.query(
      'watch_statuses',
      where: 'id = ?',
      whereArgs: [id],
    );
    if (statusResult.isEmpty) return 0;
    final statusName = statusResult.first['name'] as String;

    final countResult = await db.rawQuery(
      'SELECT COUNT(*) as count FROM animes WHERE status = ?',
      [statusName],
    );
    final count = (countResult.first['count'] as int?) ?? 0;
    if (count > 0) return -2;

    return db.delete('watch_statuses', where: 'id = ?', whereArgs: [id]);
  }

  Future<void> updateStatusesOrder(List<int> ids) async {
    final db = await _db;
    await db.transaction((txn) async {
      for (int i = 0; i < ids.length; i++) {
        await txn.update(
          'watch_statuses',
          {'sort_order': i},
          where: 'id = ?',
          whereArgs: [ids[i]],
        );
      }
    });
  }
}
