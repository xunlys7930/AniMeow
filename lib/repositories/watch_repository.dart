import '../db/database_helper.dart';

class WatchRepository {
  final DatabaseHelper _dbHelper;

  WatchRepository({DatabaseHelper? dbHelper})
    : _dbHelper = dbHelper ?? DatabaseHelper();

  Future<List<Map<String, dynamic>>> getAllStatuses() =>
      _dbHelper.getAllStatuses();

  Future<int> insertStatus(String name, int color) =>
      _dbHelper.insertStatus(name, color);

  Future<int> updateStatus(int id, String name, int color) =>
      _dbHelper.updateStatus(id, name, color);

  Future<int> deleteStatus(int id) => _dbHelper.deleteStatus(id);

  Future<void> updateStatusesOrder(List<int> ids) =>
      _dbHelper.updateStatusesOrder(ids);

  Future<List<Map<String, dynamic>>> getAllWatchRecords() =>
      _dbHelper.getAllWatchRecords();

  Future<int> insertWatchRecord({
    required int animeId,
    required int episode,
    String status = 'watched',
    String? date,
  }) => _dbHelper.insertWatchRecord(
    animeId: animeId,
    episode: episode,
    status: status,
    date: date,
  );

  Future<int> deleteWatchRecord(int id) => _dbHelper.deleteWatchRecord(id);

  Stream<void> watchCalendarData() => _dbHelper.watchTables({
    DatabaseHelper.animesTable,
    DatabaseHelper.watchRecordsTable,
  });
}
