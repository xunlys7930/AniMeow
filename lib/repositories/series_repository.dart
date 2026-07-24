import '../db/database_helper.dart';
import '../models/anime.dart';
import '../models/series.dart';

class SeriesRepository {
  final DatabaseHelper _dbHelper;

  SeriesRepository({DatabaseHelper? dbHelper})
    : _dbHelper = dbHelper ?? DatabaseHelper();

  Future<int> createSeries(String name, {String? description}) =>
      _dbHelper.createSeries(name, description: description);

  Future<Series?> getSeriesById(int id) async {
    final row = await _dbHelper.getSeriesById(id);
    return row == null ? null : Series.fromMap(row);
  }

  Future<List<Series>> getAllSeries() async {
    final rows = await _dbHelper.getAllSeries();
    return rows.map(Series.fromMap).toList();
  }

  Future<List<Map<String, dynamic>>> getAllSeriesMaps() =>
      _dbHelper.getAllSeries();

  Future<int> updateSeries(int id, Map<String, dynamic> row) =>
      _dbHelper.updateSeries(id, row);

  Future<void> deleteSeries(int id) => _dbHelper.deleteSeries(id);

  Future<List<Anime>> getAnimesInSeries(int seriesId) async {
    final rows = await _dbHelper.getAnimesInSeries(seriesId);
    return rows.map(Anime.fromMap).toList();
  }

  Future<List<Map<String, dynamic>>> getAnimeMapsInSeries(int seriesId) =>
      _dbHelper.getAnimesInSeries(seriesId);

  Future<List<Series>> searchSeries(String query) async {
    final rows = await _dbHelper.searchSeries(query);
    return rows.map(Series.fromMap).toList();
  }

  Future<List<Map<String, dynamic>>> searchSeriesMaps(String query) =>
      _dbHelper.searchSeries(query);
}
