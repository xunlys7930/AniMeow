import 'package:sqflite/sqflite.dart';

import '../database_helper.dart';

class TagDao {
  Future<Database> get _db => DatabaseHelper().database;

  Future<int> insertTag(String name) async {
    final db = await _db;
    final cleanName = name.trim();

    final existing = await db.query(
      'tags',
      where: 'name = ?',
      whereArgs: [cleanName],
    );
    if (existing.isNotEmpty) return -1;

    return db.insert('tags', {'name': cleanName, 'color': 0xFF2196F3});
  }

  Future<int> updateTag(int id, String newName) async {
    final db = await _db;
    return db.update(
      'tags',
      {'name': newName},
      where: 'id = ?',
      whereArgs: [id],
    );
  }

  Future<void> deleteTag(int id) async {
    final db = await _db;
    await db.transaction((txn) async {
      await txn.delete('anime_tags', where: 'tag_id = ?', whereArgs: [id]);
      await txn.delete('tags', where: 'id = ?', whereArgs: [id]);
    });
  }

  Future<List<Map<String, dynamic>>> getAllTags() async {
    final db = await _db;
    return db.query('tags', orderBy: 'name ASC');
  }

  Future<void> addTagToAnime(int animeId, int tagId) async {
    final db = await _db;
    await db.insert('anime_tags', {
      'anime_id': animeId,
      'tag_id': tagId,
    }, conflictAlgorithm: ConflictAlgorithm.ignore);
  }

  Future<void> updateAnimeTags(int animeId, Set<int> tagIds) async {
    final db = await _db;
    await db.transaction((txn) async {
      await txn.delete(
        'anime_tags',
        where: 'anime_id = ?',
        whereArgs: [animeId],
      );

      for (final tagId in tagIds) {
        await txn.insert('anime_tags', {
          'anime_id': animeId,
          'tag_id': tagId,
        }, conflictAlgorithm: ConflictAlgorithm.ignore);
      }
    });
  }

  Future<List<Map<String, dynamic>>> getTagsByAnimeId(int animeId) async {
    final db = await _db;
    return db.rawQuery(
      '''
      SELECT t.id, t.name
      FROM tags t
      INNER JOIN anime_tags at ON t.id = at.tag_id
      WHERE at.anime_id = ?
    ''',
      [animeId],
    );
  }

  Future<void> batchAddTagToAnimes(List<int> animeIds, int tagId) async {
    final db = await _db;
    await db.transaction((txn) async {
      for (final animeId in animeIds) {
        await txn.insert('anime_tags', {
          'anime_id': animeId,
          'tag_id': tagId,
        }, conflictAlgorithm: ConflictAlgorithm.ignore);
      }
    });
  }

  Future<void> batchRemoveTagFromAnimes(List<int> animeIds, int tagId) async {
    final db = await _db;
    await db.transaction((txn) async {
      for (final animeId in animeIds) {
        await txn.delete(
          'anime_tags',
          where: 'anime_id = ? AND tag_id = ?',
          whereArgs: [animeId, tagId],
        );
      }
    });
  }

  Future<List<Map<String, dynamic>>> getTagCounts() async {
    final db = await _db;
    return db.rawQuery('''
      SELECT t.id, t.name, COUNT(at.anime_id) as count
      FROM tags t
      LEFT JOIN anime_tags at ON t.id = at.tag_id
      GROUP BY t.id
      ORDER BY count DESC
    ''');
  }
}
