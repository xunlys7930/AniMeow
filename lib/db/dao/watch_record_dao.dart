import 'package:sqflite/sqflite.dart';

import '../database_helper.dart';

class WatchRecordDao {
  Future<Database> get _db => DatabaseHelper().database;

  Future<int> insertWatchRecord({
    required int animeId,
    required int episode,
    String status = 'watched',
    String? date,
  }) async {
    final db = await _db;
    final timestamp = date ?? DateTime.now().toIso8601String();

    if (status == 'watched') {
      final today = timestamp.substring(0, 10);
      final existing = await db.query(
        'watch_records',
        where:
            'anime_id = ? AND episode = ? AND status = ? AND record_date LIKE ?',
        whereArgs: [animeId, episode, 'watched', '$today%'],
      );
      if (existing.isNotEmpty) return -1;
    } else if (status == 'completed') {
      final today = timestamp.substring(0, 10);
      final existing = await db.query(
        'watch_records',
        where: 'anime_id = ? AND status = ? AND record_date LIKE ?',
        whereArgs: [animeId, 'completed', '$today%'],
      );
      if (existing.isNotEmpty) return -1;

      await db.delete(
        'watch_records',
        where: 'anime_id = ? AND status = ? AND record_date LIKE ?',
        whereArgs: [animeId, 'watched', '$today%'],
      );
    }

    return db.insert('watch_records', {
      'anime_id': animeId,
      'episode': episode,
      'status': status,
      'record_date': timestamp,
    });
  }

  Future<List<Map<String, dynamic>>> getAllWatchRecords() async {
    final db = await _db;
    final records = await db.rawQuery('''
      SELECT wr.*, a.title, a.cover_url, a.subject_type
      FROM watch_records wr
      JOIN animes a ON wr.anime_id = a.id
      ORDER BY wr.record_date ASC
    ''');

    final completionCounts = <int, int>{};
    final processedRecords = <Map<String, dynamic>>[];

    for (final record in records) {
      final mutableRecord = Map<String, dynamic>.from(record);
      if (record['status'] == 'completed') {
        final animeId = record['anime_id'] as int;
        completionCounts[animeId] = (completionCounts[animeId] ?? 0) + 1;
        mutableRecord['watch_count'] = completionCounts[animeId];
      }
      processedRecords.add(mutableRecord);
    }

    return processedRecords.reversed.toList();
  }

  Future<int> deleteWatchRecordsByAnimeId(int animeId) async {
    final db = await _db;
    return db.delete(
      'watch_records',
      where: 'anime_id = ?',
      whereArgs: [animeId],
    );
  }

  Future<int> deleteWatchRecord(int id) async {
    final db = await _db;
    return db.delete('watch_records', where: 'id = ?', whereArgs: [id]);
  }
}
