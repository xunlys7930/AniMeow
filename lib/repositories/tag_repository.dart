import '../db/database_helper.dart';
import '../models/tag.dart';

class TagRepository {
  final DatabaseHelper _dbHelper;

  TagRepository({DatabaseHelper? dbHelper})
    : _dbHelper = dbHelper ?? DatabaseHelper();

  Future<int> insertTag(String name) => _dbHelper.insertTag(name);

  Future<int> updateTag(int id, String newName) =>
      _dbHelper.updateTag(id, newName);

  Future<void> deleteTag(int id) => _dbHelper.deleteTag(id);

  Future<List<Tag>> getAllTagEntities() async {
    final rows = await _dbHelper.getAllTags();
    return rows.map(Tag.fromMap).toList();
  }

  Future<List<Map<String, dynamic>>> getAllTags() => _dbHelper.getAllTags();

  Future<void> addTagToAnime(int animeId, int tagId) =>
      _dbHelper.addTagToAnime(animeId, tagId);

  Future<void> updateAnimeTags(int animeId, Set<int> tagIds) =>
      _dbHelper.updateAnimeTags(animeId, tagIds);

  Future<List<Tag>> getTagEntitiesByAnimeId(int animeId) async {
    final rows = await _dbHelper.getTagsByAnimeId(animeId);
    return rows.map(Tag.fromMap).toList();
  }

  Future<List<Map<String, dynamic>>> getTagsByAnimeId(int animeId) =>
      _dbHelper.getTagsByAnimeId(animeId);

  Future<void> batchAddTagToAnimes(List<int> animeIds, int tagId) =>
      _dbHelper.batchAddTagToAnimes(animeIds, tagId);

  Future<void> batchRemoveTagFromAnimes(List<int> animeIds, int tagId) =>
      _dbHelper.batchRemoveTagFromAnimes(animeIds, tagId);

  Future<List<Tag>> getTagCountEntities() async {
    final rows = await _dbHelper.getTagCounts();
    return rows.map(Tag.fromMap).toList();
  }

  Future<List<Map<String, dynamic>>> getTagCounts() => _dbHelper.getTagCounts();

  Stream<void> watchTags() => _dbHelper.watchTables({
    DatabaseHelper.tagsTable,
    DatabaseHelper.animeTagsTable,
  });
}
