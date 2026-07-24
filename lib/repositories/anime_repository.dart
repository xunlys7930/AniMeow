import '../api/bangumi_service.dart';
import '../db/database_helper.dart';
import '../models/anime.dart';

class AnimeRepository {
  final DatabaseHelper _dbHelper;

  AnimeRepository({DatabaseHelper? dbHelper})
    : _dbHelper = dbHelper ?? DatabaseHelper();

  Future<int> insert(Anime anime) =>
      _dbHelper.insertAnime(anime.toMap(includeId: false));

  Future<int> insertAnime(Map<String, dynamic> row) =>
      _dbHelper.insertAnime(row);

  Future<int> update(Anime anime) => _dbHelper.updateAnime(anime.toMap());

  Future<int> updateAnime(Map<String, dynamic> row) =>
      _dbHelper.updateAnime(row);

  Future<int> deleteAnime(int id) => _dbHelper.deleteAnime(id);

  Future<List<Anime>> queryAnimes() async {
    final rows = await _dbHelper.queryAllAnimes();
    return rows.map(Anime.fromMap).toList();
  }

  Future<List<Map<String, dynamic>>> queryAllAnimes() =>
      _dbHelper.queryAllAnimes();

  Future<Anime?> getById(int id) async {
    final row = await _dbHelper.getAnimeById(id);
    return row == null ? null : Anime.fromMap(row);
  }

  Future<Map<String, dynamic>?> getAnimeById(int id) =>
      _dbHelper.getAnimeById(id);

  Future<Anime?> getByTitle(String title) async {
    final row = await _dbHelper.getAnimeByTitle(title);
    return row == null ? null : Anime.fromMap(row);
  }

  Future<Map<String, dynamic>?> getAnimeByTitle(String title) =>
      _dbHelper.getAnimeByTitle(title);

  Future<List<Anime>> getWithoutSeries() async {
    final rows = await _dbHelper.getAnimesWithoutSeries();
    return rows.map(Anime.fromMap).toList();
  }

  Future<List<Map<String, dynamic>>> getAnimesWithoutSeries() =>
      _dbHelper.getAnimesWithoutSeries();

  Future<void> updateAnimeSeries(int animeId, int? seriesId) =>
      _dbHelper.updateAnimeSeries(animeId, seriesId);

  Future<void> batchUpdateStatus(List<int> animeIds, String newStatus) =>
      _dbHelper.batchUpdateStatus(animeIds, newStatus);

  Future<void> batchUpdateAnimesSeries(List<int> animeIds, int seriesId) =>
      _dbHelper.batchUpdateAnimesSeries(animeIds, seriesId);

  Future<List<String>> getAllStudios() => _dbHelper.getAllStudios();

  Future<List<Anime>> getReminderAnimes() async {
    final rows = await _dbHelper.getAnimesWithReminders();
    return rows.map(Anime.fromMap).toList();
  }

  Future<List<Map<String, dynamic>>> getAnimesWithReminders() =>
      _dbHelper.getAnimesWithReminders();

  Future<List<Map<String, dynamic>>> searchAnimes({
    String? query,
    List<int>? tagIds,
    String? status,
    String? subjectType,
    List<String>? years,
    bool isAndMode = false,
    String sortOption = 'a.id DESC',
  }) => _dbHelper.searchAnimes(
    query: query,
    tagIds: tagIds,
    status: status,
    subjectType: subjectType,
    years: years,
    isAndMode: isAndMode,
    sortOption: sortOption,
  );

  Future<List<Anime>> searchAnimeEntities({
    String? query,
    List<int>? tagIds,
    String? status,
    String? subjectType,
    List<String>? years,
    bool isAndMode = false,
    String sortOption = 'a.id DESC',
  }) async {
    final rows = await searchAnimes(
      query: query,
      tagIds: tagIds,
      status: status,
      subjectType: subjectType,
      years: years,
      isAndMode: isAndMode,
      sortOption: sortOption,
    );
    return rows.map(Anime.fromMap).toList();
  }

  Stream<List<Anime>> watchAllAnimes() async* {
    yield await queryAnimes();
    await for (final _ in _dbHelper.watchTable(DatabaseHelper.animesTable)) {
      yield await queryAnimes();
    }
  }

  Stream<List<Map<String, dynamic>>> watchAllAnimeMaps() async* {
    yield await queryAllAnimes();
    await for (final _ in _dbHelper.watchTables({
      DatabaseHelper.animesTable,
      DatabaseHelper.tagsTable,
      DatabaseHelper.animeTagsTable,
      DatabaseHelper.seriesTable,
    })) {
      yield await queryAllAnimes();
    }
  }

  Future<Map<String, int>> getStatusCounts() => _dbHelper.getStatusCounts();

  Future<Map<String, int>> getSubjectTypeCounts() =>
      _dbHelper.getSubjectTypeCounts();

  Future<Map<String, int>> syncCovers(List<int> animeIds) async {
    int success = 0;
    int skip = 0;
    int fail = 0;

    for (int id in animeIds) {
      final anime = await getAnimeById(id);
      if (anime == null) continue;

      final String? coverUrl = anime['cover_url'];
      final String title = anime['title'] ?? '';

      if (coverUrl != null && coverUrl.startsWith('http')) {
        try {
          await BangumiService.updateServerCover(title, coverUrl);
          success++;
        } catch (e) {
          fail++;
        }
      } else {
        skip++;
      }
    }

    return {'success': success, 'skip': skip, 'fail': fail};
  }
}
