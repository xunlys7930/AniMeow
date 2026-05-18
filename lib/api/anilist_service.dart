import 'dart:convert';
import 'package:http/http.dart' as http;
import 'bangumi_service.dart';
import '../utils/logger.dart';

class AnilistService {
  /// AniList GraphQL API 端点
  static const String _graphqlUrl = 'https://graphql.anilist.co';

  /// 通过 AniList API 搜索动漫
  ///
  /// [query] 搜索关键词
  /// 返回匹配的动漫列表，搜索失败或为空时返回空列表
  static Future<List<BangumiSearchResult>> searchAnime(String query) async {
    if (query.isEmpty) return [];

    // GraphQL 查询语句，包含标题、封面、集数、描述、首播日期和制作公司
    const String queryDoc = r'''
      query ($search: String) {
        Page(page: 1, perPage: 10) {
          media(search: $search, type: ANIME, sort: SEARCH_MATCH) {
            id
            title {
              romaji
              english
              native
            }
            coverImage {
              large
              medium
            }
            averageScore
            episodes
            description
            startDate {
              year
              month
              day
            }
            studios(isMain: true) {
              nodes {
                name
              }
            }
          }
        }
      }
    ''';

    try {
      final response = await http.post(
        Uri.parse(_graphqlUrl),
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
        body: jsonEncode({
          'query': queryDoc,
          'variables': {'search': query},
        }),
      );

      if (response.statusCode == 200) {
        final data = jsonDecode(utf8.decode(response.bodyBytes));

        // 检查 API 返回的数据结构是否有效
        if (data['data'] == null || data['data']['Page'] == null) {
          return [];
        }

        final List<dynamic> mediaList = data['data']['Page']['media'];

        return mediaList.map((json) => parseAnilistJson(json)).toList();
      }
    } catch (e) {
      logger.e('Anilist Search Error: $e');
    }

    return [];
  }

  /// 从 AniList API 提取的单个 Media JSON 解析为 BangumiSearchResult
  static BangumiSearchResult parseAnilistJson(Map<String, dynamic> json) {
    String title =
        json['title']['native'] ?? json['title']['romaji'] ?? '未知标题';

    if (json['title']['english'] != null) {
      title += " (${json['title']['english']})";
    }

    String? airDate;
    if (json['startDate'] != null && json['startDate']['year'] != null) {
      airDate = "${json['startDate']['year']}";

      if (json['startDate']['month'] != null) {
        airDate =
            "$airDate-${json['startDate']['month'].toString().padLeft(2, '0')}";

        if (json['startDate']['day'] != null) {
          airDate =
              "$airDate-${json['startDate']['day'].toString().padLeft(2, '0')}";
        }
      }
    }

    String desc = json['description'] ?? '';
    desc = desc.replaceAll(RegExp(r'<[^>]*>'), '');

    String? studioName;
    if (json['studios'] != null && json['studios']['nodes'] != null) {
      final studios = json['studios']['nodes'] as List;
      if (studios.isNotEmpty) {
        studioName = studios[0]['name'];
      }
    }

    double? score;
    if (json['averageScore'] != null) {
      score = (json['averageScore'] as int) / 10.0;
    }

    return BangumiSearchResult(
      id: json['id'],
      nameCn: title,
      nameOriginal: json['title']['native'] ?? '',
      coverUrl: json['coverImage']?['large'],
      airDate: airDate,
      eps: json['episodes'],
      source: 'anilist',
      summary: desc,
      studio: studioName,
      score: score,
    );
  }
}
