import '../db/database_helper.dart';
import '../models/bangumi_character.dart';
import '../models/character_group_package.dart';

class CharacterRepository {
  final DatabaseHelper _dbHelper;

  CharacterRepository({DatabaseHelper? dbHelper})
    : _dbHelper = dbHelper ?? DatabaseHelper();

  Future<int> upsertBangumiCharacter(BangumiCharacter character) =>
      _dbHelper.upsertCharacter(character.toDbMap());

  Future<int> addBangumiCharacterToAnime({
    required int animeId,
    required BangumiCharacter character,
    String? roleName,
  }) async {
    final characterId = await upsertBangumiCharacter(character);
    await _dbHelper.addCharacterToAnime(
      animeId: animeId,
      characterId: characterId,
      roleName: roleName,
    );
    return characterId;
  }

  Future<void> addCharacterToWork({
    required int workId,
    required int characterId,
    String? roleName,
  }) => _dbHelper.addCharacterToAnime(
    animeId: workId,
    characterId: characterId,
    roleName: roleName,
  );

  Future<void> removeCharacterFromAnime({
    required int animeId,
    required int characterId,
  }) => _dbHelper.removeCharacterFromAnime(
    animeId: animeId,
    characterId: characterId,
  );

  Future<void> removeCharacterFromWork({
    required int workId,
    required int characterId,
  }) => removeCharacterFromAnime(animeId: workId, characterId: characterId);

  Future<List<Map<String, dynamic>>> getCharactersByAnimeId(int animeId) =>
      _dbHelper.getCharactersByAnimeId(animeId);

  Future<List<Map<String, dynamic>>> getAllCharacters({String? query}) =>
      _dbHelper.getAllCharacters(query: query);

  Future<int> createCharacterTag(String name) =>
      _dbHelper.createCharacterTag(name);

  Future<List<Map<String, dynamic>>> getAllCharacterTags() =>
      _dbHelper.getAllCharacterTags();

  Future<List<Map<String, dynamic>>> getCharacterTags(int characterId) =>
      _dbHelper.getCharacterTags(characterId);

  Future<void> updateCharacterTags({
    required int characterId,
    required Set<int> tagIds,
  }) => _dbHelper.updateCharacterTags(characterId: characterId, tagIds: tagIds);

  Future<int> upsertCharacterGroup({
    int? groupId,
    required String name,
    String? description,
    required List<int> characterIds,
    required List<int> workIds,
  }) => _dbHelper.upsertCharacterGroup(
    groupId: groupId,
    name: name,
    description: description,
    characterIds: characterIds,
    workIds: workIds,
  );

  Future<CharacterGroupImportResult> importCharacterGroupPackage(
    CharacterGroupPackage package, {
    String source = 'community',
  }) => _dbHelper.importCharacterGroupPackage(package, source: source);

  Future<void> deleteCharacterGroup(int groupId) =>
      _dbHelper.deleteCharacterGroup(groupId);

  Future<List<Map<String, dynamic>>> getCharacterGroups({String? query}) =>
      _dbHelper.getCharacterGroups(query: query);

  Future<List<Map<String, dynamic>>> getCharacterGroupCharacters(int groupId) =>
      _dbHelper.getCharacterGroupCharacters(groupId);

  Future<List<Map<String, dynamic>>> getCharacterGroupWorks(int groupId) =>
      _dbHelper.getCharacterGroupWorks(groupId);

  Future<CharacterGroupPackage> exportCharacterGroupPackage(int groupId) =>
      _dbHelper.exportCharacterGroupPackage(groupId);

  Future<List<Map<String, dynamic>>> searchGroupableWorks({
    String? query,
    String subjectType = 'all',
    int limit = 120,
  }) => _dbHelper.searchGroupableWorks(
    query: query,
    subjectType: subjectType,
    limit: limit,
  );

  Future<Set<int>> getAnimeIdsByCharacterIds(List<int> characterIds) =>
      _dbHelper.getAnimeIdsByCharacterIds(characterIds);

  Future<List<Map<String, dynamic>>> getRelationCandidates({
    required int sourceCharacterId,
    String? query,
    int limit = 80,
  }) => _dbHelper.getRelationCandidates(
    sourceCharacterId: sourceCharacterId,
    query: query,
    limit: limit,
  );

  Future<Map<String, dynamic>?> getCharacterById(int characterId) =>
      _dbHelper.getCharacterById(characterId);

  Future<int> updateCharacterPersonalReview({
    required int characterId,
    int? rating,
    String? review,
  }) => _dbHelper.updateCharacterPersonalReview(
    characterId: characterId,
    rating: rating,
    review: review,
  );

  Future<List<Map<String, dynamic>>> getWorksByCharacterId(int characterId) =>
      _dbHelper.getWorksByCharacterId(characterId);

  Future<List<Map<String, dynamic>>> getWorksLinkedToCharacterMissingFrom({
    required int fromCharacterId,
    required int missingFromCharacterId,
  }) => _dbHelper.getWorksLinkedToCharacterMissingFrom(
    fromCharacterId: fromCharacterId,
    missingFromCharacterId: missingFromCharacterId,
  );

  Future<List<Map<String, dynamic>>> searchLinkableWorks({
    required int characterId,
    String? query,
    String subjectType = 'all',
    int limit = 60,
  }) => _dbHelper.searchLinkableWorks(
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
  }) => _dbHelper.upsertCharacterRelation(
    sourceCharacterId: sourceCharacterId,
    targetCharacterId: targetCharacterId,
    relationType: relationType,
    note: note,
    strength: strength,
  );

  Future<void> deleteCharacterRelation(int relationId) =>
      _dbHelper.deleteCharacterRelation(relationId);

  Future<List<Map<String, dynamic>>> getCharacterRelations(int characterId) =>
      _dbHelper.getCharacterRelations(characterId);

  Future<List<List<Map<String, dynamic>>>> findDuplicateCharacters() =>
      _dbHelper.findDuplicateCharacters();

  Future<void> deleteCharacter(int characterId) =>
      _dbHelper.deleteCharacter(characterId);

  Future<void> updateCharacterGroupCommunityInfo({
    required int groupId,
    String? communityId,
    String? shareCode,
  }) => _dbHelper.updateCharacterGroupCommunityInfo(
    groupId: groupId,
    communityId: communityId,
    shareCode: shareCode,
  );
}
