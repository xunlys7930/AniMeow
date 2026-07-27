import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import 'dart:io';

void main() {
  setUpAll(() {
    // Initialize FFI for desktop/testing
    if (Platform.isWindows || Platform.isLinux || Platform.isMacOS) {
      sqfliteFfiInit();
      databaseFactory = databaseFactoryFfi;
    }
  });

  group('DatabaseHelper basic operations', () {
    late Database db;

    setUp(() async {
      // Use an in-memory database for testing
      db = await databaseFactory.openDatabase(
        inMemoryDatabasePath,
        options: OpenDatabaseOptions(
          version: 10,
          onCreate: (Database db, int version) async {
            // Recreate tables locally for tests
            await db.execute('''
            CREATE TABLE IF NOT EXISTS animes(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              title TEXT, 
              status TEXT, 
              subject_type TEXT DEFAULT 'anime'
            )
          ''');

            await db.execute('''
            CREATE TABLE IF NOT EXISTS tags(
              id INTEGER PRIMARY KEY AUTOINCREMENT, 
              name TEXT UNIQUE, 
              color INTEGER
            )
          ''');

            await db.execute('''
            CREATE TABLE IF NOT EXISTS anime_tags(
              anime_id INTEGER, 
              tag_id INTEGER,
              PRIMARY KEY (anime_id, tag_id)
            )
          ''');
          },
        ),
      );
    });

    tearDown(() async {
      await db.close();
    });

    test('Insert and query anime', () async {
      int id = await db.insert('animes', {
        'title': 'Test Anime',
        'status': '在看',
        'subject_type': 'anime',
      });

      expect(id, greaterThan(0));

      final results = await db.query('animes');
      expect(results.length, 1);
      expect(results.first['title'], 'Test Anime');
      expect(results.first['status'], '在看');
    });

    test('Insert and query tags', () async {
      int tagId = await db.insert('tags', {
        'name': 'Comedy',
        'color': 0xFFFFFF,
      });

      expect(tagId, greaterThan(0));

      final results = await db.query('tags');
      expect(results.length, 1);
      expect(results.first['name'], 'Comedy');
    });

    test('Anime and tags relation', () async {
      int animeId = await db.insert('animes', {'title': 'Rel Anime'});
      int tagId = await db.insert('tags', {'name': 'Action', 'color': 0});

      await db.insert('anime_tags', {'anime_id': animeId, 'tag_id': tagId});

      final results = await db.rawQuery(
        '''
        SELECT t.name 
        FROM tags t 
        INNER JOIN anime_tags at ON t.id = at.tag_id 
        WHERE at.anime_id = ?
      ''',
        [animeId],
      );

      expect(results.length, 1);
      expect(results.first['name'], 'Action');
    });
  });
}
