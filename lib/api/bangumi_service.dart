import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:html/parser.dart' as parser;
import '../utils/api_config.dart';
import '../utils/logger.dart';

class BangumiSearchResult {
  final int id; // 条目 ID
  final String nameCn; // 中文标题
  final String nameOriginal; // 原始标题
  final String? coverUrl; // 封面图片 URL
  final String? airDate; // 首播日期
  final int? eps; // 总集数

  // --- 新增字段 ---
  final String source; // 数据来源：'bangumi' 或 'anilist'
  final String? summary; // 剧情简介（Anilist 直接返回，Bangumi 需二次查询）
  final String? studio; // 制作公司
  final int? tvCount;
  final int? spCount;
  final int? totalCount;
  final double? score; // 新增：评分

  BangumiSearchResult({
    required this.id,
    required this.nameCn,
    required this.nameOriginal,
    this.coverUrl,
    this.airDate,
    this.eps,
    this.source = 'bangumi', // 默认值为 bangumi
    this.summary,
    this.studio,
    this.tvCount,
    this.spCount,
    this.totalCount,
    this.score,
  });

  /// 从 Bangumi API 返回的 JSON 数据创建搜索结果对象
  factory BangumiSearchResult.fromJson(Map<String, dynamic> json) {
    // 提取封面图片 URL（优先使用 large，其次 medium，最后 common）
    String? imageUrl;
    if (json['images'] != null) {
      imageUrl =
          json['images']['large'] ??
          json['images']['medium'] ??
          json['images']['common'];
    }

    // 确定显示的中文标题
    // 优先使用 name_cn，若无则使用 name，最后使用"未知标题"
    String cnName = json['name_cn'] ?? '';
    if (cnName.isEmpty) {
      cnName = json['name'] ?? '未知标题';
    }

    // 处理首播日期
    // 优先使用 air_date，若无则使用 date，最后检查是否为空字符串
    String? date = json['air_date'];
    if (date == null || date.isEmpty) {
      date = json['date'];
    }
    if (date != null && date.trim().isEmpty) {
      date = null;
    }

    return BangumiSearchResult(
      id: json['id'],
      nameCn: cnName,
      nameOriginal: json['name'] ?? '',
      coverUrl: imageUrl,
      airDate: date,
      eps: json['eps'],
      source: 'bangumi', // 标记为 Bangumi 来源
      score: json['rating'] != null
          ? (json['rating']['score'] as num?)?.toDouble()
          : null, // 提取评分
      // 注意：summary 和 studio 需要通过 getAnimeDetail 方法单独获取
    );
  }

  /// 创建一个带有集数详情的新实例
  BangumiSearchResult copyWith({int? tvCount, int? spCount, int? totalCount}) {
    return BangumiSearchResult(
      id: id,
      nameCn: nameCn,
      nameOriginal: nameOriginal,
      coverUrl: coverUrl,
      airDate: airDate,
      eps: eps,
      source: source,
      summary: summary,
      studio: studio,
      tvCount: tvCount,
      spCount: spCount,
      totalCount: totalCount,
      score: score,
    );
  }
}

class BangumiService {
  /// Bangumi API 基础地址
  static const String _baseUrl = 'https://api.bgm.tv';

  /// 请求头配置（包含 User-Agent）
  static final Map<String, String> _headers = {
    'User-Agent': 'AniMeow/1.0 (Flutter; +https://github.com/xunlys7930/AniMeow)',
  };

  /// 搜索动画条目 (由于兼容性保留此方法名)
  static Future<List<BangumiSearchResult>> searchAnime(String query) async {
    return searchSubject(query, type: 2);
  }

  /// 搜索书籍/漫画条目
  static Future<List<BangumiSearchResult>> searchBook(String query) async {
    return searchSubject(query, type: 1);
  }

  /// 搜索条目（通用）
  /// [type] 1=书籍, 2=动画, 3=音乐, 4=游戏, 6=三次元
  static Future<List<BangumiSearchResult>> searchSubject(
    String query, {
    int type = 2,
  }) async {
    if (query.isEmpty) return [];

    // 构造搜索 URL
    final url = Uri.parse(
      '$_baseUrl/search/subject/${Uri.encodeComponent(query)}?type=$type&responseGroup=large',
    );

    try {
      final response = await http.get(url, headers: _headers);

      if (response.statusCode == 200) {
        final data = jsonDecode(utf8.decode(response.bodyBytes));

        if (data['list'] != null) {
          final List<BangumiSearchResult> results = [];
          final List<dynamic> rawList = data['list'];
          for (var item in rawList) {
            results.add(BangumiSearchResult.fromJson(item));
          }

          // 如果是动画类型，获取集数详情
          if (type == 2) {
            final limitedList = results.take(20).toList();
            final remainingList = results.skip(20).toList();

            final enrichedList = await Future.wait(
              limitedList.map((item) async {
                try {
                  final counts = await _fetchEpisodeCounts(item.id);
                  if (counts['total']! > 0) {
                    return item.copyWith(
                      tvCount: counts['tv'],
                      spCount: counts['sp'],
                      totalCount: counts['total'],
                    );
                  }
                } catch (e) {
                  // Ignore
                }
                return item;
              }),
            );
            return [...enrichedList, ...remainingList];
          }

          return results;
        }
      }
    } catch (e) {
      logger.e('Bangumi Search Error: $e');
    }

    return [];
  }

  /// 获取 V0 API 集数详情的辅助方法
  static Future<Map<String, int>> _fetchEpisodeCounts(int id) async {
    int tvCount = 0;
    int spCount = 0;
    int total = 0;

    try {
      final epsUrl = Uri.parse('$_baseUrl/v0/episodes?subject_id=$id');
      final epsResponse = await http.get(epsUrl, headers: _headers);
      if (epsResponse.statusCode == 200) {
        final epsData = jsonDecode(utf8.decode(epsResponse.bodyBytes));
        if (epsData['data'] != null && epsData['data'] is List) {
          final List episodes = epsData['data'];

          for (var e in episodes) {
            final type = e['type'];
            if (type == 0) tvCount++;
            if (type == 1) spCount++;
          }
          total = tvCount + spCount;
        }
      }
    } catch (e) {
      logger.e("Fetch Episodes Error for $id: $e");
    }
    return {'tv': tvCount, 'sp': spCount, 'total': total};
  }

  /// 根据标签搜索动漫 (使用 v0 API)
  ///
  /// [tag] 标签关键词
  /// [offset] 偏移量，默认 0
  /// [limit] 每页数量，默认 20
  /// 根据标签搜索动漫 (使用网页爬虫)
  ///
  /// [tag] 标签关键词
  /// [offset] 偏移量
  /// [limit] 每页数量 (爬虫固定为24，参数仅用于计算页码)
  static Future<List<BangumiSearchResult>> searchByTag(
    String tag, {
    int offset = 0,
    int limit = 20,
  }) async {
    if (tag.isEmpty) return [];

    // 计算页码: Bangumi 每页 24 条
    int page = (offset / 24).floor() + 1;

    // URL: https://bgm.tv/anime/tag/{tag}?page={page}
    final url = Uri.parse(
      'https://bgm.tv/anime/tag/${Uri.encodeComponent(tag)}?page=$page',
    );

    try {
      final response = await http.get(url, headers: _headers);

      if (response.statusCode == 200) {
        final document = parser.parse(utf8.decode(response.bodyBytes));
        final List<BangumiSearchResult> results = [];

        // 查找所有列表项 #browserItemList > li
        final items = document.querySelectorAll('#browserItemList > li');

        for (var item in items) {
          try {
            // ID: <li id="item_123"> -> 123
            final idStr = item.id.replaceAll('item_', '');
            final id = int.tryParse(idStr) ?? 0;
            if (id == 0) continue;

            // 标题: <h3><a href="/subject/123" class="l">Title</a> <small class="grey">Original Title</small></h3>
            final h3 = item.querySelector('h3');
            final titleLink = h3?.querySelector('a.l');
            final titleCn = titleLink?.text.trim() ?? '';
            final titleOriginal =
                h3?.querySelector('small.grey')?.text.trim() ?? '';

            // 图片: <img src="//lain.bgm.tv/pic/cover/s/..." class="cover" />
            // 需要替换 /s/ 为 /l/ 或 /c/ 以获取清晰图片
            String? coverUrl;
            final img = item.querySelector('img.cover');
            var rawSrc = img?.attributes['src'];
            if (rawSrc != null) {
              if (rawSrc.startsWith('//')) {
                rawSrc = 'https:$rawSrc';
              }
              // 尝试获取大图
              coverUrl = rawSrc.replaceAll(
                RegExp(r'/pic/cover/[a-z]/'),
                '/pic/cover/l/',
              );
            }

            // 信息: <p class="info tip">2024-01-01 / 12话 / ...</p>
            final infoText =
                item.querySelector('p.info.tip')?.text.trim() ?? '';
            final infoParts = infoText.split('/');
            String? airDate;
            int? eps;

            // 简单解析日期和集数
            for (var part in infoParts) {
              part = part.trim();

              // 匹配 YYYY-MM-DD 或 YYYY年M月D日
              if (RegExp(r'\d{4}[-年]').hasMatch(part)) {
                airDate = part;
              }
              // 匹配集数
              else if (part.contains('话') || part.contains('集')) {
                final epsStr = part.replaceAll(RegExp(r'[^0-9]'), '');
                eps = int.tryParse(epsStr);
              }
            }

            results.add(
              BangumiSearchResult(
                id: id,
                nameCn: titleCn.isEmpty
                    ? (titleOriginal.isEmpty ? '未知标题' : titleOriginal)
                    : titleCn,
                nameOriginal: titleOriginal,
                coverUrl: coverUrl,
                airDate: airDate,
                eps: eps,
                source: 'bangumi',
              ),
            );
          } catch (e) {
            logger.e('Parse Item Error: $e');
            continue;
          }
        }
        return results;
      } else {
        logger.w('Bangumi Scraper Failed: ${response.statusCode}');
      }
    } catch (e) {
      logger.e('Bangumi Scraper Error: $e');
    }

    return [];
  }

  /// 获取动漫详情信息（包括制作公司、集数、简介等）
  ///
  /// [id] 动漫条目 ID
  /// 返回包含详细信息的 Map
  static Future<Map<String, dynamic>> getAnimeDetail(int id) async {
    final url = Uri.parse('$_baseUrl/subject/$id');

    try {
      final response = await http.get(url, headers: _headers);

      if (response.statusCode == 200) {
        final data = jsonDecode(utf8.decode(response.bodyBytes));

        // 提取基本信息
        String? studio;
        String? airDate = data['date'];
        int? eps = data['eps']; // 总集数
        String? summary = data['summary']; // 简介
        double? score = (data['rating']?['score'] as num?)?.toDouble(); // 评分

        // 从 infobox 中提取制作公司信息
        if (data['infobox'] != null) {
          final List<dynamic> infobox = data['infobox'];

          for (var item in infobox) {
            final String key = item['key'];
            final dynamic value = item['value'];

            // 检查多种可能的制作公司字段名
            if (['动画制作', '制作', '开发', 'Animation Work', '制作公司'].contains(key)) {
              if (value is String) {
                studio = value;
              } else if (value is List && value.isNotEmpty) {
                final firstItem = value[0];

                if (firstItem is Map) {
                  studio = firstItem['v'] ?? firstItem['value'];
                } else if (firstItem is String) {
                  studio = firstItem;
                }
              }

              // 找到制作公司信息后跳出循环
              if (studio != null) break;
            }
          }
        }

        // 尝试从 V0 API 获取更准确的集数 (包含 SP)
        String? epsBreakdown;
        int? tvCount;
        int? spCount;

        try {
          final counts = await _fetchEpisodeCounts(id);
          tvCount = counts['tv'];
          spCount = counts['sp'];
          int realTotal = counts['total'] ?? 0;

          if (realTotal > 0) {
            eps = realTotal;
            List<String> parts = [];
            if (tvCount! > 0) parts.add("TV $tvCount集");
            if (spCount! > 0) parts.add("SP $spCount集");
            if (parts.isNotEmpty) {
              epsBreakdown = parts.join(", ");
            }
          }
        } catch (e) {
          logger.e('Fetch Episodes Error: $e');
        }

        return {
          'studio': studio, // 制作公司
          'air_date': airDate, // 首播日期
          'eps': eps, // 总集数
          'summary': summary, // 剧情简介
          'eps_breakdown': epsBreakdown, // 集数详情
          'tv_count': tvCount, // TV 集数
          'sp_count': spCount, // SP 集数
          'score': score, // 评分
        };
      }
    } catch (e) {
      logger.e('Bangumi Detail Error: $e');
    }

    return {'studio': null}; // 失败时返回空结果
  }

  /// 获取云端自建服务器的番剧列表（支持筛选）
  /// [keyword] 搜索关键词
  /// [tag] 标签筛选
  /// [year] 年份筛选
  static Future<List<BangumiSearchResult>> getServerAnimes({
    String? keyword,
    String? tag,
    String? year,
    String? month,
  }) async {
    try {
      // 构造请求参数
      final queryParams = <String, String>{};
      if (keyword != null && keyword.isNotEmpty)
        queryParams['keyword'] = keyword;
      if (tag != null && tag.isNotEmpty) queryParams['tag'] = tag;
      if (year != null && year.isNotEmpty) queryParams['year'] = year;
      if (month != null && month.isNotEmpty) queryParams['month'] = month;

      final uri = ApiConfig.cloudUri(
        '/api/search',
        queryParams.isNotEmpty ? queryParams : null,
      );
      if (uri == null) {
        logger.w('Get Server Animes: CLOUD_API_BASE 未配置');
        return [];
      }

      // 发送请求时带上 Token
      final response = await http.get(
        uri,
        headers: {'Authorization': 'Bearer ${ApiConfig.apiToken}'},
      );

      if (response.statusCode == 200) {
        final data = jsonDecode(utf8.decode(response.bodyBytes));
        if (data['status'] == 'success' && data['data'] != null) {
          final List<dynamic> list = data['data'];
          return list.map((item) {
            // 获取数值字段，处理可能的 String 类型
            final rawScore = item['score'];
            double? score;
            if (rawScore != null) {
              if (rawScore is num) {
                score = rawScore.toDouble();
              } else if (rawScore is String) {
                score = double.tryParse(rawScore);
              }
            }

            final rawEps = item['total_eps'];
            int? eps;
            if (rawEps != null) {
              if (rawEps is num) {
                eps = rawEps.toInt();
              } else if (rawEps is String) {
                eps = int.tryParse(rawEps);
              }
            }

            // 将服务器数据库字段映射到 BangumiSearchResult
            return BangumiSearchResult(
              id: item['api_id'] ?? 0,
              nameCn: item['name_cn'] ?? '未知',
              nameOriginal: item['name_original'] ?? '',
              coverUrl: item['cover_url'],
              airDate: item['air_date'],
              eps: eps,
              source: item['source'] ?? 'server',
              summary: item['summary'],
              studio: item['studio'],
              score: score,
            );
          }).toList();
        }
      }
    } catch (e) {
      logger.e('Get Server Animes Error: $e');
    }
    return [];
  }

  /// 同步封面 URL 到自建服务器
  /// [title] 作品名称
  /// [coverUrl] 要推送的封面 URL
  static Future<void> updateServerCover(String title, String coverUrl) async {
    if (title.isEmpty || coverUrl.isEmpty) return;
    if (!coverUrl.startsWith('http')) return; // 仅推送网络地址

    try {
      final url = ApiConfig.cloudUri('/api/update_cover');
      if (url == null) return;

      final response = await http.post(
        url,
        headers: {
          'Content-Type': 'application/json',
          // 发送请求时带上 Token
          'Authorization': 'Bearer ${ApiConfig.apiToken}',
        },
        body: jsonEncode({'title': title, 'cover_url': coverUrl}),
      );

      if (response.statusCode == 200) {
        logger.i('[Sync] Server cover updated for: $title');
      } else {
        logger.w('[Sync] Server update failed: ${response.statusCode}');
      }
    } catch (e) {
      logger.e('[Sync] Server update error: $e');
    }
  }
}
