import 'package:shared_preferences/shared_preferences.dart';

import '../db/database_helper.dart';
import '../models/fun_rating_tier_config.dart';

class FunRatingService {
  static const String defaultBoardTitle = '我的番剧趣味评级';
  static const int maxBoardTitleLength = 32;
  static const String _tierLabelsKey = 'fun_rating_tier_labels_v1';
  static const String _boardTitleKey = 'fun_rating_board_title_v1';

  final DatabaseHelper _database;

  FunRatingService({DatabaseHelper? database})
    : _database = database ?? DatabaseHelper();

  Future<List<Map<String, dynamic>>> loadAnimeEntries() async {
    final rows = await _database.queryAllAnimes();
    return rows
        .where((row) => (row['subject_type'] ?? 'anime') == 'anime')
        .map((row) => Map<String, dynamic>.from(row))
        .toList(growable: false);
  }

  Future<FunRatingTierConfig> loadTierConfig() async {
    final preferences = await SharedPreferences.getInstance();
    return FunRatingTierConfig.fromPersisted(
          preferences.getStringList(_tierLabelsKey),
        ) ??
        FunRatingTierConfig.letters;
  }

  Future<void> saveTierConfig(FunRatingTierConfig config) async {
    final preferences = await SharedPreferences.getInstance();
    await preferences.setStringList(_tierLabelsKey, config.toPersisted());
  }

  Future<String> loadBoardTitle() async {
    final preferences = await SharedPreferences.getInstance();
    final value = preferences.getString(_boardTitleKey)?.trim() ?? '';
    return value.isEmpty ? defaultBoardTitle : value;
  }

  Future<void> saveBoardTitle(String title) async {
    final normalized = title.trim();
    if (normalized.isEmpty || normalized.length > maxBoardTitleLength) {
      throw ArgumentError('榜单标题长度应为 1～$maxBoardTitleLength 个字符');
    }
    final preferences = await SharedPreferences.getInstance();
    await preferences.setString(_boardTitleKey, normalized);
  }

  Future<void> setTier(int animeId, String? tier) {
    return _database.updateFunRatingTier(animeId, tier);
  }

  Future<void> clearAllTiers() => _database.clearFunRatingTiers();
}
