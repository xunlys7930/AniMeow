import 'package:flutter_test/flutter_test.dart';
import 'package:anime_tracker/api/anilist_service.dart';

void main() {
  group('AnilistService.parseAnilistJson tests', () {
    test('Parses full AniList JSON correctly', () {
      final json = {
        'id': 101,
        'title': {
          'native': 'テストアニメ',
          'romaji': 'Test Anime',
          'english': 'Test Anime English'
        },
        'startDate': {
          'year': 2024,
          'month': 4,
          'day': 1
        },
        'description': 'A <b>test</b> description.<br>',
        'studios': {
          'nodes': [
            {'name': 'Test Studio'}
          ]
        },
        'averageScore': 85,
        'coverImage': {
          'large': 'https://example.com/cover.jpg',
        },
        'episodes': 24
      };

      final result = AnilistService.parseAnilistJson(json);

      expect(result.id, 101);
      expect(result.nameCn, 'テストアニメ (Test Anime English)');
      expect(result.nameOriginal, 'テストアニメ');
      expect(result.airDate, '2024-04-01');
      expect(result.summary, 'A test description.'); // HTML stripped
      expect(result.studio, 'Test Studio');
      expect(result.score, 8.5); // 85 / 10
      expect(result.coverUrl, 'https://example.com/cover.jpg');
      expect(result.eps, 24);
      expect(result.source, 'anilist');
    });

    test('Handles missing optional fields gracefully', () {
      final json = {
        'id': 102,
        'title': {
          'romaji': 'Romaji Title Only'
        },
        'startDate': {
          'year': 2024
        },
        'description': null,
        'studios': null,
        'averageScore': null,
        'coverImage': null,
        'episodes': null
      };

      final result = AnilistService.parseAnilistJson(json);

      expect(result.id, 102);
      expect(result.nameCn, 'Romaji Title Only');
      expect(result.nameOriginal, '');
      expect(result.airDate, '2024'); // Only year provided
      expect(result.summary, '');
      expect(result.studio, isNull);
      expect(result.score, isNull);
      expect(result.coverUrl, isNull);
      expect(result.eps, isNull);
    });

    test('Falls back to unknown title when no title is provided', () {
      final json = {
        'id': 103,
        'title': {},
      };

      final result = AnilistService.parseAnilistJson(json);
      expect(result.nameCn, '未知标题');
    });
  });
}
