import 'dart:convert';

import 'package:sqflite/sqflite.dart';

import '../database_helper.dart';
import '../../models/character_group_package.dart';

class CharacterDao {
  Future<Database> get _db => DatabaseHelper().database;

  Future<List<List<Map<String, dynamic>>>> findDuplicateCharacters() async {
    final db = await _db;

    final bgmDuplicates = await db.rawQuery('''
      SELECT c.*
      FROM ${DatabaseHelper.charactersTable} c
      WHERE c.bgm_id IS NOT NULL
        AND EXISTS (
          SELECT 1
          FROM ${DatabaseHelper.charactersTable} c2
          WHERE c2.id != c.id
            AND c2.bgm_id = c.bgm_id
        )
      ORDER BY c.bgm_id ASC, c.id ASC
    ''');

    final nameCnDuplicates = await db.rawQuery('''
      SELECT c.*
      FROM ${DatabaseHelper.charactersTable} c
      WHERE c.name_cn IS NOT NULL
        AND c.name_cn != ''
        AND EXISTS (
          SELECT 1
          FROM ${DatabaseHelper.charactersTable} c2
          WHERE c2.id != c.id
            AND LOWER(c2.name_cn) = LOWER(c.name_cn)
        )
      ORDER BY LOWER(c.name_cn) ASC, c.id ASC
    ''');

    final nameDuplicates = await db.rawQuery('''
      SELECT c.*
      FROM ${DatabaseHelper.charactersTable} c
      WHERE c.name_cn IS NULL OR c.name_cn = ''
        AND EXISTS (
          SELECT 1
          FROM ${DatabaseHelper.charactersTable} c2
          WHERE c2.id != c.id
            AND LOWER(c2.name) = LOWER(c.name)
        )
      ORDER BY LOWER(c.name) ASC, c.id ASC
    ''');

    final allDuplicates = Map<int, Map<String, dynamic>>();
    for (final row in bgmDuplicates) {
      allDuplicates[row['id'] as int] = row;
    }
    for (final row in nameCnDuplicates) {
      allDuplicates[row['id'] as int] = row;
    }
    for (final row in nameDuplicates) {
      allDuplicates[row['id'] as int] = row;
    }

    final grouped = <String, List<Map<String, dynamic>>>{};
    for (final row in allDuplicates.values) {
      String key;
      if (row['bgm_id'] != null) {
        key = 'bgm_${row['bgm_id']}';
      } else if (row['name_cn'] != null && (row['name_cn'] as String).isNotEmpty) {
        key = 'cn_${(row['name_cn'] as String).toLowerCase()}';
      } else {
        key = 'name_${(row['name'] as String).toLowerCase()}';
      }
      grouped.putIfAbsent(key, () => []).add(row);
    }

    return grouped.values.where((g) => g.length >= 2).toList();
  }

  Future<void> deleteCharacter(int characterId) async {
    final db = await _db;
    await db.transaction((txn) async {
      await txn.delete(
        DatabaseHelper.animeCharactersTable,
        where: 'character_id = ?',
        whereArgs: [characterId],
      );
      await txn.delete(
        DatabaseHelper.characterTagLinksTable,
        where: 'character_id = ?',
        whereArgs: [characterId],
      );
      await txn.delete(
        DatabaseHelper.characterRelationsTable,
        where: 'source_character_id = ? OR target_character_id = ?',
        whereArgs: [characterId, characterId],
      );
      await txn.delete(
        DatabaseHelper.characterGroupCharactersTable,
        where: 'character_id = ?',
        whereArgs: [characterId],
      );
      await txn.delete(
        DatabaseHelper.charactersTable,
        where: 'id = ?',
        whereArgs: [characterId],
      );
    });
  }

  Future<int> upsertCharacter(Map<String, dynamic> row) async {
    final db = await _db;
    final bgmId = row['bgm_id'];
    if (bgmId != null) {
      final existing = await db.query(
        DatabaseHelper.charactersTable,
        columns: ['id'],
        where: 'bgm_id = ?',
        whereArgs: [bgmId],
        limit: 1,
      );
      if (existing.isNotEmpty) {
        final id = existing.first['id'] as int;
        await db.update(
          DatabaseHelper.charactersTable,
          row,
          where: 'id = ?',
          whereArgs: [id],
        );
        return id;
      }
    }
    return db.insert(DatabaseHelper.charactersTable, row);
  }

  Future<int> updateCharacterPersonalReview({
    required int characterId,
    int? rating,
    String? review,
  }) async {
    final db = await _db;
    final trimmedReview = review?.trim();
    final normalizedRating = rating?.clamp(1, 10).toInt();

    return db.update(
      DatabaseHelper.charactersTable,
      {
        'rating': normalizedRating,
        'review': trimmedReview == null || trimmedReview.isEmpty
            ? null
            : trimmedReview,
      },
      where: 'id = ?',
      whereArgs: [characterId],
    );
  }

  Future<List<Map<String, dynamic>>> getAllCharacters({String? query}) async {
    final db = await _db;
    final keyword = query?.trim();
    final whereSql = keyword == null || keyword.isEmpty
        ? ''
        : '''
        WHERE c.name LIKE ?
           OR COALESCE(c.name_cn, '') LIKE ?
           OR EXISTS (
             SELECT 1
             FROM ${DatabaseHelper.characterTagLinksTable} search_ctl
             JOIN ${DatabaseHelper.characterTagsTable} search_ct
               ON search_ct.id = search_ctl.tag_id
             WHERE search_ctl.character_id = c.id
               AND search_ct.name LIKE ?
           )
        ''';
    final args = keyword == null || keyword.isEmpty
        ? <Object?>[]
        : <Object?>['%$keyword%', '%$keyword%', '%$keyword%'];

    return db.rawQuery('''
      SELECT c.*,
             COUNT(DISTINCT ac.anime_id) AS work_count,
             COUNT(DISTINCT cr.id) AS relation_count,
             COUNT(DISTINCT ctl.tag_id) AS tag_count
      FROM ${DatabaseHelper.charactersTable} c
      LEFT JOIN ${DatabaseHelper.animeCharactersTable} ac
        ON ac.character_id = c.id
      LEFT JOIN ${DatabaseHelper.characterRelationsTable} cr
        ON cr.source_character_id = c.id OR cr.target_character_id = c.id
      LEFT JOIN ${DatabaseHelper.characterTagLinksTable} ctl
        ON ctl.character_id = c.id
      $whereSql
      GROUP BY c.id
      ORDER BY COALESCE(NULLIF(c.name_cn, ''), c.name) COLLATE NOCASE ASC
      ''', args);
  }

  Future<int> createCharacterTag(String name) async {
    final db = await _db;
    final cleanName = name.trim();
    if (cleanName.isEmpty) return -1;

    final existing = await db.query(
      DatabaseHelper.characterTagsTable,
      columns: ['id'],
      where: 'LOWER(name) = LOWER(?)',
      whereArgs: [cleanName],
      limit: 1,
    );
    if (existing.isNotEmpty) return existing.first['id'] as int;

    return db.insert(DatabaseHelper.characterTagsTable, {
      'name': cleanName,
      'color': 0xFF6750A4,
      'created_at': DateTime.now().toIso8601String(),
    });
  }

  Future<List<Map<String, dynamic>>> getAllCharacterTags() async {
    final db = await _db;
    return db.rawQuery('''
      SELECT ct.*, COUNT(ctl.character_id) AS count
      FROM ${DatabaseHelper.characterTagsTable} ct
      LEFT JOIN ${DatabaseHelper.characterTagLinksTable} ctl
        ON ctl.tag_id = ct.id
      GROUP BY ct.id
      ORDER BY ct.name COLLATE NOCASE ASC
      ''');
  }

  Future<List<Map<String, dynamic>>> getCharacterTags(int characterId) async {
    final db = await _db;
    return db.rawQuery(
      '''
      SELECT ct.*
      FROM ${DatabaseHelper.characterTagLinksTable} ctl
      JOIN ${DatabaseHelper.characterTagsTable} ct ON ct.id = ctl.tag_id
      WHERE ctl.character_id = ?
      ORDER BY ct.name COLLATE NOCASE ASC
      ''',
      [characterId],
    );
  }

  Future<void> updateCharacterTags({
    required int characterId,
    required Set<int> tagIds,
  }) async {
    final db = await _db;
    await db.transaction((txn) async {
      await txn.delete(
        DatabaseHelper.characterTagLinksTable,
        where: 'character_id = ?',
        whereArgs: [characterId],
      );

      for (final tagId in tagIds) {
        await txn.insert(
          DatabaseHelper.characterTagLinksTable,
          {'character_id': characterId, 'tag_id': tagId},
          conflictAlgorithm: ConflictAlgorithm.ignore,
        );
      }
    });
  }

  Future<CharacterGroupImportResult> importCharacterGroupPackage(
    CharacterGroupPackage package, {
    String source = 'community',
  }) async {
    final db = await _db;
    final now = DateTime.now().toIso8601String();

    return db.transaction((txn) async {
      final characterIds = <int>[];
      final workIds = <int>[];
      var createdCharacterCount = 0;
      var createdWorkCount = 0;

      for (final snapshot in package.characters) {
        final result = await _upsertCharacterSnapshot(txn, snapshot, now);
        if (result.id > 0 && !characterIds.contains(result.id)) {
          characterIds.add(result.id);
          if (result.created) createdCharacterCount++;
        }
      }

      for (final snapshot in package.works) {
        final result = await _upsertWorkSnapshot(txn, snapshot, now);
        if (result.id > 0 && !workIds.contains(result.id)) {
          workIds.add(result.id);
          if (result.created) createdWorkCount++;
        }
      }

      if (characterIds.length < 2 || workIds.isEmpty) {
        throw ArgumentError('角色群组至少需要 2 个角色和 1 部作品');
      }

      final groupId = await _upsertImportedGroup(
        txn: txn,
        package: package,
        source: source,
        characterIds: characterIds,
        workIds: workIds,
        now: now,
      );

      return CharacterGroupImportResult(
        groupId: groupId,
        characterCount: characterIds.length,
        workCount: workIds.length,
        createdCharacterCount: createdCharacterCount,
        createdWorkCount: createdWorkCount,
      );
    });
  }

  Future<_ImportItemResult> _upsertCharacterSnapshot(
    Transaction txn,
    Map<String, dynamic> snapshot,
    String now,
  ) async {
    final bgmId = _asIntImport(
      snapshot['bgm_id'] ??
          snapshot['bgmId'] ??
          snapshot['bangumi_id'] ??
          snapshot['bangumiId'],
    );
    final name =
        _firstText([snapshot['name'], snapshot['original_name']]) ??
        _firstText([
          snapshot['name_cn'],
          snapshot['nameCn'],
          snapshot['display_name'],
        ]) ??
        '未命名角色';
    final nameCn = _firstText([
      snapshot['name_cn'],
      snapshot['nameCn'],
      snapshot['display_name'],
    ]);
    final imageUrl =
        _firstText([
          snapshot['image_url'],
          snapshot['imageUrl'],
          snapshot['avatar'],
        ]) ??
        _imageUrlFromImages(snapshot['images']);
    final summary = _firstText([snapshot['summary'], snapshot['description']]);
    final gender = _firstText([snapshot['gender']]);
    final birthYear = _asIntImport(
      snapshot['birth_year'] ?? snapshot['birthYear'],
    );
    final birthMonth = _asIntImport(
      snapshot['birth_mon'] ?? snapshot['birthMonth'],
    );
    final birthDay = _asIntImport(
      snapshot['birth_day'] ?? snapshot['birthDay'],
    );
    final bloodType = _firstText([
      snapshot['blood_type'],
      snapshot['bloodType'],
    ]);
    final infoboxJson = _jsonText(
      snapshot['infobox_json'] ?? snapshot['infobox'],
    );

    final row = <String, dynamic>{'name': name, 'updated_at': now};
    if (bgmId != null) row['bgm_id'] = bgmId;
    if (nameCn != null) row['name_cn'] = nameCn;
    if (imageUrl != null) row['image_url'] = imageUrl;
    if (summary != null) row['summary'] = summary;
    if (gender != null) row['gender'] = gender;
    if (birthYear != null) row['birth_year'] = birthYear;
    if (birthMonth != null) row['birth_mon'] = birthMonth;
    if (birthDay != null) row['birth_day'] = birthDay;
    if (bloodType != null) row['blood_type'] = bloodType;
    if (infoboxJson != null) row['infobox_json'] = infoboxJson;

    final existingId = await _findCharacterImportMatch(
      txn,
      bgmId: bgmId,
      name: name,
      nameCn: nameCn,
    );
    if (existingId != null) {
      await txn.update(
        DatabaseHelper.charactersTable,
        row,
        where: 'id = ?',
        whereArgs: [existingId],
      );
      return _ImportItemResult(existingId, created: false);
    }

    final id = await txn.insert(DatabaseHelper.charactersTable, row);
    return _ImportItemResult(id, created: true);
  }

  Future<int?> _findCharacterImportMatch(
    Transaction txn, {
    int? bgmId,
    required String name,
    String? nameCn,
  }) async {
    if (bgmId != null) {
      final rows = await txn.query(
        DatabaseHelper.charactersTable,
        columns: ['id'],
        where: 'bgm_id = ?',
        whereArgs: [bgmId],
        limit: 1,
      );
      if (rows.isNotEmpty) return rows.first['id'] as int;
    }

    final candidates = <String>{name};
    if (nameCn != null) candidates.add(nameCn);
    for (final candidate in candidates) {
      final rows = await txn.query(
        DatabaseHelper.charactersTable,
        columns: ['id'],
        where:
            'LOWER(name) = LOWER(?) OR LOWER(COALESCE(name_cn, \'\')) = LOWER(?)',
        whereArgs: [candidate, candidate],
        limit: 1,
      );
      if (rows.isNotEmpty) return rows.first['id'] as int;
    }
    return null;
  }

  Future<_ImportItemResult> _upsertWorkSnapshot(
    Transaction txn,
    Map<String, dynamic> snapshot,
    String now,
  ) async {
    final title =
        _firstText([
          snapshot['title'],
          snapshot['name_cn'],
          snapshot['nameCn'],
          snapshot['name'],
          snapshot['name_original'],
          snapshot['original_name'],
        ]) ??
        '未命名作品';
    final subjectType = _normalizeImportSubjectType(
      snapshot['subject_type'] ?? snapshot['subjectType'] ?? snapshot['type'],
    );
    final coverUrl =
        _firstText([
          snapshot['cover_url'],
          snapshot['coverUrl'],
          snapshot['image_url'],
          snapshot['imageUrl'],
        ]) ??
        _imageUrlFromImages(snapshot['images']);
    final airDate = _firstText([
      snapshot['air_date'],
      snapshot['airDate'],
      snapshot['date'],
    ]);
    final studio = _firstText([snapshot['studio']]);

    final existing = await txn.query(
      DatabaseHelper.animesTable,
      where: "title = ? AND COALESCE(subject_type, 'anime') = ?",
      whereArgs: [title, subjectType],
      limit: 1,
    );
    if (existing.isNotEmpty) {
      final row = existing.first;
      final updates = <String, dynamic>{};
      if (_firstText([row['cover_url']]) == null && coverUrl != null) {
        updates['cover_url'] = coverUrl;
      }
      if (_firstText([row['air_date']]) == null && airDate != null) {
        updates['air_date'] = airDate;
      }
      if (_firstText([row['studio']]) == null && studio != null) {
        updates['studio'] = studio;
      }
      final totalEpisodes = _asIntImport(
        snapshot['total_episodes'] ??
            snapshot['totalEpisodes'] ??
            snapshot['eps'],
      );
      if ((_asIntImport(row['total_episodes']) ?? 0) <= 0 &&
          totalEpisodes != null) {
        updates['total_episodes'] = totalEpisodes;
      }
      if (updates.isNotEmpty) {
        await txn.update(
          DatabaseHelper.animesTable,
          updates,
          where: 'id = ?',
          whereArgs: [row['id']],
        );
      }
      return _ImportItemResult(row['id'] as int, created: false);
    }

    final totalEpisodes =
        _asIntImport(
          snapshot['total_episodes'] ??
              snapshot['totalEpisodes'] ??
              snapshot['eps'],
        ) ??
        0;
    final id = await txn.insert(DatabaseHelper.animesTable, {
      'title': title,
      'cover_url': coverUrl,
      'status': '未看',
      'rating': null,
      'review': null,
      'created_at': now,
      'air_date': airDate,
      'studio': studio,
      'watched_episodes': 0,
      'total_episodes': totalEpisodes,
      'tv_episodes':
          _asIntImport(snapshot['tv_episodes'] ?? snapshot['tvEpisodes']) ??
          totalEpisodes,
      'sp_episodes':
          _asIntImport(snapshot['sp_episodes'] ?? snapshot['spEpisodes']) ?? 0,
      'subject_type': subjectType,
    });
    return _ImportItemResult(id, created: true);
  }

  Future<int> _upsertImportedGroup({
    required Transaction txn,
    required CharacterGroupPackage package,
    required String source,
    required List<int> characterIds,
    required List<int> workIds,
    required String now,
  }) async {
    final cleanSource = source.trim().isEmpty ? 'community' : source.trim();
    final cleanDescription = package.description?.trim();
    final extra = <String, dynamic>{...package.extra};
    if (package.shareCode != null) extra['share_code'] = package.shareCode;
    if (package.schema.isNotEmpty) extra['schema'] = package.schema;

    final groupRow = {
      'name': package.name.trim().isEmpty ? '未命名角色群组' : package.name.trim(),
      'description': cleanDescription == null || cleanDescription.isEmpty
          ? null
          : cleanDescription,
      'cover_url': package.coverUrl,
      'community_id': package.communityId,
      'source': cleanSource,
      'is_public': cleanSource == 'community' ? 1 : 0,
      'extra_json': extra.isEmpty ? null : jsonEncode(extra),
      'updated_at': now,
    };

    int? groupId;
    if (package.communityId != null) {
      final rows = await txn.query(
        DatabaseHelper.characterGroupsTable,
        columns: ['id'],
        where: 'community_id = ?',
        whereArgs: [package.communityId],
        limit: 1,
      );
      if (rows.isNotEmpty) groupId = rows.first['id'] as int;
    }

    if (groupId == null) {
      groupId = await txn.insert(DatabaseHelper.characterGroupsTable, {
        ...groupRow,
        'created_at': now,
      });
    } else {
      await txn.update(
        DatabaseHelper.characterGroupsTable,
        groupRow,
        where: 'id = ?',
        whereArgs: [groupId],
      );
    }

    await txn.delete(
      DatabaseHelper.characterGroupCharactersTable,
      where: 'group_id = ?',
      whereArgs: [groupId],
    );
    await txn.delete(
      DatabaseHelper.characterGroupWorksTable,
      where: 'group_id = ?',
      whereArgs: [groupId],
    );

    for (var index = 0; index < characterIds.length; index++) {
      await txn.insert(
        DatabaseHelper.characterGroupCharactersTable,
        {
          'group_id': groupId,
          'character_id': characterIds[index],
          'sort_order': index,
        },
        conflictAlgorithm: ConflictAlgorithm.replace,
      );
    }
    for (var index = 0; index < workIds.length; index++) {
      await txn.insert(
        DatabaseHelper.characterGroupWorksTable,
        {'group_id': groupId, 'anime_id': workIds[index], 'sort_order': index},
        conflictAlgorithm: ConflictAlgorithm.replace,
      );
    }

    return groupId;
  }

  Future<int> upsertCharacterGroup({
    int? groupId,
    required String name,
    String? description,
    required List<int> characterIds,
    required List<int> workIds,
  }) async {
    final cleanName = name.trim();
    if (cleanName.isEmpty) return 0;

    final db = await _db;
    final now = DateTime.now().toIso8601String();
    final cleanDescription = description?.trim();
    final groupRow = {
      'name': cleanName,
      'description': cleanDescription == null || cleanDescription.isEmpty
          ? null
          : cleanDescription,
      'source': 'local',
      'updated_at': now,
    };

    return db.transaction((txn) async {
      int savedGroupId;
      if (groupId == null) {
        savedGroupId = await txn.insert(DatabaseHelper.characterGroupsTable, {
          ...groupRow,
          'created_at': now,
        });
      } else {
        final updated = await txn.update(
          DatabaseHelper.characterGroupsTable,
          groupRow,
          where: 'id = ?',
          whereArgs: [groupId],
        );
        if (updated == 0) {
          savedGroupId = await txn.insert(DatabaseHelper.characterGroupsTable, {
            ...groupRow,
            'created_at': now,
          });
        } else {
          savedGroupId = groupId;
        }
      }

      await txn.delete(
        DatabaseHelper.characterGroupCharactersTable,
        where: 'group_id = ?',
        whereArgs: [savedGroupId],
      );
      await txn.delete(
        DatabaseHelper.characterGroupWorksTable,
        where: 'group_id = ?',
        whereArgs: [savedGroupId],
      );

      final uniqueCharacterIds = <int>[];
      final seenCharacters = <int>{};
      for (final characterId in characterIds) {
        if (characterId > 0 && seenCharacters.add(characterId)) {
          uniqueCharacterIds.add(characterId);
        }
      }
      for (var index = 0; index < uniqueCharacterIds.length; index++) {
        await txn.insert(
          DatabaseHelper.characterGroupCharactersTable,
          {
            'group_id': savedGroupId,
            'character_id': uniqueCharacterIds[index],
            'sort_order': index,
          },
          conflictAlgorithm: ConflictAlgorithm.replace,
        );
      }

      final uniqueWorkIds = <int>[];
      final seenWorks = <int>{};
      for (final workId in workIds) {
        if (workId > 0 && seenWorks.add(workId)) uniqueWorkIds.add(workId);
      }
      for (var index = 0; index < uniqueWorkIds.length; index++) {
        await txn.insert(
          DatabaseHelper.characterGroupWorksTable,
          {
            'group_id': savedGroupId,
            'anime_id': uniqueWorkIds[index],
            'sort_order': index,
          },
          conflictAlgorithm: ConflictAlgorithm.replace,
        );
      }

      return savedGroupId;
    });
  }

  Future<void> deleteCharacterGroup(int groupId) async {
    final db = await _db;
    await db.delete(
      DatabaseHelper.characterGroupsTable,
      where: 'id = ?',
      whereArgs: [groupId],
    );
  }

  Future<List<Map<String, dynamic>>> getCharacterGroups({String? query}) async {
    final db = await _db;
    final keyword = query?.trim();
    final args = <Object?>[];
    var whereSql = '';
    if (keyword != null && keyword.isNotEmpty) {
      whereSql = '''
        WHERE g.name LIKE ? OR COALESCE(g.description, '') LIKE ?
      ''';
      args.addAll(['%$keyword%', '%$keyword%']);
    }

    return db.rawQuery('''
      SELECT g.*,
             COUNT(DISTINCT cgc.character_id) AS character_count,
             COUNT(DISTINCT cgw.anime_id) AS work_count
      FROM ${DatabaseHelper.characterGroupsTable} g
      LEFT JOIN ${DatabaseHelper.characterGroupCharactersTable} cgc
        ON cgc.group_id = g.id
      LEFT JOIN ${DatabaseHelper.characterGroupWorksTable} cgw
        ON cgw.group_id = g.id
      $whereSql
      GROUP BY g.id
      ORDER BY COALESCE(g.updated_at, g.created_at) DESC, g.id DESC
      ''', args);
  }

  Future<List<Map<String, dynamic>>> getCharacterGroupCharacters(
    int groupId,
  ) async {
    final db = await _db;
    return db.rawQuery(
      '''
      SELECT c.*, cgc.role_name, cgc.sort_order,
             COUNT(DISTINCT ac.anime_id) AS work_count,
             COUNT(DISTINCT cr.id) AS relation_count,
             COUNT(DISTINCT ctl.tag_id) AS tag_count
      FROM ${DatabaseHelper.characterGroupCharactersTable} cgc
      JOIN ${DatabaseHelper.charactersTable} c ON c.id = cgc.character_id
      LEFT JOIN ${DatabaseHelper.animeCharactersTable} ac
        ON ac.character_id = c.id
      LEFT JOIN ${DatabaseHelper.characterRelationsTable} cr
        ON cr.source_character_id = c.id OR cr.target_character_id = c.id
      LEFT JOIN ${DatabaseHelper.characterTagLinksTable} ctl
        ON ctl.character_id = c.id
      WHERE cgc.group_id = ?
      GROUP BY c.id, cgc.role_name, cgc.sort_order
      ORDER BY cgc.sort_order ASC,
               COALESCE(NULLIF(c.name_cn, ''), c.name) COLLATE NOCASE ASC
      ''',
      [groupId],
    );
  }

  Future<List<Map<String, dynamic>>> getCharacterGroupWorks(int groupId) async {
    final db = await _db;
    return db.rawQuery(
      '''
      SELECT a.*, cgw.sort_order
      FROM ${DatabaseHelper.characterGroupWorksTable} cgw
      JOIN ${DatabaseHelper.animesTable} a ON a.id = cgw.anime_id
      WHERE cgw.group_id = ?
      ORDER BY cgw.sort_order ASC, a.title COLLATE NOCASE ASC
      ''',
      [groupId],
    );
  }

  Future<CharacterGroupPackage> exportCharacterGroupPackage(int groupId) async {
    final db = await _db;

    final groupRows = await db.query(
      DatabaseHelper.characterGroupsTable,
      where: 'id = ?',
      whereArgs: [groupId],
    );
    if (groupRows.isEmpty) {
      throw Exception('角色群组不存在');
    }
    final group = groupRows.first;

    final characters = await db.rawQuery(
      '''
      SELECT c.*, cgc.role_name
      FROM ${DatabaseHelper.characterGroupCharactersTable} cgc
      JOIN ${DatabaseHelper.charactersTable} c ON c.id = cgc.character_id
      WHERE cgc.group_id = ?
      ORDER BY cgc.sort_order ASC
      ''',
      [groupId],
    );

    final works = await db.rawQuery(
      '''
      SELECT a.*, cgw.sort_order
      FROM ${DatabaseHelper.characterGroupWorksTable} cgw
      JOIN ${DatabaseHelper.animesTable} a ON a.id = cgw.anime_id
      WHERE cgw.group_id = ?
      ORDER BY cgw.sort_order ASC
      ''',
      [groupId],
    );

    final characterSnapshots = characters.map((c) {
      final snapshot = Map<String, dynamic>.from(c);
      snapshot.remove('id');
      return snapshot;
    }).toList();

    final workSnapshots = works.map((w) {
      final snapshot = Map<String, dynamic>.from(w);
      snapshot.remove('id');
      return snapshot;
    }).toList();

    return CharacterGroupPackage(
      name: (group['name'] ?? '未命名角色群组').toString(),
      description: group['description']?.toString(),
      coverUrl: group['cover_url']?.toString(),
      communityId: group['community_id']?.toString(),
      shareCode: group['share_code']?.toString(),
      source: group['source']?.toString(),
      characters: characterSnapshots,
      works: workSnapshots,
      extra: const {},
    );
  }

  Future<List<Map<String, dynamic>>> searchGroupableWorks({
    String? query,
    String subjectType = 'all',
    int limit = 120,
  }) async {
    final db = await _db;
    final args = <Object?>[];
    var sql =
        '''
      SELECT a.*
      FROM ${DatabaseHelper.animesTable} a
      WHERE 1 = 1
    ''';

    if (subjectType != 'all') {
      sql += " AND COALESCE(a.subject_type, 'anime') = ?";
      args.add(subjectType);
    }

    final keyword = query?.trim();
    if (keyword != null && keyword.isNotEmpty) {
      sql += ' AND a.title LIKE ?';
      args.add('%$keyword%');
    }

    sql += ' ORDER BY a.id DESC LIMIT ?';
    args.add(limit);

    return db.rawQuery(sql, args);
  }

  /// 查询指定角色 ID 列表关联的作品（anime）ID 集合
  Future<Set<int>> getAnimeIdsByCharacterIds(List<int> characterIds) async {
    if (characterIds.isEmpty) return const {};
    final db = await _db;
    final placeholders = List.filled(characterIds.length, '?').join(',');
    final rows = await db.rawQuery(
      'SELECT DISTINCT anime_id FROM ${DatabaseHelper.animeCharactersTable} '
      'WHERE character_id IN ($placeholders)',
      characterIds,
    );
    return rows
        .map((row) => row['anime_id'] as int?)
        .whereType<int>()
        .toSet();
  }

  Future<List<Map<String, dynamic>>> getRelationCandidates({
    required int sourceCharacterId,
    String? query,
    int limit = 80,
  }) async {
    final db = await _db;
    final args = <Object?>[sourceCharacterId, sourceCharacterId];
    final whereParts = <String>['c.id != ?'];

    final keyword = query?.trim();
    if (keyword != null && keyword.isNotEmpty) {
      whereParts.add('''
        (c.name LIKE ? OR COALESCE(c.name_cn, '') LIKE ?)
      ''');
      args.addAll(['%$keyword%', '%$keyword%']);
    }

    final whereSql = 'WHERE ${whereParts.join(' AND ')}';
    args.add(limit);

    return db.rawQuery('''
      SELECT c.*,
             COUNT(DISTINCT all_ac.anime_id) AS work_count,
             COUNT(DISTINCT shared_ac.anime_id) AS shared_work_count,
             COUNT(DISTINCT cr.id) AS relation_count
      FROM ${DatabaseHelper.charactersTable} c
      LEFT JOIN ${DatabaseHelper.animeCharactersTable} all_ac
        ON all_ac.character_id = c.id
      LEFT JOIN ${DatabaseHelper.animeCharactersTable} shared_ac
        ON shared_ac.character_id = c.id
       AND shared_ac.anime_id IN (
          SELECT anime_id
          FROM ${DatabaseHelper.animeCharactersTable}
          WHERE character_id = ?
       )
      LEFT JOIN ${DatabaseHelper.characterRelationsTable} cr
        ON cr.source_character_id = c.id OR cr.target_character_id = c.id
      $whereSql
      GROUP BY c.id
      ORDER BY shared_work_count DESC,
               work_count DESC,
               COALESCE(NULLIF(c.name_cn, ''), c.name) COLLATE NOCASE ASC
      LIMIT ?
      ''', args);
  }

  Future<Map<String, dynamic>?> getCharacterById(int characterId) async {
    final rows = await getAllCharacters();
    for (final row in rows) {
      if (row['id'] == characterId) return row;
    }
    return null;
  }

  Future<void> addCharacterToAnime({
    required int animeId,
    required int characterId,
    String? roleName,
  }) async {
    final db = await _db;
    await db.insert(
      DatabaseHelper.animeCharactersTable,
      {
        'anime_id': animeId,
        'character_id': characterId,
        'role_name': roleName,
        'sort_order': DateTime.now().millisecondsSinceEpoch,
      },
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  Future<void> removeCharacterFromAnime({
    required int animeId,
    required int characterId,
  }) async {
    final db = await _db;
    await db.delete(
      DatabaseHelper.animeCharactersTable,
      where: 'anime_id = ? AND character_id = ?',
      whereArgs: [animeId, characterId],
    );
  }

  Future<List<Map<String, dynamic>>> getCharactersByAnimeId(int animeId) async {
    final db = await _db;
    return db.rawQuery(
      '''
      SELECT c.*, ac.role_name, ac.sort_order
      FROM ${DatabaseHelper.animeCharactersTable} ac
      JOIN ${DatabaseHelper.charactersTable} c ON c.id = ac.character_id
      WHERE ac.anime_id = ?
      ORDER BY ac.sort_order ASC, c.name_cn ASC, c.name ASC
      ''',
      [animeId],
    );
  }

  Future<List<Map<String, dynamic>>> getWorksByCharacterId(
    int characterId,
  ) async {
    final db = await _db;
    return db.rawQuery(
      '''
      SELECT a.*, ac.role_name, ac.sort_order
      FROM ${DatabaseHelper.animeCharactersTable} ac
      JOIN ${DatabaseHelper.animesTable} a ON a.id = ac.anime_id
      WHERE ac.character_id = ?
      ORDER BY ac.sort_order ASC, a.title COLLATE NOCASE ASC
      ''',
      [characterId],
    );
  }

  Future<List<Map<String, dynamic>>> getWorksLinkedToCharacterMissingFrom({
    required int fromCharacterId,
    required int missingFromCharacterId,
  }) async {
    final db = await _db;
    return db.rawQuery(
      '''
      SELECT a.*, ac.role_name, ac.sort_order
      FROM ${DatabaseHelper.animeCharactersTable} ac
      JOIN ${DatabaseHelper.animesTable} a ON a.id = ac.anime_id
      WHERE ac.character_id = ?
        AND ac.anime_id NOT IN (
          SELECT anime_id
          FROM ${DatabaseHelper.animeCharactersTable}
          WHERE character_id = ?
        )
      ORDER BY ac.sort_order ASC, a.title COLLATE NOCASE ASC
      ''',
      [fromCharacterId, missingFromCharacterId],
    );
  }

  Future<List<Map<String, dynamic>>> searchLinkableWorks({
    required int characterId,
    String? query,
    String subjectType = 'all',
    int limit = 60,
  }) async {
    final db = await _db;
    final args = <Object?>[characterId];
    var sql =
        '''
      SELECT a.*
      FROM ${DatabaseHelper.animesTable} a
      WHERE a.id NOT IN (
        SELECT anime_id
        FROM ${DatabaseHelper.animeCharactersTable}
        WHERE character_id = ?
      )
    ''';

    if (subjectType != 'all') {
      sql += " AND COALESCE(a.subject_type, 'anime') = ?";
      args.add(subjectType);
    }

    final keyword = query?.trim();
    if (keyword != null && keyword.isNotEmpty) {
      sql += ' AND a.title LIKE ?';
      args.add('%$keyword%');
    }

    sql += ' ORDER BY a.id DESC LIMIT ?';
    args.add(limit);

    return db.rawQuery(sql, args);
  }

  Future<int> upsertCharacterRelation({
    required int sourceCharacterId,
    required int targetCharacterId,
    String relationType = '关联',
    String? note,
    int strength = 3,
  }) async {
    if (sourceCharacterId == targetCharacterId) return 0;

    final db = await _db;
    final firstId = sourceCharacterId < targetCharacterId
        ? sourceCharacterId
        : targetCharacterId;
    final secondId = sourceCharacterId < targetCharacterId
        ? targetCharacterId
        : sourceCharacterId;
    final now = DateTime.now().toIso8601String();
    final normalizedStrength = strength.clamp(1, 5).toInt();

    final existing = await db.query(
      DatabaseHelper.characterRelationsTable,
      columns: ['id'],
      where: 'source_character_id = ? AND target_character_id = ?',
      whereArgs: [firstId, secondId],
      limit: 1,
    );

    final row = {
      'source_character_id': firstId,
      'target_character_id': secondId,
      'relation_type': relationType.trim().isEmpty ? '关联' : relationType.trim(),
      'note': note?.trim(),
      'strength': normalizedStrength,
      'updated_at': now,
    };

    if (existing.isNotEmpty) {
      final id = existing.first['id'] as int;
      await db.update(
        DatabaseHelper.characterRelationsTable,
        row,
        where: 'id = ?',
        whereArgs: [id],
      );
      return id;
    }

    return db.insert(DatabaseHelper.characterRelationsTable, {
      ...row,
      'created_at': now,
    });
  }

  Future<void> deleteCharacterRelation(int relationId) async {
    final db = await _db;
    await db.delete(
      DatabaseHelper.characterRelationsTable,
      where: 'id = ?',
      whereArgs: [relationId],
    );
  }

  Future<List<Map<String, dynamic>>> getCharacterRelations(
    int characterId,
  ) async {
    final db = await _db;
    return db.rawQuery(
      '''
      SELECT cr.*,
             other.id AS related_character_id,
             other.bgm_id AS related_bgm_id,
             other.name AS related_name,
             other.name_cn AS related_name_cn,
             other.image_url AS related_image_url,
             other.gender AS related_gender,
             other.summary AS related_summary
      FROM ${DatabaseHelper.characterRelationsTable} cr
      JOIN ${DatabaseHelper.charactersTable} other
        ON other.id = CASE
          WHEN cr.source_character_id = ? THEN cr.target_character_id
          ELSE cr.source_character_id
        END
      WHERE cr.source_character_id = ? OR cr.target_character_id = ?
      ORDER BY cr.updated_at DESC, cr.id DESC
      ''',
      [characterId, characterId, characterId],
    );
  }

  Future<void> updateCharacterGroupCommunityInfo({
    required int groupId,
    String? communityId,
    String? shareCode,
  }) async {
    final db = await _db;
    final now = DateTime.now().toIso8601String();
    final updates = <String, dynamic>{'updated_at': now};
    // 当传入 null 时，清除数据库中的对应字段
    if (communityId != null) {
      updates['community_id'] = communityId;
    } else {
      updates['community_id'] = null;
    }
    if (shareCode != null) {
      updates['share_code'] = shareCode;
    } else {
      updates['share_code'] = null;
    }
    await db.update(
      DatabaseHelper.characterGroupsTable,
      updates,
      where: 'id = ?',
      whereArgs: [groupId],
    );
  }
}

class _ImportItemResult {
  final int id;
  final bool created;

  const _ImportItemResult(this.id, {required this.created});
}

String? _firstText(Iterable<dynamic> values) {
  for (final value in values) {
    final text = value?.toString().trim();
    if (text != null && text.isNotEmpty && text != 'null') return text;
  }
  return null;
}

String? _imageUrlFromImages(dynamic images) {
  if (images is! Map) return null;
  for (final key in const ['medium', 'large', 'small', 'grid', 'common']) {
    final text = _firstText([images[key]]);
    if (text != null) return text;
  }
  return null;
}

String? _jsonText(dynamic value) {
  if (value == null) return null;
  if (value is String) return value.trim().isEmpty ? null : value;
  return jsonEncode(value);
}

int? _asIntImport(dynamic value) {
  if (value == null) return null;
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value.toString());
}

String _normalizeImportSubjectType(dynamic value) {
  final text = value?.toString().trim().toLowerCase();
  if (text == 'book' || text == 'novel' || text == '小说' || text == '书籍') {
    return 'book';
  }
  return 'anime';
}
