import 'dart:async';
import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:html/parser.dart' as parser;
import '../utils/api_config.dart';
import '../utils/bangumi_image_proxy.dart';
import '../utils/logger.dart';
import '../models/bangumi_character.dart';
import '../settings_manager.dart';

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
  final List<String> tags;
  final String? format;

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
    this.tags = const [],
    this.format,
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

    final rawEps = json['eps'] ?? json['total_episodes'];
    int? eps;
    if (rawEps is int) {
      eps = rawEps;
    } else if (rawEps is num) {
      eps = rawEps.toInt();
    } else if (rawEps is String) {
      eps = int.tryParse(rawEps);
    }

    return BangumiSearchResult(
      id: json['id'],
      nameCn: cnName,
      nameOriginal: json['name'] ?? '',
      coverUrl: proxyBangumiImageUrl(imageUrl),
      airDate: date,
      eps: eps,
      source: 'bangumi', // 标记为 Bangumi 来源
      score: json['rating'] != null
          ? (json['rating']['score'] as num?)?.toDouble()
          : null, // 提取评分
      tags: _parseTags(json['tags']),
      format: json['platform']?.toString(),
      // 注意：summary 和 studio 需要通过 getAnimeDetail 方法单独获取
    );
  }

  static List<String> _parseTags(dynamic raw) {
    if (raw is String) {
      return raw
          .split(RegExp(r'[,，/、]'))
          .map((item) => item.trim())
          .where((item) => item.isNotEmpty)
          .toList();
    }
    if (raw is List) {
      return raw
          .map((item) {
            if (item is Map) return item['name']?.toString() ?? '';
            return item?.toString() ?? '';
          })
          .map((item) => item.trim())
          .where((item) => item.isNotEmpty)
          .toList();
    }
    return const [];
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
      tags: tags,
      format: format,
    );
  }
}

class BangumiUserCollectionItem {
  final int subjectId;
  final int collectionType;
  final String title;
  final String originalTitle;
  final String? coverUrl;
  final String? airDate;
  final int watchedEpisodes;
  final int totalEpisodes;
  final double? subjectScore;
  final int? userRate;
  final String? comment;
  final List<String> tags;
  final DateTime? updatedAt;

  const BangumiUserCollectionItem({
    required this.subjectId,
    required this.collectionType,
    required this.title,
    required this.originalTitle,
    this.coverUrl,
    this.airDate,
    this.watchedEpisodes = 0,
    this.totalEpisodes = 0,
    this.subjectScore,
    this.userRate,
    this.comment,
    this.tags = const [],
    this.updatedAt,
  });

  factory BangumiUserCollectionItem.fromJson(Map<String, dynamic> json) {
    final subject = _asStringKeyMap(json['subject']);
    final images = _asStringKeyMap(subject['images']);
    final rating = _asStringKeyMap(subject['rating']);

    final subjectId = _asInt(subject['id']) ?? _asInt(json['subject_id']) ?? 0;
    final title =
        _asNonEmptyString(subject['name_cn']) ??
        _asNonEmptyString(subject['name']) ??
        '未知标题';
    final originalTitle = _asNonEmptyString(subject['name']) ?? title;
    final coverUrl = proxyBangumiImageUrl(
      _asNonEmptyString(images['large']) ??
          _asNonEmptyString(images['medium']) ??
          _asNonEmptyString(images['common']),
    );
    final airDate = _asNonEmptyString(subject['date']);
    final totalEpisodes =
        _asPositiveInt(subject['total_episodes']) ??
        _asPositiveInt(subject['eps']) ??
        0;
    final updatedAtText = _asNonEmptyString(json['updated_at']);

    return BangumiUserCollectionItem(
      subjectId: subjectId,
      collectionType: _asInt(json['type']) ?? 1,
      title: title,
      originalTitle: originalTitle,
      coverUrl: coverUrl,
      airDate: airDate,
      watchedEpisodes: _asInt(json['ep_status']) ?? 0,
      totalEpisodes: totalEpisodes,
      subjectScore: _asDouble(rating['score']),
      userRate: _asInt(json['rate']),
      comment: _asNonEmptyString(json['comment']),
      tags: _parseTags(json['tags']),
      updatedAt: updatedAtText == null
          ? null
          : DateTime.tryParse(updatedAtText),
    );
  }

  String get status {
    switch (collectionType) {
      case 2:
        return '看完';
      case 3:
        return '在看';
      case 5:
        return '弃坑';
      case 1:
      case 4:
      default:
        return '未看';
    }
  }

  String get progressText {
    if (totalEpisodes > 0) {
      return '$watchedEpisodes/$totalEpisodes 集';
    }
    if (watchedEpisodes > 0) {
      return '已看 $watchedEpisodes 集';
    }
    return '集数未知';
  }

  Map<String, dynamic> toAnimeMap() {
    final cappedWatched = totalEpisodes > 0
        ? watchedEpisodes.clamp(0, totalEpisodes).toInt()
        : watchedEpisodes;

    return {
      'title': title,
      'cover_url': coverUrl,
      'status': status,
      'rating': subjectScore ?? 0,
      'review': _buildReviewText(),
      'air_date': airDate,
      'watched_episodes': cappedWatched,
      'total_episodes': totalEpisodes,
      'tv_episodes': totalEpisodes,
      'sp_episodes': 0,
      'subject_type': 'anime',
    };
  }

  String _buildReviewText() {
    final parts = <String>[];
    final cleanComment = comment?.trim();
    if (cleanComment != null && cleanComment.isNotEmpty) {
      parts.add(cleanComment);
    }
    if (userRate != null && userRate! > 0) {
      parts.add('Bangumi 个人评分：$userRate');
    }
    return parts.join('\n');
  }

  static Map<String, dynamic> _asStringKeyMap(dynamic value) {
    if (value is! Map) return <String, dynamic>{};
    return value.map((key, value) => MapEntry(key.toString(), value));
  }

  static String? _asNonEmptyString(dynamic value) {
    final text = value?.toString().trim();
    if (text == null || text.isEmpty) return null;
    return text;
  }

  static int? _asInt(dynamic value) {
    if (value == null) return null;
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value.toString());
  }

  static int? _asPositiveInt(dynamic value) {
    final parsed = _asInt(value);
    if (parsed == null || parsed <= 0) return null;
    return parsed;
  }

  static double? _asDouble(dynamic value) {
    if (value == null) return null;
    if (value is num) return value.toDouble();
    return double.tryParse(value.toString());
  }

  static List<String> _parseTags(dynamic value) {
    if (value is List) {
      return value
          .map((item) => item.toString().trim())
          .where((item) => item.isNotEmpty)
          .toSet()
          .toList();
    }

    final text = _asNonEmptyString(value);
    if (text == null) return const [];
    return text
        .replaceAll('，', ',')
        .split(',')
        .map((item) => item.trim())
        .where((item) => item.isNotEmpty)
        .toSet()
        .toList();
  }
}

class _BangumiEndpointResult<T> {
  final bool isOfficial;
  final T? value;

  const _BangumiEndpointResult({required this.isOfficial, required this.value});
}

class _BangumiCollectionPage {
  final List<BangumiUserCollectionItem> items;
  final int total;
  final int limit;

  const _BangumiCollectionPage({
    required this.items,
    required this.total,
    required this.limit,
  });
}

class BangumiService {
  /// Bangumi API 基础地址
  static const String _baseUrl = 'https://api.bgm.tv';
  static const Duration _requestTimeout = Duration(seconds: 8);
  static bool? _officialBangumiAvailable;
  static BangumiApiMode? _lastBangumiApiMode;

  static String get _proxyBaseUrl =>
      SettingsManager().bangumiApiProxyBaseNotifier.value;

  /// 请求头配置（包含 User-Agent）
  static final Map<String, String> _headers = {
    'User-Agent':
        'AniMeow/1.0 (Flutter; +https://github.com/xunlys7930/AniMeow)',
  };

  static final Map<String, String> _jsonHeaders = {
    ..._headers,
    'Content-Type': 'application/json',
  };

  static String? proxyCoverUrl(String? url) => proxyBangumiImageUrl(url);
  static Future<List<BangumiCharacter>> searchCharacters(String query) async {
    final keyword = query.trim();
    if (keyword.isEmpty) return const [];

    return await _requestBangumiEndpoint<List<BangumiCharacter>>(
          label: 'Bangumi Character Search',
          official: () => _searchCharactersWithOfficialApi(keyword),
          proxy: () => _searchCharactersWithProxy(keyword),
          isUsable: (value) => value.isNotEmpty,
        ) ??
        const [];
  }

  static Future<BangumiCharacter?> getCharacter(int id) {
    return _requestBangumiEndpoint<BangumiCharacter>(
      label: 'Bangumi Character Detail',
      official: () => _getCharacterWithOfficialApi(id),
      proxy: () => _getCharacterWithProxy(id),
      isUsable: (_) => true,
    );
  }

  static Future<List<BangumiCharacter>?> _searchCharactersWithOfficialApi(
    String keyword,
  ) {
    return _searchCharactersFrom(
      Uri.parse('$_baseUrl/v0/search/characters'),
      keyword,
    );
  }

  static Future<List<BangumiCharacter>?> _searchCharactersWithProxy(
    String keyword,
  ) {
    return _searchCharactersFrom(
      Uri.parse('$_proxyBaseUrl/v0/search/characters'),
      keyword,
    );
  }

  static Future<List<BangumiCharacter>?> _searchCharactersFrom(
    Uri baseUri,
    String keyword,
  ) async {
    final url = baseUri.replace(
      queryParameters: {
        ...baseUri.queryParameters,
        'limit': '20',
        'offset': '0',
      },
    );

    try {
      final response = await http
          .post(
            url,
            headers: _jsonHeaders,
            body: jsonEncode({'keyword': keyword, 'sort': 'match'}),
          )
          .timeout(_requestTimeout);

      if (response.statusCode != 200) {
        logger.w('Bangumi Character Search Failed: ${response.statusCode}');
        return null;
      }

      final decoded = jsonDecode(utf8.decode(response.bodyBytes));
      final rawList = decoded is Map<String, dynamic>
          ? decoded['data']
          : decoded;
      if (rawList is! List) return const [];

      return rawList
          .whereType<Map>()
          .map((item) => _characterFromJson(_stringKeyMap(item)))
          .where((item) => item.id > 0 && item.name.trim().isNotEmpty)
          .toList();
    } catch (e) {
      logger.e('Bangumi Character Search Error: $e');
      return null;
    }
  }

  static Future<BangumiCharacter?> _getCharacterWithOfficialApi(int id) {
    return _getCharacterFrom(Uri.parse('$_baseUrl/v0/characters/$id'));
  }

  static Future<BangumiCharacter?> _getCharacterWithProxy(int id) {
    return _getCharacterFrom(Uri.parse('$_proxyBaseUrl/v0/characters/$id'));
  }

  static Future<BangumiCharacter?> _getCharacterFrom(Uri url) async {
    try {
      final response = await http
          .get(url, headers: _headers)
          .timeout(_requestTimeout);
      if (response.statusCode != 200) return null;

      final decoded = jsonDecode(utf8.decode(response.bodyBytes));
      if (decoded is! Map) return null;
      return _characterFromJson(_stringKeyMap(decoded));
    } catch (e) {
      logger.e('Bangumi Character Detail Error: $e');
      return null;
    }
  }

  static BangumiCharacter _characterFromJson(Map<String, dynamic> json) {
    final infobox = json['infobox'] is List
        ? json['infobox'] as List
        : const [];
    final images = _stringKeyMap(json['images']);
    final name = _asNonEmptyText(json['name']) ?? '';
    final nameCn = _asNonEmptyText(_infoboxValue(infobox, '简体中文名'));
    final bloodType =
        _asNonEmptyText(json['blood_type']) ??
        _asNonEmptyText(_infoboxValue(infobox, '血型'));

    return BangumiCharacter(
      id: _asIntValue(json['id']) ?? 0,
      name: name,
      nameCn: nameCn,
      imageUrl: proxyBangumiImageUrl(
        _asNonEmptyText(images['large']) ??
            _asNonEmptyText(images['medium']) ??
            _asNonEmptyText(images['grid']) ??
            _asNonEmptyText(images['small']),
      ),
      summary: _asNonEmptyText(json['summary']),
      gender:
          _asNonEmptyText(_infoboxValue(infobox, '性别')) ??
          _asNonEmptyText(json['gender']),
      birthYear: _asIntValue(json['birth_year']),
      birthMonth: _asIntValue(json['birth_mon']),
      birthDay: _asIntValue(json['birth_day']),
      bloodType: bloodType,
      infoboxJson: jsonEncode(infobox),
    );
  }

  static dynamic _infoboxValue(List<dynamic> infobox, String key) {
    for (final item in infobox) {
      if (item is! Map) continue;
      if (item['key']?.toString() == key) return item['value'];
    }
    return null;
  }

  static Map<String, dynamic> _stringKeyMap(dynamic value) {
    if (value is! Map) return <String, dynamic>{};
    return value.map((key, value) => MapEntry(key.toString(), value));
  }

  static String? _asNonEmptyText(dynamic value) {
    if (value == null) return null;
    if (value is List) {
      final parts = value
          .map((item) => item is Map ? item['v'] ?? item['value'] : item)
          .map((item) => item?.toString().trim() ?? '')
          .where((item) => item.isNotEmpty)
          .toList();
      return parts.isEmpty ? null : parts.join(' / ');
    }
    final text = value.toString().trim();
    return text.isEmpty ? null : text;
  }

  static int? _asIntValue(dynamic value) {
    if (value == null) return null;
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value.toString());
  }

  static void _markOfficialBangumiAvailable(String reason) {
    if (_officialBangumiAvailable == true) return;
    _officialBangumiAvailable = true;
    logger.i('Bangumi official API available: $reason');
  }

  static void _markOfficialBangumiUnavailable(String reason) {
    if (_officialBangumiAvailable == false) return;
    _officialBangumiAvailable = false;
    logger.w('Bangumi official API disabled for this session: $reason');
  }

  static bool _isUsableValue<T>(T? value, bool Function(T value)? isUsable) {
    if (value == null) return false;
    return isUsable?.call(value) ?? true;
  }

  static Future<_BangumiEndpointResult<T>> _wrapBangumiEndpoint<T>({
    required String label,
    required bool isOfficial,
    required Future<T?> Function() request,
  }) async {
    try {
      return _BangumiEndpointResult<T>(
        isOfficial: isOfficial,
        value: await request(),
      );
    } catch (e) {
      logger.e('$label ${isOfficial ? 'Official' : 'Proxy'} Error: $e');
      return _BangumiEndpointResult<T>(isOfficial: isOfficial, value: null);
    }
  }

  static void _rememberOfficialProbe<T>({
    required String label,
    required Future<_BangumiEndpointResult<T>> officialFuture,
    bool Function(T value)? isUsable,
    bool disableOfficialWhenOfficialUnusable = false,
  }) {
    unawaited(
      officialFuture.then((result) {
        if (SettingsManager().bangumiApiModeNotifier.value !=
            BangumiApiMode.auto) {
          return;
        }

        final officialValue = result.value;
        if (_isUsableValue(officialValue, isUsable) ||
            (officialValue != null && !disableOfficialWhenOfficialUnusable)) {
          _markOfficialBangumiAvailable(
            '$label official completed after proxy',
          );
          return;
        }

        _markOfficialBangumiUnavailable(
          officialValue == null
              ? '$label official request failed after proxy'
              : '$label official returned no usable data after proxy',
        );
      }),
    );
  }

  static BangumiApiMode _currentBangumiApiMode() {
    final mode = SettingsManager().bangumiApiModeNotifier.value;
    if (_lastBangumiApiMode != mode) {
      _lastBangumiApiMode = mode;
      _officialBangumiAvailable = null;
    }
    return mode;
  }

  static Future<T?> _requestPreferredBangumiEndpoint<T>({
    required Future<T?> Function() preferred,
    required Future<T?> Function() fallback,
    bool Function(T value)? isUsable,
    bool disablePreferredWhenUnusable = false,
  }) async {
    final preferredValue = await preferred();
    if (_isUsableValue(preferredValue, isUsable) ||
        (preferredValue != null && !disablePreferredWhenUnusable)) {
      return preferredValue;
    }
    return await fallback() ?? preferredValue;
  }

  static Future<T?> _requestBangumiEndpoint<T>({
    required String label,
    required Future<T?> Function() official,
    required Future<T?> Function() proxy,
    bool Function(T value)? isUsable,
    bool disableOfficialWhenOfficialUnusable = false,
  }) async {
    final mode = _currentBangumiApiMode();
    switch (mode) {
      case BangumiApiMode.officialOnly:
        return official();
      case BangumiApiMode.proxyOnly:
        return proxy();
      case BangumiApiMode.officialFirst:
        return _requestPreferredBangumiEndpoint<T>(
          preferred: official,
          fallback: proxy,
          isUsable: isUsable,
          disablePreferredWhenUnusable: disableOfficialWhenOfficialUnusable,
        );
      case BangumiApiMode.proxyFirst:
        return _requestPreferredBangumiEndpoint<T>(
          preferred: proxy,
          fallback: official,
          isUsable: isUsable,
        );
      case BangumiApiMode.auto:
        break;
    }

    if (_officialBangumiAvailable == false) {
      return proxy();
    }

    if (_officialBangumiAvailable == true) {
      final officialValue = await official();
      if (_isUsableValue(officialValue, isUsable) ||
          (officialValue != null && !disableOfficialWhenOfficialUnusable)) {
        return officialValue;
      }
      _markOfficialBangumiUnavailable(
        officialValue == null
            ? '$label official request failed'
            : '$label official returned no usable data',
      );
      return await proxy() ?? officialValue;
    }

    final officialFuture = _wrapBangumiEndpoint<T>(
      label: label,
      isOfficial: true,
      request: official,
    );
    final proxyFuture = _wrapBangumiEndpoint<T>(
      label: label,
      isOfficial: false,
      request: proxy,
    );

    final first = await Future.any([officialFuture, proxyFuture]);
    if (_isUsableValue(first.value, isUsable)) {
      if (first.isOfficial) {
        _markOfficialBangumiAvailable('$label official completed first');
      } else {
        _markOfficialBangumiUnavailable('$label proxy completed first');
        _rememberOfficialProbe<T>(
          label: label,
          officialFuture: officialFuture,
          isUsable: isUsable,
          disableOfficialWhenOfficialUnusable:
              disableOfficialWhenOfficialUnusable,
        );
      }
      return first.value;
    }

    if (first.isOfficial && first.value == null) {
      _markOfficialBangumiUnavailable('$label official request failed');
    }

    final second = await (first.isOfficial ? proxyFuture : officialFuture);
    if (_isUsableValue(second.value, isUsable)) {
      if (second.isOfficial) {
        _markOfficialBangumiAvailable('$label official completed');
      } else if (first.isOfficial &&
          (first.value == null || disableOfficialWhenOfficialUnusable)) {
        _markOfficialBangumiUnavailable(
          '$label official returned no usable data',
        );
      }
      return second.value;
    }

    if (first.value != null) {
      if (first.isOfficial && !disableOfficialWhenOfficialUnusable) {
        _markOfficialBangumiAvailable('$label official completed');
      }
      return first.value;
    }

    if (second.value != null) {
      if (second.isOfficial) {
        _markOfficialBangumiAvailable('$label official completed');
      }
      return second.value;
    }

    _markOfficialBangumiUnavailable('$label official and proxy both failed');
    return null;
  }

  static bool _shouldEnrichAnimeEpisodeCounts() {
    final mode = _currentBangumiApiMode();
    switch (mode) {
      case BangumiApiMode.proxyOnly:
      case BangumiApiMode.proxyFirst:
        return false;
      case BangumiApiMode.auto:
        return _officialBangumiAvailable == true;
      case BangumiApiMode.officialFirst:
      case BangumiApiMode.officialOnly:
        return true;
    }
  }

  /// 搜索动画条目 (由于兼容性保留此方法名)
  static Future<List<BangumiSearchResult>> searchAnime(String query) async {
    return searchSubject(query, type: 2);
  }

  /// 拉取 Bangumi 用户的公开动画收藏。
  ///
  /// 第一阶段只支持公开收藏，不处理 OAuth 私密收藏。
  static Future<List<BangumiUserCollectionItem>> fetchUserAnimeCollections(
    String username, {
    int limit = 50,
    int maxItems = 1000,
  }) async {
    final cleanUsername = username.trim();
    if (cleanUsername.isEmpty) return [];

    final items = <BangumiUserCollectionItem>[];
    var offset = 0;
    final safeLimit = limit.clamp(1, 50).toInt();
    final safeMaxItems = maxItems.clamp(safeLimit, 5000).toInt();

    while (items.length < safeMaxItems) {
      final page = await _fetchUserAnimeCollectionPage(
        cleanUsername,
        offset: offset,
        limit: safeLimit,
      );
      if (page == null) break;
      if (page.items.isEmpty) break;

      items.addAll(page.items);
      if (items.length >= page.total) break;
      if (page.items.length < page.limit) break;

      offset += page.limit;
    }

    return items.take(safeMaxItems).toList();
  }

  static Future<_BangumiCollectionPage?> _fetchUserAnimeCollectionPage(
    String username, {
    required int offset,
    required int limit,
  }) {
    final encodedUsername = Uri.encodeComponent(username);

    return _requestBangumiEndpoint<_BangumiCollectionPage>(
      label: 'Bangumi User Collections',
      official: () => _fetchUserAnimeCollectionPageFrom(
        Uri.parse('$_baseUrl/v0/users/$encodedUsername/collections'),
        offset: offset,
        limit: limit,
      ),
      proxy: () => _fetchUserAnimeCollectionPageFrom(
        Uri.parse('$_proxyBaseUrl/v0/users/$encodedUsername/collections'),
        offset: offset,
        limit: limit,
      ),
      isUsable: (_) => true,
    );
  }

  static Future<_BangumiCollectionPage?> _fetchUserAnimeCollectionPageFrom(
    Uri baseUri, {
    required int offset,
    required int limit,
  }) async {
    final url = baseUri.replace(
      queryParameters: {
        ...baseUri.queryParameters,
        'subject_type': '2',
        'limit': '$limit',
        'offset': '$offset',
      },
    );

    try {
      final response = await http
          .get(url, headers: _headers)
          .timeout(_requestTimeout);

      if (response.statusCode != 200) {
        logger.w('Bangumi User Collections Failed: ${response.statusCode}');
        return null;
      }

      final decoded = jsonDecode(utf8.decode(response.bodyBytes));
      final List<dynamic> rawList;
      int total;
      int responseLimit;

      if (decoded is Map<String, dynamic>) {
        rawList = decoded['data'] is List ? decoded['data'] as List : const [];
        total =
            BangumiUserCollectionItem._asInt(decoded['total']) ??
            rawList.length;
        responseLimit =
            BangumiUserCollectionItem._asInt(decoded['limit']) ?? limit;
      } else if (decoded is List) {
        rawList = decoded;
        total = decoded.length;
        responseLimit = limit;
      } else {
        return null;
      }

      final items = rawList
          .whereType<Map>()
          .map(
            (item) => BangumiUserCollectionItem.fromJson(
              item.map((key, value) => MapEntry(key.toString(), value)),
            ),
          )
          .where((item) => item.subjectId > 0 && item.title.trim().isNotEmpty)
          .toList();

      return _BangumiCollectionPage(
        items: items,
        total: total,
        limit: responseLimit <= 0 ? limit : responseLimit,
      );
    } catch (e) {
      logger.e('Bangumi User Collections Error: $e');
      return null;
    }
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

    final results =
        await _requestBangumiEndpoint<List<BangumiSearchResult>>(
          label: 'Bangumi Search',
          official: () => _searchSubjectWithOfficialApi(query, type: type),
          proxy: () => _searchSubjectWithProxy(query, type: type),
          isUsable: (value) => value.isNotEmpty,
        ) ??
        [];

    if (type != 2 || !_shouldEnrichAnimeEpisodeCounts()) {
      return results;
    }

    return _enrichAnimeEpisodeCounts(results);
  }

  static Future<List<BangumiSearchResult>?> _searchSubjectWithOfficialApi(
    String query, {
    required int type,
  }) async {
    final url = Uri.parse(
      '$_baseUrl/search/subject/${Uri.encodeComponent(query)}?type=$type&responseGroup=large',
    );

    try {
      final response = await http
          .get(url, headers: _headers)
          .timeout(_requestTimeout);

      if (response.statusCode != 200) {
        logger.w('Bangumi Search Failed: ${response.statusCode}');
        return null;
      }

      final data = jsonDecode(utf8.decode(response.bodyBytes));
      final rawList = data['list'];
      if (rawList is! List) return null;

      return rawList
          .whereType<Map<String, dynamic>>()
          .map(BangumiSearchResult.fromJson)
          .toList();
    } catch (e) {
      logger.e('Bangumi Search Error: $e');
      return null;
    }
  }

  static Future<List<BangumiSearchResult>?> _searchSubjectWithProxy(
    String query, {
    required int type,
  }) async {
    final url = Uri.parse(
      '$_proxyBaseUrl/v0/search/subjects',
    ).replace(queryParameters: const {'limit': '20', 'offset': '0'});

    try {
      final response = await http
          .post(
            url,
            headers: _jsonHeaders,
            body: jsonEncode({
              'keyword': query,
              'sort': 'match',
              'filter': {
                'type': [type],
              },
            }),
          )
          .timeout(_requestTimeout);

      if (response.statusCode != 200) {
        logger.w('Bangumi Proxy Search Failed: ${response.statusCode}');
        return null;
      }

      final data = jsonDecode(utf8.decode(response.bodyBytes));
      final rawList = data['data'];
      if (rawList is! List) return [];

      final results = rawList
          .whereType<Map<String, dynamic>>()
          .map(BangumiSearchResult.fromJson)
          .toList();

      return results;
    } catch (e) {
      logger.e('Bangumi Proxy Search Error: $e');
      return null;
    }
  }

  /// 按真实开播日期范围发现动画，避免把年份当作普通标签导致结果串年。
  static Future<List<BangumiSearchResult>> searchAnimeByAirDateRange({
    required String startDate,
    required String endDate,
    int offset = 0,
    int limit = 20,
  }) async {
    final results =
        await _requestBangumiEndpoint<List<BangumiSearchResult>>(
          label: 'Bangumi Air Date Search',
          official: () => _searchAnimeByAirDateRangeFrom(
            Uri.parse('$_baseUrl/v0/search/subjects'),
            startDate: startDate,
            endDate: endDate,
            offset: offset,
            limit: limit,
          ),
          proxy: () => _searchAnimeByAirDateRangeFrom(
            Uri.parse('$_proxyBaseUrl/v0/search/subjects'),
            startDate: startDate,
            endDate: endDate,
            offset: offset,
            limit: limit,
          ),
          isUsable: (value) => value.isNotEmpty,
        ) ??
        [];

    return results.where((item) {
      final airDate = item.airDate;
      if (airDate == null || airDate.isEmpty) return false;
      return airDate.compareTo(startDate) >= 0 &&
          airDate.compareTo(endDate) < 0;
    }).toList();
  }

  static Future<List<BangumiSearchResult>?> _searchAnimeByAirDateRangeFrom(
    Uri baseUri, {
    required String startDate,
    required String endDate,
    required int offset,
    required int limit,
  }) async {
    final url = baseUri.replace(
      queryParameters: {'limit': '$limit', 'offset': '$offset'},
    );

    try {
      final response = await http
          .post(
            url,
            headers: _jsonHeaders,
            body: jsonEncode({
              'keyword': '',
              'sort': 'rank',
              'filter': {
                'type': [2],
                'air_date': ['>=$startDate', '<$endDate'],
              },
            }),
          )
          .timeout(_requestTimeout);

      if (response.statusCode != 200) {
        logger.w('Bangumi Air Date Search Failed: ${response.statusCode}');
        return null;
      }

      final data = jsonDecode(utf8.decode(response.bodyBytes));
      final rawList = data['data'];
      if (rawList is! List) return [];

      return rawList
          .whereType<Map<String, dynamic>>()
          .map(BangumiSearchResult.fromJson)
          .toList();
    } catch (e) {
      logger.e('Bangumi Air Date Search Error: $e');
      return null;
    }
  }

  static Future<List<BangumiSearchResult>> _enrichAnimeEpisodeCounts(
    List<BangumiSearchResult> results,
  ) async {
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
          // Keep the search result even if episode enrichment fails.
        }
        return item;
      }),
    );

    return [...enrichedList, ...remainingList];
  }

  static Future<Map<String, int>?> _fetchEpisodeCountsFrom(Uri epsUrl) async {
    try {
      final epsResponse = await http
          .get(epsUrl, headers: _headers)
          .timeout(_requestTimeout);
      if (epsResponse.statusCode != 200) return null;

      final epsData = jsonDecode(utf8.decode(epsResponse.bodyBytes));
      final rawEpisodes = epsData['data'];
      if (rawEpisodes is! List) return null;

      var tvCount = 0;
      var spCount = 0;
      for (var e in rawEpisodes) {
        final type = e['type'];
        if (type == 0) tvCount++;
        if (type == 1) spCount++;
      }

      return {'tv': tvCount, 'sp': spCount, 'total': tvCount + spCount};
    } catch (e) {
      logger.e("Fetch Episodes Error for $epsUrl: $e");
      return null;
    }
  }

  static Future<Map<String, int>> _fetchEpisodeCounts(int id) async {
    final counts =
        await _requestBangumiEndpoint<Map<String, int>>(
          label: 'Bangumi Episodes',
          official: () => _fetchEpisodeCountsFrom(
            Uri.parse('$_baseUrl/v0/episodes?subject_id=$id'),
          ),
          proxy: () => _fetchEpisodeCountsFrom(
            Uri.parse('$_proxyBaseUrl/v0/episodes?subject_id=$id'),
          ),
          isUsable: (value) => (value['total'] ?? 0) > 0,
        ) ??
        {'tv': 0, 'sp': 0, 'total': 0};

    return counts;
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

    return await _requestBangumiEndpoint<List<BangumiSearchResult>>(
          label: 'Bangumi Tag Search',
          official: () => _searchByTagWithOfficialScraper(tag, offset: offset),
          proxy: () => _searchByTagWithProxy(tag, offset: offset, limit: limit),
          isUsable: (value) => value.isNotEmpty,
          disableOfficialWhenOfficialUnusable: true,
        ) ??
        [];
  }

  static Future<List<BangumiSearchResult>?> _searchByTagWithOfficialScraper(
    String tag, {
    required int offset,
  }) async {
    if (tag.isEmpty) return [];

    // 计算页码: Bangumi 每页 24 条
    final page = (offset / 24).floor() + 1;

    // URL: https://bgm.tv/anime/tag/{tag}?page={page}
    final url = Uri.parse(
      'https://bgm.tv/anime/tag/${Uri.encodeComponent(tag)}?page=$page',
    );

    try {
      final response = await http
          .get(url, headers: _headers)
          .timeout(_requestTimeout);

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
              coverUrl = proxyBangumiImageUrl(
                rawSrc.replaceAll(
                  RegExp(r'/pic/cover/[a-z]/'),
                  '/pic/cover/l/',
                ),
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

    return null;
  }

  static Future<List<BangumiSearchResult>?> _searchByTagWithProxy(
    String tag, {
    required int offset,
    required int limit,
  }) async {
    final url = Uri.parse(
      '$_proxyBaseUrl/v0/search/subjects',
    ).replace(queryParameters: {'limit': '$limit', 'offset': '$offset'});

    try {
      final response = await http
          .post(
            url,
            headers: _jsonHeaders,
            body: jsonEncode({
              'keyword': '',
              'sort': 'rank',
              'filter': {
                'type': [2],
                'tag': [tag],
              },
            }),
          )
          .timeout(_requestTimeout);

      if (response.statusCode != 200) {
        logger.w('Bangumi Proxy Tag Search Failed: ${response.statusCode}');
        return null;
      }

      final data = jsonDecode(utf8.decode(response.bodyBytes));
      final rawList = data['data'];
      if (rawList is! List) return [];

      return rawList
          .whereType<Map<String, dynamic>>()
          .map(BangumiSearchResult.fromJson)
          .toList();
    } catch (e) {
      logger.e('Bangumi Proxy Tag Search Error: $e');
      return null;
    }
  }

  static Future<Map<String, dynamic>?> _fetchSubjectDetail(int id) async {
    return _requestBangumiEndpoint<Map<String, dynamic>>(
      label: 'Bangumi Detail',
      official: () =>
          _fetchSubjectDetailFrom(Uri.parse('$_baseUrl/subject/$id')),
      proxy: () =>
          _fetchSubjectDetailFrom(Uri.parse('$_proxyBaseUrl/v0/subjects/$id')),
      isUsable: (value) => value.isNotEmpty,
    );
  }

  static Future<Map<String, dynamic>?> _fetchSubjectDetailFrom(Uri url) async {
    try {
      final response = await http
          .get(url, headers: _headers)
          .timeout(_requestTimeout);
      if (response.statusCode != 200) return null;

      final data = jsonDecode(utf8.decode(response.bodyBytes));
      if (data is Map<String, dynamic>) {
        return data;
      }
    } catch (e) {
      logger.e('Bangumi Detail Fetch Error: $e');
    }

    return null;
  }

  /// 获取动漫详情信息（包括制作公司、集数、简介等）
  ///
  /// [id] 动漫条目 ID
  /// 返回包含详细信息的 Map
  static Future<Map<String, dynamic>> getAnimeDetail(int id) async {
    try {
      final data = await _fetchSubjectDetail(id);
      if (data != null) {
        // 提取基本信息
        String? studio;
        String? airDate = data['date'];
        final rawEps = data['eps'] ?? data['total_episodes'];
        int? eps;
        if (rawEps is int) {
          eps = rawEps;
        } else if (rawEps is num) {
          eps = rawEps.toInt();
        } else if (rawEps is String) {
          eps = int.tryParse(rawEps);
        }
        String? summary = data['summary'];
        double? score = (data['rating']?['score'] as num?)?.toDouble();

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
    List<String>? tags,
    String? year,
    String? month,
    String? format,
    int? offset,
    int? limit,
    String? sort,
  }) async {
    try {
      // 构造请求参数
      final queryParams = <String, String>{};
      if (keyword != null && keyword.isNotEmpty) {
        queryParams['keyword'] = keyword;
      }
      if (tag != null && tag.isNotEmpty) queryParams['tag'] = tag;
      if (tags != null && tags.isNotEmpty) {
        queryParams['tags'] = tags.join(',');
      }
      if (year != null && year.isNotEmpty) queryParams['year'] = year;
      if (month != null && month.isNotEmpty) queryParams['month'] = month;
      if (format != null && format.isNotEmpty) queryParams['format'] = format;
      if (offset != null && offset >= 0) {
        queryParams['offset'] = offset.toString();
      }
      if (limit != null && limit > 0) {
        queryParams['limit'] = limit.toString();
      }
      if (sort != null && sort.isNotEmpty) queryParams['sort'] = sort;

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
              coverUrl: proxyBangumiImageUrl(item['cover_url']),
              airDate: item['air_date'],
              eps: eps,
              source: item['source'] ?? 'server',
              summary: item['summary'],
              studio: item['studio'],
              score: score,
              tags: BangumiSearchResult._parseTags(item['tags']),
              format:
                  item['format']?.toString() ??
                  item['eps_breakdown']?.toString(),
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
    final syncedCoverUrl = proxyBangumiImageUrl(coverUrl) ?? coverUrl;

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
        body: jsonEncode({'title': title, 'cover_url': syncedCoverUrl}),
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
