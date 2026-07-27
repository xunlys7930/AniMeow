import 'package:flutter_test/flutter_test.dart';
import 'package:anime_tracker/api/bangumi_service.dart';

void main() {
  group('BangumiSearchResult.fromJson parsing tests', () {
    test('Parses basic Bangumi JSON correctly', () {
      final json = {
        'id': 12345,
        'name_cn': '测试番剧',
        'name': 'Test Anime',
        'images': {
          'large': 'https://example.com/large.jpg',
          'medium': 'https://example.com/medium.jpg',
        },
        'air_date': '2023-10-01',
        'eps': 12,
        'rating': {'score': 8.5},
      };

      final result = BangumiSearchResult.fromJson(json);

      expect(result.id, 12345);
      expect(result.nameCn, '测试番剧');
      expect(result.nameOriginal, 'Test Anime');
      expect(result.coverUrl, 'https://example.com/large.jpg');
      expect(result.airDate, '2023-10-01');
      expect(result.eps, 12);
      expect(result.score, 8.5);
      expect(result.source, 'bangumi');
    });

    test('Handles missing name_cn by falling back to name', () {
      final json = {
        'id': 54321,
        'name': 'Fallback Name',
        'images': null,
        'air_date': '',
        'eps': null,
      };

      final result = BangumiSearchResult.fromJson(json);

      expect(result.id, 54321);
      expect(result.nameCn, 'Fallback Name');
      expect(result.nameOriginal, 'Fallback Name');
      expect(result.coverUrl, isNull);
      expect(result.airDate, isNull);
    });

    test('Handles missing images gracefully', () {
      final json = {'id': 1, 'name': 'No Image'};

      final result = BangumiSearchResult.fromJson(json);
      expect(result.coverUrl, isNull);
    });

    test('Extracts medium image if large is missing', () {
      final json = {
        'id': 2,
        'name': 'Medium Image',
        'images': {
          'medium': 'https://example.com/medium.jpg',
          'common': 'https://example.com/common.jpg',
        },
      };

      final result = BangumiSearchResult.fromJson(json);
      expect(result.coverUrl, 'https://example.com/medium.jpg');
    });

    test('Proxies Bangumi cover images', () {
      final json = {
        'id': 3,
        'name': 'Proxy Image',
        'images': {
          'large': 'https://lain.bgm.tv/pic/cover/l/13/c5/400602_ZI8Y9.jpg',
        },
      };

      final result = BangumiSearchResult.fromJson(json);

      expect(
        result.coverUrl,
        'https://img-bgm.xunlys.top/pic/cover/l/13/c5/400602_ZI8Y9.jpg',
      );
    });
  });

  group('BangumiSearchResult copyWith', () {
    test('copyWith updates specified fields correctly', () {
      final original = BangumiSearchResult(
        id: 1,
        nameCn: 'Original',
        nameOriginal: 'Orig',
        eps: 12,
      );

      final updated = original.copyWith(
        tvCount: 10,
        spCount: 2,
        totalCount: 12,
      );

      expect(updated.id, 1);
      expect(updated.nameCn, 'Original');
      expect(updated.eps, 12);
      expect(updated.tvCount, 10);
      expect(updated.spCount, 2);
      expect(updated.totalCount, 12);
    });
  });

  group('BangumiUserCollectionItem parsing tests', () {
    test('Maps public collection JSON to local anime fields', () {
      final json = {
        'type': 3,
        'rate': 9,
        'ep_status': 8,
        'comment': '补标一下',
        'tags': ['新番', '周更'],
        'updated_at': '2026-06-09T12:00:00+08:00',
        'subject': {
          'id': 400602,
          'name': 'Original Anime',
          'name_cn': '测试动画',
          'date': '2024-01-01',
          'total_episodes': 12,
          'images': {
            'large': 'https://lain.bgm.tv/pic/cover/l/13/c5/400602.jpg',
          },
          'rating': {'score': 8.2},
        },
      };

      final item = BangumiUserCollectionItem.fromJson(json);
      final animeMap = item.toAnimeMap();

      expect(item.subjectId, 400602);
      expect(item.title, '测试动画');
      expect(item.status, '在看');
      expect(item.progressText, '8/12 集');
      expect(item.tags, ['新番', '周更']);
      expect(animeMap['title'], '测试动画');
      expect(animeMap['status'], '在看');
      expect(animeMap['watched_episodes'], 8);
      expect(animeMap['total_episodes'], 12);
      expect(animeMap['rating'], 8.2);
      expect(animeMap['review'], '补标一下\nBangumi 个人评分：9');
      expect(
        animeMap['cover_url'],
        'https://img-bgm.xunlys.top/pic/cover/l/13/c5/400602.jpg',
      );
    });
  });
}
