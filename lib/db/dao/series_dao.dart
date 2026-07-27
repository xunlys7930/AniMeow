import 'package:sqflite/sqflite.dart';

import '../database_helper.dart';

class SeriesDao {
  Future<Database> get _db => DatabaseHelper().database;

  Future<int> createSeries(String name, {String? description}) async {
    final db = await _db;
    return db.insert('series', {
      'name': name,
      'description': description,
      'created_at': DateTime.now().toIso8601String(),
    });
  }

  Future<Map<String, dynamic>?> getSeriesById(int id) async {
    final db = await _db;
    final maps = await db.query('series', where: 'id = ?', whereArgs: [id]);
    if (maps.isEmpty) return null;
    return maps.first;
  }

  Future<List<Map<String, dynamic>>> getAllSeries() async {
    final db = await _db;
    return db.rawQuery('''
      SELECT
        s.*,
        COUNT(a.id) as anime_count,
        (SELECT cover_url FROM animes WHERE series_id = s.id ORDER BY id DESC LIMIT 1) as default_cover_url
      FROM series s
      LEFT JOIN animes a ON s.id = a.series_id
      GROUP BY s.id
      ORDER BY s.created_at DESC
    ''');
  }

  Future<int> updateSeries(int id, Map<String, dynamic> row) async {
    final db = await _db;
    return db.update('series', row, where: 'id = ?', whereArgs: [id]);
  }

  Future<void> deleteSeries(int id) async {
    final db = await _db;
    await db.transaction((txn) async {
      await txn.update(
        'animes',
        {'series_id': null},
        where: 'series_id = ?',
        whereArgs: [id],
      );
      await txn.delete('series', where: 'id = ?', whereArgs: [id]);
    });
  }

  Future<List<Map<String, dynamic>>> getAnimesInSeries(int seriesId) async {
    final db = await _db;
    return db.rawQuery(
      'SELECT a.*, s.name as series_name FROM animes a '
      'LEFT JOIN series s ON a.series_id = s.id '
      'WHERE a.series_id = ? ORDER BY a.air_date ASC',
      [seriesId],
    );
  }

  Future<List<Map<String, dynamic>>> searchSeries(String query) async {
    final db = await _db;
    return db.query(
      'series',
      where: 'name LIKE ?',
      whereArgs: ['%$query%'],
      orderBy: 'created_at DESC',
    );
  }
}
