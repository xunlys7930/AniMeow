import 'dart:io';

import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

/// 独立的诊断日志数据库。
///
/// 与追番主数据库分开保存，因此不会进入应用的本地/云端资料备份。
class DiagnosticLogDatabase {
  DiagnosticLogDatabase._();

  static final DiagnosticLogDatabase instance = DiagnosticLogDatabase._();

  static const String _fileName = 'animeow_diagnostic_logs.db';
  Database? _database;
  Future<Database>? _openingDatabase;

  Future<Database> get database {
    if (_database != null) return Future<Database>.value(_database!);

    final openingDatabase = _openingDatabase;
    if (openingDatabase != null) return openingDatabase;

    final future = _openDatabase();
    _openingDatabase = future;
    return future.then(
      (database) {
        _database = database;
        if (identical(_openingDatabase, future)) {
          _openingDatabase = null;
        }
        return database;
      },
      onError: (Object error, StackTrace stackTrace) {
        if (identical(_openingDatabase, future)) {
          _openingDatabase = null;
        }
        Error.throwWithStackTrace(error, stackTrace);
      },
    );
  }

  Future<Database> _openDatabase() async {
    if (Platform.isWindows || Platform.isLinux || Platform.isMacOS) {
      try {
        // 读取一次以判断全局 factory 是否已经初始化。
        databaseFactory;
      } on StateError {
        sqfliteFfiInit();
        databaseFactory = databaseFactoryFfi;
      }
    }

    final supportDirectory = await getApplicationSupportDirectory();
    final logDirectory = Directory(
      path.join(supportDirectory.path, 'diagnostics'),
    );
    await logDirectory.create(recursive: true);

    return openDatabase(
      path.join(logDirectory.path, _fileName),
      version: 1,
      onCreate: (db, _) => _createTable(db),
      onOpen: _createTable,
    );
  }

  Future<void> _createTable(Database db) async {
    await db.execute('''
      CREATE TABLE IF NOT EXISTS app_logs(
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        message TEXT,
        stack_trace TEXT,
        timestamp TEXT
      )
    ''');
  }
}
