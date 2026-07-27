import 'package:sqflite/sqflite.dart';

import '../database_helper.dart';
import '../../models/fun_rating_tier_config.dart';
import '../../utils/anime_rating.dart';

class AnimeDao {
  static const String _completionStatus = '看完';

  Future<Database> get _db => DatabaseHelper().database;

  Future<int> insertAnime(Map<String, dynamic> row) async {
    final db = await _db;
    return db.insert(
      'animes',
      _normalizeCompletionProgress(_normalizeRating(row)),
    );
  }

  Future<int> updateAnime(Map<String, dynamic> row) async {
    final db = await _db;
    final normalized = await _normalizeCompletionProgressForUpdate(
      db,
      _normalizeRating(row),
    );
    return db.update(
      'animes',
      normalized,
      where: 'id = ?',
      whereArgs: [normalized['id']],
    );
  }

  Future<int> updateFunRatingTier(int animeId, String? tier) async {
    final db = await _db;
    return db.update(
      'animes',
      {'fun_rating_tier': normalizeFunRatingTier(tier)},
      where: 'id = ?',
      whereArgs: [animeId],
    );
  }

  Future<int> clearFunRatingTiers() async {
    final db = await _db;
    return db.update('animes', {
      'fun_rating_tier': null,
    }, where: 'fun_rating_tier IS NOT NULL');
  }

  Future<int> deleteAnime(int id) async {
    final db = await _db;
    return db.delete('animes', where: 'id = ?', whereArgs: [id]);
  }

  Future<List<Map<String, dynamic>>> queryAllAnimes() async {
    final db = await _db;
    return db.query('animes', orderBy: 'id DESC');
  }

  Future<Map<String, dynamic>?> getAnimeById(int id) async {
    final db = await _db;
    final maps = await db.query('animes', where: 'id = ?', whereArgs: [id]);
    if (maps.isNotEmpty) return maps.first;
    return null;
  }

  Future<Map<String, dynamic>?> getAnimeByTitle(String title) async {
    final db = await _db;
    final maps = await db.query(
      'animes',
      where: 'title = ?',
      whereArgs: [title],
      limit: 1,
    );
    if (maps.isNotEmpty) return maps.first;
    return null;
  }

  Future<List<Map<String, dynamic>>> getAnimesWithoutSeries() async {
    final db = await _db;
    return db.query('animes', where: 'series_id IS NULL', orderBy: 'title ASC');
  }

  Future<void> updateAnimeSeries(int animeId, int? seriesId) async {
    final db = await _db;
    await db.update(
      'animes',
      {'series_id': seriesId},
      where: 'id = ?',
      whereArgs: [animeId],
    );
  }

  Future<void> batchUpdateStatus(List<int> animeIds, String newStatus) async {
    final db = await _db;
    await db.transaction((txn) async {
      for (final id in animeIds) {
        if (_isCompletionStatus(newStatus)) {
          await txn.rawUpdate(
            '''
            UPDATE animes
            SET status = ?,
                watched_episodes = CASE
                  WHEN total_episodes > 0 THEN total_episodes
                  ELSE watched_episodes
                END
            WHERE id = ?
            ''',
            [newStatus, id],
          );
        } else {
          await txn.update(
            'animes',
            {'status': newStatus},
            where: 'id = ?',
            whereArgs: [id],
          );
        }
      }
    });
  }

  Map<String, dynamic> _normalizeCompletionProgress(Map<String, dynamic> row) {
    final normalized = Map<String, dynamic>.from(row);
    if (!_isCompletionStatus(normalized['status'])) return normalized;

    final total = _asInt(normalized['total_episodes']) ?? 0;
    if (total > 0) {
      normalized['watched_episodes'] = total;
    }
    return normalized;
  }

  Map<String, dynamic> _normalizeRating(Map<String, dynamic> row) {
    final normalized = Map<String, dynamic>.from(row);
    if (normalized.containsKey('rating_grade')) {
      final grade = normalizeAnimeRatingGrade(normalized['rating_grade']);
      normalized['rating_grade'] = grade;
      if (grade != null) normalized['rating'] = 0;
    } else if (normalized.containsKey('rating')) {
      // Numeric metadata syncs intentionally replace an existing letter grade.
      normalized['rating_grade'] = null;
    }
    return normalized;
  }

  Future<Map<String, dynamic>> _normalizeCompletionProgressForUpdate(
    Database db,
    Map<String, dynamic> row,
  ) async {
    final normalized = Map<String, dynamic>.from(row);

    final hasStatus = normalized.containsKey('status');
    final hasTotal = normalized.containsKey('total_episodes');
    var status = normalized['status'];
    var total = _asInt(normalized['total_episodes']);

    Map<String, dynamic>? current;
    if (!hasStatus || !hasTotal) {
      current = await _queryAnimeCompletionFields(db, normalized['id']);
      if (!hasStatus) {
        status = current?['status'];
      }
      if (!hasTotal) {
        total = _asInt(current?['total_episodes']);
      }
    }

    if (!_isCompletionStatus(status)) return normalized;
    if (total != null && total > 0) {
      normalized['watched_episodes'] = total;
    }
    return normalized;
  }

  Future<Map<String, dynamic>?> _queryAnimeCompletionFields(
    Database db,
    dynamic id,
  ) async {
    if (id == null) return null;
    final rows = await db.query(
      'animes',
      columns: ['status', 'total_episodes'],
      where: 'id = ?',
      whereArgs: [id],
      limit: 1,
    );
    return rows.isEmpty ? null : rows.first;
  }

  bool _isCompletionStatus(dynamic status) =>
      status?.toString().trim() == _completionStatus;

  int? _asInt(dynamic value) {
    if (value == null) return null;
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value.toString());
  }

  Future<void> batchUpdateAnimesSeries(List<int> animeIds, int seriesId) async {
    final db = await _db;
    await db.transaction((txn) async {
      for (final id in animeIds) {
        await txn.update(
          'animes',
          {'series_id': seriesId},
          where: 'id = ?',
          whereArgs: [id],
        );
      }
    });
  }

  Future<List<String>> getAllStudios() async {
    final db = await _db;
    final maps = await db.rawQuery('''
      SELECT DISTINCT studio
      FROM animes
      WHERE studio IS NOT NULL AND studio != ''
      ORDER BY studio ASC
    ''');
    return maps.map((row) => row['studio'] as String).toList();
  }

  Future<List<Map<String, dynamic>>> searchAnimes({
    String? query,
    List<int>? tagIds,
    String? status,
    String? subjectType,
    List<String>? years,
    bool isAndMode = false,
    String sortOption = 'a.id DESC',
  }) async {
    final db = await _db;

    String sql =
        'SELECT a.*, s.name as series_name FROM animes a '
        'LEFT JOIN series s ON a.series_id = s.id ';
    final args = <dynamic>[];

    if (tagIds != null && tagIds.isNotEmpty) {
      sql += 'JOIN anime_tags at ON a.id = at.anime_id ';
    }

    sql += 'WHERE 1=1 ';

    if (subjectType != null && subjectType != 'all') {
      sql += 'AND a.subject_type = ? ';
      args.add(subjectType);
    }

    if (years != null && years.isNotEmpty) {
      final yearPlaceholders = List.filled(
        years.length,
        'a.air_date LIKE ?',
      ).join(' OR ');
      sql += 'AND ($yearPlaceholders) ';
      for (final y in years) {
        args.add('$y%');
      }
    }

    if (tagIds != null && tagIds.isNotEmpty) {
      final placeholders = List.filled(tagIds.length, '?').join(',');
      sql += 'AND at.tag_id IN ($placeholders) ';
      args.addAll(tagIds);
    }

    if (status != null && status != '全部') {
      sql += 'AND a.status = ? ';
      args.add(status);
    }

    if (query != null && query.isNotEmpty) {
      sql += 'AND (a.title LIKE ? OR a.studio LIKE ? OR a.review LIKE ?) ';
      final likeQuery = '%$query%';
      args
        ..add(likeQuery)
        ..add(likeQuery)
        ..add(likeQuery);
    }

    if (tagIds != null && tagIds.isNotEmpty) {
      sql += 'GROUP BY a.id ';
      if (isAndMode) {
        sql += 'HAVING COUNT(DISTINCT at.tag_id) = ? ';
        args.add(tagIds.length);
      }
    } else {
      sql += 'GROUP BY a.id ';
    }

    sql += 'ORDER BY $sortOption';
    return db.rawQuery(sql, args);
  }

  Future<Map<String, int>> getStatusCounts() async {
    final db = await _db;
    final result = await db.rawQuery('''
      SELECT status, COUNT(*) as count
      FROM animes
      GROUP BY status
    ''');

    final counts = {'在看': 0, '看完': 0, '未看': 0, '弃坑': 0};
    for (final row in result) {
      if (row['status'] != null) {
        counts[row['status'] as String] = row['count'] as int;
      }
    }
    return counts;
  }

  Future<Map<String, int>> getSubjectTypeCounts() async {
    final db = await _db;
    final result = await db.rawQuery('''
      SELECT subject_type, COUNT(*) as count
      FROM animes
      GROUP BY subject_type
    ''');

    final counts = {'anime': 0, 'book': 0};
    for (final row in result) {
      final type = row['subject_type'] as String?;
      if (type != null) {
        counts[type] = row['count'] as int;
      }
    }
    return counts;
  }

  Future<List<Map<String, dynamic>>> getAnimesWithReminders() async {
    final db = await _db;
    return db.query(
      'animes',
      where: 'reminder_day IS NOT NULL AND reminder_time IS NOT NULL',
      orderBy: 'reminder_day ASC, reminder_time ASC',
    );
  }
}
