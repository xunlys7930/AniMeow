import 'dart:io';
import 'package:path/path.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';
import '../utils/logger.dart';

/// 数据库帮助类，管理应用数据存储
/// 使用 SQLite 数据库，支持跨平台（包括桌面端）
class DatabaseHelper {
  // 单例模式实例
  static final DatabaseHelper _instance = DatabaseHelper._internal();
  static Database? _database;

  /// 单例工厂构造函数
  factory DatabaseHelper() {
    return _instance;
  }

  /// 私有构造函数
  DatabaseHelper._internal();

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
      version: 9,
      onConfigure: (db) async {
        // 开启外键支持，确保级联删除生效
        await db.execute('PRAGMA foreign_keys = ON');
      },
      onCreate: _onCreate,
      onUpgrade: _onUpgrade,
    );

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

    // 创建观看记录表 (版本 6)
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

    // 创建应用日志表 (版本 7)
    await db.execute('''
      CREATE TABLE IF NOT EXISTS app_logs(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        message TEXT,
        stack_trace TEXT,
        timestamp TEXT -- ISO8601
      )
    ''');
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
  }

  // ============== 状态管理 (Custom Statuses) ==============

  Future<List<Map<String, dynamic>>> getAllStatuses() async {
    Database db = await database;
    return await db.query('watch_statuses', orderBy: 'sort_order ASC');
  }

  Future<int> insertStatus(String name, int color) async {
    Database db = await database;
    try {
      // 获取当前最大 sort_order
      final result = await db.rawQuery(
        'SELECT MAX(sort_order) as max_order FROM watch_statuses',
      );
      int maxOrder = (result.first['max_order'] as int?) ?? -1;

      return await db.insert('watch_statuses', {
        'name': name,
        'color': color,
        'sort_order': maxOrder + 1,
      });
    } catch (e) {
      logger.e("Insert status error: $e");
      return -1; // Duplicate name or error
    }
  }

  Future<int> updateStatus(int id, String name, int color) async {
    Database db = await database;
    // 获取旧名称，以便更新 animes 表
    final oldResult = await db.query(
      'watch_statuses',
      where: 'id = ?',
      whereArgs: [id],
    );
    if (oldResult.isEmpty) return 0;
    String oldName = oldResult.first['name'] as String;

    return await db.transaction((txn) async {
      int count = await txn.update(
        'watch_statuses',
        {'name': name, 'color': color},
        where: 'id = ?',
        whereArgs: [id],
      );
      if (oldName != name) {
        // 同步更新 animes 表中的 status
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

  /// 获取所有带有提醒设置的番剧
  Future<List<Map<String, dynamic>>> getAnimesWithReminders() async {
    Database db = await database;
    return await db.query(
      'animes',
      where: 'reminder_day IS NOT NULL AND reminder_time IS NOT NULL',
      orderBy: 'reminder_day ASC, reminder_time ASC',
    );
  }

  Future<int> deleteStatus(int id) async {
    Database db = await database;
    // 检查是否被使用
    final statusResult = await db.query(
      'watch_statuses',
      where: 'id = ?',
      whereArgs: [id],
    );
    if (statusResult.isEmpty) return 0;
    String statusName = statusResult.first['name'] as String;

    final countResult = await db.rawQuery(
      'SELECT COUNT(*) as count FROM animes WHERE status = ?',
      [statusName],
    );
    int count = (countResult.first['count'] as int?) ?? 0;

    if (count > 0) {
      // 被使用了，不允许删除 (或者提示用户先迁移)
      // 这里简便起见，直接抛异常或者返回特定错误码
      // 或者我们可以约定返回 -2 表示被占用
      return -2;
    }

    return await db.delete('watch_statuses', where: 'id = ?', whereArgs: [id]);
  }

  /// 批量更新状态排序
  Future<void> updateStatusesOrder(List<int> ids) async {
    Database db = await database;
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

  // ============== 观看记录 (Watch Records) ==============

  /// 插入观看记录
  Future<int> insertWatchRecord({
    required int animeId,
    required int episode,
    String status = 'watched',
    String? date,
  }) async {
    Database db = await database;
    final timestamp = date ?? DateTime.now().toIso8601String();

    // 避免同一天重复记录同一集的观看 (简单去重)
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
      // 避免同一天重复记录完成
      final today = timestamp.substring(0, 10);
      final existing = await db.query(
        'watch_records',
        where: 'anime_id = ? AND status = ? AND record_date LIKE ?',
        whereArgs: [animeId, 'completed', '$today%'],
      );
      if (existing.isNotEmpty) return -1;

      // 如果同一天已经记录了“观看了最后一集”，则可以考虑删除该条记录，
      // 因为“看完”已经包含了最后一集的信息。
      await db.delete(
        'watch_records',
        where: 'anime_id = ? AND status = ? AND record_date LIKE ?',
        whereArgs: [animeId, 'watched', '$today%'],
      );
    }

    return await db.insert('watch_records', {
      'anime_id': animeId,
      'episode': episode,
      'status': status,
      'record_date': timestamp,
    });
  }

  /// 获取所有观看记录并关联番剧信息
  /// 增加逻辑：为 'completed' 类型的记录计算这是此番剧的第几次完成
  Future<List<Map<String, dynamic>>> getAllWatchRecords() async {
    Database db = await database;
    final List<Map<String, dynamic>> records = await db.rawQuery('''
      SELECT wr.*, a.title, a.cover_url, a.subject_type
      FROM watch_records wr
      JOIN animes a ON wr.anime_id = a.id
      ORDER BY wr.record_date ASC
    ''');

    // 动态计算完成次数
    Map<int, int> completionCounts = {};
    List<Map<String, dynamic>> processedRecords = [];

    for (var record in records) {
      Map<String, dynamic> mutableRecord = Map.from(record);
      if (record['status'] == 'completed') {
        int animeId = record['anime_id'];
        completionCounts[animeId] = (completionCounts[animeId] ?? 0) + 1;
        mutableRecord['watch_count'] = completionCounts[animeId];
      }
      processedRecords.add(mutableRecord);
    }

    // 返回时按时间倒序排列（保持原有的显示顺序）
    return processedRecords.reversed.toList();
  }

  /// 删除特定动漫的所有记录 (通常级联删除已处理)
  Future<int> deleteWatchRecordsByAnimeId(int animeId) async {
    Database db = await database;
    return await db.delete(
      'watch_records',
      where: 'anime_id = ?',
      whereArgs: [animeId],
    );
  }

  /// 手动删除单条观看记录
  Future<int> deleteWatchRecord(int id) async {
    Database db = await database;
    return await db.delete('watch_records', where: 'id = ?', whereArgs: [id]);
  }

  // ============== 动漫记录基础增删改查 ==============

  /// 插入新的动漫记录
  Future<int> insertAnime(Map<String, dynamic> row) async {
    Database db = await database;
    return await db.insert('animes', row);
  }

  /// 更新现有动漫记录
  Future<int> updateAnime(Map<String, dynamic> row) async {
    Database db = await database;
    return await db.update(
      'animes',
      row,
      where: 'id = ?',
      whereArgs: [row['id']],
    );
  }

  /// 删除动漫记录
  Future<int> deleteAnime(int id) async {
    Database db = await database;
    return await db.delete('animes', where: 'id = ?', whereArgs: [id]);
  }

  /// 查询所有动漫记录（按 ID 倒序）
  Future<List<Map<String, dynamic>>> queryAllAnimes() async {
    Database db = await database;
    return await db.query('animes', orderBy: "id DESC");
  }

  /// 获取单个动漫详情
  Future<Map<String, dynamic>?> getAnimeById(int id) async {
    Database db = await database;
    List<Map<String, dynamic>> maps = await db.query(
      'animes',
      where: 'id = ?',
      whereArgs: [id],
    );
    if (maps.isNotEmpty) return maps.first;
    return null;
  }

  // ============== 系列功能 ==============

  /// 创建新系列
  Future<int> createSeries(String name, {String? description}) async {
    Database db = await database;
    return await db.insert('series', {
      'name': name,
      'description': description,
      'created_at': DateTime.now().toIso8601String(),
    });
  }

  /// 获取单个系列详情
  Future<Map<String, dynamic>?> getSeriesById(int id) async {
    final db = await database;
    final List<Map<String, dynamic>> maps = await db.query(
      'series',
      where: 'id = ?',
      whereArgs: [id],
    );
    if (maps.isEmpty) return null;
    return maps.first;
  }

  /// 获取所有系列，并提取统计信息
  Future<List<Map<String, dynamic>>> getAllSeries() async {
    Database db = await database;
    // 使用聚合查询获取系列信息、番剧数量以及最新一部番剧的封面
    // 同时也保留 custom_cover_url
    return await db.rawQuery('''
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

  /// 更新系列信息
  Future<int> updateSeries(int id, Map<String, dynamic> row) async {
    Database db = await database;
    return await db.update('series', row, where: 'id = ?', whereArgs: [id]);
  }

  /// 删除系列（关联的动漫将 series_id 置为 null）
  Future<void> deleteSeries(int id) async {
    Database db = await database;
    await db.transaction((txn) async {
      // 1. 将该系列下的动漫 series_id 置空
      await txn.update(
        'animes',
        {'series_id': null},
        where: 'series_id = ?',
        whereArgs: [id],
      );
      // 2. 删除系列
      await txn.delete('series', where: 'id = ?', whereArgs: [id]);
    });
  }

  /// 获取特定系列下的所有动漫
  Future<List<Map<String, dynamic>>> getAnimesInSeries(int seriesId) async {
    Database db = await database;
    return await db.rawQuery(
      'SELECT a.*, s.name as series_name FROM animes a '
      'LEFT JOIN series s ON a.series_id = s.id '
      'WHERE a.series_id = ? ORDER BY a.air_date ASC',
      [seriesId],
    );
  }

  /// 更新动漫所属系列
  Future<void> updateAnimeSeries(int animeId, int? seriesId) async {
    Database db = await database;
    await db.update(
      'animes',
      {'series_id': seriesId},
      where: 'id = ?',
      whereArgs: [animeId],
    );
  }

  /// 获取所有不属于任何系列的番剧
  Future<List<Map<String, dynamic>>> getAnimesWithoutSeries() async {
    Database db = await database;
    return await db.query(
      'animes',
      where: 'series_id IS NULL',
      orderBy: 'title ASC',
    );
  }

  /// 批量更新动漫所属系列
  Future<void> batchUpdateAnimesSeries(List<int> animeIds, int seriesId) async {
    Database db = await database;
    await db.transaction((txn) async {
      for (int id in animeIds) {
        await txn.update(
          'animes',
          {'series_id': seriesId},
          where: 'id = ?',
          whereArgs: [id],
        );
      }
    });
  }

  // ============== 标签管理功能 ==============

  /// 创建新标签（自动去重）
  ///
  /// [name] 标签名称
  /// 返回标签 ID，如果标签已存在则返回 -1
  Future<int> insertTag(String name) async {
    Database db = await database;

    // 1. 去除首尾空格
    final cleanName = name.trim();

    // 2. 检查同名标签是否存在
    final List<Map<String, dynamic>> existing = await db.query(
      'tags',
      where: 'name = ?',
      whereArgs: [cleanName],
    );

    if (existing.isNotEmpty) {
      return -1; // 标签已存在
    }

    // 3. 插入新标签（使用默认颜色）
    return await db.insert('tags', {'name': cleanName, 'color': 0xFF2196F3});
  }

  /// 更新标签名称
  Future<int> updateTag(int id, String newName) async {
    Database db = await database;
    return await db.update(
      'tags',
      {'name': newName},
      where: 'id = ?',
      whereArgs: [id],
    );
  }

  /// 删除标签（彻底删除及其关联记录）
  Future<void> deleteTag(int id) async {
    Database db = await database;
    await db.transaction((txn) async {
      // 1. 删除标签关联记录
      await txn.delete('anime_tags', where: 'tag_id = ?', whereArgs: [id]);
      // 2. 删除标签本身
      await txn.delete('tags', where: 'id = ?', whereArgs: [id]);
    });
  }

  /// 获取所有标签（按名称升序排序）
  Future<List<Map<String, dynamic>>> getAllTags() async {
    Database db = await database;
    return await db.query('tags', orderBy: 'name ASC');
  }

  /// 为动漫添加标签
  Future<void> addTagToAnime(int animeId, int tagId) async {
    Database db = await database;
    await db.insert('anime_tags', {
      'anime_id': animeId,
      'tag_id': tagId,
    }, conflictAlgorithm: ConflictAlgorithm.ignore);
  }

  /// 更新动漫的标签集合
  Future<void> updateAnimeTags(int animeId, Set<int> tagIds) async {
    Database db = await database;
    await db.transaction((txn) async {
      // 1. 删除该动漫的所有现有标签
      await txn.delete(
        'anime_tags',
        where: 'anime_id = ?',
        whereArgs: [animeId],
      );

      // 2. 插入新的标签集合
      for (int tagId in tagIds) {
        await txn.insert('anime_tags', {
          'anime_id': animeId,
          'tag_id': tagId,
        }, conflictAlgorithm: ConflictAlgorithm.ignore);
      }
    });
  }

  /// 根据动漫 ID 获取其所有标签
  Future<List<Map<String, dynamic>>> getTagsByAnimeId(int animeId) async {
    Database db = await database;
    return await db.rawQuery(
      '''
      SELECT t.id, t.name 
      FROM tags t 
      INNER JOIN anime_tags at ON t.id = at.tag_id 
      WHERE at.anime_id = ?
    ''',
      [animeId],
    );
  }

  // ============== 辅助方法 ==============

  /// 获取所有不重复的制作公司列表（按字母顺序排序）
  Future<List<String>> getAllStudios() async {
    Database db = await database;
    final List<Map<String, dynamic>> maps = await db.rawQuery('''
      SELECT DISTINCT studio 
      FROM animes 
      WHERE studio IS NOT NULL AND studio != '' 
      ORDER BY studio ASC
    ''');
    return maps.map((row) => row['studio'] as String).toList();
  }

  // ============== 高级搜索功能 ==============

  /// 多条件搜索动漫记录
  ///
  /// [query] 搜索关键词（标题、制作公司、评论）
  /// [tagIds] 要筛选的标签 ID 列表
  /// [status] 动漫状态筛选
  /// [isAndMode] 标签筛选模式：true=AND（同时满足所有标签），false=OR（满足任意标签）
  /// [sortOption] 排序规则，默认为 ID 倒序
  Future<List<Map<String, dynamic>>> searchAnimes({
    String? query,
    List<int>? tagIds,
    String? status,
    String? subjectType,
    List<String>? years,
    bool isAndMode = false,
    String sortOption = 'a.id DESC',
  }) async {
    Database db = await database;

    // 构建 SQL 查询
    String sql =
        'SELECT a.*, s.name as series_name FROM animes a '
        'LEFT JOIN series s ON a.series_id = s.id ';
    List<dynamic> args = [];

    // 处理标签筛选
    if (tagIds != null && tagIds.isNotEmpty) {
      sql += 'JOIN anime_tags at ON a.id = at.anime_id ';
    }

    sql += 'WHERE 1=1 ';

    // 添加类型筛选条件
    if (subjectType != null && subjectType != 'all') {
      sql += 'AND a.subject_type = ? ';
      args.add(subjectType);
    }

    // 添加年份筛选条件 (多选支持)
    if (years != null && years.isNotEmpty) {
      String yearPlaceholders = List.filled(
        years.length,
        'a.air_date LIKE ?',
      ).join(' OR ');
      sql += 'AND ($yearPlaceholders) ';
      for (var y in years) {
        args.add('$y%');
      }
    }

    // 添加标签筛选条件
    if (tagIds != null && tagIds.isNotEmpty) {
      String placeholders = List.filled(tagIds.length, '?').join(',');
      sql += 'AND at.tag_id IN ($placeholders) ';
      args.addAll(tagIds);
    }

    // 添加状态筛选条件
    if (status != null && status != '全部') {
      sql += 'AND a.status = ? ';
      args.add(status);
    }

    // 添加关键词搜索条件
    if (query != null && query.isNotEmpty) {
      sql += 'AND (a.title LIKE ? OR a.studio LIKE ? OR a.review LIKE ?) ';
      String likeQuery = '%$query%';
      args.add(likeQuery);
      args.add(likeQuery);
      args.add(likeQuery);
    }

    // 分组和标签匹配模式
    if (tagIds != null && tagIds.isNotEmpty) {
      sql += 'GROUP BY a.id ';
      if (isAndMode) {
        sql += 'HAVING COUNT(DISTINCT at.tag_id) = ? ';
        args.add(tagIds.length);
      }
    } else {
      sql += 'GROUP BY a.id ';
    }

    // 应用排序规则
    sql += 'ORDER BY $sortOption';

    return await db.rawQuery(sql, args);
  }

  // 搜索系列
  Future<List<Map<String, dynamic>>> searchSeries(String query) async {
    Database db = await database;
    return await db.query(
      'series',
      where: 'name LIKE ?',
      whereArgs: ['%$query%'],
      orderBy: 'created_at DESC',
    );
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

  // ============== 批量操作功能 ==============

  /// 批量更新动漫状态
  Future<void> batchUpdateStatus(List<int> animeIds, String newStatus) async {
    Database db = await database;
    await db.transaction((txn) async {
      for (int id in animeIds) {
        await txn.update(
          'animes',
          {'status': newStatus},
          where: 'id = ?',
          whereArgs: [id],
        );
      }
    });
  }

  /// 批量添加标签到多个动漫
  Future<void> batchAddTagToAnimes(List<int> animeIds, int tagId) async {
    Database db = await database;
    await db.transaction((txn) async {
      for (int animeId in animeIds) {
        await txn.insert('anime_tags', {
          'anime_id': animeId,
          'tag_id': tagId,
        }, conflictAlgorithm: ConflictAlgorithm.ignore);
      }
    });
  }

  /// 批量从多个动漫移除标签
  Future<void> batchRemoveTagFromAnimes(List<int> animeIds, int tagId) async {
    Database db = await database;
    await db.transaction((txn) async {
      for (int animeId in animeIds) {
        await txn.delete(
          'anime_tags',
          where: 'anime_id = ? AND tag_id = ?',
          whereArgs: [animeId, tagId],
        );
      }
    });
  }

  // ============== 查重功能 ==============

  /// 根据标题查找动漫（用于检测重复记录）
  ///
  /// [title] 动漫标题
  /// 返回匹配的动漫记录，若无匹配则返回 null
  Future<Map<String, dynamic>?> getAnimeByTitle(String title) async {
    Database db = await database;
    final List<Map<String, dynamic>> maps = await db.query(
      'animes',
      where: 'title = ?',
      whereArgs: [title],
      limit: 1,
    );
    if (maps.isNotEmpty) {
      return maps.first;
    }
    return null;
  }

  // ============== 统计功能 ==============

  /// 统计各状态动漫数量
  Future<Map<String, int>> getStatusCounts() async {
    Database db = await database;

    // 按状态分组统计
    final List<Map<String, dynamic>> result = await db.rawQuery('''
      SELECT status, COUNT(*) as count 
      FROM animes 
      GROUP BY status
    ''');

    // 初始化默认值，确保所有状态都有计数
    Map<String, int> counts = {'在看': 0, '看完': 0, '未看': 0, '弃坑': 0};

    // 填充实际统计结果
    for (var row in result) {
      if (row['status'] != null) {
        counts[row['status'] as String] = row['count'] as int;
      }
    }

    return counts;
  }

  /// 统计各类型（番剧/小说）动漫数量
  Future<Map<String, int>> getSubjectTypeCounts() async {
    Database db = await database;

    final List<Map<String, dynamic>> result = await db.rawQuery('''
      SELECT subject_type, COUNT(*) as count 
      FROM animes 
      GROUP BY subject_type
    ''');

    Map<String, int> counts = {'anime': 0, 'book': 0};

    for (var row in result) {
      String? type = row['subject_type'] as String?;
      if (type != null) {
        counts[type] = row['count'] as int;
      }
    }

    return counts;
  }

  /// 统计每个标签的使用次数（按使用次数降序排列）
  Future<List<Map<String, dynamic>>> getTagCounts() async {
    Database db = await database;
    return await db.rawQuery('''
      SELECT t.id, t.name, COUNT(at.anime_id) as count 
      FROM tags t 
      LEFT JOIN anime_tags at ON t.id = at.tag_id 
      GROUP BY t.id 
      ORDER BY count DESC
    ''');
  }

  // ============== 日志管理 (App Logs) ==============

  /// 插入日志
  Future<int> insertLog(String message, String? stackTrace) async {
    try {
      Database db = await database;
      return await db.insert('app_logs', {
        'message': message,
        'stack_trace': stackTrace ?? '',
        'timestamp': DateTime.now().toIso8601String(),
      });
    } catch (e) {
      logger.e("Insert log error: $e");
      return -1;
    }
  }

  /// 获取所有日志 (按时间倒序)
  Future<List<Map<String, dynamic>>> getLogs() async {
    try {
      Database db = await database;
      return await db.query('app_logs', orderBy: 'timestamp DESC');
    } catch (e) {
      logger.e("Query logs error: $e");
      return [];
    }
  }

  /// 清理旧日志 (24 小时前)
  Future<int> clearOldLogs() async {
    try {
      Database db = await database;
      final twentyFourHoursAgo = DateTime.now()
          .subtract(const Duration(hours: 24))
          .toIso8601String();
      return await db.delete(
        'app_logs',
        where: 'timestamp < ?',
        whereArgs: [twentyFourHoursAgo],
      );
    } catch (e) {
      logger.e("Clear old logs error: $e");
      return 0;
    }
  }
}
