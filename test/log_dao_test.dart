import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:anime_tracker/db/dao/log_dao.dart';

void main() {
  late Database db;
  late LogDao dao;

  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
  });

  setUp(() async {
    db = await databaseFactory.openDatabase(inMemoryDatabasePath);
    await db.execute('''
      CREATE TABLE app_logs(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        message TEXT,
        stack_trace TEXT,
        timestamp TEXT
      )
    ''');
    dao = LogDao(databaseProvider: () async => db);
  });

  tearDown(() async {
    await db.close();
  });

  test('batch insert keeps newest rows and supports clearing', () async {
    await dao.insertLogs([
      {
        'message': '[操作] first',
        'stack_trace': null,
        'timestamp': '2026-07-25T10:00:00.000',
      },
      {
        'message': '[操作] second',
        'stack_trace': '{"columns":4}',
        'timestamp': '2026-07-25T10:01:00.000',
      },
      {
        'message': '[错误] third',
        'stack_trace': 'stack',
        'timestamp': '2026-07-25T10:02:00.000',
      },
    ]);

    var rows = await dao.getLogs(limit: 2);
    expect(rows.map((row) => row['message']), ['[错误] third', '[操作] second']);

    await dao.pruneToLimit(2);
    rows = await dao.getLogs();
    expect(rows.length, 2);
    expect(rows.any((row) => row['message'] == '[操作] first'), isFalse);

    await dao.clearLogs();
    expect(await dao.getLogs(), isEmpty);
  });

  test('old logs are removed after fourteen days', () async {
    final oldTimestamp = DateTime.now()
        .subtract(const Duration(days: 15))
        .toIso8601String();
    final freshTimestamp = DateTime.now().toIso8601String();

    await dao.insertLogs([
      {'message': '[操作] old', 'stack_trace': null, 'timestamp': oldTimestamp},
      {
        'message': '[操作] fresh',
        'stack_trace': null,
        'timestamp': freshTimestamp,
      },
    ]);

    expect(await dao.clearOldLogs(), 1);
    final rows = await dao.getLogs();
    expect(rows.single['message'], '[操作] fresh');
  });
}
