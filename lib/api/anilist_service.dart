import 'dart:convert';
import 'package:http/http.dart' as http;
import 'bangumi_service.dart';
import '../utils/logger.dart';

class AnilistAnimeInfo {
  final int id;
  final String titleNative;
  final String titleRomaji;
  final String titleEnglish;
  final String? coverUrl;
  final String? bannerUrl;
  final String? format;
  final String? status;
  final String? airDate;
  final int? episodes;
  final double? score;
  final String? summary;
  final List<String> genres;
  final List<String> synonyms;
  final List<String> studios;
  final String? siteUrl;

  const AnilistAnimeInfo({
    required this.id,
    required this.titleNative,
    required this.titleRomaji,
    required this.titleEnglish,
    required this.coverUrl,
    required this.bannerUrl,
    required this.format,
    required this.status,
    required this.airDate,
    required this.episodes,
    required this.score,
    required this.summary,
    required this.genres,
    required this.synonyms,
    required this.studios,
    required this.siteUrl,
  });

  factory AnilistAnimeInfo.fromJson(Map<String, dynamic> json) {
    final title = json['title'] is Map<String, dynamic>
        ? json['title'] as Map<String, dynamic>
        : <String, dynamic>{};
    final cover = json['coverImage'] is Map<String, dynamic>
        ? json['coverImage'] as Map<String, dynamic>
        : <String, dynamic>{};
    final studios = json['studios']?['nodes'] is List
        ? json['studios']['nodes'] as List
        : const [];

    return AnilistAnimeInfo(
      id: json['id'] is int ? json['id'] as int : 0,
      titleNative: title['native']?.toString() ?? '',
      titleRomaji: title['romaji']?.toString() ?? '',
      titleEnglish: title['english']?.toString() ?? '',
      coverUrl: cover['extraLarge']?.toString() ?? cover['large']?.toString(),
      bannerUrl: json['bannerImage']?.toString(),
      format: json['format']?.toString(),
      status: json['status']?.toString(),
      airDate: _formatDate(json['startDate']),
      episodes: json['episodes'] is int ? json['episodes'] as int : null,
      score: json['averageScore'] is num
          ? (json['averageScore'] as num).toDouble() / 10.0
          : null,
      summary: _cleanDescription(json['description']),
      genres: _stringList(json['genres']),
      synonyms: _stringList(json['synonyms']),
      studios: studios
          .whereType<Map>()
          .map((item) => item['name']?.toString().trim() ?? '')
          .where((item) => item.isNotEmpty)
          .toList(),
      siteUrl: json['siteUrl']?.toString(),
    );
  }

  String get displayTitle {
    if (titleNative.isNotEmpty) return titleNative;
    if (titleRomaji.isNotEmpty) return titleRomaji;
    if (titleEnglish.isNotEmpty) return titleEnglish;
    return '未知标题';
  }

  String get subtitleTitle {
    if (titleRomaji.isNotEmpty && titleRomaji != displayTitle) {
      return titleRomaji;
    }
    if (titleEnglish.isNotEmpty && titleEnglish != displayTitle) {
      return titleEnglish;
    }
    return '';
  }

  List<String> get searchTitles {
    return <String>[titleNative, titleRomaji, titleEnglish, ...synonyms]
        .map((item) => item.trim())
        .where((item) => item.isNotEmpty)
        .toSet()
        .toList();
  }

  BangumiSearchResult toSearchResult() {
    return BangumiSearchResult(
      id: id,
      nameCn: displayTitle,
      nameOriginal: titleRomaji.isNotEmpty ? titleRomaji : titleNative,
      coverUrl: coverUrl,
      airDate: airDate,
      eps: episodes,
      source: 'anilist',
      summary: summary,
      studio: studios.isNotEmpty ? studios.first : null,
      score: score,
      tags: genres,
      format: format,
    );
  }

  static String? _formatDate(dynamic value) {
    if (value is! Map) return null;
    final year = value['year'];
    if (year == null) return null;
    var text = year.toString();
    final month = value['month'];
    final day = value['day'];
    if (month != null) {
      text += '-${month.toString().padLeft(2, '0')}';
    }
    if (day != null) {
      text += '-${day.toString().padLeft(2, '0')}';
    }
    return text;
  }

  static String? _cleanDescription(dynamic value) {
    final text = value?.toString().replaceAll(RegExp(r'<[^>]*>'), '').trim();
    if (text == null || text.isEmpty) return null;
    return text;
  }

  static List<String> _stringList(dynamic value) {
    if (value is! List) return const [];
    return value
        .map((item) => item?.toString().trim() ?? '')
        .where((item) => item.isNotEmpty)
        .toList();
  }
}

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

  static Future<AnilistAnimeInfo?> getAnimeInfoById(int id) async {
    if (id <= 0) return null;

    const String queryDoc = r'''
      query ($id: Int) {
        Media(id: $id, type: ANIME) {
          id
          title {
            romaji
            english
            native
          }
          coverImage {
            extraLarge
            large
            medium
          }
          bannerImage
          format
          status
          averageScore
          episodes
          description
          genres
          synonyms
          siteUrl
          startDate {
            year
            month
            day
          }
          studios(isMain: false) {
            nodes {
              name
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
          'variables': {'id': id},
        }),
      );

      if (response.statusCode != 200) {
        logger.w('Anilist Detail Failed: ${response.statusCode}');
        return null;
      }

      final data = jsonDecode(utf8.decode(response.bodyBytes));
      final media = data['data']?['Media'];
      if (media is! Map<String, dynamic>) return null;
      return AnilistAnimeInfo.fromJson(media);
    } catch (e) {
      logger.e('Anilist Detail Error: $e');
      return null;
    }
  }

  /// 从 AniList API 提取的单个 Media JSON 解析为 BangumiSearchResult
  static BangumiSearchResult parseAnilistJson(Map<String, dynamic> json) {
    String title = json['title']['native'] ?? json['title']['romaji'] ?? '未知标题';

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
      tags: AnilistAnimeInfo._stringList(json['genres']),
      format: json['format']?.toString(),
    );
  }
}
