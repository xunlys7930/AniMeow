import 'dart:async';
import 'dart:io';
import 'package:path/path.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import '../utils/logger.dart';
import '../models/character_group_package.dart';

import 'dao/anime_dao.dart';
import 'dao/tag_dao.dart';
import 'dao/series_dao.dart';
import 'dao/watch_status_dao.dart';
import 'dao/watch_record_dao.dart';
import 'dao/log_dao.dart';
import 'dao/character_dao.dart';

/// 数据库帮助类，管理应用数据存储
/// 使用 SQLite 数据库，支持跨平台（包括桌面端）
class DatabaseHelper {
  static const String animesTable = 'animes';
  static const String tagsTable = 'tags';
  static const String animeTagsTable = 'anime_tags';
  static const String seriesTable = 'series';
  static const String watchStatusesTable = 'watch_statuses';
  static const String watchRecordsTable = 'watch_records';
  static const String appLogsTable = 'app_logs';
  static const String animeAnalysisRecordsTable = 'anime_analysis_records';
  static const String charactersTable = 'characters';
  static const String animeCharactersTable = 'anime_characters';
  static const String characterRelationsTable = 'character_relations';
  static const String characterTagsTable = 'character_tags';
  static const String characterTagLinksTable = 'character_tag_links';
  static const String characterGroupsTable = 'character_groups';
  static const String characterGroupCharactersTable =
      'character_group_characters';
  static const String characterGroupWorksTable = 'character_group_works';
  static const int _databaseVersion = 19;

  // 单例模式实例
  static final DatabaseHelper _instance = DatabaseHelper._internal();
  static Database? _database;

  // DAO 成员变量（向下兼容及外部访问）
  late final AnimeDao animeDao = AnimeDao();
  late final TagDao tagDao = TagDao();
  late final SeriesDao seriesDao = SeriesDao();
  late final WatchStatusDao watchStatusDao = WatchStatusDao();
  late final WatchRecordDao watchRecordDao = WatchRecordDao();
  late final LogDao logDao = LogDao();
  late final CharacterDao characterDao = CharacterDao();

  final Map<String, StreamController<void>> _tableControllers = {};

  /// 单例工厂构造函数
  factory DatabaseHelper() {
    return _instance;
  }

  DatabaseHelper._internal();

  Stream<void> watchTable(String table) => _controllerFor(table).stream;

  Stream<void> watchTables(Set<String> tables) async* {
    final merged = StreamController<void>();
    final subscriptions = <StreamSubscription<void>>[];

    for (final table in tables) {
      subscriptions.add(
        _controllerFor(table).stream.listen((_) {
          if (!merged.isClosed) merged.add(null);
        }),
      );
    }

    merged.onCancel = () async {
      for (final subscription in subscriptions) {
        await subscription.cancel();
      }
    };

    yield* merged.stream;
  }

  StreamController<void> _controllerFor(String table) {
    return _tableControllers.putIfAbsent(
      table,
      () => StreamController<void>.broadcast(),
    );
  }

  void notifyTableChanged(String table) {
    final controller = _tableControllers[table];
    if (controller != null && !controller.isClosed) {
      controller.add(null);
    }
  }

  void notifyTablesChanged(Iterable<String> tables) {
    for (final table in tables) {
      notifyTableChanged(table);
    }
  }

  /// 获取数据库实例（懒加载）
  Future<Database> get database async {
    if (_database != null) return _database!;
    _database = await _initDatabase();
    return _database!;
  }

  /// 初始化数据库连接
  Future<Database> _initDatabase() async {
    // 在 Windows/Linux 桌面端使用 ffi 实现
    if (Platform.isWindows || Platform.isLinux) {
      sqfliteFfiInit();
      databaseFactory = databaseFactoryFfi;
    }

    // 获取数据库存储路径
    final path = await getDbPath();

    // 打开数据库，如果不存在则创建
    final db = await openDatabase(
      path,
      version: _databaseVersion,
      onConfigure: (db) async {
        // 开启外键支持，确保级联删除生效
        await db.execute('PRAGMA foreign_keys = ON');
      },
      onCreate: _onCreate,
      onUpgrade: _onUpgrade,
    );

    await _repairMojibakeDefaultStatuses(db);
    await _repairCompletedAnimeProgress(db);

    // 一次性清理历史遗留的孤立数据（针对旧版本未开启外键的情况）
    await _cleanupOrphanedData(db);

    return db;
  }

  /// 清理孤立数据（anime_tags 和 watch_records 中指向不存在番剧的记录）
  Future<void> _cleanupOrphanedData(Database db) async {
    try {
      // 删除关联了不存在番剧的标签
      await db.delete(
        'anime_tags',
        where: 'anime_id NOT IN (SELECT id FROM animes)',
      );
      // 删除关联了不存在番剧的观看记录
      await db.delete(
        'watch_records',
        where: 'anime_id NOT IN (SELECT id FROM animes)',
      );
    } catch (e) {
      logger.e("Cleanup orphaned data error: $e");
    }
  }

  /// 创建数据库表结构
  Future<void> _onCreate(Database db, int version) async {
    // 创建动漫记录表
    await db.execute('''
      CREATE TABLE IF NOT EXISTS animes(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        title TEXT, 
        cover_url TEXT, 
        status TEXT, 
        rating INTEGER,
        rating_grade TEXT,
        fun_rating_tier TEXT,
        review TEXT,
        series_id INTEGER, 
        created_at TEXT, 
        air_date TEXT, 
        studio TEXT,
        watch_start_date TEXT, 
        watch_finish_date TEXT,
        watched_episodes INTEGER DEFAULT 0, 
        total_episodes INTEGER DEFAULT 0,
        tv_episodes INTEGER DEFAULT 0,
        sp_episodes INTEGER DEFAULT 0,
        subject_type TEXT DEFAULT 'anime',
        reminder_day INTEGER, 
        reminder_time TEXT
      )
    ''');

    // 创建标签表
    await db.execute('''
      CREATE TABLE IF NOT EXISTS tags(
        id INTEGER PRIMARY KEY AUTOINCREMENT, 
        name TEXT UNIQUE, 
        color INTEGER
      )
    ''');

    // 创建动漫-标签关联表（多对多关系）
    await db.execute('''
      CREATE TABLE IF NOT EXISTS anime_tags(
        anime_id INTEGER, 
        tag_id INTEGER,
        PRIMARY KEY (anime_id, tag_id),
        FOREIGN KEY (anime_id) REFERENCES animes (id) ON DELETE CASCADE,
        FOREIGN KEY (tag_id) REFERENCES tags (id) ON DELETE CASCADE
      )
    ''');

    // 创建系列表
    await db.execute('''
      CREATE TABLE IF NOT EXISTS series(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT,
        description TEXT,
        custom_cover_url TEXT,
        created_at TEXT
      )
    ''');

    // 创建观看状态表
    await db.execute('''
      CREATE TABLE IF NOT EXISTS watch_statuses(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT UNIQUE,
        color INTEGER,
        sort_order INTEGER
      )
    ''');

    // 插入默认状态
    await _insertDefaultStatuses(db);

    // 创建观看记录表（版本 6）
    await db.execute('''
      CREATE TABLE IF NOT EXISTS watch_records(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        anime_id INTEGER,
        episode INTEGER,
        status TEXT, -- 'watched' or 'completed'
        record_date TEXT, -- ISO8601
        FOREIGN KEY (anime_id) REFERENCES animes (id) ON DELETE CASCADE
      )
    ''');

    // 创建应用日志表（版本 7）
    await db.execute('''
      CREATE TABLE IF NOT EXISTS app_logs(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        message TEXT,
        stack_trace TEXT,
        timestamp TEXT -- ISO8601
      )
    ''');

    await _createAnimeAnalysisRecordsTable(db);
    await _createCharacterTables(db);
  }

  Future<void> _createAnimeAnalysisRecordsTable(Database db) async {
    await db.execute('''
      CREATE TABLE IF NOT EXISTS anime_analysis_records(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        server_record_id INTEGER,
        user_id INTEGER,
        username TEXT,
        model TEXT,
        analysis TEXT,
        stats_json TEXT,
        created_at TEXT
      )
    ''');
  }

  Future<void> _createCharacterTables(Database db) async {
    await db.execute('''
      CREATE TABLE IF NOT EXISTS characters(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        bgm_id INTEGER UNIQUE,
        name TEXT NOT NULL,
        name_cn TEXT,
        image_url TEXT,
        summary TEXT,
        gender TEXT,
        birth_year INTEGER,
        birth_mon INTEGER,
        birth_day INTEGER,
        blood_type TEXT,
        infobox_json TEXT,
        rating INTEGER,
        review TEXT,
        updated_at TEXT
      )
    ''');
    await db.execute('''
      CREATE TABLE IF NOT EXISTS anime_characters(
        anime_id INTEGER NOT NULL,
        character_id INTEGER NOT NULL,
        role_name TEXT,
        sort_order INTEGER DEFAULT 0,
        PRIMARY KEY (anime_id, character_id),
        FOREIGN KEY (anime_id) REFERENCES animes (id) ON DELETE CASCADE,
        FOREIGN KEY (character_id) REFERENCES characters (id) ON DELETE CASCADE
      )
    ''');
    await db.execute('''
      CREATE TABLE IF NOT EXISTS character_relations(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        source_character_id INTEGER NOT NULL,
        target_character_id INTEGER NOT NULL,
        relation_type TEXT DEFAULT '关联',
        note TEXT,
        strength INTEGER DEFAULT 3,
        created_at TEXT,
        updated_at TEXT,
        UNIQUE(source_character_id, target_character_id),
        FOREIGN KEY (source_character_id) REFERENCES characters (id) ON DELETE CASCADE,
        FOREIGN KEY (target_character_id) REFERENCES characters (id) ON DELETE CASCADE
      )
    ''');
    await db.execute('''
      CREATE TABLE IF NOT EXISTS character_tags(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT UNIQUE,
        color INTEGER,
        created_at TEXT
      )
    ''');
    await db.execute('''
      CREATE TABLE IF NOT EXISTS character_tag_links(
        character_id INTEGER NOT NULL,
        tag_id INTEGER NOT NULL,
        PRIMARY KEY (character_id, tag_id),
        FOREIGN KEY (character_id) REFERENCES characters (id) ON DELETE CASCADE,
        FOREIGN KEY (tag_id) REFERENCES character_tags (id) ON DELETE CASCADE
      )
    ''');
    await db.execute('''
      CREATE TABLE IF NOT EXISTS character_groups(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        description TEXT,
        cover_url TEXT,
        community_id TEXT,
        share_code TEXT,
        source TEXT DEFAULT 'local',
        is_public INTEGER DEFAULT 0,
        extra_json TEXT,
        created_at TEXT,
        updated_at TEXT
      )
    ''');
    await db.execute('''
      CREATE TABLE IF NOT EXISTS character_group_characters(
        group_id INTEGER NOT NULL,
        character_id INTEGER NOT NULL,
        role_name TEXT,
        sort_order INTEGER DEFAULT 0,
        PRIMARY KEY (group_id, character_id),
        FOREIGN KEY (group_id) REFERENCES character_groups (id) ON DELETE CASCADE,
        FOREIGN KEY (character_id) REFERENCES characters (id) ON DELETE CASCADE
      )
    ''');
    await db.execute('''
      CREATE TABLE IF NOT EXISTS character_group_works(
        group_id INTEGER NOT NULL,
        anime_id INTEGER NOT NULL,
        sort_order INTEGER DEFAULT 0,
        PRIMARY KEY (group_id, anime_id),
        FOREIGN KEY (group_id) REFERENCES character_groups (id) ON DELETE CASCADE,
        FOREIGN KEY (anime_id) REFERENCES animes (id) ON DELETE CASCADE
      )
    ''');
  }

  Future<void> _addCharacterPersonalFields(Database db) async {
    await _ensureColumn(db, charactersTable, 'rating', 'rating INTEGER');
    await _ensureColumn(db, charactersTable, 'review', 'review TEXT');
  }

  Future<void> _ensureColumn(
    Database db,
    String table,
    String column,
    String definition,
  ) async {
    final columns = await db.rawQuery('PRAGMA table_info($table)');
    final exists = columns.any((row) => row['name'] == column);
    if (!exists) {
      await db.execute('ALTER TABLE $table ADD COLUMN $definition');
    }
  }

  Future<void> _insertDefaultStatuses(Database db) async {
    final defaults = [
      {'name': '在看', 'color': 0xFF2196F3, 'sort_order': 0}, // Blue
      {'name': '看完', 'color': 0xFF4CAF50, 'sort_order': 1}, // Green
      {'name': '未看', 'color': 0xFFFF9800, 'sort_order': 2}, // Orange
      {'name': '弃坑', 'color': 0xFFF44336, 'sort_order': 3}, // Red
    ];
    for (var s in defaults) {
      await db.insert('watch_statuses', s);
    }
  }

  Future<void> _repairMojibakeDefaultStatuses(Database db) async {
    // 历史版本里曾出现过 UTF-8 被按 GBK 写入的默认状态名。
    const statusNameFixes = {
      '\u9366\u3127\u6E45': '在看',
      '\u942A\u5B2A\u756C': '看完',
      '\u93C8\uE046\u6E45': '未看',
      '\u5BEE\u51A8\u6F59': '弃坑',
      '\u934F\u3129\u503C': '全部',
    };

    try {
      for (final entry in statusNameFixes.entries) {
        final badName = entry.key;
        final goodName = entry.value;

        await db.update(
          'animes',
          {'status': goodName},
          where: 'status = ?',
          whereArgs: [badName],
        );

        final badRows = await db.query(
          'watch_statuses',
          columns: ['id'],
          where: 'name = ?',
          whereArgs: [badName],
          limit: 1,
        );
        if (badRows.isEmpty) continue;

        final goodRows = await db.query(
          'watch_statuses',
          columns: ['id'],
          where: 'name = ?',
          whereArgs: [goodName],
          limit: 1,
        );
        final badId = badRows.first['id'] as int;

        if (goodRows.isEmpty) {
          await db.update(
            'watch_statuses',
            {'name': goodName},
            where: 'id = ?',
            whereArgs: [badId],
          );
        } else {
          await db.delete(
            'watch_statuses',
            where: 'id = ?',
            whereArgs: [badId],
          );
        }
      }
    } catch (e) {
      logger.e("Repair mojibake default statuses error: $e");
    }
  }

  Future<void> _repairCompletedAnimeProgress(Database db) async {
    try {
      await db.rawUpdate(
        '''
        UPDATE animes
        SET watched_episodes = total_episodes
        WHERE TRIM(COALESCE(status, '')) = ?
          AND COALESCE(total_episodes, 0) > 0
          AND COALESCE(watched_episodes, 0) < total_episodes
        ''',
        ['看完'],
      );
    } catch (e) {
      logger.e("Repair completed anime progress error: $e");
    }
  }

  /// 升级数据库结构
  Future<void> _onUpgrade(Database db, int oldVersion, int newVersion) async {
    if (oldVersion < 2) {
      // 版本 2: 增加 tv_episodes 和 sp_episodes 字段
      await db.execute(
        'ALTER TABLE animes ADD COLUMN tv_episodes INTEGER DEFAULT 0',
      );
      await db.execute(
        'ALTER TABLE animes ADD COLUMN sp_episodes INTEGER DEFAULT 0',
      );
    }
    if (oldVersion < 3) {
      // 版本 3: 增加系列表
      await db.execute('''
        CREATE TABLE IF NOT EXISTS series(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT,
          description TEXT,
          created_at TEXT
        )
      ''');
    }
    if (oldVersion < 4) {
      // 版本 4: 增加自定义状态表
      await db.execute('''
        CREATE TABLE IF NOT EXISTS watch_statuses(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT UNIQUE,
          color INTEGER,
          sort_order INTEGER
        )
      ''');
      await _insertDefaultStatuses(db);
    }
    if (oldVersion < 5) {
      // 版本 5: 系列表增加自定义封面
      await db.execute('ALTER TABLE series ADD COLUMN custom_cover_url TEXT');
    }
    if (oldVersion < 6) {
      // 版本 6: 增加观看记录表
      await db.execute('''
        CREATE TABLE IF NOT EXISTS watch_records(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          anime_id INTEGER,
          episode INTEGER,
          status TEXT,
          record_date TEXT,
          FOREIGN KEY (anime_id) REFERENCES animes (id) ON DELETE CASCADE
        )
      ''');
    }
    if (oldVersion < 8) {
      // 版本 8: 增加 subject_type 字段
      await db.execute(
        "ALTER TABLE animes ADD COLUMN subject_type TEXT DEFAULT 'anime'",
      );
    }
    if (oldVersion < 9) {
      // 版本 9: 增加提醒字段
      await db.execute('ALTER TABLE animes ADD COLUMN reminder_day INTEGER');
      await db.execute('ALTER TABLE animes ADD COLUMN reminder_time TEXT');
    }
    if (oldVersion < 10) {
      await _repairMojibakeDefaultStatuses(db);
    }
    if (oldVersion < 11) {
      await _createAnimeAnalysisRecordsTable(db);
    }
    if (oldVersion < 12) {
      await _createCharacterTables(db);
    }
    if (oldVersion < 13) {
      await _createCharacterTables(db);
    }
    if (oldVersion < 14) {
      await _createCharacterTables(db);
      await _addCharacterPersonalFields(db);
    }
    if (oldVersion < 15) {
      await _createCharacterTables(db);
    }
    if (oldVersion < 16) {
      await _createCharacterTables(db);
    }
    if (oldVersion < 17) {
      // share_code 列可能已在 _createCharacterTables 中添加，先检查是否存在
      final columns = await db.rawQuery("PRAGMA table_info(character_groups)");
      final hasShareCode = columns.any((c) => c['name'] == 'share_code');
      if (!hasShareCode) {
        await db.execute(
          'ALTER TABLE character_groups ADD COLUMN share_code TEXT',
        );
      }
    }
    if (oldVersion < 18) {
      await _ensureColumn(db, animesTable, 'rating_grade', 'rating_grade TEXT');
    }
    if (oldVersion < 19) {
      await _ensureColumn(
        db,
        animesTable,
        'fun_rating_tier',
        'fun_rating_tier TEXT',
      );
    }
  }

  // ============== 向下兼容的 DAO 委托代理 ==============

  // ---- 状态管理 (WatchStatusDao) ----
  Future<List<Map<String, dynamic>>> getAllStatuses() =>
      watchStatusDao.getAllStatuses();
  Future<int> insertStatus(String name, int color) async {
    final result = await watchStatusDao.insertStatus(name, color);
    if (result > 0) notifyTableChanged(watchStatusesTable);
    return result;
  }

  Future<int> updateStatus(int id, String name, int color) async {
    final result = await watchStatusDao.updateStatus(id, name, color);
    if (result > 0) notifyTablesChanged([watchStatusesTable, animesTable]);
    return result;
  }

  Future<int> deleteStatus(int id) async {
    final result = await watchStatusDao.deleteStatus(id);
    if (result > 0) notifyTableChanged(watchStatusesTable);
    return result;
  }

  Future<void> updateStatusesOrder(List<int> ids) async {
    await watchStatusDao.updateStatusesOrder(ids);
    notifyTableChanged(watchStatusesTable);
  }

  // ---- 观看记录 (WatchRecordDao) ----
  Future<int> insertWatchRecord({
    required int animeId,
    required int episode,
    String status = 'watched',
    String? date,
  }) async {
    final result = await watchRecordDao.insertWatchRecord(
      animeId: animeId,
      episode: episode,
      status: status,
      date: date,
    );
    if (result > 0) notifyTableChanged(watchRecordsTable);
    return result;
  }

  Future<List<Map<String, dynamic>>> getAllWatchRecords() =>
      watchRecordDao.getAllWatchRecords();
  Future<int> deleteWatchRecordsByAnimeId(int animeId) async {
    final result = await watchRecordDao.deleteWatchRecordsByAnimeId(animeId);
    if (result > 0) notifyTableChanged(watchRecordsTable);
    return result;
  }

  Future<int> deleteWatchRecord(int id) async {
    final result = await watchRecordDao.deleteWatchRecord(id);
    if (result > 0) notifyTableChanged(watchRecordsTable);
    return result;
  }

  // ---- 动漫基础 CRUD 及统计、提醒 (AnimeDao) ----
  Future<int> insertAnime(Map<String, dynamic> row) async {
    final result = await animeDao.insertAnime(row);
    if (result > 0) notifyTableChanged(animesTable);
    return result;
  }

  Future<int> updateAnime(Map<String, dynamic> row) async {
    final result = await animeDao.updateAnime(row);
    if (result > 0) notifyTableChanged(animesTable);
    return result;
  }

  Future<void> updateFunRatingTier(int animeId, String? tier) async {
    final result = await animeDao.updateFunRatingTier(animeId, tier);
    if (result > 0) notifyTableChanged(animesTable);
  }

  Future<void> clearFunRatingTiers() async {
    final result = await animeDao.clearFunRatingTiers();
    if (result > 0) notifyTableChanged(animesTable);
  }

  Future<int> deleteAnime(int id) async {
    final result = await animeDao.deleteAnime(id);
    if (result > 0) {
      notifyTablesChanged([animesTable, animeTagsTable, watchRecordsTable]);
    }
    return result;
  }

  Future<List<Map<String, dynamic>>> queryAllAnimes() =>
      animeDao.queryAllAnimes();
  Future<Map<String, dynamic>?> getAnimeById(int id) =>
      animeDao.getAnimeById(id);
  Future<Map<String, dynamic>?> getAnimeByTitle(String title) =>
      animeDao.getAnimeByTitle(title);
  Future<List<Map<String, dynamic>>> getAnimesWithoutSeries() =>
      animeDao.getAnimesWithoutSeries();
  Future<void> updateAnimeSeries(int animeId, int? seriesId) async {
    await animeDao.updateAnimeSeries(animeId, seriesId);
    notifyTableChanged(animesTable);
  }

  Future<void> batchUpdateStatus(List<int> animeIds, String newStatus) async {
    await animeDao.batchUpdateStatus(animeIds, newStatus);
    notifyTableChanged(animesTable);
  }

  Future<void> batchUpdateAnimesSeries(List<int> animeIds, int seriesId) async {
    await animeDao.batchUpdateAnimesSeries(animeIds, seriesId);
    notifyTablesChanged([animesTable, seriesTable]);
  }

  Future<List<String>> getAllStudios() => animeDao.getAllStudios();
  Future<List<Map<String, dynamic>>> getAnimesWithReminders() =>
      animeDao.getAnimesWithReminders();
  Future<List<Map<String, dynamic>>> searchAnimes({
    String? query,
    List<int>? tagIds,
    String? status,
    String? subjectType,
    List<String>? years,
    bool isAndMode = false,
    String sortOption = 'a.id DESC',
  }) => animeDao.searchAnimes(
    query: query,
    tagIds: tagIds,
    status: status,
    subjectType: subjectType,
    years: years,
    isAndMode: isAndMode,
    sortOption: sortOption,
  );
  Future<Map<String, int>> getStatusCounts() => animeDao.getStatusCounts();
  Future<Map<String, int>> getSubjectTypeCounts() =>
      animeDao.getSubjectTypeCounts();

  // ---- 系列功能 (SeriesDao) ----
  Future<int> createSeries(String name, {String? description}) async {
    final result = await seriesDao.createSeries(name, description: description);
    if (result > 0) notifyTableChanged(seriesTable);
    return result;
  }

  Future<Map<String, dynamic>?> getSeriesById(int id) =>
      seriesDao.getSeriesById(id);
  Future<List<Map<String, dynamic>>> getAllSeries() => seriesDao.getAllSeries();
  Future<int> updateSeries(int id, Map<String, dynamic> row) async {
    final result = await seriesDao.updateSeries(id, row);
    if (result > 0) notifyTableChanged(seriesTable);
    return result;
  }

  Future<void> deleteSeries(int id) async {
    await seriesDao.deleteSeries(id);
    notifyTablesChanged([seriesTable, animesTable]);
  }

  Future<List<Map<String, dynamic>>> getAnimesInSeries(int seriesId) =>
      seriesDao.getAnimesInSeries(seriesId);
  Future<List<Map<String, dynamic>>> searchSeries(String query) =>
      seriesDao.searchSeries(query);

  // ---- 标签管理及批量操作 (TagDao) ----
  Future<int> insertTag(String name) async {
    final result = await tagDao.insertTag(name);
    if (result > 0) notifyTableChanged(tagsTable);
    return result;
  }

  Future<int> updateTag(int id, String newName) async {
    final result = await tagDao.updateTag(id, newName);
    if (result > 0) notifyTableChanged(tagsTable);
    return result;
  }

  Future<void> deleteTag(int id) async {
    await tagDao.deleteTag(id);
    notifyTablesChanged([tagsTable, animeTagsTable]);
  }

  Future<List<Map<String, dynamic>>> getAllTags() => tagDao.getAllTags();
  Future<void> addTagToAnime(int animeId, int tagId) async {
    await tagDao.addTagToAnime(animeId, tagId);
    notifyTableChanged(animeTagsTable);
  }

  Future<void> updateAnimeTags(int animeId, Set<int> tagIds) async {
    await tagDao.updateAnimeTags(animeId, tagIds);
    notifyTableChanged(animeTagsTable);
  }

  Future<List<Map<String, dynamic>>> getTagsByAnimeId(int animeId) =>
      tagDao.getTagsByAnimeId(animeId);
  Future<void> batchAddTagToAnimes(List<int> animeIds, int tagId) async {
    await tagDao.batchAddTagToAnimes(animeIds, tagId);
    notifyTableChanged(animeTagsTable);
  }

  Future<void> batchRemoveTagFromAnimes(List<int> animeIds, int tagId) async {
    await tagDao.batchRemoveTagFromAnimes(animeIds, tagId);
    notifyTableChanged(animeTagsTable);
  }

  Future<List<Map<String, dynamic>>> getTagCounts() => tagDao.getTagCounts();

  // ---- 角色管理 (CharacterDao) ----
  Future<int> upsertCharacter(Map<String, dynamic> row) async {
    final result = await characterDao.upsertCharacter(row);
    if (result > 0) notifyTableChanged(charactersTable);
    return result;
  }

  Future<int> updateCharacterPersonalReview({
    required int characterId,
    int? rating,
    String? review,
  }) async {
    final result = await characterDao.updateCharacterPersonalReview(
      characterId: characterId,
      rating: rating,
      review: review,
    );
    if (result > 0) notifyTableChanged(charactersTable);
    return result;
  }

  Future<void> addCharacterToAnime({
    required int animeId,
    required int characterId,
    String? roleName,
  }) async {
    await characterDao.addCharacterToAnime(
      animeId: animeId,
      characterId: characterId,
      roleName: roleName,
    );
    notifyTableChanged(animeCharactersTable);
  }

  Future<void> removeCharacterFromAnime({
    required int animeId,
    required int characterId,
  }) async {
    await characterDao.removeCharacterFromAnime(
      animeId: animeId,
      characterId: characterId,
    );
    notifyTableChanged(animeCharactersTable);
  }

  Future<List<Map<String, dynamic>>> getCharactersByAnimeId(int animeId) =>
      characterDao.getCharactersByAnimeId(animeId);

  Future<List<Map<String, dynamic>>> getAllCharacters({String? query}) =>
      characterDao.getAllCharacters(query: query);

  Future<int> createCharacterTag(String name) async {
    final result = await characterDao.createCharacterTag(name);
    if (result > 0) notifyTableChanged(characterTagsTable);
    return result;
  }

  Future<List<Map<String, dynamic>>> getAllCharacterTags() =>
      characterDao.getAllCharacterTags();

  Future<List<Map<String, dynamic>>> getCharacterTags(int characterId) =>
      characterDao.getCharacterTags(characterId);

  Future<void> updateCharacterTags({
    required int characterId,
    required Set<int> tagIds,
  }) async {
    await characterDao.updateCharacterTags(
      characterId: characterId,
      tagIds: tagIds,
    );
    notifyTablesChanged([
      charactersTable,
      characterTagsTable,
      characterTagLinksTable,
    ]);
  }

  Future<int> upsertCharacterGroup({
    int? groupId,
    required String name,
    String? description,
    required List<int> characterIds,
    required List<int> workIds,
  }) async {
    final result = await characterDao.upsertCharacterGroup(
      groupId: groupId,
      name: name,
      description: description,
      characterIds: characterIds,
      workIds: workIds,
    );
    if (result > 0) {
      notifyTablesChanged([
        characterGroupsTable,
        characterGroupCharactersTable,
        characterGroupWorksTable,
      ]);
    }
    return result;
  }

  Future<CharacterGroupImportResult> importCharacterGroupPackage(
    CharacterGroupPackage package, {
    String source = 'community',
  }) async {
    final result = await characterDao.importCharacterGroupPackage(
      package,
      source: source,
    );
    notifyTablesChanged([
      animesTable,
      charactersTable,
      characterGroupsTable,
      characterGroupCharactersTable,
      characterGroupWorksTable,
    ]);
    return result;
  }

  Future<void> deleteCharacterGroup(int groupId) async {
    await characterDao.deleteCharacterGroup(groupId);
    notifyTablesChanged([
      characterGroupsTable,
      characterGroupCharactersTable,
      characterGroupWorksTable,
    ]);
  }

  Future<void> updateCharacterGroupCommunityInfo({
    required int groupId,
    String? communityId,
    String? shareCode,
  }) async {
    await characterDao.updateCharacterGroupCommunityInfo(
      groupId: groupId,
      communityId: communityId,
      shareCode: shareCode,
    );
    notifyTablesChanged([characterGroupsTable]);
  }

  Future<List<Map<String, dynamic>>> getCharacterGroups({String? query}) =>
      characterDao.getCharacterGroups(query: query);

  Future<List<Map<String, dynamic>>> getCharacterGroupCharacters(int groupId) =>
      characterDao.getCharacterGroupCharacters(groupId);

  Future<List<Map<String, dynamic>>> getCharacterGroupWorks(int groupId) =>
      characterDao.getCharacterGroupWorks(groupId);

  Future<CharacterGroupPackage> exportCharacterGroupPackage(int groupId) =>
      characterDao.exportCharacterGroupPackage(groupId);

  Future<List<Map<String, dynamic>>> searchGroupableWorks({
    String? query,
    String subjectType = 'all',
    int limit = 120,
  }) => characterDao.searchGroupableWorks(
    query: query,
    subjectType: subjectType,
    limit: limit,
  );

  Future<Set<int>> getAnimeIdsByCharacterIds(List<int> characterIds) =>
      characterDao.getAnimeIdsByCharacterIds(characterIds);

  Future<List<Map<String, dynamic>>> getRelationCandidates({
    required int sourceCharacterId,
    String? query,
    int limit = 80,
  }) => characterDao.getRelationCandidates(
    sourceCharacterId: sourceCharacterId,
    query: query,
    limit: limit,
  );

  Future<Map<String, dynamic>?> getCharacterById(int characterId) =>
      characterDao.getCharacterById(characterId);

  Future<List<Map<String, dynamic>>> getWorksByCharacterId(int characterId) =>
      characterDao.getWorksByCharacterId(characterId);

  Future<List<Map<String, dynamic>>> getWorksLinkedToCharacterMissingFrom({
    required int fromCharacterId,
    required int missingFromCharacterId,
  }) => characterDao.getWorksLinkedToCharacterMissingFrom(
    fromCharacterId: fromCharacterId,
    missingFromCharacterId: missingFromCharacterId,
  );

  Future<List<Map<String, dynamic>>> searchLinkableWorks({
    required int characterId,
    String? query,
    String subjectType = 'all',
    int limit = 60,
  }) => characterDao.searchLinkableWorks(
    characterId: characterId,
    query: query,
    subjectType: subjectType,
    limit: limit,
  );

  Future<int> upsertCharacterRelation({
    required int sourceCharacterId,
    required int targetCharacterId,
    String relationType = '关联',
    String? note,
    int strength = 3,
  }) async {
    final result = await characterDao.upsertCharacterRelation(
      sourceCharacterId: sourceCharacterId,
      targetCharacterId: targetCharacterId,
      relationType: relationType,
      note: note,
      strength: strength,
    );
    if (result > 0) notifyTableChanged(characterRelationsTable);
    return result;
  }

  Future<void> deleteCharacterRelation(int relationId) async {
    await characterDao.deleteCharacterRelation(relationId);
    notifyTableChanged(characterRelationsTable);
  }

  Future<List<Map<String, dynamic>>> getCharacterRelations(int characterId) =>
      characterDao.getCharacterRelations(characterId);

  Future<List<List<Map<String, dynamic>>>> findDuplicateCharacters() =>
      characterDao.findDuplicateCharacters();

  Future<void> deleteCharacter(int characterId) async {
    await characterDao.deleteCharacter(characterId);
    notifyTablesChanged([
      charactersTable,
      animeCharactersTable,
      characterRelationsTable,
      characterTagLinksTable,
      characterGroupCharactersTable,
    ]);
  }

  // ---- 日志管理 (LogDao) ----
  Future<int> insertLog(String message, String? stackTrace) =>
      logDao.insertLog(message, stackTrace);
  Future<List<Map<String, dynamic>>> getLogs() => logDao.getLogs();
  Future<int> clearOldLogs() => logDao.clearOldLogs();

  // ---- AI 看番风格分析记录 ----
  Future<int> insertAnimeAnalysisRecord(Map<String, dynamic> row) async {
    final db = await database;
    final result = await db.insert(animeAnalysisRecordsTable, row);
    if (result > 0) notifyTableChanged(animeAnalysisRecordsTable);
    return result;
  }

  Future<List<Map<String, dynamic>>> getAnimeAnalysisRecords({
    int? userId,
  }) async {
    final db = await database;
    return db.query(
      animeAnalysisRecordsTable,
      where: userId == null ? null : 'user_id = ?',
      whereArgs: userId == null ? null : [userId],
      orderBy: 'created_at DESC, id DESC',
    );
  }

  Future<Map<String, dynamic>?> getLatestAnimeAnalysisRecord({
    int? userId,
  }) async {
    final db = await database;
    final rows = await db.query(
      animeAnalysisRecordsTable,
      where: userId == null ? null : 'user_id = ?',
      whereArgs: userId == null ? null : [userId],
      orderBy: 'created_at DESC, id DESC',
      limit: 1,
    );
    if (rows.isEmpty) return null;
    return rows.first;
  }

  // ============== 数据库管理 ==============

  /// 获取数据库文件路径（用于备份操作）
  Future<String> getDbPath() async {
    return join(await getDatabasesPath(), 'anime_tracker_v5.db');
  }

  /// 关闭数据库连接（用于恢复操作前）
  Future<void> closeDatabase() async {
    if (_database != null) {
      await _database!.close();
      _database = null; // 重置连接，下次访问时会重新初始化
    }
  }

  Future<void> dispose() async {
    await closeDatabase();
    for (final controller in _tableControllers.values) {
      await controller.close();
    }
    _tableControllers.clear();
  }
}
